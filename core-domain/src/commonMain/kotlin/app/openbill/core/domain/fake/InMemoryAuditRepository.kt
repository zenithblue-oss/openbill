package app.openbill.core.domain.fake

import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.repository.AuditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryAuditRepository : AuditRepository {
    private val events = MutableStateFlow<List<AuditEvent>>(emptyList())

    override fun observeRecent(firmId: String, limit: Int): Flow<List<AuditEvent>> = events.map { all ->
        all.filter { it.firmId == firmId }.sortedWith(compareByDescending<AuditEvent> { it.createdAt }.thenByDescending { it.id }).take(limit)
    }

    override suspend fun record(event: AuditEvent) {
        events.update { it + event.copy(actor = event.actor ?: app.openbill.core.domain.model.Access.actorId) }
    }

    override suspend fun postedBy(firmId: String): Map<String, String> = events.value
        .filter { it.firmId == firmId && it.action == AuditRepository.POSTED_ACTION && it.voucherId != null && it.actor != null }
        .associate { it.voucherId!! to it.actor!! }
}
