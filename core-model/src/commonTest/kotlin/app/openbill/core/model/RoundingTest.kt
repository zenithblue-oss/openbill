package app.openbill.core.model

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoundingTest {
    @Test
    fun halfUpRoundsTiesAwayFromZero() {
        assertEquals(3L, Rounding.divide(5L, 2L, RoundingMode.HALF_UP))
        assertEquals(-3L, Rounding.divide(-5L, 2L, RoundingMode.HALF_UP))
        assertEquals(-3L, Rounding.divide(5L, -2L, RoundingMode.HALF_UP))
        assertEquals(3L, Rounding.divide(-5L, -2L, RoundingMode.HALF_UP))
        assertEquals(-1L, Rounding.divide(-1L, 2L, RoundingMode.HALF_UP))
    }

    @Test
    fun halfEvenRoundsTiesToEven() {
        assertEquals(2L, Rounding.divide(5L, 2L, RoundingMode.HALF_EVEN))
        assertEquals(4L, Rounding.divide(7L, 2L, RoundingMode.HALF_EVEN))
        assertEquals(-2L, Rounding.divide(-5L, 2L, RoundingMode.HALF_EVEN))
        assertEquals(-4L, Rounding.divide(-7L, 2L, RoundingMode.HALF_EVEN))
        assertEquals(-2L, Rounding.divide(5L, -2L, RoundingMode.HALF_EVEN))
        assertEquals(2L, Rounding.divide(-5L, -2L, RoundingMode.HALF_EVEN))
        assertEquals(0L, Rounding.divide(-1L, 2L, RoundingMode.HALF_EVEN))
    }

    @Test
    fun roundsNonTiesToNearestInteger() {
        assertEquals(0L, Rounding.divide(0L, 7L, RoundingMode.HALF_UP))
        assertEquals(0L, Rounding.divide(1L, 3L, RoundingMode.HALF_UP))
        assertEquals(1L, Rounding.divide(2L, 3L, RoundingMode.HALF_UP))
        assertEquals(2L, Rounding.divide(7L, 3L, RoundingMode.HALF_UP))
        assertEquals(3L, Rounding.divide(8L, 3L, RoundingMode.HALF_EVEN))
        assertEquals(-2L, Rounding.divide(-7L, 3L, RoundingMode.HALF_EVEN))
        assertEquals(-3L, Rounding.divide(-8L, 3L, RoundingMode.HALF_EVEN))
    }

    @Test
    fun roundsHalfPaiseFromFiveThousandths() {
        assertEquals(1L, Rounding.divide(5L, 10L, RoundingMode.HALF_UP))
        assertEquals(0L, Rounding.divide(5L, 10L, RoundingMode.HALF_EVEN))
        assertEquals(-1L, Rounding.divide(-5L, 10L, RoundingMode.HALF_UP))
        assertEquals(0L, Rounding.divide(-5L, 10L, RoundingMode.HALF_EVEN))
    }

    @Test
    fun handlesExtremeValues() {
        assertEquals(1L, Rounding.divide(Long.MAX_VALUE, Long.MAX_VALUE, RoundingMode.HALF_UP))
        assertEquals(1L, Rounding.divide(Long.MIN_VALUE, Long.MIN_VALUE, RoundingMode.HALF_UP))
        assertEquals(
            Long.MIN_VALUE / 2L,
            Rounding.divide(Long.MIN_VALUE, 2L, RoundingMode.HALF_UP),
        )
        assertEquals(0L, Rounding.divide(1L, Long.MIN_VALUE, RoundingMode.HALF_UP))
        assertEquals(-1L, Rounding.divide(1L shl 62, Long.MIN_VALUE, RoundingMode.HALF_UP))
        assertEquals(0L, Rounding.divide(1L shl 62, Long.MIN_VALUE, RoundingMode.HALF_EVEN))
    }

    @Test
    fun rejectsZeroDenominator() {
        assertFailsWith<ArithmeticException> { Rounding.divide(1L, 0L, RoundingMode.HALF_UP) }
        assertFailsWith<ArithmeticException> { Rounding.divide(0L, 0L, RoundingMode.HALF_EVEN) }
    }

    @Test
    fun rejectsQuotientOverflow() {
        assertFailsWith<ArithmeticException> {
            Rounding.divide(Long.MIN_VALUE, -1L, RoundingMode.HALF_UP)
        }
    }

    @Test
    fun roundedResultIsWithinHalfOfDenominator() {
        val random = Random(20261023)
        repeat(1_000) {
            val numerator = random.nextLong(-1_000_000L, 1_000_001L)
            var denominator = random.nextLong(-1_000L, 1_001L)
            if (denominator == 0L) denominator = 1L
            for (mode in RoundingMode.entries) {
                val result = Rounding.divide(numerator, denominator, mode)
                val error = abs(result * denominator - numerator)
                assertTrue(error * 2L <= abs(denominator))
            }
        }
    }
}
