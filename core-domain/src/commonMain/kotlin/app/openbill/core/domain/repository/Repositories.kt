package app.openbill.core.domain.repository

data class Repositories(
    val firms: FirmRepository,
    val parties: PartyRepository,
    val catalog: CatalogRepository,
    val vouchers: VoucherRepository,
    val stock: StockRepository,
    val ledger: LedgerRepository,
    val payments: PaymentRepository,
)
