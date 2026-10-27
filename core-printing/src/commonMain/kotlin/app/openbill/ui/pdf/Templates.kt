package app.openbill.ui.pdf

import app.openbill.ui.invoice.InvoiceDocument

/*
 * OpenBill's built-in page templates. Each is an original layout; see InvoiceTemplate for the
 * one-line descriptions shown in the gallery.
 */

/** Draws [content] inside a padded box (stroke/fill/radius), stretched to [minHeight]. */
fun boxed(
    content: Block,
    width: Float,
    pad: Float,
    stroke: Argb? = null,
    fill: Argb? = null,
    radius: Float = 0f,
    minHeight: Float = 0f,
    strokeWidth: Float = 0.6f,
): Block {
    val h = maxOf(content.height + 2 * pad, minHeight)
    return Block(h) { pen, x, top ->
        if (fill != null || stroke != null) pen.rect(x, top, width, h, fill = fill, stroke = stroke, strokeWidth = strokeWidth, radius = radius)
        content.draw(pen, x + pad, top + pad)
    }
}

/** Two boxes of equal height side by side. */
fun boxedPair(
    leftContent: Block,
    leftW: Float,
    rightContent: Block,
    rightW: Float,
    pad: Float,
    stroke: Argb?,
    gap: Float = 0f,
    fill: Argb? = null,
    radius: Float = 0f,
): Block {
    val h = maxOf(leftContent.height, rightContent.height) + 2 * pad
    val l = boxed(leftContent, leftW, pad, stroke, fill, radius, h)
    val r = boxed(rightContent, rightW, pad, stroke, fill, radius, h)
    return Block.beside(l, r, leftW + gap)
}

fun BaseLayout.label(text: String, style: TextStyle, after: Float = 2f * k): Block =
    Block(style.lineHeight + after) { pen, x, top -> pen.text(text, x, top + style.ascent, style) }

// ---------------------------------------------------------------------------------------------
// GST Detailed: a fully bordered grid that puts every statutory field in its own cell.
// ---------------------------------------------------------------------------------------------

class GstDetailedLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    private val g = 0xFF9EA9AD.toInt()
    private val headFill get() = Ink.tint(accent, 0.86f)
    private val dark get() = Ink.shade(accent, 0.2f)
    private val pad get() = 6f * k
    private val labelSt get() = ts(6.8f, FontRole.BOLD, dark)

    override fun firstHeader() {
        val titleSt = ts(12f, FontRole.BOLD, dark)
        val small = ts(7f, color = Ink.MUTED)
        val barH = 22f * k
        pen.rect(left, y, cw, barH, fill = headFill, stroke = g)
        val mid = y + barH / 2f + titleSt.size * 0.36f
        pen.text(doc.title.uppercase(), pageW / 2f, mid, titleSt, Align.CENTER)
        copyLabel?.let { pen.text(it, right - pad, y + barH / 2f + small.size * 0.36f, small, Align.RIGHT, cw * 0.3f) }
        statusText()?.let { pen.text(it, left + pad, y + barH / 2f + small.size * 0.36f, small.bold().color(Ink.ERROR), maxWidth = cw * 0.3f) }
        y += barH

        val sellerW = cw * 0.58f
        val metaW = cw - sellerW
        val logo = input.logo
        val logoBox = 50f * k
        val textOffset = if (logo != null) logoBox + 8f * k else 0f
        val seller = partyBlock(doc.seller, sellerW - 2 * pad - textOffset, ts(12f, FontRole.BOLD, Ink.BLACK), ts(7.8f))
        val meta = kvBlock(metaRows(), metaW - 2 * pad, ts(7.8f, color = Ink.MUTED), ts(7.8f, FontRole.BOLD, Ink.BLACK))
        val h = maxOf(seller.height, meta.height, if (logo != null) logoBox else 0f) + 2 * pad
        pen.rect(left, y, sellerW, h, stroke = g)
        pen.rect(left + sellerW, y, metaW, h, stroke = g)
        logo?.let { pen.image(it.fit(left + pad, y + pad, logoBox, logoBox, ImageKind.LOGO, 0.5f)) }
        seller.draw(pen, left + pad + textOffset, y + pad)
        meta.draw(pen, left + sellerW + pad, y + pad)
        y += h

        val half = cw / 2f
        val name = ts(9.5f, FontRole.BOLD, Ink.BLACK)
        val body = ts(7.8f)
        val bill = Block.stack(listOf(label("BILL TO", labelSt), partyBlock(doc.buyer, half - 2 * pad, name, body)))
        val shipContent = doc.shipTo?.let { partyBlock(it, half - 2 * pad, name, body) }
            ?: noteBlock("", if (doc.buyer == null) "-" else "Same as billing address", half - 2 * pad, ts(1f), body)
        val ship = Block.stack(listOf(label("SHIP TO", labelSt), shipContent))
        val parties = boxedPair(bill, half, ship, half, pad, g)
        parties.draw(pen, left, y)
        y += parties.height
    }

    override fun lineTable() = lineTable(standardColumns(), look())

    private fun look() = TableLook(
        body = ts(7.6f, color = Ink.BLACK),
        head = ts(7f, FontRole.BOLD, dark),
        padH = 3.5f * k,
        padV = 3.2f * k,
        headFill = Ink.tint(accent, 0.92f),
        grid = g,
    )

    override val gap: Float get() = 0f

    override fun summaryBlocks(): List<Block> {
        val leftW = cw * 0.58f
        val rightW = cw - leftW
        val title = labelSt
        val body = ts(7.8f)
        val leftContent = Block.stack(
            listOf(
                wordsBlock(leftW - 2 * pad, title, body.bold()),
                paymentBlock(leftW - 2 * pad, title, ts(7.5f, color = Ink.MUTED), ts(7.5f, FontRole.BOLD, Ink.BLACK)),
                notesAndTerms(leftW - 2 * pad, title, ts(7.4f)),
            ),
            gap = 6f * k,
        )
        val totals = kvBlock(doc.totals, rightW - 2 * pad, ts(7.8f, color = Ink.MUTED), ts(7.8f, color = Ink.BLACK))
        val totalSt = ts(10f, FontRole.BOLD, Ink.WHITE)
        val grand = Block(totalSt.lineHeight + 8f * k) { pen, x, top ->
            val bh = totalSt.lineHeight + 6f * k
            pen.rect(x - pad, top + 2f * k, rightW, bh, fill = accent)
            pen.text("Total", x, top + 5f * k + totalSt.ascent, totalSt)
            pen.text(doc.grandTotal, x + rightW - 2 * pad, top + 5f * k + totalSt.ascent, totalSt, Align.RIGHT, rightW * 0.7f, shrink = true)
        }
        val sumLook = look().copy(body = ts(7f, color = Ink.BLACK), head = ts(6.6f, FontRole.BOLD, dark), padV = 2.2f * k, headPadV = 2.6f * k, padH = 3f * k)
        // Rate-wise breakup sits under the totals; the HSN summary spans the page below.
        val rate = taxSummaryBlock(rightW - 2 * pad, sumLook, title, totalFill = Ink.SHADE)
        val rightContent = Block.stack(listOf(totals, grand, rate), gap = 4f * k)
        val first = boxedPair(leftContent, leftW, rightContent, rightW, pad, g)

        val hsn = taxSummaryBlock(cw - 2 * pad, sumLook, title, hsn = true, totalFill = Ink.SHADE)
        val taxBox = if (hsn.height > 0f) boxed(hsn, cw, pad, g) else Block.EMPTY

        val receiver = label("RECEIVER'S SIGNATURE", ts(6.5f, FontRole.BOLD, Ink.FAINT))
        val sign = signatureBlock(rightW - 2 * pad, ts(8f, FontRole.BOLD, Ink.BLACK), ts(7.2f, color = Ink.MUTED))
        val last = boxedPair(receiver, leftW, sign, rightW, pad, g)
        return listOf(first, taxBox, last)
    }
}

