package app.openbill.ui.pdf

import app.openbill.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Paper families. Thermal receipts have a fixed width and a height that follows the content. */
enum class Paper(val label: String, val width: Float, val height: Float, val thermal: Boolean = false) {
    A4("A4", 595f, 842f),
    A5("A5", 420f, 595f),
    THERMAL_80("80 mm roll", 226.8f, 0f, thermal = true),
    THERMAL_58("58 mm roll", 164.4f, 0f, thermal = true),
}

/**
 * Built-in templates. All layouts are OpenBill's own designs (docs/06-clean-room-policy.md):
 * names, geometry and colour use are original and not modelled on any other product's themes.
 * [id] is persisted, so never rename it.
 */
enum class InvoiceTemplate(val id: String, val title: String, val description: String, val paper: Paper) {
    GST_DETAILED("gst-detailed", "GST Detailed", "Bordered compliance grid with rate-wise and HSN tax summaries", Paper.A4),
    LEDGER("ledger", "Ledger", "Accounting columns with CGST, SGST or IGST on every line", Paper.A4),
    SIMPLE("simple", "Simple", "Monochrome and airy, thin rules, light on printer ink", Paper.A4),
    MODERN("modern", "Modern", "Colour header band, rounded cards and a highlighted total", Paper.A4),
    ELEGANT("elegant", "Elegant", "Centred letterhead, fine double rules and refined spacing", Paper.A4),
    COUNTER("counter", "Counter", "Dense bill-book layout for retail counters, fits many lines", Paper.A4),
    GST_DETAILED_A5("gst-detailed-a5", "GST Detailed A5", "The compliance grid on half-size paper", Paper.A5),
    SIMPLE_A5("simple-a5", "Simple A5", "The simple layout on half-size paper", Paper.A5),
    RECEIPT_80_TAX("receipt-80-tax", "Receipt 80 mm", "3-inch roll receipt with GST breakup", Paper.THERMAL_80),
    RECEIPT_80("receipt-80", "Receipt 80 mm, short", "3-inch roll receipt without tax breakup", Paper.THERMAL_80),
    RECEIPT_58_TAX("receipt-58-tax", "Receipt 58 mm", "2-inch roll receipt with GST breakup", Paper.THERMAL_58),
    RECEIPT_58("receipt-58", "Receipt 58 mm, short", "2-inch roll receipt without tax breakup", Paper.THERMAL_58),
    ;

