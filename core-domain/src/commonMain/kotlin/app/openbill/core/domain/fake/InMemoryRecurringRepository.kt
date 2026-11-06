package app.openbill.core.domain.fake

import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.repository.RecurringRepository
import app.openbill.core.domain.repository.VoucherRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryRecurringRepository(private val vouchers: VoucherRepository? = null) : RecurringRepository {
    private val schedules = MutableStateFlow<List<RecurringSchedule>>(emptyList())
    private val mutex = Mutex()

    override fun observeSchedules(firmId: String): Flow<List<RecurringSchedule>> =
        schedules.map { all -> all.filter { it.firmId == firmId }.sortedBy { it.createdAt } }

    override suspend fun findSchedule(id: String): RecurringSchedule? = schedules.value.firstOrNull { it.id == id }

    override suspend fun saveSchedule(schedule: RecurringSchedule) {
        schedules.update { all -> all.filterNot { it.id == schedule.id } + schedule }
    }

    override suspend fun deleteSchedule(id: String) {
        schedules.update { all -> all.filterNot { it.id == id } }
    }

    override suspend fun claimOccurrence(scheduleId: String, index: Int, draft: Voucher?): Boolean = mutex.withLock {
        val s = schedules.value.firstOrNull { it.id == scheduleId } ?: return false
        if (s.nextIndex != index) return false
        if (draft != null) requireNotNull(vouchers) { "No voucher repository to save the draft" }.saveDraft(draft)
        schedules.update { all ->
            all.map { if (it.id == scheduleId) it.copy(nextIndex = index + 1, generatedCount = it.generatedCount + if (draft != null) 1 else 0) else it }
        }
        true
    }
}
