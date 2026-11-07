package app.openbill.core.data

import androidx.room.Transactor
import androidx.room.useWriterConnection
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.repository.Repositories

fun OpenBillDatabase.roomRepositories(
    transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(this) { app.openbill.core.domain.model.Access.actorId },
): Repositories {
    val ledger = RoomLedgerRepository(this, transactions)
    return Repositories(
        firms = RoomFirmRepository(this, transactions),
        parties = RoomPartyRepository(this, transactions),
        catalog = RoomCatalogRepository(this, transactions),
        vouchers = RoomVoucherRepository(this, transactions, ledger),
        stock = RoomStockRepository(this),
        ledger = ledger,
        payments = RoomPaymentRepository(this, transactions),
        audit = RoomAuditRepository(this, transactions),
        recurring = RoomRecurringRepository(this, transactions),
        users = RoomUserRepository(this, transactions),
        // One outer writer transaction; repository writes nest inside it (same as the CLI import)
        // and their sync ops share one group, accepted or rejected together (docs/10-server.md 6.3).
        transaction = { block ->
            useWriterConnection {
                it.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    val group = app.openbill.core.model.Ulid.create(now).toString()
                    syncDao().begin(group, null, app.openbill.core.domain.model.Access.actorId, now)
                    block().also { syncDao().end(group) }
                }
            }
        },
    )
}
