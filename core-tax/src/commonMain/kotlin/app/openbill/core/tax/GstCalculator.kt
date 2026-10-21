package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode

object GstCalculator {
    private const val UNITS_PER_AMOUNT = 1_000_000L

    fun calculateLine(
        input: TaxLineInput,
        context: TaxContext,
        rounding: RoundingMode,
    ): TaxLineResult {
        val net = input.grossAmount - input.discount
        if (input.treatment != TaxTreatment.TAXABLE) {
            return TaxLineResult(
                taxableValue = net,
                components = emptyList(),
                totalTax = Money.ZERO,
                totalAmount = net,
                trace = listOf(
                    "mode=${input.mode} treatment=${input.treatment}",
                    "gross=${input.grossAmount.paise} discount=${input.discount.paise} net=${net.paise}",
                    "taxable=${net.paise} nonTaxable=true",
                    "totalTax=0 total=${net.paise}",
                ),
            )
        }

        return when (input.mode) {
            TaxMode.EXCLUSIVE -> calculateExclusive(input, context, net, rounding)
            TaxMode.INCLUSIVE -> calculateInclusive(input, context, net, rounding)
        }
    }

    private fun calculateExclusive(
        input: TaxLineInput,
        context: TaxContext,
        net: Money,
        rounding: RoundingMode,
    ): TaxLineResult {
        val taxable = net
        val components = mutableListOf<TaxComponent>()
        if (input.gstRate.units > 0L) {
            if (context.isIntraState) {
                val half = input.gstRate.half()
                components += TaxComponent(TaxKind.CGST, half, half.applyTo(taxable, rounding))
                components += TaxComponent(TaxKind.SGST, half, half.applyTo(taxable, rounding))
            } else {
                components += TaxComponent(
                    TaxKind.IGST,
                    input.gstRate,
                    input.gstRate.applyTo(taxable, rounding),
                )
            }
        }
        if (input.cessRate.units > 0L) {
            components += TaxComponent(
                TaxKind.CESS,
                input.cessRate,
                input.cessRate.applyTo(taxable, rounding),
            )
        }

        val totalTax = components.fold(Money.ZERO) { total, component -> total + component.amount }
        val total = taxable + totalTax
        val trace = buildTrace(input, net, taxable, components, totalTax, total)
        return TaxLineResult(taxable, components, totalTax, total, trace)
    }

    private fun calculateInclusive(
        input: TaxLineInput,
        context: TaxContext,
        net: Money,
        rounding: RoundingMode,
    ): TaxLineResult {
        val candidates = mutableListOf<Candidate>()
        if (input.gstRate.units > 0L) {
            if (context.isIntraState) {
                val half = input.gstRate.half()
                candidates += Candidate(TaxKind.CGST, half)
                candidates += Candidate(TaxKind.SGST, half)
            } else {
                candidates += Candidate(TaxKind.IGST, input.gstRate)
            }
        }
        if (input.cessRate.units > 0L) {
            candidates += Candidate(TaxKind.CESS, input.cessRate)
        }

        val denominator = checkedAdd(
            checkedAdd(UNITS_PER_AMOUNT, input.gstRate.units),
            input.cessRate.units,
        )
        val taxable = Money.of(
            Rounding.multiplyDivide(net.paise, UNITS_PER_AMOUNT, denominator, rounding),
        )
        val totalTax = net - taxable
        val amounts = allocate(totalTax, candidates)
        val components = candidates.mapIndexed { index, candidate ->
            TaxComponent(candidate.kind, candidate.rate, amounts[index])
        }
        val trace = buildTrace(input, net, taxable, components, totalTax, net)
        return TaxLineResult(taxable, components, totalTax, net, trace)
    }

    private fun buildTrace(
        input: TaxLineInput,
        net: Money,
        taxable: Money,
        components: List<TaxComponent>,
        totalTax: Money,
        total: Money,
    ): List<String> {
        val trace = mutableListOf<String>()
        trace += "mode=${input.mode} treatment=${input.treatment}"
        trace += "gross=${input.grossAmount.paise} discount=${input.discount.paise} net=${net.paise}"
        trace += "taxable=${taxable.paise}"
        for (component in components) {
            trace += "${component.kind} rateUnits=${component.rate.units} amount=${component.amount.paise}"
        }
        trace += "totalTax=${totalTax.paise} total=${total.paise}"
        return trace
    }

    private fun allocate(totalTax: Money, candidates: List<Candidate>): List<Money> {
        if (candidates.isEmpty()) {
            require(totalTax == Money.ZERO) {
                "Cannot allocate a non-zero tax without components"
            }
            return emptyList()
        }

        val weights = LongArray(candidates.size) { candidates[it].rate.units }
        var weightSum = 0L
        for (weight in weights) {
            weightSum = checkedAdd(weightSum, weight)
        }
        require(weightSum > 0L) { "Cannot allocate tax without component weights" }

        val shares = LongArray(candidates.size)
        val remainders = LongArray(candidates.size)
        var assigned = 0L
        for (index in weights.indices) {
            val (share, remainder) = multiplyDivideFloor(
                value = totalTax.paise,
                multiplier = weights[index],
                divisor = weightSum,
            )
            shares[index] = share
            remainders[index] = remainder
            assigned = checkedAdd(assigned, share)
        }

        val leftover = totalTax.paise - assigned
        require(leftover >= 0L && leftover < candidates.size.toLong()) {
            "Invalid largest-remainder allocation"
        }
        val used = BooleanArray(candidates.size)
        repeat(leftover.toInt()) {
            var chosen = -1
            for (index in candidates.indices) {
                if (used[index]) continue
                if (chosen == -1 || remainders[index] > remainders[chosen]) {
                    chosen = index
                }
            }
            used[chosen] = true
            shares[chosen] = checkedAdd(shares[chosen], 1L)
        }

        return shares.map { Money.of(it) }
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

    private data class Candidate(val kind: TaxKind, val rate: Rate)

    private fun checkedAdd(left: Long, right: Long): Long {
        val result = left + right
        if (((left xor result) and (right xor result)) < 0L) {
            throw ArithmeticException("Long overflow")
        }
        return result
    }

    private fun checkedMultiply(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        val result = left * right
        if (result / right != left) throw ArithmeticException("Long overflow")
        return result
    }
}
