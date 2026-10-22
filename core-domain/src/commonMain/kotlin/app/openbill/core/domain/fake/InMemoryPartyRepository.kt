package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.repository.PartyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

class InMemoryPartyRepository : PartyRepository {
    private val parties = MutableStateFlow<List<Party>>(emptyList())

    override fun observeParties(firmId: String): Flow<List<Party>> =
        parties.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override fun observeParty(id: String): Flow<Party?> =
        parties.map { values -> values.firstOrNull { it.id == id } }

    override suspend fun findParty(id: String): Party? = parties.value.firstOrNull { it.id == id }

    override suspend fun saveParty(party: Party) {
        parties.update { current -> current.filterNot { it.id == party.id } + party }
    }

    override suspend fun archiveParty(id: String, at: Instant) {
        parties.update { current ->
            current.map { party ->
                if (party.id == id && party.deletedAt == null) {
                    party.copy(deletedAt = at, updatedAt = at)
                } else {
                    party
                }
            }
        }
    }

    override suspend fun searchParties(firmId: String, query: String): List<Party> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return parties.value
            .filter { party ->
                party.firmId == firmId &&
                    party.deletedAt == null &&
                    listOfNotNull(party.name, party.phone, party.gstin, party.email)
                        .any { it.lowercase().contains(needle) }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
