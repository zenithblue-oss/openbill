package app.openbill.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.openbill.ui.PlatformServices
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.PdfAction
import app.openbill.ui.pdf.PdfBoxPainter
import app.openbill.ui.pdf.PdfFonts
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.print.PrinterJob
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.printing.PDFPageable

internal class DesktopPlatformServices(private val dataDir: File) : PlatformServices {
    override val isDebugBuild: Boolean = System.getProperty("openbill.debug") == "true"
    // jpackage launchers (the installed .msi/.exe/.deb/AppImage builds) set this property;
    // only `gradlew run` falls back to "dev".
    override val appVersion: String = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"

    override val pdfFontMetrics: FontMetrics get() = PdfFonts.metrics

    override suspend fun invoicePdfAction(document: InvoiceDocument, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(document.fileStem, "${document.title} ${document.number}", layout, action)

    override suspend fun pdfAction(fileName: String, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(fileName, fileName, layout, action)

    private suspend fun pdfAction(stem: String, title: String, layout: InvoiceLayout, action: PdfAction): String? = when (action) {
        // Desktop has no share sheet: "Share" saves the file and reveals it for attaching.
        PdfAction.SHARE, PdfAction.SAVE -> {
            val bytes = withContext(Dispatchers.Default) { PdfBoxPainter.toPdf(layout) }
            val target = withContext(Dispatchers.IO) { chooseSaveFile("$stem.pdf") }
            if (target == null) {
                null // user cancelled
            } else {
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
                "Saved ${target.name}"
            }
        }
        PdfAction.PRINT -> withContext(Dispatchers.IO) {
            PdfBoxPainter.document(layout, subsetFonts = false).use { doc ->
                val job = PrinterJob.getPrinterJob()
                job.jobName = title
                job.setPageable(PDFPageable(doc))
                if (job.printDialog()) {
                    job.print()
                    "Sent to the printer"
                } else {
                    null
                }
            }
        }
        PdfAction.OPEN -> withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "openbill-pdf").apply { mkdirs() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
            val file = File(dir, "$stem.pdf")
            file.writeBytes(PdfBoxPainter.toPdf(layout))
            val opened = runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file)
                } else {
                    ProcessBuilder("xdg-open", file.absolutePath).start()
                }
            }.isSuccess
            if (opened) null else "No PDF viewer found. The file is at ${file.absolutePath}"
        }
    }

    override val supportsBulkPdf: Boolean get() = true

    override suspend fun bulkPdf(pdfs: List<Pair<String, suspend () -> InvoiceLayout>>, print: Boolean, onProgress: (Int) -> Unit): String? {
        // One dialog up front (folder or printer); then a sequential loop with per-invoice failures.
        val dir = if (print) null else withContext(Dispatchers.IO) { chooseFolder() } ?: return null
        val printer = if (print) withContext(Dispatchers.IO) { PrinterJob.getPrinterJob().takeIf { it.printDialog() }?.printService } ?: return null else null
        val failed = mutableListOf<String>()
        pdfs.forEachIndexed { i, (stem, render) ->
            currentCoroutineContext().ensureActive()
            runCatching {
                val layout = render()
                withContext(Dispatchers.IO) {
                    if (dir != null) {
                        val tmp = File(dir, "$stem.pdf.tmp")
                        tmp.writeBytes(PdfBoxPainter.toPdf(layout))
                        java.nio.file.Files.move(tmp.toPath(), File(dir, "$stem.pdf").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        PdfBoxPainter.document(layout, subsetFonts = false).use { doc ->
                            val job = PrinterJob.getPrinterJob()
                            job.printService = printer
                            job.jobName = stem
                            job.setPageable(PDFPageable(doc))
                            job.print()
                        }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed += stem }
            onProgress(i + 1)
        }
        if (dir != null) File(dir, "${pdfs.firstOrNull()?.first}.pdf").takeIf { it.isFile }?.let(::revealInFolder)
        val done = pdfs.size - failed.size
        val head = if (print) "Sent $done to the printer" else "Saved $done PDFs to ${dir!!.name}"
        return if (failed.isEmpty()) head else "$head; failed: ${failed.joinToString()}"
    }

    private fun chooseFolder(): File? {
        val result = CompletableFuture<File?>()
        EventQueue.invokeLater {
            runCatching {
                val chooser = javax.swing.JFileChooser(File(System.getProperty("user.home")))
                chooser.dialogTitle = "Export invoice PDFs to folder"
                chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                val owner = Frame.getFrames().firstOrNull { it.isVisible }
                result.complete(if (chooser.showSaveDialog(owner) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null)
            }.onFailure { result.completeExceptionally(it) }
        }
        return result.get()
    }

    override suspend fun saveTextFile(fileName: String, text: String): String? {
        val target = withContext(Dispatchers.IO) { chooseSaveFile(fileName) } ?: return null
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            revealInFolder(target)
        }
        return "Saved ${target.name}"
    }

    /** Desktop has no share sheet: the text goes to the clipboard for pasting into WhatsApp Web or mail. */
    override suspend fun shareText(subject: String, text: String): String? {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        return "$subject copied to the clipboard"
    }

    override suspend fun renderPagePreview(layout: InvoiceLayout, pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) { PdfBoxPainter.preview(layout, pageIndex, widthPx).toComposeImageBitmap() }

    override suspend fun pickImage(maxEdgePx: Int): LogoImage? {
        val file = withContext(Dispatchers.IO) { chooseOpenFile("Choose an image (PNG or JPG)", "png", "jpg", "jpeg") } ?: return null
        return withContext(Dispatchers.IO) {
            val src = ImageIO.read(file) ?: error("not a PNG or JPEG image")
            downscaleToPng(src, maxEdgePx)
        }
    }

    private fun imageFile(firmId: String, kind: ImageKind): File {
        val safe = firmId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(dataDir, "logos"), if (kind == ImageKind.LOGO) "$safe.png" else "$safe-signature.png")
    }

    override suspend fun loadFirmImage(firmId: String, kind: ImageKind): LogoImage? = withContext(Dispatchers.IO) {
        val f = imageFile(firmId, kind)
        if (!f.isFile) return@withContext null
        val bytes = f.readBytes()
        val img = ImageIO.read(bytes.inputStream()) ?: return@withContext null
        LogoImage(bytes, img.width, img.height)
    }

    override suspend fun saveFirmImage(firmId: String, kind: ImageKind, image: LogoImage?) = withContext(Dispatchers.IO) {
        val f = imageFile(firmId, kind)
        if (image == null) {
            f.delete()
        } else {
            f.parentFile.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(image.bytes)
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    override suspend fun openTextFile(): String? =
        withContext(Dispatchers.IO) { chooseOpenFile("Choose a CSV file", "csv", "txt")?.readText() }

    private fun chooseOpenFile(title: String, vararg exts: String): File? {
        val result = CompletableFuture<File?>()
        EventQueue.invokeLater {
            runCatching {
                val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> exts.any { name.lowercase().endsWith(".$it") } }
                if (System.getProperty("os.name").lowercase().contains("win")) dialog.file = exts.joinToString(";") { "*.$it" }
                dialog.directory = System.getProperty("user.home")
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                result.complete(if (name == null || dir == null) null else File(dir, name))
            }.onFailure { result.completeExceptionally(it) }
        }
        return result.get()
    }

    private fun chooseSaveFile(suggested: String): File? {
        val result = CompletableFuture<File?>()
        EventQueue.invokeLater {
            runCatching {
                // Owned by the app window so focus returns to it when the dialog closes; with no
                // owner the window was left without keyboard focus (Esc/Ctrl shortcuts dead).
                val owner = Frame.getFrames().firstOrNull { it.isVisible }
                val ext = suggested.substringAfterLast('.', "pdf")
                val dialog = FileDialog(owner, "Save ${ext.uppercase()}", FileDialog.SAVE)
                dialog.file = suggested
                val docs = File(System.getProperty("user.home"), "Documents")
                dialog.directory = (if (docs.isDirectory) docs else File(System.getProperty("user.home"))).absolutePath
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                result.complete(if (name == null || dir == null) null else File(dir, if (name.endsWith(".$ext", true)) name else "$name.$ext"))
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

    companion object {
        /** Scales so the long edge is at most [maxEdge] px (never up) and encodes PNG, keeping transparency. */
        fun downscaleToPng(src: BufferedImage, maxEdge: Int): LogoImage {
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(src.width, src.height))
            val w = maxOf(1, (src.width * scale).toInt())
            val h = maxOf(1, (src.height * scale).toInt())
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
            g.dispose()
            val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
            return LogoImage(bytes, w, h)
        }
    }
}
