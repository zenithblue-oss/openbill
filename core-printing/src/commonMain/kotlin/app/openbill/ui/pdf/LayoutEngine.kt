package app.openbill.ui.pdf

import app.openbill.ui.invoice.InvoiceDocument

/** Everything a template needs to lay out one invoice. */
class LayoutInput(
    val doc: InvoiceDocument,
    val style: InvoiceStyle,
    val metrics: FontMetrics,
    val logo: LogoImage? = null,
    val signature: LogoImage? = null,
    /** UPI payment QR, generated locally when a UPI ID is set and the QR option is on. */
    val qr: QrMatrix? = null,
)

/** Entry point: lays out every requested copy with the selected template. */
object InvoiceLayoutEngine {
    fun layout(input: LayoutInput): InvoiceLayout {
        val o = input.style.options
        val effective = LayoutInput(
            input.doc, input.style, input.metrics,
            logo = input.logo.takeIf { o.showLogo },
            signature = input.signature.takeIf { o.showSignature },
            qr = input.qr.takeIf { o.showUpiQr && o.upiId.isNotBlank() },
        )
        val pages = o.copies.labels.flatMap { label -> create(effective, label).build() }
        val images = buildMap {
            effective.logo?.let { put(ImageKind.LOGO, it) }
            effective.signature?.let { put(ImageKind.SIGNATURE, it) }
        }
        return InvoiceLayout(pages, images)
    }

