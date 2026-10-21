package app.openbill.core.model

internal fun checkedAdd(left: Long, right: Long): Long {
    val result = left + right
    if (((left xor result) and (right xor result)) < 0L) {
        throw ArithmeticException("Long overflow")
    }
    return result
}

internal fun checkedSubtract(left: Long, right: Long): Long {
    val result = left - right
    if (((left xor right) and (left xor result)) < 0L) {
        throw ArithmeticException("Long overflow")
    }
    return result
}

internal fun checkedMultiply(left: Long, right: Long): Long {
    if (left == 0L || right == 0L) return 0L
    if (left == Long.MIN_VALUE && right == -1L) throw ArithmeticException("Long overflow")
    if (right == Long.MIN_VALUE && left == -1L) throw ArithmeticException("Long overflow")
    val result = left * right
    if (result / right != left) throw ArithmeticException("Long overflow")
    return result
}

internal fun checkedNegate(value: Long): Long {
    if (value == Long.MIN_VALUE) throw ArithmeticException("Long overflow")
    return -value
}

internal fun parseScaledDecimal(text: String, fractionDigits: Int, typeName: String): Long {
    fun invalid(reason: String): Nothing =
        throw IllegalArgumentException("Invalid $typeName '$text': $reason")

    if (text.isEmpty()) invalid("blank value")

    var index = 0
    val negative = text[0] == '-'
    if (negative) index++
    if (index == text.length) invalid("missing digits")

    var value = 0L
    var seenDigit = false
    var seenDot = false
    var fractionCount = 0

    while (index < text.length) {
        val char = text[index]
        when {
            char in '0'..'9' -> {
                val digit = char - '0'
                if (seenDot) {
                    fractionCount++
                    if (fractionCount > fractionDigits) {
                        invalid("expected at most $fractionDigits fractional digits")
                    }
                }
                value = checkedMultiply(value, 10L)
                value = checkedSubtract(value, digit.toLong())
                seenDigit = true
            }

            char == '.' -> {
                if (seenDot) invalid("multiple decimal points")
                if (!seenDigit) invalid("missing integer digits")
                seenDot = true
            }

            else -> invalid("unexpected character '$char'")
        }
        index++
    }

    if (!seenDigit) invalid("missing digits")
    if (seenDot && fractionCount == 0) invalid("missing fractional digits")

    repeat(fractionDigits - fractionCount) {
        value = checkedMultiply(value, 10L)
    }

    if (negative) return value
    if (value == Long.MIN_VALUE) throw ArithmeticException("Overflow parsing $typeName")
    return -value
}

internal fun formatScaledDecimal(value: Long, scale: Int, trimTrailingZeros: Boolean): String {
    val factor = powerOfTen(scale)
    val whole = value / factor
    val fraction = value % factor
    val absoluteWhole = if (whole < 0L) -whole else whole
    val absoluteFraction = if (fraction < 0L) -fraction else fraction

    val result = StringBuilder()
    if (value < 0L) result.append('-')
    result.append(absoluteWhole)

    if (fraction != 0L || !trimTrailingZeros) {
        result.append('.')
        val fractionText = absoluteFraction.toString().padStart(scale, '0')
        result.append(if (trimTrailingZeros) fractionText.trimEnd('0') else fractionText)
    }

    return result.toString()
}

private fun powerOfTen(exponent: Int): Long = when (exponent) {
    1 -> 10L
    2 -> 100L
    3 -> 1_000L
    4 -> 10_000L
    else -> throw IllegalArgumentException("Unsupported scale $exponent")
}
