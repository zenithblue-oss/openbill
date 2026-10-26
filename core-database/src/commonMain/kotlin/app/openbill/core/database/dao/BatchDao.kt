package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.BatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Insert
    suspend fun insert(batch: BatchEntity)

    @Update
    suspend fun update(batch: BatchEntity)

    @Query(
        "SELECT * FROM batches WHERE itemId = :itemId AND godownId = :godownId " +
            "AND batchNumber = :batchNumber LIMIT 1",
    )
    suspend fun findByNumber(itemId: String, godownId: String, batchNumber: String): BatchEntity?

    @Query("SELECT * FROM batches WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BatchEntity?

    @Query(
        "SELECT * FROM batches WHERE itemId = :itemId AND deletedAt IS NULL " +
            "ORDER BY expiryDate, batchNumber, id",
    )
    fun observeForItem(itemId: String): Flow<List<BatchEntity>>

    @Query(
        "SELECT batches.* FROM batches " +
            "INNER JOIN items ON items.id = batches.itemId " +
            "WHERE items.firmId = :firmId AND items.deletedAt IS NULL " +
            "AND batches.deletedAt IS NULL " +
            "AND (batches.batchNumber LIKE '%' || :query || '%' " +
            "OR items.name LIKE '%' || :query || '%') " +
            "ORDER BY batches.expiryDate, batches.batchNumber, batches.id",
    )
    suspend fun search(firmId: String, query: String): List<BatchEntity>
}
