package app.openbill.core.domain.model

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class RecurrenceFrequency(val label: String, val unit: String) {
    DAILY("Daily", "day"),
    WEEKLY("Weekly", "week"),
    MONTHLY("Monthly", "month"),
    YEARLY("Yearly", "year"),
}

/**
 * Generates a copy of [templateVoucherId] every [interval] [frequency] units from [startDate].
 * Occurrence `n` (0-based) is dated [occurrence]; [nextIndex] is the first one not yet handled,
 * so generation is idempotent per period. The schedule ends after [endDate] or [maxCount]
 * occurrences, whichever comes first. There is no cap on how many schedules a firm keeps.
 */
data class RecurringSchedule(
    val id: String,
    val firmId: String,
    val templateVoucherId: String,
    val name: String,
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val maxCount: Int? = null,
    val nextIndex: Int = 0,
    /** Invoices made so far (periods skipped while paused are not counted). */
    val generatedCount: Int = 0,
    /** Post (number) generated invoices; false leaves them as drafts to review. */
    val autoPost: Boolean = false,
    val paused: Boolean = false,
    /** Why the last generation stopped short, e.g. a posting check failed; null when fine. */
    val lastError: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Copy from: false = the original invoice ([templateVoucherId]), true = the latest one this schedule made. */
    val copyLatest: Boolean = false,
    /** The newest invoice this schedule made; set by [app.openbill.core.domain.repository.RecurringRepository.claimOccurrence]. */
    val lastVoucherId: String? = null,
) {
    init {
        require(id.isNotBlank() && firmId.isNotBlank() && templateVoucherId.isNotBlank()) { "Schedule ids cannot be blank" }
        require(name.isNotBlank()) { "Schedule name cannot be blank" }
        require(interval in 1..999) { "Repeat every 1 to 999 ${frequency.unit}s" }
        require(endDate == null || endDate >= startDate) { "End date cannot be before the start date" }
        require(maxCount == null || maxCount in 1..9_999) { "Number of invoices must be 1 to 9999" }
        require(nextIndex >= 0 && generatedCount in 0..nextIndex) { "Schedule counters are invalid" }
    }

    /** Date of occurrence [n], counted from [startDate] so month ends do not drift (31 Jan, 28 Feb, 31 Mar). */
    fun occurrence(n: Int): LocalDate {
        val k = n * interval
        return when (frequency) {
            RecurrenceFrequency.DAILY -> startDate.plus(DatePeriod(days = k))
            RecurrenceFrequency.WEEKLY -> startDate.plus(DatePeriod(days = 7 * k))
            RecurrenceFrequency.MONTHLY -> startDate.plus(DatePeriod(months = k))
            RecurrenceFrequency.YEARLY -> startDate.plus(DatePeriod(years = k))
        }
    }

    fun withinLimits(n: Int): Boolean = (maxCount == null || n < maxCount) && (endDate == null || occurrence(n) <= endDate)

    /** Next date to generate, or null once the schedule has run its course. */
    val nextDate: LocalDate? get() = nextIndex.takeIf(::withinLimits)?.let(::occurrence)

    val finished: Boolean get() = nextDate == null

    /** Occurrence indexes due on or before [today], oldest first: the catch-up after missed days. */
    fun dueIndexes(today: LocalDate): List<Int> {
        if (paused) return emptyList()
        val out = ArrayList<Int>()
        var n = nextIndex
        // ponytail: walks one occurrence at a time; a daily schedule years behind is still only a few thousand steps.
        while (withinLimits(n) && occurrence(n) <= today) out += n++
        return out
    }

    /** First occurrence index dated on or after [today]: where a resumed schedule picks up. */
    fun firstIndexFrom(today: LocalDate): Int {
        var n = nextIndex
        while (withinLimits(n) && occurrence(n) < today) n++
        return n
    }
}
