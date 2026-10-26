package app.openbill.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.usecase.AllocatePayment
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AmountRow
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.StatusChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch

/** A posted payment, expense or transfer: details, allocations, allocate the advance, cancel. */
@Composable
fun MoneyDetailScreen(container: AppContainer, session: Session, voucherId: String, onBack: () -> Unit) {
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val voucher by remember(voucherId) { repos.vouchers.observeVoucher(voucherId) }.collectAsState(initial = null)
    val details by remember(voucherId) { repos.payments.observePaymentDetails(voucherId) }.collectAsState(initial = null)
    val allocations by remember(voucherId) { repos.payments.observeAllocationsForPayment(voucherId) }.collectAsState(initial = emptyList())
    val accounts by remember { repos.ledger.observeAccounts(session.firm.id) }.collectAsState(initial = emptyList())
    var names by remember { mutableStateOf(emptyMap<String, String>()) }
    var open by remember { mutableStateOf<List<OutstandingInvoice>>(emptyList()) }
    var cancelOpen by remember { mutableStateOf(false) }
    val v = voucher

    LaunchedEffect(v?.partyId, allocations, v?.status) {
        val pid = v?.partyId ?: return@LaunchedEffect
        val m = mutableMapOf<String, String>()
        repos.parties.findParty(pid)?.let { m[pid] = it.name }
        allocations.forEach { a -> repos.vouchers.findVoucher(a.invoiceVoucherId)?.number?.let { m[a.invoiceVoucherId] = it } }
        names = m
        open = if (v.type.isPayment() && v.status == VoucherStatus.POSTED) {
            runCatching { repos.payments.outstandingInvoices(v.firmId, pid, MoneyVouchers.invoiceTypeFor(v.type)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = { AppTopBar(v?.let { (it.number ?: "Draft") + " · " + moneyTitle(it.type) } ?: "", onBack = onBack) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        if (v == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val accountNames = accounts.associate { it.id to it.name }
        val allocated = allocations.fold(Money.ZERO) { t, a -> t + a.amount }
        val advance = v.total - allocated
        CenteredContent(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ElevatedCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(moneyCounterparty(v, names + accountNames), style = MaterialTheme.typography.titleLarge)
                        StatusChip(v.status)
                        AmountRow("Amount", v.total, emphasize = true)
                        if (v.type == VoucherType.EXPENSE && v.tax > Money.ZERO) {
                            AmountRow("Expense value", v.taxable)
                            AmountRow("Input GST (ITC)", v.tax)
                        }
                        AmountRow("Date", Fmt.dateLong(v.date))
                        details?.let { d ->
                            AmountRow(
                                if (v.type == VoucherType.PAYMENT_IN) "Received into" else if (v.type == VoucherType.CONTRA) "From" else "Paid from",
                                accountNames[d.accountId] ?: "",
                            )
                            AmountRow("Mode", d.method.name.lowercase().replaceFirstChar { it.uppercase() }.replace("Upi", "UPI"))
                            d.instrumentNumber?.let { AmountRow(if (d.method == PaymentMethod.CHEQUE) "Cheque no." else "Reference", it) }
                        }
                        v.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (v.type.isPayment()) {
                    SectionHeader("Settled ${if (v.type == VoucherType.PAYMENT_IN) "invoices" else "bills"}")
                    if (allocations.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium)
                    allocations.forEach { a -> AmountRow(names[a.invoiceVoucherId] ?: "Invoice", a.amount) }
                    AmountRow("On account (advance)", advance)
                    if (v.status == VoucherStatus.POSTED && advance > Money.ZERO && open.isNotEmpty()) {
                        PrimaryButton("Settle open ${if (v.type == VoucherType.PAYMENT_IN) "invoices" else "bills"} (oldest first)", {
                            scope.launch {
                                runCatching { AllocatePayment(repos.payments)(v.id, MoneyVouchers.fifo(open, advance), container.now()) }
                                    .onSuccess { messages?.show("Advance allocated") }
                                    .onFailure { messages?.show(it.message ?: "Could not allocate") }
                            }
                        }, Modifier.fillMaxWidth())
                    }
                }
                if (v.status == VoucherStatus.POSTED) {
                    OutlinedButton(
                        onClick = { cancelOpen = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel ${moneyTitle(v.type).lowercase()}") }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    if (cancelOpen) {
        var reason by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { cancelOpen = false },
            title = { Text("Cancel this entry?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("It keeps its number; its ledger entries are reversed and its allocations stop counting.")
                    FormField("Reason *", reason, { reason = it; error = null }, error = error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (reason.isBlank()) { error = "A reason is required"; return@TextButton }
                    scope.launch {
                        runCatching { container.cancelVoucher(voucherId, reason.trim(), container.now()) }
                            .onSuccess { cancelOpen = false; messages?.show("Cancelled") }
                            .onFailure { error = it.message }
                    }
                }) { Text("Cancel entry", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancelOpen = false }) { Text("Keep") } },
        )
    }
}
