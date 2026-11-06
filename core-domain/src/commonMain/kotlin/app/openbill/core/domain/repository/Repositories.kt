package app.openbill.core.domain.repository

data class Repositories(
    val firms: FirmRepository,
    val parties: PartyRepository,
    val catalog: CatalogRepository,
    val vouchers: VoucherRepository,
    val stock: StockRepository,
    val ledger: LedgerRepository,
    val payments: PaymentRepository,
    val audit: AuditRepository = app.openbill.core.domain.fake.InMemoryAuditRepository(),
    val recurring: RecurringRepository = app.openbill.core.domain.fake.InMemoryRecurringRepository(vouchers),
    val users: UserRepository = app.openbill.core.domain.fake.InMemoryUserRepository(),
    /** Runs [block] in one database transaction; repository writes inside it roll back together. */
    val transaction: suspend (block: suspend () -> Unit) -> Unit = { it() },
)
