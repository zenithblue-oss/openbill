package app.openbill.ui.pdf

import app.openbill.ui.AppContainer
import app.openbill.ui.invoice.InvoiceDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to do with a generated invoice PDF. */
enum class PdfAction(val label: String) {
    SHARE("Share"),
    PRINT("Print"),
    SAVE("Save as"),
    OPEN("Open in another app"),
}

/** Glue between settings, firm images and the layout engine; shared by share and preview. */
object InvoicePdf {
    suspend fun layout(
        container: AppContainer,
        firmId: String,
        doc: InvoiceDocument,
        style: InvoiceStyle = container.invoiceStyles.style(firmId).value,
    ): InvoiceLayout {
        val platform = container.platform
        val logo = if (style.options.showLogo) runCatching { platform.loadFirmImage(firmId, ImageKind.LOGO) }.getOrNull() else null
        val signature = if (style.options.showSignature) runCatching { platform.loadFirmImage(firmId, ImageKind.SIGNATURE) }.getOrNull() else null
        return withContext(Dispatchers.Default) {
            val upi = style.options.upiId.trim()
            val qr = if (style.options.showUpiQr && upi.isNotEmpty()) {
                encodeQr(InvoiceLayoutEngine.upiUri(upi, doc.seller.name, doc.grandTotalPaise, doc.number))
            } else {
                null
            }
            InvoiceLayoutEngine.layout(LayoutInput(doc, style, platform.pdfFontMetrics, logo, signature, qr))
        }
    }

    suspend fun share(container: AppContainer, firmId: String, doc: InvoiceDocument, style: InvoiceStyle = container.invoiceStyles.style(firmId).value): String? =
        container.platform.invoicePdfAction(doc, layout(container, firmId, doc, style), PdfAction.SHARE)
}
