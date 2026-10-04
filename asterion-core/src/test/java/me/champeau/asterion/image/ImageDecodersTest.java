/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.champeau.asterion.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The decoders must give the pixels of ImageIO for every variant of the formats which ImageIO
 * writes: they replace it, without changing what the solver sees.
 */
class ImageDecodersTest {
    // odd sizes, which don't fill whole JPEG blocks nor whole bytes of packed pixels
    private static final int WIDTH = 101;
    private static final int HEIGHT = 67;

    @TempDir
    private Path dir;

    /**
     * An image with stars on a gradient: smooth areas and sharp details.
     *
     * @param value the value of a pixel, from its brightness in [0, 1]
     */
    private static void paint(int width, int height, int channels, java.util.function.BiConsumer<int[], double[]> value) {
        var random = new Random(42);
        var stars = new double[40][3];
        for (var star : stars) {
            star[0] = random.nextDouble() * width;
            star[1] = random.nextDouble() * height;
            star[2] = 0.3 + 0.7 * random.nextDouble();
        }
        var levels = new double[channels];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < channels; c++) {
                    var v = 0.1 + 0.2 * x / width + 0.1 * c * y / height;
                    for (var star : stars) {
                        var d2 = (x - star[0]) * (x - star[0]) + (y - star[1]) * (y - star[1]);
                        v += star[2] * Math.exp(-d2 / 3) * (1 - 0.2 * c);
                    }
                    levels[c] = Math.min(1, v + 0.02 * random.nextGaussian());
                }
                value.accept(new int[]{x, y}, levels);
            }
        }
    }

    private static BufferedImage image(int type) {
        var image = new BufferedImage(WIDTH, HEIGHT, type);
        var raster = image.getRaster();
        var max = (1 << image.getColorModel().getComponentSize(0)) - 1;
        paint(WIDTH, HEIGHT, raster.getNumBands(), (xy, levels) -> {
            for (var c = 0; c < raster.getNumBands(); c++) {
                raster.setSample(xy[0], xy[1], c, (int) Math.round(Math.max(0, levels[c]) * max));
            }
        });
        return image;
    }

    /** An image with a component color model: 16-bit color, gray with transparency, floats... */
    private static BufferedImage componentImage(int colorSpace, boolean alpha, int dataType) {
        var model = new ComponentColorModel(ColorSpace.getInstance(colorSpace), alpha, false,
                alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, dataType);
        var raster = model.createCompatibleWritableRaster(WIDTH, HEIGHT);
        var max = dataType == DataBuffer.TYPE_FLOAT ? 1.0 : dataType == DataBuffer.TYPE_USHORT ? 65535 : 255;
        paint(WIDTH, HEIGHT, raster.getNumBands(), (xy, levels) -> {
            for (var c = 0; c < raster.getNumBands(); c++) {
                raster.setSample(xy[0], xy[1], c, dataType == DataBuffer.TYPE_FLOAT ? (float) Math.max(0, levels[c])
                        : Math.round(Math.max(0, levels[c]) * max));
            }
        });
        return new BufferedImage(model, raster, false, null);
    }

    private static BufferedImage paletteImage(int bits, boolean gray) {
        var size = 1 << bits;
        var r = new byte[size];
        var g = new byte[size];
        var b = new byte[size];
        for (var i = 0; i < size; i++) {
            var level = i * 255 / (size - 1);
            r[i] = (byte) level;
            g[i] = (byte) (gray ? level : 255 - level);
            b[i] = (byte) (gray ? level : level / 2);
        }
        var model = new IndexColorModel(bits, size, r, g, b);
        var image = bits < 8
                ? new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY, model)
                : new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_INDEXED, model);
        var raster = image.getRaster();
        paint(WIDTH, HEIGHT, 1, (xy, levels) -> raster.setSample(xy[0], xy[1], 0, (int) Math.round(Math.max(0, levels[0]) * (size - 1))));
        return image;
    }

    /** A way of writing an image, with a name for the test report. */
    record Variant(
            String name,
            BufferedImage image,
            String format,
            Consumer<ImageWriteParam> param,
            Consumer<IIOMetadataNode> metadata) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Variant variant(String name, BufferedImage image, String format) {
        return new Variant(name, image, format, _ -> {
        }, null);
    }

    private static Variant variant(String name, BufferedImage image, String format, Consumer<ImageWriteParam> param) {
        return new Variant(name, image, format, param, null);
    }

    static List<Variant> variants() {
        var variants = new ArrayList<Variant>();
        // PNG
        variants.add(variant("png gray 8", image(BufferedImage.TYPE_BYTE_GRAY), "png"));
        variants.add(variant("png gray 16", image(BufferedImage.TYPE_USHORT_GRAY), "png"));
        variants.add(variant("png rgb 8", image(BufferedImage.TYPE_3BYTE_BGR), "png"));
        variants.add(variant("png rgba 8", image(BufferedImage.TYPE_4BYTE_ABGR), "png"));
        variants.add(variant("png rgb 16", componentImage(ColorSpace.CS_sRGB, false, DataBuffer.TYPE_USHORT), "png"));
        variants.add(variant("png rgba 16", componentImage(ColorSpace.CS_sRGB, true, DataBuffer.TYPE_USHORT), "png"));
        variants.add(variant("png gray alpha 8", componentImage(ColorSpace.CS_GRAY, true, DataBuffer.TYPE_BYTE), "png"));
        variants.add(variant("png gray alpha 16", componentImage(ColorSpace.CS_GRAY, true, DataBuffer.TYPE_USHORT), "png"));
        for (var bits : new int[]{1, 2, 4, 8}) {
            variants.add(variant("png gray " + bits + " bits", paletteImage(bits, true), "png"));
            variants.add(variant("png palette " + bits + " bits", paletteImage(bits, false), "png"));
        }
        for (var type : new int[]{BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_3BYTE_BGR}) {
            variants.add(
                    variant("png interlaced type " + type, image(type), "png", p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT)));
        }
        variants.add(variant("png interlaced palette 2 bits", paletteImage(2, false), "png",
                p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT)));
        // JPEG
        for (var quality : new float[]{0.5f, 0.95f}) {
            variants.add(variant("jpeg rgb " + quality, image(BufferedImage.TYPE_3BYTE_BGR), "jpeg", jpegQuality(quality)));
            variants.add(variant("jpeg gray " + quality, image(BufferedImage.TYPE_BYTE_GRAY), "jpeg", jpegQuality(quality)));
            variants.add(variant("jpeg progressive rgb " + quality, image(BufferedImage.TYPE_3BYTE_BGR), "jpeg", jpegQuality(quality)
                    .andThen(p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT))));
            variants.add(variant("jpeg progressive gray " + quality, image(BufferedImage.TYPE_BYTE_GRAY), "jpeg", jpegQuality(quality)
                    .andThen(p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT))));
        }
        for (var sampling : new int[][]{{1, 1}, {2, 1}, {1, 2}, {2, 2}}) {
            variants.add(new Variant("jpeg sampling " + sampling[0] + "x" + sampling[1], image(BufferedImage.TYPE_3BYTE_BGR), "jpeg",
                    jpegQuality(0.9f),
                    tree -> setSampling(tree, sampling[0], sampling[1])));
            variants.add(
                    new Variant("jpeg progressive sampling " + sampling[0] + "x" + sampling[1], image(BufferedImage.TYPE_3BYTE_BGR), "jpeg",
                            jpegQuality(0.9f).andThen(p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT)),
                            tree -> setSampling(tree, sampling[0], sampling[1])));
        }
        for (var interval : new int[]{1, 3, 7}) {
            variants.add(new Variant("jpeg restart " + interval, image(BufferedImage.TYPE_3BYTE_BGR), "jpeg", jpegQuality(0.8f),
                    tree -> setRestartInterval(tree, interval)));
            variants.add(new Variant("jpeg progressive restart " + interval, image(BufferedImage.TYPE_3BYTE_BGR), "jpeg",
                    jpegQuality(0.8f).andThen(p -> p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT)),
                    tree -> setRestartInterval(tree, interval)));
        }
        // TIFF
        for (var compression : new String[]{null, "LZW", "Deflate", "PackBits", "JPEG"}) {
            var types = compression != null && compression.equals("JPEG")
                    ? new int[]{BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_3BYTE_BGR}
                    : new int[]{BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_3BYTE_BGR,
                            BufferedImage.TYPE_4BYTE_ABGR};
            for (var type : types) {
                variants.add(variant("tiff " + compression + " type " + type, image(type), "tiff", tiff(compression, false)));
                variants.add(variant("tiff tiled " + compression + " type " + type, image(type), "tiff", tiff(compression, true)));
            }
        }
        variants.add(variant("tiff rgb 16", componentImage(ColorSpace.CS_sRGB, false, DataBuffer.TYPE_USHORT), "tiff", tiff("LZW", false)));
        variants.add(variant("tiff float", componentImage(ColorSpace.CS_GRAY, false, DataBuffer.TYPE_FLOAT), "tiff", tiff(null, false)));
        variants.add(variant("tiff float deflate", componentImage(ColorSpace.CS_GRAY, false, DataBuffer.TYPE_FLOAT), "tiff",
                tiff("Deflate", true)));
        variants.add(variant("tiff palette", paletteImage(8, false), "tiff", tiff("LZW", false)));
        // BMP
        variants.add(variant("bmp rgb", image(BufferedImage.TYPE_3BYTE_BGR), "bmp"));
        variants.add(variant("bmp palette 8 bits", paletteImage(8, false), "bmp"));
        variants.add(variant("bmp palette 4 bits", paletteImage(4, false), "bmp"));
        variants.add(variant("bmp palette 1 bit", paletteImage(1, false), "bmp"));
        return variants;
    }

    private static Consumer<ImageWriteParam> jpegQuality(float quality) {
        return p -> {
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
        };
    }

    private static Consumer<ImageWriteParam> tiff(String compression, boolean tiled) {
        return p -> {
            if (compression != null) {
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionType(compression);
            }
            if (tiled) {
                p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
                p.setTiling(32, 32, 0, 0);
            }
        };
    }

    private static void setSampling(IIOMetadataNode tree, int h, int v) {
        var components = tree.getElementsByTagName("componentSpec");
        for (var i = 0; i < components.getLength(); i++) {
            var component = (Element) components.item(i);
            component.setAttribute("HsamplingFactor", Integer.toString(i == 0 ? h : 1));
            component.setAttribute("VsamplingFactor", Integer.toString(i == 0 ? v : 1));
        }
    }

    private static void setRestartInterval(IIOMetadataNode tree, int interval) {
        var sequence = (IIOMetadataNode) tree.getElementsByTagName("markerSequence").item(0);
        var dri = new IIOMetadataNode("dri");
        dri.setAttribute("interval", Integer.toString(interval));
        sequence.insertBefore(dri, sequence.getFirstChild());
    }

    private Path write(Variant variant) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName(variant.format()).next();
        var param = writer.getDefaultWriteParam();
        variant.param().accept(param);
        var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(variant.image()), param);
        if (variant.metadata() != null) {
            var format = metadata.getNativeMetadataFormatName();
            var tree = (IIOMetadataNode) metadata.getAsTree(format);
            variant.metadata().accept(tree);
            metadata.setFromTree(format, tree);
        }
        var file = dir.resolve(variant.name().replace(' ', '-') + "." + variant.format());
        try (var out = ImageIO.createImageOutputStream(Files.newOutputStream(file))) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(variant.image(), null, metadata), param);
        } finally {
            writer.dispose();
        }
        return file;
    }

    @ParameterizedTest
    @MethodSource("variants")
    void decodesLikeImageIo(Variant variant) throws IOException {
        var file = write(variant);
        var reference = ImageIoReference.gray(file);
        assertNotNull(reference, "ImageIO reads " + file);
        var image = ImageLoader.load(file, 1).image();
        assertEquals(WIDTH, image.width());
        assertEquals(HEIGHT, image.height());
        assertArrayEquals(reference, image.data(), 0, variant.name());
    }

    @Test
    void binsLikeBefore() throws IOException {
        var file = write(variant("rgb", image(BufferedImage.TYPE_3BYTE_BGR), "png"));
        var reference = ImageIoReference.gray(file);
        var image = ImageLoader.load(file, 3).image();
        assertEquals(WIDTH / 3, image.width());
        assertEquals(HEIGHT / 3, image.height());
        for (var y = 0; y < image.height(); y++) {
            for (var x = 0; x < image.width(); x++) {
                var sum = 0f;
                for (var sy = 0; sy < 3; sy++) {
                    for (var sx = 0; sx < 3; sx++) {
                        sum += reference[(3 * y + sy) * WIDTH + 3 * x + sx];
                    }
                }
                assertEquals(sum, image.data()[y * image.width() + x]);
            }
        }
    }

    @Test
    void rejectsDamagedFilesCleanly() throws IOException {
        var random = new Random(7);
        for (var variant : variants()) {
            var data = Files.readAllBytes(write(variant));
            for (var damage = 0; damage < 12; damage++) {
                byte[] damaged;
                if (damage < 4) {
                    damaged = java.util.Arrays.copyOf(data, (int) (data.length * (0.2 + 0.2 * damage)));
                } else {
                    damaged = data.clone();
                    for (var i = 0; i < 1 + damage; i++) {
                        damaged[16 + random.nextInt(damaged.length - 16)] = (byte) random.nextInt();
                    }
                }
                var file = dir.resolve("damaged." + variant.format());
                Files.write(file, damaged);
                // a damaged file decodes to some image, or fails with an IOException: never another exception, nor a hang
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    try {
                        ImageLoader.load(file, 1);
                    } catch (IOException _) {
                        // expected
                    }
                }, variant.name() + ", damage " + damage);
            }
        }
    }

    @Test
    void rejectsUnknownFormats() throws IOException {
        var file = dir.resolve("text.txt");
        Files.writeString(file, "this is not an image");
        var e = assertThrows(IOException.class, () -> ImageLoader.load(file, 1));
        assertEquals("Unsupported image format: " + file + ", expected FITS, PNG, JPEG, TIFF or BMP", e.getMessage());
        Files.write(file, new byte[0]);
        assertThrows(IOException.class, () -> ImageLoader.load(file, 1));
    }

    @Test
    void readsRastersWithoutBands() {
        // the reference handles images without color channels, which no format above produces
        var raster = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, 2, 2, 1, null);
        assertEquals(1, raster.getNumBands());
    }
}
