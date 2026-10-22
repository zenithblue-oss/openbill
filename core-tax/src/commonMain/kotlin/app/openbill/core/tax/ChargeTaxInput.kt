package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate

/**
 * An invoice-level additional charge. [amount] is tax-exclusive for [TaxMode.EXCLUSIVE] and
 * tax-inclusive for [TaxMode.INCLUSIVE]. Invoice discount never applies to charges.
 *
 * [gstRate] and [cessRate] are used only for [ChargeTaxBasis.EXPLICIT_RATE]; the other bases
 * require them to be zero. [hsnSac] is the summary bucket code; when null the charge is
 * summarized under [CHARGE_SUMMARY_CODE].
 */
data class ChargeTaxInput(
    val amount: Money,
    val basis: ChargeTaxBasis = ChargeTaxBasis.PRINCIPAL_SUPPLY,
    val gstRate: Rate = Rate.ofBasisPointsHundredths(0L),
    val cessRate: Rate = Rate.ofBasisPointsHundredths(0L),
    val mode: TaxMode = TaxMode.EXCLUSIVE,
    val hsnSac: String? = null,
) {
    init {
        require(amount >= Money.ZERO) { "charge amount cannot be negative" }
        if (basis != ChargeTaxBasis.EXPLICIT_RATE) {
            require(gstRate.units == 0L && cessRate.units == 0L) {
                "only EXPLICIT_RATE charges may carry GST or cess rates"
            }
        }
    }

    companion object {
        /** Summary bucket for charges without their own HSN/SAC code. */
        const val CHARGE_SUMMARY_CODE: String = "CHARGES"
    }
}
