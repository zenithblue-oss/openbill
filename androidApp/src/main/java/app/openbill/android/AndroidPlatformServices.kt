package app.openbill.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.openbill.ui.PlatformServices
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun shareInvoicePdf(document: InvoiceDocument) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Keep only the latest few exports in the cache.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
            val out = File(dir, "${document.fileStem}.pdf")
            InvoicePdfRenderer.render(document, out)
            out
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${document.title} ${document.number}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share invoice ${document.number}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
