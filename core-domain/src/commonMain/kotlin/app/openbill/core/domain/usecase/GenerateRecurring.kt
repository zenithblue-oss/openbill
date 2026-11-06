package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.repository.RecurringRepository
import app.openbill.core.domain.repository.VoucherRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Makes the invoices every active schedule of a firm owes up to today, catching up periods missed
 * while the app was closed. Each period is claimed atomically ([RecurringRepository.claimOccurrence]),
 * so running this twice, or from two places at once, never bills a period twice.
 */
class GenerateRecurring(
    private val recurring: RecurringRepository,
    private val vouchers: VoucherRepository,
    private val post: suspend (voucherId: String, at: Instant) -> Unit,
) {
    /** Returns how many invoices were made. [financialYearId] maps a date to its year, or null when none covers it. */
    suspend operator fun invoke(
        firmId: String,
        today: LocalDate,
        at: Instant,
        newId: () -> String,
        financialYearId: (LocalDate) -> String?,
    ): Int {
        var made = 0
        for (s in recurring.observeSchedules(firmId).first()) {
            val due = s.dueIndexes(today)
            if (due.isEmpty()) continue
            // "Copy from latest": the newest invoice this schedule made, else (none yet, or deleted) the original.
            val latest = s.lastVoucherId?.takeIf { s.copyLatest }?.let { vouchers.findVoucher(it) }?.takeIf { it.deletedAt == null }
            val template = latest ?: vouchers.findVoucher(s.templateVoucherId)?.takeIf { it.deletedAt == null }
            if (template == null) {
                setError(s.id, "The invoice this schedule copies was deleted; delete the schedule or make a new one")
                continue
            }
            var error: String? = null
            for (n in due) {
                val date = s.occurrence(n)
                val fy = financialYearId(date)
                if (fy == null) {
                    error = "No financial year covers ${date}; add it in More to continue"
                    break
                }
                val draft = copyOf(template, newId(), date, fy, newId, at)
                if (!recurring.claimOccurrence(s.id, n, draft)) break
                made++
                if (s.autoPost) {
                    runCatching { post(draft.id, at) }.onFailure { e ->
                        error = "Invoice of $date was saved as a draft: ${e.message ?: "posting failed"}"
                    }
                }
            }
            setError(s.id, error)
        }
        return made
    }

    private suspend fun setError(id: String, error: String?) {
        val fresh = recurring.findSchedule(id) ?: return
        if (fresh.lastError != error) recurring.saveSchedule(fresh.copy(lastError = error))
    }

    companion object {
        /** A new draft of [template] dated [date]; keeps its payment terms, drops the e-way bill number. */
        fun copyOf(template: Voucher, id: String, date: LocalDate, financialYearId: String, newId: () -> String, at: Instant): Voucher {
            val terms = template.dueDate?.let { template.date.daysUntil(it) }
            return template.duplicateAsDraft(id, date, financialYearId, { newId() }, at)
                .copy(dueDate = terms?.let { date.plus(DatePeriod(days = it)) }, ewayBillNumber = null)
        }

        /** Resuming skips the periods that passed while paused, so they are not billed late. */
        fun resumed(s: RecurringSchedule, today: LocalDate, at: Instant): RecurringSchedule =
            s.copy(paused = false, nextIndex = s.firstIndexFrom(today), lastError = null, updatedAt = at)
    }
}
