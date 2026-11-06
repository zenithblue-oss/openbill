package app.openbill.core.domain.report

import app.openbill.core.domain.model.DocumentField
import app.openbill.core.domain.model.ExportDetails
import app.openbill.core.domain.model.ExportType
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.LedgerJournalDerivation
import app.openbill.core.domain.service.StockMovementDerivation
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reverse charge, ITC eligibility, exports/SEZ, e-commerce operator and free quantity. */
class DocumentFieldsTest {
    private val f = ReportFixture
    private val b2bSale = f.vouchers.first { it.id == "V1" } // Acme, intra-state, 1000 + 180
    private val purchase = f.vouchers.first { it.id == "V6" } // Bharat, inter-state, 1000 + 180

    private fun data(vararg vouchers: Voucher) = ReportData(
        f.firm, FirmSettings(f.F), ReportPeriod.month("2026-10"), vouchers.toList(), f.parties, f.items, f.units, f.accounts,
        emptyList(), emptyList(), emptyList(),
    )

    private fun journal(v: Voucher) = LedgerJournalDerivation({ "J" }).derivePosted(v, f.ledger, null, f.at)
        .groupBy { it.accountId }.mapValues { (_, l) -> l.fold(Money.ZERO) { t, e -> t + e.debit - e.credit } }

    private val rcmSale = b2bSale.copy(id = "R1", number = "INV-0101", reverseCharge = true, total = b2bSale.taxable)

    @Test
    fun reverseChargeSaleBooksNoOutputGstAndOwesTaxableOnly() {
        val j = journal(rcmSale)
        assertEquals(Money.parse("1000.00"), j[f.ledger.accountsReceivable.id])
        assertEquals(Money.parse("-1000.00"), j[f.ledger.sales.id])
        assertEquals(null, j[f.ledger.outputGst.id])
    }

    @Test
    fun reverseChargePurchaseOwesTaxToGovernmentAndBlockedCreditIsCost() {
        val half = purchase.lines[0].copy(id = "L-b", lineNumber = 2, itcEligible = false)
        val v = purchase.copy(
            reverseCharge = true, lines = purchase.lines + half,
            taxable = purchase.taxable + purchase.taxable, tax = purchase.tax + purchase.tax, total = purchase.taxable + purchase.taxable,
        )
        val j = journal(v)
        assertEquals(Money.parse("-2000.00"), j[f.ledger.accountsPayable.id])
        assertEquals(Money.parse("2180.00"), j[f.ledger.purchases.id]) // blocked 180 GST added to cost
        assertEquals(Money.parse("180.00"), j[f.ledger.inputGst.id])
        assertEquals(Money.parse("-360.00"), j[f.ledger.outputGst.id]) // reverse charge liability

        val t = Reports.run(ReportKind.GSTR3B, data(v))
        fun row(prefix: String) = t.rows.first { (it[0] as String).startsWith(prefix) }
        assertEquals(listOf("3.1(d) Inward reverse charge", "2000.00", "360.00", "0.00", "0.00", "0.00"), row("3.1(d)"))
        assertEquals("180.00", row("4(A)(3)")[2])
        assertEquals("0.00", row("4(A)(5)")[2])
        assertEquals("180.00", row("4(D)(2)")[2])
    }

    @Test
    fun gstr1FlagsReverseChargeExportsSezAndEcommerceOperator() {
        val export = b2bSale.copy(
            id = "X1", number = "EXP-1", partyId = "P2", placeOfSupplyStateCode = "96",
            export = ExportDetails(ExportType.EXPORT_WITH_TAX, "1234567", b2bSale.date, "INBOM1"),
        )
        val sez = b2bSale.copy(id = "S1", number = "SEZ-1", export = ExportDetails(ExportType.SEZ_WITH_TAX))
        val eco = f.vouchers.first { it.id == "V3" }.copy(ecommerceGstin = "27AAAPL1234C1ZE")
        val g = Gstr1.build(data(rcmSale, export, sez, eco))
        assertEquals(listOf("EXP-1"), g.exp.map { it.voucher.number })
        // SEZ supplies are IGST even within the firm's state.
        assertEquals(Money.parse("180.00"), g.b2b.first { it.voucher.id == "S1" }.amounts.igst)
        assertEquals("27AAAPL1234C1ZE", g.b2cs.single().etin)
        val json = g.toJson()
        assertTrue("\"rchrg\": \"Y\"" in json, json)
        assertTrue("\"inv_typ\": \"SEZWP\"" in json, json)
        assertTrue("\"exp_typ\": \"WPAY\"" in json && "\"sbpcode\": \"INBOM1\"" in json, json)
        assertTrue("\"typ\": \"E\"" in json && "\"etin\": \"27AAAPL1234C1ZE\"" in json, json)

        val t = Reports.run(ReportKind.GSTR3B, data(rcmSale, export))
        assertEquals("1000.00", t.rows.first { (it[0] as String).startsWith("3.1(b)") }[1]) // export
        assertEquals(listOf("1000.00", "0.00"), t.rows.first { (it[0] as String).startsWith("3.1(c)") }.let { listOf(it[1], it[3]) })
        assertEquals("0.00", t.rows.first { (it[0] as String).startsWith("3.1(a)") }[1])
    }

    @Test
    fun freeQuantityMovesStockAndLowersPurchaseCost() {
        val line = purchase.lines[0].copy(freeQuantity = Quantity.of(5)) // 20 billed + 5 free, taxable 1000
        val moves = StockMovementDerivation { "M" }.derivePosted(purchase.copy(lines = listOf(line)), f.items.associateBy { it.id }, FirmSettings(f.F), f.at)
        assertEquals(Quantity.of(25), moves.single().quantity)
        assertEquals(Money.parse("40.00"), moves.single().unitCost)
        val sale = b2bSale.copy(lines = listOf(b2bSale.lines[0].copy(freeQuantity = Quantity.of(1))))
        assertEquals(Quantity.of(-11), StockMovementDerivation { "M" }.derivePosted(sale, f.items.associateBy { it.id }, FirmSettings(f.F), f.at).single().quantity)
    }

    @Test
    fun documentFieldsRoundTripAndPrintInOrder() {
        val fields = listOf(DocumentField("Order ref", "A-17"), DocumentField("Site", "Gate 2"))
        assertEquals(fields, DocumentField.decode(DocumentField.encode(fields)))
        assertEquals(emptyList(), DocumentField.decode(null))
        val v = b2bSale.copy(poNumber = "PO-9", ewayBillNumber = "331000000001", customFields = fields)
        assertEquals(listOf("PO no.", "E-way bill no.", "Order ref", "Site"), v.detailRows().map { it.first })
        assertTrue(v.copy(export = ExportDetails(ExportType.SEZ_UNDER_LUT)).isIntraState("27").not())
        assertEquals(VoucherType.SALE, v.type)
    }
}
