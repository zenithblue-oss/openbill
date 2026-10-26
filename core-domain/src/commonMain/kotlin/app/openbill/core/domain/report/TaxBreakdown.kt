package app.openbill.core.domain.report

import app.openbill.core.domain.model.Voucher
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.TaxTreatment

/** Taxable value and GST heads of a supply. */
data class TaxAmounts(
    val taxable: Money = Money.ZERO,
    val igst: Money = Money.ZERO,
    val cgst: Money = Money.ZERO,
    val sgst: Money = Money.ZERO,
    val cess: Money = Money.ZERO,
) {
    val tax: Money get() = igst + cgst + sgst + cess

    operator fun plus(o: TaxAmounts) = TaxAmounts(taxable + o.taxable, igst + o.igst, cgst + o.cgst, sgst + o.sgst, cess + o.cess)

    operator fun unaryMinus() = TaxAmounts(-taxable, -igst, -cgst, -sgst, -cess)

    fun negateIf(negative: Boolean) = if (negative) -this else this

    companion object {
        fun sum(list: Iterable<TaxAmounts>) = list.fold(TaxAmounts()) { a, b -> a + b }
    }
}

/** One taxable row of a voucher (a line or a charge portion) with its GST heads. */
data class TaxRow(
    val hsnSac: String?,
    val description: String,
    val itemId: String?,
    val quantity: Quantity,
    val treatment: TaxTreatment,
    val rate: Rate,
    val amounts: TaxAmounts,
)

/**
 * Splits each stored line and charge portion into GST heads. The stored tax is the source of
 * truth: cess is the cess rate on the taxable value (capped at the stored tax), the rest is
 * GST; intra-state GST is split CGST/SGST with any odd paisa on CGST (the tax engine's
 * tie-break), inter-state GST is IGST. A voucher without lines or charges (an expense) yields
 * one row from its header. Rows always sum to the stored taxable value and tax.
 */
fun Voucher.taxRows(firmStateCode: String, rounding: RoundingMode = RoundingMode.HALF_UP): List<TaxRow> {
    val intra = firmStateCode == placeOfSupplyStateCode
    fun split(taxable: Money, tax: Money, cessRate: Rate): TaxAmounts {
        val cess = if (cessRate.units == 0L) Money.ZERO else minOf(cessRate.applyTo(taxable, rounding), tax)
        val gst = tax - cess
        return if (intra) {
            val c = Money.of((gst.paise + 1) / 2)
            TaxAmounts(taxable, cgst = c, sgst = gst - c, cess = cess)
        } else {
            TaxAmounts(taxable, igst = gst, cess = cess)
        }
    }
    val rows = ArrayList<TaxRow>()
    lines.sortedBy { it.lineNumber }.forEach { l ->
        rows += TaxRow(l.hsnSac, l.description, l.itemId, l.quantity, l.treatment, l.taxRate, split(l.taxable, l.tax, l.cessRate))
    }
    charges.sortedBy { it.sortOrder }.forEach { c ->
        if (c.portions.isEmpty()) {
            rows += TaxRow(c.hsnSac, c.name, null, Quantity.of(0), c.treatment, c.taxRate, split(c.taxableValue, c.taxAmount, c.cessRate))
        } else {
            c.portions.forEach { p ->
                rows += TaxRow(c.hsnSac, c.name, null, Quantity.of(0), p.treatment, p.taxRate, split(p.taxableValue, p.taxAmount, p.cessRate))
            }
        }
    }
    if (rows.isEmpty() && (taxable != Money.ZERO || tax != Money.ZERO)) {
        rows += TaxRow(null, notes ?: "", null, Quantity.of(0), TaxTreatment.TAXABLE, Rate.ofBasisPoints(0), split(taxable, tax, Rate.ofBasisPoints(0)))
    }
    return rows
}

/** GST rate as a plain percent number: 18, 0.25, 12.5. */
fun Rate.percentText(): String {
    val whole = units / 10_000
    val frac = (units % 10_000).toString().padStart(4, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
}
