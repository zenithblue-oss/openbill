package app.openbill.core.data

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
    )
}
