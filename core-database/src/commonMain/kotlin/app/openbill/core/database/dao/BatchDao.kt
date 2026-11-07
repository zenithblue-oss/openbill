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

    /** Batches of the firm's active items with stock on hand that expire on or before [through] (ISO date), earliest first. */
    @Query(
        "SELECT batches.*, SUM(stock_movements.quantity) AS onHand FROM batches " +
            "INNER JOIN items ON items.id = batches.itemId " +
            "INNER JOIN stock_movements ON stock_movements.batchId = batches.id " +
            "WHERE items.firmId = :firmId AND items.deletedAt IS NULL AND batches.deletedAt IS NULL " +
            "AND batches.expiryDate IS NOT NULL AND batches.expiryDate <= :through " +
            "GROUP BY batches.id HAVING SUM(stock_movements.quantity) > 0 " +
            "ORDER BY batches.expiryDate, batches.batchNumber, batches.id",
    )
    fun observeExpiring(firmId: String, through: String): Flow<List<BatchStockRow>>
}

data class BatchStockRow(
    @androidx.room.Embedded val batch: BatchEntity,
    val onHand: Long,
)
