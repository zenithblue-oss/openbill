package app.openbill.core.tax

import app.openbill.core.model.LargestRemainder
import app.openbill.core.model.Money
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

        val totalTaxable = results.fold(Money.ZERO) { total, result ->
            total + result.taxableValue
        }
        val totalTax = results.fold(Money.ZERO) { total, result ->
            total + result.totalTax
        }
        val beforeRounding = results.fold(Money.ZERO) { total, result ->
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
        )
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        val result = left + right
        if (((left xor result) and (right xor result)) < 0L) {
            throw ArithmeticException("Long overflow")
        }
        return result
    }
}
