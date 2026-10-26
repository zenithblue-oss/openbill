package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.repository.AuditRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAuditRepository(private val database: OpenBillDatabase) : AuditRepository {
    override fun observeRecent(firmId: String, limit: Int): Flow<List<AuditEvent>> =
        database.auditDao().observeRecent(firmId, limit).map { rows ->
            rows.map {
                AuditEvent(it.id, it.firmId, it.entityType, it.entityId, it.voucherId, it.action, it.detailsJson, it.actor, Instant.fromEpochMilliseconds(it.createdAt))
            }
        }

    override suspend fun record(event: AuditEvent) {
        database.auditDao().insert(
            AuditEventEntity(
                event.id, event.firmId, event.entityType, event.entityId, event.voucherId,
                event.action, event.detailsJson, event.actor, event.createdAt.toEpochMilliseconds(),
            ),
        )
    }
}
