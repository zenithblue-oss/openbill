package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.database.PartyItemPriceEntity
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyItemPrice
import app.openbill.core.model.Money
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

    override fun observePartyPage(firmId: String, types: Set<PartyType>, query: String, limit: Int, category: String?): Flow<List<Party>> =
        parties.observePage(firmId, types.map { it.name.lowercase() }, query.trim(), limit, category)
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
            // Credit terms are a control, not contact details: only roles that manage masters set them.
            val creditChanged = party.creditLimit?.paise != existing?.creditLimit || party.creditDays != existing?.creditDays
            if (creditChanged) Access.require(Permission.MANAGE_MASTERS)
            database.audit(
                party.firmId, "party", party.id, if (existing == null) "party.created" else "party.updated",
                if (creditChanged && existing != null) AuditDetails.json(
                    "creditLimit" to party.creditLimit?.paise, "oldCreditLimit" to existing.creditLimit,
                    "creditDays" to party.creditDays, "oldCreditDays" to existing.creditDays,
                ) else null,
            )
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

    override fun observeCategories(firmId: String): Flow<List<String>> = parties.observeCategories(firmId)

    override fun observePrices(partyId: String): Flow<List<PartyItemPrice>> =
        parties.observePrices(partyId).map { rows -> rows.map { PartyItemPrice(it.partyId, it.itemId, Money.of(it.price)) } }

    override suspend fun findPrice(partyId: String, itemId: String): PartyItemPrice? =
        parties.findPrice(partyId, itemId)?.let { PartyItemPrice(it.partyId, it.itemId, Money.of(it.price)) }

    override suspend fun savePrice(firmId: String, price: PartyItemPrice) {
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write {
            val party = parties.findById(price.partyId)
            require(party != null && party.firmId == firmId) { "Unknown party" }
            val item = database.itemDao().findById(price.itemId)
            require(item != null && item.firmId == firmId) { "Unknown item" }
            val existing = parties.findPrice(price.partyId, price.itemId)
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            parties.upsertPrice(
                PartyItemPriceEntity(
                    id = existing?.id ?: app.openbill.core.model.Ulid.create(now).toString(),
                    firmId = firmId, partyId = price.partyId, itemId = price.itemId, price = price.price.paise,
                    createdAt = existing?.createdAt ?: now, updatedAt = now,
                ),
            )
            database.audit(
                firmId, "party", price.partyId, "party.price_set",
                AuditDetails.json("itemId" to price.itemId, "price" to price.price.paise, "oldPrice" to existing?.price),
            )
        }
    }

    override suspend fun deletePrice(partyId: String, itemId: String) {
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write {
            val party = parties.findById(partyId) ?: return@write
            if (parties.deletePrice(partyId, itemId) > 0) {
                database.audit(party.firmId, "party", partyId, "party.price_removed", AuditDetails.json("itemId" to itemId))
            }
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
