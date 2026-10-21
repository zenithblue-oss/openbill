package app.openbill.core.tax

import app.openbill.core.model.Rate
import kotlinx.datetime.LocalDate

object GstRateSchedules {
    /**
     * Standard selectable GST slabs by effective date. These are not an HSN-level legal
     * determination; each posted document must store the rate actually used.
     */
    val standard: TaxRateSchedule = TaxRateSchedule(
        listOf(
            TaxRateVersion(
                effectiveFrom = LocalDate(2017, 7, 1),
                rates = listOf(
                    Rate.ofPercent(0),
                    Rate.ofBasisPoints(25),
                    Rate.ofPercent(3),
                    Rate.ofPercent(5),
                    Rate.ofPercent(12),
                    Rate.ofPercent(18),
                    Rate.ofPercent(28),
                ),
            ),
            TaxRateVersion(
                effectiveFrom = LocalDate(2025, 9, 22),
                rates = listOf(
                    Rate.ofPercent(0),
                    Rate.ofBasisPoints(25),
                    Rate.ofPercent(3),
                    Rate.ofPercent(5),
                    Rate.ofPercent(18),
                    Rate.ofPercent(40),
                ),
            ),
        ),
    )
}
