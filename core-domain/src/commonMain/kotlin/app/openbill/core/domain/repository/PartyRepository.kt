package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

interface PartyRepository {
    fun observeParties(firmId: String): Flow<List<Party>>

    /** The first [limit] active parties of [types] by name whose name, phone or GSTIN contains [query]; no addresses. */
    fun observePartyPage(firmId: String, types: Set<PartyType>, query: String, limit: Int): Flow<List<Party>> =
        observeParties(firmId).map { all ->
            val q = query.trim()
            all.filter {
                it.deletedAt == null && it.type in types &&
                    (q.isEmpty() || it.name.contains(q, true) || it.phone?.contains(q) == true || it.gstin?.contains(q, true) == true)
            }.take(limit)
        }

    fun observeParty(id: String): Flow<Party?>

    suspend fun findParty(id: String): Party?

    suspend fun saveParty(party: Party)

    suspend fun archiveParty(id: String, at: Instant)

    suspend fun searchParties(firmId: String, query: String): List<Party>
}
