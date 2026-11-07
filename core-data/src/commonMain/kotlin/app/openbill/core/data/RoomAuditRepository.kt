package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.repository.AuditRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Appends an audit event by the signed-in user ([Access.actorId]); call inside the write it describes. */
internal suspend fun OpenBillDatabase.audit(
    firmId: String,
    type: String,
    id: String,
    action: String,
    details: String? = null,
    at: Instant = kotlin.time.Clock.System.now(),
) = auditDao().insert(
    AuditEventEntity(
        id = app.openbill.core.model.Ulid.create(at.toEpochMilliseconds()).toString(),
        firmId = firmId,
        entityType = type,
        entityId = id,
        voucherId = null,
        action = action,
        detailsJson = details,
        actor = Access.actorId,
        createdAt = at.toEpochMilliseconds(),
    ),
)

class RoomAuditRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : AuditRepository {
    override fun observeRecent(firmId: String, limit: Int): Flow<List<AuditEvent>> =
        database.auditDao().observeRecent(firmId, limit).map { rows ->
            rows.map {
                AuditEvent(it.id, it.firmId, it.entityType, it.entityId, it.voucherId, it.action, it.detailsJson, it.actor, Instant.fromEpochMilliseconds(it.createdAt))
            }
        }

    override suspend fun postedBy(firmId: String): Map<String, String> =
        database.auditDao().postedBy(firmId).associate { it.voucherId to it.actor }

    override suspend fun record(event: AuditEvent) {
        transactions.write("audit.record") {
            database.auditDao().insert(
                AuditEventEntity(
                    event.id, event.firmId, event.entityType, event.entityId, event.voucherId,
                    event.action, event.detailsJson, event.actor ?: Access.actorId, event.createdAt.toEpochMilliseconds(),
                ),
            )
        }
    }
}
