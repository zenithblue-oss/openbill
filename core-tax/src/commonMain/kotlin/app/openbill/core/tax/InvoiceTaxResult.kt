package app.openbill.core.tax

import app.openbill.core.model.Money

data class InvoiceTaxResult(
    val lines: List<TaxLineResult>,
    val allocatedInvoiceDiscounts: List<Money>,
    val totalTaxable: Money,
    val totalTax: Money,
    val invoiceDiscount: Money,
    val roundOff: Money,
    val grandTotal: Money,
)
