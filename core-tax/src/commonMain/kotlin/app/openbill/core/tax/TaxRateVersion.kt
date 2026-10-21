package app.openbill.core.tax

import app.openbill.core.model.Rate
import kotlinx.datetime.LocalDate

class TaxRateVersion(
    val effectiveFrom: LocalDate,
    rates: List<Rate>,
) {
    val rates: List<Rate>

    init {
        require(rates.isNotEmpty()) {
            "A tax rate version must contain at least one rate"
        }
        require(rates.distinct().size == rates.size) {
            "A tax rate version cannot contain duplicate rates"
        }
        this.rates = rates.sorted()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TaxRateVersion &&
                effectiveFrom == other.effectiveFrom &&
                rates == other.rates)

    override fun hashCode(): Int = 31 * effectiveFrom.hashCode() + rates.hashCode()

    override fun toString(): String =
        "TaxRateVersion(effectiveFrom=$effectiveFrom, rates=$rates)"
}
