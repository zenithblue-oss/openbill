package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.repository.PartyRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

    override fun observeParty(id: String): Flow<Party?> = combine(
        parties.observeById(id),
        parties.observeAddressesForParty(id),
    ) { party, addresses -> party?.toDomain(addresses) }

    override suspend fun findParty(id: String): Party? {
        val party = parties.findById(id) ?: return null
        return party.toDomain(parties.addressesFor(id))
    }

    override suspend fun saveParty(party: Party) {
        transactions.write {
            val existing = parties.findById(party.id)
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
        transactions.write {
            parties.softDelete(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
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
