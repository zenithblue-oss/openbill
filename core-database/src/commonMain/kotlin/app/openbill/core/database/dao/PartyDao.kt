package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.openbill.core.database.PartyAddressEntity
import app.openbill.core.database.PartyEntity
import app.openbill.core.database.PartyItemPriceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PartyDao {
    @Insert
    suspend fun insert(party: PartyEntity)

    @Update
    suspend fun update(party: PartyEntity)

    @Query("SELECT * FROM parties WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PartyEntity?

    @Query("SELECT * FROM parties WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PartyEntity?>

    @Query(
        "SELECT * FROM parties WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeActive(firmId: String): Flow<List<PartyEntity>>

    @Query(
        "SELECT * FROM parties WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND (name LIKE '%' || :query || '%' " +
            "OR phone LIKE '%' || :query || '%' " +
            "OR gstin LIKE '%' || :query || '%') " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    suspend fun search(firmId: String, query: String): List<PartyEntity>

    /** One list page by name; rows of [types] matching [query] on name, phone or GSTIN. */
    @Query(
        "SELECT * FROM parties WHERE firmId = :firmId AND deletedAt IS NULL AND type IN (:types) " +
            "AND (:query = '' OR name LIKE '%' || :query || '%' " +
            "OR phone LIKE '%' || :query || '%' " +
            "OR gstin LIKE '%' || :query || '%') " +
            "ORDER BY name COLLATE NOCASE, id LIMIT :limit",
    )
    fun observePage(firmId: String, types: List<String>, query: String, limit: Int): Flow<List<PartyEntity>>

    @Query(
        "UPDATE parties SET deletedAt = :deletedAt, updatedAt = :updatedAt, revision = revision + 1 " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertAddress(address: PartyAddressEntity)

    @Update
    suspend fun updateAddress(address: PartyAddressEntity)

    @Query(
        "SELECT * FROM party_addresses WHERE partyId = :partyId " +
            "ORDER BY isDefault DESC, kind, line1, id",
    )
    suspend fun addressesFor(partyId: String): List<PartyAddressEntity>

    @Query(
        "SELECT * FROM party_addresses WHERE partyId = :partyId " +
            "ORDER BY isDefault DESC, kind, line1, id",
    )
    fun observeAddressesForParty(partyId: String): Flow<List<PartyAddressEntity>>

    @Query(
        "SELECT party_addresses.* FROM party_addresses " +
            "INNER JOIN parties ON parties.id = party_addresses.partyId " +
            "WHERE parties.firmId = :firmId AND parties.deletedAt IS NULL " +
            "ORDER BY party_addresses.isDefault DESC, party_addresses.kind, " +
            "party_addresses.line1, party_addresses.id",
    )
    fun observeAddressesForFirm(firmId: String): Flow<List<PartyAddressEntity>>

    @Query("DELETE FROM party_addresses WHERE partyId = :partyId")
    suspend fun deleteAddressesForParty(partyId: String): Int

    @Query("SELECT * FROM party_item_prices WHERE partyId = :partyId ORDER BY itemId")
    fun observePrices(partyId: String): Flow<List<PartyItemPriceEntity>>

    @Query("SELECT * FROM party_item_prices WHERE partyId = :partyId AND itemId = :itemId LIMIT 1")
    suspend fun findPrice(partyId: String, itemId: String): PartyItemPriceEntity?

    @Query("SELECT * FROM party_item_prices WHERE partyId = :partyId")
    suspend fun pricesFor(partyId: String): List<PartyItemPriceEntity>

    @Upsert
    suspend fun upsertPrice(price: PartyItemPriceEntity)

    @Query("DELETE FROM party_item_prices WHERE partyId = :partyId AND itemId = :itemId")
    suspend fun deletePrice(partyId: String, itemId: String): Int

    /** Distinct non-blank groups of the business's active parties, by name. */
    @Query(
        "SELECT DISTINCT category FROM parties WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND category IS NOT NULL AND category != '' ORDER BY category COLLATE NOCASE",
    )
    fun observeCategories(firmId: String): Flow<List<String>>
}
