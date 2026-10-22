package app.openbill.ui.invoice

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
import app.openbill.ui.format.AmountInWords
import app.openbill.ui.format.Fmt
import app.openbill.ui.pdf.InvoicePdf
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    return InvoiceDocument.build(voucher, firm, session.settings, party, unitsByItem(container, session, voucher.lines))
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
) {
    val voucher by remember(voucherId) { container.repositories.vouchers.observeVoucher(voucherId) }.collectAsState(initial = null)
    var party by remember { mutableStateOf<Party?>(null) }
    var units by remember { mutableStateOf(emptyMap<String, String>()) }
    var cancelOpen by remember { mutableStateOf(false) }
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(voucher?.number ?: "Draft invoice", onBack = onBack, scrollBehavior = scrollBehavior) {
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
                InvoiceMath.summarizeStored(v.lines, session.firm.stateCode, v.placeOfSupplyStateCode, v.roundOff, v.total, session.settings.roundingMode)
            }.getOrNull()
        }
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
                            StatusChip(v.status)
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

                SectionHeader("Items (${v.lines.size})")
                v.lines.forEach { line -> LineCard(line, line.itemId?.let { units[it] }) }

                SectionHeader("Totals")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.lg)) {
                        if (summary != null) {
                            AmountRow(summary.grossLabel, summary.gross)
                            if (summary.discount.paise != 0L) AmountRow("Discount", summary.discount, negative = true)
                            AmountRow("Taxable value", summary.taxable)
                            if (summary.isIntraState) {
                                AmountRow("CGST", summary.cgst)
                                AmountRow("SGST", summary.sgst)
                            } else {
                                AmountRow("IGST", summary.igst)
                            }
                            if (summary.cess.paise != 0L) AmountRow("Cess", summary.cess)
                        } else {
                            AmountRow("Taxable value", v.taxable)
                            AmountRow("GST", v.tax)
                        }
                        if (v.roundOff.paise != 0L) AmountRow("Round off", Fmt.inrSigned(v.roundOff))
                        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                        AmountRow("Total", v.total, emphasize = true)
                        Text(
                            AmountInWords.rupees(v.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
                v.notes?.let {
                    SectionHeader("Notes")
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("View PDF", { onViewPdf(v.id) }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = { share(v) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (busy) "Preparing PDF…" else "Share PDF")
                }
                when (v.status) {
                    VoucherStatus.DRAFT -> OutlinedButton(onClick = { onEditDraft(v.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Edit draft")
                    }
                    VoucherStatus.POSTED -> OutlinedButton(
                        onClick = { cancelOpen = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel invoice") }
                    VoucherStatus.CANCELLED -> {}
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    if (cancelOpen) {
        var reason by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var cancelling by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!cancelling) cancelOpen = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Cancel this invoice?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("The invoice keeps its number and stays in your records, marked as cancelled. Its ledger entries are reversed.")
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
                        runCatching { container.cancelVoucher(voucherId, reason.trim(), container.now()) }
                            .onSuccess {
                                cancelOpen = false
                                messages?.show("Invoice cancelled")
                            }
                            .onFailure { error = it.message ?: "Could not cancel" }
                        cancelling = false
                    }
                }) { Text("Cancel invoice", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancelOpen = false }, enabled = !cancelling) { Text("Keep") } },
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
private fun LineCard(line: VoucherLine, unit: String?) {
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
            val gross = InvoiceMath.gross(line.quantity, line.rate)
            AmountRow("${Fmt.qtyUnit(line.quantity, unit)} × ${Fmt.inr(line.rate)}", Fmt.inr(gross))
            if (line.discount.paise != 0L) AmountRow("Discount", Fmt.inrDeduction(line.discount))
            AmountRow("Taxable value", Fmt.inr(line.taxable))
            AmountRow("GST ${Fmt.percent(line.taxRate)}%" + if (line.taxMode.name == "INCLUSIVE") " (incl.)" else "", Fmt.inr(line.tax))
            line.hsnSac?.let { AmountRow("HSN/SAC", it) }
        }
    }
}
