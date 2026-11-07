package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyItemPrice
import app.openbill.core.domain.model.PartyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

interface PartyRepository {
    fun observeParties(firmId: String): Flow<List<Party>>

    /** The first [limit] active parties of [types] (in [category] unless null) by name whose name, phone or GSTIN contains [query]; no addresses. */
    fun observePartyPage(firmId: String, types: Set<PartyType>, query: String, limit: Int, category: String? = null): Flow<List<Party>> =
        observeParties(firmId).map { all ->
            val q = query.trim()
            all.filter {
                it.deletedAt == null && it.type in types && (category == null || it.category == category) &&
                    (q.isEmpty() || it.name.contains(q, true) || it.phone?.contains(q) == true || it.gstin?.contains(q, true) == true)
            }.take(limit)
        }

    fun observeParty(id: String): Flow<Party?>

    suspend fun findParty(id: String): Party?

    suspend fun saveParty(party: Party)

    suspend fun archiveParty(id: String, at: Instant)

    suspend fun searchParties(firmId: String, query: String): List<Party>

    /** Distinct party groups in use, by name. */
    fun observeCategories(firmId: String): Flow<List<String>> =
        observeParties(firmId).map { all -> all.mapNotNull { it.category }.distinct().sortedBy { it.lowercase() } }

    /** Special rates for one party. */
    fun observePrices(partyId: String): Flow<List<PartyItemPrice>> = flowOf(emptyList())

    suspend fun findPrice(partyId: String, itemId: String): PartyItemPrice? = null

    /** Sets a party's rate for an item, replacing any earlier one; needs MANAGE_MASTERS. [deletePrice] removes it. */
    suspend fun savePrice(firmId: String, price: PartyItemPrice): Unit = throw UnsupportedOperationException("Party prices are not stored here")

    suspend fun deletePrice(partyId: String, itemId: String): Unit = throw UnsupportedOperationException("Party prices are not stored here")
}
