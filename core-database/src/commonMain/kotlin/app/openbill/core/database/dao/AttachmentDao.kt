package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Update
    suspend fun update(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AttachmentEntity?

    @Query(
        "SELECT * FROM attachments WHERE firmId = :firmId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "AND deletedAt IS NULL ORDER BY createdAt DESC, id DESC",
    )
    fun observeForEntity(
        firmId: String,
        entityType: String,
        entityId: String,
    ): Flow<List<AttachmentEntity>>

    @Query(
        "UPDATE attachments SET deletedAt = :deletedAt, updatedAt = :updatedAt, " +
            "revision = revision + 1 WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long): Int
}
