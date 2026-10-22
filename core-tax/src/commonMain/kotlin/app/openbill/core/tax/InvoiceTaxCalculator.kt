package app.openbill.core.tax

import app.openbill.core.model.LargestRemainder
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode

object InvoiceTaxCalculator {
    fun calculate(
        input: InvoiceTaxInput,
        context: TaxContext,
        rounding: RoundingMode,
    ): InvoiceTaxResult {
        val weights = LongArray(input.lines.size) { index ->
            val line = input.lines[index]
            (line.grossAmount - line.discount).paise
        }
        var weightSum = 0L
        for (weight in weights) {
            weightSum = checkedAdd(weightSum, weight)
        }

        val shares = if (input.invoiceDiscount == Money.ZERO) {
            LongArray(input.lines.size)
        } else {
            require(input.lines.isNotEmpty()) {
                "invoiceDiscount requires at least one line"
            }
            require(input.invoiceDiscount <= Money.of(weightSum)) {
                "invoiceDiscount cannot exceed the sum of line net amounts"
            }
            LargestRemainder.allocate(input.invoiceDiscount.paise, weights)
        }
        val allocatedInvoiceDiscounts = shares.map { Money.of(it) }

        val results = input.lines.mapIndexed { index, line ->
            GstCalculator.calculateLine(
                input = line.copy(discount = line.discount + allocatedInvoiceDiscounts[index]),
                context = context,
                rounding = rounding,
            )
        }

        val chargeResults = input.charges.map { charge ->
            calculateCharge(charge, results, input.principalRatePolicy, context, rounding)
        }
        val allResults = results + chargeResults.map { it.result }

        val totalTaxable = allResults.fold(Money.ZERO) { total, result ->
            total + result.taxableValue
        }
        val totalTax = allResults.fold(Money.ZERO) { total, result ->
            total + result.totalTax
        }
        val beforeRounding = allResults.fold(Money.ZERO) { total, result ->
            total + result.totalAmount
        }

        val roundOff: Money
        val grandTotal: Money
        if (input.roundToRupee) {
            val rounded = beforeRounding.roundToRupee(rounding)
            roundOff = rounded - beforeRounding
            grandTotal = rounded
        } else {
            roundOff = Money.ZERO
            grandTotal = beforeRounding
        }

        return InvoiceTaxResult(
            lines = results,
            allocatedInvoiceDiscounts = allocatedInvoiceDiscounts,
            totalTaxable = totalTaxable,
            totalTax = totalTax,
            invoiceDiscount = input.invoiceDiscount,
            roundOff = roundOff,
            grandTotal = grandTotal,
            charges = chargeResults,
            summary = summarize(input.lines, results, chargeResults),
        )
    }

    private fun calculateCharge(
        charge: ChargeTaxInput,
        lines: List<TaxLineResult>,
        policy: PrincipalRatePolicy,
        context: TaxContext,
        rounding: RoundingMode,
    ): ChargeTaxResult {
        if (charge.basis == ChargeTaxBasis.PRINCIPAL_SUPPLY && policy == PrincipalRatePolicy.PROPORTIONAL) {
            return calculateProportionalCharge(charge, lines, context, rounding)
        }
        val zero = Rate.ofBasisPointsHundredths(0L)
        var principalIndex: Int? = null
        val treatment: TaxTreatment
        val gstRate: Rate
        val cessRate: Rate
        when (charge.basis) {
            ChargeTaxBasis.NON_TAXABLE -> {
                treatment = TaxTreatment.NON_GST
                gstRate = zero
                cessRate = zero
            }

            ChargeTaxBasis.EXPLICIT_RATE -> {
                treatment = TaxTreatment.TAXABLE
                gstRate = charge.gstRate
                cessRate = charge.cessRate
            }

            ChargeTaxBasis.PRINCIPAL_SUPPLY -> {
                val index = principalLineIndex(lines, policy)
                val principal = lines[index]
                principalIndex = index
                treatment = principal.treatment
                gstRate = principal.gstRate
                cessRate = principal.cessRate
            }
        }

        val lineResult = GstCalculator.calculateLine(
            input = TaxLineInput(
                grossAmount = charge.amount,
                gstRate = gstRate,
                cessRate = cessRate,
                mode = charge.mode,
                treatment = treatment,
            ),
            context = context,
            rounding = rounding,
        )
        val chargeTrace = buildList {
            add(
                "charge basis=${charge.basis} policy=${if (principalIndex == null) "none" else policy.name} " +
                    "principalLine=${principalIndex?.let { it + 1 } ?: "none"}",
            )
            addAll(lineResult.trace)
        }
        return ChargeTaxResult(
            basis = charge.basis,
            treatment = treatment,
            gstRate = gstRate,
            cessRate = cessRate,
            principalLineIndex = principalIndex,
            summaryCode = charge.hsnSac ?: ChargeTaxInput.CHARGE_SUMMARY_CODE,
            result = lineResult.copy(trace = chargeTrace),
            portions = emptyList(),
        )
    }

