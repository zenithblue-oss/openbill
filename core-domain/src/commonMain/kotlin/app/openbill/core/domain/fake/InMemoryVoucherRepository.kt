package app.openbill.core.domain.fake

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherNumbering
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.CatalogRepository
import app.openbill.core.domain.repository.FirmRepository
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.domain.service.StockMovementDerivation
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

class InMemoryVoucherRepository(
    private val invoicePrefix: String = "INV",
    private val numberPadding: Int = 4,
    private val stock: InMemoryStockRepository? = null,
    private val catalog: CatalogRepository? = null,
    private val firms: FirmRepository? = null,
    private val derivation: StockMovementDerivation = StockMovementDerivation(),
) : VoucherRepository {
    private data class NumberKey(
        val firmId: String,
        val type: VoucherType,
        val financialYearId: String,
    )

    private val vouchers = MutableStateFlow<List<Voucher>>(emptyList())
    private val mutex = Mutex()
    private val nextNumbers = mutableMapOf<NumberKey, Long>()

    override fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>> =
        vouchers.map { values ->
            values.filter {
                it.firmId == firmId &&
                    it.deletedAt == null &&
                    it.date >= fromDate &&
                    it.date <= toDate
            }.sortedWith(
                compareByDescending<Voucher> { it.date }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id },
            )
        }

    override fun observeVoucher(id: String): Flow<Voucher?> =
        vouchers.map { values -> values.firstOrNull { it.id == id } }

    override suspend fun findVoucher(id: String): Voucher? =
        vouchers.value.firstOrNull { it.id == id }

    override suspend fun saveDraft(voucher: Voucher) = mutex.withLock {
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be saved here" }
        require(voucher.number == null) { "Draft voucher must not have a number" }
        vouchers.update { current -> current.filterNot { it.id == voucher.id } + voucher }
    }

    override suspend fun deleteDraft(id: String) = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be deleted here" }
        vouchers.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun post(voucherId: String, at: Instant): Voucher = mutex.withLock {
        val draft = vouchers.value.firstOrNull { it.id == voucherId }
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(draft.status == VoucherStatus.DRAFT) { "Only drafts can be posted" }
        require(draft.number == null) { "Voucher already has a number" }
        val key = NumberKey(draft.firmId, draft.type, draft.financialYearId)
        val next = nextNumbers[key] ?: 1L
        val posted = draft.copy(
            status = VoucherStatus.POSTED,
            number = formatNumber(draft.type, next),
            postedAt = at,
            updatedAt = at,
        )
        val movements = prepareStock(draft, at)
        this.stock?.record(movements)
        nextNumbers[key] = next + 1
        vouchers.update { current -> current.filterNot { it.id == voucherId } + posted }
        posted
    }

    override suspend fun cancel(
        voucherId: String,
        reason: String,
        at: Instant,
    ): Voucher = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == voucherId }
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(voucher.status == VoucherStatus.POSTED) { "Only posted vouchers can be cancelled" }
        val cancelled = voucher.copy(
            status = VoucherStatus.CANCELLED,
            cancellationReason = reason,
            cancelledAt = at,
            updatedAt = at,
        )
        val originalMovements = this.stock?.observeMovements(voucherId)?.first().orEmpty()
        val movements = prepareStock(voucher, at, originalMovements)
        this.stock?.record(movements)
        vouchers.update { current -> current.filterNot { it.id == voucherId } + cancelled }
        cancelled
    }

    private suspend fun prepareStock(
        voucher: Voucher,
        at: Instant,
        reversalOf: List<StockMovement>? = null,
    ): List<StockMovement> {
        val stock = this.stock ?: return emptyList()
        val firms = this.firms ?: return emptyList()
        val settings = firms.findSettings(voucher.firmId) ?: FirmSettings(firmId = voucher.firmId)
        val movements = if (reversalOf != null) {
            derivation.deriveCancellation(voucher, reversalOf, at)
        } else {
            val catalog = this.catalog ?: return emptyList()
            val itemIds = voucher.lines.mapNotNull { it.itemId }.distinct()
            val itemsById = itemIds
                .mapNotNull { itemId ->
                    catalog.findItem(itemId)?.let { item -> itemId to item }
                }
                .toMap()
            derivation.derivePosted(voucher, itemsById, settings, at)
        }
        if (!settings.negativeStockAllowed) {
            val balances = mutableMapOf<StockKey, Quantity>()
            for (movement in movements) {
                val key = StockKey(movement.firmId, movement.itemId, movement.godownId)
                val current = balances.getOrPut(key) {
                    stock.balance(movement.firmId, movement.itemId, movement.godownId)
                }
                val next = current + movement.quantity
                require(next >= Quantity.of(0)) {
                    "Insufficient stock for item ${movement.itemId} " +
                        "in godown ${movement.godownId}"
                }
                balances[key] = next
            }
        }
        return movements
    }

    private data class StockKey(
        val firmId: String,
        val itemId: String,
        val godownId: String,
    )

    private fun formatNumber(type: VoucherType, sequence: Long): String =
        VoucherNumbering.format(prefixFor(type), sequence, numberPadding)

    private fun prefixFor(type: VoucherType): String = when (type) {
        VoucherType.SALE -> invoicePrefix
        VoucherType.PURCHASE -> "PUR"
        VoucherType.CREDIT_NOTE -> "CRN"
        VoucherType.DEBIT_NOTE -> "DBN"
        VoucherType.QUOTATION -> "QTN"
        VoucherType.PROFORMA -> "PRF"
        VoucherType.PAYMENT_IN -> "RCP"
        VoucherType.PAYMENT_OUT -> "PAY"
        VoucherType.EXPENSE -> "EXP"
        VoucherType.STOCK_ADJUSTMENT -> "ADJ"
        VoucherType.STOCK_TRANSFER -> "TRF"
    }
}
