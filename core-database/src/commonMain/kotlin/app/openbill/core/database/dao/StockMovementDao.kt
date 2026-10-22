package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.openbill.core.database.StockMovementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockMovementDao {
    @Insert
    suspend fun insertAll(movements: List<StockMovementEntity>)

    @Query(
        "SELECT * FROM stock_movements WHERE voucherId = :voucherId " +
            "ORDER BY date, itemId, id",
    )
    suspend fun movementsForVoucher(voucherId: String): List<StockMovementEntity>

    @Query(
        "SELECT * FROM stock_movements WHERE voucherId = :voucherId " +
            "ORDER BY date, itemId, id",
    )
    fun observeMovementsForVoucher(voucherId: String): Flow<List<StockMovementEntity>>

    @Query(
        "SELECT * FROM stock_movements WHERE firmId = :firmId AND itemId = :itemId " +
            "ORDER BY date DESC, createdAt DESC, id",
    )
    suspend fun movementsForItem(firmId: String, itemId: String): List<StockMovementEntity>

    @Query(
        "SELECT COALESCE(SUM(quantity), 0) FROM stock_movements " +
            "WHERE firmId = :firmId AND itemId = :itemId AND godownId = :godownId",
    )
    suspend fun balanceForItemGodown(firmId: String, itemId: String, godownId: String): Long

    @Query(
        "SELECT COALESCE(SUM(quantity), 0) FROM stock_movements " +
            "WHERE firmId = :firmId AND itemId = :itemId AND godownId = :godownId",
    )
    fun observeBalanceForItemGodown(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Flow<Long>

    @Query(
        "DELETE FROM stock_movements WHERE voucherId = :voucherId AND EXISTS (" +
            "SELECT 1 FROM vouchers WHERE id = :voucherId AND status = 'draft' " +
            "AND postedAt IS NULL)",
    )
    suspend fun deleteForDraftVoucher(voucherId: String): Int
}
