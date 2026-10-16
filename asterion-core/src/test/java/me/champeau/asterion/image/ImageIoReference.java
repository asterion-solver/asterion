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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes images with ImageIO, the way Asterion did before it had its own decoders: the brightness
 * of a pixel is the sum of its color samples. It's the reference of the tests of the decoders.
 */
final class ImageIoReference {
    private ImageIoReference() {
    }

    /**
     * The brightness of each pixel, at full resolution, or null if ImageIO can't read the image:
     * the luma of YCbCr JPEG images, as Asterion decodes it alone, the sum of the samples otherwise.
     */
    static float[] gray(Path path) throws IOException {
        BufferedImage image;
        try {
            if (isYCbCrJpeg(path)) {
                return luma(path);
            }
            image = ImageIO.read(path.toFile());
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return image == null ? null : gray(image);
    }

    /** The first component of a JPEG file, as decoded by ImageIO before any color conversion. */
    private static float[] luma(Path path) throws IOException {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                var raster = reader.readRaster(0, null);
                var out = new float[raster.getWidth() * raster.getHeight()];
                for (var y = 0; y < raster.getHeight(); y++) {
                    for (var x = 0; x < raster.getWidth(); x++) {
                        out[y * raster.getWidth() + x] = raster.getSample(x, y, 0);
                    }
                }
                return out;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Tells if a file is a JPEG image with three components in YCbCr, the way libjpeg decides it:
     * a JFIF marker, or no Adobe marker saying otherwise and components which aren't named R, G, B.
     */
    private static boolean isYCbCrJpeg(Path path) throws IOException {
        var data = Files.readAllBytes(path);
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return false;
        }
        var jfif = false;
        var adobeTransform = -1;
        var position = 2;
        while (position < data.length - 4) {
            if ((data[position] & 0xFF) != 0xFF) {
                return false;
            }
            var marker = data[position + 1] & 0xFF;
            var length = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            if (marker == 0xE0 && length >= 7) {
                jfif |= data[position + 4] == 'J' && data[position + 5] == 'F' && data[position + 6] == 'I' && data[position + 7] == 'F';
            } else if (marker == 0xEE && length >= 14 && data[position + 4] == 'A' && data[position + 5] == 'd') {
                adobeTransform = data[position + 15] & 0xFF;
            } else if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                var count = data[position + 9] & 0xFF;
                if (count != 3) {
                    return false;
                }
                var rgb = !jfif && (adobeTransform == 0
                        || adobeTransform < 0 && data[position + 10] == 'R' && data[position + 13] == 'G' && data[position + 16] == 'B');
                return !rgb;
            } else if (marker == 0xDA) {
                return false;
            }
            position += 2 + length;
        }
        return false;
    }

    /**
     * Tells if a palette is the one ImageIO gives to gray images of less than 8 bits: their samples
     * are their gray levels, as before Asterion had its own decoders.
     */
    private static boolean grayRamp(IndexColorModel palette) {
        var size = palette.getMapSize();
        for (var i = 0; i < size; i++) {
            var level = i * 255 / (size - 1);
            if (palette.getRed(i) != level || palette.getGreen(i) != level || palette.getBlue(i) != level) {
                return false;
            }
        }
        return size <= 16;
    }

    static float[] gray(BufferedImage image) {
        var width = image.getWidth();
        var height = image.getHeight();
        var out = new float[width * height];
        if (image.getColorModel() instanceof IndexColorModel palette && !grayRamp(palette)) {
            // the samples are indices: the brightness is the one of the colors of the palette
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var rgb = image.getRGB(x, y);
                    out[y * width + x] = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                }
            }
            return out;
        }
        var raster = image.getRaster();
        var bands = raster.getNumBands();
        var colors = image.getColorModel().hasAlpha() ? bands - 1 : bands;
        var floating = raster.getDataBuffer().getDataType() == DataBuffer.TYPE_FLOAT
                || raster.getDataBuffer().getDataType() == DataBuffer.TYPE_DOUBLE;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var sum = 0f;
                for (var c = 0; c < colors; c++) {
                    sum += floating ? raster.getSampleFloat(x, y, c) : raster.getSample(x, y, c);
                }
                out[y * width + x] = sum;
            }
        }
        return out;
    }
}
