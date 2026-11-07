package app.openbill.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v19: this install's sync identity and the context of the write transaction in progress
 * (docs/10-server.md section 6.2). One row, id 1, seeded on open with a fresh [deviceId].
 * Device-local: never synced, and wiped when a backup is restored.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    /** Ops are recorded only while on: set when a server is paired (S1), off by default. */
    val enabled: Boolean = false,
    /** Hybrid logical clock: last wall-clock millis stamped on a write; never goes back. */
    val hlc: Long = 0,
    /** Set by `DatabaseTransactionRunner` for the duration of one write; read by the sync triggers. */
    val txId: String? = null,
    val txAction: String? = null,
    val txUser: String? = null,
    /**
     * v20: this device's document number series (docs/10-server.md 6.5), e.g. `B`: new number
     * sequences get it in their prefix, so two offline devices never issue the same number. Null
     * while standalone (numbering unchanged); set when sync is enabled.
     */
    val series: String? = null,
    /** v20: last server `seq` applied here. */
    @ColumnInfo(defaultValue = "0") val cursor: Long = 0,
    /** v20: server epoch the cursor belongs to; a new epoch (server restored) re-pushes the retained tail. */
    val epoch: String? = null,
)

/**
 * v20: ops pulled from the server and applied here, kept until the server reports them durable
 * (in a server backup), so a server restored from that backup can be refilled (section 6.3).
 * [n] keeps the order they were first pulled in; [seq] is the op's place in the current server
 * epoch, null after a refill until the op is pulled again.
 */
@Entity(tableName = "sync_inbox", indices = [Index(value = ["opId"], unique = true), Index(value = ["seq"])])
data class SyncInboxEntity(
    @PrimaryKey(autoGenerate = true) val n: Long = 0,
    val opId: String,
    val seq: Long?,
    /** The op as canonical JSON ([app.openbill.core.sync.SyncOperation.toJson]). */
    val op: String,
)

/**
 * v19: outbox of operations this device authored, written by the sync triggers in the same
 * transaction as the change ([SyncTriggers]). [seq] is the per-device, never reused order.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["opId"], unique = true), Index(value = ["firmId"]), Index(value = ["entity", "entityId"])],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    /** Null only for workspace-wide rows (users). */
    val firmId: String?,
    val deviceId: String,
    val userId: String?,
    val hlc: Long,
    /** One write transaction: ops of a group are accepted or rejected together. */
    val groupId: String?,
    /** Table name. */
    val entity: String,
    /** Primary key; composite keys joined with `|`. */
    val entityId: String,
    /** `upsert` (full row) or `delete` (key only). */
    val kind: String,
    /** Domain operation of the write, e.g. `voucher.post`; null for writes outside the runner. */
    val action: String?,
    /** The row's `revision` before this change, for mutable records that have one. */
    val baseRevision: Long?,
    /** JSON object of the row's columns in name order. */
    val payload: String,
    val schemaVersion: Int,
)
