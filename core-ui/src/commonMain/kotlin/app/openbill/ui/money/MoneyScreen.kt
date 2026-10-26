package app.openbill.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.usesPaymentAccount
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.CollapsingTopBar
import app.openbill.ui.components.FormField
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.StatusChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

enum class MoneyCard { RECEIVABLE, PAYABLE, CASH_BANK }

/** Dashboard card: to receive, to pay, and cash plus bank balances. */
@Composable
fun MoneyDashboardCard(container: AppContainer, session: Session, onOpen: (MoneyCard) -> Unit) {
    val overview by rememberMoneyOverview(container, session.firm.id)
    val o = overview ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = pageGutter, vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
            Figure("To receive", o.receivable, Modifier.weight(1f)) { onOpen(MoneyCard.RECEIVABLE) }
            Figure("To pay", o.payable, Modifier.weight(1f)) { onOpen(MoneyCard.PAYABLE) }
            Figure("Cash & bank", o.cashAndBank, Modifier.weight(1f)) { onOpen(MoneyCard.CASH_BANK) }
        }
    }
}

@Composable
private fun Figure(label: String, amount: Money, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClickLabel = "Open $label", onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.inrSigned(amount), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private val ALL_FROM = LocalDate(2000, 1, 1)
private val ALL_TO = LocalDate(2099, 12, 31)

/** Money tab: balances, quick actions, cash/bank accounts, documents and recent money vouchers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyScreen(
    container: AppContainer,
    session: Session,
    onNew: (VoucherType) -> Unit,
    onOpenVoucher: (Voucher) -> Unit,
    onAccount: (String) -> Unit,
    onOutstanding: (receivable: Boolean) -> Unit,
    onDocuments: (VoucherType) -> Unit,
) {
    val firmId = session.firm.id
    val repos = container.repositories
    val overview by rememberMoneyOverview(container, firmId)
    val accounts by remember(firmId) { repos.ledger.observeAccounts(firmId) }.collectAsState(initial = emptyList())
    val parties by remember(firmId) { repos.parties.observeParties(firmId) }.collectAsState(initial = emptyList())
    val vouchers by remember(firmId) { repos.vouchers.observeVouchers(firmId, ALL_FROM, ALL_TO) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val names = accounts.associate { it.id to it.name } + parties.associate { it.id to it.name }
    val recent = vouchers.filter { it.type.usesPaymentAccount() && it.deletedAt == null }
        .sortedWith(compareByDescending<Voucher> { it.date }.thenByDescending { it.createdAt }).take(50)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CollapsingTopBar("Money", scrollBehavior) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item(key = "card") {
                    MoneyDashboardCard(container, session) { c ->
                        when (c) {
                            MoneyCard.RECEIVABLE -> onOutstanding(true)
                            MoneyCard.PAYABLE -> onOutstanding(false)
                            MoneyCard.CASH_BANK -> overview?.moneyAccounts?.firstOrNull()?.let { onAccount(it.first.id) }
                        }
                    }
                }
                item(key = "actions") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pageGutter, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        listOf(VoucherType.PAYMENT_IN, VoucherType.PAYMENT_OUT, VoucherType.EXPENSE, VoucherType.CONTRA).forEach { t ->
                            FilledTonalButton(onClick = { onNew(t) }) { Text(moneyTitle(t)) }
                        }
                    }
                }
                item(key = "acc-h") {
                    SectionHeader("Cash & bank", Modifier.padding(horizontal = pageGutter)) {
                        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add account") }
                    }
                }
                items(overview?.moneyAccounts.orEmpty(), key = { "acc-" + it.first.id }) { (a, balance) ->
                    ListItem(
                        headlineContent = { Text(a.name) },
                        supportingContent = { Text(if (a.type == AccountType.CASH) "Cash" else "Bank") },
                        trailingContent = { Text(Fmt.inrSigned(balance), style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.clickable { onAccount(a.id) },
                    )
                }
                item(key = "out-h") { SectionHeader("Outstanding", Modifier.padding(horizontal = pageGutter)) }
                item(key = "out-r") {
                    ListItem(
                        headlineContent = { Text("Receivables") },
                        supportingContent = { Text("Customers who owe you, with ageing") },
                        trailingContent = { Text(Fmt.inr(overview?.receivable ?: Money.ZERO)) },
                        modifier = Modifier.clickable { onOutstanding(true) },
                    )
                }
                item(key = "out-p") {
                    ListItem(
                        headlineContent = { Text("Payables") },
                        supportingContent = { Text("Suppliers you owe, with ageing") },
                        trailingContent = { Text(Fmt.inr(overview?.payable ?: Money.ZERO)) },
                        modifier = Modifier.clickable { onOutstanding(false) },
                    )
                }
                item(key = "docs-h") { SectionHeader("Documents", Modifier.padding(horizontal = pageGutter)) }
                items(listOf(VoucherType.QUOTATION, VoucherType.PROFORMA, VoucherType.DELIVERY_CHALLAN), key = { "doc-" + it.name }) { t ->
                    ListItem(
                        headlineContent = { Text(DOC_LIST_TITLES.getValue(t)) },
                        supportingContent = { Text("${vouchers.count { it.type == t }} documents") },
                        modifier = Modifier.clickable { onDocuments(t) },
                    )
                }
                item(key = "recent-h") { SectionHeader("Recent payments and expenses", Modifier.padding(horizontal = pageGutter)) }
                if (recent.isEmpty()) {
                    item(key = "recent-empty") {
                        Text(
                            "Payments, expenses and transfers you record appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = pageGutter),
                        )
                    }
                }
                items(recent, key = { it.id }) { v -> MoneyVoucherRow(v, names) { onOpenVoucher(v) } }
            }
        }
    }
    if (adding || editing != null) {
        AccountDialog(container, session, editing, onDismiss = { adding = false; editing = null })
    }
}

val DOC_LIST_TITLES = mapOf(
    VoucherType.QUOTATION to "Quotations",
    VoucherType.PROFORMA to "Proforma invoices",
    VoucherType.DELIVERY_CHALLAN to "Delivery challans",
)

/** Who or what a money voucher is for: the party, expense category or transfer destination. */
fun moneyCounterparty(v: Voucher, names: Map<String, String>): String = when (v.type) {
    VoucherType.EXPENSE -> v.ledgerAccountId?.let { names[it] } ?: "Expense"
    VoucherType.CONTRA -> "Transfer to " + (v.ledgerAccountId?.let { names[it] } ?: "account")
    else -> v.partyId?.let { names[it] } ?: "No party"
}

@Composable
fun MoneyVoucherRow(v: Voucher, names: Map<String, String>, onClick: () -> Unit) {
    val inflow = v.type == VoucherType.PAYMENT_IN
    ListItem(
        headlineContent = { Text(moneyCounterparty(v, names), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(moneyTitle(v.type), v.number, Fmt.dateLong(v.date)).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    (if (inflow) "+" else if (v.type == VoucherType.CONTRA) "" else "−") + Fmt.inr(v.total),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (inflow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                StatusChip(v.status)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Create or edit a cash/bank account (name, type, opening balance). */
@Composable
fun AccountDialog(container: AppContainer, session: Session, account: Account?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(account?.name.orEmpty()) }
    var bank by remember { mutableStateOf(account?.type != AccountType.CASH) }
    var opening by remember { mutableStateOf(account?.openingBalance?.takeIf { it != Money.ZERO }?.format().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "New account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FormField("Name *", name, { name = it; error = null }, placeholder = "e.g. HDFC current a/c")
                if (account?.code == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SelectableChip("Bank", bank, { bank = true })
                        SelectableChip("Cash", !bank, { bank = false })
                    }
                }
                FormField("Opening balance", opening, { opening = it; error = null }, keyboardType = KeyboardType.Decimal, prefix = "₹", supporting = "Negative for an overdraft")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                val ob = if (opening.isBlank()) Money.ZERO else Parse.money(opening.trim().removePrefix("-"))?.let { if (opening.trim().startsWith("-")) -it else it }
                when {
                    n.isEmpty() -> error = "Name is required"
                    ob == null -> error = "Enter a valid amount"
                    else -> scope.launch {
                        val now = container.now()
                        val a = account?.copy(name = n, openingBalance = ob, type = if (account.code != null) account.type else if (bank) AccountType.BANK else AccountType.CASH, updatedAt = now)
                            ?: Account(container.newId(), session.firm.id, n, if (bank) AccountType.BANK else AccountType.CASH, openingBalance = ob, createdAt = now, updatedAt = now)
                        runCatching { container.repositories.ledger.saveAccount(a) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = if (it.message.orEmpty().contains("UNIQUE")) "An account with this name exists" else it.message }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