// ---------------------------------------------------------------------------------------------
// Ledger: accounting columns with each tax head on every line and a column-total row.
// ---------------------------------------------------------------------------------------------

class LedgerLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    private val dark get() = Ink.shade(accent, 0.1f)
    private val labelSt get() = ts(6.6f, FontRole.BOLD, dark)

    override fun firstHeader() {
        val logo = input.logo
        val logoH = 40f * k
        var sx = left
        if (logo != null) {
            val img = logo.fit(left, y, 90f * k, logoH, ImageKind.LOGO)
            pen.image(img)
            sx = left + img.w + 10f * k
        }
        val sellerW = cw * 0.6f - (sx - left)
        val seller = partyBlock(doc.seller, sellerW, ts(13f, FontRole.BOLD, Ink.BLACK), ts(7.8f, color = Ink.MUTED))
        seller.draw(pen, sx, y)
        val titleSt = ts(15f, FontRole.BOLD, accent)
        var ry = y + titleSt.ascent
        pen.text(doc.title.uppercase(), right, ry, titleSt, Align.RIGHT)
        ry += 6f * k
        copyLabel?.let { ry += 9f * k; pen.text(it.uppercase(), right, ry, ts(6.5f, FontRole.BOLD, Ink.MUTED), Align.RIGHT) }
        val meta = ts(8.2f, color = Ink.BLACK)
        ry += 13f * k
        pen.text("No.  ${doc.number}", right, ry, meta.bold(), Align.RIGHT)
        ry += 11f * k
        pen.text("Date  ${doc.date}", right, ry, meta, Align.RIGHT)
        statusText()?.let { ry += 11f * k; pen.text(it, right, ry, ts(8f, FontRole.BOLD, Ink.ERROR), Align.RIGHT, cw * 0.4f) }
        y = maxOf(y + seller.height, ry + 4f * k, y + if (logo != null) logoH else 0f) + 6f * k
        pen.hline(left, y, cw, dark, 1.2f)
        pen.hline(left, y + 2.4f, cw, dark, 0.5f)
        y += 10f * k

        val colW = (cw - 2 * 14f * k) / 3f
        val name = ts(9f, FontRole.BOLD, Ink.BLACK)
        val body = ts(7.6f)
        val bill = Block.stack(listOf(label("BILLED TO", labelSt), partyBlock(doc.buyer, colW, name, body)))
        val ship = Block.stack(
            listOf(
                label("SHIPPED TO", labelSt),
                doc.shipTo?.let { partyBlock(it, colW, name, body) } ?: noteBlock("", "Same as billing address", colW, ts(1f), body),
            ),
        )
        val details = Block.stack(listOf(label("SUPPLY DETAILS", labelSt), kvBlock(metaRows(false), colW, ts(7.6f, color = Ink.MUTED), ts(7.6f, FontRole.BOLD, Ink.BLACK))))
        bill.draw(pen, left, y)
        ship.draw(pen, left + colW + 14f * k, y)
        details.draw(pen, left + 2 * (colW + 14f * k), y)
        y += maxOf(bill.height, ship.height, details.height) + 10f * k
    }

    private fun columns() = buildList<Column<InvoiceDocument.Line>> {
        add(Column("#", Align.CENTER) { it.index.toString() })
        add(Column("Particulars", Align.LEFT, flex = true) { it.description })
        if (showHsn) add(Column("HSN/SAC", Align.LEFT) { it.hsnSac.ifEmpty { "-" } })
        add(Column("Qty", Align.RIGHT) { it.quantity })
        add(Column("Rate", Align.RIGHT) { it.rate })
        if (showDiscount) add(Column("Disc.", Align.RIGHT) { it.discount })
        add(Column("Taxable value", Align.RIGHT) { it.taxable })
        add(Column("GST", Align.RIGHT) { it.gstRate })
        if (doc.isIntraState) {
            add(Column("CGST", Align.RIGHT) { it.cgst })
            add(Column("SGST", Align.RIGHT) { it.sgst })
        } else {
            add(Column("IGST", Align.RIGHT) { it.igst })
        }
        add(Column("Amount", Align.RIGHT, bold = true) { it.amount })
    }

    private fun look() = TableLook(
        body = ts(7.5f, color = Ink.BLACK),
        head = ts(6.8f, FontRole.BOLD, Ink.BLACK),
        padH = 3.2f * k,
        padV = 3f * k,
        headFill = Ink.SHADE,
        grid = Ink.RULE,
        gridWidth = 0.5f,
    )

    private fun totalValues(cols: List<Column<InvoiceDocument.Line>>): List<String> {
        val t = doc.lineTotals ?: return emptyList()
        return cols.map { c ->
            when (c.title) {
                "Particulars" -> "Total"
                "Qty" -> doc.totalQuantity
                "#", "HSN/SAC", "Rate", "GST" -> ""
                else -> c.value(t)
            }
        }
    }

    override fun lineTable(): Table<InvoiceDocument.Line> {
        val cols = columns()
        return lineTable(cols, look(), listOf(totalValues(cols)))
    }

    override fun lineTableEnd(table: Table<InvoiceDocument.Line>): Block? =
        totalValues(table.columns).takeIf { it.isNotEmpty() }?.let { table.totalRow(it, fill = Ink.tint(accent, 0.9f), doubleBottom = dark) }

    override fun summaryBlocks(): List<Block> {
        val rightW = cw * 0.4f
        val leftW = cw - rightW - 20f * k
        val title = labelSt
        val left = Block.stack(
            listOf(
                wordsBlock(leftW, title, ts(8f, FontRole.BOLD, Ink.BLACK)),
                paymentBlock(leftW, title, ts(7.5f, color = Ink.MUTED), ts(7.5f, FontRole.BOLD, Ink.BLACK)),
            ),
            gap = 8f * k,
        )
        val totals = kvBlock(doc.totals, rightW, ts(8f, color = Ink.MUTED), ts(8f, color = Ink.BLACK))
        val totalSt = ts(11f, FontRole.BOLD, Ink.BLACK)
        val grand = Block(totalSt.lineHeight + 10f * k) { pen, x, top ->
            pen.hline(x, top + 2f * k, rightW, Ink.BLACK, 0.6f)
            pen.text("Invoice total", x, top + 5f * k + totalSt.ascent, totalSt)
            pen.text(doc.grandTotal, x + rightW, top + 5f * k + totalSt.ascent, totalSt.color(dark), Align.RIGHT, rightW * 0.6f, shrink = true)
            val b = top + totalSt.lineHeight + 7f * k
            pen.hline(x, b, rightW, Ink.BLACK, 0.6f)
            pen.hline(x, b + 1.8f, rightW, Ink.BLACK, 0.6f)
        }
        val right = Block.stack(listOf(totals, grand), gap = 2f * k)
        val first = Block.beside(left, right, leftW + 20f * k)
        val sumLook = look().copy(body = ts(7.2f, color = Ink.BLACK), headFill = Ink.SHADE)
        val tax = taxSummaryBlock(cw, sumLook, title, totalFill = Ink.tint(accent, 0.9f))
        val notes = notesAndTerms(leftW, title, ts(7.4f))
        val sign = signatureBlock(rightW, ts(8f, FontRole.BOLD, Ink.BLACK), ts(7.2f, color = Ink.MUTED))
        return listOf(first, tax, Block.beside(notes, sign, leftW + 20f * k))
    }
}

