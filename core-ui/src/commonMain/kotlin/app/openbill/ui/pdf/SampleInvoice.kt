package app.openbill.ui.pdf

import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherCharge
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.core.tax.TaxMode
import app.openbill.ui.invoice.DraftLine
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.invoice.InvoiceMath
import app.openbill.ui.more.SampleData
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * A fictional invoice used for template previews (with the user's own firm as the seller) and
 * for layout tests. All names and numbers are made up.
 */
object SampleInvoice {
    private val epoch = Instant.fromEpochMilliseconds(1_767_225_600_000)

    fun demoFirm(stateCode: String = "29"): Firm = Firm(
        id = "sample-firm",
        name = "Saffron Loom Textiles",
        legalName = "Saffron Loom Textiles Private Limited",
        gstin = SampleData.demoGstin(stateCode, "AAJCS4821K"),
        stateCode = stateCode,
        addressLine1 = "14, 2nd Cross, Chickpet Market",
        addressLine2 = "Near Old Clock Tower",
        city = "Bengaluru",
        pincode = "560053",
        phone = "+91 80 4123 7788",
        email = "accounts@saffronloom.example",
        bankName = "Example Co-operative Bank",
        bankAccountNumber = "001234567890",
        bankIfsc = "EXMP0001234",
        bankBranch = "Chickpet",
        createdAt = epoch,
        updatedAt = epoch,
    )

    private val items = listOf(
        Triple("Handloom cotton saree, indigo block print", "5208", 18_50_00L),
        Triple("Silk blend dupatta with zari border", "5007", 9_75_00L),
        Triple("Organic cotton bedsheet set (double)", "6302", 24_99_00L),
        Triple("Jute shopping bag, printed", "4202", 1_20_00L),
        Triple("Tailoring and alteration service", "998821", 3_50_00L),
        Triple("Khadi kurta fabric, 2.5 m", "5208", 6_40_00L),
        Triple("Woollen shawl, hand embroidered", "6214", 32_00_00L),
        Triple("Cushion cover, kalamkari, 16 x 16 in", "6304", 4_25_00L),
    )

    fun document(
        firm: Firm = demoFirm(),
        lineCount: Int = 6,
        interState: Boolean = false,
        withCharge: Boolean = true,
        settings: FirmSettings = FirmSettings(firmId = firm.id),
    ): InvoiceDocument {
        val partyState = if (interState) (if (firm.stateCode == "27") "29" else "27") else firm.stateCode
        val party = Party(
            id = "sample-party",
            firmId = firm.id,
            name = "Lakeview Boutique Hotels LLP",
            type = PartyType.CUSTOMER,
            phone = "+91 98450 12345",
            gstin = SampleData.demoGstin(partyState, "AAGFL7315Q"),
            stateCode = partyState,
            addresses = listOf(
                PartyAddress("a1", AddressKind.BILLING, line1 = "Plot 7, Lakeside Road", line2 = "Hebbal", city = "Bengaluru", stateCode = partyState, pincode = "560024"),
            ),
            createdAt = epoch,
            updatedAt = epoch,
        )
        val drafts = (0 until lineCount).map { i ->
            val (name, hsn, rate) = items[i % items.size]
            DraftLine(
                key = "l$i",
                itemId = null,
                description = if (i < items.size) name else "$name (lot ${i / items.size + 1})",
                hsnSac = hsn,
                unit = if (hsn.length == 6) "HRS" else "PCS",
                quantity = Quantity.of((i % 4 + 1).toLong()),
                rate = Money.of(rate),
                discount = if (i == 1) Money.of(50_00) else Money.ZERO,
                gstRate = Rate.ofPercent(if (hsn.length == 6) 18 else if (rate > 10_00_00L) 12 else 5),
                taxMode = TaxMode.EXCLUSIVE,
            )
        }
        val summary = InvoiceMath.summarize(drafts, firm.stateCode, partyState, false, settings.roundingMode)
        val lines = InvoiceMath.toVoucherLines(drafts, summary) { it.key }
        val charges = if (withCharge) {
            listOf(
                VoucherCharge(
                    id = "c1", name = "Freight", amount = Money.of(400_00), taxRate = Rate.ofPercent(18),
                    taxAmount = Money.of(72_00), total = Money.of(472_00), taxBasis = ChargeTaxBasis.EXPLICIT_RATE,
                ),
            )
        } else {
            emptyList()
        }
        val exact = summary.grandTotal + charges.fold(Money.ZERO) { a, c -> a + c.total }
        val rounded = Money.of(((exact.paise + 50) / 100) * 100)
        val voucher = Voucher(
            id = "sample",
            firmId = firm.id,
            financialYearId = "fy",
            type = VoucherType.SALE,
            number = "${settings.invoicePrefix}/26-27/0042",
            date = LocalDate(2026, 10, 22),
            dueDate = LocalDate(2026, 11, 6),
            partyId = party.id,
            status = VoucherStatus.POSTED,
            postedAt = epoch,
            placeOfSupplyStateCode = partyState,
            lines = lines,
            charges = charges,
            roundOff = rounded - exact,
            total = rounded,
            notes = "Goods once sold can be exchanged within 7 days with the original bill.",
            createdAt = epoch,
            updatedAt = epoch,
        )
        return InvoiceDocument.build(voucher, firm, settings, party, emptyMap())
            .let { d -> d.copy(lines = d.lines.mapIndexed { i, l -> l.copy(quantity = l.quantity + " " + drafts[i].unit) }) }
    }
}
