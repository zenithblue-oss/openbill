package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.openbill.core.database.RecurringScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {
    @Upsert
    suspend fun upsert(schedule: RecurringScheduleEntity)

    @Query("SELECT * FROM recurring_schedules WHERE firmId = :firmId ORDER BY createdAt, id")
    fun observeByFirm(firmId: String): Flow<List<RecurringScheduleEntity>>

    @Query("SELECT * FROM recurring_schedules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): RecurringScheduleEntity?

    @Query("DELETE FROM recurring_schedules WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** Compare-and-set on [expected]: 0 rows when another run already moved past it. */
    @Query(
        "UPDATE recurring_schedules SET nextIndex = :expected + 1, generatedCount = generatedCount + :made, " +
            "updatedAt = :at WHERE id = :id AND nextIndex = :expected",
    )
    suspend fun advance(id: String, expected: Int, made: Int, at: Long): Int
}
