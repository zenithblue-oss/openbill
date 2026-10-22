package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.FirmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FirmDao {
    @Insert
    suspend fun insert(firm: FirmEntity)

    @Update
    suspend fun update(firm: FirmEntity)

    @Query("SELECT * FROM firms WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FirmEntity?

    @Query("SELECT * FROM firms WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE, id")
    fun observeActive(): Flow<List<FirmEntity>>

    @Query(
        "UPDATE firms SET deletedAt = :deletedAt, updatedAt = :updatedAt, revision = revision + 1 " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long): Int
}
