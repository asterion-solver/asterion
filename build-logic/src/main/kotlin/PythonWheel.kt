import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the wheel of a Python package which calls a native library with ctypes, for the current platform: the
 * package doesn't depend on the version of Python, so the wheel is tagged py3-none, and the library decides the
 * platforms it runs on, which the tag tells. See https://packaging.python.org/en/latest/specifications/binary-distribution-format/
 * Like the archives of Gradle, it isn't cached: it's mostly the library, which compresses little.
 */
abstract class PythonWheel : DefaultTask() {
    /** The directory of the package, whose name is the one of the package. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageDirectory: DirectoryProperty

    /** The native library, and the files it needs, which the package loads from its directory. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val libraries: ConfigurableFileCollection

    /** The description of the package, in Markdown, which PyPI shows. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readme: RegularFileProperty

    /** The files of the licenses, which the wheel holds at their paths in [licenseRoot]. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFiles: ConfigurableFileCollection

    @get:Internal
    abstract val licenseRoot: DirectoryProperty

    /** The name of the distribution, such as asterion-solver. */
    @get:Input
    abstract val distributionName: Property<String>

    /** The version of the project, converted to the versions of Python: 1.0.0-rc.1 is 1.0.0rc1. */
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val summary: Property<String>

    @get:Input
    abstract val license: Property<String>

    @get:Input
    abstract val requiresPython: Property<String>

    /** The links of the project, by label: Homepage, Documentation... */
    @get:Input
    abstract val projectUrls: MapProperty<String, String>

    @get:Input
    abstract val classifiers: ListProperty<String>

    /** The platform which the library was built for, see [nativePlatform]. */
    @get:Input
    abstract val platform: Property<String>

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val name = distributionName.get().replace(Regex("[-_.]+"), "_").lowercase()
        val version = pythonVersion(this.version.get())
        val tag = "py3-none-" + platformTag()
        val distInfo = "$name-$version.dist-info"
        val output = destinationDirectory.get().asFile
        output.listFiles()?.forEach { it.delete() }
        val wheel = File(output, "$name-$version-$tag.whl")
        val entries = LinkedHashMap<String, ByteArray>()
        val packageRoot = packageDirectory.get().asFile
        packageRoot.walkTopDown().filter { it.isFile && "__pycache__" !in it.path }.sortedBy { it.path }.forEach {
            entries[packageRoot.name + "/" + it.relativeTo(packageRoot).invariantSeparatorsPath] = it.readBytes()
        }
        libraries.files.sortedBy { it.name }.forEach { entries[packageRoot.name + "/" + it.name] = it.readBytes() }
        entries["$distInfo/METADATA"] = metadata(version).toByteArray()
        entries["$distInfo/WHEEL"] = "Wheel-Version: 1.0\nGenerator: asterion-build\nRoot-Is-Purelib: false\nTag: $tag\n".toByteArray()
        licenses().forEach { (path, file) -> entries["$distInfo/licenses/$path"] = file.readBytes() }
        // the hashes of the other entries, last
        entries["$distInfo/RECORD"] = record(entries, "$distInfo/RECORD").toByteArray()
        writeArchive(wheel, entries)
        logger.lifecycle("Wrote {}", wheel)
    }

    /** The RECORD of a wheel: the hash and the size of each entry, but the RECORD itself. */
    private fun record(entries: Map<String, ByteArray>, path: String) = buildString {
        entries.forEach { (name, content) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            append(name).append(",sha256=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                .append(',').append(content.size).append('\n')
        }
        append(path).append(",,\n")
    }

    private fun metadata(version: String) = buildString {
        append("Metadata-Version: 2.4\n")
        append("Name: ").append(distributionName.get()).append('\n')
        append("Version: ").append(version).append('\n')
        append("Summary: ").append(summary.get()).append('\n')
        append("License-Expression: ").append(license.get()).append('\n')
        licenses().forEach { (path, _) -> append("License-File: ").append(path).append('\n') }
        append("Requires-Python: ").append(requiresPython.get()).append('\n')
        projectUrls.get().forEach { (label, url) -> append("Project-URL: ").append(label).append(", ").append(url).append('\n') }
        classifiers.get().forEach { append("Classifier: ").append(it).append('\n') }
        append("Description-Content-Type: text/markdown\n\n")
        append(readme.get().asFile.readText())
    }

    /** The files of the licenses, by their paths. */
    private fun licenses() = licenseFiles.files.map { it.relativeTo(licenseRoot.get().asFile).invariantSeparatorsPath to it }
        .sortedBy { it.first }

    /**
     * The tag of the platforms where the library runs: the oldest glibc it needs on Linux, the oldest macOS it
     * supports, and Windows.
     */
    private fun platformTag(): String {
        val (os, arch) = platform.get().split("-", limit = 2)
        val library = libraries.files.first { it.name.startsWith("libasterion.") }
        return when (os) {
            "linux" -> {
                val glibc = glibcVersion(library)
                "manylinux_${glibc.first}_${glibc.second}_" + (if (arch == "aarch_64") "aarch64" else arch)
            }
            "osx" -> {
                val (major, minor) = macosVersion(library)
                // pip only looks for the major versions of macOS 11 and later: 13.2 needs 14
                val version = if (major < 11) "${major}_$minor" else "${if (minor > 0) major + 1 else major}_0"
                "macosx_${version}_" + (if (arch == "aarch_64") "arm64" else arch)
            }
            "windows" -> if (arch == "aarch_64") "win_arm64" else "win_amd64"
            else -> throw IllegalStateException("Unknown platform ${platform.get()}")
        }
    }
}

