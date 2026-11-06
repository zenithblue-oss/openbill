package app.openbill.core.domain.repository

import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import kotlinx.coroutines.flow.Flow

interface RecurringRepository {
    /** Every schedule of [firmId], oldest first. */
    fun observeSchedules(firmId: String): Flow<List<RecurringSchedule>>

    suspend fun findSchedule(id: String): RecurringSchedule?

    suspend fun saveSchedule(schedule: RecurringSchedule)

    /** Removes the schedule only; invoices it made stay. */
    suspend fun deleteSchedule(id: String)

    /**
     * Claims occurrence [index] of [scheduleId] in one transaction: when the schedule's next index
     * is still [index], saves [draft] (null skips the period) and moves on to index + 1. Returns
     * false, saving nothing, when another run already handled it, so no period is billed twice.
     */
    suspend fun claimOccurrence(scheduleId: String, index: Int, draft: Voucher?): Boolean
}
