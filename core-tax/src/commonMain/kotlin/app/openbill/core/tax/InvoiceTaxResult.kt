package app.openbill.core.tax

import app.openbill.core.model.Money

/**
 * [totalTaxable] and [totalTax] include additional charges; [lines] alone sum to the
 * line-only values. [grandTotal] is lines plus charges plus [roundOff].
 */
data class InvoiceTaxResult(
    val lines: List<TaxLineResult>,
    val allocatedInvoiceDiscounts: List<Money>,
    val totalTaxable: Money,
    val totalTax: Money,
    val invoiceDiscount: Money,
    val roundOff: Money,
    val grandTotal: Money,
    val charges: List<ChargeTaxResult> = emptyList(),
    val summary: List<TaxSummaryRow> = emptyList(),
)
