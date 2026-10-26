package app.openbill.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenBillBackupTest {
    private val root = Files.createTempDirectory("backup-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun location(dir: File, firmName: String, version: Int = 5): DataLocation {
        dir.mkdirs()
        val db = File(dir, "openbill.db")
        BundledSQLiteDriver().open(db.path).apply {
            execSQL("PRAGMA journal_mode=WAL")
            execSQL("CREATE TABLE firms (id TEXT PRIMARY KEY, name TEXT, createdAt INTEGER)")
            execSQL("INSERT INTO firms VALUES ('f1', '$firmName', 1)")
            execSQL("PRAGMA user_version=$version")
            close()
        }
        File(dir, "logos").mkdirs()
        File(dir, "logos/f1.png").writeText("logo-$firmName")
        File(dir, "attachments/v1").mkdirs()
        File(dir, "attachments/v1/bill.pdf").writeText("bill-$firmName")
        return DataLocation(db, mapOf("logos" to File(dir, "logos"), "attachments" to File(dir, "attachments")))
    }

    private fun firmName(db: File): String =
        BundledSQLiteDriver().open(db.path).let { c -> c.prepare("SELECT name FROM firms").use { it.step(); it.getText(0) }.also { c.close() } }

    @Test
    fun autoBackupRunsWhenDueAndKeepsTheNewest() {
        val a = location(File(root, "a"), "Alpha")
        val dir = File(root, "auto")
        val t0 = java.time.Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(OpenBillBackup.autoBackup(a, dir, "1.0", everyDays = 1, keep = 2, now = t0) != null)
        assertEquals(null, OpenBillBackup.autoBackup(a, dir, "1.0", everyDays = 1, keep = 2, now = t0.plusSeconds(3600)))
        (1L..3L).forEach { d -> OpenBillBackup.autoBackup(a, dir, "1.0", everyDays = 1, keep = 2, now = t0.plusSeconds(d * 86_400)) }
        assertEquals(listOf("auto-20260103-000000.openbill.zip", "auto-20260104-000000.openbill.zip"), dir.list()!!.sorted())
    }

    @Test
    fun encryptedArchiveNeedsItsPasswordToVerifyAndRestore() {
        val a = location(File(root, "a"), "Alpha")
        val zip = File(root, "out/a.openbill.zip")
        OpenBillBackup.create(a, zip, "1.0", password = "s3cret".toCharArray())
        assertContentEquals("OPENBILL-ENC1".toByteArray(), zip.readBytes().copyOf(13))
        assertTrue(BackupCrypto.isEncrypted(zip))
        assertFailsWith<BackupException> { OpenBillBackup.verify(zip, 5) }
        assertFailsWith<BackupException> { OpenBillBackup.verify(zip, 5, "wrong".toCharArray()) }
        assertEquals(listOf("Alpha"), OpenBillBackup.verify(zip, 5, "s3cret".toCharArray()).firms.map { it.name })
        val b = location(File(root, "b"), "Beta")
        OpenBillBackup.restore(zip, b, 5, password = "s3cret".toCharArray())
        assertEquals("Alpha", firmName(b.databaseFile))
        assertTrue(File(root, "out").list()!!.all { it == "a.openbill.zip" }, "no decrypted copy left behind")
    }

    @Test
    fun envelopeRejectsTamperingAndTruncationAtSegmentBoundaries() {
        val plain = ByteArray(2 * 64 * 1024) { it.toByte() } // exact multiple: ends with an empty final segment
        val sealed = java.io.ByteArrayOutputStream().also { BackupCrypto.encrypt(plain.inputStream(), it, "pw".toCharArray()) }.toByteArray()
        fun open(bytes: ByteArray) = java.io.ByteArrayOutputStream().also { BackupCrypto.decrypt(bytes.inputStream(), it, "pw".toCharArray()) }.toByteArray()
        assertContentEquals(plain, open(sealed))
        assertFailsWith<BackupException> { open(sealed.copyOf(sealed.size - 16)) } // drops the empty final segment
        assertFailsWith<BackupException> { open(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }) }
    }

    @Test
    fun roundTrip() {
        val a = location(File(root, "a"), "Alpha")
        val zip = File(root, "out/a.openbill.zip")
        val m = OpenBillBackup.create(a, zip, "1.0")
        assertEquals(listOf(BackupFirm("f1", "Alpha")), m.firms)
        assertEquals(5, m.schemaVersion)
        assertEquals(1L, m.recordCounts["firms"])
        assertEquals(m.files, OpenBillBackup.verify(zip, 5).files)

        val b = location(File(root, "b"), "Beta")
        val safety = OpenBillBackup.restore(zip, b, 5)
        assertEquals("Alpha", firmName(b.databaseFile))
        assertEquals("logo-Alpha", File(root, "b/logos/f1.png").readText())
        assertEquals("bill-Alpha", File(root, "b/attachments/v1/bill.pdf").readText())
        assertEquals("Beta", firmName(File(safety, "openbill.db")))
        assertEquals("logo-Beta", File(safety, "attachments/logos/f1.png").readText())
        assertTrue(File(root, "b").listFiles()!!.none { it.name.startsWith(".restore-") })
    }

    @Test
    fun corruptArchiveLeavesDataUntouched() {
        val zip = File(root, "a.openbill.zip")
        OpenBillBackup.create(location(File(root, "a"), "Alpha"), zip, "1.0")
        // Flip the logo's content while keeping the manifest: checksum must fail.
        val bad = File(root, "bad.openbill.zip")
        ZipOutputStream(bad.outputStream()).use { out ->
            ZipInputStream(zip.inputStream()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    out.putNextEntry(ZipEntry(e.name))
                    out.write(if (e.name.endsWith(".png")) "tampered".toByteArray() else bytes)
                    out.closeEntry()
                }
            }
        }
        val b = location(File(root, "b"), "Beta")
        val before = File(root, "b").walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
        val e = assertFailsWith<BackupException> { OpenBillBackup.restore(bad, b, 5) }
        assertEquals(BackupFailure.INTEGRITY, e.kind)
        val after = File(root, "b").walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
        assertEquals(before.keys, after.keys)
        before.forEach { (k, v) -> assertContentEquals(v, after[k]) }
    }

    @Test
    fun newerSchemaRefused() {
        val zip = File(root, "a.openbill.zip")
        OpenBillBackup.create(location(File(root, "a"), "Alpha", version = 9), zip, "2.0")
        assertEquals(BackupFailure.SCHEMA_TOO_NEW, assertFailsWith<BackupException> { OpenBillBackup.verify(zip, 5) }.kind)
    }

    @Test
    fun preMigrationBackupOnlyForOlderSchema() {
        val a = location(File(root, "a"), "Alpha", version = 4)
        val out = OpenBillBackup.backupBeforeMigration(a, File(root, "backups"), 5, "1.0")
        assertTrue(out != null && out.isFile)
        assertEquals(null, OpenBillBackup.backupBeforeMigration(a, File(root, "backups"), 4, "1.0"))
    }
}
