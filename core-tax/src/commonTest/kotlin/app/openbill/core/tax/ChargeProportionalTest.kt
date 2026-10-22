package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-calculated proportional apportionment cases (K13-K17). */
class ChargeProportionalTest {
    private fun taxable(gross: String, percent: Int, hsn: String? = null, treatment: TaxTreatment = TaxTreatment.TAXABLE) =
        TaxLineInput(
            grossAmount = money(gross),
            gstRate = Rate.ofPercent(percent),
            mode = TaxMode.EXCLUSIVE,
            hsnSac = hsn,
            treatment = treatment,
        )

    private fun run(
        lines: List<TaxLineInput>,
        charge: ChargeTaxInput,
        context: TaxContext = intraState,
        policy: PrincipalRatePolicy = PrincipalRatePolicy.PROPORTIONAL,
    ) = InvoiceTaxCalculator.calculate(
        InvoiceTaxInput(lines = lines, charges = listOf(charge), principalRatePolicy = policy),
        context,
        RoundingMode.HALF_UP,
    )

    @Test
    fun k13SplitsByTaxableValueIntra() {
        val result = run(
            listOf(taxable("1000.00", 5), taxable("200.00", 18)),
            ChargeTaxInput(amount = money("120.00"), hsnSac = "996511"),
        )
        val charge = result.charges.single()
        assertNull(charge.principalLineIndex)
        assertEquals(2, charge.portions.size)
        assertEquals(money("100.00"), charge.portions[0].result.taxableValue)
        assertEquals(money("5.00"), charge.portions[0].result.totalTax)
        assertEquals(money("20.00"), charge.portions[1].result.taxableValue)
        assertEquals(money("3.60"), charge.portions[1].result.totalTax)
        assertEquals(money("120.00"), charge.result.taxableValue)
        assertEquals(money("8.60"), charge.result.totalTax)
        assertEquals(money("128.60"), charge.result.totalAmount)
        assertEquals(
            listOf(TaxKind.CGST, TaxKind.SGST, TaxKind.CGST, TaxKind.SGST),
            charge.result.components.map { it.kind },
        )
        assertEquals(money("1320.00"), result.totalTaxable)
        assertEquals(money("94.60"), result.totalTax)
        assertEquals(money("0.40"), result.roundOff)
        assertEquals(money("1415.00"), result.grandTotal)
        // HSN summary: one row per code and rate; charge portions under the charge's SAC.
        assertEquals(4, result.summary.size)
        val sac = result.summary.filter { it.code == "996511" }
        assertEquals(listOf(money("100.00"), money("20.00")), sac.map { it.taxableValue })
        assertEquals(listOf(money("2.50"), money("1.80")), sac.map { it.cgst })
        assertEquals(result.totalTax, result.summary.fold(Money.ZERO) { t, r -> t + r.totalTax })
    }

    @Test
    fun k14RemainderPaiseAllocatedDeterministicallyAndSumsExactly() {
        val lines = listOf(taxable("100.00", 5), taxable("100.00", 12), taxable("100.00", 18))
        val charge = ChargeTaxInput(amount = money("100.00"))
        val first = run(lines, charge).charges.single()
        val second = run(lines, charge).charges.single()
        assertEquals(first, second)
        val amounts = first.portions.map { it.result.taxableValue }
        assertEquals(listOf(money("33.34"), money("33.33"), money("33.33")), amounts)
        assertEquals(money("100.00"), amounts.fold(Money.ZERO) { t, a -> t + a })
    }

    @Test
    fun k15ExemptGoodsCarryExemptFreightShare() {
        val result = run(
            listOf(taxable("300.00", 0, treatment = TaxTreatment.EXEMPT), taxable("100.00", 18)),
            ChargeTaxInput(amount = money("40.00")),
        )
        val charge = result.charges.single()
        assertEquals(TaxTreatment.TAXABLE, charge.treatment)
        assertEquals(TaxTreatment.EXEMPT, charge.portions[0].treatment)
        assertEquals(money("30.00"), charge.portions[0].result.totalAmount)
        assertEquals(money("0.00"), charge.portions[0].result.totalTax)
        assertEquals(money("10.00"), charge.portions[1].result.taxableValue)
        assertEquals(money("1.80"), charge.portions[1].result.totalTax)
    }

    @Test
    fun k16InclusiveInterStateSplitsGrossAndSumsToCharge() {
        val result = run(
            listOf(taxable("100.00", 5), taxable("100.00", 18)),
            ChargeTaxInput(amount = money("118.00"), mode = TaxMode.INCLUSIVE),
            context = interState,
        )
        val charge = result.charges.single()
        assertEquals(listOf(money("59.00"), money("59.00")), charge.portions.map { it.result.totalAmount })
        assertEquals(money("118.00"), charge.result.totalAmount)
        assertTrue(charge.result.components.all { it.kind == TaxKind.IGST })
        charge.portions.forEach {
            assertEquals(it.result.totalAmount, it.result.taxableValue + it.result.totalTax)
        }
    }

    @Test
    fun k17SingleRateInvoiceHasOnePortionEqualToOtherPolicies() {
        val lines = listOf(taxable("500.00", 18), taxable("100.00", 18))
        val charge = ChargeTaxInput(amount = money("50.00"))
        val proportional = run(lines, charge).charges.single()
        val highest = run(lines, charge, policy = PrincipalRatePolicy.HIGHEST_RATE).charges.single()
        assertEquals(1, proportional.portions.size)
        assertEquals(highest.result.totalAmount, proportional.result.totalAmount)
        assertEquals(highest.result.totalTax, proportional.result.totalTax)
        assertEquals(highest.result.components, proportional.result.components)
    }

    @Test
    fun zeroValueLinesAreIgnoredAndExplicitChargesAreNotSplit() {
        val lines = listOf(taxable("100.00", 5), taxable("0.00", 28), taxable("100.00", 12))
        val explicit = run(
            lines,
            ChargeTaxInput(amount = money("10.00"), basis = ChargeTaxBasis.EXPLICIT_RATE, gstRate = Rate.ofPercent(18)),
        ).charges.single()
        assertTrue(explicit.portions.isEmpty())
        assertEquals(1, explicit.effectivePortions.size)
        val split = run(lines, ChargeTaxInput(amount = money("10.00"))).charges.single()
        assertEquals(listOf(Rate.ofPercent(5), Rate.ofPercent(12)), split.portions.map { it.gstRate })
    }
}
