package app.openbill.ui.invoice

import app.openbill.core.domain.model.ExportType
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
    /** Bonus units on top of [quantity]; not priced or taxed, but they move stock. */
    val freeQuantity: Quantity = Quantity.of(0),
    /** Purchase lines: GST claimable as input credit. */
    val itcEligible: Boolean = true,
    /** Order line this line fulfils, when converted from a sales/purchase order. */
    val sourceLineId: String? = null,
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
    /** Reverse charge: [totalTax] is shown but the recipient pays it, so [grandTotal] leaves it out. */
    val reverseCharge: Boolean = false,
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

    /**
     * Rupee value of a foreign-currency [amount] at [exchangeRate] rupees per unit (four
     * decimals), half-up to the paisa. Values are converted, not unit rates (CGST Rule 34).
     */
    fun toInr(amount: Money, exchangeRate: Quantity): Money = gross(exchangeRate, amount)

    /** Foreign-currency equivalent of a rupee [amount] (e.g. an item's rupee price as a starting rate). */
    fun fromInr(amount: Money, exchangeRate: Quantity): Money =
        Money.of(Rounding.multiplyDivide(amount.paise, QTY_SCALE, exchangeRate.scaledUnits, RoundingMode.HALF_UP))

    /**
     * Throws [IllegalArgumentException] for invalid lines (e.g. discount > gross). With
     * [exchangeRate] the lines and charges are in a foreign currency: each line value, discount
     * and charge is converted to rupees first, and GST is computed on the rupee values.
     */
    fun summarize(
        lines: List<DraftLine>,
        firmStateCode: String,
        placeOfSupplyStateCode: String,
        roundToRupee: Boolean,
        rounding: RoundingMode,
        charges: List<DraftCharge> = emptyList(),
        reverseCharge: Boolean = false,
        export: ExportType? = null,
        exchangeRate: Quantity? = null,
    ): TaxSummary {
        val context = TaxContext(firmStateCode, placeOfSupplyStateCode, forceInterState = export != null)
        // Exports and SEZ supplies under LUT/bond are zero-rated: no IGST is charged.
        val zeroRated = export != null && !export.withTax
        val inr = { m: Money -> if (exchangeRate == null) m else toInr(m, exchangeRate) }
        val inputs = lines.map { line ->
            TaxLineInput(
                grossAmount = inr(line.gross),
                discount = inr(line.discount),
                gstRate = if (zeroRated) Rate.ofPercent(0) else line.gstRate,
                mode = line.taxMode,
                treatment = if (zeroRated) TaxTreatment.ZERO_RATED else TaxTreatment.TAXABLE,
            )
        }
        val result = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(lines = inputs, roundToRupee = roundToRupee, charges = charges.map { it.copy(amount = inr(it.amount)).toTaxInput() }),
            context,
            rounding,
        )
        val summary = summaryOf(result.lines, inputs.fold(Money.ZERO) { a, l -> a + l.grossAmount }, inputs.fold(Money.ZERO) { a, l -> a + l.discount }, result.roundOff, result.grandTotal, context.isIntraState, heads = result.charges.flatMap { c -> c.effectivePortions.map { it.result } }.let { rs ->
            Heads(
                rs.fold(Money.ZERO) { a, r -> a + r.taxableValue },
                { k -> rs.fold(Money.ZERO) { acc, r -> r.components.filter { it.kind == k }.fold(acc) { a, c -> a + c.amount } } },
                rs.fold(Money.ZERO) { a, r -> a + r.totalTax },
            )
        })
            .copy(chargeResults = result.charges, inclusiveLines = lines.count { it.taxMode == TaxMode.INCLUSIVE })
        if (!reverseCharge) return summary
        val payable = summary.taxable + summary.chargesTaxable
        val rounded = if (roundToRupee) payable.roundToRupee(rounding) else payable
        return summary.copy(roundOff = rounded - payable, grandTotal = rounded, reverseCharge = true)
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
        reverseCharge: Boolean = false,
        forceInterState: Boolean = false,
        exchangeRate: Quantity? = null,
    ): TaxSummary {
        val context = TaxContext(firmStateCode, placeOfSupplyStateCode, forceInterState)
        val results = lines.map { line ->
            GstCalculator.calculateLine(
                TaxLineInput(
                    grossAmount = storedGross(line, exchangeRate),
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
            lines.fold(Money.ZERO) { a, l -> a + storedGross(l, exchangeRate) },
            lines.fold(Money.ZERO) { a, l -> a + l.discount },
            roundOff,
            total,
            context.isIntraState,
            storedChargeHeads(charges, context.isIntraState, rounding),
        ).copy(inclusiveLines = lines.count { it.taxMode == TaxMode.INCLUSIVE }, reverseCharge = reverseCharge)
    }

    /** Rupee line value of a stored line: converted from the foreign rate on foreign-currency documents. */
    fun storedGross(line: VoucherLine, exchangeRate: Quantity?): Money {
        val foreign = line.foreignRate
        return if (foreign != null && exchangeRate != null) toInr(gross(line.quantity, foreign), exchangeRate) else gross(line.quantity, line.rate)
    }

    /**
     * Totals of a stored foreign-currency document in its own currency, as the editor showed them
     * (no rupee rounding). Null for rupee documents. Tax in it is informational: GST is the rupee figure.
     */
    fun foreignSummary(v: app.openbill.core.domain.model.Voucher, firmStateCode: String, rounding: RoundingMode): TaxSummary? {
        if (v.currency == null) return null
        return runCatching {
            summarize(
                v.lines.map { fromVoucherLine(it, null) }, firmStateCode, v.placeOfSupplyStateCode, false, rounding,
                v.charges.sortedBy { it.sortOrder }.map(::fromVoucherCharge), v.reverseCharge, v.export?.type,
            )
        }.getOrNull()
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
    fun toVoucherCharges(charges: List<DraftCharge>, summary: TaxSummary, exchangeRate: Quantity? = null, idFor: (DraftCharge) -> String): List<VoucherCharge> =
        charges.mapIndexed { index, c ->
            VoucherCharge(
                id = idFor(c),
                name = c.name,
                amount = if (exchangeRate == null) c.amount else toInr(c.amount, exchangeRate),
                foreignAmount = c.amount.takeIf { exchangeRate != null },
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
        amount = c.foreignAmount ?: c.amount,
        basis = c.taxBasis,
        gstRate = c.taxRate,
        inclusive = c.taxMode == TaxMode.INCLUSIVE,
    )

    /** Converts editor lines into persisted voucher lines carrying computed amounts. */
    fun toVoucherLines(
        lines: List<DraftLine>,
        summary: TaxSummary,
        godownId: String? = null,
        export: ExportType? = null,
        /** Foreign-currency documents: [lines] are in that currency; rupee amounts come from [summary]. */
        exchangeRate: Quantity? = null,
        idFor: (DraftLine) -> String,
    ): List<VoucherLine> =
        lines.mapIndexed { index, line ->
            val r = summary.lineResults[index]
            // ponytail: a zero-rated line stores 0%; switching back to a taxed supply needs the rate re-entered.
            val zeroRated = export != null && !export.withTax
            val inr = { m: Money -> if (exchangeRate == null) m else toInr(m, exchangeRate) }
            VoucherLine(
                id = idFor(line),
                lineNumber = index + 1,
                itemId = line.itemId,
                description = line.description,
                hsnSac = line.hsnSac?.takeIf { it.isNotBlank() },
                quantity = line.quantity,
                // Rupee unit rate for reference; the line value is converted, see [storedGross].
                rate = inr(line.rate),
                discount = inr(line.discount),
                foreignRate = line.rate.takeIf { exchangeRate != null },
                foreignDiscount = line.discount.takeIf { exchangeRate != null },
                taxRate = if (zeroRated) Rate.ofPercent(0) else line.gstRate,
                treatment = if (zeroRated) TaxTreatment.ZERO_RATED else TaxTreatment.TAXABLE,
                freeQuantity = line.freeQuantity,
                itcEligible = line.itcEligible,
                sourceLineId = line.sourceLineId,
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
        rate = line.foreignRate ?: line.rate,
        discount = line.foreignDiscount ?: line.discount,
        gstRate = line.taxRate,
        taxMode = line.taxMode,
        batchNumber = line.batchNumber,
        expiryDate = line.expiryDate,
        serialNumbers = line.serialNumbers,
        freeQuantity = line.freeQuantity,
        itcEligible = line.itcEligible,
        sourceLineId = line.sourceLineId,
    )
}
