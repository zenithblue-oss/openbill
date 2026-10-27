package app.openbill.ui.invoice

import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.tax.TaxKind
import app.openbill.core.tax.TaxTreatment
import app.openbill.ui.format.AmountInWords
import app.openbill.ui.format.Fmt

/**
 * A fully resolved, display-ready invoice. Everything is pre-formatted text so that every
 * template and every platform painter produce identical content (see app.openbill.ui.pdf).
 */
data class InvoiceDocument(
    val fileStem: String,
    val title: String,
    val statusNote: String?,
    val seller: PartyBlock,
    val buyer: PartyBlock?,
    val number: String,
    val date: String,
    val dueDate: String?,
    val placeOfSupply: String,
    val isIntraState: Boolean,
    val lines: List<Line>,
    val totals: List<Pair<String, String>>,
    val grandTotal: String,
    val amountInWords: String,
    val notes: String?,
    val bank: String?,
    /** Shipping address when the party has one that differs from billing. */
    val shipTo: PartyBlock? = null,
    /** One row per GST rate (lines and additional-charge portions together). */
    val rateSummary: List<TaxRow> = emptyList(),
    /** One row per HSN/SAC code and rate. */
    val hsnSummary: List<TaxRow> = emptyList(),
    val taxTotal: TaxRow? = null,
    val bankRows: List<Pair<String, String>> = emptyList(),
    val hasDiscount: Boolean = lines.any { it.discount != "-" },
    val hasCess: Boolean = false,
    /** Grand total in paise, for the UPI payment link. */
    val grandTotalPaise: Long = 0,
    /** Short tax total ("₹180.00"), for compact layouts. */
    val totalTax: String = "",
    val itemCount: Int = lines.size,
    val totalQuantity: String = "",
    /** Column sums of the line table (taxable, tax heads, amount), for ledger layouts. */
    val lineTotals: Line? = null,
    /** "Previous balance ... · Current balance ...", filled by the caller when the print option is on. */
    val partyBalance: String? = null,
) {
    /** Plain-text summary sent along with a shared PDF; WhatsApp and SMS pick it up from the share sheet. */
    fun shareSummary(): String = buildString {
        append("$title $number dated $date")
        buyer?.let { append("\nTo: ${it.name}") }
        append("\nAmount: $grandTotal")
        dueDate?.let { append("\nDue by: $it") }
        append("\nFrom: ${seller.name}")
    }

    data class PartyBlock(
        val name: String,
        val addressLines: List<String>,
        val gstin: String?,
        val state: String?,
        val phone: String?,
        val email: String? = null,
    )

    data class Line(
        val index: Int,
        val description: String,
        val hsnSac: String,
        val quantity: String,
        val rate: String,
        val discount: String,
        val taxable: String,
        val gstRate: String,
        val tax: String,
        val amount: String,
        val cgst: String = "-",
        val sgst: String = "-",
        val igst: String = "-",
        /** Half the GST rate, e.g. "9%", shown beside CGST/SGST in ledger layouts. */
        val halfRate: String = "",
    )

    /** One row of a tax summary; all amounts formatted without the rupee sign. */
    data class TaxRow(
        val label: String,
        val rate: String,
        val taxable: String,
        val cgst: String,
        val sgst: String,
        val igst: String,
        val cess: String,
        val totalTax: String,
    )

    companion object {
        private class Acc {
            var taxable = Money.ZERO
            var cgst = Money.ZERO
            var sgst = Money.ZERO
            var igst = Money.ZERO
            var cess = Money.ZERO
            fun add(t: Money, c: Money, s: Money, i: Money, ce: Money) {
                taxable += t; cgst += c; sgst += s; igst += i; cess += ce
            }
            fun row(label: String, rate: String) = TaxRow(
                label, rate,
                Fmt.inr(taxable, symbol = false),
                Fmt.inr(cgst, symbol = false), Fmt.inr(sgst, symbol = false), Fmt.inr(igst, symbol = false),
                Fmt.inr(cess, symbol = false), Fmt.inr(cgst + sgst + igst + cess, symbol = false),
            )
        }

        private fun rateLabel(treatment: TaxTreatment, rate: Rate): String = when (treatment) {
            TaxTreatment.TAXABLE, TaxTreatment.ZERO_RATED -> "GST ${Fmt.percent(rate)}%"
            TaxTreatment.EXEMPT -> "Exempt"
            TaxTreatment.NIL_RATED -> "Nil rated"
            TaxTreatment.NON_GST -> "Non-GST"
        }

        private fun addressLines(a: PartyAddress?): List<String> = if (a == null) emptyList() else listOfNotNull(
            a.line1.takeIf { it.isNotBlank() },
            a.line2?.takeIf { it.isNotBlank() },
            listOfNotNull(a.city.takeIf { it.isNotBlank() }, a.pincode?.takeIf { it.isNotBlank() }).joinToString(" - ").takeIf { it.isNotBlank() },
        )

        fun build(
            voucher: Voucher,
            firm: Firm,
            settings: FirmSettings,
            party: Party?,
            unitsByItemId: Map<String, String>,
        ): InvoiceDocument {
            val summary = InvoiceMath.summarizeStored(
                voucher.lines,
                firm.stateCode,
                voucher.placeOfSupplyStateCode,
                voucher.roundOff,
                voucher.total,
                settings.roundingMode,
            )
            val seller = PartyBlock(
                name = firm.legalName?.takeIf { it.isNotBlank() } ?: firm.name,
                addressLines = listOfNotNull(
                    firm.addressLine1,
                    firm.addressLine2?.takeIf { it.isNotBlank() },
                    listOfNotNull(firm.city, firm.pincode?.takeIf { it.isNotBlank() }).joinToString(" - "),
                ),
                gstin = firm.gstin?.takeIf { it.isNotBlank() },
                state = Fmt.stateName(firm.stateCode),
                phone = firm.phone?.takeIf { it.isNotBlank() },
                email = firm.email?.takeIf { it.isNotBlank() },
            )
            val billing = party?.addresses?.let { list -> list.firstOrNull { it.kind == AddressKind.BILLING } ?: list.firstOrNull() }
            val buyer = party?.let { p ->
                PartyBlock(
                    name = p.name,
                    addressLines = addressLines(billing),
                    gstin = p.gstin?.takeIf { it.isNotBlank() },
                    state = p.stateCode?.let { Fmt.stateName(it) },
                    phone = p.phone?.takeIf { it.isNotBlank() },
                    email = p.email?.takeIf { it.isNotBlank() },
                )
            }
            val shipping = party?.addresses?.filter { it.kind == AddressKind.SHIPPING }?.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
            val shipTo = if (party != null && shipping != null && (addressLines(shipping) != addressLines(billing) || shipping.gstin != null)) {
                PartyBlock(party.name, addressLines(shipping), shipping.gstin?.takeIf { it.isNotBlank() }, shipping.stateCode?.let { Fmt.stateName(it) }, null)
            } else {
                null
            }

            fun dashIfZero(m: Money) = if (m.paise == 0L) "-" else Fmt.inr(m, symbol = false)
            val rateGroups = LinkedHashMap<String, Acc>()
            val hsnGroups = LinkedHashMap<Pair<String, String>, Acc>()
            val total = Acc()
            var hasCess = false

            val lines = voucher.lines.mapIndexed { i, line ->
                val unit = line.itemId?.let { unitsByItemId[it] }
                val r = summary.lineResults.getOrNull(i)
                fun comp(kind: TaxKind) = r?.components?.filter { it.kind == kind }?.fold(Money.ZERO) { a, c -> a + c.amount } ?: Money.ZERO
                val cgst = comp(TaxKind.CGST)
                val sgst = comp(TaxKind.SGST)
                val igst = comp(TaxKind.IGST)
                val cess = comp(TaxKind.CESS)
                if (cess.paise != 0L) hasCess = true
                val label = rateLabel(line.treatment, line.taxRate)
                val taxable = r?.taxableValue ?: line.taxable
                rateGroups.getOrPut(label) { Acc() }.add(taxable, cgst, sgst, igst, cess)
                hsnGroups.getOrPut((line.hsnSac ?: "-") to label) { Acc() }.add(taxable, cgst, sgst, igst, cess)
                total.add(taxable, cgst, sgst, igst, cess)
                Line(
                    index = i + 1,
                    description = line.description,
                    hsnSac = line.hsnSac ?: "",
                    quantity = Fmt.qty(line.quantity) + (unit?.let { " $it" } ?: ""),
                    rate = Fmt.inr(line.rate, symbol = false),
                    discount = if (line.discount.paise == 0L) "-" else Fmt.inr(line.discount, symbol = false),
                    taxable = Fmt.inr(line.taxable, symbol = false),
                    gstRate = Fmt.percent(line.taxRate) + "%",
                    tax = Fmt.inr(line.tax, symbol = false),
                    amount = Fmt.inr(line.total, symbol = false),
                    cgst = dashIfZero(cgst),
                    sgst = dashIfZero(sgst),
                    igst = dashIfZero(igst),
                    halfRate = if (line.taxRate.units % 2L == 0L) Fmt.percent(line.taxRate.half()) + "%" else "",
                )
            }

            fun sumOf(f: (app.openbill.core.domain.model.VoucherLine) -> Money) = voucher.lines.fold(Money.ZERO) { a, l -> a + f(l) }
            fun compSum(kind: TaxKind) = summary.lineResults.fold(Money.ZERO) { a, r ->
                r.components.filter { it.kind == kind }.fold(a) { b, c -> b + c.amount }
            }
            val lineTotals = Line(
                index = 0,
                description = "Total",
                hsnSac = "",
                quantity = "",
                rate = "",
                discount = Fmt.inr(sumOf { it.discount }, symbol = false),
                taxable = Fmt.inr(sumOf { it.taxable }, symbol = false),
                gstRate = "",
                tax = Fmt.inr(sumOf { it.tax }, symbol = false),
                amount = Fmt.inr(sumOf { it.total }, symbol = false),
                cgst = Fmt.inr(compSum(TaxKind.CGST), symbol = false),
                sgst = Fmt.inr(compSum(TaxKind.SGST), symbol = false),
                igst = Fmt.inr(compSum(TaxKind.IGST), symbol = false),
            )

            // Additional charges: each rate portion joins the matching rate group.
            val intra = summary.isIntraState
            var chargesTaxable = Money.ZERO
            val chargeRows = mutableListOf<Pair<String, String>>()
            voucher.charges.sortedBy { it.sortOrder }.forEach { charge ->
                chargesTaxable += charge.taxableValue
                chargeRows += charge.name to Fmt.inr(charge.taxableValue)
                val portions = charge.portions.ifEmpty {
                    listOf(
                        app.openbill.core.domain.model.VoucherChargePortion(
                            charge.treatment, charge.taxRate, charge.cessRate, charge.taxableValue, charge.taxAmount, charge.total,
                        ),
                    )
                }
                portions.forEach { p ->
                    val cess = if (p.cessRate.units == 0L) Money.ZERO else p.cessRate.applyTo(p.taxableValue, settings.roundingMode)
                        .let { if (it > p.taxAmount) p.taxAmount else it }
                    val gst = p.taxAmount - cess
                    if (cess.paise != 0L) hasCess = true
                    val c = if (intra) Money.of((gst.paise + 1) / 2) else Money.ZERO
                    val s = if (intra) gst - c else Money.ZERO
                    val ig = if (intra) Money.ZERO else gst
                    val label = rateLabel(p.treatment, p.taxRate)
                    rateGroups.getOrPut(label) { Acc() }.add(p.taxableValue, c, s, ig, cess)
                    hsnGroups.getOrPut((charge.hsnSac ?: charge.name) to label) { Acc() }.add(p.taxableValue, c, s, ig, cess)
                    total.add(p.taxableValue, c, s, ig, cess)
                }
            }

            val totals = buildList {
                val hasCharges = chargeRows.isNotEmpty()
                add((if (hasCharges) "Items taxable value" else "Taxable value") to Fmt.inr(summary.taxable))
                if (hasCharges) {
                    addAll(chargeRows)
                    add("Total taxable value" to Fmt.inr(summary.taxable + chargesTaxable))
                }
                if (intra) {
                    add("CGST" to Fmt.inr(total.cgst))
                    add("SGST/UTGST" to Fmt.inr(total.sgst))
                } else {
                    add("IGST" to Fmt.inr(total.igst))
                }
                if (total.cess.paise != 0L) add("Cess" to Fmt.inr(total.cess))
                if (voucher.roundOff.paise != 0L) add("Round off" to Fmt.inrSigned(voucher.roundOff))
            }
            val number = voucher.number ?: "DRAFT"
            val bankRows = buildList {
                val acct = firm.bankAccountNumber?.takeIf { it.isNotBlank() } ?: return@buildList
                firm.bankName?.takeIf { it.isNotBlank() }?.let { add("Bank" to it) }
                add("A/c no." to acct)
                firm.bankIfsc?.takeIf { it.isNotBlank() }?.let { add("IFSC" to it) }
                firm.bankBranch?.takeIf { it.isNotBlank() }?.let { add("Branch" to it) }
            }
            val totalQty = voucher.lines.fold(0L) { a, l -> a + l.quantity.scaledUnits }
            return InvoiceDocument(
                fileStem = pdfTitle(voucher.type, firm.gstin).replace(" ", "") + "-" + number.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                title = pdfTitle(voucher.type, firm.gstin),
                statusNote = when (voucher.status) {
                    VoucherStatus.DRAFT -> if (voucher.type == VoucherType.SALE) "DRAFT - not a valid tax invoice" else "DRAFT"
                    VoucherStatus.CANCELLED -> "CANCELLED" + (voucher.cancellationReason?.let { ": $it" } ?: "")
                    VoucherStatus.POSTED -> null
                },
                seller = seller,
                buyer = buyer,
                number = number,
                date = Fmt.date(voucher.date),
                dueDate = voucher.dueDate?.let { Fmt.date(it) },
                placeOfSupply = Fmt.stateName(voucher.placeOfSupplyStateCode),
                isIntraState = intra,
                lines = lines,
                totals = totals,
                grandTotal = Fmt.inr(voucher.total),
                amountInWords = AmountInWords.rupees(voucher.total),
                notes = voucher.notes?.takeIf { it.isNotBlank() },
                bank = bankRows.takeIf { it.isNotEmpty() }?.joinToString(", ") { (k, v) -> "$k $v" },
                shipTo = shipTo,
                rateSummary = rateGroups.map { (label, acc) -> acc.row(label, label) },
                hsnSummary = hsnGroups.map { (key, acc) -> acc.row(key.first, key.second) },
                taxTotal = total.row("Total", ""),
                bankRows = bankRows,
                hasCess = hasCess,
                grandTotalPaise = voucher.total.paise,
                totalTax = Fmt.inr(total.cgst + total.sgst + total.igst + total.cess),
                itemCount = lines.size,
                totalQuantity = app.openbill.core.model.Quantity.ofScaledUnits(totalQty).let { Fmt.qty(it) },
                lineTotals = lineTotals,
            )
        }
    }
}

/** Printed heading of a document; GST names for notes and bills. */
fun pdfTitle(type: VoucherType, gstin: String?): String = when (type) {
    VoucherType.SALE -> if (gstin.isNullOrBlank()) "Invoice" else "Tax Invoice"
    VoucherType.PURCHASE -> "Purchase Bill"
    VoucherType.CREDIT_NOTE -> "Credit Note"
    VoucherType.DEBIT_NOTE -> "Debit Note"
    VoucherType.QUOTATION -> "Quotation"
    VoucherType.PROFORMA -> "Proforma Invoice"
    VoucherType.DELIVERY_CHALLAN -> "Delivery Challan"
    else -> type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
