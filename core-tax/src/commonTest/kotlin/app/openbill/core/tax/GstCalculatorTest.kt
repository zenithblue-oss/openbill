package app.openbill.core.tax

import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals

class GstCalculatorTest {
    @Test
    fun intraStateEighteenPercentExclusiveSplitsIntoCgstAndSgst() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = Rate.ofPercent(18),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertEquals(listOf(TaxKind.CGST, TaxKind.SGST), result.components.map { it.kind })
        assertEquals(
            listOf(Rate.ofPercent(9), Rate.ofPercent(9)),
            result.components.map { it.rate },
        )
        assertEquals(listOf(money("9.00"), money("9.00")), result.components.map { it.amount })
        assertEquals(money("18.00"), result.totalTax)
        assertEquals(money("118.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun interStateEighteenPercentExclusiveUsesIgst() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = Rate.ofPercent(18),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, interState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertEquals(listOf(TaxKind.IGST), result.components.map { it.kind })
        assertEquals(listOf(money("18.00")), result.components.map { it.amount })
        assertEquals(money("18.00"), result.totalTax)
        assertEquals(money("118.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun inclusiveEighteenPercentExtractsTaxFromGross() {
        val input = TaxLineInput(
            grossAmount = money("118.00"),
            gstRate = Rate.ofPercent(18),
            mode = TaxMode.INCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertEquals(listOf(TaxKind.CGST, TaxKind.SGST), result.components.map { it.kind })
        assertEquals(listOf(money("9.00"), money("9.00")), result.components.map { it.amount })
        assertEquals(money("18.00"), result.totalTax)
        assertEquals(money("118.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun quarterPercentExclusiveRoundsEachHalfRateComponent() {
        val rate = Rate.ofBasisPointsHundredths(2_500)
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = rate,
            mode = TaxMode.EXCLUSIVE,
        )

        val halfUp = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)
        assertEquals(
            listOf(Rate.ofBasisPointsHundredths(1_250), Rate.ofBasisPointsHundredths(1_250)),
            halfUp.components.map { it.rate },
        )
        assertEquals(listOf(money("0.13"), money("0.13")), halfUp.components.map { it.amount })
        assertEquals(money("0.26"), halfUp.totalTax)
        assertEquals(money("100.26"), halfUp.totalAmount)
        assertLineInvariants(input, halfUp)

        val halfEven = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_EVEN)
        assertEquals(listOf(money("0.12"), money("0.12")), halfEven.components.map { it.amount })
        assertEquals(money("0.24"), halfEven.totalTax)
        assertEquals(money("100.24"), halfEven.totalAmount)
        assertLineInvariants(input, halfEven)
    }

    @Test
    fun quarterPercentInclusiveExtractsTaxAndAllocatesHalfRates() {
        val rate = Rate.ofBasisPointsHundredths(2_500)
        val input = TaxLineInput(
            grossAmount = money("100.25"),
            gstRate = rate,
            mode = TaxMode.INCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertEquals(
            listOf(Rate.ofBasisPointsHundredths(1_250), Rate.ofBasisPointsHundredths(1_250)),
            result.components.map { it.rate },
        )
        assertEquals(listOf(money("0.13"), money("0.12")), result.components.map { it.amount })
        assertEquals(money("0.25"), result.totalTax)
        assertEquals(money("100.25"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun cessIsComputedAlongsideGstForExclusivePrices() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = Rate.ofPercent(18),
            cessRate = Rate.ofPercent(1),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(
            listOf(TaxKind.CGST, TaxKind.SGST, TaxKind.CESS),
            result.components.map { it.kind },
        )
        assertEquals(
            listOf(money("9.00"), money("9.00"), money("1.00")),
            result.components.map { it.amount },
        )
        assertEquals(money("19.00"), result.totalTax)
        assertEquals(money("119.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun cessIsAllocatedAlongsideGstForInclusivePrices() {
        val input = TaxLineInput(
            grossAmount = money("119.00"),
            gstRate = Rate.ofPercent(18),
            cessRate = Rate.ofPercent(1),
            mode = TaxMode.INCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertEquals(
            listOf(TaxKind.CGST, TaxKind.SGST, TaxKind.CESS),
            result.components.map { it.kind },
        )
        assertEquals(
            listOf(money("9.00"), money("9.00"), money("1.00")),
            result.components.map { it.amount },
        )
        assertEquals(money("19.00"), result.totalTax)
        assertEquals(money("119.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun lineDiscountReducesTheTaxableValue() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            discount = money("10.00"),
            gstRate = Rate.ofPercent(18),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("90.00"), result.taxableValue)
        assertEquals(listOf(money("8.10"), money("8.10")), result.components.map { it.amount })
        assertEquals(money("16.20"), result.totalTax)
        assertEquals(money("106.20"), result.totalAmount)
        assertLineInvariants(input, result)
    }

    @Test
    fun inclusiveDiscountReducesTheNetAmount() {
        val input = TaxLineInput(
            grossAmount = money("118.00"),
            discount = money("11.80"),
            gstRate = Rate.ofPercent(18),
            mode = TaxMode.INCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("90.00"), result.taxableValue)
        assertEquals(money("16.20"), result.totalTax)
        assertEquals(money("106.20"), result.totalAmount)
        assertLineInvariants(input, result)
    }
}