// ---------------------------------------------------------------------------------------------
// Simple: monochrome, generous whitespace and hairlines only. Ignores the accent colour.
// ---------------------------------------------------------------------------------------------

class SimpleLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    override val margin: Float get() = if (paper == Paper.A5) 24f else 46f
    private val labelSt get() = ts(6.4f, FontRole.BOLD, Ink.FAINT)

    override fun firstHeader() {
        val logo = input.logo
        var top = y
        if (logo != null) {
            val img = logo.fit(left, y, 110f * k, 34f * k, ImageKind.LOGO)
            pen.image(img)
            top += img.h + 8f * k
        }
        val seller = partyBlock(doc.seller, cw * 0.55f, ts(12.5f, FontRole.BOLD, Ink.BLACK), ts(7.8f, color = Ink.MUTED))
        seller.draw(pen, left, top)
        val titleSt = ts(22f, color = Ink.BLACK)
        var ry = y + titleSt.ascent
        pen.text(doc.title, right, ry, titleSt, Align.RIGHT)
        ry += 14f * k
        pen.text(doc.number, right, ry, ts(9f, FontRole.BOLD, Ink.BLACK), Align.RIGHT)
        copyLabel?.let { ry += 12f * k; pen.text(it, right, ry, ts(7f, color = Ink.MUTED), Align.RIGHT) }
        statusText()?.let { ry += 12f * k; pen.text(it, right, ry, ts(8f, FontRole.BOLD, Ink.ERROR), Align.RIGHT, cw * 0.42f) }
        y = maxOf(top + seller.height, ry) + 16f * k
        pen.hline(left, y, cw, Ink.BLACK, 0.5f)
        y += 14f * k

        val colGap = 16f * k
        val colW = (cw - 2 * colGap) / 3f
        val bill = Block.stack(listOf(label("BILLED TO", labelSt, 4f * k), partyBlock(doc.buyer, colW, ts(9f, FontRole.BOLD, Ink.BLACK), ts(7.6f, color = Ink.BODY))))
        val date = Block.stack(
            listOf(
                label("DATE", labelSt, 4f * k),
                kvBlock(
                    listOfNotNull("Issued" to doc.date, doc.dueDate?.takeIf { opt.showDueDate }?.let { "Due" to it }),
                    colW, ts(7.6f, color = Ink.MUTED), ts(7.6f, color = Ink.BLACK), valueX = 36f * k,
                ),
            ),
        )
        val supply = Block.stack(
            listOf(
                label("SUPPLY", labelSt, 4f * k),
                kvBlock(
                    listOf("Place" to doc.placeOfSupply, "Type" to if (doc.isIntraState) "Intra-state" else "Inter-state", "Reverse charge" to "No"),
                    colW, ts(7.6f, color = Ink.MUTED), ts(7.6f, color = Ink.BLACK), valueX = 68f * k,
                ),
            ),
        )
        bill.draw(pen, left, y)
        date.draw(pen, left + colW + colGap, y)
        supply.draw(pen, left + 2 * (colW + colGap), y)
        y += maxOf(bill.height, date.height, supply.height) + 18f * k
        doc.shipTo?.let {
            val ship = Block.stack(listOf(label("SHIPPED TO", labelSt, 4f * k), partyBlock(it, cw / 2f, ts(8.5f, FontRole.BOLD, Ink.BLACK), ts(7.6f))))
            ship.draw(pen, left, y)
            y += ship.height + 14f * k
        }
    }

    private fun look() = TableLook(
        body = ts(7.8f, color = Ink.BLACK),
        head = ts(6.6f, FontRole.BOLD, Ink.MUTED),
        padH = 3.5f * k,
        padV = 5f * k,
        headPadV = 5f * k,
        headBottomRule = Ink.BLACK,
        headRuleWidth = 0.5f,
        rowRule = Ink.HAIRLINE,
    )

    override fun lineTable() = lineTable(standardColumns(), look())

    override val gap: Float get() = 14f * k

    override fun summaryBlocks(): List<Block> {
        val rightW = minOf(220f * k, cw * 0.45f)
        val leftW = cw - rightW - 24f * k
        val totals = kvBlock(doc.totals, rightW, ts(8f, color = Ink.MUTED), ts(8f, color = Ink.BLACK), rowGap = 3f * k)
        val totalSt = ts(13f, FontRole.BOLD, Ink.BLACK)
        val grand = Block(totalSt.lineHeight + 12f * k) { pen, x, top ->
            pen.hline(x, top + 3f * k, rightW, Ink.BLACK, 0.5f)
            pen.text("Total", x, top + 8f * k + totalSt.ascent, ts(9f, FontRole.BOLD, Ink.BLACK))
            pen.text(doc.grandTotal, x + rightW, top + 8f * k + totalSt.ascent, totalSt, Align.RIGHT, rightW * 0.7f, shrink = true)
        }
        val right = Block.stack(listOf(totals, grand), gap = 2f * k)
        val left = Block.stack(
            listOf(
                wordsBlock(leftW, labelSt, ts(7.8f, color = Ink.BLACK)),
                paymentBlock(leftW, labelSt, ts(7.4f, color = Ink.MUTED), ts(7.4f, color = Ink.BLACK)),
            ),
            gap = 12f * k,
        )
        val tax = taxSummaryBlock(cw, look().copy(body = ts(7.2f, color = Ink.BLACK), padV = 3.5f * k), labelSt, totalTopRule = Ink.BLACK)
        val notes = notesAndTerms(leftW, labelSt, ts(7.4f, color = Ink.BODY))
        val sign = signatureBlock(rightW, ts(8f, FontRole.BOLD, Ink.BLACK), ts(7.2f, color = Ink.MUTED), Ink.BLACK)
        return listOf(Block.beside(left, right, leftW + 24f * k), tax, Block.beside(notes, sign, leftW + 24f * k))
    }
}

