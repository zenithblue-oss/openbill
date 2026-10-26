package app.openbill.ui.reports

import app.openbill.core.domain.report.ColumnKind
import app.openbill.core.domain.report.ReportTable
import app.openbill.core.model.Money
import app.openbill.ui.format.Fmt
import app.openbill.ui.pdf.Align
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.FontRole
import app.openbill.ui.pdf.Ink
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.PdfPage
import app.openbill.ui.pdf.Pen
import app.openbill.ui.pdf.TextStyle

/** Display text of a report cell: Indian grouping for money, dd-MM-yyyy for dates. */
fun reportCell(kind: ColumnKind, raw: String): String = when {
    raw.isEmpty() -> raw
    kind == ColumnKind.MONEY -> runCatching {
        val m = Money.parse(raw)
        if (m.paise < 0) "\u2212" + Fmt.inr(-m, symbol = false) else Fmt.inr(m, symbol = false)
    }.getOrDefault(raw)
    kind == ColumnKind.DATE -> runCatching { Fmt.date(kotlinx.datetime.LocalDate.parse(raw)) }.getOrDefault(raw)
    else -> raw
}

/** A4 table print of any report (landscape when wide), paginated with a repeated header. */
object ReportPdf {
    private const val MARGIN = 28f
    private val title = TextStyle(13f, FontRole.BOLD, Ink.BLACK)
    private val sub = TextStyle(8.5f, FontRole.REGULAR, Ink.MUTED)
    private val head = TextStyle(7.5f, FontRole.BOLD, Ink.BLACK)
    private val body = TextStyle(7.5f, FontRole.REGULAR, Ink.BODY)

    fun layout(table: ReportTable, firmName: String, subtitle: String, metrics: FontMetrics): InvoiceLayout {
        val landscape = table.columns.size > 6
        val w = if (landscape) 842f else 595f
        val h = if (landscape) 595f else 842f
        val measure = Pen(metrics)
        val rows = table.rows.map { r -> r.mapIndexed { i, c -> reportCell(table.columns[i].kind, c) } }
        val totals = table.totals?.mapIndexed { i, c -> reportCell(table.columns[i].kind, c) }
        val natural = table.columns.indices.map { i ->
            (listOf(measure.width(table.columns[i].label, head)) + (rows + listOfNotNull(totals)).map { measure.width(it[i], body) }).max().coerceIn(24f, 180f) + 8f
        }
        val scale = (w - 2 * MARGIN) / natural.sum()
        val widths = natural.map { it * scale }
        val lineH = body.lineHeight + 3f
        val pages = ArrayList<PdfPage>()
        var pen = Pen(metrics)
        var y = 0f
        fun header(first: Boolean) {
            y = MARGIN
            if (first) {
                pen.text(table.title, MARGIN, y + title.ascent, title)
                y += title.lineHeight
                pen.text("$firmName · $subtitle", MARGIN, y + sub.ascent, sub)
                y += sub.lineHeight + 6f
            }
            pen.rect(MARGIN, y, w - 2 * MARGIN, lineH, fill = Ink.SHADE)
            row(pen, table, table.columns.map { it.label }, widths, y, head)
            y += lineH
        }
        fun newPage() {
            pages += PdfPage(pen.ops.toList(), w, h)
            pen = Pen(metrics)
            header(first = false)
        }
        header(first = true)
        rows.forEach { r ->
            if (y + lineH > h - MARGIN) newPage()
            row(pen, table, r, widths, y, body)
            y += lineH
            pen.hline(MARGIN, y, w - 2 * MARGIN, Ink.HAIRLINE, 0.3f)
        }
        totals?.let {
            if (y + lineH > h - MARGIN) newPage()
            row(pen, table, it, widths, y, head)
            y += lineH
        }
        table.notes.forEach { n ->
            if (y + sub.lineHeight > h - MARGIN) newPage()
            y += 2f
            pen.text(n, MARGIN, y + sub.ascent, sub, maxWidth = w - 2 * MARGIN)
            y += sub.lineHeight
        }
        pages += PdfPage(pen.ops.toList(), w, h)
        return InvoiceLayout(pages)
    }

    private fun row(pen: Pen, table: ReportTable, cells: List<String>, widths: List<Float>, y: Float, style: TextStyle) {
        var x = MARGIN
        cells.forEachIndexed { i, c ->
            val numeric = table.columns[i].numeric
            pen.text(c, if (numeric) x + widths[i] - 4f else x + 4f, y + 2f + style.ascent, style, if (numeric) Align.RIGHT else Align.LEFT, widths[i] - 8f, shrink = true)
            x += widths[i]
        }
    }
}
