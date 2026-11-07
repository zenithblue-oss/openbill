package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.openbill.core.database.SyncOutboxEntity
import app.openbill.core.database.SyncStateEntity

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun state(): SyncStateEntity?

    /**
     * Opens a write's context for the sync triggers; the clock never goes back. Inside an outer
     * transaction that opened a group ([txId] already set) the write joins that group.
     */
    @Query("UPDATE sync_state SET txId = COALESCE(txId, :txId), txAction = :action, txUser = :user, hlc = MAX(hlc, :now) WHERE id = 1")
    suspend fun begin(txId: String, action: String?, user: String?, now: Long)

    /** Closes [begin]; the group stays open when it belongs to an outer transaction. */
    @Query("UPDATE sync_state SET txId = CASE WHEN txId = :txId THEN NULL ELSE txId END, txAction = NULL, txUser = NULL WHERE id = 1")
    suspend fun end(txId: String)

    @Query("UPDATE sync_state SET enabled = :enabled WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean)

    @Query("SELECT * FROM sync_outbox ORDER BY seq LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Long

    /** Drops ops the server has accepted, up to and including [seq]. */
    @Query("DELETE FROM sync_outbox WHERE seq <= :seq")
    suspend fun acknowledge(seq: Long): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()
}
