package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaxLineValidationTest {
    @Test
    fun rejectsInvalidStateCodes() {
        val invalidCodes = listOf(
            "",
            "1",
            "123",
            "2A",
            "A2",
            " 7",
            "7 ",
            "-1",
            "२७",
        )
        for (code in invalidCodes) {
            assertFailsWith<IllegalArgumentException>("expected firm state '$code' to be rejected") {
                TaxContext(firmStateCode = code, placeOfSupplyStateCode = "27")
            }
            assertFailsWith<IllegalArgumentException>("expected place of supply '$code' to be rejected") {
                TaxContext(firmStateCode = "27", placeOfSupplyStateCode = code)
            }
        }
    }

    @Test
    fun acceptsTwoDigitAsciiStateCodes() {
        val context = TaxContext(firmStateCode = "07", placeOfSupplyStateCode = "33")
        assertTrue(!context.isIntraState)
        assertEquals("07", context.firmStateCode)
        assertEquals("33", context.placeOfSupplyStateCode)
        assertTrue(TaxContext("07", "07").isIntraState)
    }

    @Test
    fun rejectsNegativeGrossAmount() {
        assertFailsWith<IllegalArgumentException> {
            TaxLineInput(
                grossAmount = Money.of(-1L),
                gstRate = Rate.ofPercent(18),
                mode = TaxMode.EXCLUSIVE,
            )
        }
    }

    @Test
    fun rejectsNegativeOrExcessiveDiscount() {
        assertFailsWith<IllegalArgumentException> {
            TaxLineInput(
                grossAmount = money("100.00"),
                discount = Money.of(-1L),
                gstRate = Rate.ofPercent(18),
                mode = TaxMode.EXCLUSIVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaxLineInput(
                grossAmount = money("100.00"),
                discount = money("100.01"),
                gstRate = Rate.ofPercent(18),
                mode = TaxMode.EXCLUSIVE,
            )
        }
    }

    @Test
    fun nonTaxableTreatmentsHaveNoTaxComponents() {
        val treatments = listOf(
            TaxTreatment.ZERO_RATED,
            TaxTreatment.EXEMPT,
            TaxTreatment.NIL_RATED,
            TaxTreatment.NON_GST,
        )
        for (treatment in treatments) {
            for (mode in TaxMode.entries) {
                val input = TaxLineInput(
                    grossAmount = money("100.00"),
                    discount = money("10.00"),
                    gstRate = zeroRate(),
                    cessRate = zeroRate(),
                    mode = mode,
                    treatment = treatment,
                )

                val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

                assertEquals(money("90.00"), result.taxableValue)
                assertTrue(result.components.isEmpty())
                assertEquals(Money.ZERO, result.totalTax)
                assertEquals(money("90.00"), result.totalAmount)
                assertLineInvariants(input, result)
            }
        }
    }

    @Test
    fun rejectsNonZeroRatesOnNonTaxableTreatments() {
        val treatments = listOf(
            TaxTreatment.ZERO_RATED,
            TaxTreatment.EXEMPT,
            TaxTreatment.NIL_RATED,
            TaxTreatment.NON_GST,
        )
        for (treatment in treatments) {
            assertFailsWith<IllegalArgumentException> {
                TaxLineInput(
                    grossAmount = money("100.00"),
                    gstRate = Rate.ofPercent(5),
                    mode = TaxMode.EXCLUSIVE,
                    treatment = treatment,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                TaxLineInput(
                    grossAmount = money("100.00"),
                    gstRate = zeroRate(),
                    cessRate = Rate.ofPercent(1),
                    mode = TaxMode.EXCLUSIVE,
                    treatment = treatment,
                )
            }
        }
    }

    @Test
    fun rejectsOddIntraStateRateUnits() {
        val oddRate = Rate.ofBasisPointsHundredths(1)
        for (mode in TaxMode.entries) {
            val input = TaxLineInput(
                grossAmount = money("100.00"),
                gstRate = oddRate,
                mode = mode,
            )
            assertFailsWith<IllegalArgumentException> {
                GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)
            }
        }
    }

    @Test
    fun interStateAcceptsOddRateUnitsBecauseIgstIsNotSplit() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = Rate.ofBasisPointsHundredths(1),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, interState, RoundingMode.HALF_UP)

        assertEquals(listOf(TaxKind.IGST), result.components.map { it.kind })
        assertLineInvariants(input, result)
    }

    @Test
    fun zeroRateTaxableLineHasNoComponents() {
        val input = TaxLineInput(
            grossAmount = money("100.00"),
            gstRate = zeroRate(),
            mode = TaxMode.EXCLUSIVE,
        )

        val result = GstCalculator.calculateLine(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.00"), result.taxableValue)
        assertTrue(result.components.isEmpty())
        assertEquals(Money.ZERO, result.totalTax)
        assertEquals(money("100.00"), result.totalAmount)
        assertLineInvariants(input, result)
    }
}
