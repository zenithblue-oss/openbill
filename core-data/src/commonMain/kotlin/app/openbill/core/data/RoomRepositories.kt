package app.openbill.core.data

import androidx.room.Transactor
import androidx.room.useWriterConnection
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.repository.Repositories

fun OpenBillDatabase.roomRepositories(
    transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(this),
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
        audit = RoomAuditRepository(this),
        // One outer writer transaction; repository writes nest inside it (same as the CLI import).
        transaction = { block -> useWriterConnection { it.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() } } },
    )
}