// ---------------------------------------------------------------------------------------------
// Modern: full-bleed colour band, soft cards, zebra rows and a highlighted total card.
// ---------------------------------------------------------------------------------------------

class ModernLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    override val margin: Float get() = 40f
    private val soft get() = Ink.tint(accent, 0.93f)
    private val dark get() = Ink.shade(accent, 0.25f)
    private val onBand get() = Ink.tint(accent, 0.78f)
    private val labelSt get() = ts(6.8f, FontRole.BOLD, accent)

    override fun firstHeader() {
        val bandH = 118f * k
        pen.rect(0f, 0f, pageW, bandH, fill = accent)
        pen.rect(0f, bandH, pageW, 3f * k, fill = Ink.MARIGOLD)
        val logo = input.logo
        var sx = left
        val top = 30f * k
        if (logo != null) {
            val tile = 54f * k
            pen.rect(left, top, tile, tile, fill = Ink.WHITE, radius = 10f * k)
            pen.image(logo.fit(left + 5f * k, top + 5f * k, tile - 10f * k, tile - 10f * k, ImageKind.LOGO, 0.5f))
            sx = left + tile + 12f * k
        }
        val sellerW = cw * 0.58f - (sx - left)
        val nameSt = ts(15f, FontRole.BOLD, Ink.WHITE)
        val small = ts(7.6f, color = onBand)
        val name = m.wrap(doc.seller.name, nameSt, sellerW, 2)
        var t = top + pen.paragraph(name, sx, top, nameSt)
        val info = listOf(doc.seller.addressLines.joinToString(", ")) +
            listOfNotNull(doc.seller.gstin?.let { "GSTIN $it" }, listOfNotNull(doc.seller.state, doc.seller.phone).joinToString("  ·  ").takeIf { it.isNotBlank() })
        info.forEach { line ->
            m.wrap(line, small, sellerW, 2).forEach { pen.text(it, sx, t + small.ascent, small); t += small.lineHeight }
        }
        val titleSt = ts(20f, FontRole.BOLD, Ink.WHITE)
        pen.text(doc.title, right, top + titleSt.ascent, titleSt, Align.RIGHT)
        var ry = top + titleSt.lineHeight + 4f * k
        val metaSt = ts(8.4f, color = Ink.WHITE)
        pen.text("No. ${doc.number}", right, ry + metaSt.ascent, metaSt.bold(), Align.RIGHT)
        ry += metaSt.lineHeight
        pen.text(doc.date, right, ry + metaSt.ascent, metaSt.color(onBand), Align.RIGHT)
        ry += metaSt.lineHeight + 4f * k
        copyLabel?.let {
            val cs = ts(6.6f, FontRole.BOLD, Ink.WHITE)
            val w = m.width(it.uppercase(), cs) + 12f * k
            pen.rect(right - w, ry, w, cs.lineHeight + 4f * k, stroke = onBand, strokeWidth = 0.6f, radius = (cs.lineHeight + 4f * k) / 2f)
            pen.text(it.uppercase(), right - w / 2f, ry + 2f * k + cs.ascent, cs, Align.CENTER)
        }
        y = maxOf(bandH, t, ry) + 18f * k
        statusText()?.let {
            pen.text(it, left, y + 6f, ts(9f, FontRole.BOLD, Ink.ERROR), maxWidth = cw)
            y += 16f * k
        }

        val cardGap = 12f * k
        val billW = cw * 0.55f
        val detW = cw - billW - cardGap
        val pad = 10f * k
        val bill = Block.stack(listOf(label("BILLED TO", labelSt, 3f * k), partyBlock(doc.buyer, billW - 2 * pad, ts(10f, FontRole.BOLD, Ink.BLACK), ts(7.6f))))
        val detRows = metaRows(false) + listOfNotNull(doc.shipTo?.let { "Ship to" to it.shipLine() })
        val det = Block.stack(listOf(label("DETAILS", labelSt, 3f * k), kvBlock(detRows, detW - 2 * pad, ts(7.6f, color = Ink.MUTED), ts(7.6f, FontRole.BOLD, Ink.BLACK))))
        val cards = boxedPair(bill, billW, det, detW, pad, null, cardGap, soft, 8f * k)
        cards.draw(pen, left, y)
        y += cards.height + 16f * k
    }

    private fun look() = TableLook(
        body = ts(7.8f, color = Ink.BLACK),
        head = ts(7f, FontRole.BOLD, dark),
        padH = 4f * k,
        padV = 5f * k,
        headPadV = 6f * k,
        headFill = Ink.tint(accent, 0.86f),
        headRadius = 6f * k,
        zebra = Ink.tint(accent, 0.965f),
        zebraRadius = 4f * k,
    )

    override fun lineTable() = lineTable(standardColumns(), look())

    override fun continuationHeader() {
        pen.rect(0f, 0f, pageW, 6f * k, fill = accent)
        super.continuationHeader()
    }

    override fun footer(pen: Pen, pageNo: Int, count: Int) {
        val st = ts(7f, color = Ink.MUTED)
        val base = footerBaseline
        pen.rect(left, base - st.size - 1f, 16f * k, 3f * k, fill = accent, radius = 1.5f * k)
        pen.text("${doc.title} ${doc.number}", left + 22f * k, base - st.size * 0.1f, st, maxWidth = cw / 2f)
        pen.text("Page $pageNo of $count", right, base - st.size * 0.1f, st, Align.RIGHT)
    }

    override fun summaryBlocks(): List<Block> {
        val rightW = cw * 0.4f
        val leftW = cw - rightW - 16f * k
        val pad = 10f * k
        val totals = kvBlock(doc.totals, rightW - 2 * pad, ts(8f, color = Ink.MUTED), ts(8f, color = Ink.BLACK), rowGap = 2.5f * k)
        val totalSt = ts(13f, FontRole.BOLD, Ink.WHITE)
        val pill = totalSt.lineHeight + 14f * k
        val grand = Block(pill + 6f * k) { pen, x, top ->
            pen.rect(x - pad + 4f * k, top + 6f * k, rightW - 8f * k, pill, fill = accent, radius = 8f * k)
            val base = top + 6f * k + (pill - totalSt.lineHeight) / 2f + totalSt.ascent
            pen.text("Total", x + 6f * k, base, ts(9f, FontRole.BOLD, Ink.WHITE))
            pen.text(doc.grandTotal, x + rightW - 2 * pad - 6f * k, base, totalSt, Align.RIGHT, rightW * 0.66f, shrink = true)
        }
        val card = boxed(Block.stack(listOf(totals, grand)), rightW, pad, fill = soft, radius = 10f * k)
        val left = Block.stack(
            listOf(
                wordsBlock(leftW, labelSt, ts(8f, FontRole.BOLD, Ink.BLACK)),
                paymentBlock(leftW, labelSt, ts(7.4f, color = Ink.MUTED), ts(7.4f, FontRole.BOLD, Ink.BLACK)),
            ),
            gap = 12f * k,
        )
        val tax = taxSummaryBlock(cw, look().copy(body = ts(7.2f, color = Ink.BLACK), padV = 3.5f * k, headPadV = 4f * k), labelSt, totalFill = Ink.tint(accent, 0.9f))
        val notes = notesAndTerms(leftW, labelSt, ts(7.4f))
        val sign = signatureBlock(rightW, ts(8f, FontRole.BOLD, Ink.BLACK), ts(7.2f, color = Ink.MUTED), accent)
        return listOf(Block.beside(left, card, leftW + 16f * k), tax, Block.beside(notes, sign, leftW + 16f * k))
    }
}

