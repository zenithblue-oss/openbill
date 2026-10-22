package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.ItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Insert
    suspend fun insert(item: ItemEntity)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ItemEntity?

    @Query(
        "SELECT * FROM items WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeActive(firmId: String): Flow<List<ItemEntity>>

    @Query(
        "SELECT * FROM items WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND (name LIKE '%' || :query || '%' " +
            "OR sku LIKE '%' || :query || '%' " +
            "OR hsnSac LIKE '%' || :query || '%') " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    suspend fun search(firmId: String, query: String): List<ItemEntity>

    @Query(
        "UPDATE items SET deletedAt = :deletedAt, updatedAt = :updatedAt, revision = revision + 1 " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long): Int
}
