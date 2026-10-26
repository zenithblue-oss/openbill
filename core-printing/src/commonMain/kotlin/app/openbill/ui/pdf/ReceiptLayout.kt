package app.openbill.ui.pdf

/**
 * Roll-paper receipt (58 mm or 80 mm). One continuous page whose height follows the content,
 * black only, bold weights instead of colour, and no hairlines thinner than a printer dot.
 */
class ReceiptLayout(
    input: LayoutInput,
    paper: Paper,
    copy: String?,
    private val withTax: Boolean,
) : BaseLayout(input, paper, copy) {
    private val narrow get() = paper == Paper.THERMAL_58

    /** 80 mm rolls print about 72 mm wide, 58 mm rolls about 48 mm. */
    override val margin: Float get() = if (narrow) 14f else 11f
    override val k: Float get() = if (narrow) 0.84f else 1f
    override val bottom: Float get() = Float.MAX_VALUE

    private val body get() = ts(8f, color = Ink.BLACK)
    private val bold get() = ts(8f, FontRole.BOLD, Ink.BLACK)

    private fun dashed() {
        y += 3f * k
        var x = left
        val dash = 3f
        while (x < right) {
            pen.line(x, y, minOf(x + dash, right), y, Ink.BLACK, 0.7f)
            x += dash * 2
        }
        y += 5f * k
    }

    private fun center(text: String, style: TextStyle, maxLines: Int = 3) {
        y += pen.paragraph(m.wrap(text, style, cw, maxLines), pageW / 2f, y, style, Align.CENTER)
    }

    private fun row(label: String, value: String, style: TextStyle = body) {
        val vw = pen.text(value, right, y + style.ascent, style, Align.RIGHT, cw * 0.6f, shrink = true)
        pen.text(label, left, y + style.ascent, style, maxWidth = cw - vw - 4f)
        y += style.lineHeight
    }

    override fun firstHeader() {}
    override fun lineTable(): Table<app.openbill.ui.invoice.InvoiceDocument.Line> = throw UnsupportedOperationException()
    override fun summaryBlocks(): List<Block> = emptyList()

    override fun build(): List<PdfPage> {
        newPage()
        y = margin
        input.logo?.let {
            val img = it.fit(left, y, cw, 34f * k, ImageKind.LOGO, 0.5f)
            pen.image(img)
            y += img.h + 4f * k
        }
        center(doc.seller.name, ts(11f, FontRole.BOLD, Ink.BLACK), 2)
        doc.seller.addressLines.forEach { center(it, body, 2) }
        doc.seller.gstin?.let { center("GSTIN: $it", bold) }
        doc.seller.phone?.let { center("Ph: $it", body) }
        dashed()
        center(doc.title.uppercase(), ts(9f, FontRole.BOLD, Ink.BLACK))
        copyLabel?.let { center(it, ts(7f, color = Ink.BLACK)) }
        statusText()?.let { center(it, bold) }
        y += 2f * k
        row("No. ${doc.number}", doc.date, bold)
        if (opt.showDueDate) doc.dueDate?.let { row("Due", it) }
        val buyer = doc.buyer
        if (buyer != null) {
            y += pen.paragraph(m.wrap("To: ${buyer.name}", bold, cw, 2), left, y, bold)
            buyer.gstin?.let { row("GSTIN", it) }
            buyer.phone?.let { row("Phone", it) }
        }
        row("Place of supply", doc.placeOfSupply.substringBefore(" ("))
        dashed()

        // Items: name on its own line(s), then qty x rate and the amount.
        val head = ts(7.4f, FontRole.BOLD, Ink.BLACK)
        pen.text("Item", left, y + head.ascent, head)
        pen.text("Amount", right, y + head.ascent, head, Align.RIGHT)
        y += head.lineHeight
        doc.lines.forEach { line ->
            val name = if (opt.showHsn && line.hsnSac.isNotEmpty()) "${line.description} (${line.hsnSac})" else line.description
            y += pen.paragraph(m.wrap("${line.index}. $name", body, cw, 3), left, y, body)
            val small = ts(7.4f, color = Ink.BLACK)
            val detail = buildString {
                append("${line.quantity} x ${line.rate}")
                if (opt.showDiscount && line.discount != "-") append("  -${line.discount}")
                if (withTax) append("  GST ${line.gstRate}")
            }
            val aw = pen.text(line.amount, right, y + small.ascent, bold.sized(small.size), Align.RIGHT, cw * 0.45f, shrink = true)
            pen.text(detail, left + 8f * k, y + small.ascent, small, maxWidth = cw - aw - 12f * k)
            y += small.lineHeight + 2f * k
        }
        dashed()
        doc.totals.forEach { (l, v) -> row(l, v) }
        val big = ts(12f, FontRole.BOLD, Ink.BLACK)
        y += 2f * k
        row("TOTAL", doc.grandTotal, big)
        if (opt.showAmountInWords) {
            y += 2f * k
            y += pen.paragraph(m.wrap(doc.amountInWords, ts(7.2f, color = Ink.BLACK), cw, 4), left, y, ts(7.2f, color = Ink.BLACK))
        }
        doc.partyBalance?.takeIf { opt.showPartyBalance }?.let {
            y += 2f * k
            y += pen.paragraph(m.wrap(it, ts(7.2f, color = Ink.BLACK), cw, 4), left, y, ts(7.2f, color = Ink.BLACK))
        }
        if (withTax && opt.showTaxSummary && doc.rateSummary.isNotEmpty()) {
            dashed()
            val look = TableLook(
                body = ts(6.8f, color = Ink.BLACK),
                head = ts(6.4f, FontRole.BOLD, Ink.BLACK),
                padH = 1.5f,
                padV = 1.2f,
                headPadV = 1.5f,
                headBottomRule = Ink.BLACK,
                headRuleWidth = 0.6f,
            )
            val table = taxSummaryTable(doc.rateSummary, cw, look, "Rate")
            val blk = table.block(doc.rateSummary, if (doc.rateSummary.size > 1) table.totalRow(taxTotalValues(table), topRule = Ink.BLACK) else null)
            blk.draw(pen, left, y)
            y += blk.height
        }
        val pay = paymentBlock(cw, bold.sized(7.4f), ts(7f, color = Ink.BLACK), ts(7f, FontRole.BOLD, Ink.BLACK), qrSize = 0f)
        if (opt.showBank && doc.bankRows.isNotEmpty() || upiId != null) {
            dashed()
            if (input.qr == null) {
                pay.draw(pen, left, y)
                y += pay.height
            } else {
                upiId?.let { center("UPI: $it", bold) }
                val size = minOf(cw * 0.62f, 110f)
                pen.qr(input.qr, pageW / 2f - size / 2f, y + 2f, size)
                y += size + 4f
                center("Scan to pay ${doc.grandTotal}", body)
            }
        }
        val notes = notesAndTerms(cw, bold.sized(7.4f), ts(7.2f, color = Ink.BLACK))
        if (notes.height > 0f) {
            dashed()
            notes.draw(pen, left, y)
            y += notes.height
        }
        dashed()
        input.signature?.let {
            val img = it.fit(left, y, cw, 30f * k, ImageKind.SIGNATURE, 0.5f)
            pen.image(img)
            y += img.h + 2f
        }
        center("For ${doc.seller.name}", ts(7.2f, color = Ink.BLACK), 2)
        y += 4f * k
        center("Thank you. Please visit again.", bold)
        y += margin
        return listOf(PdfPage(pen.ops.toList(), pageW, y))
    }
}
