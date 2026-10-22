package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Party
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface PartyRepository {
    fun observeParties(firmId: String): Flow<List<Party>>

    fun observeParty(id: String): Flow<Party?>

    suspend fun findParty(id: String): Party?

    suspend fun saveParty(party: Party)

    suspend fun archiveParty(id: String, at: Instant)

    suspend fun searchParties(firmId: String, query: String): List<Party>
}
