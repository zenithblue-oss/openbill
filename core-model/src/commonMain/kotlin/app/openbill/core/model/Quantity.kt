package app.openbill.core.model

@JvmInline
value class Quantity private constructor(val scaledUnits: Long) : Comparable<Quantity> {
    companion object {
        private const val UNITS_PER_WHOLE = 10_000L

        fun of(units: Long): Quantity = Quantity(checkedMultiply(units, UNITS_PER_WHOLE))

        fun of(units: Long, tenThousandths: Long): Quantity {
            require(tenThousandths in 0L..9_999L) {
                "tenThousandths must be between 0 and 9999"
            }
            val whole = checkedMultiply(units, UNITS_PER_WHOLE)
            return if (units < 0L) {
                Quantity(checkedSubtract(whole, tenThousandths))
            } else {
                Quantity(checkedAdd(whole, tenThousandths))
            }
        }

        fun ofScaledUnits(scaledUnits: Long): Quantity = Quantity(scaledUnits)

        fun parse(text: String): Quantity = Quantity(parseScaledDecimal(text, 4, "quantity"))
    }

    operator fun plus(other: Quantity): Quantity =
        Quantity(checkedAdd(scaledUnits, other.scaledUnits))

    operator fun minus(other: Quantity): Quantity =
        Quantity(checkedSubtract(scaledUnits, other.scaledUnits))

    operator fun times(factor: Int): Quantity = times(factor.toLong())

    operator fun times(factor: Long): Quantity =
        Quantity(checkedMultiply(scaledUnits, factor))

    override fun compareTo(other: Quantity): Int = scaledUnits.compareTo(other.scaledUnits)

    fun format(): String = formatScaledDecimal(scaledUnits, 4, trimTrailingZeros = true)

    override fun toString(): String = format()
}
