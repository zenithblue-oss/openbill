package app.openbill.ui

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.ui.invoice.DraftCharge
import app.openbill.ui.invoice.DraftLine
import app.openbill.ui.invoice.InvoiceMath
import app.openbill.ui.invoice.LineForm
import app.openbill.ui.invoice.LineRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceLogicTest {
    private fun line(qty: String, rate: String, gst: Int, discount: String = "0", mode: TaxMode = TaxMode.EXCLUSIVE) = DraftLine(
        key = "k$qty$rate",
        itemId = null,
        description = "Thing",
        hsnSac = null,
        unit = null,
        quantity = Quantity.parse(qty),
        rate = Money.parse(rate),
        discount = Money.parse(discount),
        gstRate = Rate.ofPercent(gst),
        taxMode = mode,
    )

    @Test
    fun grossMultipliesQuantityAndRateInPaise() {
        assertEquals(Money.parse("312.50"), InvoiceMath.gross(Quantity.parse("2.5"), Money.parse("125")))
        assertEquals(Money.parse("0.33"), InvoiceMath.gross(Quantity.parse("0.333"), Money.parse("1")))
    }

    @Test
    fun intraStateSplitsIntoCgstAndSgst() {
        val s = InvoiceMath.summarize(listOf(line("2", "500", 18)), "29", "29", true, RoundingMode.HALF_UP)
        assertTrue(s.isIntraState)
        assertEquals(Money.parse("1000"), s.taxable)
        assertEquals(Money.parse("90"), s.cgst)
        assertEquals(Money.parse("90"), s.sgst)
        assertEquals(Money.ZERO, s.igst)
        assertEquals(Money.parse("1180"), s.grandTotal)
    }

    @Test
    fun interStateUsesIgstAndRoundsOff() {
        val s = InvoiceMath.summarize(listOf(line("1", "99.99", 5)), "29", "27", true, RoundingMode.HALF_UP)
        assertEquals(Money.parse("5.00"), s.igst)
        assertEquals(Money.ZERO, s.cgst)
        // 99.99 + 5.00 = 104.99 -> 105.00
        assertEquals(Money.parse("0.01"), s.roundOff)
        assertEquals(Money.parse("105"), s.grandTotal)
        assertEquals(s.taxable + s.totalTax + s.roundOff, s.grandTotal)
    }

    @Test
    fun voucherLinesCarryComputedAmountsThatReconcile() {
        val lines = listOf(line("3", "120", 12, discount = "10"), line("1", "1180", 18, mode = TaxMode.INCLUSIVE))
        val s = InvoiceMath.summarize(lines, "27", "27", true, RoundingMode.HALF_UP)
        val stored = InvoiceMath.toVoucherLines(lines, s) { it.key }
        assertEquals(listOf(1, 2), stored.map { it.lineNumber })
        assertEquals(Money.parse("1000"), stored[1].taxable)
        assertEquals(s.taxable, stored.fold(Money.ZERO) { a, l -> a + l.taxable })
        val rebuilt = InvoiceMath.summarizeStored(stored, "27", "27", s.roundOff, s.grandTotal, RoundingMode.HALF_UP)
        assertEquals(s.cgst, rebuilt.cgst)
        assertEquals(s.sgst, rebuilt.sgst)
    }

    @Test
    fun lineDiscountAcceptsAmountOrPercent() {
        assertEquals(Money.parse("25"), LineRules.discountAmount("25", Money.parse("200")))
        assertEquals(Money.parse("20"), LineRules.discountAmount("10%", Money.parse("200")))
        assertEquals(Money.ZERO, LineRules.discountAmount("", Money.parse("200")))
        assertNull(LineRules.discountAmount("abc", Money.parse("200")))
    }

    @Test
    fun lineValidationRejectsBadInput() {
        val (bad, errors) = LineRules.validate(LineForm(description = "", quantity = "0", rate = "x")) { "id" }
        assertNull(bad)
        assertTrue(errors.keys.containsAll(listOf("description", "quantity", "rate")))

        val (tooMuch, e2) = LineRules.validate(LineForm(description = "A", quantity = "1", rate = "100", discount = "150")) { "id" }
        assertNull(tooMuch)
        assertTrue("discount" in e2)

        val (ok, e3) = LineRules.validate(
            LineForm(description = "A", quantity = "2", rate = "50", discount = "10%", gstRate = Rate.ofPercent(18), inclusive = true),
        ) { "new-id" }
        assertNotNull(ok)
        assertTrue(e3.isEmpty())
        assertEquals("new-id", ok.key)
        assertEquals(Money.parse("10"), ok.discount)
        assertEquals(TaxMode.INCLUSIVE, ok.taxMode)
    }

    @Test
    fun percentDiscountSurvivesEditingAndReappliesToNewQuantity() {
        val (first, _) = LineRules.validate(LineForm(description = "Bulb", quantity = "3", rate = "99", discount = "10%")) { "k1" }
        assertNotNull(first)
        assertEquals(Money.parse("29.70"), first.discount)
        assertEquals(Rate.ofPercent(10), first.discountPercent)

        val reopened = LineRules.fromDraft(first)
        assertEquals("10%", reopened.discount)

        val (edited, _) = LineRules.validate(reopened.copy(quantity = "5")) { "unused" }
        assertNotNull(edited)
        assertEquals("k1", edited.key)
        assertEquals(Money.parse("49.50"), edited.discount)
    }

    @Test
    fun amountDiscountHasNoPercent() {
        val (line, _) = LineRules.validate(LineForm(description = "Bulb", quantity = "3", rate = "99", discount = "25")) { "k1" }
        assertNotNull(line)
        assertNull(line.discountPercent)
        assertEquals("25.00", LineRules.fromDraft(line).discount)
    }

    private fun charge(amount: String, basis: ChargeTaxBasis, gst: Int = 0, inclusive: Boolean = false) =
        DraftCharge("c", "Freight", Money.parse(amount), basis, Rate.ofPercent(gst), inclusive)

    @Test
    fun nonTaxableChargeAddsToTotalWithoutTax() {
        val s = InvoiceMath.summarize(listOf(line("1", "1000", 18)), "27", "27", true, RoundingMode.HALF_UP, listOf(charge("50", ChargeTaxBasis.NON_TAXABLE)))
        assertEquals(Money.parse("180"), s.totalTax)
        assertEquals(Money.parse("1230"), s.grandTotal)
    }

    @Test
    fun explicitRateChargeAddsTaxAndPersistsReconciled() {
        val lines = listOf(line("1", "1000", 5))
        val charges = listOf(charge("100", ChargeTaxBasis.EXPLICIT_RATE, 18))
        val s = InvoiceMath.summarize(lines, "27", "27", true, RoundingMode.HALF_UP, charges)
        assertEquals(Money.parse("68"), s.totalTax) // 50 + 18
        assertEquals(Money.parse("1168"), s.grandTotal)
        assertEquals(Money.parse("9"), s.cgst - Money.parse("25"))
        val stored = InvoiceMath.toVoucherCharges(charges, s) { it.key }
        assertEquals(Money.parse("118"), stored[0].total)
        val rebuilt = InvoiceMath.summarizeStored(InvoiceMath.toVoucherLines(lines, s) { it.key }, "27", "27", s.roundOff, s.grandTotal, RoundingMode.HALF_UP, stored)
        assertEquals(s.cgst, rebuilt.cgst)
        assertEquals(s.totalTax, rebuilt.totalTax)
    }

    @Test
    fun principalSupplyChargeFollowsLineRate() {
        val s = InvoiceMath.summarize(listOf(line("1", "1000", 12)), "27", "29", true, RoundingMode.HALF_UP, listOf(charge("100", ChargeTaxBasis.PRINCIPAL_SUPPLY)))
        assertEquals(Money.parse("132"), s.igst) // 120 + 12
        assertEquals(Money.parse("1232"), s.grandTotal)
    }

    @Test
    fun serialTrackedLinesNeedOneSerialPerUnitAndBatchLinesABatch() {
        val base = LineForm(description = "Phone", quantity = "2", rate = "100", trackSerial = true)
        assertTrue(LineRules.validate(base.copy(serials = "A1"), { "k" }).second.containsKey("serials"))
        val (line, errors) = LineRules.validate(base.copy(serials = "A1, A2"), { "k" })
        assertTrue(errors.isEmpty())
        assertEquals(listOf("A1", "A2"), line!!.serialNumbers)
        val batch = LineForm(description = "Syrup", quantity = "1", rate = "10", trackBatch = true)
        assertTrue(LineRules.validate(batch, { "k" }).second.containsKey("batch"))
        assertEquals("B7", LineRules.validate(batch.copy(batchNumber = "B7"), { "k" }).first!!.batchNumber)
    }

    @Test
    fun inventoryRoutesSurviveTheirKeys() {
        val routes = listOf(
            app.openbill.ui.nav.Route.InvoiceEditor(null, app.openbill.core.domain.model.VoucherType.PURCHASE),
            app.openbill.ui.nav.Route.InvoiceEditor(null, app.openbill.core.domain.model.VoucherType.DEBIT_NOTE, returnOf = "v1"),
            app.openbill.ui.nav.Route.InvoiceEditor("d1"),
            app.openbill.ui.nav.Route.StockSummary(lowOnly = true),
            app.openbill.ui.nav.Route.ItemStock("i1"),
            app.openbill.ui.nav.Route.StockEntry("i1", transfer = true),
            app.openbill.ui.nav.Route.StockEntry(null, transfer = false),
            app.openbill.ui.nav.Route.BarcodeLabels(listOf("a", "b")),
            app.openbill.ui.nav.Route.Purchases,
            app.openbill.ui.nav.Route.Godowns,
        )
        routes.forEach { assertEquals(it, app.openbill.ui.nav.Route.fromKey(it.key)) }
    }
}
