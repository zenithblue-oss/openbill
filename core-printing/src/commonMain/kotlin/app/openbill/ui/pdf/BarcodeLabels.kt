package app.openbill.ui.pdf

/** One printed label; repeat it in the list for several copies. */
data class BarcodeLabel(val name: String, val code: String, val price: String?)

/** A4 sheet of Code 128 labels, 3 x 8 per page, with faint cut guides. */
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

    fun layout(labels: List<BarcodeLabel>, metrics: FontMetrics, encode: (String) -> BooleanArray? = ::encodeCode128): InvoiceLayout {
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

    private fun drawLabel(pen: Pen, label: BarcodeLabel, bars: BooleanArray?, x: Float, y: Float, w: Float, h: Float) {
        pen.rect(x, y, w, h, stroke = Ink.HAIRLINE, strokeWidth = 0.4f, radius = 3f)
        val cx = x + w / 2f
        val innerW = w - 2 * PAD
        var top = y + PAD
        pen.text(label.name, cx, top + nameStyle.ascent, nameStyle, Align.CENTER, innerW)
        top += nameStyle.lineHeight + 2f
        val bottom = y + h - PAD - (if (label.price != null) priceStyle.lineHeight else 0f)
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
