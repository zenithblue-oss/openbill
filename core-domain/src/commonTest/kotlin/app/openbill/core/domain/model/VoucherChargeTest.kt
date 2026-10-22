package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.core.tax.InvoiceTaxCalculator
import app.openbill.core.tax.InvoiceTaxInput
import app.openbill.core.tax.PrincipalRatePolicy
import app.openbill.core.tax.TaxContext
import app.openbill.core.tax.TaxLineInput
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.TaxTreatment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoucherChargeTest {
    private val intra = TaxContext("27", "27")

    @Test
    fun defaultsAreAdditiveAndFollowThePrincipalSupply() {
        val charge = VoucherCharge(id = "c1", name = "Freight", amount = Money.of(100L))
        assertEquals(ChargeTaxBasis.PRINCIPAL_SUPPLY, charge.taxBasis)
        assertEquals(TaxMode.EXCLUSIVE, charge.taxMode)
        assertEquals(TaxTreatment.TAXABLE, charge.treatment)
        assertEquals(null, charge.hsnSac)
    }

    @Test
    fun proportionalChargeStoresPortionsSummingToTheCharge() {
        val charge = VoucherCharge(id = "c1", name = "Freight", amount = Money.of(12_000L))
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = listOf(
                    TaxLineInput(Money.of(100_000L), gstRate = Rate.ofPercent(5), mode = TaxMode.EXCLUSIVE),
                    TaxLineInput(Money.of(20_000L), gstRate = Rate.ofPercent(18), mode = TaxMode.EXCLUSIVE),
                ),
                charges = listOf(charge.toTaxInput()),
            ),
            intra,
            RoundingMode.HALF_UP,
        )
        val applied = charge.withTaxResult(result.charges.single())
        assertEquals(2, applied.portions.size)
        assertEquals(Money.of(860L), applied.taxAmount)
        assertEquals(Money.of(12_860L), applied.total)
        assertEquals(Money.of(500L), applied.portions[0].taxAmount)
        assertEquals(Money.of(360L), applied.portions[1].taxAmount)
        assertEquals(Rate.ofPercent(18), applied.taxRate)
        assertFailsWith<IllegalArgumentException> {
            applied.copy(taxAmount = Money.of(861L), total = Money.of(12_861L))
        }
    }

    @Test
    fun principalChargeStoresResolvedRateAndAmounts() {
        val charge = VoucherCharge(id = "c1", name = "Freight", amount = Money.of(15_000L), hsnSac = "9965")
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(
                lines = listOf(
                    TaxLineInput(Money.of(100_000L), gstRate = Rate.ofPercent(5), mode = TaxMode.EXCLUSIVE),
                    TaxLineInput(Money.of(20_000L), gstRate = Rate.ofPercent(18), mode = TaxMode.EXCLUSIVE),
                ),
                charges = listOf(charge.toTaxInput()),
                principalRatePolicy = PrincipalRatePolicy.HIGHEST_RATE,
            ),
            intra,
            RoundingMode.HALF_UP,
        )
        val applied = charge.withTaxResult(result.charges.single())
        assertEquals(Rate.ofPercent(18), applied.taxRate)
        assertEquals(Money.of(2_700L), applied.taxAmount)
        assertEquals(Money.of(17_700L), applied.total)
        assertEquals(Money.of(15_000L), applied.taxableValue)
        assertEquals("9965", result.charges.single().summaryCode)
    }

    @Test
    fun explicitRatePassesRatesAndOtherBasesDoNot() {
        val explicit = VoucherCharge(
            id = "c1",
            name = "Insurance",
            amount = Money.of(100L),
            taxRate = Rate.ofPercent(18),
            taxBasis = ChargeTaxBasis.EXPLICIT_RATE,
        )
        assertEquals(Rate.ofPercent(18), explicit.toTaxInput().gstRate)
        // A stored principal-supply charge keeps its applied rate but recomputes from the rule.
        val principal = explicit.copy(taxBasis = ChargeTaxBasis.PRINCIPAL_SUPPLY)
        assertEquals(0L, principal.toTaxInput().gstRate.units)
    }

    @Test
    fun nonTaxableFactoryAndValidation() {
        val packing = VoucherCharge.nonTaxable("c1", "Packing", Money.of(500L))
        assertEquals(TaxTreatment.NON_GST, packing.treatment)
        assertEquals(Money.of(500L), packing.taxableValue)
        assertEquals(ChargeTaxBasis.NON_TAXABLE, packing.toTaxInput().basis)

        assertFailsWith<IllegalArgumentException> {
            packing.copy(taxAmount = Money.of(1L), total = Money.of(501L))
        }
        assertFailsWith<IllegalArgumentException> {
            packing.copy(treatment = TaxTreatment.TAXABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            VoucherCharge(id = "c2", name = "Freight", amount = Money.of(1L), taxAmount = Money.of(2L), total = Money.of(1L))
        }
    }
}