    companion object {
        val DEFAULT = MODERN
        fun fromId(id: String?): InvoiceTemplate = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Curated accents; each keeps white text at 4.5:1 or better. Peacock is the brand primary. */
enum class Accent(val label: String, val argb: Argb) {
    PEACOCK("Peacock", 0xFF00687B.toInt()),
    INDIGO("Indigo", 0xFF3949AB.toInt()),
    FOREST("Forest", 0xFF2E6B47.toInt()),
    WINE("Wine", 0xFF8C2B4A.toInt()),
    RUST("Rust", 0xFFA34722.toInt()),
    GRAPHITE("Graphite", 0xFF3D4A52.toInt()),
    ;

    companion object {
        fun fromName(name: String?): Accent = entries.firstOrNull { it.name == name } ?: PEACOCK
    }
}

/** Which copy label to print; [ALL_THREE] produces three copies in one PDF. */
enum class CopyMode(val label: String) {
    ORIGINAL("Original for recipient"),
    DUPLICATE("Duplicate for transporter"),
    TRIPLICATE("Triplicate for supplier"),
    ALL_THREE("All three copies"),
    NONE("No copy label"),
    ;

    val labels: List<String?>
        get() = when (this) {
            ALL_THREE -> listOf(ORIGINAL.label, DUPLICATE.label, TRIPLICATE.label)
            NONE -> listOf(null)
            else -> listOf(label)
        }
}

/** Per-firm print options applied by every template. */
data class PrintOptions(
    val showLogo: Boolean = true,
    val showHsn: Boolean = true,
    val showDiscount: Boolean = true,
    val showDueDate: Boolean = true,
    val showAmountInWords: Boolean = true,
    val showTaxSummary: Boolean = true,
    val showBank: Boolean = true,
    val showUpiQr: Boolean = true,
    val showNotes: Boolean = true,
    val showTerms: Boolean = true,
    val showSignature: Boolean = true,
    val copies: CopyMode = CopyMode.ORIGINAL,
    /** UPI VPA such as name@bank; printed with a locally generated payment QR. */
    val upiId: String = "",
    val terms: String = "",
)

/** Everything that decides how a firm's invoices look. */
data class InvoiceStyle(
    val template: InvoiceTemplate = InvoiceTemplate.DEFAULT,
    val accent: Accent = Accent.PEACOCK,
    val options: PrintOptions = PrintOptions(),
) {
    companion object {
        private fun key(firmId: String, name: String) = "invoice.$firmId.$name"

        fun read(store: SettingsStore, firmId: String): InvoiceStyle {
            fun s(name: String) = store.getString(key(firmId, name))
            fun b(name: String, default: Boolean) = s(name)?.let { it == "true" } ?: default
            val d = PrintOptions()
            return InvoiceStyle(
                template = InvoiceTemplate.fromId(s("template")),
                accent = Accent.fromName(s("accent")),
                options = PrintOptions(
                    showLogo = b("logo", d.showLogo),
                    showHsn = b("hsn", d.showHsn),
                    showDiscount = b("discount", d.showDiscount),
                    showDueDate = b("dueDate", d.showDueDate),
                    showAmountInWords = b("words", d.showAmountInWords),
                    showTaxSummary = b("taxSummary", d.showTaxSummary),
                    showBank = b("bank", d.showBank),
                    showUpiQr = b("upiQr", d.showUpiQr),
                    showNotes = b("notes", d.showNotes),
                    showTerms = b("terms", d.showTerms),
                    showSignature = b("signature", d.showSignature),
                    copies = s("copies")?.let { v -> CopyMode.entries.firstOrNull { it.name == v } } ?: d.copies,
                    upiId = s("upiId") ?: "",
                    terms = s("termsText") ?: "",
                ),
            )
        }

        fun write(store: SettingsStore, firmId: String, style: InvoiceStyle) {
            fun put(name: String, v: String) = store.putString(key(firmId, name), v)
            val o = style.options
            put("template", style.template.id)
            put("accent", style.accent.name)
            put("logo", o.showLogo.toString())
            put("hsn", o.showHsn.toString())
            put("discount", o.showDiscount.toString())
            put("dueDate", o.showDueDate.toString())
            put("words", o.showAmountInWords.toString())
            put("taxSummary", o.showTaxSummary.toString())
            put("bank", o.showBank.toString())
            put("upiQr", o.showUpiQr.toString())
            put("notes", o.showNotes.toString())
            put("terms", o.showTerms.toString())
            put("signature", o.showSignature.toString())
            put("copies", o.copies.name)
            put("upiId", o.upiId)
            put("termsText", o.terms)
        }
    }
}

/** Live per-firm invoice style, persisted on every change. */
class InvoiceStyleController(private val store: SettingsStore) {
    private val flows = mutableMapOf<String, MutableStateFlow<InvoiceStyle>>()

    private fun flow(firmId: String) = flows.getOrPut(firmId) {
        MutableStateFlow(runCatching { InvoiceStyle.read(store, firmId) }.getOrDefault(InvoiceStyle()))
    }

    fun style(firmId: String): StateFlow<InvoiceStyle> = flow(firmId).asStateFlow()

    fun update(firmId: String, transform: (InvoiceStyle) -> InvoiceStyle) {
        val f = flow(firmId)
        val next = transform(f.value)
        if (next == f.value) return
        f.value = next
        runCatching { InvoiceStyle.write(store, firmId, next) }
    }

    /** Bumped whenever a firm image (logo or signature) changes, so previews re-render. */
    val imageVersion = MutableStateFlow(0)
}
