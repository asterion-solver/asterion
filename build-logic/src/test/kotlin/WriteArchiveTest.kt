import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.random.Random

class WriteArchiveTest {
    @TempDir
    lateinit var directory: File

    private val entries = linkedMapOf(
        "package/empty.py" to ByteArray(0),
        "package/__init__.py" to "print('hello')\n".toByteArray(),
        "package/library.so" to ByteArray(3_000_000) { (it % 251).toByte() } + Random(42).nextBytes(500_000),
    )

    @Test
    fun `entries tell their sizes before their data, as PyPI requires`() {
        val archive = File(directory, "test.whl")
        writeArchive(archive, entries)

        // the local headers, one after the other: bit 3 of their flags announces a data descriptor
        val bytes = ByteBuffer.wrap(archive.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        val names = mutableListOf<String>()
        while (bytes.getInt(offset) == 0x04034B50) {
            assertEquals(0, bytes.getShort(offset + 6).toInt() and 0x08, "data descriptor at $offset")
            val compressedSize = bytes.getInt(offset + 18)
            val nameLength = bytes.getShort(offset + 26).toInt()
            val extraLength = bytes.getShort(offset + 28).toInt()
            names += String(archive.readBytes(), offset + 30, nameLength)
            offset += 30 + nameLength + extraLength + compressedSize
        }
        assertEquals(entries.keys.toList(), names)
    }

    @Test
    fun `entries are the given ones`() {
        val archive = File(directory, "test.whl")
        writeArchive(archive, entries)

        ZipFile(archive).use { zip ->
            entries.forEach { (path, content) -> assertArrayEquals(content, zip.getInputStream(zip.getEntry(path)).readBytes(), path) }
        }
    }
}
