package app.openbill.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.openbill.ui.invoice.InvoiceDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Draws an A4 invoice with [PdfDocument]. Coordinates are in PostScript points (1/72 inch).
 * The layout is OpenBill's own: header band, two address blocks, line table, totals, words.
 */
object InvoicePdfRenderer {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val FOOTER_H = 28f

    private val accent = Color.rgb(0, 105, 107)
    private val muted = Color.rgb(90, 98, 98)
    private val rule = Color.rgb(200, 208, 208)
    private val band = Color.rgb(232, 243, 243)

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
            textAlign = align
        }

    private data class Column(val title: String, val width: Float, val right: Boolean, val value: (InvoiceDocument.Line) -> String)

    private val columns = listOf(
        Column("#", 16f, false) { it.index.toString() },
        Column("Item", 131f, false) { it.description },
        Column("HSN/SAC", 44f, false) { it.hsnSac },
        Column("Qty", 46f, true) { it.quantity },
        Column("Rate", 52f, true) { it.rate },
        Column("Disc.", 40f, true) { it.discount },
        Column("Taxable", 58f, true) { it.taxable },
        Column("GST", 28f, true) { it.gstRate },
        Column("Tax", 48f, true) { it.tax },
        Column("Amount", 60f, true) { it.amount },
    )

    fun render(doc: InvoiceDocument, out: File) {
        val pdf = PdfDocument()
        try {
            Writer(pdf, doc).draw()
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (p.measureText(candidate) <= width) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                // Hard-break words wider than the column.
                var w = word
                while (p.measureText(w) > width && w.length > 1) {
                    var cut = w.length
                    while (cut > 1 && p.measureText(w, 0, cut) > width) cut--
                    lines += w.substring(0, cut)
                    w = w.substring(cut)
                }
                current = w
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private class Writer(val pdf: PdfDocument, val doc: InvoiceDocument) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = MARGIN

        val body = paint(8.5f)
        val bodyBold = paint(8.5f, bold = true)
        val small = paint(7.5f, color = muted)
        val cell = paint(7.5f)
        val cellRight = paint(7.5f, align = Paint.Align.RIGHT)
        val head = paint(7.5f, bold = true, color = Color.WHITE)
        val headRight = paint(7.5f, bold = true, color = Color.WHITE, align = Paint.Align.RIGHT)
        val linePaint = Paint().apply { color = rule; strokeWidth = 0.6f }
        val fill = Paint().apply { style = Paint.Style.FILL }

        fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            c = page.canvas
            y = MARGIN
        }

        fun finishPage() {
            val footer = paint(7f, color = muted)
            c.drawLine(MARGIN, PAGE_H - MARGIN - 12f, PAGE_W - MARGIN, PAGE_H - MARGIN - 12f, linePaint)
            c.drawText("${doc.title} ${doc.number}  -  Page $pageNo", MARGIN, PAGE_H - MARGIN, footer)
            c.drawText("Computer-generated with OpenBill", PAGE_W - MARGIN, PAGE_H - MARGIN, paint(7f, color = muted, align = Paint.Align.RIGHT))
            pdf.finishPage(page)
        }

        fun ensure(height: Float, repeatTableHeader: Boolean = false) {
            if (y + height > PAGE_H - MARGIN - FOOTER_H) {
                newPage()
                if (repeatTableHeader) tableHeader()
            }
        }

        fun draw() {
            newPage()
            header()
            parties()
            tableHeader()
            doc.lines.forEach { row(it) }
            totals()
            finishPage()
        }

        fun header() {
            // Seller on the left, document title on the right.
            val name = paint(16f, bold = true, color = accent)
            c.drawText(doc.seller.name, MARGIN, y + 14f, name)
            var ly = y + 28f
            doc.seller.addressLines.forEach { c.drawText(it, MARGIN, ly, body); ly += 11f }
            doc.seller.gstin?.let { c.drawText("GSTIN: $it", MARGIN, ly, bodyBold); ly += 11f }
            doc.seller.state?.let { c.drawText("State: $it", MARGIN, ly, body); ly += 11f }
            doc.seller.phone?.let { c.drawText("Phone: $it", MARGIN, ly, body); ly += 11f }

            val right = PAGE_W - MARGIN
            c.drawText(doc.title.uppercase(), right, y + 14f, paint(14f, bold = true, align = Paint.Align.RIGHT))
            val r = paint(8.5f, align = Paint.Align.RIGHT)
            val rb = paint(8.5f, bold = true, align = Paint.Align.RIGHT)
            c.drawText("No. ${doc.number}", right, y + 30f, rb)
            c.drawText("Date ${doc.date}", right, y + 42f, r)
            var ry = y + 54f
            doc.dueDate?.let { c.drawText("Due $it", right, ry, r); ry += 12f }
            doc.statusNote?.let {
                c.drawText(it, right, ry, paint(9f, bold = true, color = Color.rgb(186, 26, 26), align = Paint.Align.RIGHT))
                ry += 12f
            }
            y = maxOf(ly, ry) + 6f
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paint().apply { color = accent; strokeWidth = 1.2f })
            y += 12f
        }

        fun parties() {
            val half = CONTENT_W / 2
            val label = paint(7.5f, bold = true, color = accent)
            c.drawText("BILL TO", MARGIN, y, label)
            c.drawText("SUPPLY DETAILS", MARGIN + half + 8f, y, label)
            var ly = y + 13f
            val buyer = doc.buyer
            if (buyer == null) {
                c.drawText("Cash sale", MARGIN, ly, bodyBold); ly += 11f
            } else {
                wrap(buyer.name, bodyBold, half - 8f).forEach { c.drawText(it, MARGIN, ly, bodyBold); ly += 11f }
                buyer.addressLines.forEach { line ->
                    wrap(line, body, half - 8f).forEach { c.drawText(it, MARGIN, ly, body); ly += 11f }
                }
                buyer.gstin?.let { c.drawText("GSTIN: $it", MARGIN, ly, body); ly += 11f }
                buyer.phone?.let { c.drawText("Phone: $it", MARGIN, ly, body); ly += 11f }
            }
            var ry = y + 13f
            val rx = MARGIN + half + 8f
            c.drawText("Place of supply: ${doc.placeOfSupply}", rx, ry, body); ry += 11f
            c.drawText(if (doc.isIntraState) "Intra-state supply (CGST + SGST)" else "Inter-state supply (IGST)", rx, ry, body); ry += 11f
            buyer?.state?.let { c.drawText("Buyer state: $it", rx, ry, body); ry += 11f }
            y = maxOf(ly, ry) + 10f
        }

        fun tableHeader() {
            fill.color = accent
            c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + 16f, fill)
            var x = MARGIN
            columns.forEach { col ->
                if (col.right) c.drawText(col.title, x + col.width - 3f, y + 11f, headRight)
                else c.drawText(col.title, x + 3f, y + 11f, head)
                x += col.width
            }
            y += 16f
        }

        fun row(line: InvoiceDocument.Line) {
            val wrapped = columns.map { col ->
                if (col.right) listOf(col.value(line)) else wrap(col.value(line), cell, col.width - 6f)
            }
            val height = wrapped.maxOf { it.size } * 10f + 6f
            ensure(height, repeatTableHeader = true)
            if (line.index % 2 == 0) {
                fill.color = band
                c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, fill)
            }
            var x = MARGIN
            columns.forEachIndexed { i, col ->
                var ty = y + 11f
                wrapped[i].forEach { text ->
                    if (col.right) c.drawText(text, x + col.width - 3f, ty, cellRight) else c.drawText(text, x + 3f, ty, cell)
                    ty += 10f
                }
                x += col.width
            }
            y += height
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint)
        }

        fun totals() {
            val boxW = 200f
            val needed = doc.totals.size * 13f + 60f
            ensure(needed + 70f)
            y += 10f
            val left = PAGE_W - MARGIN - boxW
            val right = PAGE_W - MARGIN
            val top = y
            val label = paint(8.5f)
            val value = paint(8.5f, align = Paint.Align.RIGHT)
            doc.totals.forEach { (k, v) ->
                c.drawText(k, left + 6f, y + 10f, label)
                c.drawText(v, right - 6f, y + 10f, value)
                y += 13f
            }
            fill.color = accent
            c.drawRect(left, y + 2f, right, y + 22f, fill)
            c.drawText("Total", left + 6f, y + 16f, paint(10f, bold = true, color = Color.WHITE))
            c.drawText(doc.grandTotal, right - 6f, y + 16f, paint(10f, bold = true, color = Color.WHITE, align = Paint.Align.RIGHT))
            val totalsBottom = y + 22f

            // Left column: amount in words, notes, bank details.
            var ly = top + 10f
            val leftW = CONTENT_W - boxW - 16f
            c.drawText("Amount in words", MARGIN, ly, paint(7.5f, bold = true, color = accent)); ly += 11f
            wrap(doc.amountInWords, body, leftW).forEach { c.drawText(it, MARGIN, ly, body); ly += 11f }
            doc.notes?.let { notes ->
                ly += 4f
                c.drawText("Notes", MARGIN, ly, paint(7.5f, bold = true, color = accent)); ly += 11f
                notes.lines().forEach { para -> wrap(para, small, leftW).forEach { c.drawText(it, MARGIN, ly, small); ly += 10f } }
            }
            doc.bank?.let {
                ly += 4f
                c.drawText("Bank", MARGIN, ly, paint(7.5f, bold = true, color = accent)); ly += 11f
                wrap(it, small, leftW).forEach { line -> c.drawText(line, MARGIN, ly, small); ly += 10f }
            }
            y = maxOf(ly, totalsBottom) + 30f

            ensure(40f)
            val sig = paint(8.5f, align = Paint.Align.RIGHT)
            c.drawText("For ${doc.seller.name}", right, y, paint(8.5f, bold = true, align = Paint.Align.RIGHT))
            y += 30f
            c.drawLine(right - 140f, y - 10f, right, y - 10f, linePaint)
            c.drawText("Authorised signatory", right, y, sig)
        }
    }
}
