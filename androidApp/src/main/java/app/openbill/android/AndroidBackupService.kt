package app.openbill.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.openbill.core.backup.BackupCrypto
import app.openbill.core.backup.DataLocation
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.ui.more.BackupService
import app.openbill.ui.more.BackupSummary
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun androidDataLocation(context: Context, databaseName: String) =
    DataLocation(
        context.getDatabasePath(databaseName),
        mapOf("logos" to File(context.filesDir, "logos"), "attachments" to File(context.filesDir, "attachments")),
    )

/**
 * Backup to a user-chosen document (SAF CreateDocument) and restore from one (OpenDocument).
 * Archives are staged in the cache directory because the core works on files.
 */
class AndroidBackupService(
    private val context: Context,
    private val databaseName: String,
    private val appVersion: String,
    private val closeDatabase: () -> Unit,
    private val createDocument: suspend (suggestedName: String) -> Uri?,
    private val openDocument: suspend () -> Uri?,
) : BackupService {
    private val stage get() = File(context.cacheDir, "backup").apply { mkdirs() }

    override suspend fun createBackup(password: String?): String? {
        val name = OpenBillBackup.defaultFileName("openbill", Instant.now())
        val uri = createDocument(name) ?: return null
        withContext(Dispatchers.IO) {
            val tmp = File(stage, name)
            try {
                OpenBillBackup.create(androidDataLocation(context, databaseName), tmp, appVersion, password = password?.toCharArray())
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not open the chosen location")
            } finally {
                tmp.delete()
            }
        }
        return "Backup saved"
    }

    override suspend fun pickBackup(askPassword: suspend () -> String?): BackupSummary? {
        val uri = openDocument() ?: return null
        val file = withContext(Dispatchers.IO) {
            stage.listFiles()?.forEach { it.delete() }
            val file = File(stage, "restore.openbill.zip")
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: error("Could not read the chosen file")
            file
        }
        val password = if (BackupCrypto.isEncrypted(file)) askPassword() ?: run { file.delete(); return null } else null
        return withContext(Dispatchers.IO) {
            val m = runCatching { OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, password?.toCharArray()) }
                .getOrElse { file.delete(); throw it }
            BackupSummary(file, m.firms.map { it.name }, m.createdAt, m.appVersion, m.recordCounts, password)
        }
    }

    override suspend fun restore(summary: BackupSummary) {
        val file = summary.handle as File
        withContext(Dispatchers.IO) { OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, summary.password?.toCharArray()) }
        closeDatabase()
        withContext(Dispatchers.IO) {
            // On failure the swap is rolled back; either way the process restarts on a fresh database handle.
            runCatching { OpenBillBackup.restore(file, androidDataLocation(context, databaseName), OPENBILL_DATABASE_VERSION, password = summary.password?.toCharArray()) }
            file.delete()
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
