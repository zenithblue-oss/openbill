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
) {
    private val writeMutex = Mutex()

    suspend fun <T> write(block: suspend () -> T): T = writeWith { block() }

    /** [write] that hands [block] the transaction's connection, for raw SQL such as DDL. */
    suspend fun <T> writeWith(block: suspend (Transactor) -> T): T {
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
                        block(transactor)
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
