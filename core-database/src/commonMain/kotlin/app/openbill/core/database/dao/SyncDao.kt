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

    /** Pairing: recording on, in [series], pulling after [cursor] (the bootstrap snapshot's seq in [epoch]). */
    @Query("UPDATE sync_state SET enabled = 1, series = :series, cursor = :cursor, epoch = :epoch WHERE id = 1")
    suspend fun pair(series: String, cursor: Long, epoch: String?)

    /** Unpairing: recording off, no series, no cursor. */
    @Query("UPDATE sync_state SET enabled = 0, series = NULL, cursor = 0, epoch = NULL WHERE id = 1")
    suspend fun unpair()

    @Query("UPDATE sync_state SET cursor = :cursor, hlc = MAX(hlc, :hlc) WHERE id = 1")
    suspend fun advance(cursor: Long, hlc: Long)

    @Query("UPDATE sync_state SET epoch = :epoch, cursor = :cursor WHERE id = 1")
    suspend fun setEpoch(epoch: String, cursor: Long)

    @Query("SELECT * FROM sync_outbox ORDER BY seq LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Long

    /** Drops ops the server has accepted, up to and including [seq]. */
    @Query("DELETE FROM sync_outbox WHERE seq <= :seq")
    suspend fun acknowledge(seq: Long): Int

    @Query("DELETE FROM sync_outbox WHERE opId IN (:opIds)")
    suspend fun remove(opIds: List<String>): Int

    /** An unsent or unpulled local op on the row: it lands after any op pulled now, so it wins. */
    @Query("SELECT EXISTS (SELECT 1 FROM sync_outbox WHERE entity = :entity AND entityId = :entityId)")
    suspend fun hasPending(entity: String, entityId: String): Boolean

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()

    /** Keeps a pulled op; one pulled again after a refill keeps its first place and gets its new [seq]. */
    @Query("INSERT INTO sync_inbox (opId, seq, op) VALUES (:opId, :seq, :op) ON CONFLICT(opId) DO UPDATE SET seq = excluded.seq")
    suspend fun retain(opId: String, seq: Long, op: String)

    /** Every retained op, in the order first pulled. */
    @Query("SELECT op FROM sync_inbox ORDER BY n")
    suspend fun retained(): List<String>

    /** Drops retained ops the server holds durably ([through] inclusive). */
    @Query("DELETE FROM sync_inbox WHERE seq <= :through")
    suspend fun pruneThrough(through: Long)

    /** The lowest retained seq of the current epoch: everything below it was durable when seen. */
    @Query("SELECT MIN(seq) FROM sync_inbox")
    suspend fun oldestRetained(): Long?

    /** After a refill: seqs belonged to the old epoch; ops wait to be pulled again. */
    @Query("UPDATE sync_inbox SET seq = NULL")
    suspend fun forgetSeqs()

    /** Ops the server rejected on a refill: its corrections are in the log instead. */
    @Query("DELETE FROM sync_inbox WHERE opId IN (:opIds)")
    suspend fun dropRetained(opIds: List<String>)

    @Query("DELETE FROM sync_inbox")
    suspend fun clearInbox()
}