/**
 * Writes a zip archive whose entries tell their sizes and checksums before their data, as PyPI requires, see
 * https://docs.pypi.org/archives: ZipOutputStream writes them after the data of compressed entries, in data
 * descriptors, unless they're known in advance. It checks that they match what it writes.
 */
internal fun writeArchive(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        for ((path, content) in entries) {
            // compressed as ZipOutputStream compresses, to know the size it writes
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(content)
            deflater.finish()
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                deflater.deflate(buffer)
            }
            val entry = ZipEntry(path).apply {
                timeLocal = FIXED_TIME
                size = content.size.toLong()
                compressedSize = deflater.bytesWritten
                crc = CRC32().apply { update(content) }.value
            }
            deflater.end()
            zip.putNextEntry(entry)
            zip.write(content)
            zip.closeEntry()
        }
    }
}

/** The time of the entries of the wheel, as Gradle sets it: the same archive for the same content. */
private val FIXED_TIME: LocalDateTime = LocalDateTime.of(1980, 2, 1, 0, 0)

/**
 * The version of a project for Python: 1.0.0 stays, 1.0.0-rc.1 is 1.0.0rc1, 1.0.0-SNAPSHOT is 1.0.0.dev0. The
 * release workflow checks the versions it releases the same way.
 */
internal fun pythonVersion(version: String): String {
    val match = Regex("""(\d+\.\d+\.\d+)(?:-SNAPSHOT|-(alpha|beta|rc)\.?(0|[1-9]\d*))?""").matchEntire(version)
        ?: throw IllegalArgumentException("The version $version has no equivalent for Python")
    val (release, qualifier, number) = match.destructured
    return when {
        version.endsWith("-SNAPSHOT") -> "$release.dev0"
        qualifier.isEmpty() -> release
        else -> release + mapOf("alpha" to "a", "beta" to "b", "rc" to "rc").getValue(qualifier) + number
    }
}

/** The newest version of glibc which a library of Linux needs: the names of the versions of its symbols say it. */
private fun glibcVersion(library: File): Pair<Int, Int> {
    val versions = Regex("""GLIBC_(\d+)\.(\d+)""").findAll(String(library.readBytes(), Charsets.ISO_8859_1))
        .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        .toList()
    return versions.maxWithOrNull(compareBy({ it.first }, { it.second }))
        ?: throw IllegalStateException("$library doesn't tell which glibc it needs")
}

/** The oldest version of macOS which a Mach-O library supports, from its LC_BUILD_VERSION command. */
private fun macosVersion(library: File): Pair<Int, Int> {
    val buffer = ByteBuffer.wrap(library.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(buffer.getInt(0) == 0xFEEDFACF.toInt()) { "$library isn't a 64-bit Mach-O library" }
    var offset = 32
    repeat(buffer.getInt(16)) {
        val command = buffer.getInt(offset)
        // LC_BUILD_VERSION: platform, then the minimum version, encoded as xxxx.yy.zz
        if (command == 0x32) {
            val minimum = buffer.getInt(offset + 12)
            return (minimum ushr 16) to ((minimum ushr 8) and 0xFF)
        }
        offset += buffer.getInt(offset + 4)
    }
    throw IllegalStateException("$library doesn't tell the oldest version of macOS it supports")
}
