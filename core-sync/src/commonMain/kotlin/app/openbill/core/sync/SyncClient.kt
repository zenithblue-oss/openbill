package app.openbill.core.sync

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local files referenced from rows (firm logo and signature, attachments) travel as content
 * references (`blob:sha256:<hex>`), never as this device's paths (docs/10-server.md 6.2.2). The
 * blob bytes themselves move in S1.
 */
interface BlobRefs {
    /** Reference for a local file; the path unchanged when it cannot be read. */
    fun refOf(path: String): String

    /** This device's path of a referenced blob, or null while it is not here. */
    fun pathOf(ref: String): String?

    companion object {
        val PATH_COLUMNS = mapOf("firms" to setOf("logoPath", "signaturePath"), "attachments" to setOf("storagePath"))

        /** No blob store: paths pass through unchanged. */
        val None = object : BlobRefs {
            override fun refOf(path: String) = path
            override fun pathOf(ref: String): String? = null
        }
    }
}

data class SyncReport(val pushed: Int, val rejected: Int, val pulled: Int, val cursor: Long)

/**
 * One device's side of sync (docs/10-server.md 6.3, 6.4). Push sends the outbox in authoring
 * order, whole groups at a time; pull applies the server's ops in server order in one
 * transaction per page with recording off (no echo) and advances the cursor in it.
 *
 * Rebase rule: a pulled op is skipped while this device still has an op for the same row in its
 * outbox (unsent, or sent but not yet pulled back). That local op lands later in server order,
 * so on every replica it overwrites the pulled one; skipping it here reaches the same row now.
 * Own ops leave the outbox when they come back in the pull, not when the push is acknowledged,
 * so the rule holds until the server's copy is applied. A rejected op leaves at once and the
 * server's rows come back as corrections. Result: once nothing is pending, every replica holds
 * exactly the server's rows (last writer in server order wins per row).
 */
