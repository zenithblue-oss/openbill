package app.openbill.core.tax

import app.openbill.core.model.LargestRemainder
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
        val result = if (input.treatment != TaxTreatment.TAXABLE) {
            TaxLineResult(
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
        } else {
            when (input.mode) {
                TaxMode.EXCLUSIVE -> calculateExclusive(input, context, net, rounding)
                TaxMode.INCLUSIVE -> calculateInclusive(input, context, net, rounding)
            }
        }
        return result.copy(
            treatment = input.treatment,
            gstRate = input.gstRate,
            cessRate = input.cessRate,
        )
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
        val amounts = if (candidates.isEmpty()) {
            require(totalTax == Money.ZERO) {
                "Cannot allocate a non-zero tax without components"
            }
            emptyList()
        } else {
            val weights = LongArray(candidates.size) { candidates[it].rate.units }
            LargestRemainder.allocate(totalTax.paise, weights).map { Money.of(it) }
        }
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

    private data class Candidate(val kind: TaxKind, val rate: Rate)

    private fun checkedAdd(left: Long, right: Long): Long {
        val result = left + right
        if (((left xor result) and (right xor result)) < 0L) {
            throw ArithmeticException("Long overflow")
        }
        return result
    }
}
