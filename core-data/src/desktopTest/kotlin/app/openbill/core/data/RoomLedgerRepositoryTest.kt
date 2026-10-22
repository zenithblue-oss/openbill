package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomLedgerRepositoryTest {
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
    fun systemAccountsAreIdempotentAndCodeAddressed(): Unit = runBlocking {
        seedMasterData()
        val first = ledger.ensureSystemAccounts("firm-1", CREATED_AT)
        val second = ledger.ensureSystemAccounts("firm-1", CREATED_AT)
        val third = ledger.ensureSystemAccounts("firm-1", UPDATED_AT)

        assertEquals(first, second)
        assertEquals(first, third)
        AccountCode.entries.forEach { code ->
            assertEquals(code, ledger.findAccount("firm-1", code)?.code)
            assertEquals(code, first.accountFor(code).code)
        }
        assertEquals(
            AccountCode.entries.size,
            ledger.observeAccounts("firm-1").first().size,
        )
        assertEquals(AccountType.CASH, first.cash.type)
        assertEquals(AccountType.EXPENSE, first.roundOff.type)
    }

    @Test
    fun systemAccountCreationRejectsANameConflictWithoutPartialCreation(): Unit = runBlocking {
        seedMasterData()
        ledger.saveAccount(
            Account(
                id = "custom-sales",
                firmId = "firm-1",
                name = "Sales",
                type = AccountType.INCOME,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ledger.ensureSystemAccounts("firm-1", CREATED_AT)
        }
        assertEquals(
            listOf("custom-sales"),
            ledger.observeAccounts("firm-1").first().map { it.id },
        )
        assertNull(ledger.findAccount("firm-1", AccountCode.AR))
    }

    @Test
    fun saleAndPaymentJournalEntriesDrivePartyLedgerAndBalance(): Unit = runBlocking {
        seedMasterData()
        val cash = ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        val sale = testVoucher()
        vouchers.saveDraft(sale)
        vouchers.post(sale.id, POSTED_AT)

        val payment = testPaymentVoucher(total = 10_000L)
        vouchers.saveDraft(payment)
        payments.savePaymentDetails(
            testPaymentDetails(payment.id, cash.id, reference = "  REF-1  "),
        )
        vouchers.post(payment.id, POSTED_AT)

        assertEquals(
            "REF-1",
            payments.findPaymentDetails(payment.id)?.reference,
        )
        val saleEntries = db.ledgerDao().entriesForVoucher(sale.id)
        assertEquals(3, saleEntries.size)
        assertEquals(
            saleEntries.sumOf { it.debit },
            saleEntries.sumOf { it.credit },
        )
        assertEquals(2, db.ledgerDao().entriesForVoucher(payment.id).size)

        val full = ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 1),
            LocalDate(2026, 5, 31),
        ).first()
        assertEquals(Money.of(12_345L), full.openingBalance)
        assertEquals(Money.of(26_145L), full.closingBalance)
        assertEquals(2, full.entries.size)
        assertEquals(AccountCode.AR, full.entries.first().accountCode)
        assertEquals(Money.of(23_800L), full.entries.first().debit)
        assertEquals(Money.of(10_000L), full.entries.last().credit)

        val later = ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 11),
            LocalDate(2026, 5, 31),
        ).first()
        assertEquals(Money.of(36_145L), later.openingBalance)
        assertEquals(1, later.entries.size)
        assertEquals(Money.of(26_145L), later.closingBalance)

        val balance = ledger.partyBalance("firm-1", "party-1", LocalDate(2026, 5, 31))
        assertEquals(Money.of(36_145L), balance.debit)
        assertEquals(Money.of(10_000L), balance.credit)
        assertEquals(Money.of(26_145L), balance.net)
    }

    @Test
    fun everyLedgerVoucherTypePostsBalancedEntriesToExpectedAccounts(): Unit = runBlocking {
        seedMasterData()
        val accounts = ledger.ensureSystemAccounts("firm-1", CREATED_AT)
        val drafts = listOf(
            testVoucher(
                id = "sale-1",
                lines = listOf(testLine(id = "line-sale-1")),
                charges = emptyList(),
            ),
            testVoucher(
                id = "purchase-1",
                type = VoucherType.PURCHASE,
                lines = listOf(testLine(id = "line-purchase-1")),
                charges = emptyList(),
            ),
            testVoucher(
                id = "credit-note-1",
                type = VoucherType.CREDIT_NOTE,
                lines = listOf(testLine(id = "line-credit-note-1")),
                charges = emptyList(),
            ),
            testVoucher(
                id = "debit-note-1",
                type = VoucherType.DEBIT_NOTE,
                lines = listOf(testLine(id = "line-debit-note-1")),
                charges = emptyList(),
            ),
        )
        drafts.forEach {
            vouchers.saveDraft(it)
            vouchers.post(it.id, POSTED_AT)
        }
        val paymentIn = testPaymentVoucher(id = "payment-in-1", total = 10_000L)
        val paymentOut = testPaymentVoucher(
            id = "payment-out-1",
            type = VoucherType.PAYMENT_OUT,
            total = 7_500L,
        )
        listOf(paymentIn, paymentOut).forEach { payment ->
            vouchers.saveDraft(payment)
            payments.savePaymentDetails(testPaymentDetails(payment.id, accounts.cash.id))
            vouchers.post(payment.id, POSTED_AT)
        }

        val codesById = listOf(
            accounts.accountsReceivable,
            accounts.accountsPayable,
            accounts.outputGst,
            accounts.inputGst,
            accounts.roundOff,
            accounts.sales,
            accounts.purchases,
            accounts.cash,
        ).associate { it.id to requireNotNull(it.code) }

        suspend fun assertJournal(voucherId: String, expected: Set<Triple<AccountCode, Long, Long>>) {
            val entries = db.ledgerDao().entriesForVoucher(voucherId)
            assertEquals(entries.sumOf { it.debit }, entries.sumOf { it.credit })
            val actual = entries.map {
                Triple(codesById.getValue(it.accountId), it.debit, it.credit)
            }.toSet()
            assertEquals(expected, actual)
        }

        assertJournal(
            "sale-1",
            setOf(
                Triple(AccountCode.AR, 23_800L, 0L),
                Triple(AccountCode.SALES, 0L, 20_000L),
                Triple(AccountCode.OUTPUT_GST, 0L, 3_800L),
            ),
        )
        assertJournal(
            "purchase-1",
            setOf(
                Triple(AccountCode.PURCHASES, 20_000L, 0L),
                Triple(AccountCode.INPUT_GST, 3_800L, 0L),
                Triple(AccountCode.AP, 0L, 23_800L),
            ),
        )
        assertJournal(
            "credit-note-1",
            setOf(
                Triple(AccountCode.SALES, 20_000L, 0L),
                Triple(AccountCode.OUTPUT_GST, 3_800L, 0L),
                Triple(AccountCode.AR, 0L, 23_800L),
            ),
        )
        assertJournal(
            "debit-note-1",
            setOf(
                Triple(AccountCode.AP, 23_800L, 0L),
                Triple(AccountCode.PURCHASES, 0L, 20_000L),
                Triple(AccountCode.INPUT_GST, 0L, 3_800L),
            ),
        )
        assertJournal(
            "payment-in-1",
            setOf(
                Triple(AccountCode.CASH, 10_000L, 0L),
                Triple(AccountCode.AR, 0L, 10_000L),
            ),
        )
        assertJournal(
            "payment-out-1",
            setOf(
                Triple(AccountCode.AP, 7_500L, 0L),
                Triple(AccountCode.CASH, 0L, 7_500L),
            ),
        )
    }

    @Test
    fun cancelledVoucherJournalsAreAppendedButLeaveThePostedPartyLedger(): Unit = runBlocking {
        seedMasterData()
        val sale = testVoucher()
        vouchers.saveDraft(sale)
        vouchers.post(sale.id, POSTED_AT)
        val original = ledger.journalEntriesForVoucher(sale.id)

        vouchers.cancel(
            sale.id,
            "Customer request",
            Instant.parse("2026-05-20T20:00:00Z"),
        )

        val entries = ledger.journalEntriesForVoucher(sale.id)
        assertEquals(original.size * 2, entries.size)
        assertEquals(original, entries.take(original.size))
        assertEquals(
            original.map { it.debit to it.credit }.toSet(),
            entries.drop(original.size).map { it.credit to it.debit }.toSet(),
        )
        val ledgerAfterCancel = ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 1),
            LocalDate(2026, 5, 31),
        ).first()
        assertEquals(Money.of(12_345L), ledgerAfterCancel.openingBalance)
        assertEquals(Money.of(12_345L), ledgerAfterCancel.closingBalance)
        assertEquals(0, ledgerAfterCancel.entries.size)
    }

    @Test
    fun partyLedgerExcludesOtherPartiesDraftsAndNonControlEntries(): Unit = runBlocking {
        seedMasterData()
        parties.saveParty(
            testParty(
                id = "party-2",
                name = "Beta Supplies",
                addresses = emptyList(),
            ),
        )
        val own = testVoucher()
        vouchers.saveDraft(own)
        vouchers.post(own.id, POSTED_AT)
        val other = testVoucher(
            id = "voucher-other",
            lines = listOf(testLine(id = "line-other")),
            charges = emptyList(),
        ).copy(partyId = "party-2")
        vouchers.saveDraft(other)
        vouchers.post(other.id, POSTED_AT)
        vouchers.saveDraft(
            testVoucher(
                id = "voucher-draft",
                lines = listOf(testLine(id = "line-draft")),
                charges = emptyList(),
            ),
        )

        val party = ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 1),
            LocalDate(2026, 5, 31),
        ).first()

        assertEquals(1, party.entries.size)
        assertEquals(Money.of(23_800L), party.entries.single().debit)
        assertNotNull(party.entries.single().voucherNumber)
        assertEquals(AccountCode.AR, party.entries.single().accountCode)
        assertNull(
            party.entries.firstOrNull { it.voucherId == other.id },
        )
    }

    private suspend fun seedMasterData() {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings())
        firms.saveFinancialYear(testFinancialYear())
        parties.saveParty(testParty())
    }
}
