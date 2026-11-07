package app.openbill.core.database

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes writes against a single [OpenBillDatabase].
 *
 * Share one runner instance per database so that all writers use the same mutex.
 * Callers must not invoke [write] from inside a [write] block, directly or from a child
 * coroutine. Nested writes throw [IllegalStateException] instead of deadlocking on the
 * mutex. Read-only work inside a write should call DAOs directly rather than [read].
 */
class DatabaseTransactionRunner(
    private val database: OpenBillDatabase,
    /** Signed-in user stamped on the write's sync ops. */
    private val actor: () -> String? = { null },
) {
    private val writeMutex = Mutex()

    /** [action] names the domain operation (e.g. `voucher.post`) on the sync ops this write records. */
    suspend fun <T> write(action: String? = null, block: suspend () -> T): T = writeWith(action) { block() }

    /** [write] that hands [block] the transaction's connection, for raw SQL such as DDL. */
    suspend fun <T> writeWith(action: String? = null, block: suspend (Transactor) -> T): T {
        check(currentCoroutineContext()[TransactionMarker.Key] == null) {
            "Nested DatabaseTransactionRunner.write calls are not allowed"
        }
        return writeMutex.withLock {
            check(currentCoroutineContext()[TransactionMarker.Key] == null) {
                "Nested DatabaseTransactionRunner.write calls are not allowed"
            }
            withContext(TransactionMarker) {
                database.useWriterConnection { transactor ->
                    transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        val sync = database.syncDao()
                        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        val group = app.openbill.core.model.Ulid.create(now).toString()
                        sync.begin(group, action, actor(), now)
                        block(transactor).also { sync.end(group) }
                    }
                }
            }
        }
    }

    suspend fun <T> read(block: suspend () -> T): T =
        database.useReaderConnection { block() }
}

private object TransactionMarker : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<TransactionMarker>
}
