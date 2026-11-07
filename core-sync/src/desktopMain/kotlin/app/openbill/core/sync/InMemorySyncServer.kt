package app.openbill.core.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import app.openbill.core.model.Ulid
import java.io.File
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The sync server without HTTP (docs/10-server.md section 6): per-workspace op log with a
 * server-assigned global seq, dedupe by op id, and a materialized database (same schema and code
 * as the app) that every accepted group is applied to after [SyncRules] validate it. Each
 * workspace is its own database file under [dir], so workspaces never share data.
 */
class InMemorySyncServer(private val dir: File) : SyncTransport {
    private val mutex = Mutex()
    private val workspaces = HashMap<String, Workspace>()

    /** Pairing the first device (section 6.6): its snapshot becomes the workspace at seq 0. */
    suspend fun createWorkspace(id: String, snapshot: ByteArray) = mutex.withLock {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && id !in workspaces) { "Bad or existing workspace $id" }
        val file = File(dir, "$id.db")
        dir.mkdirs()
        file.writeBytes(snapshot)
        SyncSnapshots.prepare(file)
        workspaces[id] = Workspace(file)
    }

    /** The server's own database, for inspection in tests. */
    suspend fun database(id: String): OpenBillDatabase = mutex.withLock { ws(id).db }

    suspend fun head(id: String): Long = mutex.withLock { ws(id).log.size.toLong() }

    /** Backs the workspace up: ops up to now survive a [restore], so devices may drop their copies. */
    suspend fun backup(id: String) = mutex.withLock { ws(id).backup() }

    /** Restores the last [backup]: later ops are gone, a new epoch starts and devices refill it. */
    suspend fun restore(id: String) = mutex.withLock { ws(id).restore() }

    /** Process restart: the database is closed and reopened from its file; the log is durable. */
    suspend fun restart(id: String) = mutex.withLock { ws(id).reopen() }

    suspend fun close() = mutex.withLock { workspaces.values.forEach { it.db.close() } }

    override suspend fun push(workspaceId: String, ops: List<SyncOperation>): PushResponse = mutex.withLock {
        val w = ws(workspaceId)
        // A copied data folder: two installs with one device id send different ops under one device seq.
        ops.firstOrNull { op -> w.deviceSeqs[op.deviceId to op.seq]?.let { it != op.opId } == true }?.let {
            throw SyncRefusedException("Device ${it.deviceId} reused op number ${it.seq}: this install shares another's identity (copied data folder); pair it again")
        }
        val results = ArrayList<OpResult>()
        val corrections = ArrayList<SyncOperation>()
        for (group in groups(ops)) {
            val fresh = group.filter { it.opId !in w.seqOf }
            group.filter { it.opId in w.seqOf }.forEach { results += OpResult(it.opId, OpResult.Status.DUPLICATE, w.seqOf[it.opId]) }
            if (fresh.isEmpty()) continue
            var accepted = fresh
            var reason: String? = null
            try {
                w.apply(fresh)
            } catch (e: SyncRules.AllocationsRejected) {
                // The allocations go; the rest of the group (the posted payment) stays.
                accepted = fresh.filter { it.opId !in e.opIds }
                reason = e.message
                try {
                    if (accepted.isNotEmpty()) w.apply(accepted)
                } catch (e2: Exception) {
                    accepted = emptyList()
                    reason = e2.message
                }
            } catch (e: Exception) {
                accepted = emptyList()
                reason = e.message ?: e::class.simpleName
            }
            accepted.forEach { results += OpResult(it.opId, OpResult.Status.ACCEPTED, w.append(it)) }
            val rejected = fresh - accepted.toSet()
            if (rejected.isNotEmpty()) {
                rejected.forEach { results += OpResult(it.opId, OpResult.Status.REJECTED, reason = reason) }
                corrections += w.correct(rejected, reason ?: "rejected")
            }
        }
        PushResponse(w.epoch, results, corrections)
    }

    override suspend fun pull(workspaceId: String, after: Long, limit: Int): PullResponse = mutex.withLock {
        val w = ws(workspaceId)
        require(after >= 0 && limit > 0) { "Bad pull range" }
        PullResponse(w.epoch, w.epochStart, w.durable, w.log.size.toLong(), w.log.drop(after.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(limit))
    }

    override suspend fun snapshot(workspaceId: String): Snapshot = mutex.withLock {
        val w = ws(workspaceId)
        val out = File.createTempFile("snapshot", ".db", dir).also { it.delete() }
        try {
            SyncSnapshots.vacuumInto(w.file, out)
            Snapshot(w.log.size.toLong(), w.epoch, out.readBytes())
        } finally {
            out.delete()
        }
    }

    private fun ws(id: String) = workspaces[id] ?: throw SyncRefusedException("Unknown workspace $id")

    /** Consecutive ops sharing a group id; an op without one is its own group. */
    private fun groups(ops: List<SyncOperation>): List<List<SyncOperation>> = buildList {
        var current = ArrayList<SyncOperation>()
        for (op in ops) {
            if (current.isNotEmpty() && (op.groupId == null || op.groupId != current.last().groupId)) {
                add(current)
                current = ArrayList()
            }
            current += op
        }
        if (current.isNotEmpty()) add(current)
    }

    private class Workspace(val file: File) {
        var db: OpenBillDatabase = open()
        val log = ArrayList<Pulled>()
        val seqOf = HashMap<String, Long>()
        val deviceSeqs = HashMap<Pair<String, Long>, String>()
        var epoch = newEpoch()
        var epochStart = 0L
        var durable = 0L
        private var backupSeq = -1L
        private val backupFile = File(file.path + ".backup")

        private fun open() = openBillDatabase(Room.databaseBuilder<OpenBillDatabase>(name = file.path))

        suspend fun apply(ops: List<SyncOperation>) {
            DatabaseTransactionRunner(db).writeWith("sync.server") { c ->
                val rows = SyncRows(c)
                val group = SyncRules.Group()
                for (op in ops) {
                    SyncRules.before(rows, op, group)
                    rows.apply(op)
                }
                SyncRules.after(rows, db, ops, group)
                SyncRules.refreshDerived(db, ops)
            }
        }

        fun append(op: SyncOperation): Long {
            val seq = log.size + 1L
            val stored = if (op.deviceId == SyncRows.SERVER_DEVICE) op.copy(seq = seq) else op
            log += Pulled(seq, stored)
            seqOf[op.opId] = seq
            if (op.deviceId != SyncRows.SERVER_DEVICE) deviceSeqs[op.deviceId to op.seq] = op.opId
            return seq
        }

        /**
         * The server's rows for every entity [rejected] touched, as one server-authored group in
         * the log (so a device that relayed someone else's op still corrects the author), plus a
         * `sync.rejected` audit event: the review task of section 6.4.
         */
        suspend fun correct(rejected: List<SyncOperation>, reason: String): List<SyncOperation> {
            val group = SyncRows.newGroupId()
            val ops = DatabaseTransactionRunner(db).writeWith("sync.correction") { c ->
                val rows = SyncRows(c)
                // A malformed op (unknown table, no key) has no row to correct.
                val fixes = rejected.distinctBy { it.entity to it.entityId }.mapNotNull { op ->
                    runCatching { rows.shape(op.entity); rows.key(op.entity, op.payload) }.getOrNull()?.let { rows.correction(op, group, 0) }
                }
                val first = rejected.first()
                val review = first.firmId?.takeIf { rows.read("firms", JsonObject(mapOf("id" to JsonPrimitive(it)))) != null }?.let { firmId ->
                    val now = Clock.System.now().toEpochMilliseconds()
                    val id = Ulid.create(now).toString()
                    val details = JsonObject(
                        mapOf(
                            "reason" to JsonPrimitive(reason), "action" to JsonPrimitive(first.action),
                            "device" to JsonPrimitive(first.deviceId), "ops" to JsonPrimitive(rejected.size),
                        ),
                    ).toString()
                    SyncOperation(
                        seq = 0, opId = SyncRows.randomOpId(), firmId = firmId, deviceId = SyncRows.SERVER_DEVICE, userId = null, hlc = now,
                        groupId = group, entity = "audit_events", entityId = id, kind = SyncOperation.Kind.UPSERT, action = "sync.review",
                        baseRevision = null, schemaVersion = OPENBILL_DATABASE_VERSION,
                        payload = JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(id), "firmId" to JsonPrimitive(firmId), "entityType" to JsonPrimitive(first.entity),
                                "entityId" to JsonPrimitive(first.entityId), "voucherId" to JsonPrimitive(null as String?),
                                "action" to JsonPrimitive("sync.rejected"), "detailsJson" to JsonPrimitive(details),
                                "actor" to JsonPrimitive(first.userId), "createdAt" to JsonPrimitive(now),
                            ).toSortedMap(),
                        ),
                    ).also { rows.apply(it) }
                }
                fixes + listOfNotNull(review)
            }
            return ops.map { op -> op.copy(seq = append(op)) }
        }

        fun backup() {
            backupFile.delete()
            SyncSnapshots.vacuumInto(file, backupFile)
            backupSeq = log.size.toLong()
            durable = backupSeq
        }

        fun restore() {
            check(backupSeq >= 0) { "No backup to restore" }
            db.close()
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
            backupFile.copyTo(file)
            db = open()
            val kept = log.take(backupSeq.toInt())
            log.clear()
            seqOf.clear()
            deviceSeqs.clear()
            kept.forEach { append(it.op) }
            epoch = newEpoch()
            epochStart = backupSeq
            durable = backupSeq
        }

        fun reopen() {
            db.close()
            db = open()
        }

        private fun newEpoch() = Ulid.create(Clock.System.now().toEpochMilliseconds()).toString()
    }
}

