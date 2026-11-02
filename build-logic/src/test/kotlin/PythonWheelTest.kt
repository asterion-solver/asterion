import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PythonWheelTest {
    @Test
    fun `versions are written the way of Python`() {
        assertEquals("1.0.0", pythonVersion("1.0.0"))
        assertEquals("1.0.0.dev0", pythonVersion("1.0.0-SNAPSHOT"))
        assertEquals("1.0.0rc1", pythonVersion("1.0.0-rc.1"))
        assertEquals("1.2.3a2", pythonVersion("1.2.3-alpha.2"))
        assertEquals("1.2.3b10", pythonVersion("1.2.3-beta10"))
    }

    @Test
    fun `versions without an equivalent are refused`() {
        for (version in listOf("1.0.0-M1", "1.0.0-rc", "1.0.0-rc.01", "1.0", "1.0.0-RC.1")) {
            assertThrows<IllegalArgumentException>(version) { pythonVersion(version) }
        }
    }
}
