package app.openbill.core.tax

import app.openbill.core.model.Money

data class InvoiceTaxInput(
    val lines: List<TaxLineInput>,
    val invoiceDiscount: Money = Money.ZERO,
    val roundToRupee: Boolean = true,
    /** Additional charges, applied after the invoice discount and before round-off. */
    val charges: List<ChargeTaxInput> = emptyList(),
    /** Rate policy for [ChargeTaxBasis.PRINCIPAL_SUPPLY] charges. */
    val principalRatePolicy: PrincipalRatePolicy = PrincipalRatePolicy.PROPORTIONAL,
) {
    init {
        require(invoiceDiscount >= Money.ZERO) { "invoiceDiscount cannot be negative" }
    }
}
