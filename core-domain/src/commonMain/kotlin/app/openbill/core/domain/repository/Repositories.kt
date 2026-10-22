package app.openbill.core.domain.repository

data class Repositories(
    val firms: FirmRepository,
    val parties: PartyRepository,
    val catalog: CatalogRepository,
    val vouchers: VoucherRepository,
)
