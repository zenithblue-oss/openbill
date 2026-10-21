package app.openbill.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyRoundingTest {
    @Test
    fun roundsPositiveAmountsToNearestRupee() {
        assertEquals(Money.of(1_100L), Money.of(1_124L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_200L), Money.of(1_176L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_100L), Money.of(1_149L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(1_200L), Money.of(1_151L).roundToRupee(RoundingMode.HALF_EVEN))
    }

    @Test
    fun positiveTiesUseHalfUpOrHalfEven() {
        assertEquals(Money.of(1_100L), Money.of(1_050L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_000L), Money.of(1_050L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(1_200L), Money.of(1_150L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_200L), Money.of(1_150L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(1_300L), Money.of(1_250L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_200L), Money.of(1_250L).roundToRupee(RoundingMode.HALF_EVEN))
    }

    @Test
    fun roundsNegativeAmountsToNearestRupee() {
        assertEquals(Money.of(-1_100L), Money.of(-1_124L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(-1_200L), Money.of(-1_176L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-1_100L), Money.of(-1_149L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-1_200L), Money.of(-1_151L).roundToRupee(RoundingMode.HALF_EVEN))
    }

    @Test
    fun negativeTiesUseHalfUpOrHalfEven() {
        assertEquals(Money.of(-1_100L), Money.of(-1_050L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(-1_000L), Money.of(-1_050L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-1_200L), Money.of(-1_150L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(-1_200L), Money.of(-1_150L).roundToRupee(RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-1_300L), Money.of(-1_250L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(-1_200L), Money.of(-1_250L).roundToRupee(RoundingMode.HALF_EVEN))
    }

    @Test
    fun wholeRupeesAndZeroAreUnchanged() {
        assertEquals(Money.ZERO, Money.ZERO.roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(1_200L), Money.of(1_200L).roundToRupee(RoundingMode.HALF_UP))
        assertEquals(Money.of(-1_200L), Money.of(-1_200L).roundToRupee(RoundingMode.HALF_EVEN))
    }

    @Test
    fun extremeValuesRoundWithoutOverflow() {
        val max = Money.of(Long.MAX_VALUE).roundToRupee(RoundingMode.HALF_UP)
        val min = Money.of(Long.MIN_VALUE).roundToRupee(RoundingMode.HALF_UP)

        assertEquals(Money.of(9_223_372_036_854_775_800L), max)
        assertEquals(Money.of(-9_223_372_036_854_775_800L), min)
        assertEquals(0L, max.paise % 100L)
        assertEquals(0L, min.paise % 100L)
        assertTrue(max <= Money.of(Long.MAX_VALUE))
        assertTrue(min >= Money.of(Long.MIN_VALUE))
    }
}
