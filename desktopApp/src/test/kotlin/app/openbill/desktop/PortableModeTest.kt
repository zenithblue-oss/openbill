package app.openbill.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortableModeTest {
    @Test
    fun flagBesideExecutableSelectsDataDirThere() {
        val dir = Files.createTempDirectory("portable").toFile()
        val exe = File(dir, "OpenBill").apply { writeText("") }.path
        assertNull(portableDataDir(exe))
        File(dir, "portable.flag").writeText("")
        assertEquals(File(dir, "openbill-data").absoluteFile, portableDataDir(exe))
        assertNull(portableDataDir(null))
        dir.deleteRecursively()
    }
}
