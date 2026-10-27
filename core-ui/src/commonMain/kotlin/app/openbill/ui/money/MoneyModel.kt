package app.openbill.ui.money

import app.openbill.ui.settings.SettingsStore
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/** Home card and Money tab figures, from SQL aggregates rather than the movement history. */
data class MoneyTotals(val receivable: Money, val payable: Money, val moneyAccounts: List<Pair<Account, Money>>) {
    val cashAndBank: Money get() = moneyAccounts.fold(Money.ZERO) { t, (_, b) -> t + b }
}

/** Live [MoneyTotals]: per-party balances grouped in SQL, and each cash/bank account's net. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberMoneyTotals(container: AppContainer, firmId: String): State<MoneyTotals?> = remember(firmId) {
    val repos = container.repositories
    val money = repos.ledger.observeAccounts(firmId).flatMapLatest { all ->
        val list = all.filter { it.type.isMoney }
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { a -> repos.ledger.observeAccountNet(a.id).map { a to a.openingBalance + it.first } }) { it.toList() }
    }
    combine(repos.ledger.observePartyBalances(firmId, container.today()), money) { balances, accounts ->
        MoneyTotals(
            receivable = balances.values.fold(Money.ZERO) { t, b -> if (b > Money.ZERO) t + b else t },
            payable = balances.values.fold(Money.ZERO) { t, b -> if (b < Money.ZERO) t - b else t },
            moneyAccounts = accounts,
        )
    }
}.collectAsState(initial = null)

/** Live [MoneyOverview] for the firm (with ageing: loads every receivable/payable movement). */
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
    /** Placeholders: {party} {firm} {balance} {invoices} (bulleted, may be empty) {upi} (may be empty). */
    const val DEFAULT_TEMPLATE = "Dear {party},\n\nThis is a friendly reminder from {firm}. Your outstanding balance is {balance}.\n{invoices}{upi}\nPlease ignore if already paid. Thank you!"

    private fun key(firmId: String) = "reminder.template.$firmId"
    fun template(store: SettingsStore, firmId: String): String =
        runCatching { store.getString(key(firmId)) }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE
    fun saveTemplate(store: SettingsStore, firmId: String, template: String) = runCatching { store.putString(key(firmId), template) }

    /** WhatsApp-friendly payment reminder; plain text, no links other than an optional UPI one. */
    fun text(
        firmName: String,
        partyName: String,
        balance: Money,
        open: List<OutstandingInvoice>,
        today: LocalDate,
        upiId: String? = null,
        template: String = DEFAULT_TEMPLATE,
    ): String {
        val invoices = if (open.isEmpty()) "" else open.sortedBy { it.date }.joinToString("", prefix = "\n") { inv ->
            val days = (today.toEpochDays() - inv.date.toEpochDays())
            "• ${inv.number ?: "Invoice"} dated ${Fmt.date(inv.date)}: ${Fmt.inr(inv.outstanding)} ($days days)\n"
        }
        val upi = upiId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "\nPay by UPI to $it\n${InvoiceLayoutEngine.upiUri(it, firmName, balance.paise, "dues")}\n" }.orEmpty()
        return template.replace("{party}", partyName).replace("{firm}", firmName).replace("{balance}", Fmt.inr(balance))
            .replace("{invoices}", invoices).replace("{upi}", upi)
    }
}
