package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.openbill.core.database.AuditEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEventEntity)

    @Query(
        "SELECT * FROM audit_events WHERE firmId = :firmId " +
            "AND entityType = :entityType AND entityId = :entityId " +
            "ORDER BY createdAt DESC, id DESC",
    )
    fun observeForEntity(
        firmId: String,
        entityType: String,
        entityId: String,
    ): Flow<List<AuditEventEntity>>

    @Query(
        "SELECT * FROM audit_events WHERE firmId = :firmId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    fun observeRecent(firmId: String, limit: Int): Flow<List<AuditEventEntity>>
}
