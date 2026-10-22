package app.openbill.core.domain.service

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherCharge
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class LedgerJournalDerivationTest {
    private val createdAt = Instant.parse("2026-05-01T00:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T20:00:00Z")
    private val accounts = fixtureAccounts()
    private val bank = Account(
        id = "bank-1",
        firmId = "firm-1",
        name = "Current Account",
        type = app.openbill.core.domain.model.AccountType.BANK,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
    private val derivation = LedgerJournalDerivation()

    @Test
    fun saleDebitsReceivableAndCreditsSalesAndGst() {
        val voucher = voucher(VoucherType.SALE)
        val entries = derivation.derivePosted(voucher, accounts, null, postedAt)

        assertBalanced(entries)
        assertEquals(Money.of(11_800L), entries.forCode(AccountCode.AR).debit)
        assertEquals(Money.of(10_000L), entries.forCode(AccountCode.SALES).credit)
        assertEquals(Money.of(1_800L), entries.forCode(AccountCode.OUTPUT_GST).credit)
        assertEquals(voucher.date, entries.first().date)
    }

    @Test
    fun purchaseCreditNoteAndDebitNoteUseMirroredDirections() {
        val purchase = derivation.derivePosted(
            voucher(VoucherType.PURCHASE),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(purchase)
        assertEquals(Money.of(10_000L), purchase.forCode(AccountCode.PURCHASES).debit)
        assertEquals(Money.of(1_800L), purchase.forCode(AccountCode.INPUT_GST).debit)
        assertEquals(Money.of(11_800L), purchase.forCode(AccountCode.AP).credit)

        val creditNote = derivation.derivePosted(
            voucher(VoucherType.CREDIT_NOTE),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(creditNote)
        assertEquals(Money.of(10_000L), creditNote.forCode(AccountCode.SALES).debit)
        assertEquals(Money.of(1_800L), creditNote.forCode(AccountCode.OUTPUT_GST).debit)
        assertEquals(Money.of(11_800L), creditNote.forCode(AccountCode.AR).credit)

        val debitNote = derivation.derivePosted(
            voucher(VoucherType.DEBIT_NOTE),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(debitNote)
        assertEquals(Money.of(10_000L), debitNote.forCode(AccountCode.PURCHASES).credit)
        assertEquals(Money.of(1_800L), debitNote.forCode(AccountCode.INPUT_GST).credit)
        assertEquals(Money.of(11_800L), debitNote.forCode(AccountCode.AP).debit)
    }

    @Test
    fun positiveAndNegativeRoundOffBalanceTheirVoucher() {
        val positive = voucher(
            VoucherType.SALE,
            roundOff = 37L,
            total = 11_837L,
        )
        val positiveEntries = derivation.derivePosted(positive, accounts, null, postedAt)
        assertBalanced(positiveEntries)
        assertEquals(Money.of(37L), positiveEntries.forCode(AccountCode.ROUND_OFF).credit)
        assertEquals(Money.of(11_837L), positiveEntries.forCode(AccountCode.AR).debit)

        val negative = voucher(
            VoucherType.SALE,
            roundOff = -37L,
            total = 11_763L,
        )
        val negativeEntries = derivation.derivePosted(negative, accounts, null, postedAt)
        assertBalanced(negativeEntries)
        assertEquals(Money.of(37L), negativeEntries.forCode(AccountCode.ROUND_OFF).debit)
        assertEquals(Money.of(11_763L), negativeEntries.forCode(AccountCode.AR).debit)
    }

    @Test
    fun includedChargesAreAddedToSalesAndGst() {
        val charge = VoucherCharge(
            id = "charge-1",
            name = "Freight",
            amount = Money.of(500L),
            taxAmount = Money.of(90L),
            total = Money.of(590L),
        )
        val included = voucher(
            VoucherType.SALE,
            charges = listOf(charge),
            total = 12_390L,
        )
        val entries = derivation.derivePosted(included, accounts, null, postedAt)
        assertBalanced(entries)
        assertEquals(Money.of(10_500L), entries.forCode(AccountCode.SALES).credit)
        assertEquals(Money.of(1_890L), entries.forCode(AccountCode.OUTPUT_GST).credit)
        assertEquals(Money.of(12_390L), entries.forCode(AccountCode.AR).debit)

        val excluded = voucher(
            VoucherType.SALE,
            charges = listOf(charge),
            total = 11_800L,
        )
        val excludedEntries = derivation.derivePosted(excluded, accounts, null, postedAt)
        assertEquals(Money.of(10_000L), excludedEntries.forCode(AccountCode.SALES).credit)
        assertEquals(Money.of(1_800L), excludedEntries.forCode(AccountCode.OUTPUT_GST).credit)
    }

    @Test
    fun inconsistentHeaderChargeAndRoundOffTotalsAreRejected() {
        val charge = VoucherCharge(
            id = "charge-1",
            name = "Freight",
            amount = Money.of(500L),
            taxAmount = Money.of(90L),
            total = Money.of(590L),
        )
        assertFailsWith<IllegalArgumentException> {
            derivation.derivePosted(
                voucher(VoucherType.SALE, charges = listOf(charge), total = 12_000L),
                accounts,
                null,
                postedAt,
            )
        }
    }

    @Test
    fun chargesPostTaxableValueNotInclusiveAmountInLegacyHeader() {
        // Inclusive freight of 118.00 at 18%: taxable 100.00, GST 18.00. Header excludes it.
        val inclusive = VoucherCharge(
            id = "charge-inc",
            name = "Freight",
            amount = Money.of(11_800L),
            taxRate = app.openbill.core.model.Rate.ofPercent(18),
            taxAmount = Money.of(1_800L),
            total = Money.of(11_800L),
            taxMode = app.openbill.core.tax.TaxMode.INCLUSIVE,
        )
        val packing = VoucherCharge.nonTaxable("charge-nt", "Packing", Money.of(250L), sortOrder = 1)
        val entries = derivation.derivePosted(
            voucher(VoucherType.SALE, charges = listOf(inclusive, packing), total = 11_800L + 11_800L + 250L),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(entries)
        assertEquals(Money.of(10_000L + 10_000L + 250L), entries.forCode(AccountCode.SALES).credit)
        assertEquals(Money.of(3_600L), entries.forCode(AccountCode.OUTPUT_GST).credit)
        assertEquals(Money.of(23_850L), entries.forCode(AccountCode.AR).debit)
    }

    @Test
    fun creditNoteWithChargesInHeaderReversesSalesGstAndRoundOff() {
        // Engine convention: header taxable/tax already include charges (golden K07-style).
        val freight = VoucherCharge(
            id = "charge-1",
            name = "Freight",
            amount = Money.of(5_000L),
            taxRate = app.openbill.core.model.Rate.ofPercent(12),
            taxAmount = Money.of(600L),
            total = Money.of(5_600L),
        )
        val handling = VoucherCharge.nonTaxable("charge-2", "Handling", Money.of(1_000L), sortOrder = 1)
        val entries = derivation.derivePosted(
            voucher(
                VoucherType.CREDIT_NOTE,
                taxable = 25_000L + 5_000L + 1_000L,
                tax = 3_000L + 600L,
                roundOff = -60L,
                charges = listOf(freight, handling),
            ),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(entries)
        assertEquals(Money.of(31_000L), entries.forCode(AccountCode.SALES).debit)
        assertEquals(Money.of(3_600L), entries.forCode(AccountCode.OUTPUT_GST).debit)
        assertEquals(Money.of(60L), entries.forCode(AccountCode.ROUND_OFF).credit)
        assertEquals(Money.of(34_540L), entries.forCode(AccountCode.AR).credit)
    }

    @Test
    fun purchaseChargesPostToPurchasesAndInputGst() {
        val freight = VoucherCharge(
            id = "charge-1",
            name = "Freight",
            amount = Money.of(1_000L),
            taxRate = app.openbill.core.model.Rate.ofPercent(18),
            taxAmount = Money.of(180L),
            total = Money.of(1_180L),
        )
        val entries = derivation.derivePosted(
            voucher(VoucherType.PURCHASE, charges = listOf(freight), total = 11_800L + 1_180L),
            accounts,
            null,
            postedAt,
        )
        assertBalanced(entries)
        assertEquals(Money.of(11_000L), entries.forCode(AccountCode.PURCHASES).debit)
        assertEquals(Money.of(1_980L), entries.forCode(AccountCode.INPUT_GST).debit)
        assertEquals(Money.of(12_980L), entries.forCode(AccountCode.AP).credit)
    }

    @Test
    fun zeroTaxProducesNoGstEntry() {
        val entries = derivation.derivePosted(
            voucher(VoucherType.SALE, tax = 0L, total = 10_000L),
            accounts,
            null,
            postedAt,
        )

        assertBalanced(entries)
        assertEquals(2, entries.size)
        assertEquals(Money.of(10_000L), entries.forCode(AccountCode.SALES).credit)
        assertTrue(entries.none { it.accountId == accounts.outputGst.id })
    }

    @Test
    fun paymentPostingsDebitOrCreditTheControlAccount() {
        val incoming = derivation.derivePosted(
            voucher(
                VoucherType.PAYMENT_IN,
                taxable = 0L,
                tax = 0L,
                total = 5_000L,
                number = "PAY-0001",
            ),
            accounts,
            bank.id,
            postedAt,
        )
        assertBalanced(incoming)
        assertEquals(Money.of(5_000L), incoming.forCode(AccountCode.AR).credit)
        assertEquals(Money.of(5_000L), incoming.single { it.accountId == bank.id }.debit)

        val outgoing = derivation.derivePosted(
            voucher(
                VoucherType.PAYMENT_OUT,
                taxable = 0L,
                tax = 0L,
                total = 5_000L,
                number = "PAY-0002",
            ),
            accounts,
            bank.id,
            postedAt,
        )
        assertBalanced(outgoing)
        assertEquals(Money.of(5_000L), outgoing.forCode(AccountCode.AP).debit)
        assertEquals(Money.of(5_000L), outgoing.single { it.accountId == bank.id }.credit)
    }

    @Test
    fun paymentRequiresAnAccountAndNoTaxOrRoundOff() {
        val payment = voucher(
            VoucherType.PAYMENT_IN,
            taxable = 0L,
            tax = 0L,
            total = 5_000L,
            number = "PAY-0001",
        )
        assertFailsWith<IllegalArgumentException> {
            derivation.derivePosted(payment, accounts, null, postedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            derivation.derivePosted(
                payment.copy(taxable = Money.of(1L)),
                accounts,
                bank.id,
                postedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            derivation.derivePosted(
                payment.copy(roundOff = Money.of(1L)),
                accounts,
                bank.id,
                postedAt,
            )
        }
    }

    @Test
    fun reversalSwapsSidesUsesCancellationDateAndKeepsOriginals() {
        val voucher = voucher(VoucherType.SALE, number = "INV-0001")
        val original = derivation.derivePosted(voucher, accounts, null, postedAt)
        val originalSnapshot = original.toList()

        val reversal = derivation.deriveReversal(voucher, original, cancelledAt)

        assertEquals(originalSnapshot, original)
        assertEquals(original.size, reversal.size)
        assertEquals(LocalDate(2026, 5, 20), reversal.map { it.date }.distinct().single())
        assertTrue(reversal.all { it.voucherId == voucher.id })
        assertTrue(reversal.all { it.narration == "Reversal of INV-0001" })
        assertEquals(original.map { it.id }.toSet().intersect(reversal.map { it.id }.toSet()).size, 0)
        original.zip(reversal).forEach { (posted, reversed) ->
            assertEquals(posted.debit, reversed.credit)
            assertEquals(posted.credit, reversed.debit)
            assertEquals(posted.accountId, reversed.accountId)
            assertNotEquals(posted.id, reversed.id)
        }
    }

    @Test
    fun fiftySeededSalesAlwaysBalance() {
        repeat(50) { index ->
            val seed = index + 1L
            val taxable = seed * 137L
            val tax = taxable * 18L / 100L
            val roundOff = when {
                seed % 3L == 0L -> -(seed % 7L)
                else -> seed % 5L
            }
            val charge = if (seed % 4L == 0L) {
                VoucherCharge(
                    id = "charge-$seed",
                    name = "Freight",
                    amount = Money.of(seed * 3L),
                    taxAmount = Money.of(seed),
                    total = Money.of(seed * 4L),
                )
            } else {
                null
            }
            val charges = listOfNotNull(charge)
            val total = taxable + tax + roundOff + charges.sumOf { it.total.paise }
            val entries = derivation.derivePosted(
                voucher(
                    VoucherType.SALE,
                    taxable = taxable,
                    tax = tax,
                    roundOff = roundOff,
                    total = total,
                    charges = charges,
                    number = "INV-${seed.toString().padStart(4, '0')}",
                ),
                accounts,
                null,
                postedAt,
            )
            assertBalanced(entries)
        }
    }

    @Test
    fun nonLedgerVoucherTypesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            derivation.derivePosted(
                voucher(
                    VoucherType.QUOTATION,
                    taxable = 0L,
                    tax = 0L,
                    total = 1L,
                ),
                accounts,
                null,
                postedAt,
            )
        }
    }

    private fun voucher(
        type: VoucherType,
        taxable: Long = 10_000L,
        tax: Long = 1_800L,
        roundOff: Long = 0L,
        total: Long = taxable + tax + roundOff,
        charges: List<VoucherCharge> = emptyList(),
        number: String = "INV-0001",
    ): Voucher = Voucher(
        id = "voucher-${type.name.lowercase()}",
        firmId = "firm-1",
        financialYearId = "fy-1",
        type = type,
        number = number,
        date = LocalDate(2026, 5, 10),
        partyId = "party-1",
        status = VoucherStatus.POSTED,
        placeOfSupplyStateCode = "27",
        charges = charges,
        subtotal = Money.of(taxable),
        taxable = Money.of(taxable),
        tax = Money.of(tax),
        roundOff = Money.of(roundOff),
        total = Money.of(total),
        createdAt = createdAt,
        updatedAt = postedAt,
        postedAt = postedAt,
    )

    private fun fixtureAccounts(): FirmLedgerAccounts = FirmLedgerAccounts(
        accountsReceivable = account(AccountCode.AR),
        accountsPayable = account(AccountCode.AP),
        outputGst = account(AccountCode.OUTPUT_GST),
        inputGst = account(AccountCode.INPUT_GST),
        roundOff = account(AccountCode.ROUND_OFF),
        sales = account(AccountCode.SALES),
        purchases = account(AccountCode.PURCHASES),
        cash = account(AccountCode.CASH),
    )

    private fun account(code: AccountCode): Account = Account(
        id = "account-${code.name}",
        firmId = "firm-1",
        name = code.accountName,
        type = code.accountType,
        code = code,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun List<JournalEntry>.forCode(code: AccountCode): JournalEntry =
        single { it.accountId == accounts.accountFor(code).id }

    private fun assertBalanced(entries: List<JournalEntry>) {
        val debit = entries.fold(Money.ZERO) { total, entry -> total + entry.debit }
        val credit = entries.fold(Money.ZERO) { total, entry -> total + entry.credit }
        assertEquals(debit, credit, "Debit $debit must equal credit $credit")
    }
}
