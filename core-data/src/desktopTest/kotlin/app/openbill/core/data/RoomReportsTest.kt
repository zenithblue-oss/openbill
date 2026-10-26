package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.report.loadReportData
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.usecase.BooksLock
import app.openbill.core.domain.usecase.MoneyVoucherInput
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomReportsTest {
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

    private suspend fun sale(id: String, date: LocalDate, fy: String = "fy-1") {
        val line = testLine(id = "$id-l", rate = Money.of(10_000)).copy(itemId = null, taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800))
        repos.vouchers.saveDraft(
            testVoucher(id = id, date = date, financialYearId = fy, lines = listOf(line), charges = emptyList())
                .copy(subtotal = Money.of(10_000), taxable = Money.of(10_000), tax = Money.of(1_800), total = Money.of(11_800)),
        )
    }

    @Test
    fun reportsTieToRoomLedger(): Unit = runBlocking {
        val ledger = repos.ledger as RoomLedgerRepository
        val cash = ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        val rent = Account("rent", "firm-1", "Rent", AccountType.EXPENSE, createdAt = CREATED_AT, updatedAt = CREATED_AT)
        ledger.saveAccount(rent)
        sale("s1", LocalDate(2026, 5, 10))
        repos.vouchers.post("s1", POSTED_AT)
        MoneyVouchers(repos.vouchers, repos.payments, repos.ledger).record(
            MoneyVoucherInput(
                type = VoucherType.EXPENSE, id = "e1", firmId = "firm-1", financialYearId = "fy-1", stateCode = "27",
                date = LocalDate(2026, 5, 20), amount = Money.of(1_180), accountId = cash.id, ledgerAccountId = rent.id, inputTax = Money.of(180),
            ),
            POSTED_AT,
        )
        val data = repos.loadReportData("firm-1", ReportPeriod.month("2026-05"))
        val register = Reports.run(ReportKind.SALES_REGISTER, data)
        val sales = repos.ledger.observeAccountBalances("firm-1").first()[ledger.findAccount("firm-1", app.openbill.core.domain.model.AccountCode.SALES)!!.id]!!
        assertEquals(-sales, Money.parse(register.totals!![register.columns.indexOfFirst { it.key == "taxable" }]))
        val expenses = Reports.run(ReportKind.EXPENSES, data)
        assertEquals(listOf("Rent", "1", "10.00", "1.80", "11.80"), expenses.rows.single())
        val pl = Reports.run(ReportKind.PROFIT_LOSS, data)
        assertEquals("90.00", pl.totals!![1]) // 100.00 sales - 10.00 rent
        val book = Reports.run(ReportKind.CASH_BANK_BOOK, data)
        assertEquals("-11.80", book.totals!![7])
        val gst3b = Reports.run(ReportKind.GSTR3B, data)
        assertEquals("0.90", gst3b.rows[4][3]) // ITC CGST half of 1.80
    }

    @Test
    fun lockDateAndYearCloseBlockPostingAndAreAudited(): Unit = runBlocking {
        val lock = BooksLock(repos.firms, repos.vouchers) { "fy-new-${ids++}" }
        sale("s1", LocalDate(2026, 5, 10))
        repos.vouchers.post("s1", POSTED_AT)
        sale("s2", LocalDate(2026, 5, 15))
        lock.setLockDate("firm-1", LocalDate(2026, 5, 31))
        assertFailsWith<IllegalArgumentException> { repos.vouchers.post("s2", POSTED_AT) }
        assertFailsWith<IllegalArgumentException> { repos.vouchers.cancel("s1", "Wrong", POSTED_AT) }
        lock.setLockDate("firm-1", null)
        repos.vouchers.post("s2", POSTED_AT)

        val closedAt = Instant.fromEpochMilliseconds(POSTED_MILLIS + 1_000)
        lock.closeYear("fy-1", closedAt)
        val years = repos.firms.observeFinancialYears("firm-1").first()
        assertEquals(LocalDate(2027, 4, 1), years.first().startDate) // next year created
        assertFailsWith<IllegalArgumentException> { repos.vouchers.cancel("s2", "Wrong", POSTED_AT) }
        lock.reopenYear("fy-1")
        lock.closeYear("fy-1", closedAt)
        sale("n1", LocalDate(2027, 4, 5), years.first().id)
        repos.vouchers.post("n1", Instant.fromEpochMilliseconds(POSTED_MILLIS + 2_000))
        assertFailsWith<IllegalArgumentException> { lock.reopenYear("fy-1") }

        val actions = repos.audit.observeRecent("firm-1", 100).first().map { it.action }
        assertTrue("books.lock_changed" in actions)
        assertTrue("financial_year.closed" in actions && "financial_year.reopened" in actions)
        assertTrue("voucher.posted" in actions)
    }
}