// ---------------------------------------------------------------------------------------------
// Elegant: centred letterhead, fine double rules and a small ornament; no fills.
// ---------------------------------------------------------------------------------------------

class ElegantLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    override val margin: Float get() = 48f
    private val dark get() = Ink.shade(accent, 0.3f)
    private val labelSt get() = ts(6.6f, FontRole.BOLD, accent)

    private fun doubleRule(pen: Pen, x: Float, y: Float, w: Float) {
        pen.hline(x, y, w, dark, 0.9f)
        pen.hline(x, y + 2.6f, w, dark, 0.35f)
    }

    private fun ornament(pen: Pen, cx: Float, cy: Float, half: Float) {
        val d = 3.2f * k
        pen.hline(cx - half, cy, half - 8f * k, accent, 0.5f)
        pen.hline(cx + 8f * k, cy, half - 8f * k, accent, 0.5f)
        pen.rect(cx - d / 2f, cy - d / 2f, d, d, fill = accent, radius = d / 2f)
        pen.rect(cx - 6f * k - 1f, cy - 1f, 2f, 2f, fill = accent, radius = 1f)
        pen.rect(cx + 6f * k - 1f, cy - 1f, 2f, 2f, fill = accent, radius = 1f)
    }

    override fun firstHeader() {
        val cx = pageW / 2f
        input.logo?.let {
            val img = it.fit(left, y, cw, 40f * k, ImageKind.LOGO, 0.5f)
            pen.image(img)
            y += img.h + 8f * k
        }
        val nameSt = ts(18f, FontRole.BOLD, dark)
        y += pen.paragraph(m.wrap(doc.seller.name.uppercase(), nameSt, cw, 2), cx, y, nameSt, Align.CENTER)
        val small = ts(7.8f, color = Ink.MUTED)
        val lines = listOf(doc.seller.addressLines.joinToString(", ")) +
            listOfNotNull(
                listOfNotNull(doc.seller.gstin?.let { "GSTIN $it" }, doc.seller.state).joinToString("   ·   ").takeIf { it.isNotBlank() },
                listOfNotNull(doc.seller.phone, doc.seller.email).joinToString("   ·   ").takeIf { it.isNotBlank() },
            )
        y += 2f * k
        lines.forEach { l -> y += pen.paragraph(m.wrap(l, small, cw * 0.8f, 2), cx, y, small, Align.CENTER) }
        y += 8f * k
        doubleRule(pen, left, y, cw)
        y += 16f * k
        val titleSt = ts(10f, FontRole.BOLD, accent)
        val title = doc.title.uppercase().toCharArray().joinToString(" ")
        val tw = m.width(title, titleSt)
        pen.text(title, cx, y + titleSt.ascent * 0.7f, titleSt, Align.CENTER)
        ornament(pen, cx - tw / 2f - 46f * k, y + titleSt.size * 0.32f, 30f * k)
        ornament(pen, cx + tw / 2f + 46f * k, y + titleSt.size * 0.32f, 30f * k)
        y += titleSt.lineHeight
        copyLabel?.let { pen.text(it, cx, y + 6f * k, ts(7f, color = Ink.MUTED), Align.CENTER); y += 10f * k }
        statusText()?.let { pen.text(it, cx, y + 8f * k, ts(8f, FontRole.BOLD, Ink.ERROR), Align.CENTER, cw); y += 12f * k }
        y += 14f * k

        val colW = (cw - 30f * k) / 2f
        val bill = Block.stack(listOf(label("BILLED TO", labelSt, 4f * k), partyBlock(doc.buyer, colW, ts(10f, FontRole.BOLD, Ink.BLACK), ts(7.8f))))
        val details = Block.stack(
            listOf(
                label("INVOICE", labelSt, 4f * k),
                kvBlock(metaRows() + listOfNotNull(doc.shipTo?.let { "Ship to" to it.shipLine() }), colW, ts(7.8f, color = Ink.MUTED), ts(7.8f, color = Ink.BLACK), valueX = 78f * k),
            ),
        )
        bill.draw(pen, left, y)
        details.draw(pen, left + colW + 30f * k, y)
        y += maxOf(bill.height, details.height) + 18f * k
    }

    private fun look() = TableLook(
        body = ts(7.8f, color = Ink.BLACK),
        head = ts(6.8f, FontRole.BOLD, dark),
        padH = 4f * k,
        padV = 5.5f * k,
        headPadV = 6f * k,
        headTopRule = dark,
        headBottomRule = dark,
        headRuleWidth = 0.6f,
        rowRule = Ink.HAIRLINE,
    )

    override fun lineTable() = lineTable(standardColumns(), look())

    override val gap: Float get() = 16f * k

    override fun footer(pen: Pen, pageNo: Int, count: Int) {
        val st = ts(7f, color = Ink.MUTED)
        val base = footerBaseline
        val text = "${doc.number}   ·   Page $pageNo of $count"
        val w = m.width(text, st)
        pen.text(text, pageW / 2f, base, st, Align.CENTER)
        pen.hline(pageW / 2f - w / 2f - 40f * k, base - st.size * 0.35f, 30f * k, Ink.RULE, 0.5f)
        pen.hline(pageW / 2f + w / 2f + 10f * k, base - st.size * 0.35f, 30f * k, Ink.RULE, 0.5f)
    }

    override fun summaryBlocks(): List<Block> {
        val rightW = cw * 0.42f
        val leftW = cw - rightW - 30f * k
        val totals = kvBlock(doc.totals, rightW, ts(8f, color = Ink.MUTED), ts(8f, color = Ink.BLACK), rowGap = 3f * k)
        val totalSt = ts(12.5f, FontRole.BOLD, dark)
        val grand = Block(totalSt.lineHeight + 16f * k) { pen, x, top ->
            doubleRule(pen, x, top + 4f * k, rightW)
            val base = top + 10f * k + totalSt.ascent
            pen.text("Total", x, base, ts(9f, FontRole.BOLD, dark))
            pen.text(doc.grandTotal, x + rightW, base, totalSt, Align.RIGHT, rightW * 0.7f, shrink = true)
            doubleRule(pen, x, top + totalSt.lineHeight + 12f * k, rightW)
        }
        val right = Block.stack(listOf(totals, grand), gap = 2f * k)
        val left = Block.stack(
            listOf(
                wordsBlock(leftW, labelSt, ts(8f, color = Ink.BLACK)),
                paymentBlock(leftW, labelSt, ts(7.4f, color = Ink.MUTED), ts(7.4f, color = Ink.BLACK)),
            ),
            gap = 12f * k,
        )
        val tax = taxSummaryBlock(cw, look().copy(body = ts(7.2f, color = Ink.BLACK), padV = 3.5f * k, headPadV = 4f * k), labelSt, totalTopRule = dark)
        val notes = notesAndTerms(leftW, labelSt, ts(7.4f))
        val sign = signatureBlock(rightW, ts(8f, FontRole.BOLD, dark), ts(7.2f, color = Ink.MUTED), dark)
        return listOf(Block.beside(left, right, leftW + 30f * k), tax, Block.beside(notes, sign, leftW + 30f * k))
    }
}

