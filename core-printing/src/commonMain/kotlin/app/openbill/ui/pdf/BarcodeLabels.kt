package app.openbill.ui.pdf

/** One printed label; repeat it in the list for several copies. */
data class BarcodeLabel(val name: String, val code: String, val price: String?)

/**
 * Label stock: an A4 sheet of 3 x 8, or a roll for a dedicated label printer, where every PDF
 * page is one label of that size (the printer driver feeds one label per page).
 */
enum class LabelStock(val label: String, val widthMm: Float, val heightMm: Float) {
    A4_SHEET("A4 sheet, ${BarcodeLabels.PER_PAGE} per page", 210f, 297f),
    ROLL_50X25("Label roll 50 × 25 mm", 50f, 25f),
    ROLL_38X25("Label roll 38 × 25 mm", 38f, 25f),
    ROLL_50X30("Label roll 50 × 30 mm", 50f, 30f),
    ROLL_100X50("Label roll 100 × 50 mm", 100f, 50f),
    ;

    val roll: Boolean get() = this != A4_SHEET

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: A4_SHEET
    }
}

/** Code 128 labels on [LabelStock]: A4 sheets with faint cut guides, or one label per page for roll printers. */
object BarcodeLabels {
    const val COLUMNS = 3
    const val ROWS = 8
    const val PER_PAGE = COLUMNS * ROWS

    private const val MARGIN_X = 20f
    private const val MARGIN_Y = 24f
    private const val GAP = 6f
    private const val PAD = 8f
    private const val QUIET_MODULES = 10

    /** Widest module drawn; small codes stay compact instead of filling the label. */
    private const val MAX_MODULE = 1.2f

    private val nameStyle = TextStyle(8.5f, FontRole.BOLD, Ink.BLACK)
    private val codeStyle = TextStyle(7.5f, FontRole.REGULAR, Ink.BODY)
    private val priceStyle = TextStyle(9f, FontRole.BOLD, Ink.BLACK)

    fun layout(
        labels: List<BarcodeLabel>,
        metrics: FontMetrics,
        encode: (String) -> BooleanArray? = ::encodeCode128,
        stock: LabelStock = LabelStock.A4_SHEET,
    ): InvoiceLayout {
        if (stock.roll) {
            val w = stock.widthMm * 72f / 25.4f
            val h = stock.heightMm * 72f / 25.4f
            val bars = HashMap<String, BooleanArray?>()
            val pages = labels.ifEmpty { listOf(null) }.map { label ->
                val pen = Pen(metrics)
                label?.let { drawLabel(pen, it, bars.getOrPut(it.code) { encode(it.code) }, 0f, 0f, w, h, pad = 4f, border = false) }
                PdfPage(pen.ops.toList(), w, h)
            }
            return InvoiceLayout(pages)
        }
        val paper = Paper.A4
        val cellW = (paper.width - 2 * MARGIN_X - (COLUMNS - 1) * GAP) / COLUMNS
        val cellH = (paper.height - 2 * MARGIN_Y - (ROWS - 1) * GAP) / ROWS
        val bars = HashMap<String, BooleanArray?>()
        val pages = labels.chunked(PER_PAGE).ifEmpty { listOf(emptyList()) }.map { chunk ->
            val pen = Pen(metrics)
            chunk.forEachIndexed { i, label ->
                val x = MARGIN_X + (i % COLUMNS) * (cellW + GAP)
                val y = MARGIN_Y + (i / COLUMNS) * (cellH + GAP)
                drawLabel(pen, label, bars.getOrPut(label.code) { encode(label.code) }, x, y, cellW, cellH)
            }
            PdfPage(pen.ops.toList(), paper.width, paper.height)
        }
        return InvoiceLayout(pages)
    }

    private fun drawLabel(pen: Pen, label: BarcodeLabel, bars: BooleanArray?, x: Float, y: Float, w: Float, h: Float, pad: Float = PAD, border: Boolean = true) {
        if (border) pen.rect(x, y, w, h, stroke = Ink.HAIRLINE, strokeWidth = 0.4f, radius = 3f)
        val cx = x + w / 2f
        val innerW = w - 2 * pad
        var top = y + pad
        pen.text(label.name, cx, top + nameStyle.ascent, nameStyle, Align.CENTER, innerW)
        top += nameStyle.lineHeight + 2f
        val bottom = y + h - pad - (if (label.price != null) priceStyle.lineHeight else 0f)
        val barH = bottom - codeStyle.lineHeight - top
        if (bars != null) {
            val module = minOf(innerW / (bars.size + 2 * QUIET_MODULES), MAX_MODULE)
            val barW = module * bars.size
            pen.barcode(bars, cx - barW / 2f, top, barW, barH)
        } else {
            pen.text("Cannot encode", cx, top + barH / 2f, codeStyle.color(Ink.ERROR), Align.CENTER, innerW)
        }
        pen.text(label.code, cx, top + barH + codeStyle.ascent, codeStyle, Align.CENTER, innerW, shrink = true)
        label.price?.let { pen.text(it, cx, bottom + priceStyle.ascent, priceStyle, Align.CENTER, innerW) }
    }
}
