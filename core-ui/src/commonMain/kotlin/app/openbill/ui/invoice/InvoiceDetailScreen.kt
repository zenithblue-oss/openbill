package app.openbill.ui.invoice

import app.openbill.core.domain.model.AccountCode
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.OrderFulfilment
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.isOrder
import app.openbill.core.domain.model.orderTarget
import app.openbill.core.model.Quantity
import app.openbill.ui.recurring.RecurringScheduleDialog
import app.openbill.ui.AppContainer
import app.openbill.core.domain.usecase.duplicateAsDraft
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
import app.openbill.ui.format.AmountInWords
import app.openbill.ui.format.Fmt
import app.openbill.core.model.Money
import app.openbill.ui.pdf.InvoicePdf
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/** Unit symbol for each catalogue item used on the voucher (custom lines have none). */
suspend fun unitsByItem(container: AppContainer, session: Session, lines: List<VoucherLine>): Map<String, String> {
    val repos = container.repositories
    val units = repos.catalog.observeUnits(session.firm.id).first().associate { it.id to it.symbol }
    return lines.mapNotNull { it.itemId }.distinct().mapNotNull { id ->
        repos.catalog.findItem(id)?.let { item -> units[item.unitId]?.let { id to it } }
    }.toMap()
}

/** Resolves units and party for a stored voucher and builds the printable document. */
suspend fun buildInvoiceDocument(container: AppContainer, session: Session, voucher: Voucher): InvoiceDocument {
    val repos = container.repositories
    val party = voucher.partyId?.let { repos.parties.findParty(it) }
    val firm = repos.firms.findFirm(session.firm.id) ?: session.firm
    // Custom item fields print after the line description when the business turns it on.
    val printed = if (!session.settings.printItemFields) voucher else voucher.copy(
        lines = voucher.lines.map { line ->
            val fields = line.itemId?.let { repos.catalog.findItem(it) }?.customFields.orEmpty()
                .filter { it.label in session.settings.itemFieldLabels && it.value.isNotBlank() }
            if (fields.isEmpty()) line else line.copy(description = line.description + " (" + fields.joinToString("; ") { "${it.label}: ${it.value}" } + ")")
        },
    )
    val upi = container.invoiceStyles.style(firm.id).value.options.upiId.trim()
    val payable = voucher.type in setOf(VoucherType.SALE, VoucherType.PROFORMA) && voucher.status != VoucherStatus.CANCELLED && voucher.currency == null
    val built = InvoiceDocument.build(printed, firm, session.settings, party, unitsByItem(container, session, voucher.lines)).let { d ->
        if (!payable || upi.isEmpty() || d.grandTotalPaise <= 0) d
        else d.copy(paymentLink = app.openbill.ui.pdf.InvoiceLayoutEngine.upiUri(upi, d.seller.name, d.grandTotalPaise, d.number))
    }
    // A short-closed order prints when and what was closed, and why.
    val doc = voucher.shortClosedAt?.let { at ->
        val closed = Orders.shortClosed(voucher, repos.vouchers.observeConversions(voucher.id).first())
        built.copy(
            details = built.details + listOfNotNull(
                "Short-closed on" to Fmt.date(at.toLocalDateTime(container.timeZone).date),
                closed.takeIf { it.isNotEmpty() }?.let { c -> "Not delivered" to voucher.lines.filter { it.id in c }.joinToString("; ") { "${it.description} ${Fmt.qty(c.getValue(it.id))}" } },
                voucher.shortCloseNote?.let { "Close note" to it },
            ),
        )
    } ?: built
    if (party == null || voucher.status != VoucherStatus.POSTED || !container.invoiceStyles.style(firm.id).value.options.showPartyBalance) return doc
    val current = repos.ledger.partyBalance(firm.id, party.id, voucher.date).net
    // This voucher's own receivable/payable lines, not the firm's whole movement history.
    val control = listOfNotNull(repos.ledger.findAccount(firm.id, AccountCode.AR), repos.ledger.findAccount(firm.id, AccountCode.AP)).map { it.id }.toSet()
    val effect = repos.ledger.journalEntriesForVoucher(voucher.id)
        .filter { it.accountId in control }.fold(Money.ZERO) { a, e -> a + e.debit - e.credit }
    fun drCr(m: Money) = Fmt.inr(if (m < Money.ZERO) -m else m) + if (m < Money.ZERO) " Cr" else " Dr"
    return doc.copy(partyBalance = "Previous balance ${drCr(current - effect)} · Current balance ${drCr(current)}")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDetailScreen(
    container: AppContainer,
    session: Session,
    voucherId: String,
    onBack: () -> Unit,
    onEditDraft: (String) -> Unit,
    onViewPdf: (String) -> Unit = {},
    onReturn: (String) -> Unit = {},
    /** Quotation/proforma/challan/order to a new document of the given type. */
    onConvert: (String, VoucherType) -> Unit = { _, _ -> },
    onRecordPayment: (Voucher) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onRecurring: () -> Unit = {},
) {
    val outstanding by remember(voucherId) { container.repositories.payments.observeInvoiceOutstanding(voucherId) }
        .collectAsState(initial = null)
    val voucher by remember(voucherId) { container.repositories.vouchers.observeVoucher(voucherId) }.collectAsState(initial = null)
    val conversions by remember(voucherId) { container.repositories.vouchers.observeConversions(voucherId) }.collectAsState(initial = emptyList())
    val converted = conversions.firstOrNull { it.status != VoucherStatus.CANCELLED && it.type != VoucherType.CREDIT_NOTE && it.type != VoucherType.DEBIT_NOTE }
    var recurringOpen by remember { mutableStateOf(false) }
    var party by remember { mutableStateOf<Party?>(null) }
    var units by remember { mutableStateOf(emptyMap<String, String>()) }
    var cancelOpen by remember { mutableStateOf(false) }
    var shortCloseOpen by remember { mutableStateOf(false) }
    var restoreOf by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(voucher?.partyId) {
        party = voucher?.partyId?.let { container.repositories.parties.findParty(it) }
    }
    LaunchedEffect(voucher?.lines) {
        voucher?.let { v -> units = runCatching { unitsByItem(container, session, v.lines) }.getOrDefault(emptyMap()) }
    }

    fun share(v: Voucher) {
        busy = true
        scope.launch {
            runCatching { InvoicePdf.share(container, session.firm.id, buildInvoiceDocument(container, session, v)) }
                .onSuccess { msg -> msg?.let { messages?.show(it) } }
                .onFailure { messages?.show("Could not create the PDF: ${it.message}") }
            busy = false
        }
    }

    // SMS or email in the user's own app: amount, balance still due, due date and a UPI link for that balance.
    fun send(v: Voucher, email: Boolean) {
        busy = true
        scope.launch {
            runCatching {
                val built = buildInvoiceDocument(container, session, v)
                val due = outstanding?.takeIf { v.type == VoucherType.SALE && v.status == VoucherStatus.POSTED }
                val doc = when {
                    due == null || built.paymentLink == null -> built
                    due.paise <= 0 -> built.copy(paymentLink = null)
                    due.paise < built.grandTotalPaise -> built.copy(
                        paymentLink = app.openbill.ui.pdf.InvoiceLayoutEngine.upiUri(container.invoiceStyles.style(session.firm.id).value.options.upiId.trim(), built.seller.name, due.paise, built.number),
                    )
                    else -> built
                }
                val balance = due?.takeIf { it.paise != doc.grandTotalPaise }?.let { if (it.paise <= 0) "nil, paid in full. Thank you" else Fmt.inr(it) }
                val pdf = if (email) doc.fileStem to InvoicePdf.layout(container, session.firm.id, doc) else null
                container.platform.composeMessage(email, if (email) party?.email else party?.phone, "${doc.title} ${doc.number}", doc.messageText(balance), pdf)
            }.onSuccess { msg -> msg?.let { messages?.show(it) } }
                .onFailure { messages?.show("Could not prepare the message: ${it.message}") }
            busy = false
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(voucher?.number ?: "Draft " + docLabels(voucher?.type ?: app.openbill.core.domain.model.VoucherType.SALE).noun, onBack = onBack, scrollBehavior = scrollBehavior) {
                voucher?.let { v ->
                    IconButton(onClick = { share(v) }, enabled = !busy) { Icon(Icons.Filled.Share, contentDescription = "Share PDF") }
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val v = voucher
        if (v == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val summary = remember(v) {
            runCatching {
                InvoiceMath.summarizeStored(v.lines, session.firm.stateCode, v.placeOfSupplyStateCode, v.roundOff, v.total, session.settings.roundingMode, v.charges, v.reverseCharge, v.export != null, v.exchangeRate)
            }.getOrNull()
        }
        val foreign = remember(v) { InvoiceMath.foreignSummary(v, session.firm.stateCode, session.settings.roundingMode) }
        CenteredContent(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ElevatedCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                party?.name ?: "No party",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f).padding(end = Spacing.sm),
                            )
                            StatusChip(v.status, issued = v.type.isIssueOnly(), postedLabel = Orders.status(v).label.takeIf { v.type.isOrder() })
                        }
                        Text(
                            party?.gstin?.let { "GSTIN $it" } ?: "Unregistered (no GSTIN)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = Spacing.xs))
                        MetaRow("Date", Fmt.dateLong(v.date))
                        MetaRow("Place of supply", Fmt.stateName(v.placeOfSupplyStateCode))
                        MetaRow("Tax", if (summary?.isIntraState != false) "CGST + SGST (intra-state)" else "IGST (inter-state)")
                        if (v.reverseCharge) MetaRow("Reverse charge", "Yes: tax payable by the recipient")
                        v.detailRows { Fmt.date(it) }.forEach { (k, value) -> MetaRow(k, value) }
                    }
                }
                if (v.status == VoucherStatus.CANCELLED) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Cancelled: ${v.cancellationReason.orEmpty().ifBlank { "no reason recorded" }}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                v.shortClosedAt?.let { at ->
                    val closed = Orders.shortClosed(v, conversions)
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("Closed on ${Fmt.dateLong(at.toLocalDateTime(container.timeZone).date)}: the rest will not be delivered", style = MaterialTheme.typography.bodyMedium)
                            v.lines.filter { it.id in closed }.forEach { l ->
                                Text("${l.description}: ${Fmt.qty(closed.getValue(l.id))} short-closed", style = MaterialTheme.typography.bodySmall)
                            }
                            v.shortCloseNote?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                SectionHeader("Items (${v.lines.size})")
                // Issued orders show how much of each line is already invoiced or billed.
                val showBilled = v.type.isOrder() && v.status != VoucherStatus.DRAFT
                val billed = if (showBilled) Orders.converted(conversions) else emptyMap()
                v.lines.forEach { line ->
                    LineCard(line, line.itemId?.let { units[it] }, if (showBilled) billed[line.id] ?: Quantity.of(0) else null, v.currency, v.exchangeRate, shortClosed = v.shortClosedAt != null)
                }

                SectionHeader("Totals")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.lg)) {
                        if (summary != null) {
                            AmountRow(summary.grossLabel, summary.gross)
                            if (summary.discount.paise != 0L) AmountRow("Discount", summary.discount, negative = true)
                            AmountRow(if (v.charges.isEmpty()) "Taxable value" else "Items taxable value", summary.taxable)
                            v.charges.sortedBy { it.sortOrder }.forEach { AmountRow(it.name, it.taxableValue) }
                            if (summary.isIntraState) {
                                AmountRow("CGST", summary.cgst)
                                AmountRow("SGST", summary.sgst)
                            } else {
                                AmountRow("IGST", summary.igst)
                            }
                            if (summary.cess.paise != 0L) AmountRow("Cess", summary.cess)
                            if (v.reverseCharge && summary.totalTax.paise != 0L) AmountRow("Less: GST on reverse charge", summary.totalTax, negative = true)
                        } else {
                            AmountRow("Taxable value", v.taxable)
                            AmountRow("GST", v.tax)
                        }
                        if (v.roundOff.paise != 0L) AmountRow("Round off", Fmt.inrSigned(v.roundOff))
                        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                        AmountRow(if (v.currency == null) "Total" else "Total (INR)", v.total, emphasize = true)
                        Text(
                            AmountInWords.rupees(v.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                        if (foreign != null && v.currency != null) {
                            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                            AmountRow("Total (${v.currency})", Fmt.money(foreign.grandTotal, v.currency))
                            Text(
                                "Rupee amounts converted at ₹${Fmt.qty(v.exchangeRate!!)} per ${v.currency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                    }
                }
                v.notes?.let {
                    SectionHeader("Notes")
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (container.platform.supportsAttachments) AttachmentsSection(container, v.id)
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("View PDF", { onViewPdf(v.id) }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = { share(v) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (busy) "Preparing PDF…" else "Share PDF")
                }
                if (v.status != VoucherStatus.CANCELLED && v.type in setOf(VoucherType.SALE, VoucherType.PROFORMA, VoucherType.QUOTATION, VoucherType.CREDIT_NOTE, VoucherType.SALES_ORDER)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (container.platform.supportsSms) OutlinedButton(onClick = { send(v, email = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (party?.phone.isNullOrBlank()) "Send by SMS" else "SMS ${party?.phone}", maxLines = 1)
                        }
                        OutlinedButton(onClick = { send(v, email = true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("Email with PDF", maxLines = 1)
                        }
                    }
                }
                val canCreate = app.openbill.core.domain.model.Access.can(app.openbill.core.domain.model.Permission.CREATE_VOUCHERS)
                val canCancel = app.openbill.core.domain.model.Access.can(app.openbill.core.domain.model.Permission.CANCEL_DELETE)
                if (if (v.status == VoucherStatus.DRAFT) canCreate else canCancel) when (v.status) {
                    VoucherStatus.DRAFT -> OutlinedButton(onClick = { onEditDraft(v.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Edit draft")
                    }
                    VoucherStatus.POSTED -> OutlinedButton(
                        onClick = { cancelOpen = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel ${docLabels(v.type).noun}") }
                    VoucherStatus.CANCELLED -> OutlinedButton(onClick = { restoreOf = v.cancellationReason.orEmpty() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore ${docLabels(v.type).noun}")
                    }
                }
                if (canCreate) OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val today = container.today()
                            val fy = session.financialYearFor(today) ?: error("No financial year covers $today")
                            val copy = v.duplicateAsDraft(container.newId(), today, fy.id, { container.newId() }, container.now())
                            container.repositories.vouchers.saveDraft(copy)
                            copy.id
                        }.onSuccess(onEditDraft).onFailure { messages?.show(it.message ?: "Could not duplicate") }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Duplicate as new draft") }
                if (v.status == VoucherStatus.DRAFT && canCreate) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                runCatching { container.repositories.vouchers.softDeleteDraft(v.id, container.now()) }
                                    .onSuccess { messages?.show("Draft moved to the recycle bin (More)"); onBack() }
                                    .onFailure { messages?.show(it.message ?: "Could not delete") }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete draft") }
                }
                if (!canCreate) {
                    // Read-only roles (accountant/CA): view and share only.
                } else if (v.status == VoucherStatus.POSTED && v.type.isOrder()) {
                    val target = v.type.orderTarget()!!
                    if (v.fulfilment != OrderFulfilment.COMPLETE && v.shortClosedAt == null) {
                        PrimaryButton(if (target == VoucherType.SALE) "Convert to invoice" else "Convert to purchase bill", { onConvert(v.id, target) }, Modifier.fillMaxWidth())
                    }
                    if (canCancel && Orders.canShortClose(v)) {
                        OutlinedButton(onClick = { shortCloseOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Close remaining") }
                    }
                } else if (v.status == VoucherStatus.POSTED && v.type.isIssueOnly()) {
                    val inv = converted
                    if (inv == null) {
                        PrimaryButton("Convert to invoice", { onConvert(v.id, VoucherType.SALE) }, Modifier.fillMaxWidth())
                        if (v.type == VoucherType.QUOTATION) {
                            OutlinedButton(onClick = { onConvert(v.id, VoucherType.SALES_ORDER) }, modifier = Modifier.fillMaxWidth()) { Text("Convert to sales order") }
                        }
                    } else {
                        Text("Converted \u2192 ${docLabels(inv.type).noun} ${inv.number ?: "(draft)"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (v.type.isOrder() && conversions.isNotEmpty()) {
                    SectionHeader(if (v.type == VoucherType.SALES_ORDER) "Invoices from this order" else "Bills from this order")
                    conversions.forEach { c ->
                        val qty = c.lines.filter { it.sourceLineId != null }.fold(0L) { a, l -> a + l.quantity.scaledUnits }
                        ListItem(
                            headlineContent = { Text("${c.number ?: "Draft"} \u00b7 ${Fmt.date(c.date)}") },
                            supportingContent = { Text("Qty ${Fmt.qty(Quantity.ofScaledUnits(qty))} \u00b7 ${Fmt.inr(c.total)}") },
                            trailingContent = { StatusChip(c.status) },
                            modifier = Modifier.clickable(onClickLabel = "Open ${c.number ?: "draft"}") { onOpen(c.id) },
                        )
                    }
                }
                if (v.type == VoucherType.SALE && v.status != VoucherStatus.CANCELLED && app.openbill.core.domain.model.Access.can(app.openbill.core.domain.model.Permission.MANAGE_MASTERS)) {
                    OutlinedButton(onClick = { recurringOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Repeat on a schedule") }
                }
                val due = outstanding
                if (v.status == VoucherStatus.POSTED && due != null && due > app.openbill.core.model.Money.ZERO &&
                    (v.type == app.openbill.core.domain.model.VoucherType.PURCHASE || v.type == app.openbill.core.domain.model.VoucherType.SALE)
                ) {
                    AmountRow("Balance due", due, emphasize = true)
                    if (canCreate) OutlinedButton(onClick = { onRecordPayment(v) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (v.type == app.openbill.core.domain.model.VoucherType.SALE) "Record payment received" else "Record payment made")
                    }
                }
                if (canCreate && v.status == VoucherStatus.POSTED && (v.type == app.openbill.core.domain.model.VoucherType.PURCHASE || v.type == app.openbill.core.domain.model.VoucherType.SALE)) {
                    OutlinedButton(onClick = { onReturn(v.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (v.type == app.openbill.core.domain.model.VoucherType.PURCHASE) "Return to supplier (debit note)" else "Sales return (credit note)")
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    if (shortCloseOpen) voucher?.let { order ->
        var note by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        val pending = Orders.pending(order, conversions).filterValues { it.scaledUnits > 0L }
        val drafts = conversions.count { it.status == VoucherStatus.DRAFT && it.deletedAt == null }
        AlertDialog(
            onDismissRequest = { if (!closing) shortCloseOpen = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Close what is still pending?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("The order is marked Closed and these quantities will not be ${if (order.type == VoucherType.SALES_ORDER) "invoiced" else "billed"}. This cannot be undone.")
                    order.lines.filter { it.id in pending }.forEach { l ->
                        Text("${l.description}: ${Fmt.qty(pending.getValue(l.id))}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (drafts > 0) Text("$drafts draft ${if (drafts == 1) "conversion" else "conversions"} from this order can no longer be posted.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    FormField(
                        "Note (optional)", note, { note = it.take(Orders.SHORT_CLOSE_NOTE_MAX); error = null }, error = error,
                        supporting = "Printed on the order, e.g. customer cancelled the rest", singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !closing, onClick = {
                    closing = true
                    scope.launch {
                        runCatching { container.repositories.vouchers.shortCloseOrder(order.id, note, container.now()) }
                            .onSuccess { shortCloseOpen = false; messages?.show("Order closed") }
                            .onFailure { error = it.message ?: "Could not close the order" }
                        closing = false
                    }
                }) { Text("Close remaining") }
            },
            dismissButton = { TextButton(enabled = !closing, onClick = { shortCloseOpen = false }) { Text("Keep open") } },
        )
    }

    if (cancelOpen) {
        var reason by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var cancelling by remember { mutableStateOf(false) }
        val filing = voucher?.let { FilingPeriod.warning(it.type, it.date, container.today()) }
        val noun = docLabels(voucher?.type ?: VoucherType.SALE).noun
        AlertDialog(
            onDismissRequest = { if (!cancelling) cancelOpen = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Cancel this ${noun}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "The $noun keeps its number and stays in your records, marked as cancelled. " +
                            if (voucher?.type?.isIssueOnly() == true) "It has no stock or ledger entries to reverse." else "Its ledger entries are reversed.",
                    )
                    filing?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    FormField("Reason *", reason, { reason = it; error = null }, error = error)
                }
            },
            confirmButton = {
                TextButton(enabled = !cancelling, onClick = {
                    if (reason.isBlank()) {
                        error = "A reason is required"
                        return@TextButton
                    }
                    cancelling = true
                    scope.launch {
                        runCatching {
                            container.cancelVoucher(voucherId, reason.trim(), container.now())
                            if (filing != null) {
                                container.repositories.audit.record(
                                    app.openbill.core.domain.model.AuditEvent(
                                        id = container.newId(), firmId = session.firm.id, entityType = "voucher", entityId = voucherId, voucherId = voucherId,
                                        action = "filing_period_warning_acknowledged", detailsJson = "{\"action\":\"cancel\"}", createdAt = container.now(),
                                    ),
                                )
                            }
                        }
                            .onSuccess {
                                cancelOpen = false
                                messages?.show("${noun.replaceFirstChar { it.uppercase() }} cancelled")
                            }
                            .onFailure { error = it.message ?: "Could not cancel" }
                        cancelling = false
                    }
                }) { Text("Cancel $noun", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancelOpen = false }, enabled = !cancelling) { Text("Keep") } },
        )
    }

    voucher?.takeIf { recurringOpen }?.let { v ->
        RecurringScheduleDialog(
            container = container,
            session = session,
            template = v,
            partyName = party?.name,
            onDismiss = { recurringOpen = false },
            onSaved = {
                recurringOpen = false
                messages?.show("Schedule saved. Manage it under Money > Recurring invoices")
                onRecurring()
            },
        )
    }

    restoreOf?.let { cancellationReason ->
        var reason by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { restoreOf = null },
            title = { Text("Restore this document?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("It is posted again with its original number; stock and ledger entries are reapplied.")
                    FormField("Reason *", reason, { reason = it; error = null }, error = error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (reason.isBlank()) { error = "A reason is required"; return@TextButton }
                    scope.launch {
                        runCatching { container.restoreVoucher(voucherId, cancellationReason, reason.trim(), container.now()) }
                            .onSuccess { restoreOf = null; messages?.show("Restored") }
                            .onFailure { error = it.message ?: "Could not restore" }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreOf = null }) { Text("Keep cancelled") } },
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

/** One invoice line: name and amount on top, then a label/value grid that never breaks mid-figure. */
@Composable
private fun LineCard(line: VoucherLine, unit: String?, billed: Quantity? = null, currency: String? = null, exchangeRate: Quantity? = null, shortClosed: Boolean = false) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    line.description,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(Fmt.inr(line.total), style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(Spacing.xs))
            val foreignRate = line.foreignRate
            if (currency != null && foreignRate != null) {
                AmountRow("${Fmt.qtyUnit(line.quantity, unit)} × ${Fmt.money(foreignRate, currency)}", Fmt.money(InvoiceMath.gross(line.quantity, foreignRate), currency))
                line.foreignDiscount?.takeIf { it.paise != 0L }?.let { AmountRow("Discount ($currency)", "−" + Fmt.money(it, currency)) }
            }
            val gross = InvoiceMath.storedGross(line, exchangeRate)
            // Foreign lines: the rupee value is the converted line value, not quantity times the rounded rupee rate.
            AmountRow(if (currency != null && foreignRate != null) "Value in rupees" else "${Fmt.qtyUnit(line.quantity, unit)} × ${Fmt.inr(line.rate)}", Fmt.inr(gross))
            if (line.freeQuantity.scaledUnits > 0) AmountRow("Free: ${Fmt.qtyUnit(line.freeQuantity, unit)}", Fmt.inr(Money.ZERO))
            if (!line.itcEligible) AmountRow("Input tax credit", "Not claimed")
            if (line.discount.paise != 0L) AmountRow("Discount", Fmt.inrDeduction(line.discount))
            AmountRow("Taxable value", Fmt.inr(line.taxable))
            AmountRow("GST ${Fmt.percent(line.taxRate)}%" + if (line.taxMode.name == "INCLUSIVE") " (incl.)" else "", Fmt.inr(line.tax))
            line.hsnSac?.let { AmountRow("HSN/SAC", it) }
            billed?.let {
                val pending = Quantity.ofScaledUnits(maxOf(0L, line.quantity.scaledUnits - it.scaledUnits))
                AmountRow("Billed ${Fmt.qtyUnit(it, unit)}", if (pending.scaledUnits == 0L) "Done" else "${if (shortClosed) "Short-closed" else "Pending"} ${Fmt.qtyUnit(pending, unit)}")
            }
        }
    }
}

/** Images and PDFs attached to a voucher or party; stored beside the database and carried in backups. */
@Composable
internal fun AttachmentsSection(container: AppContainer, voucherId: String) {
    val platform = container.platform
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var version by remember { mutableStateOf(0) }
    val names by produceState(emptyList<String>(), voucherId, version) { value = runCatching { platform.attachments(voucherId) }.getOrDefault(emptyList()) }
    fun run(block: suspend () -> String?) {
        scope.launch {
            runCatching { block() }.onSuccess { it?.let { m -> messages?.show(m) } }.onFailure { messages?.show(it.message ?: "Could not do that") }
            version++
        }
    }
    SectionHeader("Attachments (${names.size})")
    names.forEach { name ->
        ListItem(
            headlineContent = { Text(name) },
            trailingContent = { TextButton(onClick = { run { platform.deleteAttachment(voucherId, name); "Removed $name" } }) { Text("Remove") } },
            modifier = Modifier.clickable(role = Role.Button) { run { platform.openAttachment(voucherId, name) } },
        )
    }
    OutlinedButton(onClick = { run { platform.addAttachment(voucherId) } }, modifier = Modifier.fillMaxWidth()) { Text("Attach image or PDF") }
}