// ---------------------------------------------------------------------------------------------
// Counter: dense bill-book layout for retail; small type, compact grid, many lines per page.
// ---------------------------------------------------------------------------------------------

class CounterLayout(input: LayoutInput, paper: Paper, copy: String?) : BaseLayout(input, paper, copy) {
    override val margin: Float get() = 28f
    private val g = 0xFF8F9A9E.toInt()
    private val pad get() = 5f * k

    override fun firstHeader() {
        val cx = pageW / 2f
        val logo = input.logo
        val headTop = y
        val nameSt = ts(14f, FontRole.BOLD, Ink.BLACK)
        var t = y + pad
        if (logo != null) pen.image(logo.fit(left + pad, t, 60f * k, 40f * k, ImageKind.LOGO))
        copyLabel?.let { pen.text(it, right - pad, t + 6f, ts(6.5f, color = Ink.MUTED), Align.RIGHT, 120f * k) }
        val textW = cw - 2 * 70f * k
        t += pen.paragraph(m.wrap(doc.seller.name, nameSt, textW, 2), cx, t, nameSt, Align.CENTER)
        val small = ts(7.4f)
        val info = listOf(doc.seller.addressLines.joinToString(", ")) +
            listOfNotNull(listOfNotNull(doc.seller.gstin?.let { "GSTIN: $it" }, doc.seller.phone?.let { "Ph: $it" }, doc.seller.state).joinToString("   ").takeIf { it.isNotBlank() })
        info.forEach { l -> t += pen.paragraph(m.wrap(l, small, textW, 2), cx, t, small, Align.CENTER) }
        t = maxOf(t, if (logo != null) y + pad + 40f * k else 0f) + pad
        pen.rect(left, headTop, cw, t - headTop, stroke = g)
        y = t
        val barSt = ts(9f, FontRole.BOLD, Ink.WHITE)
        val barH = barSt.lineHeight + 4f * k
        pen.rect(left, y, cw, barH, fill = accent)
        pen.text(doc.title.uppercase(), cx, y + 2f * k + barSt.ascent, barSt, Align.CENTER)
        statusText()?.let { pen.text(it, left + pad, y + 2f * k + barSt.ascent, ts(7f, FontRole.BOLD, Ink.WHITE), maxWidth = cw * 0.3f) }
        y += barH

        val lab = ts(7.4f, color = Ink.MUTED)
        val v = ts(7.4f, FontRole.BOLD, Ink.BLACK)
        val half = cw / 2f
        val buyer = doc.buyer
        val partyRows = if (buyer == null) listOf("Party" to "Cash sale") else buildList {
            add("Party" to buyer.name)
            if (buyer.addressLines.isNotEmpty()) add("Address" to buyer.addressLines.joinToString(", "))
            buyer.gstin?.let { add("GSTIN" to it) }
            buyer.phone?.let { add("Phone" to it) }
        }
        val leftRows = kvBlock(partyRows, half - 2 * pad, lab, v, valueX = 44f * k, rowGap = 0.5f * k)
        val rightRows = kvBlock(
            listOfNotNull("Bill no." to doc.number, "Date" to doc.date, doc.dueDate?.takeIf { opt.showDueDate }?.let { "Due" to it }, "Place of supply" to doc.placeOfSupply),
            half - 2 * pad, lab, v, valueX = 62f * k, rowGap = 0.5f * k,
        )
        val pair = boxedPair(leftRows, half, rightRows, half, pad, g)
        pair.draw(pen, left, y)
        y += pair.height
    }

