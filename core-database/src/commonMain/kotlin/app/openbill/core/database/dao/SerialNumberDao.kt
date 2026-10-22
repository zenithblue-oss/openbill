package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.SerialNumberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SerialNumberDao {
    @Insert
    suspend fun insert(serialNumber: SerialNumberEntity)

    @Update
    suspend fun update(serialNumber: SerialNumberEntity)

    @Query("SELECT * FROM serial_numbers WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SerialNumberEntity?

    @Query(
        "SELECT * FROM serial_numbers WHERE itemId = :itemId AND deletedAt IS NULL " +
            "ORDER BY serialNumber COLLATE NOCASE, id",
    )
    fun observeForItem(itemId: String): Flow<List<SerialNumberEntity>>

    @Query(
        "SELECT * FROM serial_numbers WHERE itemId = :itemId AND serialNumber = :serialNumber LIMIT 1",
    )
    suspend fun findByNumber(itemId: String, serialNumber: String): SerialNumberEntity?

    @Query(
        "SELECT serial_numbers.* FROM serial_numbers " +
            "INNER JOIN items ON items.id = serial_numbers.itemId " +
            "WHERE items.firmId = :firmId AND items.deletedAt IS NULL " +
            "AND serial_numbers.deletedAt IS NULL " +
            "AND (serial_numbers.serialNumber LIKE '%' || :query || '%' " +
            "OR items.name LIKE '%' || :query || '%') " +
            "ORDER BY serial_numbers.serialNumber COLLATE NOCASE, serial_numbers.id",
    )
    suspend fun search(firmId: String, query: String): List<SerialNumberEntity>
}
