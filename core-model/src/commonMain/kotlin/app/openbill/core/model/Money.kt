package app.openbill.core.model

@JvmInline
value class Money private constructor(val paise: Long) : Comparable<Money> {
    companion object {
        val ZERO: Money = Money(0L)

        fun of(paise: Long): Money = Money(paise)

        fun parse(text: String): Money = Money(parseScaledDecimal(text, 2, "money"))
    }

    operator fun plus(other: Money): Money = Money(checkedAdd(paise, other.paise))

    operator fun minus(other: Money): Money = Money(checkedSubtract(paise, other.paise))

    operator fun unaryMinus(): Money = Money(checkedNegate(paise))

    operator fun times(factor: Int): Money = times(factor.toLong())

    operator fun times(factor: Long): Money = Money(checkedMultiply(paise, factor))

    override fun compareTo(other: Money): Int = paise.compareTo(other.paise)

    fun format(): String = formatScaledDecimal(paise, 2, trimTrailingZeros = false)

    override fun toString(): String = format()
}
