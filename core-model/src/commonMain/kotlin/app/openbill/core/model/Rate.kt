package app.openbill.core.model

@JvmInline
value class Rate private constructor(val units: Long) : Comparable<Rate> {
    companion object {
        private const val UNITS_PER_PERCENT = 10_000L
        private const val UNITS_PER_BASIS_POINT = 100L
        private const val UNITS_PER_AMOUNT = 1_000_000L

        fun ofBasisPointsHundredths(units: Long): Rate {
            require(units >= 0L) { "Rate cannot be negative" }
            return Rate(units)
        }

        fun ofBasisPoints(basisPoints: Long): Rate {
            require(basisPoints >= 0L) { "Rate cannot be negative" }
            return Rate(checkedMultiply(basisPoints, UNITS_PER_BASIS_POINT))
        }

        fun ofPercent(percent: Int): Rate = ofPercent(percent.toLong())

        fun ofPercent(percent: Long): Rate {
            require(percent >= 0L) { "Rate cannot be negative" }
            return Rate(checkedMultiply(percent, UNITS_PER_PERCENT))
        }
    }

    fun half(): Rate {
        require(units % 2L == 0L) { "Rate cannot be split into equal halves" }
        return Rate(units / 2L)
    }

    override fun compareTo(other: Rate): Int = units.compareTo(other.units)

    fun applyTo(money: Money, mode: RoundingMode): Money =
        Money.of(
            Rounding.multiplyDivide(
                value = money.paise,
                multiplier = units,
                divisor = UNITS_PER_AMOUNT,
                mode = mode,
            ),
        )
}
