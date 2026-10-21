package app.openbill.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateTest {
    @Test
    fun representsPercentInHundredthsOfABasisPoint() {
        assertEquals(0L, Rate.ofPercent(0).units)
        assertEquals(10_000L, Rate.ofPercent(1).units)
        assertEquals(180_000L, Rate.ofPercent(18).units)
        assertEquals(180_000L, Rate.ofPercent(18L).units)
        assertEquals(2_500L, Rate.ofBasisPoints(25).units)
    }

    @Test
    fun representsFractionalBasisPoints() {
        assertEquals(2_500L, Rate.ofBasisPointsHundredths(2_500).units)
        assertEquals(Rate.ofBasisPoints(25), Rate.ofBasisPointsHundredths(2_500))
        assertEquals(1_250L, Rate.ofBasisPointsHundredths(1_250).units)
        assertEquals(1L, Rate.ofBasisPointsHundredths(1).units)
    }

    @Test
    fun halvesRatesExactly() {
        assertEquals(1_250L, Rate.ofBasisPointsHundredths(2_500).half().units)
        assertEquals(Rate.ofBasisPointsHundredths(1_250), Rate.ofBasisPointsHundredths(2_500).half())
        assertEquals(90_000L, Rate.ofPercent(18).half().units)
        assertEquals(0L, Rate.ofPercent(0).half().units)
    }

    @Test
    fun rejectsOddRateHalves() {
        assertFailsWith<IllegalArgumentException> {
            Rate.ofBasisPointsHundredths(1).half()
        }
        assertFailsWith<IllegalArgumentException> {
            Rate.ofBasisPointsHundredths(125).half()
        }
    }

    @Test
    fun eighteenPercentOfOneHundredIsEighteen() {
        val amount = Money.parse("100.00")
        assertEquals(Money.parse("18.00"), Rate.ofPercent(18).applyTo(amount, RoundingMode.HALF_UP))
        assertEquals(Money.parse("18.00"), Rate.ofPercent(18).applyTo(amount, RoundingMode.HALF_EVEN))
        assertEquals(Money.of(1_800L), Rate.ofPercent(18).applyTo(amount, RoundingMode.HALF_UP))
    }

    @Test
    fun appliesRateToNegativeMoney() {
        val amount = Money.parse("-100.00")
        assertEquals(Money.parse("-18.00"), Rate.ofPercent(18).applyTo(amount, RoundingMode.HALF_UP))
        assertEquals(Money.parse("-18.00"), Rate.ofPercent(18).applyTo(amount, RoundingMode.HALF_EVEN))
    }

    @Test
    fun zeroRateProducesZero() {
        assertEquals(Money.ZERO, Rate.ofPercent(0).applyTo(Money.parse("123.45"), RoundingMode.HALF_UP))
    }

    @Test
    fun roundsFiveThousandthsOfARupee() {
        val rupee = Money.parse("1.00")
        val halfPercent = Rate.ofBasisPoints(50)

        assertEquals(Money.of(1L), halfPercent.applyTo(rupee, RoundingMode.HALF_UP))
        assertEquals(Money.ZERO, halfPercent.applyTo(rupee, RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-1L), halfPercent.applyTo(-rupee, RoundingMode.HALF_UP))
        assertEquals(Money.ZERO, halfPercent.applyTo(-rupee, RoundingMode.HALF_EVEN))
    }

    @Test
    fun appliesHalfUpAndHalfEvenToTies() {
        val rupee = Money.parse("1.00")
        val rate = Rate.ofBasisPoints(250)

        assertEquals(Money.of(3L), rate.applyTo(rupee, RoundingMode.HALF_UP))
        assertEquals(Money.of(2L), rate.applyTo(rupee, RoundingMode.HALF_EVEN))
        assertEquals(Money.of(-3L), rate.applyTo(-rupee, RoundingMode.HALF_UP))
        assertEquals(Money.of(-2L), rate.applyTo(-rupee, RoundingMode.HALF_EVEN))
    }

    @Test
    fun comparesByUnits() {
        assertTrue(Rate.ofBasisPointsHundredths(1) < Rate.ofBasisPointsHundredths(2))
        assertEquals(0, Rate.ofPercent(5).compareTo(Rate.ofBasisPoints(500)))
    }

    @Test
    fun rejectsNegativeRates() {
        assertFailsWith<IllegalArgumentException> { Rate.ofBasisPoints(-1L) }
        assertFailsWith<IllegalArgumentException> { Rate.ofPercent(-1) }
        assertFailsWith<IllegalArgumentException> { Rate.ofPercent(-1L) }
        assertFailsWith<IllegalArgumentException> { Rate.ofBasisPointsHundredths(-1L) }
    }

    @Test
    fun appliesRateWithoutIntermediateOverflow() {
        val amount = Money.of(Long.MAX_VALUE / 2L)
        val rate = Rate.ofBasisPoints(1)
        assertEquals(Money.of(461168601842739L), rate.applyTo(amount, RoundingMode.HALF_UP))
        assertEquals(Money.of(-461168601842739L), rate.applyTo(-amount, RoundingMode.HALF_UP))
    }

    @Test
    fun rejectsOverflow() {
        assertFailsWith<ArithmeticException> { Rate.ofPercent(Long.MAX_VALUE) }
        assertFailsWith<ArithmeticException> { Rate.ofBasisPoints(Long.MAX_VALUE) }
        assertFailsWith<ArithmeticException> {
            Rate.ofBasisPointsHundredths(Long.MAX_VALUE).applyTo(Money.of(2L), RoundingMode.HALF_UP)
        }
    }
}
