package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate

data class TaxComponent(
    val kind: TaxKind,
    val rate: Rate,
    val amount: Money,
)
