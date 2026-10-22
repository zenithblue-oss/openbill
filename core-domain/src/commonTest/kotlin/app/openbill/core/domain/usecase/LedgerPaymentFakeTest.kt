package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryLedgerRepository
import app.openbill.core.domain.fake.InMemoryPartyRepository
import app.openbill.core.domain.fake.InMemoryPaymentRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class LedgerPaymentFakeTest {
    private val createdAt = Instant.parse("2026-05-01T00:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T20:00:00Z")

    @Test
    fun salePurchaseNotesAndPaymentsPostBalancedJournals(): Unit = runBlocking {
        val fixture = fixture()
        val sale = fixture.post(
            fixture.voucher(VoucherType.SALE, total = 10_000L),
            postedAt,
        )
        val purchase = fixture.post(
            fixture.voucher(VoucherType.PURCHASE, id = "purchase-1", total = 8_000L),
            postedAt,
        )
        val creditNote = fixture.post(
            fixture.voucher(VoucherType.CREDIT_NOTE, id = "credit-note-1", total = 2_000L),
            postedAt,
        )
        val debitNote = fixture.post(
            fixture.voucher(VoucherType.DEBIT_NOTE, id = "debit-note-1", total = 1_500L),
            postedAt,
        )
        val payment = fixture.paymentIn(total = 4_000L)

        listOf(sale, purchase, creditNote, debitNote, payment).forEach { voucher ->
            val entries = fixture.ledger.journalEntriesForVoucher(voucher.id)
            assertTrue(entries.isNotEmpty())
            val debit = entries.fold(Money.ZERO) { total, entry -> total + entry.debit }
            val credit = entries.fold(Money.ZERO) { total, entry -> total + entry.credit }
            assertEquals(debit, credit)
        }
    }

    @Test
    fun postingRequiresAPartyForLedgerVouchers(): Unit = runBlocking {
        val fixture = fixture()
        val draft = fixture.voucher(VoucherType.SALE, total = 10_000L)
            .copy(partyId = null)
        fixture.vouchers.saveDraft(draft)

        assertFailsWith<IllegalArgumentException> {
            PostVoucher(fixture.vouchers)(draft.id, postedAt)
        }
        assertTrue(fixture.ledger.journalEntriesForVoucher(draft.id).isEmpty())
    }

    @Test
    fun paymentPostingRequiresSavedDetails(): Unit = runBlocking {
        val fixture = fixture()
        val draft = fixture.voucher(VoucherType.PAYMENT_IN, total = 5_000L)
        fixture.vouchers.saveDraft(draft)

        assertFailsWith<IllegalArgumentException> {
            PostVoucher(fixture.vouchers)(draft.id, postedAt)
        }
        assertEquals(VoucherStatus.DRAFT, fixture.vouchers.findVoucher(draft.id)?.status)
    }

    @Test
    fun savePaymentDetailsValidatesAccountAndNormalizesText(): Unit = runBlocking {
        val fixture = fixture()
        val draft = fixture.voucher(VoucherType.PAYMENT_IN, total = 5_000L)
        fixture.vouchers.saveDraft(draft)
        val cash = fixture.cashAccount()
        val useCase = SavePaymentDetails(fixture.vouchers, fixture.payments, fixture.ledger)

        val saved = useCase(
            fixture.details(draft.id, cash.id, reference = "  REF-1  "),
        )

        assertEquals("REF-1", saved.reference)
        assertEquals(saved, fixture.payments.findPaymentDetails(draft.id))
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(draft.id, fixture.salesAccount().id))
        }
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(draft.id, fixture.otherFirmAccount().id))
        }
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(draft.id, fixture.deletedAccount().id))
        }
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(draft.id, cash.id).copy(firmId = "firm-2"))
        }
        val saleDraft = fixture.voucher(VoucherType.SALE, id = "sale-draft", total = 5_000L)
        fixture.vouchers.saveDraft(saleDraft)
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(saleDraft.id, cash.id))
        }
        val postedPayment = fixture.paymentIn(id = "payment-posted", total = 1_000L)
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(postedPayment.id, cash.id))
        }
        val deletedDraft = fixture.voucher(
            VoucherType.PAYMENT_OUT,
            id = "payment-deleted",
            total = 1_000L,
        ).copy(deletedAt = createdAt)
        fixture.vouchers.saveDraft(deletedDraft)
        assertFailsWith<IllegalArgumentException> {
            useCase(fixture.details(deletedDraft.id, cash.id))
        }
    }

    @Test
    fun allocationTracksPartialAndExactOutstanding(): Unit = runBlocking {
        val fixture = fixture()
        val invoice = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        val firstPayment = fixture.paymentIn(id = "payment-1", total = 2_500L)
        val secondPayment = fixture.paymentIn(id = "payment-2", total = 3_500L)
        val allocate = AllocatePayment(fixture.payments)

        allocate(
            firstPayment.id,
            listOf(AllocationRequest(invoice.id, Money.of(2_500L))),
            postedAt,
        )
        assertEquals(Money.of(7_500L), fixture.payments.invoiceOutstanding(invoice.id))
        assertEquals(
            Money.of(2_500L),
            fixture.payments.allocatedTotalForPayment(firstPayment.id),
        )

        allocate(
            secondPayment.id,
            listOf(AllocationRequest(invoice.id, Money.of(3_500L))),
            postedAt,
        )
        assertEquals(Money.of(4_000L), fixture.payments.invoiceOutstanding(invoice.id))
        assertEquals(1, fixture.payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE).size)

        val exactPayment = fixture.paymentIn(id = "payment-3", total = 4_000L)
        allocate(
            exactPayment.id,
            listOf(AllocationRequest(invoice.id, Money.of(4_000L))),
            postedAt,
        )
        assertEquals(Money.of(0L), fixture.payments.invoiceOutstanding(invoice.id))
        assertTrue(
            fixture.payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE).isEmpty(),
        )
    }

    @Test
    fun allocationRejectsOverAllocationOnEitherSide(): Unit = runBlocking {
        val fixture = fixture()
        val first = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        val second = fixture.post(
            fixture.voucher(VoucherType.SALE, id = "sale-2", total = 10_000L),
            postedAt,
        )
        val payment = fixture.paymentIn(total = 15_000L)
        val allocate = AllocatePayment(fixture.payments)

        assertFailsWith<IllegalArgumentException> {
            allocate(
                payment.id,
                listOf(
                    AllocationRequest(first.id, Money.of(6_000L)),
                    AllocationRequest(second.id, Money.of(10_000L)),
                ),
                postedAt,
            )
        }
        assertTrue(fixture.payments.observeAllocationsForPayment(payment.id).first().isEmpty())

        val small = fixture.paymentIn(id = "payment-2", total = 5_000L)
        assertFailsWith<IllegalArgumentException> {
            allocate(
                small.id,
                listOf(AllocationRequest(first.id, Money.of(5_001L))),
                postedAt,
            )
        }
    }

    @Test
    fun allocationRejectsInvalidRequestsAndInvoices(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seedParty("party-2")
        val invoice = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        val otherPartyInvoice = fixture.post(
            fixture.voucher(
                VoucherType.SALE,
                id = "sale-other",
                total = 10_000L,
                partyId = "party-2",
            ),
            postedAt,
        )
        val purchase = fixture.post(
            fixture.voucher(VoucherType.PURCHASE, id = "purchase-1", total = 10_000L),
            postedAt,
        )
        val cancelled = fixture.post(
            fixture.voucher(VoucherType.SALE, id = "sale-cancelled", total = 10_000L),
            postedAt,
        )
        fixture.vouchers.cancel(cancelled.id, "Customer request", cancelledAt)
        val payment = fixture.paymentIn(total = 50_000L)
        val allocate = AllocatePayment(fixture.payments)

        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, listOf(AllocationRequest(invoice.id, Money.ZERO)), postedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, listOf(AllocationRequest(invoice.id, -Money.of(1L))), postedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(
                payment.id,
                listOf(
                    AllocationRequest(invoice.id, Money.of(1_000L)),
                    AllocationRequest(invoice.id, Money.of(1_000L)),
                ),
                postedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(
                payment.id,
                listOf(AllocationRequest(otherPartyInvoice.id, Money.of(1_000L))),
                postedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(
                payment.id,
                listOf(AllocationRequest(purchase.id, Money.of(1_000L))),
                postedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocate(
                payment.id,
                listOf(AllocationRequest(cancelled.id, Money.of(1_000L))),
                postedAt,
            )
        }

        allocate(payment.id, listOf(AllocationRequest(invoice.id, Money.of(1_000L))), postedAt)
        assertFailsWith<IllegalArgumentException> {
            allocate(payment.id, listOf(AllocationRequest(invoice.id, Money.of(1_000L))), postedAt)
        }
    }

    @Test
    fun cancellationKeepsAllocationRowsButStopsOutstandingNetting(): Unit = runBlocking {
        val fixture = fixture()
        val invoice = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        val payment = fixture.paymentIn(total = 6_000L)
        val allocate = AllocatePayment(fixture.payments)
        allocate(payment.id, listOf(AllocationRequest(invoice.id, Money.of(3_000L))), postedAt)
        assertEquals(Money.of(7_000L), fixture.payments.invoiceOutstanding(invoice.id))

        CancelVoucher(fixture.vouchers)(payment.id, "Bounced", cancelledAt)

        assertEquals(
            1,
            fixture.payments.observeAllocationsForPayment(payment.id).first().size,
        )
        assertEquals(Money.of(3_000L), fixture.payments.allocatedTotalForPayment(payment.id))
        assertEquals(Money.of(10_000L), fixture.payments.invoiceOutstanding(invoice.id))
        assertEquals(
            Money.of(10_000L),
            fixture.payments.observeInvoiceOutstanding(invoice.id).first(),
        )
    }

    @Test
    fun creditNoteDoesNotReduceInvoiceOutstanding(): Unit = runBlocking {
        val fixture = fixture()
        val invoice = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        fixture.post(
            fixture.voucher(VoucherType.CREDIT_NOTE, id = "credit-note-1", total = 2_000L),
            postedAt,
        )

        assertEquals(Money.of(10_000L), fixture.payments.invoiceOutstanding(invoice.id))
        assertEquals(
            listOf(invoice.id),
            fixture.payments.outstandingInvoices("firm-1", "party-1", VoucherType.SALE)
                .map { it.voucherId },
        )
    }

    @Test
    fun cancelAppendsReversalEntriesAndKeepsThemWithTheVoucher(): Unit = runBlocking {
        val fixture = fixture()
        val sale = fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        val original = fixture.ledger.journalEntriesForVoucher(sale.id)
        assertEquals(2, original.size)

        fixture.vouchers.cancel(sale.id, "Returned", cancelledAt)

        val entries = fixture.ledger.journalEntriesForVoucher(sale.id)
        assertEquals(4, entries.size)
        assertEquals(original, entries.take(original.size))
        assertTrue(entries.drop(original.size).all { it.date == LocalDate(2026, 5, 20) })
        assertTrue(entries.drop(original.size).all { it.narration == "Reversal of ${sale.number}" })
    }

    @Test
    fun partyLedgerAppliesOpeningBalanceRunningEntriesAndDateRange(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seedParty(openingBalance = 1_000L)
        fixture.post(
            fixture.voucher(
                VoucherType.SALE,
                total = 10_000L,
                date = LocalDate(2026, 5, 10),
            ),
            postedAt,
        )
        fixture.paymentIn(
            total = 4_000L,
            date = LocalDate(2026, 5, 12),
        )

        val full = fixture.ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 1),
            LocalDate(2026, 5, 31),
        ).first()
        assertEquals(Money.of(1_000L), full.openingBalance)
        assertEquals(Money.of(7_000L), full.closingBalance)
        assertEquals(2, full.entries.size)
        assertEquals(AccountCode.AR, full.entries.first().accountCode)
        assertEquals(Money.of(10_000L), full.entries.first().debit)
        assertEquals(Money.of(4_000L), full.entries.last().credit)

        val later = fixture.ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 11),
            LocalDate(2026, 5, 31),
        ).first()
        assertEquals(Money.of(11_000L), later.openingBalance)
        assertEquals(1, later.entries.size)
        assertEquals(Money.of(7_000L), later.closingBalance)

        val balance = fixture.ledger.partyBalance(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 31),
        )
        assertEquals(Money.of(11_000L), balance.debit)
        assertEquals(Money.of(4_000L), balance.credit)
        assertEquals(Money.of(7_000L), balance.net)
    }

    @Test
    fun partyLedgerExcludesOtherPartiesDraftsAndCancelledVouchers(): Unit = runBlocking {
        val fixture = fixture()
        fixture.seedParty()
        fixture.seedParty("party-2")
        fixture.post(fixture.voucher(VoucherType.SALE, total = 10_000L), postedAt)
        fixture.post(
            fixture.voucher(
                VoucherType.SALE,
                id = "sale-other",
                total = 9_000L,
                partyId = "party-2",
            ),
            postedAt,
        )
        val cancelled = fixture.post(
            fixture.voucher(VoucherType.SALE, id = "sale-cancelled", total = 8_000L),
            postedAt,
        )
        fixture.vouchers.cancel(cancelled.id, "Void", cancelledAt)
        fixture.vouchers.saveDraft(
            fixture.voucher(VoucherType.SALE, id = "sale-draft", total = 7_000L),
        )

        val ledger = fixture.ledger.observePartyLedger(
            "firm-1",
            "party-1",
            LocalDate(2026, 5, 1),
            LocalDate(2026, 5, 31),
        ).first()

        assertEquals(1, ledger.entries.size)
        assertEquals(Money.of(10_000L), ledger.entries.single().debit)
        assertEquals(Money.of(10_000L), ledger.closingBalance)
    }

    @Test
    fun systemAccountsAreCreatedOnceAndStable(): Unit = runBlocking {
        val fixture = fixture()
        val first = fixture.cashAccount()
        val second = fixture.cashAccount()

        assertEquals(first, second)
        assertEquals(AccountCode.CASH, first.code)
        assertEquals(AccountType.CASH, first.type)
        assertEquals(AccountCode.entries.size, fixture.ledger.observeAccounts("firm-1").first().size)
        assertNotNull(fixture.ledger.findAccount("firm-1", AccountCode.AR))
    }

    @Test
    fun saleWithEngineComputedChargesPostsBalancedJournalThroughFakes(): Unit = runBlocking {
        val fixture = fixture()
        val freight = app.openbill.core.domain.model.VoucherCharge(
            id = "charge-freight",
            name = "Freight",
            amount = Money.of(7_525L),
        )
        val packing = app.openbill.core.domain.model.VoucherCharge.nonTaxable(
            id = "charge-packing",
            name = "Packing",
            amount = Money.of(500L),
            sortOrder = 1,
        )
        val result = app.openbill.core.tax.InvoiceTaxCalculator.calculate(
            app.openbill.core.tax.InvoiceTaxInput(
                lines = listOf(
                    app.openbill.core.tax.TaxLineInput(
                        grossAmount = Money.of(100_000L),
                        gstRate = app.openbill.core.model.Rate.ofPercent(18),
                        mode = app.openbill.core.tax.TaxMode.EXCLUSIVE,
                    ),
                ),
                invoiceDiscount = Money.of(10_000L),
                charges = listOf(freight.toTaxInput(), packing.toTaxInput()),
            ),
            app.openbill.core.tax.TaxContext("27", "27"),
            app.openbill.core.model.RoundingMode.HALF_UP,
        )
        val charges = listOf(freight, packing).mapIndexed { index, charge ->
            charge.withTaxResult(result.charges[index])
        }
        val draft = fixture.voucher(VoucherType.SALE, id = "sale-charges", total = 0L).copy(
            charges = charges,
            discount = Money.of(10_000L),
            taxable = result.totalTaxable,
            tax = result.totalTax,
            roundOff = result.roundOff,
            total = result.grandTotal,
        )
        // 900.00 + 75.25 + 5.00 taxable; 162.00 + 13.54 GST; 1155.79 -> 1156.00.
        assertEquals(Money.of(98_025L), draft.taxable)
        assertEquals(Money.of(17_554L), draft.tax)
        assertEquals(Money.of(21L), draft.roundOff)
        assertEquals(Money.of(115_600L), draft.total)

        val posted = fixture.post(draft, postedAt)
        assertEquals(charges, posted.charges)
        val entries = fixture.ledger.journalEntriesForVoucher(posted.id)
        val accounts = fixture.ledger.ensureSystemAccounts("firm-1", createdAt)
        assertEquals(
            Money.of(98_025L),
            entries.filter { it.accountId == accounts.sales.id }.fold(Money.ZERO) { t, e -> t + e.credit },
        )
        assertEquals(
            Money.of(17_554L),
            entries.filter { it.accountId == accounts.outputGst.id }.fold(Money.ZERO) { t, e -> t + e.credit },
        )
        val debit = entries.fold(Money.ZERO) { total, entry -> total + entry.debit }
        val credit = entries.fold(Money.ZERO) { total, entry -> total + entry.credit }
        assertEquals(debit, credit)
        assertEquals(Money.of(115_600L), debit)
    }

    private fun fixture(): Fixture = Fixture(createdAt, postedAt)

    private class Fixture(
        private val createdAt: Instant,
        private val postedAt: Instant,
    ) {
        val parties = InMemoryPartyRepository()
        val ledger = InMemoryLedgerRepository(parties)
        val payments = InMemoryPaymentRepository()
        val vouchers = InMemoryVoucherRepository(ledger = ledger, payments = payments)

        init {
            ledger.attachVouchers(vouchers)
            payments.attachVouchers(vouchers)
        }

        suspend fun seedParty(
            id: String = "party-1",
            openingBalance: Long = 0L,
        ) {
            parties.saveParty(
                Party(
                    id = id,
                    firmId = "firm-1",
                    name = "Party $id",
                    type = PartyType.CUSTOMER,
                    openingBalance = Money.of(openingBalance),
                    openingBalanceType = OpeningBalanceType.DEBIT,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
        }

        suspend fun cashAccount(): Account =
            ledger.ensureSystemAccounts("firm-1", createdAt).cash

        suspend fun salesAccount(): Account =
            ledger.ensureSystemAccounts("firm-1", createdAt).sales

        suspend fun otherFirmAccount(): Account {
            val account = Account(
                id = "other-firm-bank",
                firmId = "firm-2",
                name = "Other Bank",
                type = AccountType.BANK,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
            ledger.saveAccount(account)
            return account
        }

        suspend fun deletedAccount(): Account {
            val account = Account(
                id = "deleted-bank",
                firmId = "firm-1",
                name = "Deleted Bank",
                type = AccountType.BANK,
                createdAt = createdAt,
                updatedAt = createdAt,
                deletedAt = createdAt,
            )
            ledger.saveAccount(account)
            return account
        }

        fun voucher(
            type: VoucherType,
            id: String = "voucher-${type.name.lowercase()}",
            total: Long,
            partyId: String? = "party-1",
            date: LocalDate = LocalDate(2026, 5, 10),
        ): Voucher = Voucher(
            id = id,
            firmId = "firm-1",
            financialYearId = "fy-1",
            type = type,
            date = date,
            partyId = partyId,
            status = VoucherStatus.DRAFT,
            placeOfSupplyStateCode = "27",
            lines = if (type == VoucherType.PAYMENT_IN || type == VoucherType.PAYMENT_OUT) {
                emptyList()
            } else {
                listOf(
                    VoucherLine(
                        id = "line-$id",
                        lineNumber = 1,
                        description = "Item",
                        quantity = Quantity.of(1),
                        rate = Money.of(total),
                        taxable = Money.of(total),
                        total = Money.of(total),
                    ),
                )
            },
            taxable = if (type.postsLedger() && !isPayment(type)) Money.of(total) else Money.ZERO,
            total = Money.of(total),
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        suspend fun post(voucher: Voucher, at: Instant): Voucher {
            vouchers.saveDraft(voucher)
            return PostVoucher(vouchers)(voucher.id, at)
        }

        suspend fun paymentIn(
            id: String = "payment-1",
            total: Long,
            date: LocalDate = LocalDate(2026, 5, 12),
            partyId: String = "party-1",
        ): Voucher {
            val draft = voucher(VoucherType.PAYMENT_IN, id = id, total = total, partyId = partyId, date = date)
            vouchers.saveDraft(draft)
            SavePaymentDetails(vouchers, payments, ledger)(
                details(draft.id, cashAccount().id),
            )
            return PostVoucher(vouchers)(draft.id, postedAt)
        }

        fun details(
            voucherId: String,
            accountId: String,
            reference: String? = null,
        ): PaymentDetails = PaymentDetails(
            voucherId = voucherId,
            firmId = "firm-1",
            accountId = accountId,
            method = PaymentMethod.CASH,
            reference = reference,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        private fun isPayment(type: VoucherType): Boolean =
            type == VoucherType.PAYMENT_IN || type == VoucherType.PAYMENT_OUT
    }
}
