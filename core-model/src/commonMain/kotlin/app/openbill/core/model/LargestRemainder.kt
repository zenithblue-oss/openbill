package app.openbill.core.model

object LargestRemainder {
    fun allocate(total: Long, weights: List<Long>): List<Long> =
        allocate(total, weights.toLongArray()).toList()

    fun allocate(total: Long, weights: LongArray): LongArray {
        require(total >= 0L) { "total cannot be negative" }
        require(weights.isNotEmpty()) { "weights cannot be empty" }

        var weightSum = 0L
        for (weight in weights) {
            require(weight >= 0L) { "weights cannot be negative" }
            weightSum = checkedAdd(weightSum, weight)
        }
        require(weightSum > 0L) { "weights must have a positive sum" }

        if (total == 0L) return LongArray(weights.size)

        val shares = LongArray(weights.size)
        val remainders = LongArray(weights.size)
        var assigned = 0L
        for (index in weights.indices) {
            val (share, remainder) = multiplyDivideFloor(
                value = total,
                multiplier = weights[index],
                divisor = weightSum,
            )
            shares[index] = share
            remainders[index] = remainder
            assigned = checkedAdd(assigned, share)
        }

        val leftover = total - assigned
        require(leftover >= 0L && leftover < weights.size.toLong()) {
            "Invalid largest-remainder allocation"
        }

        val used = BooleanArray(weights.size)
        repeat(leftover.toInt()) {
            var chosen = -1
            for (index in weights.indices) {
                if (used[index]) continue
                if (chosen == -1 || remainders[index] > remainders[chosen]) {
                    chosen = index
                }
            }
            used[chosen] = true
            shares[chosen] = checkedAdd(shares[chosen], 1L)
        }

        return shares
    }

    private fun multiplyDivideFloor(value: Long, multiplier: Long, divisor: Long): Pair<Long, Long> {
        require(value >= 0L) { "value cannot be negative" }
        require(multiplier >= 0L) { "multiplier cannot be negative" }
        require(divisor > 0L) { "divisor must be positive" }

        val base = checkedMultiply(value / divisor, multiplier)
        val reduced = value % divisor
        var quotient = 0L
        var remainder = 0L
        val divisorULong = divisor.toULong()
        for (bit in 62 downTo 0) {
            quotient = checkedMultiply(quotient, 2L)
            val doubled = remainder.toULong() * 2UL
            if (doubled >= divisorULong) {
                remainder = (doubled - divisorULong).toLong()
                quotient = checkedAdd(quotient, 1L)
            } else {
                remainder = doubled.toLong()
            }
            if ((multiplier ushr bit) and 1L != 0L) {
                val threshold = divisor - reduced
                if (remainder >= threshold) {
                    remainder -= threshold
                    quotient = checkedAdd(quotient, 1L)
                } else {
                    remainder += reduced
                }
            }
        }
        return checkedAdd(base, quotient) to remainder
    }
}