/** Snapshot files (section 6.6): the server's copy of a seeding device, and a joining device's copy of the server. */
object SyncSnapshots {
    /**
     * Makes [file] a fresh replica: no device identity (a new one is seeded on open), no unsent or
     * retained ops, no number counters (each device numbers in its own series, section 6.5).
     */
    fun prepare(file: File) {
        val c = BundledSQLiteDriver().open(file.path)
        try {
            val version = c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
            require(version == OPENBILL_DATABASE_VERSION.toLong()) { "Snapshot is schema $version, expected $OPENBILL_DATABASE_VERSION" }
            listOf("sync_state", "sync_outbox", "sync_inbox", "voucher_number_sequences").forEach { c.execSQL("DELETE FROM `$it`") }
        } finally {
            c.close()
        }
    }

    /** A consistent copy of a live database file. */
    fun vacuumInto(source: File, out: File) {
        val c = BundledSQLiteDriver().open(source.path)
        try {
            c.prepare("VACUUM INTO ?").use { it.bindText(1, out.path); it.step() }
        } finally {
            c.close()
        }
    }
}

/**
 * Content-addressed blob store in [dir]: a referenced file is copied in under its SHA-256, so a
 * row's reference resolves to the same bytes on every device that has the blob (transfer: S1).
 */
class FileBlobRefs(private val dir: File) : BlobRefs {
    override fun refOf(path: String): String {
        if (path.startsWith(PREFIX)) return path
        val file = File(path)
        if (!file.isFile) return path
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val stored = File(dir, hash)
        if (!stored.isFile) {
            dir.mkdirs()
            file.copyTo(stored, overwrite = true)
        }
        return PREFIX + hash
    }

    override fun pathOf(ref: String): String? =
        ref.removePrefix(PREFIX).takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { File(dir, it) }?.takeIf { it.isFile }?.path

    private companion object {
        const val PREFIX = "blob:sha256:"
    }
}
