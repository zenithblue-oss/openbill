package app.openbill.core.model

@JvmInline
value class Rate private constructor(val basisPoints: Long) : Comparable<Rate> {
    companion object {
        private const val BASIS_POINTS_PER_PERCENT = 100L
        private const val BASIS_POINTS_PER_UNIT = 10_000L

        fun ofBasisPoints(basisPoints: Long): Rate {
            require(basisPoints >= 0L) { "Rate cannot be negative" }
            return Rate(basisPoints)
        }

        fun ofPercent(percent: Int): Rate = ofPercent(percent.toLong())

        fun ofPercent(percent: Long): Rate {
            require(percent >= 0L) { "Rate cannot be negative" }
            return Rate(checkedMultiply(percent, BASIS_POINTS_PER_PERCENT))
        }
    }

    override fun compareTo(other: Rate): Int = basisPoints.compareTo(other.basisPoints)

    fun applyTo(money: Money, mode: RoundingMode): Money =
        Money.of(
            Rounding.multiplyDivide(
                value = money.paise,
                multiplier = basisPoints,
                divisor = BASIS_POINTS_PER_UNIT,
                mode = mode,
            ),
        )
}
