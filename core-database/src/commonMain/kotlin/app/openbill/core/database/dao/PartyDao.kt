package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.PartyAddressEntity
import app.openbill.core.database.PartyEntity
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
}
