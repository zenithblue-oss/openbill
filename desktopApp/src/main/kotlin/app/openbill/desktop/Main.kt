package app.openbill.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.openBillDatabase
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import androidx.compose.runtime.CompositionLocalProvider
import app.openbill.ui.more.LocalBackupService
import app.openbill.ui.AppContainer
import app.openbill.ui.OpenBillApp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.openbill.ui.theme.BrandLogo
import java.awt.Dimension
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** docs/07-desktop.md section 5: `${XDG_DATA_HOME:-$HOME/.local/share}/openbill` on Linux. */
internal fun dataDirectory(): File {
    System.getProperty("openbill.dataDir")?.takeIf { it.isNotBlank() }?.let { return File(it).apply { mkdirs() } }
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        "win" in os -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "OpenBill") }
            ?: File(home, "AppData/Local/OpenBill")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "openbill")
    }
    base.mkdirs()
    runCatching {
        if (!("win" in os)) java.nio.file.Files.setPosixFilePermissions(
            base.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
    }
    // One-time migration from the pre-D1 location (~/.openbill).
    val db = File(base, "openbill.db")
    val legacy = File(home, ".openbill")
    if (!db.exists() && File(legacy, "openbill.db").exists()) {
        legacy.listFiles { f -> f.name.startsWith("openbill.db") && !f.name.endsWith(".lck") }
            ?.forEach { runCatching { it.copyTo(File(base, it.name)) } }
    }
    return base
}

/** Single-writer protection: a second launch exits with a message instead of corrupting the database. */
private fun acquireSingleInstanceLock(dataDir: File): FileChannel? {
    val channel = FileChannel.open(
        File(dataDir, "openbill.instance.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
    )
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
    return if (lock != null) channel else null
}

fun main() {
    val dataDir = dataDirectory()
    val lockChannel = acquireSingleInstanceLock(dataDir)
    if (lockChannel == null) {
        System.err.println("OpenBill is already running for data directory $dataDir; exiting.")
        exitProcess(1)
    }
    val platform = DesktopPlatformServices(dataDir)
    // Automatic pre-migration backup (docs/03-architecture.md section 14); best effort.
    runCatching {
        OpenBillBackup.backupBeforeMigration(desktopDataLocation(dataDir), File(dataDir, "backups"), OPENBILL_DATABASE_VERSION, platform.appVersion)
    }.onFailure { System.err.println("Pre-migration backup failed: ${it.message}") }
    val database = openBillDatabase(File(dataDir, "openbill.db").absolutePath)
    val container = AppContainer(database.roomRepositories(), platform)
    val backupService = DesktopBackupService(dataDir, platform.appVersion) { database.close() }
    application {
        val windowState = rememberWindowState(size = DpSize(1100.dp, 760.dp))
        Window(
            onCloseRequest = ::exitApplication,
            title = "OpenBill",
            icon = rememberVectorPainter(BrandLogo.Mark),
            state = windowState,
        ) {
            window.minimumSize = Dimension(1024, 700)
            CompositionLocalProvider(LocalBackupService provides backupService) {
                OpenBillApp(container)
            }
        }
    }
}
