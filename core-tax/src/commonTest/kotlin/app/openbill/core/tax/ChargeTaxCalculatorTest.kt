package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChargeTaxCalculatorTest {
    private fun taxable(gross: String, percent: Int, hsn: String? = null) = TaxLineInput(
        grossAmount = money(gross),
        gstRate = Rate.ofPercent(percent),
        mode = TaxMode.EXCLUSIVE,
        hsnSac = hsn,
    )

    @Test
    fun noChargesKeepsExistingResultShape() {
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(lines = listOf(taxable("100.00", 18))),
            intraState,
            RoundingMode.HALF_UP,
        )
        assertTrue(result.charges.isEmpty())
        assertEquals(money("100.00"), result.totalTaxable)
        assertEquals(money("118.00"), result.grandTotal)
        assertEquals(1, result.summary.size)
    }

    @Test
    fun defaultChargeBasisIsPrincipalSupplyWithProportionalPolicy() {
        val charge = ChargeTaxInput(amount = money("10.00"))
        assertEquals(ChargeTaxBasis.PRINCIPAL_SUPPLY, charge.basis)
        assertEquals(TaxMode.EXCLUSIVE, charge.mode)
        assertEquals(PrincipalRatePolicy.PROPORTIONAL, InvoiceTaxInput(lines = emptyList()).principalRatePolicy)
    }

    @Test
    fun nonExplicitBasesRejectRates() {
        assertFailsWith<IllegalArgumentException> {
            ChargeTaxInput(amount = money("1.00"), basis = ChargeTaxBasis.NON_TAXABLE, gstRate = Rate.ofPercent(18))
        }
        assertFailsWith<IllegalArgumentException> {
            ChargeTaxInput(
                amount = money("1.00"),
                basis = ChargeTaxBasis.PRINCIPAL_SUPPLY,
                cessRate = Rate.ofPercent(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChargeTaxInput(amount = money("-1.00"), basis = ChargeTaxBasis.NON_TAXABLE)
        }
    }

    @Test
    fun principalChargeWithoutValuedLinesIsRejected() {
        val principal = ChargeTaxInput(amount = money("5.00"))
        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(
                InvoiceTaxInput(lines = emptyList(), charges = listOf(principal)),
                intraState,
                RoundingMode.HALF_UP,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(
                InvoiceTaxInput(
                    lines = listOf(taxable("100.00", 18)),
                    invoiceDiscount = money("100.00"),
                    charges = listOf(principal),
                ),
                intraState,
                RoundingMode.HALF_UP,
            )
        }
    }

    @Test
    fun chargesOnlyInvoiceWorksForExplicitAndNonTaxable() {
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = emptyList(),
                charges = listOf(
                    ChargeTaxInput(money("100.00"), ChargeTaxBasis.EXPLICIT_RATE, gstRate = Rate.ofPercent(18)),
                    ChargeTaxInput(money("20.00"), ChargeTaxBasis.NON_TAXABLE),
                ),
            ),
            interState,
            RoundingMode.HALF_UP,
        )
        assertEquals(money("120.00"), result.totalTaxable)
        assertEquals(money("18.00"), result.totalTax)
        assertEquals(money("138.00"), result.grandTotal)
    }

    @Test
    fun invoiceDiscountIsNotAllocatedToCharges() {
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = listOf(taxable("100.00", 18)),
                invoiceDiscount = money("10.00"),
                charges = listOf(ChargeTaxInput(money("50.00"))),
            ),
            intraState,
            RoundingMode.HALF_UP,
        )
        assertEquals(listOf(money("10.00")), result.allocatedInvoiceDiscounts)
        assertEquals(money("50.00"), result.charges.single().result.taxableValue)
        // Discount larger than lines is still rejected even when charges would cover it.
        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(
                InvoiceTaxInput(
                    lines = listOf(taxable("100.00", 18)),
                    invoiceDiscount = money("120.00"),
                    charges = listOf(ChargeTaxInput(money("50.00"))),
                ),
                intraState,
                RoundingMode.HALF_UP,
            )
        }
    }

    @Test
    fun highestRateTiesGoToEarlierLineAndPreferTaxableOverExempt() {
        val lines = listOf(
            TaxLineInput(grossAmount = money("900.00"), gstRate = zeroRate(), mode = TaxMode.EXCLUSIVE, treatment = TaxTreatment.EXEMPT),
            taxable("100.00", 0),
            taxable("50.00", 12),
            taxable("70.00", 12),
        )
        val highest = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = lines,
                charges = listOf(ChargeTaxInput(money("10.00"))),
                principalRatePolicy = PrincipalRatePolicy.HIGHEST_RATE,
            ),
            intraState,
            RoundingMode.HALF_UP,
        )
        // 12% lines tie on rate; the larger value (70.00) wins the tie-break.
        assertEquals(3, highest.charges.single().principalLineIndex)

        val tie = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = listOf(taxable("50.00", 12), taxable("50.00", 12)),
                charges = listOf(ChargeTaxInput(money("10.00"))),
                principalRatePolicy = PrincipalRatePolicy.HIGHEST_RATE,
            ),
            intraState,
            RoundingMode.HALF_UP,
        )
        assertEquals(0, tie.charges.single().principalLineIndex)

        val largest = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = lines,
                charges = listOf(ChargeTaxInput(money("10.00"))),
                principalRatePolicy = PrincipalRatePolicy.LARGEST_VALUE,
            ),
            intraState,
            RoundingMode.HALF_UP,
        )
        assertEquals(0, largest.charges.single().principalLineIndex)
        assertEquals(TaxTreatment.EXEMPT, largest.charges.single().treatment)
        assertEquals(Money.ZERO, largest.charges.single().result.totalTax)
    }

    @Test
    fun oddExplicitRateIsRejectedIntraStateButAcceptedInterState() {
        val odd = ChargeTaxInput(
            money("100.00"),
            ChargeTaxBasis.EXPLICIT_RATE,
            gstRate = Rate.ofBasisPointsHundredths(1L),
        )
        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(InvoiceTaxInput(lines = emptyList(), charges = listOf(odd)), intraState, RoundingMode.HALF_UP)
        }
        InvoiceTaxCalculator.calculate(InvoiceTaxInput(lines = emptyList(), charges = listOf(odd)), interState, RoundingMode.HALF_UP)
    }

    @Test
    fun summaryGroupsByCodeTreatmentAndRateWithChargeBucket() {
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = listOf(
                    taxable("100.00", 18, hsn = "8471"),
                    taxable("200.00", 18, hsn = "8471"),
                    taxable("50.00", 5, hsn = "1006"),
                ),
                charges = listOf(
                    ChargeTaxInput(money("30.00")),
                    ChargeTaxInput(money("20.00"), ChargeTaxBasis.EXPLICIT_RATE, gstRate = Rate.ofPercent(18), hsnSac = "9965"),
                    ChargeTaxInput(money("5.00"), ChargeTaxBasis.NON_TAXABLE),
                ),
                principalRatePolicy = PrincipalRatePolicy.HIGHEST_RATE,
            ),
            intraState,
            RoundingMode.HALF_UP,
        )
        val rows = result.summary.associateBy { Triple(it.code, it.treatment, it.gstRate.units) }
        assertEquals(5, result.summary.size)
        val goods = rows.getValue(Triple("8471", TaxTreatment.TAXABLE, 180_000L))
        assertEquals(money("300.00"), goods.taxableValue)
        assertEquals(money("27.00"), goods.cgst)
        assertEquals(money("27.00"), goods.sgst)
        assertEquals(money("30.00"), rows.getValue(Triple("CHARGES", TaxTreatment.TAXABLE, 180_000L)).taxableValue)
        assertEquals(money("20.00"), rows.getValue(Triple("9965", TaxTreatment.TAXABLE, 180_000L)).taxableValue)
        val nonTaxable = rows.getValue(Triple("CHARGES", TaxTreatment.NON_GST, 0L))
        assertEquals(money("5.00"), nonTaxable.taxableValue)
        assertEquals(Money.ZERO, nonTaxable.totalTax)
        assertEquals("8471", result.summary.first().code)
    }
}
