package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomPaymentRepositoryTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private lateinit var vouchers: RoomVoucherRepository
    private lateinit var firms: RoomFirmRepository
    private lateinit var parties: RoomPartyRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val runner = DatabaseTransactionRunner(db)
        ledger = RoomLedgerRepository(db, runner)
        payments = RoomPaymentRepository(db, runner)
        vouchers = RoomVoucherRepository(db, runner, ledger)
        firms = RoomFirmRepository(db, runner)
        parties = RoomPartyRepository(db, runner)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun detailsRoundTripNormalizeAndDraftDeletion(): Unit = runBlocking {
        seedMasterData()
        val draft = testPaymentVoucher()
        vouchers.saveDraft(draft)
        val cash = cashAccount()

        payments.savePaymentDetails(
            testPaymentDetails(draft.id, cash.id, reference = "  REF-1  "),
        )

        val saved = assertNotNull(payments.findPaymentDetails(draft.id))
        assertEquals("REF-1", saved.reference)
        assertEquals(PaymentMethod.CASH, saved.method)
        assertEquals(saved, payments.observePaymentDetails(draft.id).first())

        payments.savePaymentDetails(
            saved.copy(notes = "  Paid in full  ", updatedAt = CANCELLED_AT),
        )
        val updated = assertNotNull(payments.findPaymentDetails(draft.id))
        assertEquals("Paid in full", updated.notes)
        assertEquals(saved.createdAt, updated.createdAt)

        payments.deletePaymentDetails(draft.id)
        assertNull(payments.findPaymentDetails(draft.id))

        payments.savePaymentDetails(testPaymentDetails(draft.id, cash.id))
        vouchers.post(draft.id, POSTED_AT)
        payments.deletePaymentDetails(draft.id)
        assertNotNull(payments.findPaymentDetails(draft.id))
    }

    @Test
    fun paymentDetailsRequireADraftPaymentAndAValidPaymentAccount(): Unit = runBlocking {
        seedMasterData()
        val cash = cashAccount()
        val draft = testPaymentVoucher()
        vouchers.saveDraft(draft)

        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(
                testPaymentDetails(
                    draft.id,
                    ledger.ensureSystemAccounts("firm-1", CREATED_AT).sales.id,
                ),
            )
        }
        firms.saveFirm(testFirm(id = "firm-2", name = "Other Firm"))
        val otherFirm = Account(
            id = "other-firm-bank",
            firmId = "firm-2",
            name = "Other Bank",
            type = AccountType.BANK,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )
        ledger.saveAccount(otherFirm)
        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(testPaymentDetails(draft.id, otherFirm.id))
        }
        val deleted = Account(
            id = "deleted-bank",
            firmId = "firm-1",
            name = "Deleted Bank",
            type = AccountType.BANK,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
            deletedAt = CREATED_AT,
        )
        ledger.saveAccount(deleted)
        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(testPaymentDetails(draft.id, deleted.id))
        }
        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(
                testPaymentDetails(draft.id, cash.id).copy(firmId = "firm-2"),
            )
        }
        val saleDraft = testVoucher(
            id = "sale-draft",
            lines = listOf(testLine(id = "line-sale-draft")),
            charges = emptyList(),
        )
        vouchers.saveDraft(saleDraft)
        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(testPaymentDetails(saleDraft.id, cash.id))
        }
        assertFailsWith<IllegalArgumentException> {
            vouchers.post(draft.id, POSTED_AT)
        }

        val postedPayment = testPaymentVoucher(id = "payment-posted", total = 5_000L)
        vouchers.saveDraft(postedPayment)
        val postedDetails = testPaymentDetails(postedPayment.id, cash.id)
        payments.savePaymentDetails(postedDetails)
        vouchers.post(postedPayment.id, POSTED_AT)
        assertFailsWith<IllegalArgumentException> {
            payments.savePaymentDetails(postedDetails.copy(notes = "After posting"))
        }
        assertNotNull(cash)
    }

    @Test
    fun allocationTracksOutstandingAndPersistsAcrossQueries(): Unit = runBlocking {
        seedMasterData()
        val invoice = postSale("sale-1", 23_800L)
        val payment = postPayment("payment-1", 20_000L)
        suspend fun allocate(invoiceId: String, amount: Long) = payments.allocate(
            payment.id,
            listOf(AllocationRequest(invoiceId, Money.of(amount))),
            POSTED_AT,
        )

        val allocation = allocate(invoice.id, 10_000L).single()

        assertEquals(invoice.id, allocation.invoiceVoucherId)
        assertEquals(Money.of(10_000L), payments.allocatedTotalForPayment(payment.id))
        assertEquals(Money.of(13_800L), payments.invoiceOutstanding(invoice.id))
        assertEquals(
            Money.of(13_800L),
            payments.observeInvoiceOutstanding(invoice.id).first(),
        )
        assertEquals(
            listOf(allocation.id),
            payments.observeAllocationsForInvoice(invoice.id).first().map { it.id },
        )
        assertEquals(
            listOf(allocation.id),
            payments.observeAllocationsForPayment(payment.id).first().map { it.id },
        )
        val outstanding = payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE)
        assertEquals(1, outstanding.size)
        assertEquals(Money.of(10_000L), outstanding.single().allocated)
        assertEquals(Money.of(13_800L), outstanding.single().outstanding)
        assertTrue(
            db.auditDao().observeForEntity("firm-1", "voucher", payment.id).first()
                .any { it.action == "payment.allocated" },
        )

        val exactPayment = postPayment("payment-2", 13_800L)
        payments.allocate(
            exactPayment.id,
            listOf(AllocationRequest(invoice.id, Money.of(13_800L))),
            POSTED_AT,
        )
        assertEquals(Money.of(0L), payments.invoiceOutstanding(invoice.id))
        assertTrue(payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE).isEmpty())
    }

    @Test
    fun allocationRejectsInvalidRequestsInvoicesAndPairs(): Unit = runBlocking {
        seedMasterData()
        parties.saveParty(
            testParty(id = "party-2", name = "Beta Supplies", addresses = emptyList()),
        )
        val invoice = postSale("sale-1", 23_800L)
        val second = postSale("sale-2", 10_000L)
        val purchase = postSale("purchase-1", 10_000L, type = VoucherType.PURCHASE)
        val otherParty = postSale("sale-other", 10_000L, partyId = "party-2")
        val cancelledInvoice = postSale("sale-cancelled", 10_000L)
        vouchers.cancel(
            cancelledInvoice.id,
            "Void",
            Instant.parse("2026-05-20T20:00:00Z"),
        )
        val payment = postPayment("payment-1", 30_000L)
        val smallPayment = postPayment("payment-2", 5_000L)
        suspend fun allocate(paymentId: String, invoiceId: String, amount: Long) =
            payments.allocate(
                paymentId,
                listOf(AllocationRequest(invoiceId, Money.of(amount))),
                POSTED_AT,
            )

        assertFailsWith<IllegalArgumentException> {
            payments.allocate(
                payment.id,
                listOf(
                    AllocationRequest(invoice.id, Money.of(20_000L)),
                    AllocationRequest(second.id, Money.of(10_001L)),
                ),
                POSTED_AT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(smallPayment.id, invoice.id, 5_001L)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, invoice.id, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, invoice.id, -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            payments.allocate(
                payment.id,
                listOf(
                    AllocationRequest(invoice.id, Money.of(1_000L)),
                    AllocationRequest(invoice.id, Money.of(1_000L)),
                ),
                POSTED_AT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, purchase.id, 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, otherParty.id, 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, cancelledInvoice.id, 1_000L)
        }
        assertTrue(payments.observeAllocationsForPayment(payment.id).first().isEmpty())

        allocate(payment.id, invoice.id, 1_000L)
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, invoice.id, 1_000L)
        }
        assertEquals(1, payments.observeAllocationsForPayment(payment.id).first().size)
    }

    @Test
    fun cancelledPaymentRowsSurviveButStopCounting(): Unit = runBlocking {
        seedMasterData()
        val invoice = postSale("sale-1", 23_800L)
        val payment = postPayment("payment-1", 20_000L)
        payments.allocate(
            payment.id,
            listOf(AllocationRequest(invoice.id, Money.of(10_000L))),
            POSTED_AT,
        )
        assertEquals(Money.of(13_800L), payments.invoiceOutstanding(invoice.id))

        vouchers.cancel(
            payment.id,
            "Bounced",
            Instant.parse("2026-05-20T20:00:00Z"),
        )

        assertEquals(1, payments.observeAllocationsForPayment(payment.id).first().size)
        assertEquals(Money.of(10_000L), payments.allocatedTotalForPayment(payment.id))
        assertEquals(0, db.paymentAllocationDao().effectiveTotalAllocatedForInvoice(invoice.id))
        assertEquals(Money.of(23_800L), payments.invoiceOutstanding(invoice.id))
        assertEquals(
            Money.of(23_800L),
            payments.observeInvoiceOutstanding(invoice.id).first(),
        )
        val outstanding = payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE)
        assertEquals(Money.ZERO, outstanding.single().allocated)
    }

    @Test
    fun outstandingOnlyIncludesPostedNotDeletedMatchingPartyAndType(): Unit = runBlocking {
        seedMasterData()
        parties.saveParty(
            testParty(id = "party-2", name = "Beta Supplies", addresses = emptyList()),
        )
        val partial = postSale("sale-1", 10_000L)
        val exact = postSale("sale-2", 5_000L)
        postSale("sale-other", 7_000L, partyId = "party-2")
        postSale("purchase-1", 9_000L, type = VoucherType.PURCHASE)
        vouchers.saveDraft(
            testVoucher(
                id = "sale-draft",
                lines = listOf(testLine(id = "line-draft")),
                charges = emptyList(),
            ),
        )
        val payment = postPayment("payment-1", 10_000L)
        payments.allocate(
            payment.id,
            listOf(AllocationRequest(exact.id, Money.of(5_000L))),
            POSTED_AT,
        )

        val outstanding = payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE)
        assertEquals(listOf(partial.id), outstanding.map { it.voucherId })
        assertEquals(Money.of(10_000L), outstanding.single().outstanding)
        assertEquals(
            listOf("purchase-1"),
            payments.outstandingInvoices("firm-1", "party-1", VoucherType.PURCHASE)
                .map { it.voucherId },
        )
        assertFailsWith<IllegalArgumentException> {
            payments.outstandingInvoices("firm-1", "party-1", VoucherType.CREDIT_NOTE)
        }
    }

    private suspend fun postSale(
        id: String,
        total: Long,
        partyId: String = "party-1",
        type: VoucherType = VoucherType.SALE,
    ): Voucher {
        val line = testLine(id = "line-$id", rate = Money.of(total)).copy(
            taxable = Money.of(total),
            tax = Money.ZERO,
            total = Money.of(total),
        )
        val draft = testVoucher(
            id = id,
            type = type,
            partyId = partyId,
            lines = listOf(line),
            charges = emptyList(),
        ).copy(
            subtotal = Money.of(total),
            taxable = Money.of(total),
            tax = Money.ZERO,
            roundOff = Money.ZERO,
            total = Money.of(total),
        )
        vouchers.saveDraft(draft)
        return vouchers.post(id, POSTED_AT)
    }

    private suspend fun postPayment(id: String, total: Long): Voucher {
        val draft = testPaymentVoucher(id = id, total = total)
        vouchers.saveDraft(draft)
        payments.savePaymentDetails(
            testPaymentDetails(draft.id, cashAccount().id),
        )
        return vouchers.post(id, POSTED_AT)
    }

    private suspend fun cashAccount() =
        ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash

    private suspend fun seedMasterData() {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings())
        firms.saveFinancialYear(testFinancialYear())
        parties.saveParty(testParty())
    }
}
