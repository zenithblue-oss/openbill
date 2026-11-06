package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.PaymentMethod
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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

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
        post("pos", POSTED_AT)

        val tenders = listOf(PosTender(PaymentMethod.CASH, cash.id, Money.parse("100.00")), PosTender(PaymentMethod.UPI, bank.id, Money.parse("50.00"), "UTR1"))
        val settlement = PosCheckout.settle(Money.of(11_800), tenders, creditAllowed = false)
        val made = PosCheckout.recordPayments(
            repos, post, "pos", "party-1", "firm-1", "fy-1", "27", LocalDate(2026, 5, 10), tenders, settlement, { "pay-${ids++}" }, POSTED_AT,
        )
        assertEquals(2, made.size)
        assertTrue(repos.payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE).isEmpty(), "fully paid")
        val balances = repos.ledger.observeAccountBalances("firm-1").first()
        assertEquals(Money.parse("68.00"), balances[cash.id], "cash net of 32.00 change")
        assertEquals(Money.parse("50.00"), balances[bank.id])
    }
}
