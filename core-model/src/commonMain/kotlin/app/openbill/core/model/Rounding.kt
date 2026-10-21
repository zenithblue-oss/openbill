package app.openbill.core.model

enum class RoundingMode {
    HALF_UP,
    HALF_EVEN,
}

object Rounding {
    fun multiplyDivide(
        value: Long,
        multiplier: Long,
        divisor: Long,
        mode: RoundingMode,
    ): Long {
        require(divisor > 0L) { "divisor must be positive" }
        if (value == 0L || multiplier == 0L) return 0L

        val whole = value / divisor
        val remainder = value % divisor
        val multipliedWhole = checkedMultiply(whole, multiplier)
        val multipliedRemainder = checkedMultiply(remainder, multiplier)
        val fraction = divide(multipliedRemainder, divisor, mode)
        return checkedAdd(multipliedWhole, fraction)
    }

    fun divide(numerator: Long, denominator: Long, mode: RoundingMode): Long {
        if (denominator == 0L) throw ArithmeticException("Division by zero")
        if (numerator == Long.MIN_VALUE && denominator == -1L) {
            throw ArithmeticException("Long overflow")
        }

        val quotient = numerator / denominator
        val remainder = numerator % denominator
        if (remainder == 0L) return quotient

        val absoluteRemainder = if (remainder < 0L) -remainder else remainder
        val half: Long
        val denominatorIsOdd: Boolean
        if (denominator == Long.MIN_VALUE) {
            half = 1L shl 62
            denominatorIsOdd = false
        } else {
            val absoluteDenominator = if (denominator < 0L) -denominator else denominator
            half = absoluteDenominator / 2L
            denominatorIsOdd = absoluteDenominator % 2L != 0L
        }

        val isTie = absoluteRemainder == half && !denominatorIsOdd
        val isAboveHalf = absoluteRemainder > half
        val roundAwayFromZero = isAboveHalf ||
            (isTie && (mode == RoundingMode.HALF_UP || quotient % 2L != 0L))

        if (!roundAwayFromZero) return quotient

        return if ((numerator < 0L) xor (denominator < 0L)) {
            checkedSubtract(quotient, 1L)
        } else {
            checkedAdd(quotient, 1L)
        }
    }
}
