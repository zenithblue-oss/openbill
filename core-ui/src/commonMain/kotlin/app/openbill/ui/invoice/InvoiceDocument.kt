package app.openbill.ui.invoice

import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.ui.format.AmountInWords
import app.openbill.ui.format.Fmt

/**
 * A fully resolved, display-ready invoice. Everything is pre-formatted text so that the
 * Android PdfDocument renderer and a future desktop renderer produce identical content.
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
) {
    data class PartyBlock(
        val name: String,
        val addressLines: List<String>,
        val gstin: String?,
        val state: String?,
        val phone: String?,
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
    )

    companion object {
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
                gstin = firm.gstin,
                state = Fmt.stateName(firm.stateCode),
                phone = firm.phone,
            )
            val buyer = party?.let { p ->
                val address = p.addresses.firstOrNull { it.kind == AddressKind.BILLING } ?: p.addresses.firstOrNull()
                PartyBlock(
                    name = p.name,
                    addressLines = if (address == null) emptyList() else listOfNotNull(
                        address.line1,
                        address.line2?.takeIf { it.isNotBlank() },
                        listOfNotNull(address.city, address.pincode?.takeIf { it.isNotBlank() }).joinToString(" - "),
                    ),
                    gstin = p.gstin,
                    state = p.stateCode?.let { Fmt.stateName(it) },
                    phone = p.phone,
                )
            }
            val lines = voucher.lines.mapIndexed { i, line ->
                val unit = line.itemId?.let { unitsByItemId[it] }
                InvoiceDocument.Line(
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
                )
            }
            val totals = buildList {
                add("Taxable value" to Fmt.inr(summary.taxable))
                if (summary.isIntraState) {
                    add("CGST" to Fmt.inr(summary.cgst))
                    add("SGST/UTGST" to Fmt.inr(summary.sgst))
                } else {
                    add("IGST" to Fmt.inr(summary.igst))
                }
                if (summary.cess.paise != 0L) add("Cess" to Fmt.inr(summary.cess))
                if (voucher.roundOff.paise != 0L) add("Round off" to Fmt.inr(voucher.roundOff))
            }
            val number = voucher.number ?: "DRAFT"
            return InvoiceDocument(
                fileStem = "Invoice-" + number.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                title = if (firm.gstin.isNullOrBlank()) "Invoice" else "Tax Invoice",
                statusNote = when (voucher.status) {
                    VoucherStatus.DRAFT -> "DRAFT - not a valid tax invoice"
                    VoucherStatus.CANCELLED -> "CANCELLED" + (voucher.cancellationReason?.let { ": $it" } ?: "")
                    VoucherStatus.POSTED -> null
                },
                seller = seller,
                buyer = buyer,
                number = number,
                date = Fmt.date(voucher.date),
                dueDate = voucher.dueDate?.let { Fmt.date(it) },
                placeOfSupply = Fmt.stateName(voucher.placeOfSupplyStateCode),
                isIntraState = summary.isIntraState,
                lines = lines,
                totals = totals,
                grandTotal = Fmt.inr(voucher.total),
                amountInWords = AmountInWords.rupees(voucher.total),
                notes = voucher.notes?.takeIf { it.isNotBlank() },
                bank = firm.bankAccountNumber?.takeIf { it.isNotBlank() }?.let {
                    listOfNotNull(firm.bankName, "A/c $it", firm.bankIfsc?.let { i -> "IFSC $i" }).joinToString(", ")
                },
            )
        }
    }
}
