package app.openbill.core.tax

import app.openbill.core.model.Money
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private val allGoldenFixtures = rateGoldenFixtures + discountGoldenFixtures + edgeGoldenFixtures
private val goldenFixturesById = allGoldenFixtures.associateBy { it.id }

class TaxGoldenTest {
    @Test
    fun a01ZeroRateExclusiveIntra() = verifyGolden("A01")

    @Test
    fun a02QuarterPercentExclusiveInter() = verifyGolden("A02")

    @Test
    fun a03QuarterPercentExclusiveIntra() = verifyGolden("A03")

    @Test
    fun a04ThreePercentExclusiveIntra() = verifyGolden("A04")

    @Test
    fun a05FivePercentExclusiveInter() = verifyGolden("A05")

    @Test
    fun a06TwelvePercentExclusiveIntra() = verifyGolden("A06")

    @Test
    fun a07EighteenPercentExclusiveInter() = verifyGolden("A07")

    @Test
    fun a08TwentyEightPercentExclusiveIntra() = verifyGolden("A08")

    @Test
    fun a09TwentyEightPlusCess1Inter() = verifyGolden("A09")

    @Test
    fun a10TwentyEightPlusCess12Intra() = verifyGolden("A10")

    @Test
    fun a11TwentyEightPlusCess22Inter() = verifyGolden("A11")

    @Test
    fun a12TwentyEightPlusCess22Intra() = verifyGolden("A12")

    @Test
    fun a13EvenPaiseHalfEvenIntra() = verifyGolden("A13")

    @Test
    fun a14ThreePercentExclusiveInter() = verifyGolden("A14")

    @Test
    fun a15FivePercentExclusiveIntra() = verifyGolden("A15")

    @Test
    fun a16TwelvePercentExclusiveInter() = verifyGolden("A16")

    @Test
    fun a17ZeroRateExclusiveInter() = verifyGolden("A17")

    @Test
    fun a18TwentyEightPercentExclusiveInter() = verifyGolden("A18")

    @Test
    fun a19EvenPaiseHalfUpIntra() = verifyGolden("A19")

    @Test
    fun b01EighteenInclusiveIntra() = verifyGolden("B01")

    @Test
    fun b02EighteenInclusiveInter() = verifyGolden("B02")

    @Test
    fun b03TwelveInclusiveIntra() = verifyGolden("B03")

    @Test
    fun b04FiveInclusiveInter() = verifyGolden("B04")

    @Test
    fun b05ThreeInclusiveIntra() = verifyGolden("B05")

    @Test
    fun b06QuarterInclusiveInter() = verifyGolden("B06")

    @Test
    fun b07TwentyEightInclusiveIntra() = verifyGolden("B07")

    @Test
    fun b08TwentyEightCess22InclusiveInterLargestRemainderSplit() = verifyGolden("B08")

    @Test
    fun b09TwentyEightCess1InclusiveInterLargestRemainderSplit() = verifyGolden("B09")

    @Test
    fun b10TwentyEightCess12InclusiveInterLargestRemainderSplit() = verifyGolden("B10")

    @Test
    fun b11EighteenCess1InclusiveIntraLargestRemainderSplit() = verifyGolden("B11")

    @Test
    fun b12EighteenCess12InclusiveIntraLargestRemainderSplit() = verifyGolden("B12")

    @Test
    fun b13ZeroInclusiveIntra() = verifyGolden("B13")

    @Test
    fun b14EighteenInclusiveOddSplitIntraLargestRemainderSplit() = verifyGolden("B14")

    @Test
    fun b15QuarterInclusiveIntra() = verifyGolden("B15")

    @Test
    fun b16ThreeInclusiveInter() = verifyGolden("B16")

    @Test
    fun b17FiveInclusiveIntra() = verifyGolden("B17")

    @Test
    fun b18TwelveInclusiveInter() = verifyGolden("B18")

    @Test
    fun b19TwentyEightInclusiveInter() = verifyGolden("B19")

    @Test
    fun b20ZeroInclusiveInter() = verifyGolden("B20")

    @Test
    fun c01EighteenExclusiveLineDiscountIntra() = verifyGolden("C01")

    @Test
    fun c02TwelveExclusiveLineDiscountInter() = verifyGolden("C02")

    @Test
    fun c03EighteenInclusiveLineDiscountIntra() = verifyGolden("C03")

    @Test
    fun c04TwentyEightInclusiveLineDiscountInter() = verifyGolden("C04")

    @Test
    fun c05TinyInclusiveLineDiscountIntra() = verifyGolden("C05")

    @Test
    fun c06FullLineDiscount() = verifyGolden("C06")

    @Test
    fun d01InvoiceDiscountEqualShares() = verifyGolden("D01")

    @Test
    fun d02InvoiceDiscountWeightedLines() = verifyGolden("D02")

