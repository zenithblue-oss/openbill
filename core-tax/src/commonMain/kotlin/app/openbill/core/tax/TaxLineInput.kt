package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate

data class TaxLineInput(
    val grossAmount: Money,
    val discount: Money = Money.ZERO,
    val gstRate: Rate,
    val cessRate: Rate = Rate.ofBasisPointsHundredths(0L),
    val mode: TaxMode,
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    /** HSN/SAC code used only for [InvoiceTaxResult.summary] grouping. */
    val hsnSac: String? = null,
) {
    init {
        require(grossAmount >= Money.ZERO) { "grossAmount cannot be negative" }
        require(discount >= Money.ZERO) { "discount cannot be negative" }
        require(discount <= grossAmount) { "discount cannot exceed grossAmount" }
        if (treatment != TaxTreatment.TAXABLE) {
            require(gstRate.units == 0L && cessRate.units == 0L) {
                "non-taxable treatments require zero GST and cess rates"
            }
        }
    }
}
