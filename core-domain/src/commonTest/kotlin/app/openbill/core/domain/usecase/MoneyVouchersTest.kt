package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryLedgerRepository
import app.openbill.core.domain.fake.InMemoryPartyRepository
import app.openbill.core.domain.fake.InMemoryPaymentRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
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

class MoneyVouchersTest {
    private val at = Instant.parse("2026-05-01T00:00:00Z")
    private val parties = InMemoryPartyRepository()
    private val ledger = InMemoryLedgerRepository(parties)
    private val payments = InMemoryPaymentRepository()
    private val vouchers = InMemoryVoucherRepository(ledger = ledger, payments = payments)
    private val money = MoneyVouchers(vouchers, payments, ledger)

    init {
        ledger.attachVouchers(vouchers)
        payments.attachVouchers(vouchers)
    }

    private suspend fun setup(): Pair<Account, Account> {
        parties.saveParty(
            Party(
                id = "p1", firmId = "f1", name = "Asha", type = PartyType.CUSTOMER,
                openingBalance = Money.ZERO, openingBalanceType = OpeningBalanceType.DEBIT,
                createdAt = at, updatedAt = at,
            ),
        )
        val cash = ledger.ensureSystemAccounts("f1", at).cash
        val bank = Account("bank", "f1", "HDFC", AccountType.BANK, openingBalance = Money.of(100_000), createdAt = at, updatedAt = at)
        ledger.saveAccount(bank)
        return cash to bank
    }

    private suspend fun sale(id: String, total: Long, date: LocalDate): Voucher {
        vouchers.saveDraft(
            Voucher(
                id = id, firmId = "f1", financialYearId = "fy", type = VoucherType.SALE, date = date,
                partyId = "p1", placeOfSupplyStateCode = "27",
                lines = listOf(VoucherLine("$id-l", 1, description = "x", quantity = Quantity.of(1), rate = Money.of(total), taxable = Money.of(total), total = Money.of(total))),
                taxable = Money.of(total), total = Money.of(total), createdAt = at, updatedAt = at,
            ),
        )
        return PostVoucher(vouchers)(id, at)
    }

    private fun input(type: VoucherType, id: String, amount: Long, account: String) = MoneyVoucherInput(
        type = type, id = id, firmId = "f1", financialYearId = "fy", stateCode = "27",
        date = LocalDate(2026, 6, 1), amount = Money.of(amount), accountId = account,
    )

    @Test
    fun paymentInAllocatesFifoAndKeepsRemainderAsAdvance(): Unit = runBlocking {
        val (cash, _) = setup()
        sale("s1", 10_000, LocalDate(2026, 5, 1))
        sale("s2", 5_000, LocalDate(2026, 5, 5))
        val open = payments.outstandingInvoices("f1", "p1", VoucherType.SALE)
        val alloc = MoneyVouchers.fifo(open, Money.of(12_000))
        assertEquals(listOf(Money.of(10_000), Money.of(2_000)), alloc.map { it.amount })

        val posted = money.record(input(VoucherType.PAYMENT_IN, "r1", 20_000, cash.id).copy(partyId = "p1", allocations = alloc), at)
        assertEquals(VoucherStatus.POSTED, posted.status)
        assertEquals(Money.ZERO, payments.invoiceOutstanding("s1"))
        assertEquals(Money.of(3_000), payments.invoiceOutstanding("s2"))
        assertEquals(Money.of(12_000), payments.allocatedTotalForPayment("r1"))
        // Party owes 15,000 and paid 20,000: 5,000 advance.
        val movements = ledger.observePartyMovements("f1").first()
        assertEquals(Money.of(-5_000), movements.fold(Money.ZERO) { t, m -> t + m.net })
        assertEquals(Money.of(20_000), ledger.observeAccountBalances("f1").first()[cash.id])
    }

    @Test
    fun overAllocationIsRejectedBeforeAnythingIsSaved(): Unit = runBlocking {
        val (cash, _) = setup()
        sale("s1", 1_000, LocalDate(2026, 5, 1))
        val bad = MoneyVouchers.fifo(payments.outstandingInvoices("f1", "p1", VoucherType.SALE), Money.of(1_000))
            .map { it.copy(amount = Money.of(1_500)) }
        assertFailsWith<IllegalArgumentException> {
            money.record(input(VoucherType.PAYMENT_IN, "r1", 2_000, cash.id).copy(partyId = "p1", allocations = bad), at)
        }
        assertNull(vouchers.findVoucher("r1"))
    }

