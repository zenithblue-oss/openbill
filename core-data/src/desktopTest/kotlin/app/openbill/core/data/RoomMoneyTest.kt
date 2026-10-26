package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.domain.usecase.MoneyVoucherInput
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomMoneyTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private lateinit var vouchers: RoomVoucherRepository

    @BeforeTest
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
        val runner = DatabaseTransactionRunner(db)
        ledger = RoomLedgerRepository(db, runner)
        payments = RoomPaymentRepository(db, runner)
        vouchers = RoomVoucherRepository(db, runner, ledger)
        val firms = RoomFirmRepository(db, runner)
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings())
        firms.saveFinancialYear(testFinancialYear())
        RoomPartyRepository(db, runner).saveParty(testParty(addresses = emptyList()))
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun input(type: VoucherType, id: String, amount: Long, account: String) = MoneyVoucherInput(
        type = type, id = id, firmId = "firm-1", financialYearId = "fy-1", stateCode = "27",
        date = LocalDate(2026, 5, 20), amount = Money.of(amount), accountId = account,
    )

    @Test
    fun paymentExpenseContraAndReconciliationThroughRoom(): Unit = runBlocking {
        val money = MoneyVouchers(vouchers, payments, ledger)
        val cash = ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        val bank = Account("bank", "firm-1", "HDFC", AccountType.BANK, openingBalance = Money.of(50_000), createdAt = CREATED_AT, updatedAt = CREATED_AT)
        val rent = Account("rent", "firm-1", "Rent", AccountType.EXPENSE, createdAt = CREATED_AT, updatedAt = CREATED_AT)
        ledger.saveAccount(bank)
        ledger.saveAccount(rent)
        val line = testLine(id = "l1", rate = Money.of(10_000)).copy(taxable = Money.of(10_000), tax = Money.ZERO, total = Money.of(10_000))
        vouchers.saveDraft(
            testVoucher(id = "s1", lines = listOf(line), charges = emptyList())
                .copy(subtotal = Money.of(10_000), taxable = Money.of(10_000), tax = Money.ZERO, total = Money.of(10_000)),
        )
        vouchers.post("s1", POSTED_AT)

        val alloc = MoneyVouchers.fifo(payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE), Money.of(4_000))
        money.record(
            input(VoucherType.PAYMENT_IN, "r1", 4_000, bank.id).copy(
                partyId = "party-1", method = PaymentMethod.UPI, instrumentNumber = "UTR9", allocations = alloc,
            ),
            POSTED_AT,
        )
        money.record(input(VoucherType.EXPENSE, "e1", 1_180, cash.id).copy(ledgerAccountId = rent.id, inputTax = Money.of(180)), POSTED_AT)
        money.record(input(VoucherType.CONTRA, "c1", 2_000, bank.id).copy(ledgerAccountId = cash.id), POSTED_AT)

        assertEquals(Money.of(6_000), payments.invoiceOutstanding("s1"))
        val balances = ledger.observeAccountBalances("firm-1").first()
        assertEquals(Money.of(50_000 + 4_000 - 2_000), balances["bank"])
        assertEquals(Money.of(2_000 - 1_180), balances[cash.id])
        assertEquals(Money.of(1_000), balances["rent"])

        // Party: opening 12,345 debit + sale 10,000 - receipt 4,000.
        val movements = ledger.observePartyMovements("firm-1").first()
        val ageing = PartyAgeing.of(Money.of(12_345), movements.filter { it.partyId == "party-1" }, LocalDate(2026, 5, 31))
        assertEquals(ledger.partyBalance("firm-1", "party-1", LocalDate(2026, 5, 31)).net, ageing.total)
        assertEquals(Money.of(18_345), ageing.total)

        val txns = ledger.observeAccountTransactions("bank").first()
        assertEquals(listOf(VoucherType.PAYMENT_IN, VoucherType.CONTRA), txns.map { it.voucherType })
        ledger.setCleared(txns.first().entry.id, LocalDate(2026, 5, 22))
        assertEquals(LocalDate(2026, 5, 22), ledger.observeAccountTransactions("bank").first().first().entry.clearedOn)
        ledger.setCleared(txns.first().entry.id, null)
        assertNull(ledger.observeAccountTransactions("bank").first().first().entry.clearedOn)
        assertEquals(balances, ledger.observeAccountBalances("firm-1").first())
    }
}