    /** The UPI deep link encoded in the payment QR (NPCI UPI linking specification). */
    fun upiUri(upiId: String, payee: String, amountPaise: Long, invoiceNumber: String): String {
        fun enc(s: String) = buildString {
            s.encodeToByteArray().forEach { b ->
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 128 || ch in "-._~@") append(ch) else append('%' + c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        val amount = "${amountPaise / 100}.${(amountPaise % 100).toString().padStart(2, '0')}"
        return "upi://pay?pa=${enc(upiId.trim())}&pn=${enc(payee)}&am=$amount&cu=INR&tn=${enc("Invoice $invoiceNumber")}"
    }

    private fun create(input: LayoutInput, copy: String?): BaseLayout = when (input.style.template) {
        InvoiceTemplate.GST_DETAILED -> GstDetailedLayout(input, Paper.A4, copy)
        InvoiceTemplate.GST_DETAILED_A5 -> GstDetailedLayout(input, Paper.A5, copy)
        InvoiceTemplate.LEDGER -> LedgerLayout(input, Paper.A4, copy)
        InvoiceTemplate.SIMPLE -> SimpleLayout(input, Paper.A4, copy)
        InvoiceTemplate.SIMPLE_A5 -> SimpleLayout(input, Paper.A5, copy)
        InvoiceTemplate.MODERN -> ModernLayout(input, Paper.A4, copy)
        InvoiceTemplate.ELEGANT -> ElegantLayout(input, Paper.A4, copy)
        InvoiceTemplate.COUNTER -> CounterLayout(input, Paper.A4, copy)
        InvoiceTemplate.RECEIPT_80_TAX -> ReceiptLayout(input, Paper.THERMAL_80, copy, withTax = true)
        InvoiceTemplate.RECEIPT_80 -> ReceiptLayout(input, Paper.THERMAL_80, copy, withTax = false)
        InvoiceTemplate.RECEIPT_58_TAX -> ReceiptLayout(input, Paper.THERMAL_58, copy, withTax = true)
        InvoiceTemplate.RECEIPT_58 -> ReceiptLayout(input, Paper.THERMAL_58, copy, withTax = false)
    }
}

/** A measured piece of layout that draws itself at (x, top). */
class Block(val height: Float, val draw: (pen: Pen, x: Float, top: Float) -> Unit) {
    companion object {
        val EMPTY = Block(0f) { _, _, _ -> }

        /** Blocks one below the other with [gap] between non-empty ones. */
        fun stack(blocks: List<Block>, gap: Float = 0f): Block {
            val list = blocks.filter { it.height > 0f }
            if (list.isEmpty()) return EMPTY
            val h = list.sumOf { it.height.toDouble() }.toFloat() + gap * (list.size - 1)
            return Block(h) { pen, x, top ->
                var t = top
                list.forEach { b -> b.draw(pen, x, t); t += b.height + gap }
            }
        }

        /** Two blocks side by side; [rightX] is the right block's offset from x. */
        fun beside(left: Block, right: Block, rightX: Float): Block =
            Block(maxOf(left.height, right.height)) { pen, x, top ->
                left.draw(pen, x, top)
                right.draw(pen, x + rightX, top)
            }

        fun spacer(h: Float) = Block(h) { _, _, _ -> }
    }
}

class Column<T>(
    val title: String,
    val align: Align,
    /** Flexible columns share the width left after fixed columns are sized to their content. */
    val flex: Boolean = false,
    val bold: Boolean = false,
    /** Lower bound for content-sized columns, so short figures do not crowd. */
    val minWidth: Float = 0f,
    val value: (T) -> String,
) {
    var width = 0f
}

/** Visual treatment of a table; templates differ mostly here and in headers. */
data class TableLook(
    val body: TextStyle,
    val head: TextStyle,
    val padH: Float = 4f,
    val padV: Float = 3.5f,
    val headPadV: Float = 4f,
    val headFill: Argb? = null,
    val headRadius: Float = 0f,
    val grid: Argb? = null,
    val gridWidth: Float = 0.6f,
    val rowRule: Argb? = null,
    val rowRuleWidth: Float = 0.5f,
    val zebra: Argb? = null,
    val zebraRadius: Float = 0f,
    val headTopRule: Argb? = null,
    val headBottomRule: Argb? = null,
    val headRuleWidth: Float = 0.8f,
    val maxFlexLines: Int = 4,
)

/** Measures and draws a table with content-sized numeric columns and wrapping flex columns. */
class Table<T>(
    private val measure: Pen,
    val columns: List<Column<T>>,
    val width: Float,
    val look: TableLook,
    sample: List<T>,
    extraRows: List<List<String>> = emptyList(),
) {
    init {
        val fixed = columns.filterNot { it.flex }
        fixed.forEachIndexed { _, c ->
            val idx = columns.indexOf(c)
            val headW = c.title.split(' ').maxOf { measure.width(it, look.head) }
            // Bold is the wider weight; total rows print figures in bold too.
            val bodyW = sample.maxOfOrNull { measure.width(c.value(it), look.body.bold()) } ?: 0f
            val extraW = extraRows.maxOfOrNull { r -> r.getOrNull(idx)?.let { measure.width(it, look.body.bold()) } ?: 0f } ?: 0f
            c.width = maxOf(maxOf(headW, bodyW, extraW) + 2 * look.padH, c.minWidth)
        }
        val flex = columns.filter { it.flex }
        val fixedTotal = fixed.sumOf { it.width.toDouble() }.toFloat()
        val minFlex = width * 0.2f
        if (flex.isNotEmpty()) {
            var remaining = width - fixedTotal
            if (remaining < minFlex) {
                // Squeeze numeric columns proportionally; their text shrinks to fit.
                val factor = (width - minFlex) / fixedTotal
                fixed.forEach { it.width *= factor }
                remaining = minFlex
            }
            flex.forEach { it.width = remaining / flex.size }
        } else if (fixedTotal > 0f) {
            val factor = width / fixedTotal
            fixed.forEach { it.width *= factor }
        }
    }

    private fun headLines(c: Column<T>) = measure.wrap(c.title, look.head, c.width - 2 * look.padH, 2, breakWords = false)

    val headerHeight: Float = (columns.maxOf { headLines(it).size }) * look.head.lineHeight + 2 * look.headPadV

    fun drawHeader(pen: Pen, x: Float, top: Float): Float {
        val h = headerHeight
        look.headFill?.let { pen.rect(x, top, width, h, fill = it, radius = look.headRadius) }
        look.headTopRule?.let { pen.hline(x, top, width, it, look.headRuleWidth) }
        look.headBottomRule?.let { pen.hline(x, top + h, width, it, look.headRuleWidth) }
        var cx = x
        columns.forEach { c ->
            val lines = headLines(c)
            val blockH = lines.size * look.head.lineHeight
            var t = top + (h - blockH) / 2f
            lines.forEach { line ->
                pen.text(line, anchor(c, cx), t + look.head.ascent, look.head, c.align, c.width - 2 * look.padH, shrink = true)
                t += look.head.lineHeight
            }
            cx += c.width
        }
        look.grid?.let { g -> grid(pen, x, top, h, g) }
        return h
    }

    private fun anchor(c: Column<T>, cx: Float) = when (c.align) {
        Align.LEFT -> cx + look.padH
        Align.CENTER -> cx + c.width / 2f
        Align.RIGHT -> cx + c.width - look.padH
    }

    private fun grid(pen: Pen, x: Float, top: Float, h: Float, g: Argb) {
        pen.rect(x, top, width, h, stroke = g, strokeWidth = look.gridWidth)
        var cx = x
        columns.dropLast(1).forEach { c ->
            cx += c.width
            pen.line(cx, top, cx, top + h, g, look.gridWidth)
        }
    }

    fun row(item: T, index: Int): Block {
        val cells = columns.map { c ->
            val style = if (c.bold) look.body.bold() else look.body
            if (c.flex) measure.wrap(c.value(item), style, c.width - 2 * look.padH, look.maxFlexLines) else listOf(c.value(item))
        }
        return cellsBlock(cells, index, bold = false)
    }

    /** A summary row (e.g. column totals) with one string per column. */
    fun totalRow(values: List<String>, fill: Argb? = null, topRule: Argb? = null, doubleBottom: Argb? = null): Block {
        val inner = cellsBlock(values.map { listOf(it) }, -1, bold = true)
        return Block(inner.height) { pen, x, top ->
            fill?.let { pen.rect(x, top, width, inner.height, fill = it) }
            inner.draw(pen, x, top)
            topRule?.let { pen.hline(x, top, width, it, 0.8f) }
            doubleBottom?.let {
                pen.hline(x, top + inner.height, width, it, 0.6f)
                pen.hline(x, top + inner.height + 1.8f, width, it, 0.6f)
            }
        }
    }

    private fun cellsBlock(cells: List<List<String>>, index: Int, bold: Boolean): Block {
        val lh = look.body.lineHeight
        val h = cells.maxOf { it.size } * lh + 2 * look.padV
        return Block(h) { pen, x, top ->
            if (index >= 0 && index % 2 == 1) look.zebra?.let { pen.rect(x, top, width, h, fill = it, radius = look.zebraRadius) }
            var cx = x
            columns.forEachIndexed { i, c ->
                val style = if (bold || c.bold) look.body.bold() else look.body
                var t = top + look.padV
                cells[i].forEach { line ->
                    pen.text(line, anchor(c, cx), t + look.body.ascent, style, c.align, c.width - 2 * look.padH, shrink = !c.flex)
                    t += lh
                }
                cx += c.width
            }
            look.grid?.let { g -> grid(pen, x, top, h, g) }
            look.rowRule?.let { pen.hline(x, top + h, width, it, look.rowRuleWidth) }
        }
    }

    /** The whole table as one block (for short tables such as tax summaries). */
    fun block(rows: List<T>, total: Block? = null): Block {
        val header = Block(headerHeight) { pen, x, top -> drawHeader(pen, x, top) }
        return Block.stack(listOf(header) + rows.mapIndexed { i, r -> row(r, i) } + listOfNotNull(total))
    }
}

/**
 * Shared page machinery: pagination with repeated table headers, "continued" notes, summary
 * blocks that never split, and "Page x of y" footers. Templates supply headers, looks and
 * summary blocks.
 */
abstract class BaseLayout(val input: LayoutInput, val paper: Paper, val copyLabel: String?) {
    val doc: InvoiceDocument get() = input.doc
    val opt: PrintOptions get() = input.style.options
    val accent: Argb get() = input.style.accent.argb

    /** Type scale: A5 shrinks everything so the same design fits half the area. */
    open val k: Float get() = if (paper == Paper.A5) 0.8f else 1f
    open val margin: Float get() = if (paper == Paper.A5) 22f else 36f
    val pageW: Float get() = paper.width
    val pageH: Float get() = paper.height
    val left: Float get() = margin
    val right: Float get() = pageW - margin
    val cw: Float get() = right - left
    open val footerH: Float get() = 20f * k
    open val gap: Float get() = 10f * k

    /** Measuring pen (its ops are discarded). */
    val m = Pen(input.metrics)

    fun ts(size: Float, role: FontRole = FontRole.REGULAR, color: Argb = Ink.BODY) = TextStyle(size * k, role, color)

    protected val pages = mutableListOf<Pen>()
    protected lateinit var pen: Pen
    protected var y = 0f
    private var pageContentTop = 0f
    /** Baseline of the footer line; content stops [footerH] above it. */
    open val footerBaseline: Float get() = pageH - minOf(margin, 32f * k)
    open val bottom: Float get() = footerBaseline - footerH

    protected fun newPage() {
        pen = Pen(input.metrics)
        pages += pen
        y = margin
    }

    abstract fun firstHeader()
    abstract fun lineTable(): Table<InvoiceDocument.Line>
    open fun lineTableEnd(table: Table<InvoiceDocument.Line>): Block? = null
    abstract fun summaryBlocks(): List<Block>

    /** Compact header on pages after the first. */
    open fun continuationHeader() {
        val name = ts(10f, FontRole.BOLD, Ink.BLACK)
        val meta = ts(8f, color = Ink.MUTED)
        pen.text(doc.seller.name, left, y + name.ascent, name, maxWidth = cw * 0.6f)
        pen.text("${doc.title} ${doc.number} (continued)", right, y + name.ascent, meta, Align.RIGHT, cw * 0.4f)
        y += name.lineHeight + 4f * k
        pen.hline(left, y, cw, Ink.RULE, 0.6f)
        y += 8f * k
    }

    open fun footer(pen: Pen, pageNo: Int, count: Int) {
        val st = ts(7f, color = Ink.MUTED)
        val base = footerBaseline
        pen.hline(left, base - st.size - 6f * k, cw, Ink.HAIRLINE, 0.5f)
        pen.text("${doc.title} ${doc.number}", left, base, st, maxWidth = cw / 3f)
        pen.text("Page $pageNo of $count", pageW / 2f, base, st, Align.CENTER)
        pen.text("Computer-generated invoice", right, base, st, Align.RIGHT, cw / 3f)
    }

    open fun continuedNote() {
        val st = ts(7.5f, color = Ink.MUTED)
        pen.text("Continued on next page", right, y + 4f * k + st.ascent, st, Align.RIGHT)
    }

    private fun startContinuationPage() {
        newPage()
        continuationHeader()
        pageContentTop = y
    }

    /** Places a block, moving to a new page first when it does not fit (unless the page is empty). */
    protected fun place(block: Block) {
        if (block.height <= 0f) return
        if (y + block.height > bottom && y > pageContentTop + 0.5f) startContinuationPage()
        block.draw(pen, left, y)
        y += block.height
    }

    open fun build(): List<PdfPage> {
        newPage()
        firstHeader()
        pageContentTop = y
        val table = lineTable()
        val rowsBottom = bottom - 14f * k
        y += table.drawHeader(pen, left, y)
        doc.lines.forEachIndexed { i, line ->
            val b = table.row(line, i)
            if (y + b.height > rowsBottom) {
                continuedNote()
                startContinuationPage()
                y += table.drawHeader(pen, left, y)
            }
            b.draw(pen, left, y)
            y += b.height
        }
        lineTableEnd(table)?.let { place(it) }
        summaryBlocks().forEach { b ->
            if (b.height > 0f) {
                y += gap
                place(b)
            }
        }
        val count = pages.size
        return pages.mapIndexed { i, p ->
            footer(p, i + 1, count)
            PdfPage(p.ops.toList(), pageW, pageH)
        }
    }

    // ---- Shared content helpers -------------------------------------------------------------

    /** Invoice meta rows honouring the print options. */
    fun metaRows(includeNumberDate: Boolean = true): List<Pair<String, String>> = buildList {
        if (includeNumberDate) {
            add("Invoice no." to doc.number)
            add("Invoice date" to doc.date)
        }
        if (opt.showDueDate) doc.dueDate?.let { add("Due date" to it) }
        add("Place of supply" to doc.placeOfSupply)
        add("Supply type" to if (doc.isIntraState) "Intra-state" else "Inter-state")
        add("Reverse charge" to "No")
    }

    fun partyLines(p: InvoiceDocument.PartyBlock, includeName: Boolean = false): List<Pair<String, Boolean>> = buildList {
        if (includeName) add(p.name to true)
        p.addressLines.forEach { add(it to false) }
        p.state?.let { add("State: $it" to false) }
        p.gstin?.let { add("GSTIN: $it" to true) }
        listOfNotNull(p.phone?.let { "Phone: $it" }, p.email).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { add(it to false) }
    }

    /** Wrapped party details as a block (name in bold, then address and tax ids). */
    fun partyBlock(
        p: InvoiceDocument.PartyBlock?,
        width: Float,
        nameStyle: TextStyle,
        bodyStyle: TextStyle,
        align: Align = Align.LEFT,
        emptyText: String = "Cash sale (no party)",
    ): Block {
        if (p == null) {
            val lines = m.wrap(emptyText, nameStyle, width)
            return Block(lines.size * nameStyle.lineHeight) { pen, x, top -> pen.paragraph(lines, ax(x, width, align), top, nameStyle, align) }
        }
        val name = m.wrap(p.name, nameStyle, width, 2)
        val rows = partyLines(p).flatMap { (t, bold) ->
            val st = if (bold) bodyStyle.bold() else bodyStyle
            m.wrap(t, st, width, 2).map { it to st }
        }
        val h = name.size * nameStyle.lineHeight + 1.5f * k + rows.size * bodyStyle.lineHeight
        return Block(h) { pen, x, top ->
            var t = top + pen.paragraph(name, ax(x, width, align), top, nameStyle, align) + 1.5f * k
            rows.forEach { (line, st) ->
                pen.text(line, ax(x, width, align), t + st.ascent, st, align)
                t += bodyStyle.lineHeight
            }
        }
    }

    fun ax(x: Float, width: Float, align: Align) = when (align) {
        Align.LEFT -> x
        Align.CENTER -> x + width / 2f
        Align.RIGHT -> x + width
    }

    /** Label/value rows; values right-aligned to [width] or set at a fixed [valueX]. */
    fun kvBlock(
        rows: List<Pair<String, String>>,
        width: Float,
        label: TextStyle,
        value: TextStyle,
        valueX: Float? = null,
        rowGap: Float = 1.5f * k,
    ): Block {
        if (rows.isEmpty()) return Block.EMPTY
        val lh = maxOf(label.lineHeight, value.lineHeight)
        val h = rows.size * lh + (rows.size - 1) * rowGap
        return Block(h) { pen, x, top ->
            var t = top
            rows.forEach { (kk, v) ->
                if (valueX == null) {
                    val vw = pen.text(v, x + width, t + value.ascent, value, Align.RIGHT, width * 0.62f, shrink = true)
                    pen.text(kk, x, t + label.ascent, label, maxWidth = width - vw - 6f)
                } else {
                    pen.text(kk, x, t + label.ascent, label, maxWidth = valueX - 4f)
                    pen.text(v, x + valueX, t + value.ascent, value, maxWidth = width - valueX)
                }
                t += lh + rowGap
            }
        }
    }

    /** A small heading followed by wrapped text. */
    fun noteBlock(title: String, text: String, width: Float, titleStyle: TextStyle, body: TextStyle, maxLines: Int = 12): Block {
        val lines = m.wrap(text, body, width, maxLines)
        return Block(titleStyle.lineHeight + 1f * k + lines.size * body.lineHeight) { pen, x, top ->
            pen.text(title, x, top + titleStyle.ascent, titleStyle, maxWidth = width)
            pen.paragraph(lines, x, top + titleStyle.lineHeight + 1f * k, body)
        }
    }

    fun wordsBlock(width: Float, titleStyle: TextStyle, body: TextStyle): Block = Block.stack(
        listOfNotNull(
            if (opt.showAmountInWords) noteBlock("Amount in words", doc.amountInWords, width, titleStyle, body, 3) else null,
            doc.partyBalance?.takeIf { opt.showPartyBalance }?.let { noteBlock("Party balance", it, width, titleStyle, body, 3) },
        ),
        gap = 4f * k,
    )

    fun notesAndTerms(width: Float, titleStyle: TextStyle, body: TextStyle): Block = Block.stack(
        listOfNotNull(
            doc.notes?.takeIf { opt.showNotes }?.let { noteBlock("Notes", it, width, titleStyle, body) },
            opt.terms.takeIf { opt.showTerms && it.isNotBlank() }?.let { noteBlock("Terms and conditions", it, width, titleStyle, body) },
        ),
        gap = 6f * k,
    )

    val upiId: String? get() = opt.upiId.trim().takeIf { it.isNotEmpty() && (opt.showBank || opt.showUpiQr) }

    /** Bank rows and UPI ID, with the payment QR to the right when present. */
    fun paymentBlock(width: Float, titleStyle: TextStyle, label: TextStyle, value: TextStyle, qrSize: Float = 62f * k): Block {
        val rows = (if (opt.showBank) doc.bankRows else emptyList()) + listOfNotNull(upiId?.let { "UPI ID" to it })
        val qr = input.qr
        if (rows.isEmpty() && qr == null) return Block.EMPTY
        val textW = if (qr != null) width - qrSize - 8f * k else width
        val kv = kvBlock(rows, textW, label, value, valueX = 48f * k)
        val title = if (rows.isEmpty()) "Scan to pay" else "Payment details"
        val textH = titleStyle.lineHeight + 2f * k + kv.height
        val h = maxOf(textH, if (qr != null) qrSize + label.lineHeight else 0f)
        return Block(h) { pen, x, top ->
            pen.text(title, x, top + titleStyle.ascent, titleStyle)
            kv.draw(pen, x, top + titleStyle.lineHeight + 2f * k)
            if (qr != null) {
                val qx = x + width - qrSize
                pen.qr(qr, qx, top, qrSize)
                pen.text("Scan to pay with UPI", qx + qrSize / 2f, top + qrSize + label.ascent, label.sized(label.size * 0.9f), Align.CENTER, qrSize, shrink = true)
            }
        }
    }

    /** "For <firm>", optional signature image, then the signatory line. */
    fun signatureBlock(width: Float, nameStyle: TextStyle, label: TextStyle, rule: Argb = Ink.RULE, align: Align = Align.RIGHT): Block {
        val sigH = 32f * k
        val name = m.wrap("For ${doc.seller.name}", nameStyle, width, 2)
        val h = name.size * nameStyle.lineHeight + sigH + 4f * k + label.lineHeight
        return Block(h) { pen, x, top ->
            val anchorX = ax(x, width, align)
            var t = top + pen.paragraph(name, anchorX, top, nameStyle, align)
            input.signature?.let { sig ->
                val bw = minOf(width, 130f * k)
                val bx = when (align) { Align.RIGHT -> x + width - bw; Align.CENTER -> x + (width - bw) / 2f; Align.LEFT -> x }
                pen.image(sig.fit(bx, t + 2f * k, bw, sigH - 4f * k, ImageKind.SIGNATURE, alignX = if (align == Align.RIGHT) 1f else if (align == Align.CENTER) 0.5f else 0f))
            }
            t += sigH
            val lw = minOf(width, 140f * k)
            val lx = when (align) { Align.RIGHT -> x + width - lw; Align.CENTER -> x + (width - lw) / 2f; Align.LEFT -> x }
            pen.hline(lx, t, lw, rule, 0.6f)
            pen.text("Authorised signatory", anchorX, t + 4f * k + label.ascent, label, align)
        }
    }

    // ---- Tables ------------------------------------------------------------------------------

    val showDiscount: Boolean get() = opt.showDiscount && doc.hasDiscount
    val showHsn: Boolean get() = opt.showHsn

    /** The standard GST line columns, filtered by options. */
    fun standardColumns(withTax: Boolean = true, withTaxable: Boolean = true): List<Column<InvoiceDocument.Line>> = buildList {
        add(Column("#", Align.CENTER) { it.index.toString() })
        add(Column("Item", Align.LEFT, flex = true) { it.description })
        if (showHsn) add(Column("HSN/SAC", Align.LEFT) { it.hsnSac.ifEmpty { "-" } })
        add(Column("Qty", Align.RIGHT) { it.quantity })
        add(Column("Rate", Align.RIGHT) { it.rate })
        if (showDiscount) add(Column("Discount", Align.RIGHT) { it.discount })
        if (withTaxable) add(Column("Taxable value", Align.RIGHT) { it.taxable })
        add(Column("GST", Align.RIGHT) { it.gstRate })
        if (withTax) add(Column("Tax", Align.RIGHT) { it.tax })
        add(Column("Amount", Align.RIGHT, bold = true) { it.amount })
    }

    fun lineTable(columns: List<Column<InvoiceDocument.Line>>, look: TableLook, extra: List<List<String>> = emptyList()) =
        Table(m, columns, cw, look, doc.lines, extra)

    /** Rate-wise (or HSN-wise) GST summary table. */
    fun taxSummaryTable(rows: List<InvoiceDocument.TaxRow>, width: Float, look: TableLook, firstTitle: String, hsn: Boolean = false): Table<InvoiceDocument.TaxRow> {
        val cols = buildList<Column<InvoiceDocument.TaxRow>> {
            add(Column(firstTitle, Align.LEFT, flex = true) { it.label })
            val mw = if (width > 300f) 58f * k else 0f
            if (hsn) add(Column("Rate", Align.LEFT, minWidth = mw * 0.6f) { it.rate.removePrefix("GST ") })
            add(Column("Taxable value", Align.RIGHT, minWidth = mw) { it.taxable })
            if (doc.isIntraState) {
                add(Column("CGST", Align.RIGHT, minWidth = mw) { it.cgst })
                add(Column("SGST/UTGST", Align.RIGHT, minWidth = mw) { it.sgst })
            } else {
                add(Column("IGST", Align.RIGHT, minWidth = mw) { it.igst })
            }
            if (doc.hasCess) add(Column("Cess", Align.RIGHT, minWidth = mw) { it.cess })
            add(Column("Total tax", Align.RIGHT, bold = true, minWidth = mw) { it.totalTax })
        }
        return Table(m, cols, width, look, rows + listOfNotNull(doc.taxTotal))
    }

    fun taxTotalValues(table: Table<InvoiceDocument.TaxRow>): List<String> =
        doc.taxTotal?.let { t -> table.columns.mapIndexed { i, c -> if (i == 0) "Total" else if (c.title == "Rate") "" else c.value(t) } } ?: emptyList()

    /** Title + table of the GST summary, or empty when switched off. */
    fun taxSummaryBlock(
        width: Float,
        look: TableLook,
        titleStyle: TextStyle,
        hsn: Boolean = false,
        totalFill: Argb? = null,
        totalTopRule: Argb? = null,
    ): Block {
        if (!opt.showTaxSummary) return Block.EMPTY
        val rows = if (hsn) doc.hsnSummary else doc.rateSummary
        if (rows.isEmpty()) return Block.EMPTY
        val table = taxSummaryTable(rows, width, look, if (hsn) "HSN/SAC" else "GST rate", hsn)
        val body = table.block(rows, if (rows.size > 1) table.totalRow(taxTotalValues(table), totalFill, totalTopRule) else null)
        val title = Block(titleStyle.lineHeight + 2f * k) { pen, x, top ->
            pen.text(if (hsn) "HSN/SAC summary" else "GST summary", x, top + titleStyle.ascent, titleStyle)
        }
        return Block.stack(listOf(title, body))
    }

    /** Copy label and document status, for headers. */
    fun statusText(): String? = doc.statusNote
}
