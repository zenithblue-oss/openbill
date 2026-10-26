package app.openbill.ui.pdf

import app.openbill.ui.invoice.InvoiceDocument

/**
 * Raw ESC/POS receipt for roll printers: 32 columns on 58 mm, 48 on 80 mm (Font A). Text is
 * ASCII only because code pages differ between printers: "₹" prints as "Rs." and any other
 * non-ASCII character as "?".
 */
object EscPos {
    fun receipt(doc: InvoiceDocument, paper: Paper): ByteArray {
        val cols = if (paper == Paper.THERMAL_58) 32 else 48
        val out = ArrayList<Byte>(2048)
        fun raw(vararg b: Int) = b.forEach { out += it.toByte() }
        fun line(s: String = "") {
            ascii(s).forEach { out += it.code.toByte() }
            raw(0x0A)
        }
        fun wrap(s: String, width: Int = cols): List<String> =
            ascii(s).split(' ').filter { it.isNotEmpty() }.flatMap { it.chunked(width) }.fold(mutableListOf<String>()) { acc, w ->
                if (acc.isNotEmpty() && acc.last().length + 1 + w.length <= width) acc[acc.lastIndex] += " $w" else acc += w
                acc
            }
        fun row(label: String, value: String) {
            val l = ascii(label)
            val v = ascii(value)
            if (l.length + 1 + v.length <= cols) line(l + v.padStart(cols - l.length)) else { wrap(l).forEach(::line); line(v.padStart(cols)) }
        }
        fun align(n: Int) = raw(0x1B, 0x61, n)
        fun bold(on: Boolean) = raw(0x1B, 0x45, if (on) 1 else 0)
        fun rule() = line("-".repeat(cols))

        raw(0x1B, 0x40) // initialise
        align(1)
        bold(true)
        raw(0x1D, 0x21, 0x11) // double width and height
        wrap(doc.seller.name, cols / 2).forEach(::line)
        raw(0x1D, 0x21, 0x00)
        bold(false)
        doc.seller.addressLines.forEach { a -> wrap(a).forEach(::line) }
        doc.seller.gstin?.let { line("GSTIN: $it") }
        doc.seller.phone?.let { line("Ph: $it") }
        rule()
        bold(true)
        line(doc.title.uppercase())
        bold(false)
        doc.statusNote?.let { s -> wrap(s).forEach(::line) }
        align(0)
        row("No. ${doc.number}", doc.date)
        doc.dueDate?.let { row("Due", it) }
        doc.buyer?.let { b ->
            wrap("To: ${b.name}").forEach(::line)
            b.gstin?.let { row("GSTIN", it) }
            b.phone?.let { row("Phone", it) }
        }
        rule()
        doc.lines.forEach { l ->
            wrap("${l.index}. ${l.description}").forEach(::line)
            row("   ${l.quantity} x ${l.rate}", l.amount)
        }
        rule()
        doc.totals.forEach { (l, v) -> row(l, v) }
        bold(true)
        raw(0x1D, 0x21, 0x01) // double height keeps the column count
        row("TOTAL", doc.grandTotal)
        raw(0x1D, 0x21, 0x00)
        bold(false)
        wrap(doc.amountInWords).forEach(::line)
        rule()
        align(1)
        line("Thank you. Please visit again.")
        raw(0x0A, 0x0A, 0x0A)
        raw(0x1D, 0x56, 0x42, 0x00) // feed and partial cut
        return out.toByteArray()
    }

    internal fun ascii(s: String): String = buildString {
        s.forEach { c ->
            when {
                c == '₹' -> append("Rs.")
                c == '−' || c == '–' || c == '—' -> append('-')
                c.isWhitespace() -> append(' ')
                c.code in 0x20..0x7E -> append(c)
                else -> append('?')
            }
        }
    }
}
