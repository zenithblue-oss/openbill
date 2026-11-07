package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.RecurringScheduleEntity
import app.openbill.core.domain.model.RecurrenceFrequency
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.RecurringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

class RoomRecurringRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : RecurringRepository {
    private val dao get() = database.recurringDao()
    private val vouchers get() = database.voucherDao()

    override fun observeSchedules(firmId: String): Flow<List<RecurringSchedule>> =
        dao.observeByFirm(firmId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun findSchedule(id: String): RecurringSchedule? = dao.findById(id)?.toDomain()

    /** Counters never move back, so saving a stale copy (pause, error note) cannot re-bill a claimed period. */
    override suspend fun saveSchedule(schedule: RecurringSchedule) = transactions.write("recurring.save") {
        val stored = dao.findById(schedule.id)
        val merged = if (stored == null) schedule else schedule.copy(
            nextIndex = maxOf(schedule.nextIndex, stored.nextIndex),
            generatedCount = maxOf(schedule.generatedCount, stored.generatedCount),
            // Only claimOccurrence moves the latest invoice forward.
            lastVoucherId = stored.lastVoucherId ?: schedule.lastVoucherId,
        )
        dao.upsert(merged.toEntity())
    }

    override suspend fun deleteSchedule(id: String) {
        transactions.write("recurring.delete") { dao.deleteById(id) }
    }

    override suspend fun claimOccurrence(scheduleId: String, index: Int, draft: Voucher?): Boolean = transactions.write("recurring.claim") {
        app.openbill.core.domain.model.Access.require(app.openbill.core.domain.model.Permission.CREATE_VOUCHERS)
        if (draft != null) require(draft.status == VoucherStatus.DRAFT && draft.number == null) { "Recurring invoices start as drafts" }
        val claimed = dao.advance(scheduleId, index, if (draft != null) 1 else 0, draft?.createdAt?.toEpochMillis() ?: 0L, draft?.id) == 1
        if (claimed && draft != null) {
            vouchers.insert(draft.toEntity(null))
            if (draft.lines.isNotEmpty()) vouchers.insertLines(draft.lines.map { it.toEntity(draft.id, draft, null) })
            if (draft.charges.isNotEmpty()) {
                vouchers.insertCharges(draft.charges.map { it.toEntity(draft.id) })
                vouchers.insertChargePortions(draft.charges.flatMap { it.portionEntities(draft.id) })
            }
        }
        claimed
    }
}

private fun RecurringScheduleEntity.toDomain() = RecurringSchedule(
    id = id, firmId = firmId, templateVoucherId = templateVoucherId, name = name,
    frequency = decodeEnum<RecurrenceFrequency>(frequency, "recurrence frequency"), interval = repeatEvery,
    startDate = LocalDate.parse(startDate), endDate = endDate?.let(LocalDate::parse), maxCount = maxCount,
    nextIndex = nextIndex, generatedCount = generatedCount, autoPost = autoPost, paused = paused, lastError = lastError,
    createdAt = createdAt.toInstant(), updatedAt = updatedAt.toInstant(), copyLatest = copyLatest, lastVoucherId = lastVoucherId,
)

private fun RecurringSchedule.toEntity() = RecurringScheduleEntity(
    id = id, firmId = firmId, templateVoucherId = templateVoucherId, name = name, frequency = frequency.name.lowercase(),
    repeatEvery = interval, startDate = startDate.toString(), endDate = endDate?.toString(), maxCount = maxCount,
    nextIndex = nextIndex, generatedCount = generatedCount, autoPost = autoPost, paused = paused, lastError = lastError,
    createdAt = createdAt.toEpochMillis(), updatedAt = updatedAt.toEpochMillis(), copyLatest = copyLatest, lastVoucherId = lastVoucherId,
)
