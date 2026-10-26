package app.openbill.ui.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.AgeingBuckets
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.format.Fmt
import app.openbill.ui.pdf.InvoiceLayoutEngine
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/** One party's position: signed balance (positive = they owe us) and its ageing. */
data class PartyPosition(val party: Party, val ageing: AgeingBuckets) {
    val balance: Money get() = ageing.total
}

/** Dashboard figures: receivables, payables and money in hand. */
data class MoneyOverview(
    val positions: List<PartyPosition>,
    val moneyAccounts: List<Pair<Account, Money>>,
) {
    val receivable: Money get() = positions.fold(Money.ZERO) { t, p -> if (p.balance > Money.ZERO) t + p.balance else t }
    val payable: Money get() = positions.fold(Money.ZERO) { t, p -> if (p.balance < Money.ZERO) t - p.balance else t }
    val cashAndBank: Money get() = moneyAccounts.fold(Money.ZERO) { t, (_, b) -> t + b }

    fun receivables() = positions.filter { it.balance > Money.ZERO }.sortedByDescending { it.balance }
    fun payables() = positions.filter { it.balance < Money.ZERO }.sortedBy { it.balance }

    companion object {
        fun of(
            parties: List<Party>,
            movements: List<PartyMovement>,
            accounts: List<Account>,
            balances: Map<String, Money>,
            asOf: LocalDate,
        ): MoneyOverview {
            val byParty = movements.groupBy { it.partyId }
            return MoneyOverview(
                positions = parties.map { p -> PartyPosition(p, PartyAgeing.of(signedOpening(p), byParty[p.id].orEmpty(), asOf)) }
                    .filter { it.balance != Money.ZERO },
                moneyAccounts = accounts.filter { it.type.isMoney }.map { it to (balances[it.id] ?: it.openingBalance) },
            )
        }

        fun signedOpening(p: Party): Money =
            if (p.openingBalanceType == OpeningBalanceType.DEBIT) p.openingBalance else -p.openingBalance
    }
}

val AccountType.isMoney: Boolean get() = this == AccountType.CASH || this == AccountType.BANK

/** Live [MoneyOverview] for the firm. */
@Composable
fun rememberMoneyOverview(container: AppContainer, firmId: String): State<MoneyOverview?> = remember(firmId) {
    val repos = container.repositories
    combine(
        repos.parties.observeParties(firmId),
        repos.ledger.observePartyMovements(firmId),
        repos.ledger.observeAccounts(firmId),
        repos.ledger.observeAccountBalances(firmId),
    ) { parties, movements, accounts, balances -> MoneyOverview.of(parties, movements, accounts, balances, container.today()) }
}.collectAsState(initial = null)

fun moneyTitle(type: VoucherType): String = when (type) {
    VoucherType.PAYMENT_IN -> "Payment in"
    VoucherType.PAYMENT_OUT -> "Payment out"
    VoucherType.EXPENSE -> "Expense"
    VoucherType.CONTRA -> "Transfer"
    VoucherType.SALE -> "Invoice"
    VoucherType.PURCHASE -> "Purchase bill"
    VoucherType.CREDIT_NOTE -> "Credit note"
    VoucherType.DEBIT_NOTE -> "Debit note"
    else -> type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

object Reminders {
    /** WhatsApp-friendly payment reminder; plain text, no links other than an optional UPI one. */
    fun text(
        firmName: String,
        partyName: String,
        balance: Money,
        open: List<OutstandingInvoice>,
        today: LocalDate,
        upiId: String? = null,
    ): String = buildString {
        appendLine("Dear $partyName,")
        appendLine()
        appendLine("This is a friendly reminder from $firmName. Your outstanding balance is ${Fmt.inr(balance)}.")
        if (open.isNotEmpty()) {
            appendLine()
            open.sortedBy { it.date }.forEach { inv ->
                val days = (today.toEpochDays() - inv.date.toEpochDays())
                appendLine("• ${inv.number ?: "Invoice"} dated ${Fmt.date(inv.date)}: ${Fmt.inr(inv.outstanding)} ($days days)")
            }
        }
        upiId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("Pay by UPI to $it")
            appendLine(InvoiceLayoutEngine.upiUri(it, firmName, balance.paise, "dues"))
        }
        appendLine()
        append("Please ignore if already paid. Thank you!")
    }
}