    private data class RateGroup(
        val treatment: TaxTreatment,
        val gstRate: Rate,
        val cessRate: Rate,
        var weight: Long,
        val firstLine: Int,
    )

    /**
     * Splits [charge] across the rate groups of the positive-value lines by taxable value
     * (largest remainder, ties to the earlier group), then taxes each portion on its own.
     */
    private fun calculateProportionalCharge(
        charge: ChargeTaxInput,
        lines: List<TaxLineResult>,
        context: TaxContext,
        rounding: RoundingMode,
    ): ChargeTaxResult {
        val groups = LinkedHashMap<Triple<TaxTreatment, Long, Long>, RateGroup>()
        lines.forEachIndexed { index, line ->
            if (line.taxableValue <= Money.ZERO) return@forEachIndexed
            val key = Triple(line.treatment, line.gstRate.units, line.cessRate.units)
            val group = groups.getOrPut(key) {
                RateGroup(line.treatment, line.gstRate, line.cessRate, 0L, index)
            }
            group.weight = checkedAdd(group.weight, line.taxableValue.paise)
        }
        require(groups.isNotEmpty()) {
            "PRINCIPAL_SUPPLY charges require at least one line with a positive taxable value"
        }
        val ordered = groups.values.toList()
        val amounts = LargestRemainder.allocate(charge.amount.paise, ordered.map { it.weight })
        val kept = ordered.indices.filter { amounts[it] > 0L }.ifEmpty { listOf(0) }

        val portions = kept.map { i ->
            val group = ordered[i]
            val lineResult = GstCalculator.calculateLine(
                input = TaxLineInput(
                    grossAmount = Money.of(amounts[i]),
                    gstRate = group.gstRate,
                    cessRate = group.cessRate,
                    mode = charge.mode,
                    treatment = group.treatment,
                ),
                context = context,
                rounding = rounding,
            )
            ChargeTaxPortion(group.treatment, group.gstRate, group.cessRate, lineResult)
        }

        val representative = portions.maxWithOrNull(
            compareBy<ChargeTaxPortion>(
                { it.treatment == TaxTreatment.TAXABLE },
                { it.gstRate.units },
                { it.cessRate.units },
            ),
        ) ?: portions.first()

        val trace = buildList {
            add("charge basis=${charge.basis} policy=${PrincipalRatePolicy.PROPORTIONAL.name} portions=${portions.size}")
            portions.forEachIndexed { n, portion ->
                add("portion ${n + 1} of ${portions.size}")
                addAll(portion.result.trace)
            }
        }
        val components = ArrayList<TaxComponent>()
        portions.forEach { portion ->
            portion.result.components.forEach { component ->
                val at = components.indexOfFirst { it.kind == component.kind && it.rate == component.rate }
                if (at >= 0) {
                    components[at] = components[at].copy(amount = components[at].amount + component.amount)
                } else {
                    components.add(component)
                }
            }
        }
        val aggregate = TaxLineResult(
            taxableValue = portions.fold(Money.ZERO) { t, p -> t + p.result.taxableValue },
            components = components,
            totalTax = portions.fold(Money.ZERO) { t, p -> t + p.result.totalTax },
            totalAmount = portions.fold(Money.ZERO) { t, p -> t + p.result.totalAmount },
            trace = trace,
            treatment = representative.treatment,
            gstRate = representative.gstRate,
            cessRate = representative.cessRate,
        )
        return ChargeTaxResult(
            basis = charge.basis,
            treatment = representative.treatment,
            gstRate = representative.gstRate,
            cessRate = representative.cessRate,
            principalLineIndex = null,
            summaryCode = charge.hsnSac ?: ChargeTaxInput.CHARGE_SUMMARY_CODE,
            result = aggregate,
            portions = portions,
        )
    }

