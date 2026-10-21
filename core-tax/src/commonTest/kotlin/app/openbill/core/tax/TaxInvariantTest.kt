package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.random.Random
import kotlin.test.Test

class TaxInvariantTest {
    @Test
    fun randomizedTaxableLinesSatisfyInvariants() {
        val random = Random(20261024)
        val gstUnits = longArrayOf(
            0L,
            1_000L,
            2_500L,
            5_000L,
            12_000L,
            12_500L,
            60_000L,
            90_000L,
            180_000L,
            280_000L,
        )
        val cessUnits = longArrayOf(0L, 500L, 1_000L, 10_000L, 20_000L)

        repeat(2_000) {
            val grossPaise = random.nextLong(0L, 1_000_001L)
            val discountPaise = if (grossPaise == 0L) {
                0L
            } else {
                random.nextLong(0L, grossPaise + 1L)
            }
            val input = TaxLineInput(
                grossAmount = Money.of(grossPaise),
                discount = Money.of(discountPaise),
                gstRate = Rate.ofBasisPointsHundredths(gstUnits[random.nextInt(gstUnits.size)]),
                cessRate = Rate.ofBasisPointsHundredths(cessUnits[random.nextInt(cessUnits.size)]),
                mode = TaxMode.entries[random.nextInt(TaxMode.entries.size)],
            )
            val context = if (random.nextBoolean()) intraState else interState
            val rounding = RoundingMode.entries[random.nextInt(RoundingMode.entries.size)]

            val result = GstCalculator.calculateLine(input, context, rounding)

            assertLineInvariants(input, result)
        }
    }

    @Test
    fun randomizedNonTaxableLinesSatisfyInvariants() {
        val random = Random(20261025)
        val treatments = listOf(
            TaxTreatment.ZERO_RATED,
            TaxTreatment.EXEMPT,
            TaxTreatment.NIL_RATED,
            TaxTreatment.NON_GST,
        )

        repeat(500) {
            val grossPaise = random.nextLong(0L, 1_000_001L)
            val discountPaise = if (grossPaise == 0L) {
                0L
            } else {
                random.nextLong(0L, grossPaise + 1L)
            }
            val input = TaxLineInput(
                grossAmount = Money.of(grossPaise),
                discount = Money.of(discountPaise),
                gstRate = zeroRate(),
                cessRate = zeroRate(),
                mode = TaxMode.entries[random.nextInt(TaxMode.entries.size)],
                treatment = treatments[random.nextInt(treatments.size)],
            )
            val context = if (random.nextBoolean()) intraState else interState
            val rounding = RoundingMode.entries[random.nextInt(RoundingMode.entries.size)]

            val result = GstCalculator.calculateLine(input, context, rounding)

            assertLineInvariants(input, result)
        }
    }
}
