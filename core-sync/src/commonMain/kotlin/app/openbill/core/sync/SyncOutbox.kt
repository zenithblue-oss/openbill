package app.openbill.core.sync

import androidx.room.useReaderConnection
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.VoucherNumbering

/**
 * This device's unsent operations (docs/10-server.md section 6.2). The sync triggers fill it in
 * the same transaction as each change, but only while enabled: a standalone install records
 * nothing, so the outbox cannot grow for users who never pair. Pairing (S1) enables it in the same
 * transaction as the snapshot it uploads; history before that travels in the snapshot.
 */
class SyncOutbox(private val database: OpenBillDatabase) {
    private val dao = database.syncDao()

    suspend fun deviceId(): String = checkNotNull(dao.state()) { "Sync state is seeded when the database opens" }.deviceId

    suspend fun isEnabled(): Boolean = dao.state()?.enabled == true

    /**
     * Starts recording, numbering in [series] (section 6.5: one series per paired device, never
     * reused), pulling after [cursor], the seq of the snapshot this database starts from, in the
     * server's [epoch] (null: adopt the server's on first contact).
     */
    suspend fun enable(series: String, cursor: Long = 0, epoch: String? = null) {
        require(SERIES.matches(series)) { "A number series is 1-3 capital letters or digits, got '$series'" }
        // GST: numbers stay within 16 characters with the series added (other document prefixes are 3 letters at most).
        val prefixes = database.useReaderConnection { c ->
            c.usePrepared("SELECT invoicePrefix FROM firm_settings") { s -> buildList { while (s.step()) add(s.getText(0)) } }
        }
        prefixes.forEach { prefix ->
            val sample = VoucherNumbering.seriesPrefix(prefix, series)
            require(runCatching { VoucherNumbering.format(sample, 1, 4) }.isSuccess) {
                "With series $series invoice numbers would start ${sample}-0001, over GST's ${VoucherNumbering.MAX_LENGTH} characters: use a shorter series or invoice prefix"
            }
        }
        DatabaseTransactionRunner(database).write("sync.enable") { dao.pair(series, cursor, epoch) }
    }

    /** Unpairing: stop recording and drop unsent and retained ops; re-pairing starts from a fresh snapshot (section 6.6). */
    suspend fun disable() = DatabaseTransactionRunner(database).write("sync.disable") {
        dao.unpair()
        dao.clear()
        dao.clearInbox()
    }

    /** Oldest first, in authoring order: the order they must be pushed in. */
    suspend fun pending(limit: Int = 500): List<SyncOperation> = dao.pending(limit).map(SyncOperation::from)

    suspend fun size(): Long = dao.pendingCount()

    /** Drops ops up to and including [seq]. */
    suspend fun acknowledge(seq: Long) {
        dao.acknowledge(seq)
    }

    private companion object {
        val SERIES = Regex("[A-Z0-9]{1,3}")
    }
}
