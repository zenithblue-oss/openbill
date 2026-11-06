package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.usecase.PosCheckout
import app.openbill.core.domain.usecase.PosTender
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RoomPosTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repos: Repositories
    private var ids = 0

    @BeforeTest
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
        repos = db.roomRepositories()
        repos.firms.saveFirm(testFirm())
        repos.firms.saveSettings(testSettings(negativeStockAllowed = true))
        repos.firms.saveFinancialYear(testFinancialYear())
        repos.parties.saveParty(testParty(addresses = emptyList()))
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun splitTenderWithChangeSettlesTheInvoiceInOneGo(): Unit = runBlocking {
        val ledger = repos.ledger as RoomLedgerRepository
        val cash = ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        val bank = Account("bank", "firm-1", "Bank", AccountType.BANK, createdAt = CREATED_AT, updatedAt = CREATED_AT)
        ledger.saveAccount(bank)
        val line = testLine(id = "pos-l", rate = Money.of(10_000)).copy(itemId = null, taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800))
        repos.vouchers.saveDraft(
            testVoucher(id = "pos", lines = listOf(line), charges = emptyList())
                .copy(subtotal = Money.of(10_000), taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800)),
        )
        val post = PostVoucher(repos.vouchers)

        val tenders = listOf(PosTender(PaymentMethod.CASH, cash.id, Money.parse("100.00")), PosTender(PaymentMethod.UPI, bank.id, Money.parse("50.00"), "UTR1"))
        val settlement = PosCheckout.settle(Money.of(11_800), tenders, creditAllowed = false)
        val (invoice, made) = PosCheckout.postAndSettle(repos, post, "pos", "27", tenders, settlement, { "pay-${ids++}" }, POSTED_AT)
        assertEquals(VoucherStatus.POSTED, invoice.status)
        assertEquals(2, made.size)
        assertTrue(repos.payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE).isEmpty(), "fully paid")
        val balances = repos.ledger.observeAccountBalances("firm-1").first()
        assertEquals(Money.parse("68.00"), balances[cash.id], "cash net of 32.00 change")
        assertEquals(Money.parse("50.00"), balances[bank.id])
    }

    @Test
    fun failedPaymentLeavesTheBillAnUnpostedDraft(): Unit = runBlocking {
        val cash = (repos.ledger as RoomLedgerRepository).ensureSystemAccounts("firm-1", CREATED_AT).cash
        val line = testLine(id = "pos-l", rate = Money.of(10_000)).copy(itemId = null, taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800))
        repos.vouchers.saveDraft(
            testVoucher(id = "pos", lines = listOf(line), charges = emptyList())
                .copy(subtotal = Money.of(10_000), taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800)),
        )
        val tenders = listOf(PosTender(PaymentMethod.CASH, cash.id, Money.of(5_000)), PosTender(PaymentMethod.CASH, cash.id, Money.of(6_800)))
        val settlement = PosCheckout.settle(Money.of(11_800), tenders, creditAllowed = false)
        var calls = 0
        assertFailsWith<IllegalStateException> {
            PosCheckout.postAndSettle(repos, PostVoucher(repos.vouchers), "pos", "27", tenders, settlement, { if (calls++ == 0) "pay-0" else error("disk full") }, POSTED_AT)
        }
        val bill = repos.vouchers.findVoucher("pos")!!
        assertEquals(VoucherStatus.DRAFT, bill.status, "invoice post rolled back")
        assertEquals(null, bill.number)
        assertEquals(null, repos.vouchers.findVoucher("pay-0"), "first payment rolled back")
        assertEquals(null, repos.ledger.observeAccountBalances("firm-1").first()[cash.id]?.takeIf { it != Money.ZERO })
    }
}