    @Test
    fun d03InvoiceDiscountLargestRemainder() = verifyGolden("D03")

    @Test
    fun d04InvoiceDiscountInclusive() = verifyGolden("D04")

    @Test
    fun d05InvoiceDiscountOnePaiseTieLargestRemainderSplit() = verifyGolden("D05")

    @Test
    fun d06InvoiceDiscountZeroWeightLine() = verifyGolden("D06")

    @Test
    fun d07InvoiceDiscountFullSubtotal() = verifyGolden("D07")

    @Test
    fun d08InvoiceDiscountMixedRates() = verifyGolden("D08")

    @Test
    fun d09InvoiceDiscountMixedModes() = verifyGolden("D09")

    @Test
    fun e01MixedTreatmentsIntra() = verifyGolden("E01")

    @Test
    fun e02MixedTreatmentsInvoiceDiscountInter() = verifyGolden("E02")

    @Test
    fun e03AllExemptRoundUp() = verifyGolden("E03")

    @Test
    fun e04NonGstOnly() = verifyGolden("E04")

    @Test
    fun e05ZeroRatedOnly() = verifyGolden("E05")

    @Test
    fun e06NilRatedOnly() = verifyGolden("E06")

    @Test
    fun e07ZeroRateTaxableVsExempt() = verifyGolden("E07")

    @Test
    fun e08UnionTerritoryIntra() = verifyGolden("E08")

    @Test
    fun f01OnePaiseExclusiveUnrounded() = verifyGolden("F01")

    @Test
    fun f02OnePaiseExclusiveRounded() = verifyGolden("F02")

    @Test
    fun f03OneRupeeInclusiveLargestRemainderSplit() = verifyGolden("F03")

    @Test
    fun f04OneRupeeCess() = verifyGolden("F04")

    @Test
    fun f05OneRupeeQuarter() = verifyGolden("F05")

    @Test
    fun f06OnePaiseInclusiveQuarter() = verifyGolden("F06")

    @Test
    fun g01LargeEighteenExclusive() = verifyGolden("G01")

    @Test
    fun g02LargeTwentyEightCessExclusive() = verifyGolden("G02")

    @Test
    fun g03LargeTwentyEightCessInclusiveLargestRemainderSplit() = verifyGolden("G03")

    @Test
    fun g04LargeEighteenCessInclusiveLargestRemainderSplit() = verifyGolden("G04")

    @Test
    fun g05LargeTwelveExclusive() = verifyGolden("G05")

    @Test
    fun g06LargeFiveInclusive() = verifyGolden("G06")

    @Test
    fun h01RoundOffUp() = verifyGolden("H01")

    @Test
    fun h02RoundOffDown() = verifyGolden("H02")

    @Test
    fun h03RoundOffZero() = verifyGolden("H03")

    @Test
    fun h04RoundOffHalfUp() = verifyGolden("H04")

    @Test
    fun h05RoundOffHalfEvenDown() = verifyGolden("H05")

    @Test
    fun h06RoundOffHalfEvenUp() = verifyGolden("H06")

    @Test
    fun h07RoundOffDisabled() = verifyGolden("H07")

    @Test
    fun h08InclusiveRoundDown() = verifyGolden("H08")

    @Test
    fun h09InclusiveRoundUpLargestRemainderLargestRemainderSplit() = verifyGolden("H09")
}

