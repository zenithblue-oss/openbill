package app.openbill.desktop

import app.openbill.ui.invoice.InvoiceDocument
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Dependency-free A4 invoice PDF writer for desktop (base-14 Helvetica, no embedded fonts).
 * Layout mirrors the Android renderer. Non-Latin text is not supported yet (rendered as '?');
 * the rupee sign is written as "Rs." because it is not in WinAnsi.
 */
internal object DesktopInvoicePdf {
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val FOOTER_H = 28f

    private val HELV = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )
    private val HELV_BOLD = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    )

    private class Rgb(val r: Float, val g: Float, val b: Float)
    private fun rgb(r: Int, g: Int, b: Int) = Rgb(r / 255f, g / 255f, b / 255f)
    private val accent = rgb(0, 105, 107)
    private val muted = rgb(90, 98, 98)
    private val rule = rgb(200, 208, 208)
    private val band = rgb(232, 243, 243)
    private val black = rgb(0, 0, 0)
    private val white = rgb(255, 255, 255)
    private val red = rgb(186, 26, 26)

    private fun clean(s: String): String =
        s.replace("₹", "Rs.").map { if (it.code in 32..126) it else if (it == '\n') ' ' else if (it.code in 160..255) it else '?' }
            .joinToString("")

    private fun width(text: String, size: Float, bold: Boolean): Float {
        val t = if (bold) HELV_BOLD else HELV
        var w = 0
        for (ch in clean(text)) w += if (ch.code in 32..126) t[ch.code - 32] else 556
        return w * size / 1000f
    }

    private fun num(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private fun wrap(text: String, size: Float, bold: Boolean, maxW: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(" ")) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (width(cand, size, bold) <= maxW) { cur = cand; continue }
            if (cur.isNotEmpty()) out += cur
            var w = word
            while (width(w, size, bold) > maxW && w.length > 1) {
                var cut = w.length
                while (cut > 1 && width(w.substring(0, cut), size, bold) > maxW) cut--
                out += w.substring(0, cut); w = w.substring(cut)
            }
            cur = w
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** One page's content stream; y is measured from the top, like the Android canvas. */
    private class Page { val sb = StringBuilder() }

    private enum class Align { LEFT, RIGHT }

    private class Column(val title: String, val width: Float, val right: Boolean, val value: (InvoiceDocument.Line) -> String)

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

    fun render(doc: InvoiceDocument): ByteArray = Writer(doc).build()

    private class Writer(val doc: InvoiceDocument) {
        val pages = mutableListOf<Page>()
        lateinit var p: Page
        var y = MARGIN

        fun newPage() {
            if (pages.isNotEmpty()) footer()
            p = Page().also { pages += it }
            y = MARGIN
        }

        fun text(s: String, x: Float, baseline: Float, size: Float, bold: Boolean = false, color: Rgb = black, align: Align = Align.LEFT) {
            val t = clean(s)
            if (t.isEmpty()) return
            val px = if (align == Align.RIGHT) x - width(t, size, bold) else x
            val esc = t.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            p.sb.append("BT /${if (bold) "F2" else "F1"} ${num(size)} Tf ${num(color.r)} ${num(color.g)} ${num(color.b)} rg ")
                .append("${num(px)} ${num(PAGE_H - baseline)} Td (").append(esc).append(") Tj ET\n")
        }

        fun rect(x1: Float, y1: Float, x2: Float, y2: Float, c: Rgb) {
            p.sb.append("${num(c.r)} ${num(c.g)} ${num(c.b)} rg ${num(x1)} ${num(PAGE_H - y2)} ${num(x2 - x1)} ${num(y2 - y1)} re f\n")
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Rgb, w: Float) {
            p.sb.append("${num(c.r)} ${num(c.g)} ${num(c.b)} RG ${num(w)} w ${num(x1)} ${num(PAGE_H - y1)} m ${num(x2)} ${num(PAGE_H - y2)} l S\n")
        }

        fun footer() {
            val n = pages.size
            line(MARGIN, PAGE_H - MARGIN - 12f, PAGE_W - MARGIN, PAGE_H - MARGIN - 12f, rule, 0.6f)
            text("${doc.title} ${doc.number}  -  Page $n", MARGIN, PAGE_H - MARGIN, 7f, color = muted)
            text("Computer-generated with OpenBill", PAGE_W - MARGIN, PAGE_H - MARGIN, 7f, color = muted, align = Align.RIGHT)
        }

        fun ensure(h: Float, repeatHeader: Boolean = false) {
            if (y + h > PAGE_H - MARGIN - FOOTER_H) {
                newPage()
                if (repeatHeader) tableHeader()
            }
        }

        fun build(): ByteArray {
            newPage(); header(); parties(); tableHeader()
            doc.lines.forEach { row(it) }
            totals(); footer()
            return assemble()
        }

        fun header() {
            text(doc.seller.name, MARGIN, y + 14f, 16f, true, accent)
            var ly = y + 28f
            doc.seller.addressLines.forEach { text(it, MARGIN, ly, 8.5f); ly += 11f }
            doc.seller.gstin?.let { text("GSTIN: $it", MARGIN, ly, 8.5f, true); ly += 11f }
            doc.seller.state?.let { text("State: $it", MARGIN, ly, 8.5f); ly += 11f }
            doc.seller.phone?.let { text("Phone: $it", MARGIN, ly, 8.5f); ly += 11f }
            val right = PAGE_W - MARGIN
            text(doc.title.uppercase(), right, y + 14f, 14f, true, align = Align.RIGHT)
            text("No. ${doc.number}", right, y + 30f, 8.5f, true, align = Align.RIGHT)
            text("Date ${doc.date}", right, y + 42f, 8.5f, align = Align.RIGHT)
            var ry = y + 54f
            doc.dueDate?.let { text("Due $it", right, ry, 8.5f, align = Align.RIGHT); ry += 12f }
            doc.statusNote?.let { text(it, right, ry, 9f, true, red, Align.RIGHT); ry += 12f }
            y = maxOf(ly, ry) + 6f
            line(MARGIN, y, PAGE_W - MARGIN, y, accent, 1.2f)
            y += 12f
        }

        fun parties() {
            val half = CONTENT_W / 2
            text("BILL TO", MARGIN, y, 7.5f, true, accent)
            text("SUPPLY DETAILS", MARGIN + half + 8f, y, 7.5f, true, accent)
            var ly = y + 13f
            val buyer = doc.buyer
            if (buyer == null) {
                text("Cash sale", MARGIN, ly, 8.5f, true); ly += 11f
            } else {
                wrap(buyer.name, 8.5f, true, half - 8f).forEach { text(it, MARGIN, ly, 8.5f, true); ly += 11f }
                buyer.addressLines.forEach { l -> wrap(l, 8.5f, false, half - 8f).forEach { text(it, MARGIN, ly, 8.5f); ly += 11f } }
                buyer.gstin?.let { text("GSTIN: $it", MARGIN, ly, 8.5f); ly += 11f }
                buyer.phone?.let { text("Phone: $it", MARGIN, ly, 8.5f); ly += 11f }
            }
            var ry = y + 13f
            val rx = MARGIN + half + 8f
            text("Place of supply: ${doc.placeOfSupply}", rx, ry, 8.5f); ry += 11f
            text(if (doc.isIntraState) "Intra-state supply (CGST + SGST)" else "Inter-state supply (IGST)", rx, ry, 8.5f); ry += 11f
            buyer?.state?.let { text("Buyer state: $it", rx, ry, 8.5f); ry += 11f }
            y = maxOf(ly, ry) + 10f
        }

        fun tableHeader() {
            rect(MARGIN, y, PAGE_W - MARGIN, y + 16f, accent)
            var x = MARGIN
            columns.forEach { c ->
                if (c.right) text(c.title, x + c.width - 3f, y + 11f, 7.5f, true, white, Align.RIGHT)
                else text(c.title, x + 3f, y + 11f, 7.5f, true, white)
                x += c.width
            }
            y += 16f
        }

        fun row(l: InvoiceDocument.Line) {
            val wrapped = columns.map { c -> if (c.right) listOf(c.value(l)) else wrap(c.value(l), 7.5f, false, c.width - 6f) }
            val h = wrapped.maxOf { it.size } * 10f + 6f
            ensure(h, true)
            if (l.index % 2 == 0) rect(MARGIN, y, PAGE_W - MARGIN, y + h, band)
            var x = MARGIN
            columns.forEachIndexed { i, c ->
                var ty = y + 11f
                wrapped[i].forEach { t ->
                    if (c.right) text(t, x + c.width - 3f, ty, 7.5f, align = Align.RIGHT) else text(t, x + 3f, ty, 7.5f)
                    ty += 10f
                }
                x += c.width
            }
            y += h
            line(MARGIN, y, PAGE_W - MARGIN, y, rule, 0.6f)
        }

        fun totals() {
            val boxW = 200f
            ensure(doc.totals.size * 13f + 130f)
            y += 10f
            val left = PAGE_W - MARGIN - boxW
            val right = PAGE_W - MARGIN
            val top = y
            doc.totals.forEach { (k, v) ->
                text(k, left + 6f, y + 10f, 8.5f)
                text(v, right - 6f, y + 10f, 8.5f, align = Align.RIGHT)
                y += 13f
            }
            rect(left, y + 2f, right, y + 22f, accent)
            text("Total", left + 6f, y + 16f, 10f, true, white)
            text(doc.grandTotal, right - 6f, y + 16f, 10f, true, white, Align.RIGHT)
            val bottom = y + 22f
            var ly = top + 10f
            val leftW = CONTENT_W - boxW - 16f
            text("Amount in words", MARGIN, ly, 7.5f, true, accent); ly += 11f
            wrap(doc.amountInWords, 8.5f, false, leftW).forEach { text(it, MARGIN, ly, 8.5f); ly += 11f }
            doc.notes?.let { n ->
                ly += 4f; text("Notes", MARGIN, ly, 7.5f, true, accent); ly += 11f
                n.lines().forEach { para -> wrap(para, 7.5f, false, leftW).forEach { text(it, MARGIN, ly, 7.5f, color = muted); ly += 10f } }
            }
            doc.bank?.let {
                ly += 4f; text("Bank", MARGIN, ly, 7.5f, true, accent); ly += 11f
                wrap(it, 7.5f, false, leftW).forEach { l -> text(l, MARGIN, ly, 7.5f, color = muted); ly += 10f }
            }
            y = maxOf(ly, bottom) + 30f
            ensure(40f)
            text("For ${doc.seller.name}", right, y, 8.5f, true, align = Align.RIGHT)
            y += 30f
            line(right - 140f, y - 10f, right, y - 10f, rule, 0.6f)
            text("Authorised signatory", right, y, 8.5f, align = Align.RIGHT)
        }

        fun assemble(): ByteArray {
            val out = ByteArrayOutputStream()
            val offsets = mutableListOf<Int>()
            fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            fun obj(id: Int, body: String) { offsets.add(out.size()); w("$id 0 obj\n$body\nendobj\n") }
            w("%PDF-1.4\n")
            // 1 catalog, 2 pages, 3 F1, 4 F2, then page/content pairs from 5.
            val kids = pages.indices.joinToString(" ") { "${5 + it * 2} 0 R" }
            obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            obj(2, "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
            obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
            obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
            pages.forEachIndexed { i, pg ->
                val pid = 5 + i * 2
                obj(pid, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${pid + 1} 0 R >>")
                val data = pg.sb.toString()
                val len = data.toByteArray(Charsets.ISO_8859_1).size
                obj(pid + 1, "<< /Length $len >>\nstream\n$data\nendstream")
            }
            val xref = out.size()
            w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { w(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
            w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
    }
}
