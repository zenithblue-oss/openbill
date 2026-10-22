package app.openbill.desktop

import app.openbill.ui.PlatformServices
import app.openbill.ui.invoice.InvoiceDocument
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopPlatformServices(private val dataDir: File) : PlatformServices {
    override val isDebugBuild: Boolean = System.getProperty("openbill.debug") == "true"
    override val appVersion: String = "dev"

    /** Renders the invoice to PDF, asks where to save it, and reveals the saved file's folder. */
    override suspend fun shareInvoicePdf(document: InvoiceDocument) {
        val bytes = withContext(Dispatchers.Default) { DesktopInvoicePdf.render(document) }
        val target = withContext(Dispatchers.IO) { chooseSaveFile(document.fileStem + ".pdf") }
            ?: return // user cancelled
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            // Write atomically so an interrupted export never leaves a truncated PDF.
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            revealInFolder(target)
        }
    }

    private fun chooseSaveFile(suggested: String): File? {
        val result = CompletableFuture<File?>()
        EventQueue.invokeLater {
            runCatching {
                val dialog = FileDialog(null as Frame?, "Save invoice PDF", FileDialog.SAVE)
                dialog.file = suggested
                val docs = File(System.getProperty("user.home"), "Documents")
                dialog.directory = (if (docs.isDirectory) docs else File(System.getProperty("user.home"))).absolutePath
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                result.complete(if (name == null || dir == null) null else File(dir, if (name.endsWith(".pdf", true)) name else "$name.pdf"))
            }.onFailure { result.completeExceptionally(it) }
        }
        return result.get()
    }

    /** "Open containing folder": best effort, falls back to xdg-open for the parent directory. */
    private fun revealInFolder(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(file)
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.parentFile)
            } else {
                ProcessBuilder("xdg-open", file.parentFile.absolutePath).start()
            }
        }
    }
}
