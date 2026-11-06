package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.repository.PartyRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomPartyRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : PartyRepository {
    private val parties get() = database.partyDao()

    override fun observeParties(firmId: String): Flow<List<Party>> = combine(
        parties.observeActive(firmId),
        parties.observeAddressesForFirm(firmId),
    ) { values, addresses ->
        val addressesByParty = addresses.groupBy { it.partyId }
        values.map { party -> party.toDomain(addressesByParty[party.id].orEmpty()) }
    }

    override fun observePartyPage(firmId: String, types: Set<PartyType>, query: String, limit: Int): Flow<List<Party>> =
        parties.observePage(firmId, types.map { it.name.lowercase() }, query.trim(), limit)
            .map { values -> values.map { it.toDomain(emptyList()) } }

    override fun observeParty(id: String): Flow<Party?> = combine(
        parties.observeById(id),
        parties.observeAddressesForParty(id),
    ) { party, addresses -> party?.toDomain(addresses) }

    override suspend fun findParty(id: String): Party? {
        val party = parties.findById(id) ?: return null
        return party.toDomain(parties.addressesFor(id))
    }

    override suspend fun saveParty(party: Party) {
        Access.require(Permission.EDIT_PARTIES)
        transactions.write {
            val existing = parties.findById(party.id)
            database.audit(party.firmId, "party", party.id, if (existing == null) "party.created" else "party.updated")
            val addressEntities = parties.addressesFor(party.id).associateBy { it.id }
            val entity = party.toEntity(existing)
            if (existing == null) {
                parties.insert(entity)
            } else {
                parties.update(entity)
            }

            parties.deleteAddressesForParty(party.id)
            party.addresses.forEach { address ->
                parties.insertAddress(
                    address.toEntity(
                        partyId = party.id,
                        party = party,
                        existing = addressEntities[address.id],
                    ),
                )
            }
        }
    }

    override suspend fun archiveParty(id: String, at: Instant) {
        Access.require(Permission.CANCEL_DELETE)
        transactions.write {
            val firmId = parties.findById(id)?.firmId
            parties.softDelete(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
            firmId?.let { database.audit(it, "party", id, "party.deleted", at = at) }
        }
    }

    override suspend fun searchParties(firmId: String, query: String): List<Party> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return parties.search(firmId, needle).map { party ->
            party.toDomain(parties.addressesFor(party.id))
        }
    }
}
