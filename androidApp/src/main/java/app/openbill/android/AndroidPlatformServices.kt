package app.openbill.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import app.openbill.ui.PlatformServices
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.PdfAction
import app.openbill.ui.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hooks the current activity provides for flows that need activity results. */
class ActivityHooks(
    val activity: ComponentActivity,
    val pickImage: suspend () -> Uri?,
    val createPdf: suspend (suggestedName: String) -> Uri?,
)

class AndroidPlatformServices(
    private val context: Context,
    override val isDebugBuild: Boolean,
    override val appVersion: String,
) : PlatformServices {

    /** UI preferences (theme) in a private SharedPreferences file; business data stays in Room. */
    override val settingsStore: SettingsStore = object : SettingsStore {
        private val prefs = context.getSharedPreferences("openbill_ui", Context.MODE_PRIVATE)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private val painter by lazy { AndroidPdfPainter(context) }
    // Strong reference: nothing else holds the hooks object, so a weak one is collected and every
    // activity-result flow (photo picker, Save as, Print) fails. detach() clears it with the activity.
    private var hooks: ActivityHooks? = null

    fun attach(h: ActivityHooks) {
        hooks = h
    }

    fun detach(activity: ComponentActivity) {
        if (hooks?.activity === activity) hooks = null
    }

    private fun requireHooks() = hooks ?: error("The app window is not ready")

    override val pdfFontMetrics: FontMetrics get() = painter.metrics

    private suspend fun writeCachedPdf(stem: String, layout: InvoiceLayout): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Keep only the latest few exports in the cache.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        val out = File(dir, "$stem.pdf")
        FileOutputStream(out).use { painter.writePdf(layout, it) }
        out
    }

    override suspend fun shareText(subject: String, text: String): String? {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    override suspend fun invoicePdfAction(document: InvoiceDocument, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(document.fileStem, "${document.title} ${document.number}", layout, action)

    override suspend fun pdfAction(fileName: String, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(fileName, fileName, layout, action)

    private suspend fun pdfAction(stem: String, title: String, layout: InvoiceLayout, action: PdfAction): String? {
        when (action) {
            PdfAction.SHARE -> {
                val uri = uriFor(writeCachedPdf(stem, layout))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share $title").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
                return null
            }
            PdfAction.OPEN -> {
                val uri = uriFor(writeCachedPdf(stem, layout))
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(view)
                    null
                } catch (_: ActivityNotFoundException) {
                    "No app to open PDFs is installed. Use Share or Save as instead."
                }
            }
            PdfAction.PRINT -> {
                val file = writeCachedPdf(stem, layout)
                val activity = requireHooks().activity
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = title
                // Default the dialog to the document's own paper size (A4, A5 or a roll) instead of the
                // locale default (Letter), so the preview is not shrunk or letterboxed.
                val first = layout.pages.first()
                val mils = { pt: Float -> (pt * 1000f / 72f).toInt() }
                val media = PrintAttributes.MediaSize("openbill", "Invoice", mils(first.width), mils(first.height))
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(media)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                manager.print(name, FilePrintAdapter(file, name, layout.pages.size), attrs)
                return null
            }
            PdfAction.SAVE -> {
                val uri = requireHooks().createPdf("$stem.pdf") ?: return null
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { painter.writePdf(layout, it) }
                        ?: error("Could not open the chosen location")
                }
                return "PDF saved"
            }
        }
    }

    override suspend fun renderPagePreview(layout: InvoiceLayout, pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) { painter.renderBitmap(layout, pageIndex, widthPx).asImageBitmap() }

    override suspend fun pickImage(maxEdgePx: Int): LogoImage? {
        val uri = requireHooks().pickImage() ?: return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a supported image" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("not a supported image")
            val scale = minOf(1f, maxEdgePx.toFloat() / maxOf(decoded.width, decoded.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
            } else {
                decoded
            }
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            LogoImage(bytes, scaled.width, scaled.height)
        }
    }

    private fun imageFile(firmId: String, kind: ImageKind): File {
        val safe = firmId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(context.filesDir, "logos"), if (kind == ImageKind.LOGO) "$safe.png" else "$safe-signature.png")
    }

    override suspend fun loadFirmImage(firmId: String, kind: ImageKind): LogoImage? = withContext(Dispatchers.IO) {
        val f = imageFile(firmId, kind)
        if (!f.isFile) return@withContext null
        val bytes = f.readBytes()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else LogoImage(bytes, o.outWidth, o.outHeight)
    }

    override suspend fun saveFirmImage(firmId: String, kind: ImageKind, image: LogoImage?) = withContext(Dispatchers.IO) {
        val f = imageFile(firmId, kind)
        if (image == null) {
            f.delete()
        } else {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(image.bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
        Unit
    }
}

/** Hands an already generated PDF file to the Android print framework. */
private class FilePrintAdapter(private val file: File, private val name: String, private val pages: Int) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("$name.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        runCatching {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
        }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
            .onFailure { callback.onWriteFailed(it.message) }
    }
}
