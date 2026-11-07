package app.openbill.core.sync

/**
 * The device's view of a sync server (docs/10-server.md section 6.3). `SyncServer` (JVM) runs it
 * in process for the simulation suite; [HttpSyncTransport] makes the same calls over HTTP.
 */
interface SyncTransport {
    /**
     * Ops in authoring order (whole groups). Each op is accepted (gets a server seq), a duplicate
     * of one already accepted (same seq again), or rejected; rejected entities come back as
     * [PushResponse.corrections] holding the server's rows.
     */
    suspend fun push(workspaceId: String, ops: List<SyncOperation>): PushResponse

    /** Up to [limit] accepted ops with seq greater than [after], in seq order, the caller's own included. */
    suspend fun pull(workspaceId: String, after: Long, limit: Int): PullResponse

    /** The workspace's database at a known seq, for joining devices (section 6.6). */
    suspend fun snapshot(workspaceId: String): Snapshot
}

data class PushResponse(val epoch: String, val results: List<OpResult>, val corrections: List<SyncOperation>)

data class OpResult(val opId: String, val status: Status, val seq: Long? = null, val reason: String? = null) {
    enum class Status { ACCEPTED, DUPLICATE, REJECTED }
}

/**
 * [epochStart]: the seq the current epoch began at (a restored server's last backed-up op).
 * [durable]: ops up to this seq survive a server restore, so devices may drop their retained copy.
 */
data class PullResponse(val epoch: String, val epochStart: Long, val durable: Long, val head: Long, val ops: List<Pulled>)

data class Pulled(val seq: Long, val op: SyncOperation)

class Snapshot(val seq: Long, val epoch: String, val database: ByteArray)

/** The link failed (network, server down): safe to retry, every call is idempotent. */
class SyncTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server refused this device (e.g. a copied data folder sharing another device's identity): retrying cannot help. */
class SyncRefusedException(message: String) : Exception(message)
