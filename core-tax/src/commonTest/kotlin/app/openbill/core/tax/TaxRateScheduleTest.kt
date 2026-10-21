package app.openbill.core.tax

import app.openbill.core.model.Rate
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxRateScheduleTest {
    private val schedule = TaxRateSchedule(
        listOf(
            version(LocalDate(2020, 1, 1), 5, 12, 18),
            version(LocalDate(2022, 7, 1), 5, 12, 18, 28),
            version(LocalDate(2024, 1, 1), 5, 18, 28),
        ),
    )

    @Test
    fun versionOnBeforeFirstReturnsNull() {
        assertNull(schedule.versionOn(LocalDate(2019, 12, 31)))
    }

    @Test
    fun versionOnExactChangeDateReturnsNewVersion() {
        val version = schedule.versionOn(LocalDate(2022, 7, 1))

        assertEquals(LocalDate(2022, 7, 1), version?.effectiveFrom)
        assertEquals(listOf(5, 12, 18, 28), version?.rates?.map { (it.units / 10_000).toInt() })
    }

    @Test
    fun versionOnDayBeforeChangeReturnsPreviousVersion() {
        val version = schedule.versionOn(LocalDate(2022, 6, 30))

        assertEquals(LocalDate(2020, 1, 1), version?.effectiveFrom)
    }

    @Test
    fun versionOnBetweenVersionsReturnsMostRecentEarlierVersion() {
        val version = schedule.versionOn(LocalDate(2023, 6, 30))

        assertEquals(LocalDate(2022, 7, 1), version?.effectiveFrom)
    }

    @Test
    fun latestReturnsFinalVersionAndFutureDatesUseIt() {
        assertEquals(LocalDate(2024, 1, 1), schedule.latest.effectiveFrom)
        assertEquals(schedule.latest, schedule.versionOn(LocalDate(2030, 1, 1)))
    }

    @Test
    fun nextChangeAfterReturnsFollowingVersion() {
        assertEquals(
            LocalDate(2022, 7, 1),
            schedule.nextChangeAfter(LocalDate(2020, 1, 1))?.effectiveFrom,
        )
    }

    @Test
    fun nextChangeAfterSkipsVersionOnExactDate() {
        assertEquals(
            LocalDate(2024, 1, 1),
            schedule.nextChangeAfter(LocalDate(2022, 7, 1))?.effectiveFrom,
        )
    }

    @Test
    fun nextChangeAfterLatestReturnsNull() {
        assertNull(schedule.nextChangeAfter(LocalDate(2024, 1, 1)))
        assertNull(schedule.nextChangeAfter(LocalDate(2030, 1, 1)))
    }

    @Test
    fun historyIsSortedAndDefensivelyCopied() {
        val shuffled = TaxRateSchedule(
            listOf(
                version(LocalDate(2024, 1, 1), 18),
                version(LocalDate(2020, 1, 1), 12),
                version(LocalDate(2022, 7, 1), 28),
            ),
        )
        val expectedDates = listOf(
            LocalDate(2020, 1, 1),
            LocalDate(2022, 7, 1),
            LocalDate(2024, 1, 1),
        )
        assertEquals(expectedDates, shuffled.history().map { it.effectiveFrom })

        val snapshot = shuffled.history()
        @Suppress("UNCHECKED_CAST")
        (snapshot as MutableList<TaxRateVersion>).clear()

        assertEquals(expectedDates, shuffled.history().map { it.effectiveFrom })
    }

    @Test
    fun unsortedRatesAreNormalizedToAscendingOrder() {
        val version = TaxRateVersion(
            LocalDate(2020, 1, 1),
            listOf(Rate.ofPercent(18), Rate.ofPercent(5), Rate.ofPercent(12)),
        )

        assertEquals(
            listOf(Rate.ofPercent(5), Rate.ofPercent(12), Rate.ofPercent(18)),
            version.rates,
        )
    }

    @Test
    fun emptyVersionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaxRateSchedule(emptyList())
        }
    }

    @Test
    fun emptyRatesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaxRateVersion(LocalDate(2020, 1, 1), emptyList())
        }
    }

    @Test
    fun duplicateRatesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaxRateVersion(
                LocalDate(2020, 1, 1),
                listOf(Rate.ofPercent(5), Rate.ofPercent(5)),
            )
        }
    }

    @Test
    fun duplicateEffectiveDatesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaxRateSchedule(
                listOf(
                    version(LocalDate(2020, 1, 1), 5),
                    version(LocalDate(2020, 1, 1), 12),
                ),
            )
        }
    }

    @Test
    fun ratesOnBeforeFirstReturnsEmptyList() {
        assertEquals(emptyList<Rate>(), schedule.ratesOn(LocalDate(2019, 12, 31)))
    }

    @Test
    fun standardScheduleHasExpectedEffectiveDates() {
        assertEquals(
            listOf(LocalDate(2017, 7, 1), LocalDate(2025, 9, 22)),
            GstRateSchedules.standard.history().map { it.effectiveFrom },
        )
        assertEquals(LocalDate(2025, 9, 22), GstRateSchedules.standard.latest.effectiveFrom)
    }

    @Test
    fun standardScheduleHasExpectedSlabSets() {
        assertEquals(
            legacyStandardRates,
            GstRateSchedules.standard.ratesOn(LocalDate(2017, 7, 1)),
        )
        assertEquals(
            currentStandardRates,
            GstRateSchedules.standard.ratesOn(LocalDate(2025, 9, 22)),
        )
    }

    @Test
    fun standardScheduleDropsTwelveAndTwentyEightFromSeptember2025() {
        val rates = GstRateSchedules.standard.ratesOn(LocalDate(2025, 9, 22))

        assertFalse(rates.contains(Rate.ofPercent(12)))
        assertFalse(rates.contains(Rate.ofPercent(28)))
        assertTrue(rates.contains(Rate.ofBasisPoints(25)))
        assertTrue(rates.contains(Rate.ofPercent(3)))
        assertTrue(rates.contains(Rate.ofPercent(40)))
    }

    @Test
    fun standardScheduleKeepsHistoricalSlabsBeforeSeptember2025() {
        assertEquals(
            LocalDate(2017, 7, 1),
            GstRateSchedules.standard.versionOn(LocalDate(2025, 9, 21))?.effectiveFrom,
        )
        assertEquals(
            legacyStandardRates,
            GstRateSchedules.standard.ratesOn(LocalDate(2024, 1, 1)),
        )
        assertTrue(GstRateSchedules.standard.ratesOn(LocalDate(2024, 1, 1)).contains(Rate.ofPercent(12)))
        assertTrue(GstRateSchedules.standard.ratesOn(LocalDate(2024, 1, 1)).contains(Rate.ofPercent(28)))
    }

    @Test
    fun leapDayMonthAndYearBoundariesSelectCorrectVersion() {
        val boundaries = TaxRateSchedule(
            listOf(
                version(LocalDate(2024, 2, 29), 12),
                version(LocalDate(2024, 12, 31), 18),
                version(LocalDate(2025, 1, 1), 28),
            ),
        )

        assertNull(boundaries.versionOn(LocalDate(2024, 2, 28)))
        assertEquals(LocalDate(2024, 2, 29), boundaries.versionOn(LocalDate(2024, 2, 29))?.effectiveFrom)
        assertEquals(LocalDate(2024, 2, 29), boundaries.versionOn(LocalDate(2024, 3, 1))?.effectiveFrom)
        assertEquals(LocalDate(2024, 12, 31), boundaries.versionOn(LocalDate(2024, 12, 31))?.effectiveFrom)
        assertEquals(LocalDate(2025, 1, 1), boundaries.versionOn(LocalDate(2025, 1, 1))?.effectiveFrom)
        assertEquals(LocalDate(2024, 2, 29), boundaries.nextChangeAfter(LocalDate(2024, 2, 28))?.effectiveFrom)
        assertEquals(LocalDate(2025, 1, 1), boundaries.nextChangeAfter(LocalDate(2024, 12, 31))?.effectiveFrom)
    }

    private companion object {
        fun version(date: LocalDate, vararg percents: Int): TaxRateVersion =
            TaxRateVersion(date, percents.map { Rate.ofPercent(it) })

        val legacyStandardRates = listOf(
            Rate.ofPercent(0),
            Rate.ofBasisPoints(25),
            Rate.ofPercent(3),
            Rate.ofPercent(5),
            Rate.ofPercent(12),
            Rate.ofPercent(18),
            Rate.ofPercent(28),
        )

        val currentStandardRates = listOf(
            Rate.ofPercent(0),
            Rate.ofBasisPoints(25),
            Rate.ofPercent(3),
            Rate.ofPercent(5),
            Rate.ofPercent(18),
            Rate.ofPercent(40),
        )
    }
}
