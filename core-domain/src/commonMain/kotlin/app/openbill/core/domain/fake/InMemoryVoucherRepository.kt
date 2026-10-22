package app.openbill.core.domain.fake

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherNumbering
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.isPaymentAccount
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.domain.repository.CatalogRepository
import app.openbill.core.domain.repository.FirmRepository
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.domain.service.LedgerJournalDerivation
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

class InMemoryVoucherRepository(
    private val invoicePrefix: String = "INV",
    private val numberPadding: Int = 4,
    private val stock: InMemoryStockRepository? = null,
    private val catalog: CatalogRepository? = null,
    private val firms: FirmRepository? = null,
    private val derivation: StockMovementDerivation = StockMovementDerivation(),
    private val ledger: InMemoryLedgerRepository? = null,
    private val payments: InMemoryPaymentRepository? = null,
    private val ledgerDerivation: LedgerJournalDerivation = LedgerJournalDerivation(),
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

    override fun observeRecycleBin(firmId: String): Flow<List<Voucher>> =
        vouchers.map { values ->
            values.filter {
                it.firmId == firmId &&
                    it.status == VoucherStatus.DRAFT &&
                    it.deletedAt != null
            }.sortedWith(
                compareByDescending<Voucher> { it.deletedAt }
                    .thenBy { it.id },
            )
        }

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
        payments?.deletePaymentDetails(id)
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
        val journalEntries = prepareLedgerPosted(posted, at)
        this.stock?.record(movements)
        ledger?.record(journalEntries)
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
        val journalEntries = prepareLedgerCancelled(voucher, at)
        this.stock?.record(movements)
        ledger?.record(journalEntries)
        vouchers.update { current -> current.filterNot { it.id == voucherId } + cancelled }
        cancelled
    }

    override suspend fun restoreCancelled(
        voucherId: String,
        cancellationReason: String,
        restoreReason: String,
        at: Instant,
    ): Voucher = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == voucherId }
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(voucher.status == VoucherStatus.CANCELLED) {
            "Only cancelled vouchers can be restored"
        }
        require(voucher.deletedAt == null) { "Deleted vouchers cannot be restored" }
        val recordedReason = voucher.cancellationReason
        require(!recordedReason.isNullOrBlank()) {
            "Cancelled voucher has no cancellation reason"
        }
        require(cancellationReason.trim() == recordedReason.trim()) {
            "Cancellation reason does not match the recorded reason"
        }
        val trimmedRestoreReason = restoreReason.trim()
        require(trimmedRestoreReason.isNotEmpty()) { "Restore reason cannot be blank" }

        val posted = voucher.copy(status = VoucherStatus.POSTED)
        val restoreDate = at.toLocalDateTime(TimeZone.UTC).date
        val movements = prepareStock(posted, at).map { it.copy(date = restoreDate) }
        val journalEntries = prepareLedgerPosted(posted, at).map { it.copy(date = restoreDate) }
        val restored = posted.copy(
            restoredAt = at,
            updatedAt = at,
        )
        this.stock?.record(movements)
        ledger?.record(journalEntries)
        vouchers.update { current -> current.filterNot { it.id == voucherId } + restored }
        restored
    }

    override suspend fun softDeleteDraft(id: String, at: Instant): Voucher = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(voucher.status == VoucherStatus.DRAFT) {
            "Only drafts can be moved to the recycle bin"
        }
        require(voucher.deletedAt == null) { "Draft is already in the recycle bin" }
        val deleted = voucher.copy(deletedAt = at, updatedAt = at)
        vouchers.update { current -> current.filterNot { it.id == id } + deleted }
        deleted
    }

    override suspend fun restoreDraft(id: String, at: Instant): Voucher = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(voucher.status == VoucherStatus.DRAFT) {
            "Only drafts can be restored from the recycle bin"
        }
        require(voucher.deletedAt != null) { "Draft is not in the recycle bin" }
        val restored = voucher.copy(deletedAt = null, updatedAt = at)
        vouchers.update { current -> current.filterNot { it.id == id } + restored }
        restored
    }

    override suspend fun purgeDraft(id: String) = mutex.withLock {
        val voucher = vouchers.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be purged" }
        require(voucher.deletedAt != null) { "Only recycle bin drafts can be purged" }
        payments?.deletePaymentDetails(id)
        vouchers.update { current -> current.filterNot { it.id == id } }
    }

    private suspend fun prepareLedgerPosted(voucher: Voucher, at: Instant): List<JournalEntry> {
        val ledger = this.ledger ?: return emptyList()
        if (!voucher.type.postsLedger()) return emptyList()
        val partyId = voucher.partyId
        require(partyId != null) { "Voucher must have a party to post to the ledger" }
        val paymentAccountId = paymentAccountId(voucher, ledger)
        val accounts = ledger.ensureSystemAccounts(voucher.firmId, at)
        return ledgerDerivation.derivePosted(voucher, accounts, paymentAccountId, at)
    }

    private suspend fun prepareLedgerCancelled(
        voucher: Voucher,
        at: Instant,
    ): List<JournalEntry> {
        val ledger = this.ledger ?: return emptyList()
        if (!voucher.type.postsLedger()) return emptyList()
        val entries = ledger.journalEntriesForVoucher(voucher.id)
        return ledgerDerivation.deriveReversal(voucher, entries, at)
    }

    private suspend fun paymentAccountId(
        voucher: Voucher,
        ledger: InMemoryLedgerRepository,
    ): String? {
        if (!voucher.type.isPayment()) return null
        val details = payments?.findPaymentDetails(voucher.id)
            ?: throw IllegalArgumentException("Payment details are required before posting")
        val account = ledger.findAccountById(details.accountId)
            ?: throw IllegalArgumentException("Unknown account: ${details.accountId}")
        require(account.firmId == voucher.firmId) {
            "Payment account belongs to a different firm"
        }
        require(account.deletedAt == null) { "Payment account is deleted" }
        require(account.type.isPaymentAccount) {
            "Payment account must be a cash, bank, or asset account"
        }
        return account.id
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