private fun verifyGolden(id: String) {
    val fixture = goldenFixturesById[id] ?: fail("Unknown golden fixture $id")
    val prefix = "${fixture.id} ${fixture.name}"
    val input = InvoiceTaxInput(
        lines = fixture.lines.map { it.toTaxLineInput() },
        invoiceDiscount = Money.parse(fixture.invoiceDiscount),
        roundToRupee = fixture.roundToRupee,
    )

    val result = InvoiceTaxCalculator.calculate(input, fixture.context, fixture.rounding)

    assertEquals(
        fixture.allocatedDiscounts.map(Money::parse),
        result.allocatedInvoiceDiscounts,
        "$prefix allocated invoice discounts",
    )
    assertEquals(fixture.lines.size, result.lines.size, "$prefix line count")
    assertEquals(Money.parse(fixture.invoiceDiscount), result.invoiceDiscount, "$prefix invoice discount")

    var expectedTaxableTotal = Money.ZERO
    var expectedTaxTotal = Money.ZERO
    var expectedBeforeRounding = Money.ZERO

    for (index in fixture.lines.indices) {
        val source = fixture.lines[index]
        val expected = fixture.expectedLines[index]
        val actual = result.lines[index]
        val lineLabel = "$prefix line ${index + 1}"

        assertEquals(Money.parse(expected.taxableValue), actual.taxableValue, "$lineLabel taxable value")
        assertEquals(Money.parse(expected.totalTax), actual.totalTax, "$lineLabel total tax")
        assertEquals(Money.parse(expected.totalAmount), actual.totalAmount, "$lineLabel total amount")
        assertEquals(
            expected.components.map { it.kind },
            actual.components.map { it.kind },
            "$lineLabel component kinds",
        )
        assertEquals(
            expected.components.map { percentRate(it.ratePercent) },
            actual.components.map { it.rate },
            "$lineLabel component rates",
        )
        assertEquals(
            expected.components.map { Money.parse(it.amount) },
            actual.components.map { it.amount },
            "$lineLabel component amounts",
        )

        val expectedComponentSum = expected.components.fold(Money.ZERO) { total, component ->
            total + Money.parse(component.amount)
        }
        assertEquals(Money.parse(expected.totalTax), expectedComponentSum, "$lineLabel fixture component sum")

        val actualComponentSum = actual.components.fold(Money.ZERO) { total, component ->
            total + component.amount
        }
        assertEquals(actual.totalTax, actualComponentSum, "$lineLabel actual component sum")

        val adjustedNet = Money.parse(source.gross) -
            Money.parse(source.discount) -
            Money.parse(fixture.allocatedDiscounts[index])
        if (source.treatment == TaxTreatment.TAXABLE && source.mode == TaxMode.EXCLUSIVE) {
            assertEquals(
                Money.parse(expected.totalAmount),
                Money.parse(expected.taxableValue) + Money.parse(expected.totalTax),
                "$lineLabel fixture exclusive total",
            )
        } else {
            assertEquals(Money.parse(expected.totalAmount), adjustedNet, "$lineLabel fixture net total")
        }

        expectedTaxableTotal += Money.parse(expected.taxableValue)
        expectedTaxTotal += Money.parse(expected.totalTax)
        expectedBeforeRounding += Money.parse(expected.totalAmount)
    }

    assertEquals(
        Money.parse(fixture.expectedTotalTaxable),
        result.totalTaxable,
        "$prefix invoice taxable",
    )
    assertEquals(Money.parse(fixture.expectedTotalTax), result.totalTax, "$prefix invoice total tax")
    assertEquals(Money.parse(fixture.expectedRoundOff), result.roundOff, "$prefix invoice round-off")
    assertEquals(Money.parse(fixture.expectedGrandTotal), result.grandTotal, "$prefix invoice grand total")

    assertEquals(expectedTaxableTotal, Money.parse(fixture.expectedTotalTaxable), "$prefix fixture taxable sum")
    assertEquals(expectedTaxTotal, Money.parse(fixture.expectedTotalTax), "$prefix fixture tax sum")
    assertEquals(
        Money.parse(fixture.invoiceDiscount),
        fixture.allocatedDiscounts.fold(Money.ZERO) { total, share -> total + Money.parse(share) },
        "$prefix fixture invoice discount allocation",
    )
    assertEquals(
        Money.parse(fixture.invoiceDiscount),
        result.allocatedInvoiceDiscounts.fold(Money.ZERO) { total, share -> total + share },
        "$prefix actual invoice discount allocation",
    )
    assertEquals(
        result.totalTaxable,
        result.lines.fold(Money.ZERO) { total, line -> total + line.taxableValue },
        "$prefix taxable sum invariant",
    )
    assertEquals(
        result.totalTax,
        result.lines.fold(Money.ZERO) { total, line -> total + line.totalTax },
        "$prefix tax sum invariant",
    )

    val actualBeforeRounding = result.lines.fold(Money.ZERO) { total, line -> total + line.totalAmount }
    assertEquals(expectedBeforeRounding, actualBeforeRounding, "$prefix fixture pre-round sum")
    assertEquals(
        Money.parse(fixture.expectedGrandTotal) - Money.parse(fixture.expectedRoundOff),
        actualBeforeRounding,
        "$prefix pre-round identity",
    )
    assertEquals(
        Money.parse(fixture.expectedGrandTotal),
        actualBeforeRounding + result.roundOff,
        "$prefix grand total identity",
    )

    if (fixture.roundToRupee) {
        assertEquals(0L, result.grandTotal.paise % 100L, "$prefix grand total must be a whole rupee")
        assertTrue(abs(result.roundOff.paise) <= 50L, "$prefix round-off must be at most half a rupee")
    } else {
        assertEquals(Money.ZERO, result.roundOff, "$prefix round-off must be zero when disabled")
        assertEquals(actualBeforeRounding, result.grandTotal, "$prefix grand total must stay unrounded")
    }
}

private fun GoldenLine.toTaxLineInput(): TaxLineInput = TaxLineInput(
    grossAmount = Money.parse(gross),
    discount = Money.parse(discount),
    gstRate = percentRate(gstPercent),
    cessRate = percentRate(cessPercent),
    mode = mode,
    treatment = treatment,
)
