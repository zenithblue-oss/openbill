package app.openbill.desktop

import app.openbill.core.backup.BackupCrypto
import app.openbill.core.backup.DataLocation
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.ui.more.BackupService
import app.openbill.ui.more.BackupSummary
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun desktopDataLocation(dataDir: File) = DataLocation(
    File(dataDir, "openbill.db"),
    mapOf("logos" to File(dataDir, "logos"), "attachments" to File(dataDir, "attachments")),
)

/** Backup/restore with native file dialogs. Restore closes the database, swaps the files and exits. */
internal class DesktopBackupService(
    private val dataDir: File,
    private val appVersion: String,
    private val closeDatabase: () -> Unit,
) : BackupService {
    override suspend fun createBackup(password: String?): String? {
        val target = withContext(Dispatchers.IO) {
            chooseFile("Save backup", FileDialog.SAVE, OpenBillBackup.defaultFileName("openbill", Instant.now()))
        } ?: return null
        val out = if (target.name.endsWith(".zip")) target else File(target.path + ".openbill.zip")
        withContext(Dispatchers.IO) { OpenBillBackup.create(desktopDataLocation(dataDir), out, appVersion, password = password?.toCharArray()) }
        return "Backup saved to ${out.name}"
    }

    override suspend fun pickBackup(askPassword: suspend () -> String?): BackupSummary? {
        val file = withContext(Dispatchers.IO) { chooseFile("Choose a backup", FileDialog.LOAD, null) } ?: return null
        val password = if (withContext(Dispatchers.IO) { BackupCrypto.isEncrypted(file) }) askPassword() ?: return null else null
        val m = withContext(Dispatchers.IO) { OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, password?.toCharArray()) }
        return BackupSummary(file, m.firms.map { it.name }, m.createdAt, m.appVersion, m.recordCounts, password)
    }

    override suspend fun restore(summary: BackupSummary) {
        val file = summary.handle as File
        // Verify again before closing anything: a failure here leaves the app running untouched.
        withContext(Dispatchers.IO) { OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, summary.password?.toCharArray()) }
        closeDatabase()
        val result = withContext(Dispatchers.IO) {
            runCatching { OpenBillBackup.restore(file, desktopDataLocation(dataDir), OPENBILL_DATABASE_VERSION, password = summary.password?.toCharArray()) }
        }
        val text = result.fold(
            { "Backup restored. The previous data is kept in\n$it\n\nOpenBill will now close; start it again." },
            { "Restore failed and your data was left as it was:\n${it.message}\n\nOpenBill will now close; start it again." },
        )
        EventQueue.invokeAndWait { JOptionPane.showMessageDialog(Frame.getFrames().firstOrNull { it.isVisible }, text, "OpenBill", JOptionPane.INFORMATION_MESSAGE) }
        exitProcess(0)
    }

    private fun chooseFile(title: String, mode: Int, suggested: String?): File? {
        val result = CompletableFuture<File?>()
        EventQueue.invokeLater {
            runCatching {
                val dialog = FileDialog(Frame.getFrames().firstOrNull { it.isVisible }, title, mode)
                suggested?.let { dialog.file = it }
                if (mode == FileDialog.LOAD) dialog.setFilenameFilter { _, name -> name.endsWith(".zip", true) }
                val docs = File(System.getProperty("user.home"), "Documents")
                dialog.directory = (if (docs.isDirectory) docs else File(System.getProperty("user.home"))).absolutePath
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                result.complete(if (name == null || dir == null) null else File(dir, name))
            }.onFailure { result.completeExceptionally(it) }
        }
        return result.get()
    }
}
