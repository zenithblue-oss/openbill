package app.openbill.core.model

@JvmInline
value class Money private constructor(val paise: Long) : Comparable<Money> {
    companion object {
        private const val PAISE_PER_RUPEE = 100L

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

    fun roundToRupee(mode: RoundingMode): Money =
        Money(checkedMultiply(Rounding.divide(paise, PAISE_PER_RUPEE, mode), PAISE_PER_RUPEE))

    fun format(): String = formatScaledDecimal(paise, 2, trimTrailingZeros = false)

    override fun toString(): String = format()
}
