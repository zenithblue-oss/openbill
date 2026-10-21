package app.openbill.core.tax

import app.openbill.core.model.Money

data class InvoiceTaxInput(
    val lines: List<TaxLineInput>,
    val invoiceDiscount: Money = Money.ZERO,
    val roundToRupee: Boolean = true,
) {
    init {
        require(invoiceDiscount >= Money.ZERO) { "invoiceDiscount cannot be negative" }
    }
}
