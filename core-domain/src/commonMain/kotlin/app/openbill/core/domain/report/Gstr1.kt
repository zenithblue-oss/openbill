package app.openbill.core.domain.report

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.Gstin
import app.openbill.core.tax.TaxTreatment
import kotlinx.datetime.LocalDate

/**
 * GSTR-1 for one period from posted sales and credit notes, in the sections of the GSTN
 * offline-tool JSON (b2b, b2cl, b2cs, cdnr, hsn, doc_issue). Classification rules and
 * omissions are documented in docs/09-tax-assumptions.md section 12.
 */
class Gstr1(
    val data: ReportData,
    /** Registered recipients' invoices, by voucher. */
    val b2b: List<Doc>,
    val b2cl: List<Doc>,
    val b2cs: List<B2cs>,
    val cdnr: List<Doc>,
    val hsn: List<Hsn>,
    val docs: List<DocRange>,
    val skipped: List<String>,
) {
    /** One invoice or note with its rate-wise items. */
    class Doc(val voucher: Voucher, val ctin: String?, val items: List<Pair<Rate, TaxAmounts>>) {
        val amounts: TaxAmounts get() = TaxAmounts.sum(items.map { it.second })
    }

    data class B2cs(val intra: Boolean, val pos: String, val rate: Rate, val amounts: TaxAmounts)

    data class Hsn(val b2b: Boolean, val code: String, val description: String, val uqc: String, val rate: Rate, val quantity: Quantity, val amounts: TaxAmounts)

    data class DocRange(val nature: Int, val from: String, val to: String, val total: Int, val cancelled: Int)

    /** Section summary as a report table. */
    fun summaryTable(): ReportTable {
        fun docsRow(label: String, list: List<Doc>, sign: Int = 1) = summaryRow(label, list.size.toString(), TaxAmounts.sum(list.map { it.amounts }).negateIf(sign < 0), list.fold(Money.ZERO) { t, d -> t + d.voucher.total } * sign)
        val b2csSum = TaxAmounts.sum(b2cs.map { it.amounts })
        val hsnSum = TaxAmounts.sum(hsn.map { it.amounts })
        val rows = listOf(
            docsRow("4A B2B invoices", b2b),
            docsRow("5 B2C large invoices", b2cl),
            summaryRow("7 B2C others", b2cs.size.toString(), b2csSum, b2csSum.taxable + b2csSum.tax),
            docsRow("9B Credit notes (registered)", cdnr, -1),
            summaryRow("12 HSN summary", hsn.size.toString(), hsnSum, hsnSum.taxable + hsnSum.tax),
            listOf("13 Documents issued", docs.sumOf { it.total - it.cancelled }.toString(), "", "", "", "", "", ""),
        )
        return ReportTable(
            "GSTR-1 summary",
            listOf(
                ReportColumn("section", "Section"), ReportColumn("count", "Records", ColumnKind.NUMBER),
                ReportColumn("taxable", "Taxable", ColumnKind.MONEY), ReportColumn("igst", "IGST", ColumnKind.MONEY),
                ReportColumn("cgst", "CGST", ColumnKind.MONEY), ReportColumn("sgst", "SGST", ColumnKind.MONEY),
                ReportColumn("cess", "Cess", ColumnKind.MONEY), ReportColumn("value", "Value", ColumnKind.MONEY),
            ),
            rows,
            notes = listOf("Period ${data.from} to ${data.to}. Credit notes count negative.") + skipped,
        )
    }

    private fun summaryRow(label: String, count: String, a: TaxAmounts, value: Money) =
        listOf(label, count, a.taxable.cell(), a.igst.cell(), a.cgst.cell(), a.sgst.cell(), a.cess.cell(), value.cell())

    /**
     * GSTN offline-tool JSON. [splitHsn] writes the HSN table as `hsn_b2b`/`hsn_b2c` (return
     * periods from April 2025); otherwise as `data`.
     */
    fun toJson(splitHsn: Boolean = splitHsnFor(data.from)): String {
        val gstin = data.firm.gstin?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("The firm has no GSTIN; GSTR-1 needs one")
        val root = linkedMapOf<String, Any?>("gstin" to gstin, "fp" to data.period.returnPeriod)
        if (b2b.isNotEmpty()) {
            root["b2b"] = b2b.groupBy { it.ctin!! }.toList().sortedBy { it.first }.map { (ctin, list) ->
                linkedMapOf("ctin" to ctin, "inv" to list.map { d ->
                    linkedMapOf(
                        "inum" to d.voucher.number, "idt" to gstDate(d.voucher.date), "val" to num(d.voucher.total),
                        "pos" to d.voucher.placeOfSupplyStateCode, "rchrg" to "N", "inv_typ" to "R", "itms" to itms(d, intra(d.voucher)),
                    )
                })
            }
        }
        if (b2cl.isNotEmpty()) {
            root["b2cl"] = b2cl.groupBy { it.voucher.placeOfSupplyStateCode }.toList().sortedBy { it.first }.map { (pos, list) ->
                linkedMapOf("pos" to pos, "inv" to list.map { d ->
                    linkedMapOf("inum" to d.voucher.number, "idt" to gstDate(d.voucher.date), "val" to num(d.voucher.total), "itms" to itms(d, false))
                })
            }
        }
        if (b2cs.isNotEmpty()) {
            root["b2cs"] = b2cs.map { r ->
                linkedMapOf<String, Any?>("sply_ty" to if (r.intra) "INTRA" else "INTER", "pos" to r.pos, "typ" to "OE", "txval" to num(r.amounts.taxable), "rt" to JsonText.Num(r.rate.percentText()))
                    .apply { heads(this, r.amounts, r.intra) }
            }
        }
        if (cdnr.isNotEmpty()) {
            root["cdnr"] = cdnr.groupBy { it.ctin!! }.toList().sortedBy { it.first }.map { (ctin, list) ->
                linkedMapOf("ctin" to ctin, "nt" to list.map { d ->
                    linkedMapOf(
                        "ntty" to "C", "nt_num" to d.voucher.number, "nt_dt" to gstDate(d.voucher.date), "val" to num(d.voucher.total),
                        "pos" to d.voucher.placeOfSupplyStateCode, "rchrg" to "N", "inv_typ" to "R", "itms" to itms(d, intra(d.voucher)),
                    )
                })
            }
        }
        if (hsn.isNotEmpty()) {
            fun rows(list: List<Hsn>) = list.mapIndexed { i, h ->
                linkedMapOf(
                    "num" to i + 1, "hsn_sc" to h.code, "desc" to h.description, "uqc" to h.uqc, "qty" to JsonText.Num(h.quantity.format()),
                    "rt" to JsonText.Num(h.rate.percentText()), "txval" to num(h.amounts.taxable), "iamt" to num(h.amounts.igst),
                    "camt" to num(h.amounts.cgst), "samt" to num(h.amounts.sgst), "csamt" to num(h.amounts.cess),
                )
            }
            root["hsn"] = if (splitHsn) {
                linkedMapOf<String, Any?>().apply {
                    hsn.filter { it.b2b }.takeIf { it.isNotEmpty() }?.let { put("hsn_b2b", rows(it)) }
                    hsn.filter { !it.b2b }.takeIf { it.isNotEmpty() }?.let { put("hsn_b2c", rows(it)) }
                }
            } else {
                linkedMapOf("data" to rows(hsn))
            }
        }
        if (docs.isNotEmpty()) {
            root["doc_issue"] = linkedMapOf("doc_det" to docs.groupBy { it.nature }.toList().sortedBy { it.first }.map { (nature, list) ->
                linkedMapOf("doc_num" to nature, "docs" to list.mapIndexed { i, r ->
                    linkedMapOf("num" to i + 1, "from" to r.from, "to" to r.to, "totnum" to r.total, "cancel" to r.cancelled, "net_issue" to r.total - r.cancelled)
                })
            })
        }
        return JsonText.write(root)
    }

    private fun intra(v: Voucher) = v.placeOfSupplyStateCode == data.firm.stateCode

    private fun itms(d: Doc, intra: Boolean) = d.items.mapIndexed { i, (rate, a) ->
        linkedMapOf("num" to i + 1, "itm_det" to linkedMapOf<String, Any?>("txval" to num(a.taxable), "rt" to JsonText.Num(rate.percentText())).apply { heads(this, a, intra) })
    }

    private fun heads(m: MutableMap<String, Any?>, a: TaxAmounts, intra: Boolean) {
        if (intra) {
            m["camt"] = num(a.cgst)
            m["samt"] = num(a.sgst)
        } else {
            m["iamt"] = num(a.igst)
        }
        m["csamt"] = num(a.cess)
    }

    companion object {
        /** B2C large: inter-state supply to an unregistered person above this invoice value. */
        fun b2clLimit(date: LocalDate): Money = if (date >= LocalDate(2024, 8, 1)) Money.parse("100000.00") else Money.parse("250000.00")

        fun splitHsnFor(from: LocalDate) = from >= LocalDate(2025, 4, 1)

        private val GST_TREATMENTS = setOf(TaxTreatment.TAXABLE, TaxTreatment.ZERO_RATED)

        private fun num(m: Money) = JsonText.Num(m.format())

        fun gstDate(d: LocalDate): String = d.toString().split('-').let { "${it[2]}-${it[1]}-${it[0]}" }

        fun registeredGstin(v: Voucher, data: ReportData): String? =
            v.partyId?.let { data.partyById[it]?.gstin }?.trim()?.uppercase()?.takeIf { Gstin.isValid(it) }

        fun build(data: ReportData): Gstr1 {
            val state = data.firm.stateCode
            val outward = data.posted.filter { it.type == VoucherType.SALE || it.type == VoucherType.CREDIT_NOTE }
            val b2b = ArrayList<Doc>()
            val b2cl = ArrayList<Doc>()
            val cdnr = ArrayList<Doc>()
            val b2cs = LinkedHashMap<Triple<Boolean, String, Rate>, TaxAmounts>()
            val skipped = ArrayList<String>()
            for (v in outward) {
                val rows = v.taxRows(state, data.rounding)
                val gstRows = rows.filter { it.treatment in GST_TREATMENTS }
                val items = gstRows.groupBy { it.rate }.toList().sortedBy { it.first }.map { (rate, list) -> rate to TaxAmounts.sum(list.map { it.amounts }) }
                val ctin = registeredGstin(v, data)
                val intra = v.placeOfSupplyStateCode == state
                when {
                    items.isEmpty() -> skipped += "${v.number}: no taxable lines (nil, exempt or non-GST only); not in B2B/B2C tables"
                    v.type == VoucherType.SALE && ctin != null -> b2b += Doc(v, ctin, items)
                    v.type == VoucherType.SALE && !intra && v.total > b2clLimit(v.date) -> b2cl += Doc(v, null, items)
                    v.type == VoucherType.CREDIT_NOTE && ctin != null -> cdnr += Doc(v, ctin, items)
                    else -> items.forEach { (rate, a) ->
                        val key = Triple(intra, v.placeOfSupplyStateCode, rate)
                        b2cs[key] = (b2cs[key] ?: TaxAmounts()) + a.negateIf(v.type == VoucherType.CREDIT_NOTE)
                    }
                }
            }
            return Gstr1(
                data, b2b, b2cl,
                b2cs.entries.sortedWith(compareBy({ it.key.second }, { it.key.third })).map { (k, a) -> B2cs(k.first, k.second, k.third, a) },
                cdnr, hsnRows(data, outward), docRanges(data), skipped,
            )
        }

        /** Table 12: outward taxable, zero, nil and exempt rows by HSN, unit and rate (credit notes negative). */
        private fun hsnRows(data: ReportData, outward: List<Voucher>): List<Hsn> {
            data class Key(val b2b: Boolean, val code: String, val uqc: String, val rate: Rate)
            val acc = LinkedHashMap<Key, Pair<Quantity, TaxAmounts>>()
            val desc = HashMap<Key, String>()
            for (v in outward) {
                val b2b = registeredGstin(v, data) != null
                val sign = v.type == VoucherType.CREDIT_NOTE
                for (r in v.taxRows(data.firm.stateCode, data.rounding)) {
                    if (r.treatment == TaxTreatment.NON_GST) continue
                    val item = r.itemId?.let { data.itemById[it] }
                    val uqc = if (item?.isService == true || (item == null && r.quantity.scaledUnits == 0L)) "NA" else uqcOf(item?.unitId?.let { data.unitById[it]?.symbol })
                    val key = Key(b2b, r.hsnSac?.trim().orEmpty(), uqc, r.rate)
                    val qty = if (uqc == "NA") 0L else r.quantity.scaledUnits * (if (sign) -1 else 1)
                    val (q, a) = acc[key] ?: (Quantity.of(0) to TaxAmounts())
                    acc[key] = Quantity.ofScaledUnits(q.scaledUnits + qty) to (a + r.amounts.negateIf(sign))
                    desc.getOrPut(key) { item?.name ?: r.description }
                }
            }
            return acc.entries.sortedWith(compareBy({ !it.key.b2b }, { it.key.code }, { it.key.rate }, { it.key.uqc }))
                .map { (k, v) -> Hsn(k.b2b, k.code, desc[k].orEmpty(), k.uqc, k.rate, v.first, v.second) }
        }

        /** Table 13: invoice (nature 1) and credit note (nature 5) number ranges with cancellations. */
        private fun docRanges(data: ReportData): List<DocRange> = listOf(VoucherType.SALE to 1, VoucherType.CREDIT_NOTE to 5).mapNotNull { (type, nature) ->
            val issued = data.vouchers.filter { it.type == type && it.number != null && it.status != VoucherStatus.DRAFT }.sortedBy { it.number }
            if (issued.isEmpty()) null else DocRange(nature, issued.first().number!!, issued.last().number!!, issued.size, issued.count { it.status == VoucherStatus.CANCELLED })
        }

        /** GSTN unit quantity code for a unit symbol; OTH when unknown. */
        fun uqcOf(symbol: String?): String = when (symbol?.trim()?.lowercase()?.trimEnd('.')) {
            "pcs", "pc", "nos", "no", "piece", "pieces", "unit", "units" -> "NOS"
            "kg", "kgs" -> "KGS"
            "g", "gm", "gms", "gram" -> "GMS"
            "l", "ltr", "ltrs", "litre" -> "LTR"
            "ml" -> "MLT"
            "m", "mtr", "mtrs", "metre" -> "MTR"
            "box", "boxes" -> "BOX"
            "dozen", "dz", "doz" -> "DOZ"
            "set", "sets" -> "SET"
            "pack", "pkt", "packet", "pac" -> "PAC"
            "bag", "bags" -> "BAG"
            "bottle", "btl" -> "BTL"
            "pair", "pairs", "prs" -> "PRS"
            "ton", "tonne", "mt" -> "TON"
            "sqm", "sq m" -> "SQM"
            "sqft", "sq ft" -> "SQF"
            null, "" -> "OTH"
            else -> "OTH"
        }

        /** HSN-wise outward summary (Table 12) as a report. */
        fun hsnTable(data: ReportData): ReportTable {
            val rows = build(data).hsn
            val sum = TaxAmounts.sum(rows.map { it.amounts })
            return ReportTable(
                "HSN-wise summary",
                listOf(
                    ReportColumn("type", "B2B/B2C"), ReportColumn("hsn", "HSN/SAC"), ReportColumn("description", "Description"),
                    ReportColumn("uqc", "UQC"), ReportColumn("qty", "Qty", ColumnKind.QUANTITY), ReportColumn("rate", "Rate %", ColumnKind.NUMBER),
                    ReportColumn("taxable", "Taxable", ColumnKind.MONEY), ReportColumn("igst", "IGST", ColumnKind.MONEY),
                    ReportColumn("cgst", "CGST", ColumnKind.MONEY), ReportColumn("sgst", "SGST", ColumnKind.MONEY),
                    ReportColumn("cess", "Cess", ColumnKind.MONEY), ReportColumn("value", "Total value", ColumnKind.MONEY),
                ),
                rows.map { h ->
                    listOf(
                        if (h.b2b) "B2B" else "B2C", h.code, h.description, h.uqc, h.quantity.format(), h.rate.percentText(),
                        h.amounts.taxable.cell(), h.amounts.igst.cell(), h.amounts.cgst.cell(), h.amounts.sgst.cell(), h.amounts.cess.cell(),
                        (h.amounts.taxable + h.amounts.tax).cell(),
                    )
                },
                listOf("Total", "", "", "", "", "", sum.taxable.cell(), sum.igst.cell(), sum.cgst.cell(), sum.sgst.cell(), sum.cess.cell(), (sum.taxable + sum.tax).cell()),
                notes = rows.count { it.code.isEmpty() }.takeIf { it > 0 }?.let { listOf("$it row(s) have no HSN/SAC; the portal rejects them") }.orEmpty(),
            )
        }
    }
}