class SyncClient(
    private val database: OpenBillDatabase,
    private val transport: SyncTransport,
    private val workspaceId: String,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
    private val blobs: BlobRefs = BlobRefs.None,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val pageSize: Int = 500,
) {
    private val dao = database.syncDao()

    /** Set when the server refused this device; it must be paired again (section 6.6). */
    var refused: String? = null
        private set

    /** Pull, then push and pull until the outbox is empty; transport failures retry with backoff. */
    suspend fun sync(): SyncReport {
        refused?.let { throw SyncRefusedException(it) }
        check(dao.state()?.enabled == true) { "Sync is not enabled on this device" }
        var pushed = 0
        var rejected = 0
        var pulled = retrying { pull() }
        while (true) {
            val batch = nextBatch()
            if (batch.isEmpty()) break
            val (accepted, refusedOps) = retrying { push(batch) }
            pushed += accepted
            rejected += refusedOps
            pulled += retrying { pull() }
            // No progress (the server holds the ops but the pull has not returned them yet): try again next sync.
            if (nextBatch().firstOrNull()?.opId == batch.first().opId) break
        }
        return SyncReport(pushed, rejected, pulled, dao.state()!!.cursor)
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var wait = 200L
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return block()
            } catch (e: SyncTransportException) {
                sleep(wait + random.nextLong(wait))
                wait = (wait * 2).coerceAtMost(30_000L)
            } catch (e: SyncRefusedException) {
                refused = e.message
                throw e
            }
        }
        return block()
    }

    /** The outbox head, whole groups only (a group never splits across pushes). */
    private suspend fun nextBatch(): List<SyncOperation> {
        val ops = dao.pending(pageSize + 1).map(SyncOperation::from)
        if (ops.size <= pageSize) return ops
        val last = ops[pageSize].groupId ?: return ops.take(pageSize)
        val whole = ops.take(pageSize).dropLastWhile { it.groupId == last }
        if (whole.isNotEmpty()) return whole
        return dao.pending(Int.MAX_VALUE).map(SyncOperation::from).takeWhile { it.groupId == last }
    }

    private suspend fun push(batch: List<SyncOperation>): Pair<Int, Int> {
        val response = transport.push(workspaceId, batch.map { it.outgoing() })
        val rejected = response.results.filter { it.status == OpResult.Status.REJECTED }.map { it.opId }
        if (rejected.isNotEmpty() || response.corrections.isNotEmpty()) {
            transactions.writeWith("sync.reject") { c ->
                dao.remove(rejected)
                correctLocked(c, response.corrections)
            }
        }
        return (response.results.size - rejected.size) to rejected.size
    }

    /** Applies pages until the cursor reaches the server head. */
    private suspend fun pull(): Int {
        var applied = 0
        var limit = pageSize
        while (true) {
            val state = dao.state()!!
            val page = transport.pull(workspaceId, state.cursor, limit)
            if (state.epoch != null && state.epoch != page.epoch) {
                refill(page, state.cursor)
                continue
            }
            // Only the next contiguous seqs count: a transport may repeat or reorder what it delivers.
            val fresh = page.ops.filter { it.seq > state.cursor }.distinctBy { it.seq }.sortedBy { it.seq }
            val contiguous = fresh.withIndex().takeWhile { (i, p) -> p.seq == state.cursor + 1 + i }.map { it.value }
            // Whole groups only: a page may end inside one (a posted voucher without its journal).
            // A group's ops are adjacent in the log, so only the last group can be cut.
            val last = contiguous.lastOrNull()?.op?.groupId
            val run = if (last == null || state.cursor + contiguous.size >= page.head) contiguous else contiguous.dropLastWhile { it.op.groupId == last }
            if (run.isEmpty() && contiguous.isNotEmpty()) {
                // One group fills the page: ask for more, unless the delivery was short (retry next sync).
                if (page.ops.size < limit) return applied
                limit *= 2
                continue
            }
            transactions.writeWith("sync.pull") { c ->
                if (state.epoch == null) dao.setEpoch(page.epoch, state.cursor)
                if (run.isNotEmpty()) applyLocked(c, run.map { it.op }, own = true)
                run.forEach { dao.retain(it.op.opId, it.seq, it.op.toJson()) }
                run.lastOrNull()?.let { last -> dao.advance(last.seq, run.maxOf { it.op.hlc }) }
                dao.pruneThrough(page.durable)
            }
            applied += run.size
            if (run.isEmpty() || state.cursor + run.size >= page.head) return applied
        }
    }

    /**
     * The server was restored from a backup ([PullResponse.epochStart]) and lost the ops after it.
     * Re-push every retained copy in its old order, whole groups at a time (any author's; op ids
     * make it idempotent), then pull again from the oldest point that is surely common: the last
     * durable seq this device saw, or the restore point if lower. Re-applying ops already here is
     * harmless and lands every replica on the new epoch's order, even after several restores.
     * The copies stay retained until pulled again and durable, so a second restore before then
     * still finds them. Rejected copies go and the server's corrections replace them here and in
     * the inbox (this device applied the rejected ops before). Unsent own ops follow in the next push.
     */
    private suspend fun refill(page: PullResponse, cursor: Long) {
        val common = minOf(cursor, page.epochStart, dao.oldestRetained()?.minus(1) ?: cursor)
        val responses = wholeGroups(dao.retained().map(SyncOperation::fromJson)).map { transport.push(workspaceId, it) }
        transactions.writeWith("sync.epoch") { c ->
            dao.dropRetained(responses.flatMap { r -> r.results.filter { it.status == OpResult.Status.REJECTED }.map { it.opId } })
            correctLocked(c, responses.flatMap { it.corrections })
            dao.forgetSeqs()
            dao.setEpoch(page.epoch, common)
        }
    }

    /**
     * Inside a write: applies the server's corrections and retains them like pulled ops, so they
     * survive a server restore that happens before they are pulled.
     */
    private suspend fun correctLocked(c: androidx.room.Transactor, corrections: List<SyncOperation>) {
        applyLocked(c, corrections, own = false)
        corrections.forEach { dao.retain(it.opId, it.seq, it.toJson()) }
    }

    /** Batches of about [pageSize] ops that never split a group. */
    private fun wholeGroups(ops: List<SyncOperation>): List<List<SyncOperation>> = buildList {
        var batch = ArrayList<SyncOperation>()
        for (op in ops) {
            if (batch.size >= pageSize && (op.groupId == null || op.groupId != batch.last().groupId)) {
                add(batch)
                batch = ArrayList()
            }
            batch += op
        }
        if (batch.isNotEmpty()) add(batch)
    }

    /**
     * Inside a write: applies [ops] with recording off (restored before commit). Rows with a
     * pending local op are skipped (rebase rule above). With [own], ops this device authored
     * leave the outbox first: the server's copy is the one that counts now.
     */
    private suspend fun applyLocked(c: androidx.room.Transactor, ops: List<SyncOperation>, own: Boolean) {
        val enabled = dao.state()!!.enabled
        dao.setEnabled(false)
        val rows = SyncRows(c)
        val applied = ArrayList<SyncOperation>()
        for (op in ops) {
            if (own) dao.remove(listOf(op.opId))
            if (dao.hasPending(op.entity, op.entityId)) continue
            val local = op.incoming()
            rows.apply(local)
            applied += local
        }
        SyncRules.refreshDerived(database, applied)
        dao.setEnabled(enabled)
    }

    private fun SyncOperation.outgoing(): SyncOperation = mapPaths { blobs.refOf(it) }

    private fun SyncOperation.incoming(): SyncOperation = mapPaths { if (it.startsWith("blob:")) blobs.pathOf(it) ?: it else it }

    private fun SyncOperation.mapPaths(map: (String) -> String): SyncOperation {
        val columns = BlobRefs.PATH_COLUMNS[entity] ?: return this
        if (kind != SyncOperation.Kind.UPSERT) return this
        return copy(payload = JsonObject(payload.mapValues { (k, v) -> if (k in columns && v is JsonPrimitive && v.isString) JsonPrimitive(map(v.content)) else v }))
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
