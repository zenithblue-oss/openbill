package app.openbill.core.domain.repository

import app.openbill.core.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow

/** Append-only audit trail; there is no update or delete. */
interface AuditRepository {
    /** Newest first. */
    fun observeRecent(firmId: String, limit: Int): Flow<List<AuditEvent>>

    suspend fun record(event: AuditEvent)
}
