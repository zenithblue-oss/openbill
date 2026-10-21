package app.openbill.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun zeroFormatsWithTwoFractionalDigits() {
        assertEquals(0L, Money.ZERO.paise)
        assertEquals("0.00", Money.ZERO.format())
        assertEquals("0.00", Money.ZERO.toString())
        assertEquals(Money.ZERO, Money.ZERO + Money.ZERO)
    }

    @Test
    fun positiveAmountsFormatWithExactlyTwoFractionalDigits() {
        assertEquals("0.01", Money.of(1).format())
        assertEquals("0.05", Money.of(5).format())
        assertEquals("1.00", Money.of(100).format())
        assertEquals("12.30", Money.of(1230).format())
        assertEquals("1234.56", Money.of(123456).format())
        assertEquals("92233720368547758.07", Money.of(Long.MAX_VALUE).format())
    }

    @Test
    fun negativeAmountsFormatWithLeadingMinus() {
        assertEquals("-0.01", Money.of(-1).format())
        assertEquals("-0.05", Money.of(-5).format())
        assertEquals("-1.00", Money.of(-100).format())
        assertEquals("-12.30", Money.of(-1230).format())
        assertEquals("-92233720368547758.08", Money.of(Long.MIN_VALUE).format())
    }

    @Test
    fun parsesDecimalText() {
        assertEquals(Money.of(0), Money.parse("0"))
        assertEquals(Money.of(0), Money.parse("-0.00"))
        assertEquals(Money.of(1), Money.parse("0.01"))
        assertEquals(Money.of(100), Money.parse("1.00"))
        assertEquals(Money.of(120), Money.parse("1.2"))
        assertEquals(Money.of(120), Money.parse("001.20"))
        assertEquals(Money.of(-1), Money.parse("-0.01"))
        assertEquals(Money.of(-12345), Money.parse("-123.45"))
        assertEquals(Money.of(Long.MAX_VALUE), Money.parse("92233720368547758.07"))
        assertEquals(Money.of(Long.MIN_VALUE), Money.parse("-92233720368547758.08"))
    }

    @Test
    fun formatAndParseRoundTrip() {
        val values = listOf(
            Long.MIN_VALUE,
            -123456789L,
            -100L,
            -1L,
            0L,
            1L,
            100L,
            123456789L,
            Long.MAX_VALUE,
        )
        for (value in values) {
            assertEquals(value, Money.parse(Money.of(value).format()).paise)
        }
    }

    @Test
    fun rejectsInvalidText() {
        val invalidValues = listOf(
            "",
            " ",
            " 1.00",
            "1.00 ",
            "+1.00",
            "-",
            ".",
            ".50",
            "1.",
            "1.234",
            "1..00",
            "--1.00",
            "1,00",
            "1e2",
            "abc",
        )
        for (value in invalidValues) {
            assertFailsWith<IllegalArgumentException>("expected rejection of '$value'") {
                Money.parse(value)
            }
        }
    }

    @Test
    fun rejectsParsingOverflow() {
        assertFailsWith<ArithmeticException> {
            Money.parse("92233720368547758.08")
        }
        assertFailsWith<ArithmeticException> {
            Money.parse("-92233720368547758.09")
        }
        assertFailsWith<ArithmeticException> {
            Money.parse("999999999999999999999999999")
        }
    }

    @Test
    fun arithmeticIsExact() {
        assertEquals(Money.of(300), Money.of(100) + Money.of(200))
        assertEquals(Money.of(-100), Money.of(100) - Money.of(200))
        assertEquals(Money.of(-100), -Money.of(100))
        assertEquals(Money.of(300), Money.of(100).times(3))
        assertEquals(Money.of(-300), Money.of(100).times(-3L))
        assertEquals(Money.ZERO, Money.of(-5) + Money.of(5))
        assertTrue(Money.of(1) < Money.of(2))
        assertTrue(Money.of(-1) < Money.ZERO)
    }

    @Test
    fun arithmeticOverflowThrows() {
        assertFailsWith<ArithmeticException> { Money.of(Long.MAX_VALUE) + Money.of(1) }
        assertFailsWith<ArithmeticException> { Money.of(Long.MIN_VALUE) - Money.of(1) }
        assertFailsWith<ArithmeticException> { -Money.of(Long.MIN_VALUE) }
        assertFailsWith<ArithmeticException> { Money.of(Long.MAX_VALUE).times(2L) }
        assertFailsWith<ArithmeticException> { Money.of(Long.MIN_VALUE).times(-1L) }
        assertFailsWith<ArithmeticException> { Money.of(-1L).times(Long.MIN_VALUE) }
    }

    @Test
    fun additionAndSubtractionAreInverse() {
        val random = Random(20261021)
        repeat(1_000) {
            val left = Money.of(random.nextLong(-1_000_000_000L, 1_000_000_001L))
            val right = Money.of(random.nextLong(-1_000_000_000L, 1_000_000_001L))
            assertEquals(left, left + right - right)
            assertEquals(left, left + Money.ZERO)
            assertEquals(Money.ZERO, left - left)
        }
    }
}