    private fun principalLineIndex(
        lines: List<TaxLineResult>,
        policy: PrincipalRatePolicy,
    ): Int {
        var best = -1
        for (index in lines.indices) {
            if (lines[index].taxableValue <= Money.ZERO) continue
            if (best < 0 || outranks(lines[index], lines[best], policy)) best = index
        }
        require(best >= 0) {
            "PRINCIPAL_SUPPLY charges require at least one line with a positive taxable value"
        }
        return best
    }

    /** True when [candidate] strictly outranks [current]; ties keep the earlier line. */
    private fun outranks(
        candidate: TaxLineResult,
        current: TaxLineResult,
        policy: PrincipalRatePolicy,
    ): Boolean {
        val byRate = compareBy<TaxLineResult>(
            { it.treatment == TaxTreatment.TAXABLE },
            { it.gstRate.units },
            { it.cessRate.units },
        )
        val comparator = when (policy) {
            PrincipalRatePolicy.HIGHEST_RATE, PrincipalRatePolicy.PROPORTIONAL ->
                byRate.thenBy { it.taxableValue.paise }
            PrincipalRatePolicy.LARGEST_VALUE ->
                compareBy<TaxLineResult> { it.taxableValue.paise }.then(byRate)
        }
        return comparator.compare(candidate, current) > 0
    }

    private fun summarize(
        lineInputs: List<TaxLineInput>,
        lines: List<TaxLineResult>,
        charges: List<ChargeTaxResult>,
    ): List<TaxSummaryRow> {
        val rows = LinkedHashMap<SummaryKey, TaxSummaryRow>()
        fun add(code: String?, result: TaxLineResult) {
            val key = SummaryKey(code, result.treatment, result.gstRate.units, result.cessRate.units)
            fun sum(kind: TaxKind) = result.components
                .filter { it.kind == kind }
                .fold(Money.ZERO) { total, component -> total + component.amount }
            val existing = rows[key] ?: TaxSummaryRow(
                code = code,
                treatment = result.treatment,
                gstRate = result.gstRate,
                cessRate = result.cessRate,
                taxableValue = Money.ZERO,
                cgst = Money.ZERO,
                sgst = Money.ZERO,
                igst = Money.ZERO,
                cess = Money.ZERO,
            )
            rows[key] = existing.copy(
                taxableValue = existing.taxableValue + result.taxableValue,
                cgst = existing.cgst + sum(TaxKind.CGST),
                sgst = existing.sgst + sum(TaxKind.SGST),
                igst = existing.igst + sum(TaxKind.IGST),
                cess = existing.cess + sum(TaxKind.CESS),
            )
        }
        lines.forEachIndexed { index, result -> add(lineInputs[index].hsnSac, result) }
        charges.forEach { charge ->
            charge.effectivePortions.forEach { portion -> add(charge.summaryCode, portion.result) }
        }
        return rows.values.toList()
    }

    private data class SummaryKey(
        val code: String?,
        val treatment: TaxTreatment,
        val gstUnits: Long,
        val cessUnits: Long,
    )

    private fun checkedAdd(left: Long, right: Long): Long {
        val result = left + right
        if (((left xor result) and (right xor result)) < 0L) {
            throw ArithmeticException("Long overflow")
        }
        return result
    }
}
