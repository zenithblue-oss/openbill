package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryCatalogRepository
import app.openbill.core.domain.fake.InMemoryFirmRepository
import app.openbill.core.domain.fake.InMemoryLedgerRepository
import app.openbill.core.domain.fake.InMemoryPartyRepository
import app.openbill.core.domain.fake.InMemoryPaymentRepository
import app.openbill.core.domain.fake.InMemoryStockRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class VoucherRestoreAndRecycleTest {
    private val createdAt = Instant.parse("2026-05-01T00:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T20:00:00Z")
    private val restoredAt = Instant.parse("2026-05-25T10:00:00Z")
    private val secondCancelledAt = Instant.parse("2026-05-28T10:00:00Z")
    private val secondRestoredAt = Instant.parse("2026-05-30T10:00:00Z")

    @Test
    fun restoreCancelledSaleReappliesStockAndLedgerExactlyOnce(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seed()
        val draft = saleVoucher()
        fixture.vouchers.saveDraft(draft)
        val posted = PostVoucher(fixture.vouchers)(draft.id, postedAt)
        assertEquals(Quantity.of(2) * -1L, fixture.stockBalance())
        assertEquals(2, fixture.ledger.journalEntriesForVoucher(draft.id).size)

        CancelVoucher(fixture.vouchers)(draft.id, "Customer request", cancelledAt)
        assertEquals(Quantity.of(0), fixture.stockBalance())
        assertEquals(4, fixture.ledger.journalEntriesForVoucher(draft.id).size)

        val restored = RestoreVoucher(fixture.vouchers)(
            draft.id,
            "Customer request",
            "Customer changed their mind",
            restoredAt,
        )

        assertEquals(VoucherStatus.POSTED, restored.status)
        assertEquals(posted.number, restored.number)
        assertEquals("Customer request", restored.cancellationReason)
        assertEquals(cancelledAt, restored.cancelledAt)
        assertEquals(restoredAt, restored.restoredAt)
        assertEquals(restoredAt, restored.updatedAt)
        assertEquals(restored, fixture.vouchers.findVoucher(draft.id))
        assertEquals(Quantity.of(2) * -1L, fixture.stockBalance())

        val movements = fixture.stock.observeMovements(draft.id).first()
        assertEquals(3, movements.size)
        val restoreDate = restoredAt.toLocalDateTime(TimeZone.UTC).date
        assertEquals(restoreDate, movements.last().date)
        assertEquals(1, movements.count { it.date == restoreDate })

        val entries = fixture.ledger.journalEntriesForVoucher(draft.id)
        assertEquals(6, entries.size)
        val receivable = entries.filter {
            it.accountId == fixture.receivableAccountId()
        }
        assertEquals(Money.of(10_000), receivable.fold(Money.ZERO) { total, entry ->
            total + entry.debit - entry.credit
        })
        assertTrue(entries.all { it.date <= restoreDate })

        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(
                draft.id,
                "Customer request",
                "Restore again",
                restoredAt,
            )
        }
        assertEquals(3, fixture.stock.observeMovements(draft.id).first().size)
        assertEquals(6, fixture.ledger.journalEntriesForVoucher(draft.id).size)
    }

    @Test
    fun restoreRejectsWrongReasonBlankReasonAndNonCancelledVoucher(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seed()
        val draft = saleVoucher()
        fixture.vouchers.saveDraft(draft)

        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(draft.id, "Customer request", "Restore", restoredAt)
        }

        PostVoucher(fixture.vouchers)(draft.id, postedAt)
        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(draft.id, "Customer request", "Restore", restoredAt)
        }

        CancelVoucher(fixture.vouchers)(draft.id, "Customer request", cancelledAt)
        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(draft.id, "Different reason", "Restore", restoredAt)
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(draft.id, "Customer request", "   ", restoredAt)
        }
        assertEquals(VoucherStatus.CANCELLED, fixture.vouchers.findVoucher(draft.id)?.status)
        assertEquals(2, fixture.stock.observeMovements(draft.id).first().size)

        val restored = fixture.vouchers.restoreCancelled(
            draft.id,
            "  Customer request  ",
            "  Restored after review  ",
            restoredAt,
        )
        assertEquals(VoucherStatus.POSTED, restored.status)
        assertEquals("Customer request", restored.cancellationReason)
        assertEquals(restoredAt, restored.restoredAt)
    }

    @Test
    fun secondCancelAndRestoreCycleNetsCorrectly(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seed()
        val draft = saleVoucher()
        fixture.vouchers.saveDraft(draft)
        PostVoucher(fixture.vouchers)(draft.id, postedAt)

        CancelVoucher(fixture.vouchers)(draft.id, "First cancellation", cancelledAt)
        assertEquals(Quantity.of(0), fixture.stockBalance())
        RestoreVoucher(fixture.vouchers)(
            draft.id,
            "First cancellation",
            "Restored once",
            restoredAt,
        )
        assertEquals(Quantity.of(2) * -1L, fixture.stockBalance())

        CancelVoucher(fixture.vouchers)(draft.id, "Second cancellation", secondCancelledAt)
        assertEquals(Quantity.of(0), fixture.stockBalance())
        val restored = RestoreVoucher(fixture.vouchers)(
            draft.id,
            "Second cancellation",
            "Restored twice",
            secondRestoredAt,
        )

        assertEquals(secondRestoredAt, restored.restoredAt)
        assertEquals(Quantity.of(2) * -1L, fixture.stockBalance())
        assertEquals(7, fixture.stock.observeMovements(draft.id).first().size)
        val entries = fixture.ledger.journalEntriesForVoucher(draft.id)
        assertEquals(14, entries.size)
        val receivable = entries.filter {
            it.accountId == fixture.receivableAccountId()
        }
        assertEquals(Money.of(10_000), receivable.fold(Money.ZERO) { total, entry ->
            total + entry.debit - entry.credit
        })
    }

    @Test
    fun restorePaymentReactivatesDormantAllocationsAndRevalidatesAccount(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seed()
        val sale = fixture.post(saleVoucher(), postedAt)
        val payment = fixture.postPayment(total = 4_000)
        fixture.payments.allocate(
            payment.id,
            listOf(AllocationRequest(sale.id, Money.of(4_000))),
            postedAt,
        )
        assertEquals(Money.of(6_000), fixture.payments.invoiceOutstanding(sale.id))

        CancelVoucher(fixture.vouchers)(payment.id, "Cheque bounced", cancelledAt)
        assertEquals(Money.of(10_000), fixture.payments.invoiceOutstanding(sale.id))
        assertEquals(
            Money.of(10_000),
            fixture.payments.observeInvoiceOutstanding(sale.id).first(),
        )
        assertEquals(1, fixture.payments.observeAllocationsForPayment(payment.id).first().size)
        assertEquals(Money.of(4_000), fixture.payments.allocatedTotalForPayment(payment.id))

        val restored = RestoreVoucher(fixture.vouchers)(
            payment.id,
            "Cheque bounced",
            "Replacement cheque cleared",
            restoredAt,
        )

        assertEquals(VoucherStatus.POSTED, restored.status)
        assertEquals(restoredAt, restored.restoredAt)
        assertEquals(Money.of(6_000), fixture.payments.invoiceOutstanding(sale.id))
        assertEquals(
            Money.of(6_000),
            fixture.payments.observeInvoiceOutstanding(sale.id).first(),
        )
        val allocations = fixture.payments.observeAllocationsForPayment(payment.id).first()
        assertEquals(1, allocations.size)
        assertEquals(Money.of(4_000), allocations.single().amount)
        assertEquals(6, fixture.ledger.journalEntriesForVoucher(payment.id).size)

        CancelVoucher(fixture.vouchers)(payment.id, "Stopped again", secondCancelledAt)
        val cash = assertNotNull(fixture.ledger.findAccount("firm-1", AccountCode.CASH))
        fixture.ledger.saveAccount(
            cash.copy(
                deletedAt = secondCancelledAt,
                updatedAt = secondCancelledAt,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.vouchers.restoreCancelled(
                payment.id,
                "Stopped again",
                "Account is gone",
                secondRestoredAt,
            )
        }
        assertEquals(VoucherStatus.CANCELLED, fixture.vouchers.findVoucher(payment.id)?.status)
    }

    @Test
    fun draftRecycleBinSoftDeleteRestoreAndPurge(): Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val draft = draftVoucher("draft-1")
        repository.saveDraft(draft)

        assertEquals(listOf(draft.id), activeIds(repository))
        val deleted = repository.softDeleteDraft(draft.id, deletedAt(1))
        assertEquals(deletedAt(1), deleted.deletedAt)
        assertTrue(activeIds(repository).isEmpty())
        assertEquals(listOf(draft.id), repository.observeRecycleBin("firm-1").first().map { it.id })
        assertEquals(deleted, repository.findVoucher(draft.id))

        assertFailsWith<IllegalArgumentException> {
            repository.softDeleteDraft(draft.id, deletedAt(2))
        }

        val restored = repository.restoreDraft(draft.id, deletedAt(2))
        assertNull(restored.deletedAt)
        assertEquals(deletedAt(2), restored.updatedAt)
        assertEquals(listOf(draft.id), activeIds(repository))
        assertTrue(repository.observeRecycleBin("firm-1").first().isEmpty())

        assertFailsWith<IllegalArgumentException> {
            repository.restoreDraft(draft.id, deletedAt(3))
        }

        repository.softDeleteDraft(draft.id, deletedAt(3))
        repository.purgeDraft(draft.id)
        assertNull(repository.findVoucher(draft.id))
        assertTrue(repository.observeRecycleBin("firm-1").first().isEmpty())

        assertFailsWith<IllegalArgumentException> {
            repository.purgeDraft(draft.id)
        }
        val second = draftVoucher("draft-2")
        repository.saveDraft(second)
        assertFailsWith<IllegalArgumentException> {
            repository.purgeDraft(second.id)
        }
        repository.deleteDraft(second.id)
        assertNull(repository.findVoucher(second.id))
    }

    @Test
    fun postedAndCancelledVouchersCannotBeRecycledOrPurged(): Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val postedDraft = draftVoucher("posted-voucher")
        repository.saveDraft(postedDraft)
        PostVoucher(repository)(postedDraft.id, postedAt)

        assertFailsWith<IllegalArgumentException> {
            repository.softDeleteDraft(postedDraft.id, deletedAt(1))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.purgeDraft(postedDraft.id)
        }
        assertEquals(postedDraft.id, repository.findVoucher(postedDraft.id)?.id)

        CancelVoucher(repository)(postedDraft.id, "Cancelled", cancelledAt)
        assertFailsWith<IllegalArgumentException> {
            repository.softDeleteDraft(postedDraft.id, deletedAt(2))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.purgeDraft(postedDraft.id)
        }
        assertEquals(VoucherStatus.CANCELLED, repository.findVoucher(postedDraft.id)?.status)
        assertTrue(repository.observeRecycleBin("firm-1").first().isEmpty())
    }

    @Test
    fun recycleBinOrdersByDeletedAtAndScopesFirm(): Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(draftVoucher("firm-1-a"))
        repository.saveDraft(draftVoucher("firm-1-b"))
        repository.saveDraft(draftVoucher("firm-2-a", firmId = "firm-2"))

        repository.softDeleteDraft("firm-1-a", deletedAt(1))
        repository.softDeleteDraft("firm-1-b", deletedAt(2))
        repository.softDeleteDraft("firm-2-a", deletedAt(3))

        assertEquals(
            listOf("firm-1-b", "firm-1-a"),
            repository.observeRecycleBin("firm-1").first().map { it.id },
        )
        assertEquals(
            listOf("firm-2-a"),
            repository.observeRecycleBin("firm-2").first().map { it.id },
        )
    }

    @Test
    fun restoreUseCaseValidatesReasonsBeforeDelegating(): Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val draft = draftVoucher("draft-1")
        repository.saveDraft(draft)
        PostVoucher(repository)(draft.id, postedAt)
        CancelVoucher(repository)(draft.id, "Customer request", cancelledAt)

        assertFailsWith<IllegalArgumentException> {
            RestoreVoucher(repository)(draft.id, "    ", "Restore", restoredAt)
        }
        assertFailsWith<IllegalArgumentException> {
            RestoreVoucher(repository)(draft.id, "Customer request", "   ", restoredAt)
        }
        assertFailsWith<IllegalArgumentException> {
            RestoreVoucher(repository)(draft.id, "Wrong", "Restore", restoredAt)
        }

        val restored = RestoreVoucher(repository)(
            draft.id,
            "  Customer request  ",
            "  Approved  ",
            restoredAt,
        )
        assertEquals(VoucherStatus.POSTED, restored.status)
        assertEquals("Customer request", restored.cancellationReason)
    }

    private suspend fun activeIds(repository: InMemoryVoucherRepository): List<String> =
        repository.observeVouchers(
            "firm-1",
            LocalDate(2026, 1, 1),
            LocalDate(2026, 12, 31),
        ).first().map { it.id }

    private fun deletedAt(minutes: Long): Instant = createdAt + minutes.minutes

    private fun saleVoucher(id: String = "sale-1"): Voucher = Voucher(
        id = id,
        firmId = "firm-1",
        financialYearId = "fy-1",
        type = VoucherType.SALE,
        date = LocalDate(2026, 5, 10),
        partyId = "party-1",
        placeOfSupplyStateCode = "27",
        lines = listOf(
            VoucherLine(
                id = "line-$id",
                lineNumber = 1,
                itemId = "item-1",
                description = "Steel Widget",
                quantity = Quantity.of(2),
                rate = Money.of(5_000),
                taxable = Money.of(10_000),
                total = Money.of(10_000),
                godownId = "godown-1",
            ),
        ),
        subtotal = Money.of(10_000),
        taxable = Money.of(10_000),
        total = Money.of(10_000),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun draftVoucher(id: String, firmId: String = "firm-1"): Voucher = Voucher(
        id = id,
        firmId = firmId,
        financialYearId = "fy-1",
        type = VoucherType.SALE,
        date = LocalDate(2026, 5, 10),
        partyId = "party-1",
        placeOfSupplyStateCode = "27",
        lines = listOf(
            VoucherLine(
                id = "line-$id",
                lineNumber = 1,
                description = "Draft item",
                quantity = Quantity.of(1),
                rate = Money.of(1_000),
                taxable = Money.of(1_000),
                total = Money.of(1_000),
            ),
        ),
        subtotal = Money.of(1_000),
        taxable = Money.of(1_000),
        total = Money.of(1_000),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun fixture(): Fixture = Fixture(createdAt)

    private class Fixture(private val createdAt: Instant) {
        val stock = InMemoryStockRepository()
        val catalog = InMemoryCatalogRepository()
        val firms = InMemoryFirmRepository()
        val parties = InMemoryPartyRepository()
        val ledger = InMemoryLedgerRepository(parties)
        val payments = InMemoryPaymentRepository()
        val vouchers = InMemoryVoucherRepository(
            stock = stock,
            catalog = catalog,
            firms = firms,
            ledger = ledger,
            payments = payments,
        )

        init {
            ledger.attachVouchers(vouchers)
            payments.attachVouchers(vouchers)
        }

        suspend fun seed() {
            firms.saveSettings(
                FirmSettings(
                    firmId = "firm-1",
                    defaultGodownId = "godown-1",
                    negativeStockAllowed = true,
                ),
            )
            parties.saveParty(
                Party(
                    id = "party-1",
                    firmId = "firm-1",
                    name = "Acme Traders",
                    type = PartyType.CUSTOMER,
                    openingBalance = Money.ZERO,
                    openingBalanceType = OpeningBalanceType.DEBIT,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
            catalog.saveGodown(
                Godown(
                    id = "godown-1",
                    firmId = "firm-1",
                    name = "Main",
                    isDefault = true,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
            catalog.saveItem(
                Item(
                    id = "item-1",
                    firmId = "firm-1",
                    name = "Steel Widget",
                    unitId = "unit-1",
                    trackInventory = true,
                    salePrice = Money.of(5_000),
                    purchasePrice = Money.of(4_000),
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
        }

        suspend fun post(voucher: Voucher, at: Instant): Voucher {
            vouchers.saveDraft(voucher)
            return PostVoucher(vouchers)(voucher.id, at)
        }

        suspend fun postPayment(total: Long): Voucher {
            val draft = Voucher(
                id = "payment-1",
                firmId = "firm-1",
                financialYearId = "fy-1",
                type = VoucherType.PAYMENT_IN,
                date = LocalDate(2026, 5, 12),
                partyId = "party-1",
                placeOfSupplyStateCode = "27",
                total = Money.of(total),
                createdAt = createdAt,
                updatedAt = createdAt,
            )
            vouchers.saveDraft(draft)
            val cash = ledger.ensureSystemAccounts("firm-1", createdAt).cash
            payments.savePaymentDetails(
                PaymentDetails(
                    voucherId = draft.id,
                    firmId = "firm-1",
                    accountId = cash.id,
                    method = PaymentMethod.CASH,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
            return PostVoucher(vouchers)(draft.id, Instant.parse("2026-05-10T09:00:00Z"))
        }

        suspend fun stockBalance(): Quantity = stock.balance("firm-1", "item-1", "godown-1")

        suspend fun receivableAccountId(): String =
            ledger.ensureSystemAccounts("firm-1", createdAt).accountsReceivable.id
    }
}
