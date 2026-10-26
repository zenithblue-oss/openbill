package app.openbill.ui.money

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.usecase.MoneyVoucherInput
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.DateField
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Records a payment in/out (with allocation to open invoices or bills; the rest stays on
 * account as an advance), an expense (category, optional input GST) or a contra transfer.
 */
@Composable
fun MoneyEditorScreen(
    container: AppContainer,
    session: Session,
    type: VoucherType,
    initialPartyId: String?,
    invoiceId: String?,
    onDone: (voucherId: String?) -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
) {
    val firmId = session.firm.id
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val accounts by remember { repos.ledger.observeAccounts(firmId) }.collectAsState(initial = emptyList())
    val parties by remember { repos.parties.observeParties(firmId) }.collectAsState(initial = emptyList())
    LaunchedEffect(firmId) { runCatching { repos.ledger.ensureSystemAccounts(firmId, container.now()) } }
    val moneyAccounts = accounts.filter { it.type.isMoney }
    val categories = accounts.filter { it.type == AccountType.EXPENSE && it.code == null }

    var partyId by rememberSaveable { mutableStateOf(initialPartyId) }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(container.today()) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var toAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var gst by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CASH) }
    var refNo by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<List<OutstandingInvoice>>(emptyList()) }
    val alloc = remember { mutableStateMapOf<String, String>() }
    // Split tender: further modes for the same payment, each saved as its own payment voucher.
    val extra = remember { mutableStateListOf<Tender>() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    backHandler(true) { onDone(null) }

    val account = moneyAccounts.firstOrNull { it.id == accountId }
        ?: moneyAccounts.firstOrNull { it.code == AccountCode.CASH }
    val party = parties.firstOrNull { it.id == partyId }
    val amountMoney = Parse.money(amount)?.let { a -> extra.fold(a) { t, x -> t + (Parse.money(x.amount) ?: Money.ZERO) } }
    val allocated = alloc.values.fold(Money.ZERO) { t, s -> t + (Parse.money(s) ?: Money.ZERO) }

    LaunchedEffect(partyId) {
        alloc.clear()
        val pid = partyId
        open = if (pid != null && type.isPayment()) {
            runCatching { repos.payments.outstandingInvoices(firmId, pid, MoneyVouchers.invoiceTypeFor(type)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        open.firstOrNull { it.voucherId == invoiceId }?.let { inv ->
            alloc[inv.voucherId] = inv.outstanding.format()
            if (amount.isBlank()) amount = inv.outstanding.format()
        }
    }

    fun autoAllocate() {
        alloc.clear()
        MoneyVouchers.fifo(open, amountMoney ?: Money.ZERO).forEach { alloc[it.invoiceVoucherId] = it.amount.format() }
    }

    fun submit() {
        val amt = Parse.money(amount)?.takeIf { it > Money.ZERO } ?: run { error = "Enter an amount above zero"; return }
        val acc = account ?: run { error = "Choose an account"; return }
        val fy = session.financialYearFor(date) ?: run { error = "No financial year covers this date"; return }
        if (type.isPayment() && party == null) { error = "Choose a party"; return }
        if (type == VoucherType.EXPENSE && categoryId == null) { error = "Choose a category"; return }
        if (type == VoucherType.CONTRA && (toAccountId == null || toAccountId == acc.id)) { error = "Choose a different destination account"; return }
        if (method != PaymentMethod.CASH && refNo.isBlank()) { error = "Enter the ${refLabel(method).lowercase()}"; return }
        val tax = Parse.money(gst) ?: run { error = "Enter a valid GST amount"; return }
        val requests = alloc.mapNotNull { (id, s) -> Parse.money(s)?.takeIf { it > Money.ZERO }?.let { AllocationRequest(id, it) } }
        if (alloc.values.any { Parse.money(it) == null }) { error = "Check the allocated amounts"; return }
        val tenders = listOf(Tender(amount, acc.id, method, refNo)) + extra
        val amounts = tenders.map { t -> Parse.money(t.amount)?.takeIf { it > Money.ZERO } ?: run { error = "Enter an amount above zero for each mode"; return } }
        tenders.forEach { t ->
            if (t.accountId == null) { error = "Choose an account for each mode"; return }
            if (t.method != PaymentMethod.CASH && t.ref.isBlank()) { error = "Enter the ${refLabel(t.method).lowercase()}"; return }
        }
        val split = MoneyVouchers.splitAcross(requests, amounts)
        busy = true
        error = null
        val ids = tenders.map { container.newId() }
        val id = ids.first()
        scope.launch {
            runCatching { repos.transaction { tenders.forEachIndexed { i, t ->
                MoneyVouchers(repos.vouchers, repos.payments, repos.ledger, container.postVoucher).record(
                    MoneyVoucherInput(
                        type = type,
                        id = ids[i],
                        firmId = firmId,
                        financialYearId = fy.id,
                        stateCode = session.firm.stateCode,
                        date = date,
                        amount = amounts[i],
                        accountId = t.accountId!!,
                        method = t.method,
                        partyId = partyId.takeIf { type.isPayment() },
                        ledgerAccountId = if (type == VoucherType.EXPENSE) categoryId else toAccountId.takeIf { type == VoucherType.CONTRA },
                        inputTax = if (type == VoucherType.EXPENSE) tax else Money.ZERO,
                        instrumentNumber = t.ref.takeIf { t.method != PaymentMethod.CASH },
                        instrumentDate = date.takeIf { t.method == PaymentMethod.CHEQUE || t.method == PaymentMethod.BANK },
                        notes = notes,
                        allocations = split[i],
                    ),
                    container.now(),
                )
            } } }.onSuccess {
                messages?.show("${moneyTitle(type)} recorded")
                onDone(id)
            }.onFailure { e ->
                busy = false
                error = e.message ?: "Could not record"
            }
        }
    }

    Scaffold(topBar = { AppTopBar(moneyTitle(type), onBack = { onDone(null) }) }) { padding ->
        CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            FormColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                if (type.isPayment()) {
                    PickerField(if (type == VoucherType.PAYMENT_IN) "Received from *" else "Paid to *", party?.name.orEmpty(), { pick = "party" })
                }
                if (type == VoucherType.EXPENSE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PickerField("Category *", categories.firstOrNull { it.id == categoryId }?.name.orEmpty(), { pick = "category" }, Modifier.weight(1f))
                        TextButton(onClick = { newCategory = "" }) { Text("New") }
                    }
                }
                FormField("Amount *", amount, { amount = it; error = null }, keyboardType = KeyboardType.Decimal, prefix = "₹")
                if (type == VoucherType.EXPENSE) {
                    FormField(
                        "Input GST to claim (ITC)",
                        gst,
                        { gst = it },
                        keyboardType = KeyboardType.Decimal,
                        prefix = "₹",
                        supporting = "GST included in the amount that you can claim; leave blank if none",
                    )
                }
                DateField("Date", date, { date = it })
                PickerField(
                    when (type) {
                        VoucherType.PAYMENT_IN -> "Received into *"
                        VoucherType.CONTRA -> "From account *"
                        else -> "Paid from *"
                    },
                    account?.name.orEmpty(),
                    { pick = "account" },
                )
                if (type == VoucherType.CONTRA) {
                    PickerField("To account *", moneyAccounts.firstOrNull { it.id == toAccountId }?.name.orEmpty(), { pick = "to" })
                }
                SectionHeader("Mode")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PaymentMethod.entries.forEach { m ->
                        SelectableChip(m.name.lowercase().replaceFirstChar { it.uppercase() }.replace("Upi", "UPI"), method == m, { method = m })
                    }
                }
                if (method != PaymentMethod.CASH) FormField(refLabel(method) + " *", refNo, { refNo = it })
                if (type.isPayment()) {
                    extra.forEachIndexed { i, t ->
                        SectionHeader("Also paid by") { TextButton(onClick = { extra.removeAt(i) }) { Text("Remove") } }
                        FormField("Amount *", t.amount, { v -> extra[i] = t.copy(amount = v); error = null }, keyboardType = KeyboardType.Decimal, prefix = "₹")
                        PickerField("Account *", moneyAccounts.firstOrNull { it.id == t.accountId }?.name.orEmpty(), { pick = "tender:$i" })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            PaymentMethod.entries.forEach { m ->
                                SelectableChip(m.name.lowercase().replaceFirstChar { it.uppercase() }.replace("Upi", "UPI"), t.method == m, { extra[i] = t.copy(method = m) })
                            }
                        }
                        if (t.method != PaymentMethod.CASH) FormField(refLabel(t.method) + " *", t.ref, { v -> extra[i] = t.copy(ref = v) })
                    }
                    TextButton(onClick = { extra.add(Tender(accountId = account?.id)) }) { Text("Split: add another payment mode") }
                    if (extra.isNotEmpty()) Text("Total ${Fmt.inr(amountMoney ?: Money.ZERO)}", style = MaterialTheme.typography.titleMedium)
                }
                FormField("Notes", notes, { notes = it }, singleLine = false)

                if (type.isPayment() && open.isNotEmpty()) {
                    SectionHeader("Settle ${if (type == VoucherType.PAYMENT_IN) "invoices" else "bills"}") {
                        TextButton(onClick = { autoAllocate() }) { Text("Auto (oldest first)") }
                    }
                    open.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.number ?: "Unnumbered", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${Fmt.date(inv.date)} · due ${Fmt.inr(inv.outstanding)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedTextField(
                                value = alloc[inv.voucherId].orEmpty(),
                                onValueChange = { alloc[inv.voucherId] = it; error = null },
                                singleLine = true,
                                prefix = { Text("₹") },
                                placeholder = { Text("0") },
                                modifier = Modifier.width(140.dp),
                            )
                        }
                    }
                    val advance = (amountMoney ?: Money.ZERO) - allocated
                    Text(
                        if (advance < Money.ZERO) "Allocated ${Fmt.inr(allocated)} is more than the amount"
                        else "Allocated ${Fmt.inr(allocated)} · on account (advance) ${Fmt.inr(advance)}",
                        color = if (advance < Money.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (type.isPayment() && party != null) {
                    Text(
                        "No open ${if (type == VoucherType.PAYMENT_IN) "invoices" else "bills"}; the amount is kept on account as an advance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("Save", { submit() }, Modifier.fillMaxWidth(), busy = busy, busyText = "Saving…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    when (pick) {
        "party" -> PickerSheet(
            title = "Party",
            options = parties.sortedBy { (it.type == PartyType.SUPPLIER) == (type == VoucherType.PAYMENT_IN) },
            label = { it.name },
            supporting = { it.phone },
            selected = { it.id == partyId },
            key = { it.id },
            searchable = true,
            emptyText = "Add a party first.",
            onSelect = { partyId = it.id; pick = null },
            onDismiss = { pick = null },
        )
        "account", "to" -> PickerSheet(
            title = "Account",
            options = moneyAccounts,
            label = { it.name },
            supporting = { if (it.type == AccountType.CASH) "Cash" else "Bank" },
            selected = { it.id == (if (pick == "to") toAccountId else account?.id) },
            key = { it.id },
            onSelect = { a: Account -> if (pick == "to") toAccountId = a.id else accountId = a.id; pick = null },
            onDismiss = { pick = null },
        )
        "category" -> PickerSheet(
            title = "Category",
            options = categories,
            label = { it.name },
            selected = { it.id == categoryId },
            key = { it.id },
            emptyText = "No categories yet. Use New to add one, such as Rent or Salary.",
            onSelect = { categoryId = it.id; pick = null },
            onDismiss = { pick = null },
        )
        else -> pick?.removePrefix("tender:")?.toIntOrNull()?.takeIf { it in extra.indices }?.let { i -> PickerSheet(
            title = "Account",
            options = moneyAccounts,
            label = { it.name },
            supporting = { if (it.type == AccountType.CASH) "Cash" else "Bank" },
            selected = { it.id == extra[i].accountId },
            key = { it.id },
            onSelect = { a: Account -> extra[i] = extra[i].copy(accountId = a.id); pick = null },
            onDismiss = { pick = null },
        ) }
    }
    newCategory?.let { text ->
        var catError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { newCategory = null },
            title = { Text("New expense category") },
            text = { FormField("Name", text, { newCategory = it; catError = null }, error = catError, placeholder = "e.g. Rent") },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.trim()
                    if (n.isEmpty()) { catError = "Name is required"; return@TextButton }
                    val now = container.now()
                    val a = Account(container.newId(), firmId, n, AccountType.EXPENSE, createdAt = now, updatedAt = now)
                    scope.launch {
                        runCatching { repos.ledger.saveAccount(a) }
                            .onSuccess { categoryId = a.id; newCategory = null }
                            .onFailure { catError = "An account with this name exists" }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newCategory = null }) { Text("Cancel") } },
        )
    }
}

private fun refLabel(m: PaymentMethod): String = when (m) {
    PaymentMethod.UPI -> "UPI reference / UTR"
    PaymentMethod.CHEQUE -> "Cheque number"
    PaymentMethod.BANK -> "Bank reference"
    PaymentMethod.CASH -> "Reference"
}

private data class Tender(val amount: String = "", val accountId: String? = null, val method: PaymentMethod = PaymentMethod.UPI, val ref: String = "")
