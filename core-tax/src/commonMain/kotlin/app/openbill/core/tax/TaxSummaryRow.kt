package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate

/**
 * One HSN/SAC and rate bucket of an invoice. Lines without a code use a null [code];
 * charges without a code use [ChargeTaxInput.CHARGE_SUMMARY_CODE]. Non-taxable treatments
 * get their own rows with zero tax so that taxable and non-taxable value never mix.
 */
data class TaxSummaryRow(
    val code: String?,
    val treatment: TaxTreatment,
    val gstRate: Rate,
    val cessRate: Rate,
    val taxableValue: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
) {
    val totalTax: Money
        get() = cgst + sgst + igst + cess
}