    private fun look() = TableLook(
        body = ts(7.2f, color = Ink.BLACK),
        head = ts(6.8f, FontRole.BOLD, Ink.BLACK),
        padH = 3f * k,
        padV = 2.2f * k,
        headPadV = 3f * k,
        headFill = Ink.tint(accent, 0.88f),
        grid = g,
        gridWidth = 0.5f,
        maxFlexLines = 2,
    )

    override fun lineTable() = lineTable(standardColumns(withTax = false, withTaxable = false), look())

    override val gap: Float get() = 0f

    override fun summaryBlocks(): List<Block> {
        val rightW = cw * 0.38f
        val leftW = cw - rightW
        val lab = ts(7.4f, color = Ink.MUTED)
        val v = ts(7.4f, color = Ink.BLACK)
        val leftContent = Block.stack(
            listOf(
                kvBlock(listOf("Items" to doc.itemCount.toString(), "Total qty" to doc.totalQuantity), 120f * k, lab, v.bold(), valueX = 50f * k),
                wordsBlock(leftW - 2 * pad, ts(6.8f, FontRole.BOLD, Ink.MUTED), ts(7.4f, FontRole.BOLD, Ink.BLACK)),
                paymentBlock(leftW - 2 * pad, ts(6.8f, FontRole.BOLD, Ink.MUTED), lab, v.bold(), qrSize = 54f * k),
            ),
            gap = 4f * k,
        )
        val totals = kvBlock(doc.totals, rightW - 2 * pad, lab, v, rowGap = 0.5f * k)
        val totalSt = ts(11f, FontRole.BOLD, Ink.BLACK)
        val grand = Block(totalSt.lineHeight + 8f * k) { pen, x, top ->
            pen.rect(x - pad, top + 2f * k, rightW, totalSt.lineHeight + 6f * k, fill = Ink.tint(accent, 0.88f))
            pen.text("TOTAL", x, top + 5f * k + totalSt.ascent, totalSt)
            pen.text(doc.grandTotal, x + rightW - 2 * pad, top + 5f * k + totalSt.ascent, totalSt, Align.RIGHT, rightW * 0.7f, shrink = true)
        }
        val first = boxedPair(leftContent, leftW, Block.stack(listOf(totals, grand), gap = 2f * k), rightW, pad, g)
        val tax = taxSummaryBlock(cw - 2 * pad, look().copy(body = ts(6.8f, color = Ink.BLACK)), ts(6.8f, FontRole.BOLD, Ink.MUTED), totalFill = Ink.SHADE)
        val taxBox = if (tax.height > 0f) boxed(tax, cw, pad, g) else Block.EMPTY
        val notes = notesAndTerms(leftW - 2 * pad, ts(6.8f, FontRole.BOLD, Ink.MUTED), ts(7f))
        val thanks = Block.stack(listOf(notes, label("Thank you for your purchase.", ts(7.6f, FontRole.BOLD, accent))), gap = 4f * k)
        val sign = signatureBlock(rightW - 2 * pad, ts(7.4f, FontRole.BOLD, Ink.BLACK), ts(6.8f, color = Ink.MUTED))
        return listOf(Block.stack(listOf(first, taxBox, boxedPair(thanks, leftW, sign, rightW, pad, g))))
    }
}

/** One-line ship-to for key/value rows; keeps the ship-to GSTIN, which decides IGST. */
private fun InvoiceDocument.PartyBlock.shipLine() = (addressLines + listOfNotNull(gstin?.let { "GSTIN $it" })).joinToString(", ")
