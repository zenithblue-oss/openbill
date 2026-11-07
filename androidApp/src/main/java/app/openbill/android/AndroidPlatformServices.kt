package app.openbill.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import app.openbill.core.backup.AttachmentStore
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
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Hooks the current activity provides for flows that need activity results. */
class ActivityHooks(
    val activity: ComponentActivity,
    val pickImage: suspend () -> Uri?,
    val createPdf: suspend (suggestedName: String) -> Uri?,
    /** Save dialog for any file; the MIME type follows the name's extension. */
    val createFile: suspend (suggestedName: String) -> Uri?,
    /** Open dialog for a document of the given MIME types. */
    val openFile: suspend (mimeTypes: Array<String>) -> Uri?,
    /** Runtime permission prompt; true when granted. */
    val requestPermission: suspend (permission: String) -> Boolean,
    /** Camera app preview capture (TakePicturePreview, no FileProvider); null if cancelled. */
    val capturePhoto: suspend () -> android.graphics.Bitmap? = { null },
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

    override val supportsBiometric: Boolean
        get() = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun biometricUnlock(): Boolean {
        val activity = requireHooks().activity as? FragmentActivity ?: return false
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock OpenBill")
                    .setNegativeButtonText("Use PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    override val supportsThermalPrint: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    private suspend fun bluetooth(): BluetoothAdapter {
        if (Build.VERSION.SDK_INT >= 31 && !requireHooks().requestPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            error("Allow Nearby devices to print over Bluetooth")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("This device has no Bluetooth")
        if (!adapter.isEnabled) error("Turn on Bluetooth first")
        return adapter
    }

    /** Paired devices as "Name · address"; the user picks the printer (classes vary by vendor). */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested in bluetooth()
    override suspend fun thermalPrinters(): List<String> =
        bluetooth().bondedDevices.orEmpty().map { "${it.name ?: "Unnamed"} · ${it.address}" }.sorted()

    @SuppressLint("MissingPermission")
    override suspend fun thermalPrint(printer: String, bytes: ByteArray): String? {
        val device = bluetooth().getRemoteDevice(printer.substringAfterLast(" · "))
        withContext(Dispatchers.IO) {
            // Serial Port Profile, which Bluetooth ESC/POS printers expose.
            device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")).use { socket ->
                socket.connect()
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                Thread.sleep(500) // some printers drop the tail of the job when the link closes at once
            }
        }
        return "Sent to ${printer.substringBeforeLast(" · ")}"
    }

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

    override suspend fun openTextFile(): String? {
        val uri = requireHooks().openFile(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel")) ?: return null
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Could not open the chosen file")
        }
    }

    override suspend fun saveTextFile(fileName: String, text: String): String? = saveFile(fileName, text.toByteArray(Charsets.UTF_8))

    override suspend fun saveFile(fileName: String, bytes: ByteArray): String? {
        val uri = requireHooks().createFile(fileName) ?: return null
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Could not open the chosen location")
        }
        return "Saved $fileName"
    }

    override suspend fun shareFiles(subject: String, files: List<Pair<String, ByteArray>>, email: Boolean): String? {
        if (files.isEmpty()) return "Nothing to share"
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (email && mailto.resolveActivity(context.packageManager) == null) return "No email app is installed. Use Share or Download ZIP instead."
        val uris = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Keep only the latest few exports in the cache.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
            files.map { (name, bytes) -> uriFor(File(dir, name).apply { writeBytes(bytes) }) }
        }
        val single = uris.size == 1
        val send = Intent(if (single) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (single) shareMime(files[0].first) else "*/*"
            if (single) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (email) putExtra(Intent.EXTRA_TEXT, "$subject: ${files.joinToString { it.first }}")
            // Grants every file to the receiving app, not only the first.
            clipData = android.content.ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            if (email) selector = mailto
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                Intent.createChooser(send, if (email) "Email $subject" else "Share $subject")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            null
        } catch (_: ActivityNotFoundException) {
            if (email) "No email app is installed. Use Share or Download ZIP instead." else "No app can receive these files"
        }
    }

    private fun shareMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "zip" -> "application/zip"
        else -> "text/plain"
    }

    private val attachmentStore = AttachmentStore(androidDataLocation(context, OpenBillApplication.DATABASE_NAME).attachmentDirs.getValue("attachments"))
    override val supportsAttachments: Boolean get() = true
    override suspend fun attachments(ownerId: String): List<String> = withContext(Dispatchers.IO) { attachmentStore.list(ownerId) }
    override suspend fun deleteAttachment(ownerId: String, name: String) = withContext(Dispatchers.IO) { attachmentStore.delete(ownerId, name) }
    override suspend fun deleteAllAttachments(ownerId: String) = withContext(Dispatchers.IO) { attachmentStore.deleteAll(ownerId) }

    override suspend fun addAttachment(ownerId: String): String? {
        val uri = requireHooks().openFile(arrayOf("image/*", "application/pdf")) ?: return null
        return withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"
            context.contentResolver.openInputStream(uri)?.use { attachmentStore.add(ownerId, name, it) } ?: error("Could not open the chosen file")
        }.let { "Attached $it" }
    }

    override suspend fun openAttachment(ownerId: String, name: String): String? {
        val file = withContext(Dispatchers.IO) { attachmentStore.file(ownerId, name) }
        val type = if (name.endsWith(".pdf", true)) "application/pdf" else "image/*"
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (_: ActivityNotFoundException) {
            "No app can open $name"
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    override suspend fun invoicePdfAction(document: InvoiceDocument, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(document.fileStem, "${document.title} ${document.number}", layout, action, document.shareSummary())

    override suspend fun pdfAction(fileName: String, layout: InvoiceLayout, action: PdfAction): String? =
        pdfAction(fileName, fileName, layout, action)

    private suspend fun pdfAction(stem: String, title: String, layout: InvoiceLayout, action: PdfAction, text: String? = null): String? {
        when (action) {
            PdfAction.SHARE -> {
                val uri = uriFor(writeCachedPdf(stem, layout))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share $title").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
                return null
            }
            PdfAction.IMAGE -> {
                val file = withContext(Dispatchers.Default) {
                    val bmp = painter.renderBitmap(layout, 0, 1240)
                    File(File(context.cacheDir, "shared").apply { mkdirs() }, "$stem.png").also { f ->
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uriFor(file))
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
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
            toPng(decoded, maxEdgePx)
        }
    }

    private fun toPng(decoded: Bitmap, maxEdgePx: Int): LogoImage {
        val scale = minOf(1f, maxEdgePx.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
        } else {
            decoded
        }
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return LogoImage(bytes, scaled.width, scaled.height)
    }

    override val supportsCamera: Boolean
        get() = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

    override suspend fun captureImage(maxEdgePx: Int): LogoImage? {
        // The barcode scanner library declares CAMERA, so the camera app is only started once it is granted.
        require(requireHooks().requestPermission(Manifest.permission.CAMERA)) { "camera access is off; allow it in Android settings to take a photo" }
        val bitmap = requireHooks().capturePhoto() ?: return null
        return withContext(Dispatchers.Default) { toPng(bitmap, maxEdgePx) }
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
