package app.openbill.core.sync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * One workspace's ordered operation log (docs/10-server.md section 3, `oplog.sqlite`): the
 * server's source of truth. [SyncServer] keeps ordering, dedupe and validation; a log only stores.
 * Callers serialize access (one workspace lock).
 */
interface SyncLog : AutoCloseable {
    /** Last assigned seq (0 when empty). */
    fun head(): Long

    fun seqOf(opId: String): Long?

    /** The op id a device sent under its own op number [deviceSeq], to catch copied data folders. */
    fun opIdAt(deviceId: String, deviceSeq: Long): String?

    /** Appends [ops] atomically and durably with consecutive seqs; server-authored ops carry their seq. */
    fun append(ops: List<SyncOperation>): List<Pulled>

    fun page(after: Long, limit: Int): List<Pulled>

    /** Drops ops after [seq] (restore from a backup taken at [seq]) and sets [meta], in one transaction. */
    fun truncateAfter(seq: Long, meta: Map<String, String>)

    fun meta(key: String): String?

    /** Sets all [values] in one transaction. */
    fun setMeta(values: Map<String, String>)
}

/** [SyncLog] in its own SQLite file, WAL mode with full fsync: an accepted op survives power loss. */
class SqliteSyncLog(file: File) : SyncLog {
    private val c: SQLiteConnection = BundledSQLiteDriver().open(file.path)

    init {
        c.prepare("PRAGMA journal_mode = WAL").use { it.step() }
        c.execSQL("PRAGMA synchronous = FULL")
        c.execSQL("CREATE TABLE IF NOT EXISTS ops (seq INTEGER PRIMARY KEY, opId TEXT NOT NULL UNIQUE, deviceId TEXT NOT NULL, deviceSeq INTEGER NOT NULL, op TEXT NOT NULL)")
        c.execSQL("CREATE INDEX IF NOT EXISTS ops_device ON ops (deviceId, deviceSeq)")
        c.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun head(): Long = c.prepare("SELECT COALESCE(MAX(seq), 0) FROM ops").use { it.step(); it.getLong(0) }

    override fun seqOf(opId: String): Long? = c.prepare("SELECT seq FROM ops WHERE opId = ?").use { s ->
        s.bindText(1, opId)
        if (s.step()) s.getLong(0) else null
    }

    override fun opIdAt(deviceId: String, deviceSeq: Long): String? =
        c.prepare("SELECT opId FROM ops WHERE deviceId = ? AND deviceSeq = ? AND deviceId != ? LIMIT 1").use { s ->
            s.bindText(1, deviceId)
            s.bindLong(2, deviceSeq)
            s.bindText(3, SyncRows.SERVER_DEVICE)
            if (s.step()) s.getText(0) else null
        }

    override fun append(ops: List<SyncOperation>): List<Pulled> = transaction {
        var seq = head()
        ops.map { op ->
            seq++
            val stored = if (op.deviceId == SyncRows.SERVER_DEVICE) op.copy(seq = seq) else op
            c.prepare("INSERT INTO ops (seq, opId, deviceId, deviceSeq, op) VALUES (?, ?, ?, ?, ?)").use { s ->
                s.bindLong(1, seq)
                s.bindText(2, stored.opId)
                s.bindText(3, stored.deviceId)
                s.bindLong(4, stored.seq)
                s.bindText(5, stored.toJson())
                s.step()
            }
            Pulled(seq, stored)
        }
    }

    override fun page(after: Long, limit: Int): List<Pulled> = c.prepare("SELECT seq, op FROM ops WHERE seq > ? ORDER BY seq LIMIT ?").use { s ->
        s.bindLong(1, after)
        s.bindLong(2, limit.toLong())
        buildList { while (s.step()) add(Pulled(s.getLong(0), SyncOperation.fromJson(s.getText(1)))) }
    }

    override fun truncateAfter(seq: Long, meta: Map<String, String>) = transaction {
        c.prepare("DELETE FROM ops WHERE seq > ?").use { it.bindLong(1, seq); it.step() }
        putMeta(meta)
    }

    override fun meta(key: String): String? = c.prepare("SELECT value FROM meta WHERE key = ?").use { s ->
        s.bindText(1, key)
        if (s.step()) s.getText(0) else null
    }

    override fun setMeta(values: Map<String, String>) = transaction { putMeta(values) }

    private fun putMeta(values: Map<String, String>) {
        values.forEach { (key, value) ->
            c.prepare("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { s ->
                s.bindText(1, key)
                s.bindText(2, value)
                s.step()
            }
        }
    }

    override fun close() = c.close()

    private fun <T> transaction(block: () -> T): T {
        c.execSQL("BEGIN IMMEDIATE")
        try {
            return block().also { c.execSQL("COMMIT") }
        } catch (e: Throwable) {
            runCatching { c.execSQL("ROLLBACK") }
            throw e
        }
    }
}
