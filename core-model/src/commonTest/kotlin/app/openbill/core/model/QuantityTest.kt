package app.openbill.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuantityTest {
    @Test
    fun integralQuantitiesFormatWithoutDecimalPoint() {
        assertEquals("0", Quantity.of(0).format())
        assertEquals("5", Quantity.of(5).format())
        assertEquals("-5", Quantity.of(-5).format())
        assertEquals("5", Quantity.of(5, 0).format())
    }

    @Test
    fun formatterTrimsTrailingZeros() {
        assertEquals("1.5", Quantity.parse("1.5000").format())
        assertEquals("1.25", Quantity.parse("1.2500").format())
        assertEquals("1.2", Quantity.parse("1.2000").format())
        assertEquals("1", Quantity.parse("1.0000").format())
        assertEquals("0.5", Quantity.parse("0.5000").format())
        assertEquals("0.05", Quantity.parse("0.0500").format())
        assertEquals("0.005", Quantity.parse("0.0050").format())
        assertEquals("0.0001", Quantity.parse("0.0001").format())
        assertEquals("-1.5", Quantity.parse("-1.5000").format())
        assertEquals("-0.0001", Quantity.parse("-0.0001").format())
    }

    @Test
    fun constructsFromWholeUnitsAndFraction() {
        assertEquals(15_000L, Quantity.of(1, 5_000).scaledUnits)
        assertEquals(123_456L, Quantity.of(12, 3_456).scaledUnits)
        assertEquals(0L, Quantity.of(0, 0).scaledUnits)
        assertEquals(-15_000L, Quantity.of(-1, 5_000).scaledUnits)
        assertEquals("-1.5", Quantity.of(-1, 5_000).format())
        assertEquals("12.3456", Quantity.of(12, 3_456).format())
    }

    @Test
    fun rejectsInvalidFractionComponents() {
        assertFailsWith<IllegalArgumentException> { Quantity.of(1, -1) }
        assertFailsWith<IllegalArgumentException> { Quantity.of(1, 10_000) }
    }

    @Test
    fun parsesUpToFourFractionalDigits() {
        assertEquals(0L, Quantity.parse("0").scaledUnits)
        assertEquals(0L, Quantity.parse("-0.0000").scaledUnits)
        assertEquals(10_000L, Quantity.parse("1").scaledUnits)
        assertEquals(12_000L, Quantity.parse("1.2").scaledUnits)
        assertEquals(12_340L, Quantity.parse("1.234").scaledUnits)
        assertEquals(12_345L, Quantity.parse("1.2345").scaledUnits)
        assertEquals(15_000L, Quantity.parse("0001.5000").scaledUnits)
        assertEquals(1L, Quantity.parse("0.0001").scaledUnits)
        assertEquals(-1L, Quantity.parse("-0.0001").scaledUnits)
        assertEquals(-12_345L, Quantity.parse("-1.2345").scaledUnits)
    }

    @Test
    fun formatAndParseRoundTrip() {
        val values = listOf(
            Long.MIN_VALUE,
            -123_456_789L,
            -10_000L,
            -1L,
            0L,
            1L,
            10_000L,
            123_456_789L,
            Long.MAX_VALUE,
        )
        for (value in values) {
            val quantity = Quantity.ofScaledUnits(value)
            assertEquals(value, Quantity.parse(quantity.format()).scaledUnits)
        }
    }

    @Test
    fun rejectsInvalidText() {
        val invalidValues = listOf(
            "",
            " ",
            " +1",
            "+1",
            "-",
            ".",
            ".5",
            "1.",
            "1.23456",
            "1..0",
            "--1",
            "1a",
            "1 000",
            "1,5",
        )
        for (value in invalidValues) {
            assertFailsWith<IllegalArgumentException>("expected rejection of '$value'") {
                Quantity.parse(value)
            }
        }
    }

    @Test
    fun rejectsOverflow() {
        assertEquals(Long.MAX_VALUE, Quantity.parse("922337203685477.5807").scaledUnits)
        assertEquals(Long.MIN_VALUE, Quantity.parse("-922337203685477.5808").scaledUnits)
        assertFailsWith<ArithmeticException> { Quantity.parse("922337203685477.5808") }
        assertFailsWith<ArithmeticException> { Quantity.parse("-922337203685477.5809") }
        assertFailsWith<ArithmeticException> { Quantity.parse("999999999999999999999") }
        assertFailsWith<ArithmeticException> { Quantity.of(Long.MAX_VALUE) }
    }

    @Test
    fun arithmeticIsExact() {
        assertEquals(Quantity.of(3), Quantity.of(1) + Quantity.of(2))
        assertEquals(Quantity.of(-1), Quantity.of(1) - Quantity.of(2))
        assertEquals(Quantity.of(3), Quantity.of(1).times(3))
        assertEquals(Quantity.of(-3), Quantity.of(1).times(-3L))
        assertEquals(Quantity.of(0), Quantity.of(-5) + Quantity.of(5))
        assertTrue(Quantity.of(1) < Quantity.of(2))
        assertTrue(Quantity.ofScaledUnits(-1) < Quantity.of(0))
    }

    @Test
    fun arithmeticOverflowThrows() {
        assertFailsWith<ArithmeticException> {
            Quantity.ofScaledUnits(Long.MAX_VALUE) + Quantity.ofScaledUnits(1)
        }
        assertFailsWith<ArithmeticException> {
            Quantity.ofScaledUnits(Long.MIN_VALUE) - Quantity.ofScaledUnits(1)
        }
        assertFailsWith<ArithmeticException> {
            Quantity.ofScaledUnits(Long.MAX_VALUE).times(2L)
        }
        assertFailsWith<ArithmeticException> {
            Quantity.ofScaledUnits(Long.MIN_VALUE).times(-1L)
        }
    }

    @Test
    fun additionAndSubtractionAreInverse() {
        val random = Random(20261022)
        repeat(1_000) {
            val left = Quantity.ofScaledUnits(random.nextLong(-1_000_000_000L, 1_000_000_001L))
            val right = Quantity.ofScaledUnits(random.nextLong(-1_000_000_000L, 1_000_000_001L))
            assertEquals(left, left + right - right)
            assertEquals(Quantity.of(0), left - left)
            assertEquals(left + left, left.times(2))
        }
    }
}
