package app.openbill.core.tax

import app.openbill.core.model.Money
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private val chargeFixturesById = chargeGoldenFixtures.associateBy { it.base.id }

class TaxGoldenChargeTest {
    @Test
    fun k01ExplicitEighteenExclusiveIntra() = verifyChargeGolden("K01")

    @Test
    fun k02NonTaxableChargeInterRoundUp() = verifyChargeGolden("K02")

    @Test
    fun k03PrincipalHighestRateMixedIntra() = verifyChargeGolden("K03")

    @Test
    fun k04PrincipalLargestValueMixedIntra() = verifyChargeGolden("K04")

    @Test
    fun k05ExplicitInclusiveChargeInter() = verifyChargeGolden("K05")

    @Test
    fun k06InvoiceDiscountThenChargeThenRoundOffIntra() = verifyChargeGolden("K06")

    @Test
    fun k07CreditNoteWithChargesInter() = verifyChargeGolden("K07")

    @Test
    fun k08PrincipalFollowsExemptInvoice() = verifyChargeGolden("K08")

    @Test
    fun k09PrincipalCarriesCessInter() = verifyChargeGolden("K09")

    @Test
    fun k10InclusivePrincipalChargeLargestRemainderIntra() = verifyChargeGolden("K10")

    @Test
    fun k11ExplicitChargeUnroundedInter() = verifyChargeGolden("K11")

    @Test
    fun k12PrincipalSkipsFullyDiscountedHigherRateLine() = verifyChargeGolden("K12")

    @Test
    fun everyChargeFixtureHasATest() {
        assertEquals(12, chargeGoldenFixtures.size)
        assertEquals(chargeGoldenFixtures.size, chargeFixturesById.size, "fixture ids must be unique")
    }
}

private fun verifyChargeGolden(id: String) {
    val fixture = chargeFixturesById[id] ?: fail("Unknown charge golden fixture $id")
    val base = fixture.base
    val prefix = "${base.id} ${base.name}"
    val input = InvoiceTaxInput(
        lines = base.lines.map { line ->
            TaxLineInput(
                grossAmount = Money.parse(line.gross),
                discount = Money.parse(line.discount),
                gstRate = percentRate(line.gstPercent),
                cessRate = percentRate(line.cessPercent),
                mode = line.mode,
                treatment = line.treatment,
            )
        },
        invoiceDiscount = Money.parse(base.invoiceDiscount),
        roundToRupee = base.roundToRupee,
        charges = fixture.charges.map { charge ->
            ChargeTaxInput(
                amount = Money.parse(charge.amount),
                basis = charge.basis,
                gstRate = percentRate(charge.gstPercent),
                cessRate = percentRate(charge.cessPercent),
                mode = charge.mode,
            )
        },
        principalRatePolicy = fixture.policy,
    )

    val result = InvoiceTaxCalculator.calculate(input, base.context, base.rounding)

    assertEquals(base.allocatedDiscounts.map(Money::parse), result.allocatedInvoiceDiscounts, "$prefix discounts")
    assertEquals(base.lines.size, result.lines.size, "$prefix line count")
    base.expectedLines.forEachIndexed { index, expected ->
        assertLineMatches(expected, result.lines[index], "$prefix line ${index + 1}")
    }

    assertEquals(fixture.charges.size, result.charges.size, "$prefix charge count")
    fixture.expectedCharges.forEachIndexed { index, expected ->
        val actual = result.charges[index]
        val label = "$prefix charge ${index + 1}"
        assertEquals(fixture.charges[index].basis, actual.basis, "$label basis")
        assertEquals(expected.treatment, actual.treatment, "$label treatment")
        assertEquals(percentRate(expected.gstPercent), actual.gstRate, "$label gst rate")
        assertEquals(percentRate(expected.cessPercent), actual.cessRate, "$label cess rate")
        assertEquals(expected.principalLine, actual.principalLineIndex, "$label principal line")
        assertEquals(ChargeTaxInput.CHARGE_SUMMARY_CODE, actual.summaryCode, "$label summary code")
        assertLineMatches(expected.line, actual.result, label)
        assertTrue(actual.result.trace.first().startsWith("charge basis="), "$label trace header")
        assertTrue(actual.result.trace.all { entry -> entry.all { it.code < 128 } }, "$label trace ASCII")
        if (fixture.charges[index].mode == TaxMode.EXCLUSIVE || actual.treatment != TaxTreatment.TAXABLE) {
            if (actual.treatment == TaxTreatment.TAXABLE) {
                assertEquals(Money.parse(fixture.charges[index].amount), actual.result.taxableValue, "$label taxable")
            } else {
                assertEquals(Money.parse(fixture.charges[index].amount), actual.result.totalAmount, "$label total")
            }
        } else {
            assertEquals(Money.parse(fixture.charges[index].amount), actual.result.totalAmount, "$label inclusive total")
        }
    }

    assertEquals(Money.parse(base.expectedTotalTaxable), result.totalTaxable, "$prefix total taxable")
    assertEquals(Money.parse(base.expectedTotalTax), result.totalTax, "$prefix total tax")
    assertEquals(Money.parse(base.expectedRoundOff), result.roundOff, "$prefix round-off")
    assertEquals(Money.parse(base.expectedGrandTotal), result.grandTotal, "$prefix grand total")

    val parts = result.lines + result.charges.map { it.result }
    val sumOf = { pick: (TaxLineResult) -> Money -> parts.fold(Money.ZERO) { total, part -> total + pick(part) } }
    assertEquals(result.totalTaxable, sumOf { it.taxableValue }, "$prefix taxable identity")
    assertEquals(result.totalTax, sumOf { it.totalTax }, "$prefix tax identity")
    assertEquals(result.grandTotal, sumOf { it.totalAmount } + result.roundOff, "$prefix grand total identity")

    val summaryTaxable = result.summary.fold(Money.ZERO) { total, row -> total + row.taxableValue }
    val summaryTax = result.summary.fold(Money.ZERO) { total, row -> total + row.totalTax }
    assertEquals(result.totalTaxable, summaryTaxable, "$prefix summary taxable")
    assertEquals(result.totalTax, summaryTax, "$prefix summary tax")
    assertTrue(
        result.summary.any { it.code == ChargeTaxInput.CHARGE_SUMMARY_CODE },
        "$prefix charges must appear in the summary",
    )

    if (base.roundToRupee) {
        assertEquals(0L, result.grandTotal.paise % 100L, "$prefix whole rupee")
        assertTrue(abs(result.roundOff.paise) <= 50L, "$prefix round-off bound")
    } else {
        assertEquals(Money.ZERO, result.roundOff, "$prefix no round-off")
    }
}

private fun assertLineMatches(expected: GoldenLineExpectation, actual: TaxLineResult, label: String) {
    assertEquals(Money.parse(expected.taxableValue), actual.taxableValue, "$label taxable value")
    assertEquals(Money.parse(expected.totalTax), actual.totalTax, "$label total tax")
    assertEquals(Money.parse(expected.totalAmount), actual.totalAmount, "$label total amount")
    assertEquals(expected.components.map { it.kind }, actual.components.map { it.kind }, "$label kinds")
    assertEquals(
        expected.components.map { percentRate(it.ratePercent) },
        actual.components.map { it.rate },
        "$label rates",
    )
    assertEquals(
        expected.components.map { Money.parse(it.amount) },
        actual.components.map { it.amount },
        "$label amounts",
    )
}
