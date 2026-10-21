package app.openbill.core.tax

import app.openbill.core.model.Money

data class TaxLineResult(
    val taxableValue: Money,
    val components: List<TaxComponent>,
    val totalTax: Money,
    val totalAmount: Money,
    val trace: List<String>,
)
