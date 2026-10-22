package app.openbill.ui.invoice

import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.GstCalculator
import app.openbill.core.tax.InvoiceTaxCalculator
import app.openbill.core.tax.InvoiceTaxInput
import app.openbill.core.tax.TaxContext
import app.openbill.core.tax.TaxKind
import app.openbill.core.tax.TaxLineInput
import app.openbill.core.tax.TaxLineResult
import app.openbill.core.tax.TaxMode

/** A line as the user edits it, before tax is applied. */
data class DraftLine(
    val key: String,
    val itemId: String?,
    val description: String,
    val hsnSac: String?,
    val unit: String?,
    val quantity: Quantity,
    val rate: Money,
    val discount: Money,
    val gstRate: Rate,
    val taxMode: TaxMode,
    /** Set when the discount was entered as a percentage, so edits re-apply it to the new value. */
    val discountPercent: Rate? = null,
) {
    val gross: Money get() = InvoiceMath.gross(quantity, rate)
}

/** Totals per tax head, used by the editor footer, detail screen and PDF. */
data class TaxSummary(
    val gross: Money,
    val discount: Money,
    val taxable: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
    val totalTax: Money,
    val roundOff: Money,
    val grandTotal: Money,
    val isIntraState: Boolean,
    val lineResults: List<TaxLineResult>,
)

object InvoiceMath {
    private const val QTY_SCALE = 10_000L

    /** quantity x rate, rounded to paise (half-up). */
    fun gross(quantity: Quantity, rate: Money): Money =
        Money.of(Rounding.multiplyDivide(rate.paise, quantity.scaledUnits, QTY_SCALE, RoundingMode.HALF_UP))

    fun percentOf(amount: Money, rate: Rate): Money = rate.applyTo(amount, RoundingMode.HALF_UP)

    /** Throws [IllegalArgumentException] for invalid lines (e.g. discount > gross). */
    fun summarize(
        lines: List<DraftLine>,
        firmStateCode: String,
        placeOfSupplyStateCode: String,
        roundToRupee: Boolean,
        rounding: RoundingMode,
    ): TaxSummary {
        val context = TaxContext(firmStateCode, placeOfSupplyStateCode)
        val inputs = lines.map { line ->
            TaxLineInput(
                grossAmount = line.gross,
                discount = line.discount,
                gstRate = line.gstRate,
                mode = line.taxMode,
            )
        }
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(lines = inputs, roundToRupee = roundToRupee),
            context,
            rounding,
        )
        return summaryOf(result.lines, lines.fold(Money.ZERO) { a, l -> a + l.gross }, lines.fold(Money.ZERO) { a, l -> a + l.discount }, result.roundOff, result.grandTotal, context.isIntraState)
    }

    /** Rebuilds the per-head breakdown for a stored voucher's lines. */
    fun summarizeStored(
        lines: List<VoucherLine>,
        firmStateCode: String,
        placeOfSupplyStateCode: String,
        roundOff: Money,
        total: Money,
        rounding: RoundingMode,
    ): TaxSummary {
        val context = TaxContext(firmStateCode, placeOfSupplyStateCode)
        val results = lines.map { line ->
            GstCalculator.calculateLine(
                TaxLineInput(
                    grossAmount = gross(line.quantity, line.rate),
                    discount = line.discount,
                    gstRate = line.taxRate,
                    cessRate = line.cessRate,
                    mode = line.taxMode,
                    treatment = line.treatment,
                ),
                context,
                rounding,
            )
        }
        return summaryOf(
            results,
            lines.fold(Money.ZERO) { a, l -> a + gross(l.quantity, l.rate) },
            lines.fold(Money.ZERO) { a, l -> a + l.discount },
            roundOff,
            total,
            context.isIntraState,
        )
    }

    private fun summaryOf(
        results: List<TaxLineResult>,
        gross: Money,
        discount: Money,
        roundOff: Money,
        grandTotal: Money,
        intra: Boolean,
    ): TaxSummary {
        fun sumOf(kind: TaxKind) = results.fold(Money.ZERO) { acc, r ->
            r.components.filter { it.kind == kind }.fold(acc) { a, c -> a + c.amount }
        }
        return TaxSummary(
            gross = gross,
            discount = discount,
            taxable = results.fold(Money.ZERO) { a, r -> a + r.taxableValue },
            cgst = sumOf(TaxKind.CGST),
            sgst = sumOf(TaxKind.SGST),
            igst = sumOf(TaxKind.IGST),
            cess = sumOf(TaxKind.CESS),
            totalTax = results.fold(Money.ZERO) { a, r -> a + r.totalTax },
            roundOff = roundOff,
            grandTotal = grandTotal,
            isIntraState = intra,
            lineResults = results,
        )
    }

    /** Converts editor lines into persisted voucher lines carrying computed amounts. */
    fun toVoucherLines(lines: List<DraftLine>, summary: TaxSummary, idFor: (DraftLine) -> String): List<VoucherLine> =
        lines.mapIndexed { index, line ->
            val r = summary.lineResults[index]
            VoucherLine(
                id = idFor(line),
                lineNumber = index + 1,
                itemId = line.itemId,
                description = line.description,
                hsnSac = line.hsnSac?.takeIf { it.isNotBlank() },
                quantity = line.quantity,
                rate = line.rate,
                discount = line.discount,
                taxRate = line.gstRate,
                taxMode = line.taxMode,
                taxable = r.taxableValue,
                tax = r.totalTax,
                total = r.totalAmount,
            )
        }

    fun fromVoucherLine(line: VoucherLine, unit: String?): DraftLine = DraftLine(
        key = line.id,
        itemId = line.itemId,
        description = line.description,
        hsnSac = line.hsnSac,
        unit = unit,
        quantity = line.quantity,
        rate = line.rate,
        discount = line.discount,
        gstRate = line.taxRate,
        taxMode = line.taxMode,
    )
}
