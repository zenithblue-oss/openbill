package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomVoucherRestoreTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var runner: DatabaseTransactionRunner
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private lateinit var vouchers: RoomVoucherRepository
    private lateinit var firms: RoomFirmRepository
    private lateinit var parties: RoomPartyRepository
    private lateinit var catalog: RoomCatalogRepository

    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T20:00:00Z")
    private val restoredAt = Instant.parse("2026-05-25T10:00:00Z")
    private val secondCancelledAt = Instant.parse("2026-05-28T10:00:00Z")

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        runner = DatabaseTransactionRunner(db)
        ledger = RoomLedgerRepository(db, runner)
        payments = RoomPaymentRepository(db, runner)
        vouchers = RoomVoucherRepository(db, runner, ledger)
        firms = RoomFirmRepository(db, runner)
        parties = RoomPartyRepository(db, runner)
        catalog = RoomCatalogRepository(db, runner)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun restoreCancelledSaleReappliesStockAndJournalAtRestoreDate(): Unit = runBlocking {
        seedMasterData()
        val draft = stockVoucher()
        vouchers.saveDraft(draft)
        val posted = vouchers.post(draft.id, postedAt)
        assertEquals(
            Quantity.of(2).scaledUnits * -1,
            db.stockMovementDao().balanceForItemGodown("firm-1", "item-1", "godown-1"),
        )

        vouchers.cancel(draft.id, "Customer request", cancelledAt)
        assertEquals(
            0L,
            db.stockMovementDao().balanceForItemGodown("firm-1", "item-1", "godown-1"),
        )

        val restored = vouchers.restoreCancelled(
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
        assertEquals(restored, vouchers.findVoucher(draft.id))
        assertEquals(
            Quantity.of(2).scaledUnits * -1,
            db.stockMovementDao().balanceForItemGodown("firm-1", "item-1", "godown-1"),
        )

        val entity = assertNotNull(db.voucherDao().findById(draft.id))
        assertEquals("posted", entity.status)
        assertEquals(posted.number, entity.number)
        assertEquals("Customer request", entity.cancellationReason)
        assertEquals(cancelledAt.toEpochMilliseconds(), entity.cancelledAt)
        assertEquals(restoredAt.toEpochMilliseconds(), entity.restoredAt)
        assertEquals(4L, entity.sync.revision)

        val movements = db.stockMovementDao().movementsForVoucher(draft.id)
        assertEquals(3, movements.size)
        assertEquals(
            listOf(
                Quantity.of(2).scaledUnits * -1,
                Quantity.of(2).scaledUnits,
                Quantity.of(2).scaledUnits * -1,
            ),
            movements.map { it.quantity },
        )
        assertEquals(LocalDate(2026, 5, 10), LocalDate.parse(movements[0].date))
        assertEquals(LocalDate(2026, 5, 20), LocalDate.parse(movements[1].date))
        assertEquals(LocalDate(2026, 5, 25), LocalDate.parse(movements[2].date))

        val journal = db.ledgerDao().entriesForVoucher(draft.id)
        assertEquals(6, journal.size)
        assertEquals(4L, entity.sync.revision)

        val audits = auditEvents(draft.id)
        assertEquals(
            listOf("voucher.restored", "voucher.cancelled", "voucher.posted"),
            audits.map { it.action },
        )
        val restoration = audits.first()
        val details = assertNotNull(restoration.detailsJson)
        assertTrue(details.contains("\"cancellationReason\":\"Customer request\""))
        assertTrue(details.contains("\"restoreReason\":\"Customer changed their mind\""))
        assertTrue(details.contains("\"restoredAt\":${restoredAt.toEpochMilliseconds()}"))

        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreCancelled(
                draft.id,
                "Customer request",
                "Restore again",
                restoredAt,
            )
        }
        assertEquals(3, db.stockMovementDao().movementsForVoucher(draft.id).size)
        assertEquals(6, db.ledgerDao().entriesForVoucher(draft.id).size)
    }

    @Test
    fun restoreRejectsWrongReasonDuplicateAndDeletedVoucher(): Unit = runBlocking {
        seedMasterData()
        val draft = stockVoucher()
        vouchers.saveDraft(draft)
        vouchers.post(draft.id, postedAt)
        vouchers.cancel(draft.id, "Customer request", cancelledAt)

        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreCancelled(draft.id, "Wrong reason", "Restore", restoredAt)
        }
        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreCancelled(draft.id, "Customer request", "   ", restoredAt)
        }
        assertEquals(VoucherStatus.CANCELLED, vouchers.findVoucher(draft.id)?.status)
        assertEquals(2, db.stockMovementDao().movementsForVoucher(draft.id).size)

        val restored = vouchers.restoreCancelled(
            draft.id,
            "Customer request",
            "Restore",
            restoredAt,
        )
        assertEquals(VoucherStatus.POSTED, restored.status)
        val duplicate = runCatching {
            vouchers.restoreCancelled(draft.id, "Customer request", "Restore again", restoredAt)
        }
        assertIs<IllegalArgumentException>(duplicate.exceptionOrNull())
        assertEquals(restoredAt, vouchers.findVoucher(draft.id)?.restoredAt)

        val deletedDraft = stockVoucher().copy(
            id = "voucher-deleted",
            lines = listOf(
                testLine(
                    id = "line-deleted",
                    itemId = "item-1",
                    quantity = Quantity.of(2),
                    rate = Money.of(5_000),
                    godownId = "godown-1",
                ).copy(
                    taxable = Money.of(10_000),
                    tax = Money.ZERO,
                    total = Money.of(10_000),
                ),
            ),
            charges = emptyList(),
        ).copy(
            subtotal = Money.of(10_000),
            taxable = Money.of(10_000),
            tax = Money.ZERO,
            total = Money.of(10_000),
        )
        vouchers.saveDraft(deletedDraft)
        vouchers.post(deletedDraft.id, postedAt)
        vouchers.cancel(deletedDraft.id, "Customer request", cancelledAt)
        val entity = assertNotNull(db.voucherDao().findById(deletedDraft.id)).let { stored ->
            stored.copy(
                sync = stored.sync.copy(
                    deletedAt = cancelledAt.toEpochMilliseconds(),
                    updatedAt = cancelledAt.toEpochMilliseconds(),
                ),
            )
        }
        db.voucherDao().update(entity)
        val deletedFailure = runCatching {
            vouchers.restoreCancelled(
                deletedDraft.id,
                "Customer request",
                "Restore",
                restoredAt,
            )
        }
        assertIs<IllegalArgumentException>(deletedFailure.exceptionOrNull())
    }

    @Test
    fun restorePaymentRevalidatesDetailsAndReactivatesAllocations(): Unit = runBlocking {
        seedMasterData()
        val sale = saveAndPost(
            testVoucher(
                id = "sale-1",
                lines = listOf(testLine(id = "sale-line-1")),
                charges = emptyList(),
            ),
        )
        val payment = postPayment("payment-1", 4_000)
        payments.allocate(
            payment.id,
            listOf(AllocationRequest(sale.id, Money.of(4_000))),
            postedAt,
        )
        assertEquals(Money.of(19_800), payments.invoiceOutstanding(sale.id))
        assertEquals(1, payments.observeAllocationsForPayment(payment.id).first().size)

        vouchers.cancel(payment.id, "Cheque bounced", cancelledAt)
        assertEquals(Money.of(23_800), payments.invoiceOutstanding(sale.id))
        assertEquals(
            Money.of(23_800),
            payments.observeInvoiceOutstanding(sale.id).first(),
        )
        assertEquals(1, payments.observeAllocationsForPayment(payment.id).first().size)
        assertEquals(Money.of(4_000), payments.allocatedTotalForPayment(payment.id))

        val restored = vouchers.restoreCancelled(
            payment.id,
            "Cheque bounced",
            "Replacement cheque cleared",
            restoredAt,
        )
        assertEquals(VoucherStatus.POSTED, restored.status)
        assertEquals(restoredAt, restored.restoredAt)
        assertEquals(Money.of(19_800), payments.invoiceOutstanding(sale.id))
        assertEquals(
            Money.of(19_800),
            payments.observeInvoiceOutstanding(sale.id).first(),
        )
        assertEquals(1, payments.observeAllocationsForPayment(payment.id).first().size)
        assertEquals(Money.of(4_000), payments.allocatedTotalForPayment(payment.id))
        assertEquals(6, db.ledgerDao().entriesForVoucher(payment.id).size)

        val details = assertNotNull(
            auditEvents(payment.id).single { it.action == "voucher.restored" }.detailsJson,
        )
        assertTrue(details.contains("\"cancellationReason\":\"Cheque bounced\""))
        assertTrue(details.contains("\"restoreReason\":\"Replacement cheque cleared\""))

        vouchers.cancel(payment.id, "Stopped again", secondCancelledAt)
        val cash = assertNotNull(ledger.findAccount("firm-1", AccountCode.CASH))
        ledger.saveAccount(
            cash.copy(
                deletedAt = secondCancelledAt,
                updatedAt = secondCancelledAt,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreCancelled(
                payment.id,
                "Stopped again",
                "Account is gone",
                restoredAt,
            )
        }
        assertEquals(VoucherStatus.CANCELLED, vouchers.findVoucher(payment.id)?.status)
    }

    @Test
    fun restorePaymentFailsWhenDetailsWereRemoved(): Unit = runBlocking {
        seedMasterData()
        val payment = postPayment("payment-1", 4_000)
        vouchers.cancel(payment.id, "Bounced", cancelledAt)
        db.paymentDetailsDao().delete(payment.id)

        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreCancelled(payment.id, "Bounced", "Clear", restoredAt)
        }
        assertEquals(VoucherStatus.CANCELLED, vouchers.findVoucher(payment.id)?.status)
    }

    private suspend fun saveAndPost(voucher: Voucher): Voucher {
        vouchers.saveDraft(voucher)
        return vouchers.post(voucher.id, postedAt)
    }

    private suspend fun postPayment(id: String, total: Long): Voucher {
        val draft = testPaymentVoucher(id = id, total = total)
        vouchers.saveDraft(draft)
        payments.savePaymentDetails(
            testPaymentDetails(draft.id, cashAccount().id),
        )
        return vouchers.post(draft.id, postedAt)
    }

    private suspend fun cashAccount(): Account =
        ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash

    private suspend fun seedMasterData() {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings(negativeStockAllowed = true))
        firms.saveFinancialYear(testFinancialYear())
        parties.saveParty(testParty())
        catalog.saveUnit(testUnit())
        catalog.saveCategory(testCategory())
        catalog.saveGodown(testGodown())
        catalog.saveItem(testItem())
    }

    private fun stockVoucher(): Voucher = testVoucher(
        id = "voucher-1",
        lines = listOf(
            testLine(
                id = "line-1",
                itemId = "item-1",
                quantity = Quantity.of(2),
                rate = Money.of(5_000),
                godownId = "godown-1",
            ).copy(
                taxable = Money.of(10_000),
                tax = Money.ZERO,
                total = Money.of(10_000),
            ),
        ),
        charges = emptyList(),
    ).copy(
        subtotal = Money.of(10_000),
        taxable = Money.of(10_000),
        tax = Money.ZERO,
        total = Money.of(10_000),
    )

    private suspend fun auditEvents(voucherId: String): List<AuditEventEntity> =
        db.auditDao().observeForEntity("firm-1", "voucher", voucherId).first()
}