    @Test
    fun expenseWithItcAndContraPostBalancedEntries(): Unit = runBlocking {
        val (cash, bank) = setup()
        val rent = Account("rent", "f1", "Rent", AccountType.EXPENSE, createdAt = at, updatedAt = at)
        ledger.saveAccount(rent)
        money.record(input(VoucherType.EXPENSE, "e1", 11_800, bank.id).copy(ledgerAccountId = rent.id, inputTax = Money.of(1_800), method = PaymentMethod.UPI, instrumentNumber = "UTR1"), at)
        money.record(input(VoucherType.CONTRA, "c1", 5_000, bank.id).copy(ledgerAccountId = cash.id, method = PaymentMethod.CHEQUE, instrumentNumber = "000123", instrumentDate = LocalDate(2026, 6, 1)), at)
        val balances = ledger.observeAccountBalances("f1").first()
        val itc = ledger.ensureSystemAccounts("f1", at).inputGst
        assertEquals(Money.of(10_000), balances["rent"])
        assertEquals(Money.of(1_800), balances[itc.id])
        assertEquals(Money.of(100_000 - 11_800 - 5_000), balances["bank"])
        assertEquals(Money.of(5_000), balances[cash.id])
        // Contra to the same account and expense to a non-expense account are rejected.
        assertFailsWith<IllegalArgumentException> {
            money.record(input(VoucherType.CONTRA, "c2", 1, bank.id).copy(ledgerAccountId = bank.id), at)
        }
        assertFailsWith<IllegalArgumentException> {
            money.record(input(VoucherType.EXPENSE, "e2", 1, bank.id).copy(ledgerAccountId = cash.id), at)
        }
        assertNull(vouchers.findVoucher("c2"))
    }

    @Test
    fun reconciliationMarksAndUndoesWithoutTouchingAmounts(): Unit = runBlocking {
        val (_, bank) = setup()
        val rent = Account("rent", "f1", "Rent", AccountType.EXPENSE, createdAt = at, updatedAt = at)
        ledger.saveAccount(rent)
        money.record(input(VoucherType.EXPENSE, "e1", 500, bank.id).copy(ledgerAccountId = rent.id), at)
        val entry = ledger.observeAccountTransactions(bank.id).first().single().entry
        ledger.setCleared(entry.id, LocalDate(2026, 6, 3))
        assertEquals(LocalDate(2026, 6, 3), ledger.observeAccountTransactions(bank.id).first().single().entry.clearedOn)
        ledger.setCleared(entry.id, null)
        val after = ledger.observeAccountTransactions(bank.id).first().single().entry
        assertNull(after.clearedOn)
        assertEquals(entry.copy(clearedOn = null), after)
    }

    @Test
    fun quotationIsIssuedWithANumberAndNoLedger(): Unit = runBlocking {
        setup()
        vouchers.saveDraft(
            Voucher(
                id = "q1", firmId = "f1", financialYearId = "fy", type = VoucherType.QUOTATION, date = LocalDate(2026, 5, 1),
                partyId = "p1", placeOfSupplyStateCode = "27",
                lines = listOf(VoucherLine("q1-l", 1, description = "x", quantity = Quantity.of(1), rate = Money.of(100), taxable = Money.of(100), total = Money.of(100))),
                taxable = Money.of(100), total = Money.of(100), createdAt = at, updatedAt = at,
            ),
        )
        val issued = PostVoucher(vouchers)("q1", at)
        assertNotNull(issued.number)
        assertTrue(ledger.journalEntriesForVoucher("q1").isEmpty())
    }

    @Test
    fun ageingIsFifoAndSumsToTheBalance() {
        val asOf = LocalDate(2026, 6, 30)
        fun m(date: LocalDate, net: Long) = PartyMovement("p", "v", VoucherType.SALE, null, date, Money.of(net))
        val buckets = PartyAgeing.of(
            Money.of(1_000), // opening: oldest
            listOf(
                m(LocalDate(2026, 3, 1), 4_000), // 121 days
                m(LocalDate(2026, 5, 10), 3_000), // 51 days
                m(LocalDate(2026, 6, 20), 2_000), // 10 days
                m(LocalDate(2026, 6, 25), -2_500), // settles opening and 1,500 of March
                m(LocalDate(2026, 7, 5), 9_999), // after asOf: ignored
            ),
            asOf,
        )
        assertEquals(Money.of(2_500), buckets.over90)
        assertEquals(Money.ZERO, buckets.days61To90)
        assertEquals(Money.of(3_000), buckets.days31To60)
        assertEquals(Money.of(2_000), buckets.upTo30)
        assertEquals(Money.of(7_500), buckets.total)
        // An advance larger than everything owed leaves a negative (credit) balance.
        val advance = PartyAgeing.of(Money.ZERO, listOf(m(asOf, 100), m(asOf, -300)), asOf)
        assertEquals(Money.of(-200), advance.upTo30)
    }
}
