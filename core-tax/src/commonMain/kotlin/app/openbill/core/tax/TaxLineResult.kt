package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate

/**
 * [treatment], [gstRate] and [cessRate] echo the values the result was computed with, so
 * summaries and principal-supply selection do not need the original input.
 */
data class TaxLineResult(
    val taxableValue: Money,
    val components: List<TaxComponent>,
    val totalTax: Money,
    val totalAmount: Money,
    val trace: List<String>,
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    val gstRate: Rate = Rate.ofBasisPointsHundredths(0L),
    val cessRate: Rate = Rate.ofBasisPointsHundredths(0L),
)
