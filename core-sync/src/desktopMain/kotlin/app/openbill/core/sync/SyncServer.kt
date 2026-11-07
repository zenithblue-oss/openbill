package app.openbill.core.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import app.openbill.core.model.Ulid
import androidx.room.Transactor
import java.io.File
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The sync server core, without HTTP (docs/10-server.md section 6): per-workspace op log with a
 * server-assigned global seq, dedupe by op id, and a materialized database (same schema and code
 * as the app) that every accepted group is applied to after [SyncRules] validate it. Storage of
 * the log is a [SyncLog]; each workspace lives in its own directory under [dir]
 * (`<id>/projection.sqlite`, `<id>/oplog.sqlite`, section 3), so workspaces never share data.
 *
 * Crash safety: an accepted group is appended to the log (durable) inside the projection's
 * transaction, which also records the applied seq in `sync_state.cursor`. If the projection
 * commit is lost, opening the workspace replays the log tail into it.
 */
class SyncServer(
    private val dir: File,
    private val openLog: (File) -> SyncLog = { SqliteSyncLog(it) },
) : SyncTransport {
    private val lock = Mutex()
    private val workspaces = HashMap<String, Workspace>()

    /** Seeding (section 6.6): the first device's snapshot becomes the workspace at seq 0. */
    suspend fun createWorkspace(id: String, snapshot: ByteArray) = lock.withLock {
        require(ID.matches(id)) { "Bad workspace id" }
        val home = File(dir, id)
        val projection = File(home, PROJECTION)
        require(!projection.exists() && !File(home, OPLOG).exists()) { "Workspace $id already has data" }
        home.mkdirs()
        val seed = File(home, "seed.tmp")
        try {
            seed.writeBytes(snapshot)
            SyncSnapshots.prepare(seed)
            check(seed.renameTo(projection)) { "Could not store the workspace database" }
        } finally {
            seed.delete()
        }
        workspaces[id] = Workspace(home, openLog(File(home, OPLOG))).also { it.open() }
    }

    /** True once the workspace has been seeded. */
    fun exists(id: String): Boolean = ID.matches(id) && File(dir, "$id/$PROJECTION").isFile

    /** The server's own database, for inspection in tests. */
    suspend fun database(id: String): OpenBillDatabase = ws(id).db

    suspend fun head(id: String): Long = ws(id).let { w -> w.mutex.withLock { w.log.head() } }

    /** Backs the workspace up: ops up to now survive a [restore], so devices may drop their copies. */
    suspend fun backup(id: String) = ws(id).let { w -> w.mutex.withLock { w.backup() } }

    /** Restores the last [backup]: later ops are gone, a new epoch starts and devices refill it. */
    suspend fun restore(id: String) = ws(id).let { w -> w.mutex.withLock { w.restore() } }

    /** Process restart: the database and the log are closed and reopened from their files. */
    suspend fun restart(id: String) = ws(id).let { w -> w.mutex.withLock { w.reopen() } }

    suspend fun close() = lock.withLock {
        workspaces.values.forEach { w -> w.mutex.withLock { w.close() } }
        workspaces.clear()
    }

    override suspend fun push(workspaceId: String, ops: List<SyncOperation>): PushResponse {
        val w = ws(workspaceId)
        // Server-authored ops (corrections) come back from devices when they refill a restored server.
        // ponytail: not authenticated as the server's own; sign them when non-owner roles push (S2).
        return w.mutex.withLock {
            // A copied data folder: two installs with one device id send different ops under one device seq.
            ops.firstOrNull { op -> w.log.opIdAt(op.deviceId, op.seq)?.let { it != op.opId } == true }?.let {
                throw SyncRefusedException("Device ${it.deviceId} reused op number ${it.seq}: this install shares another's identity (copied data folder); pair it again")
            }
            val results = ArrayList<OpResult>()
            val corrections = ArrayList<SyncOperation>()
            for (group in groups(ops)) {
                val fresh = ArrayList<SyncOperation>()
                for (op in group.distinctBy { it.opId }) {
                    val seq = w.log.seqOf(op.opId)
                    if (seq == null) fresh += op else results += OpResult(op.opId, OpResult.Status.DUPLICATE, seq)
                }
                if (fresh.isEmpty()) continue
                var accepted: List<SyncOperation> = fresh
                var reason: String? = null
                var stored: List<Pulled> = emptyList()
                try {
                    stored = w.apply(fresh)
                } catch (e: SyncRules.AllocationsRejected) {
                    // The allocations go; the rest of the group (the posted payment) stays.
                    accepted = fresh.filter { it.opId !in e.opIds }
                    reason = e.message
                    try {
                        if (accepted.isNotEmpty()) stored = w.apply(accepted)
                    } catch (e2: Exception) {
                        if (!isRejection(e2)) throw e2
                        accepted = emptyList()
                        reason = e2.message
                    }
                } catch (e: Exception) {
                    // Disk, lock or other server trouble is not the op's fault: fail the call so the device retries.
                    if (!isRejection(e)) throw e
                    accepted = emptyList()
                    reason = e.message ?: e::class.simpleName
                }
                stored.forEach { results += OpResult(it.op.opId, OpResult.Status.ACCEPTED, it.seq) }
                val rejected = fresh - accepted.toSet()
                if (rejected.isNotEmpty()) {
                    rejected.forEach { results += OpResult(it.opId, OpResult.Status.REJECTED, reason = reason) }
                    corrections += w.correct(rejected, reason ?: "rejected")
                }
            }
            PushResponse(w.epoch, results, corrections)
        }
    }

    override suspend fun pull(workspaceId: String, after: Long, limit: Int): PullResponse {
        require(after >= 0 && limit > 0) { "Bad pull range" }
        val w = ws(workspaceId)
        return w.mutex.withLock { PullResponse(w.epoch, w.epochStart, w.durable, w.log.head(), w.log.page(after, limit)) }
    }

    override suspend fun snapshot(workspaceId: String): Snapshot {
        val w = ws(workspaceId)
        return w.mutex.withLock {
            val out = File.createTempFile("snapshot", ".db", w.home).also { it.delete() }
            try {
                SyncSnapshots.vacuumInto(w.file, out)
                Snapshot(w.log.head(), w.epoch, out.readBytes())
            } finally {
                out.delete()
            }
        }
    }

    private suspend fun ws(id: String): Workspace = lock.withLock {
        workspaces[id] ?: run {
            if (!exists(id)) throw SyncRefusedException("Unknown workspace $id")
            val home = File(dir, id)
            Workspace(home, openLog(File(home, OPLOG))).also { it.open(); workspaces[id] = it }
        }
    }

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

    private class Workspace(val home: File, val log: SyncLog) {
        val mutex = Mutex()
        val file = File(home, PROJECTION)
        lateinit var db: OpenBillDatabase
        var epoch: String = log.meta(EPOCH) ?: newEpoch().also { log.setMeta(mapOf(EPOCH to it)) }
        var epochStart = log.meta(EPOCH_START)?.toLong() ?: 0L
        var durable = log.meta(DURABLE)?.toLong() ?: 0L
        private val backupFile = File(home, "projection.backup.sqlite")

        /** Opens the projection and replays any log tail its last commit missed. */
        suspend fun open() {
            db = openBillDatabase(Room.databaseBuilder<OpenBillDatabase>(name = file.path).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING))
            val applied = db.syncDao().state()!!.cursor
            val head = log.head()
            check(applied <= head) { "Workspace ${home.name}: database is ahead of its op log ($applied > $head); restore it from a backup" }
            if (applied == head) return
            val tail = log.page(applied, Int.MAX_VALUE)
            DatabaseTransactionRunner(db).writeWith("sync.replay") { c ->
                val rows = SyncRows(c)
                tail.forEach { rows.apply(it.op) }
                SyncRules.refreshDerived(db, tail.map { it.op })
                setCursor(c, head)
            }
        }

        /** Validates and applies [ops] as one group and appends them to the log; nothing is kept if anything fails. */
        suspend fun apply(ops: List<SyncOperation>): List<Pulled> = guarded {
            DatabaseTransactionRunner(db).writeWith("sync.server") { c ->
                val rows = SyncRows(c)
                val group = SyncRules.Group()
                for (op in ops) {
                    SyncRules.before(rows, op, group)
                    rows.apply(op)
                }
                SyncRules.after(rows, db, ops, group)
                SyncRules.refreshDerived(db, ops)
                log.append(ops).also { setCursor(c, it.last().seq) }
            }
        }

        /**
         * The server's rows for every entity [rejected] touched, as one server-authored group in
         * the log (so a device that relayed someone else's op still corrects the author), plus a
         * `sync.rejected` audit event: the review task of section 6.4.
         */
        suspend fun correct(rejected: List<SyncOperation>, reason: String): List<SyncOperation> = guarded {
            val group = SyncRows.newGroupId()
            DatabaseTransactionRunner(db).writeWith("sync.correction") { c ->
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
                val ops = fixes + listOfNotNull(review)
                if (ops.isEmpty()) emptyList() else log.append(ops).also { setCursor(c, it.last().seq) }.map { it.op }
            }
        }

        /** A failed projection commit after the log append leaves the log ahead: reopening replays it. */
        private suspend fun <T> guarded(block: suspend () -> T): T {
            val before = log.head()
            try {
                return block()
            } catch (e: Throwable) {
                if (log.head() != before) runCatching { reopen() }
                throw e
            }
        }

        fun backup() {
            backupFile.delete()
            SyncSnapshots.vacuumInto(file, backupFile)
            durable = log.head()
            log.setMeta(mapOf(DURABLE to durable.toString(), BACKUP_SEQ to durable.toString()))
        }

        /** Projection first, then the log: a crash in between replays the tail, as if no restore happened. */
        suspend fun restore() {
            val backupSeq = checkNotNull(log.meta(BACKUP_SEQ)?.toLong()) { "No backup to restore" }
            db.close()
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
            backupFile.copyTo(file)
            epoch = newEpoch()
            epochStart = backupSeq
            durable = backupSeq
            log.truncateAfter(backupSeq, mapOf(EPOCH to epoch, EPOCH_START to backupSeq.toString(), DURABLE to backupSeq.toString()))
            open()
        }

        suspend fun reopen() {
            db.close()
            open()
        }

        fun close() {
            db.close()
            log.close()
        }

        private suspend fun setCursor(c: Transactor, seq: Long) = c.usePrepared("UPDATE sync_state SET cursor = ? WHERE id = 1") { it.bindLong(1, seq); it.step() }

        private fun newEpoch() = Ulid.create(Clock.System.now().toEpochMilliseconds()).toString()
    }

    companion object {
        /** Workspace ids are also directory names: nothing that could leave [dir]. */
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val PROJECTION = "projection.sqlite"
        const val OPLOG = "oplog.sqlite"
        private const val EPOCH = "epoch"
        private const val EPOCH_START = "epochStart"
        private const val DURABLE = "durable"
        private const val BACKUP_SEQ = "backupSeq"

        /**
         * Whether [e] is the op's fault (a rule, a malformed op, a constraint), so the op is
         * rejected for good; anything else (disk, locks) fails the call and the device retries.
         * SQLite: 1 SQL error (unknown column), 19 constraint, 20 type mismatch, 25 range.
         */
        fun isRejection(e: Throwable): Boolean = when (e) {
            is SyncRules.Rejected, is SyncRules.AllocationsRejected, is IllegalArgumentException -> true
            is SQLiteException -> Regex("Error code: (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toInt() in setOf(1, 19, 20, 25)
            else -> false
        }
    }
}

/** Snapshot files (section 6.6): the server's copy of a seeding device, and a joining device's copy of the server. */
object SyncSnapshots {
    /**
     * Makes [file] a fresh replica: no device identity (a new one is seeded on open), no unsent or
     * retained ops, no number counters (each device numbers in its own series, section 6.5).
     * Refuses a damaged file or another schema version.
     */
    fun prepare(file: File) {
        val c = BundledSQLiteDriver().open(file.path)
        try {
            val integrity = c.prepare("PRAGMA integrity_check").use { it.step(); it.getText(0) }
            require(integrity == "ok") { "Snapshot is damaged: $integrity" }
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
