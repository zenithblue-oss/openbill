package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.repository.FirmRepository
import app.openbill.core.model.Ulid
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFirmRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : FirmRepository {
    private val firms get() = database.firmDao()
    private val settings get() = database.firmSettingsDao()
    private val financialYears get() = database.financialYearDao()

    override fun observeFirms(): Flow<List<Firm>> =
        firms.observeActive().map { values -> values.map { it.toDomain() } }

    override suspend fun findFirm(id: String): Firm? = firms.findById(id)?.toDomain()

    override suspend fun saveFirm(firm: Firm) {
        transactions.write {
            val existing = firms.findById(firm.id)
            val entity = firm.toEntity(existing)
            if (existing == null) {
                firms.insert(entity)
            } else {
                firms.update(entity)
            }
        }
    }

    override suspend fun archiveFirm(id: String, at: Instant) {
        transactions.write {
            firms.softDelete(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
        }
    }

    override fun observeSettings(firmId: String): Flow<FirmSettings?> =
        settings.observeByFirm(firmId).map { it?.toDomain() }

    override suspend fun findSettings(firmId: String): FirmSettings? =
        settings.findByFirm(firmId)?.toDomain()

    override suspend fun saveSettings(settings: FirmSettings) {
        transactions.write {
            val existing = this@RoomFirmRepository.settings.findByFirm(settings.firmId)
            val now = Clock.System.now()
            this@RoomFirmRepository.settings.upsert(settings.toEntity(existing, now))
            val oldLock = existing?.lockDate
            val newLock = settings.lockDate?.toString()
            if (oldLock != newLock) {
                audit(settings.firmId, "firm", settings.firmId, "books.lock_changed", AuditDetails.json("from" to oldLock, "to" to newLock), now)
            }
        }
    }

    override fun observeFinancialYears(firmId: String): Flow<List<FinancialYear>> =
        financialYears.observeForFirm(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun findFinancialYear(id: String): FinancialYear? =
        financialYears.findById(id)?.toDomain()

    override suspend fun saveFinancialYear(financialYear: FinancialYear) {
        transactions.write {
            val existing = financialYears.findById(financialYear.id)
            val now = Clock.System.now()
            val entity = financialYear.toEntity(existing, now)
            if (existing == null) {
                financialYears.insert(entity)
            } else {
                financialYears.update(entity)
                if ((existing.closedAt == null) != (entity.closedAt == null)) {
                    val action = if (entity.closedAt != null) "financial_year.closed" else "financial_year.reopened"
                    audit(
                        financialYear.firmId, "financial_year", financialYear.id, action,
                        AuditDetails.json("startDate" to entity.startDate, "endDate" to entity.endDate), now,
                    )
                }
            }
        }
    }

    private suspend fun audit(firmId: String, type: String, id: String, action: String, details: String, at: Instant) {
        database.auditDao().insert(
            AuditEventEntity(
                id = Ulid.create(at.toEpochMilliseconds()).toString(),
                firmId = firmId,
                entityType = type,
                entityId = id,
                voucherId = null,
                action = action,
                detailsJson = details,
                actor = null,
                createdAt = at.toEpochMilliseconds(),
            ),
        )
    }
}
