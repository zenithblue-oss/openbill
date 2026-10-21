package app.openbill.core.tax

import app.openbill.core.model.Rate
import kotlinx.datetime.LocalDate

class TaxRateSchedule(versions: List<TaxRateVersion>) {
    private val versions: List<TaxRateVersion>

    init {
        require(versions.isNotEmpty()) {
            "A tax rate schedule must contain at least one version"
        }
        require(versions.map { it.effectiveFrom }.distinct().size == versions.size) {
            "A tax rate schedule cannot contain duplicate effective dates"
        }
        this.versions = versions.sortedBy { it.effectiveFrom }
    }

    fun versionOn(date: LocalDate): TaxRateVersion? =
        versions.lastOrNull { it.effectiveFrom <= date }

    fun ratesOn(date: LocalDate): List<Rate> =
        versionOn(date)?.rates ?: emptyList()

    fun history(): List<TaxRateVersion> = versions.toList()

    fun nextChangeAfter(date: LocalDate): TaxRateVersion? =
        versions.firstOrNull { it.effectiveFrom > date }

    val latest: TaxRateVersion
        get() = versions.last()
}
