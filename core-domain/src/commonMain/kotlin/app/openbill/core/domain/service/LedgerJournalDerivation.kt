package app.openbill.core.domain.service

import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.model.Money
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class LedgerJournalDerivation(
    private val entryId: (Instant) -> String = {
        Ulid.create(it.toEpochMilliseconds()).toString()
    },
) {
    fun derivePosted(
        voucher: Voucher,
        accounts: FirmLedgerAccounts,
        paymentAccountId: String?,
        at: Instant,
    ): List<JournalEntry> {
        require(voucher.firmId == accounts.firmId) {
            "Voucher and ledger accounts belong to different firms"
        }
        require(voucher.type.postsLedger()) {
            "Voucher type ${voucher.type} does not post to the ledger"
        }
        require(voucher.status == VoucherStatus.POSTED) {
            "Only posted vouchers can derive journal entries"
        }
        require(voucher.total > Money.ZERO) { "Voucher total must be positive" }

        val entries = when (voucher.type) {
            VoucherType.SALE -> saleEntries(voucher, accounts, at, creditControl = true)
            VoucherType.CREDIT_NOTE -> saleEntries(voucher, accounts, at, creditControl = false)
            VoucherType.PURCHASE -> purchaseEntries(voucher, accounts, at, debitControl = true)
            VoucherType.DEBIT_NOTE -> purchaseEntries(voucher, accounts, at, debitControl = false)
            VoucherType.PAYMENT_IN -> {
                val accountId = requirePaymentAccount(voucher, paymentAccountId)
                listOfNotNull(
                    entry(voucher, accountId, debit = voucher.total, at = at),
                    entry(voucher, accounts.accountsReceivable.id, credit = voucher.total, at = at),
                )
            }

            VoucherType.PAYMENT_OUT -> {
                val accountId = requirePaymentAccount(voucher, paymentAccountId)
                listOfNotNull(
                    entry(voucher, accounts.accountsPayable.id, debit = voucher.total, at = at),
                    entry(voucher, accountId, credit = voucher.total, at = at),
                )
            }

            else -> error("Unsupported ledger voucher type ${voucher.type}")
        }

        requireBalanced(entries)
        return entries
    }

    fun deriveReversal(
        voucher: Voucher,
        entries: List<JournalEntry>,
        at: Instant,
    ): List<JournalEntry> {
        val cancellationDate = at.toLocalDateTime(TimeZone.UTC).date
        val label = voucher.number ?: voucher.id
        return entries
            .filter { it.voucherId == voucher.id }
            .map { entry ->
                entry.copy(
                    id = entryId(at),
                    date = cancellationDate,
                    debit = entry.credit,
                    credit = entry.debit,
                    narration = "Reversal of $label",
                    createdAt = at,
                )
            }
    }

    private fun saleEntries(
        voucher: Voucher,
        accounts: FirmLedgerAccounts,
        at: Instant,
        creditControl: Boolean,
    ): List<JournalEntry> {
        val charges = includedCharges(voucher)
        val salesAmount = voucher.taxable + charges.amount
        val gstAmount = voucher.tax + charges.tax
        val controlEntry = if (creditControl) {
            entry(voucher, accounts.accountsReceivable.id, debit = voucher.total, at = at)
        } else {
            entry(voucher, accounts.accountsReceivable.id, credit = voucher.total, at = at)
        }
        return listOfNotNull(
            controlEntry,
            if (creditControl) {
                entry(voucher, accounts.sales.id, credit = salesAmount, at = at)
            } else {
                entry(voucher, accounts.sales.id, debit = salesAmount, at = at)
            },
            if (creditControl) {
                entry(voucher, accounts.outputGst.id, credit = gstAmount, at = at)
            } else {
                entry(voucher, accounts.outputGst.id, debit = gstAmount, at = at)
            },
            roundOffEntry(voucher, accounts, at, credit = creditControl),
        )
    }

    private fun purchaseEntries(
        voucher: Voucher,
        accounts: FirmLedgerAccounts,
        at: Instant,
        debitControl: Boolean,
    ): List<JournalEntry> {
        val charges = includedCharges(voucher)
        val purchaseAmount = voucher.taxable + charges.amount
        val gstAmount = voucher.tax + charges.tax
        val controlEntry = if (debitControl) {
            entry(voucher, accounts.accountsPayable.id, credit = voucher.total, at = at)
        } else {
            entry(voucher, accounts.accountsPayable.id, debit = voucher.total, at = at)
        }
        return listOfNotNull(
            controlEntry,
            if (debitControl) {
                entry(voucher, accounts.purchases.id, debit = purchaseAmount, at = at)
            } else {
                entry(voucher, accounts.purchases.id, credit = purchaseAmount, at = at)
            },
            if (debitControl) {
                entry(voucher, accounts.inputGst.id, debit = gstAmount, at = at)
            } else {
                entry(voucher, accounts.inputGst.id, credit = gstAmount, at = at)
            },
            roundOffEntry(voucher, accounts, at, credit = !debitControl),
        )
    }

    private fun roundOffEntry(
        voucher: Voucher,
        accounts: FirmLedgerAccounts,
        at: Instant,
        credit: Boolean,
    ): JournalEntry? = when {
        voucher.roundOff > Money.ZERO && credit ->
            entry(voucher, accounts.roundOff.id, credit = voucher.roundOff, at = at)

        voucher.roundOff > Money.ZERO ->
            entry(voucher, accounts.roundOff.id, debit = voucher.roundOff, at = at)

        voucher.roundOff < Money.ZERO && credit ->
            entry(voucher, accounts.roundOff.id, debit = -voucher.roundOff, at = at)

        voucher.roundOff < Money.ZERO ->
            entry(voucher, accounts.roundOff.id, credit = -voucher.roundOff, at = at)

        else -> null
    }

    private data class IncludedCharges(
        val amount: Money,
        val tax: Money,
    )

    private fun includedCharges(voucher: Voucher): IncludedCharges {
        val headerBase = voucher.taxable + voucher.tax + voucher.roundOff
        val chargeAmount = voucher.charges.fold(Money.ZERO) { total, charge ->
            total + charge.amount
        }
        val chargeTax = voucher.charges.fold(Money.ZERO) { total, charge ->
            total + charge.taxAmount
        }
        val chargeTotal = chargeAmount + chargeTax
        val extra = voucher.total - headerBase
        return when {
            extra == Money.ZERO -> IncludedCharges(Money.ZERO, Money.ZERO)
            extra == chargeTotal -> IncludedCharges(chargeAmount, chargeTax)
            else -> throw IllegalArgumentException(
                "Voucher ${voucher.id} totals are inconsistent with its header, charges, and round-off",
            )
        }
    }

    private fun requirePaymentAccount(voucher: Voucher, paymentAccountId: String?): String {
        require(voucher.type.isPayment()) { "Voucher is not a payment" }
        require(paymentAccountId != null) { "Payment vouchers require a payment account" }
        require(voucher.taxable == Money.ZERO && voucher.tax == Money.ZERO) {
            "Payment vouchers cannot have taxable value or tax"
        }
        require(voucher.roundOff == Money.ZERO) {
            "Payment vouchers cannot have round-off"
        }
        return paymentAccountId
    }

    private fun entry(
        voucher: Voucher,
        accountId: String,
        debit: Money = Money.ZERO,
        credit: Money = Money.ZERO,
        at: Instant,
    ): JournalEntry? {
        require(debit >= Money.ZERO && credit >= Money.ZERO) {
            "Journal entry amounts cannot be negative"
        }
        if (debit == Money.ZERO && credit == Money.ZERO) return null
        return JournalEntry(
            id = entryId(at),
            firmId = voucher.firmId,
            voucherId = voucher.id,
            accountId = accountId,
            date = voucher.date,
            debit = debit,
            credit = credit,
            narration = null,
            createdAt = at,
        )
    }

    private fun requireBalanced(entries: List<JournalEntry>) {
        val debit = entries.fold(Money.ZERO) { total, entry -> total + entry.debit }
        val credit = entries.fold(Money.ZERO) { total, entry -> total + entry.credit }
        require(debit == credit) {
            "Journal entries do not balance: debit $debit, credit $credit"
        }
    }
}
