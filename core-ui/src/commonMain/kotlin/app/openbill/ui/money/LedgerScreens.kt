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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.AgeingBuckets
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AmountRow
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Cash or bank account: balance, running statement and manual reconciliation (bank). */
@Composable
fun AccountScreen(
    container: AppContainer,
    session: Session,
    accountId: String,
    onBack: () -> Unit,
    onOpenVoucher: (String, VoucherType) -> Unit,
) {
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val accounts by remember { repos.ledger.observeAccounts(session.firm.id) }.collectAsState(initial = null)
    val txns by remember(accountId) { repos.ledger.observeAccountTransactions(accountId) }.collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }
    val account = accounts?.firstOrNull { it.id == accountId }
    Scaffold(
        topBar = {
            AppTopBar(account?.name ?: "Account", onBack = onBack) {
                if (account != null) IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit account") }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val list = txns
        if (account == null || list == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val bank = account.type == AccountType.BANK
        var running = account.openingBalance
        val rows = list.map { t -> running += t.entry.debit - t.entry.credit; t to running }
        val cleared = list.filter { it.entry.clearedOn != null }.fold(account.openingBalance) { b, t -> b + t.entry.debit - t.entry.credit }
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item {
                    Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                        AmountRow("Opening balance", Fmt.inrSigned(account.openingBalance))
                        AmountRow("Balance", Fmt.inrSigned(running), emphasize = true)
                        if (bank) {
                            AmountRow("Cleared (as per bank)", Fmt.inrSigned(cleared))
                            Text(
                                "Tick entries that appear on your bank statement to reconcile. Untick to undo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (rows.isEmpty()) item { SectionHeader("No transactions yet", Modifier.padding(horizontal = pageGutter)) }
                items(rows.reversed(), key = { it.first.entry.id }) { (t, balance) ->
                    val e = t.entry
                    ListItem(
                        leadingContent = if (bank) {
                            {
                                Checkbox(
                                    checked = e.clearedOn != null,
                                    onCheckedChange = { on ->
                                        scope.launch { runCatching { repos.ledger.setCleared(e.id, if (on) container.today() else null) } }
                                    },
                                )
                            }
                        } else null,
                        headlineContent = { Text(listOfNotNull(moneyTitle(t.voucherType), t.voucherNumber).joinToString(" "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(Fmt.date(e.date), e.narration, e.clearedOn?.let { "cleared ${Fmt.date(it)}" }).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    if (e.debit > Money.ZERO) "+" + Fmt.inr(e.debit) else "−" + Fmt.inr(e.credit),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (e.debit > Money.ZERO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(Fmt.inrSigned(balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.clickable { onOpenVoucher(e.voucherId, t.voucherType) },
                    )
                }
            }
        }
    }
    if (editing && account != null) AccountDialog(container, session, account, onDismiss = { editing = false })
}

@Composable
fun AgeingRow(b: AgeingBuckets, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        b.asList().forEach { (label, amount) ->
            Column {
                Text("$label days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.inr(if (amount < Money.ZERO) -amount else amount), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Receivables or payables by party with 0-30/31-60/61-90/90+ ageing. */
@Composable
fun OutstandingScreen(
    container: AppContainer,
    session: Session,
    receivable: Boolean,
    onBack: () -> Unit,
    onParty: (String) -> Unit,
) {
    val overview by rememberMoneyOverview(container, session.firm.id)
    Scaffold(topBar = { AppTopBar(if (receivable) "Receivables" else "Payables", onBack = onBack) }) { padding ->
        val o = overview
        if (o == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val rows = if (receivable) o.receivables() else o.payables()
        val total = rows.fold(AgeingBuckets()) { t, p -> t + p.ageing }
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item {
                    Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                        AmountRow(if (receivable) "Total to receive" else "Total to pay", Fmt.inr(if (receivable) o.receivable else o.payable), emphasize = true)
                        AgeingRow(total, Modifier.padding(top = Spacing.xs))
                    }
                }
                if (rows.isEmpty()) item { SectionHeader("Nothing outstanding", Modifier.padding(horizontal = pageGutter)) }
                items(rows, key = { it.party.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.party.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { AgeingRow(p.ageing) },
                        trailingContent = { Text(Fmt.inr(if (p.balance < Money.ZERO) -p.balance else p.balance), style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.clickable { onParty(p.party.id) },
                    )
                }
            }
        }
    }
}

private val LEDGER_FROM = LocalDate(2000, 1, 1)
private val LEDGER_TO = LocalDate(2099, 12, 31)

/** Party statement with running balance, ageing, reminder/statement sharing and payment entry. */
@Composable
fun PartyLedgerScreen(
    container: AppContainer,
    session: Session,
    partyId: String,
    onBack: () -> Unit,
    onRecordPayment: (VoucherType) -> Unit,
    onStatement: (() -> Unit)? = null,
) {
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val party by remember(partyId) { repos.parties.observeParty(partyId) }.collectAsState(initial = null)
    val ledger by remember(partyId) { repos.ledger.observePartyLedger(session.firm.id, partyId, LEDGER_FROM, LEDGER_TO) }.collectAsState(initial = null)
    val overview by rememberMoneyOverview(container, session.firm.id)
    val p = party
    Scaffold(
        topBar = {
            AppTopBar(p?.name ?: "Ledger", onBack = onBack) {
                if (onStatement != null) IconButton(onClick = onStatement) { Icon(Icons.Filled.List, contentDescription = "Statement report (CSV, PDF)") }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val l = ledger
        if (p == null || l == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val closing = l.closingBalance
        val ageing = overview?.positions?.firstOrNull { it.party.id == partyId }?.ageing ?: AgeingBuckets()
        fun share() = scope.launch {
            val text = if (closing > Money.ZERO) {
                val open = runCatching { repos.payments.outstandingInvoices(session.firm.id, partyId, VoucherType.SALE) }.getOrDefault(emptyList())
                Reminders.text(
                    session.firm.name, p.name, closing, open, container.today(),
                    container.invoiceStyles.style(session.firm.id).value.options.upiId,
                    Reminders.template(container.platform.settingsStore, session.firm.id),
                )
            } else {
                statementText(session.firm.name, p.name, l.openingBalance, l.entries.map { Triple(it.date, listOfNotNull(moneyTitle(it.voucherType), it.voucherNumber).joinToString(" "), it.net) })
            }
            container.platform.shareText(if (closing > Money.ZERO) "Payment reminder" else "Statement", text)?.let { messages?.show(it) }
        }
        var running = l.openingBalance
        val rows = l.entries.map { e -> running += e.net; e to running }
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item {
                    Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AmountRow("Opening balance", Fmt.inrSigned(l.openingBalance))
                        AmountRow(
                            when {
                                closing > Money.ZERO -> "Receivable"
                                closing < Money.ZERO -> "Payable / advance"
                                else -> "Settled"
                            },
                            Fmt.inr(if (closing < Money.ZERO) -closing else closing),
                            emphasize = true,
                        )
                        if (closing != Money.ZERO) AgeingRow(ageing)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilledTonalButton(onClick = { share() }) {
                                Icon(Icons.Filled.Share, contentDescription = null)
                                Text(if (closing > Money.ZERO) " Send reminder" else " Share statement")
                            }
                            OutlinedButton(onClick = { onRecordPayment(if (closing >= Money.ZERO) VoucherType.PAYMENT_IN else VoucherType.PAYMENT_OUT) }) {
                                Text(if (closing >= Money.ZERO) "Receive payment" else "Make payment")
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item { SectionHeader("No entries yet", Modifier.padding(horizontal = pageGutter)) }
                items(rows.reversed(), key = { it.first.id }) { (e, balance) ->
                    ListItem(
                        headlineContent = { Text(listOfNotNull(moneyTitle(e.voucherType), e.voucherNumber).joinToString(" ")) },
                        supportingContent = { Text(listOfNotNull(Fmt.date(e.date), e.narration).joinToString(" · ")) },
                        trailingContent = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    if (e.debit > Money.ZERO) "Dr " + Fmt.inr(e.debit) else "Cr " + Fmt.inr(e.credit),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(Fmt.inrSigned(balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Plain-text party statement: opening, each movement with running balance, closing. */
fun statementText(firmName: String, partyName: String, opening: Money, rows: List<Triple<LocalDate, String, Money>>): String = buildString {
    appendLine("Statement of account: $partyName")
    appendLine("From $firmName")
    appendLine()
    var bal = opening
    appendLine("Opening balance: ${Fmt.inrSigned(opening)}")
    rows.forEach { (date, label, net) ->
        bal += net
        appendLine("${Fmt.date(date)} $label ${if (net >= Money.ZERO) "Dr" else "Cr"} ${Fmt.inr(if (net < Money.ZERO) -net else net)} = ${Fmt.inrSigned(bal)}")
    }
    append("Closing balance: ${Fmt.inrSigned(bal)} (positive: you owe $firmName)")
}
