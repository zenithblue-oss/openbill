package app.openbill.ui.invoice

import app.openbill.core.domain.model.VoucherCharge
import app.openbill.core.domain.model.VoucherChargePortion
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.core.tax.ChargeTaxInput
import app.openbill.core.tax.ChargeTaxResult
import app.openbill.core.tax.GstCalculator
import app.openbill.core.tax.InvoiceTaxCalculator
import app.openbill.core.tax.InvoiceTaxInput
import app.openbill.core.tax.TaxContext
import app.openbill.core.tax.TaxKind
import app.openbill.core.tax.TaxLineInput
import app.openbill.core.tax.TaxLineResult
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.TaxTreatment

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
    val batchNumber: String? = null,
    val expiryDate: kotlinx.datetime.LocalDate? = null,
    val serialNumbers: List<String> = emptyList(),
) {
    val gross: Money get() = InvoiceMath.gross(quantity, rate)
}

/** An additional charge as the user edits it. [amount] is tax-exclusive unless [inclusive]. */
data class DraftCharge(
    val key: String,
    val name: String,
    val amount: Money,
    val basis: ChargeTaxBasis = ChargeTaxBasis.PRINCIPAL_SUPPLY,
    /** Used only for [ChargeTaxBasis.EXPLICIT_RATE]. */
    val gstRate: Rate = Rate.ofPercent(0),
    val inclusive: Boolean = false,
) {
    val taxMode: TaxMode get() = if (inclusive && basis != ChargeTaxBasis.NON_TAXABLE) TaxMode.INCLUSIVE else TaxMode.EXCLUSIVE

    fun toTaxInput(): ChargeTaxInput = ChargeTaxInput(
        amount = amount,
        basis = basis,
        gstRate = if (basis == ChargeTaxBasis.EXPLICIT_RATE) gstRate else Rate.ofPercent(0),
        mode = taxMode,
    )
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
    /**
     * Additional charges. [taxable] and [lineResults] cover items only; the tax heads,
     * [totalTax] and [grandTotal] include charges.
     */
    val chargesTaxable: Money = Money.ZERO,
    val chargeResults: List<ChargeTaxResult> = emptyList(),
    /** Number of lines whose rate already includes GST; their gross and discount contain tax. */
    val inclusiveLines: Int = 0,
) {
    /** Label for [gross]: a plain "Items value" would not add up to the taxable value otherwise. */
    val grossLabel: String
        get() = when {
            inclusiveLines == 0 -> "Items value"
            inclusiveLines == lineResults.size -> "Items value (incl. GST)"
            else -> "Items value (some incl. GST)"
        }
}

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
        charges: List<DraftCharge> = emptyList(),
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
            InvoiceTaxInput(lines = inputs, roundToRupee = roundToRupee, charges = charges.map { it.toTaxInput() }),
            context,
            rounding,
        )
        return summaryOf(result.lines, lines.fold(Money.ZERO) { a, l -> a + l.gross }, lines.fold(Money.ZERO) { a, l -> a + l.discount }, result.roundOff, result.grandTotal, context.isIntraState, heads = result.charges.flatMap { c -> c.effectivePortions.map { it.result } }.let { rs ->
            Heads(
                rs.fold(Money.ZERO) { a, r -> a + r.taxableValue },
                { k -> rs.fold(Money.ZERO) { acc, r -> r.components.filter { it.kind == k }.fold(acc) { a, c -> a + c.amount } } },
                rs.fold(Money.ZERO) { a, r -> a + r.totalTax },
            )
        })
            .copy(chargeResults = result.charges, inclusiveLines = lines.count { it.taxMode == TaxMode.INCLUSIVE })
    }

    /** Rebuilds the per-head breakdown for a stored voucher's lines. */
    fun summarizeStored(
        lines: List<VoucherLine>,
        firmStateCode: String,
        placeOfSupplyStateCode: String,
        roundOff: Money,
        total: Money,
        rounding: RoundingMode,
        charges: List<VoucherCharge> = emptyList(),
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
            storedChargeHeads(charges, context.isIntraState, rounding),
        ).copy(inclusiveLines = lines.count { it.taxMode == TaxMode.INCLUSIVE })
    }

    private fun summaryOf(
        results: List<TaxLineResult>,
        gross: Money,
        discount: Money,
        roundOff: Money,
        grandTotal: Money,
        intra: Boolean,
        heads: Heads = Heads(),
    ): TaxSummary {
        fun sumOf(kind: TaxKind) = results.fold(Money.ZERO) { acc, r ->
            r.components.filter { it.kind == kind }.fold(acc) { a, c -> a + c.amount }
        }
        return TaxSummary(
            gross = gross,
            discount = discount,
            taxable = results.fold(Money.ZERO) { a, r -> a + r.taxableValue },
            cgst = sumOf(TaxKind.CGST) + heads.of(TaxKind.CGST),
            sgst = sumOf(TaxKind.SGST) + heads.of(TaxKind.SGST),
            igst = sumOf(TaxKind.IGST) + heads.of(TaxKind.IGST),
            cess = sumOf(TaxKind.CESS) + heads.of(TaxKind.CESS),
            totalTax = results.fold(Money.ZERO) { a, r -> a + r.totalTax } + heads.tax,
            chargesTaxable = heads.taxable,
            roundOff = roundOff,
            grandTotal = grandTotal,
            isIntraState = intra,
            lineResults = results,
        )
    }

    /** Tax heads and taxable value contributed by additional charges. */
    private class Heads(
        val taxable: Money = Money.ZERO,
        private val byKind: (TaxKind) -> Money = { Money.ZERO },
        val tax: Money = Money.ZERO,
    ) {
        fun of(kind: TaxKind): Money = byKind(kind)
    }

    /** Splits stored charge portions into heads the way the PDF does (CGST takes the odd paisa). */
    private fun storedChargeHeads(charges: List<VoucherCharge>, intra: Boolean, rounding: RoundingMode): Heads {
        var taxable = Money.ZERO
        var tax = Money.ZERO
        var cgst = Money.ZERO
        var sgst = Money.ZERO
        var igst = Money.ZERO
        var cessT = Money.ZERO
        charges.forEach { charge ->
            val portions = charge.portions.ifEmpty {
                listOf(VoucherChargePortion(charge.treatment, charge.taxRate, charge.cessRate, charge.taxableValue, charge.taxAmount, charge.total))
            }
            portions.forEach { p ->
                val cess = if (p.cessRate.units == 0L) Money.ZERO else p.cessRate.applyTo(p.taxableValue, rounding).let { if (it > p.taxAmount) p.taxAmount else it }
                val gst = p.taxAmount - cess
                val c = if (intra) Money.of((gst.paise + 1) / 2) else Money.ZERO
                taxable += p.taxableValue
                tax += p.taxAmount
                cessT += cess
                cgst += c
                sgst += if (intra) gst - c else Money.ZERO
                igst += if (intra) Money.ZERO else gst
            }
        }
        return Heads(taxable, { k -> when (k) { TaxKind.CGST -> cgst; TaxKind.SGST -> sgst; TaxKind.IGST -> igst; else -> cessT } }, tax)
    }

    /** Converts editor charges into persisted voucher charges carrying computed amounts. */
    fun toVoucherCharges(charges: List<DraftCharge>, summary: TaxSummary, idFor: (DraftCharge) -> String): List<VoucherCharge> =
        charges.mapIndexed { index, c ->
            VoucherCharge(
                id = idFor(c),
                name = c.name,
                amount = c.amount,
                taxRate = if (c.basis == ChargeTaxBasis.EXPLICIT_RATE) c.gstRate else Rate.ofPercent(0),
                sortOrder = index,
                taxBasis = c.basis,
                taxMode = c.taxMode,
                treatment = if (c.basis == ChargeTaxBasis.NON_TAXABLE) TaxTreatment.NON_GST else TaxTreatment.TAXABLE,
            ).withTaxResult(summary.chargeResults[index])
        }

    fun fromVoucherCharge(c: VoucherCharge): DraftCharge = DraftCharge(
        key = c.id,
        name = c.name,
        amount = c.amount,
        basis = c.taxBasis,
        gstRate = c.taxRate,
        inclusive = c.taxMode == TaxMode.INCLUSIVE,
    )

    /** Converts editor lines into persisted voucher lines carrying computed amounts. */
    fun toVoucherLines(
        lines: List<DraftLine>,
        summary: TaxSummary,
        godownId: String? = null,
        idFor: (DraftLine) -> String,
    ): List<VoucherLine> =
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
                godownId = godownId.takeIf { line.itemId != null },
                batchNumber = line.batchNumber,
                expiryDate = line.expiryDate,
                serialNumbers = line.serialNumbers,
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
        batchNumber = line.batchNumber,
        expiryDate = line.expiryDate,
        serialNumbers = line.serialNumbers,
    )
}
