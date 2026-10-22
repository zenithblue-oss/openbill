package app.openbill.core.domain.model

import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

enum class AccountType {
    CASH,
    BANK,
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
}

val AccountType.isPaymentAccount: Boolean
    get() = this == AccountType.CASH || this == AccountType.BANK || this == AccountType.ASSET

enum class AccountCode(
    val accountName: String,
    val accountType: AccountType,
) {
    AR("Accounts Receivable", AccountType.ASSET),
    AP("Accounts Payable", AccountType.LIABILITY),
    OUTPUT_GST("Output GST", AccountType.LIABILITY),
    INPUT_GST("Input GST", AccountType.ASSET),
    ROUND_OFF("Round Off", AccountType.EXPENSE),
    SALES("Sales", AccountType.INCOME),
    PURCHASES("Purchases", AccountType.EXPENSE),
    CASH("Cash", AccountType.CASH),
}

data class Account(
    val id: String,
    val firmId: String,
    val name: String,
    val type: AccountType,
    val code: AccountCode? = null,
    val openingBalance: Money = Money.ZERO,
    val isDefault: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Account id cannot be blank" }
        require(firmId.isNotBlank()) { "Account firmId cannot be blank" }
        require(name.isNotBlank()) { "Account name cannot be blank" }
        require(updatedAt >= createdAt) { "Account updatedAt cannot precede createdAt" }
    }
}

data class FirmLedgerAccounts(
    val accountsReceivable: Account,
    val accountsPayable: Account,
    val outputGst: Account,
    val inputGst: Account,
    val roundOff: Account,
    val sales: Account,
    val purchases: Account,
    val cash: Account,
) {
    init {
        val accounts = listOf(
            accountsReceivable,
            accountsPayable,
            outputGst,
            inputGst,
            roundOff,
            sales,
            purchases,
            cash,
        )
        require(accounts.map { it.firmId }.distinct().size == 1) {
            "System accounts must belong to one firm"
        }
        require(accountsReceivable.code == AccountCode.AR) { "Accounts receivable code is invalid" }
        require(accountsPayable.code == AccountCode.AP) { "Accounts payable code is invalid" }
        require(outputGst.code == AccountCode.OUTPUT_GST) { "Output GST code is invalid" }
        require(inputGst.code == AccountCode.INPUT_GST) { "Input GST code is invalid" }
        require(roundOff.code == AccountCode.ROUND_OFF) { "Round off code is invalid" }
        require(sales.code == AccountCode.SALES) { "Sales code is invalid" }
        require(purchases.code == AccountCode.PURCHASES) { "Purchases code is invalid" }
        require(cash.code == AccountCode.CASH) { "Cash code is invalid" }
    }

    val firmId: String
        get() = accountsReceivable.firmId

    fun accountFor(code: AccountCode): Account = when (code) {
        AccountCode.AR -> accountsReceivable
        AccountCode.AP -> accountsPayable
        AccountCode.OUTPUT_GST -> outputGst
        AccountCode.INPUT_GST -> inputGst
        AccountCode.ROUND_OFF -> roundOff
        AccountCode.SALES -> sales
        AccountCode.PURCHASES -> purchases
        AccountCode.CASH -> cash
    }
}

data class JournalEntry(
    val id: String,
    val firmId: String,
    val voucherId: String,
    val accountId: String,
    val date: LocalDate,
    val debit: Money,
    val credit: Money,
    val narration: String? = null,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "JournalEntry id cannot be blank" }
        require(firmId.isNotBlank()) { "JournalEntry firmId cannot be blank" }
        require(voucherId.isNotBlank()) { "JournalEntry voucherId cannot be blank" }
        require(accountId.isNotBlank()) { "JournalEntry accountId cannot be blank" }
        require(debit >= Money.ZERO) { "JournalEntry debit cannot be negative" }
        require(credit >= Money.ZERO) { "JournalEntry credit cannot be negative" }
        require((debit > Money.ZERO) != (credit > Money.ZERO)) {
            "JournalEntry must have exactly one positive side"
        }
    }
}

data class PartyLedgerEntry(
    val id: String,
    val voucherId: String,
    val voucherNumber: String?,
    val voucherType: VoucherType,
    val date: LocalDate,
    val accountId: String,
    val accountCode: AccountCode,
    val narration: String?,
    val debit: Money,
    val credit: Money,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "PartyLedgerEntry id cannot be blank" }
        require(voucherId.isNotBlank()) { "PartyLedgerEntry voucherId cannot be blank" }
        require(accountId.isNotBlank()) { "PartyLedgerEntry accountId cannot be blank" }
        require(debit >= Money.ZERO) { "PartyLedgerEntry debit cannot be negative" }
        require(credit >= Money.ZERO) { "PartyLedgerEntry credit cannot be negative" }
    }

    val net: Money
        get() = debit - credit
}

data class PartyLedger(
    val firmId: String,
    val partyId: String,
    val openingBalance: Money,
    val entries: List<PartyLedgerEntry>,
) {
    init {
        require(firmId.isNotBlank()) { "PartyLedger firmId cannot be blank" }
        require(partyId.isNotBlank()) { "PartyLedger partyId cannot be blank" }
    }

    val closingBalance: Money
        get() = entries.fold(openingBalance) { balance, entry -> balance + entry.net }
}

data class PartyBalance(
    val debit: Money,
    val credit: Money,
) {
    init {
        require(debit >= Money.ZERO) { "PartyBalance debit cannot be negative" }
        require(credit >= Money.ZERO) { "PartyBalance credit cannot be negative" }
    }

    val net: Money
        get() = debit - credit
}

fun VoucherType.postsLedger(): Boolean = when (this) {
    VoucherType.SALE,
    VoucherType.PURCHASE,
    VoucherType.CREDIT_NOTE,
    VoucherType.DEBIT_NOTE,
    VoucherType.PAYMENT_IN,
    VoucherType.PAYMENT_OUT,
    -> true

    else -> false
}

fun VoucherType.isPayment(): Boolean =
    this == VoucherType.PAYMENT_IN || this == VoucherType.PAYMENT_OUT
