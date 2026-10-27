package app.openbill.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.report.Gstr1
import app.openbill.core.domain.report.PeriodPreset
import app.openbill.core.domain.report.ReportGroup
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.ReportTable
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.report.writeTallyXml
import app.openbill.core.domain.report.loadReportData
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormField
import app.openbill.core.model.OnProgress
import app.openbill.core.model.Progress
import app.openbill.ui.components.ProgressOverlay
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.pdf.PdfAction
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun periodLabel(p: ReportPeriod) = "${Fmt.date(p.from)} to ${Fmt.date(p.to)}"

/** Preset chips plus a custom from/to range. */
@Composable
fun PeriodPicker(period: ReportPeriod, today: kotlinx.datetime.LocalDate, startMonth: Int, onChange: (ReportPeriod) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var from by remember(period) { mutableStateOf(Fmt.date(period.from)) }
    var to by remember(period) { mutableStateOf(Fmt.date(period.to)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PeriodPreset.entries.forEach { p ->
            val r = p.resolve(today, startMonth)
            SelectableChip(p.label, !custom && r == period, { custom = false; onChange(r) })
        }
        SelectableChip("Custom", custom, { custom = true })
    }
    if (custom) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField("From", from, { from = it }, Modifier.weight(1f), placeholder = "dd-mm-yyyy")
            FormField("To", to, { to = it }, Modifier.weight(1f), placeholder = "dd-mm-yyyy")
        }
        val f = Parse.date(from)
        val t = Parse.date(to)
        TextButton(enabled = f != null && t != null && f <= t, onClick = { onChange(ReportPeriod(f!!, t!!)) }) { Text("Apply range") }
    }
}

/** Reports hub (reached from Money and More): period, reports by group, compliance exports. */
@Composable
fun ReportsScreen(
    container: AppContainer,
    session: Session,
    onBack: () -> Unit,
    onOpen: (ReportKind, ReportPeriod, String?) -> Unit,
    onAudit: () -> Unit,
    onBooks: () -> Unit,
) {
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val startMonth = session.settings.financialYearStartMonth
    var period by remember { mutableStateOf(PeriodPreset.THIS_MONTH.resolve(container.today(), startMonth)) }
    var pickParty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val parties by remember { repos.parties.observeParties(session.firm.id) }.collectAsState(initial = emptyList())

    fun export(block: suspend (OnProgress) -> String?) {
        busy = true
        progress = null
        job = scope.launch {
            val msg = runCatching { block { progress = it } }
                .getOrElse { if (it is CancellationException) "Export cancelled" else it.message ?: "Export failed" }
            busy = false
            job = null
            msg?.let { messages?.show(it) }
        }
    }

    Scaffold(topBar = { AppTopBar("Reports", onBack = onBack) }, snackbarHost = { AppSnackbarHost() }) { padding ->
      Box {
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item(key = "period") {
                    Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PeriodPicker(period, container.today(), startMonth) { period = it }
                        Text(periodLabel(period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ReportGroup.entries.forEach { group ->
                    item(key = "h-" + group.name) { SectionHeader(group.label, Modifier.padding(horizontal = pageGutter)) }
                    items(ReportKind.entries.filter { it.group == group }, key = { it.key }) { kind ->
                        ListItem(
                            headlineContent = { Text(kind.title) },
                            supportingContent = { Text(REPORT_HINTS[kind].orEmpty()) },
                            modifier = Modifier.clickable { if (kind.needsParty) pickParty = true else onOpen(kind, period, null) },
                        )
                    }
                }
                item(key = "exports") {
                    SectionHeader("Exports for your accountant", Modifier.padding(horizontal = pageGutter))
                    Column(Modifier.padding(horizontal = pageGutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilledTonalButton(enabled = !busy, onClick = {
                                export { onProgress ->
                                    val month = ReportPeriod.monthOf(period.from)
                                    val g = withContext(Dispatchers.Default) { Gstr1.build(repos.loadReportData(session.firm.id, month, ReportKind.GSTR1, onProgress)) }
                                    container.platform.saveTextFile("GSTR1-${session.firm.gstin.orEmpty()}-${month.returnPeriod}.json", g.toJson())
                                }
                            }) { Text("GSTR-1 JSON") }
                            FilledTonalButton(enabled = !busy, onClick = {
                                export { onProgress ->
                                    val xml = withContext(Dispatchers.Default) { buildString { repos.writeTallyXml(session.firm.id, period, this, onProgress) } }
                                    container.platform.saveTextFile("Tally-${period.from}-${period.to}.xml", xml)
                                }
                            }) { Text("Tally XML") }
                        }
                        Text(
                            "GSTR-1 uses the month of the start date (${Fmt.date(period.from)}). Check the summary before uploading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "admin") {
                    SectionHeader("Books", Modifier.padding(horizontal = pageGutter))
                    ListItem(headlineContent = { Text("Lock books and close year") }, supportingContent = { Text("Stop changes to filed periods") }, modifier = Modifier.clickable(onClick = onBooks))
                    ListItem(headlineContent = { Text("Audit log") }, supportingContent = { Text("Every post, cancel, restore and lock") }, modifier = Modifier.clickable(onClick = onAudit))
                }
            }
        }
        if (busy) ProgressOverlay(progress, "Exporting", Modifier.padding(padding), onCancel = { job?.cancel() })
      }
    }
    if (pickParty) {
        AlertDialog(
            onDismissRequest = { pickParty = false },
            title = { Text("Choose a party") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(parties.sortedBy { it.name.lowercase() }, key = { it.id }) { p ->
                        ListItem(headlineContent = { Text(p.name) }, modifier = Modifier.clickable { pickParty = false; onOpen(ReportKind.PARTY_STATEMENT, period, p.id) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickParty = false }) { Text("Cancel") } },
        )
    }
}

private val REPORT_HINTS = mapOf(
    ReportKind.SALES_REGISTER to "Invoices and credit notes with tax heads",
    ReportKind.PURCHASE_REGISTER to "Supplier bills and debit notes",
    ReportKind.DAY_BOOK to "Every voucher by date",
    ReportKind.CASH_BANK_BOOK to "Receipts, payments and running balance per account",
    ReportKind.EXPENSES to "Expenses by category",
    ReportKind.OUTSTANDING to "Receivables and payables with ageing",
    ReportKind.PARTY_STATEMENT to "One party's ledger with running balance",
    ReportKind.STOCK_SUMMARY to "Opening, in, out, closing and value",
    ReportKind.LOW_STOCK to "Items at or below their low-stock level",
    ReportKind.ITEM_PROFIT to "Sales, weighted-average cost and margin per item",
    ReportKind.PROFIT_LOSS to "Trading and profit and loss from the ledger",
    ReportKind.GSTR1 to "B2B, B2CL, B2CS, credit notes, HSN and documents",
    ReportKind.GSTR3B to "Outward tax (3.1) and input credit (4)",
    ReportKind.HSN_SUMMARY to "Outward quantity and tax by HSN/SAC",
)

/** One report as a scrollable table with CSV, JSON and PDF export. */
@Composable
fun ReportScreen(
    container: AppContainer,
    session: Session,
    kind: ReportKind,
    initial: ReportPeriod,
    partyId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var period by remember { mutableStateOf(initial) }
    var menu by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    val result by produceState<Result<ReportTable>?>(null, period, partyId) {
        value = null
        progress = null
        // Off the main thread; leaving the screen (Cancel) cancels the coroutine.
        value = withContext(Dispatchers.Default) {
            runCatching { Reports.run(kind, container.repositories.loadReportData(session.firm.id, period, kind) { progress = it }, partyId) }
        }
    }
    val table = result?.getOrNull()
    val stem = "${kind.key}-${period.from}-${period.to}"

    fun act(block: suspend (ReportTable) -> String?) {
        val t = table ?: return
        scope.launch { (runCatching { block(t) }.getOrElse { it.message ?: "Export failed" })?.let { messages?.show(it) } }
    }
    fun pdf(action: PdfAction) = act { t ->
        container.platform.pdfAction(stem, ReportPdf.layout(t, session.firm.name, periodLabel(period), container.platform.pdfFontMetrics), action)
    }

    Scaffold(
        topBar = {
            AppTopBar(table?.title ?: kind.title, onBack = onBack) {
                IconButton(onClick = { menu = true }, enabled = table != null) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export CSV") }, onClick = { menu = false; act { container.platform.saveTextFile("$stem.csv", it.toCsv()) } })
                    DropdownMenuItem(text = { Text("Export JSON") }, onClick = {
                        menu = false
                        act { container.platform.saveTextFile("$stem.json", it.toJson(mapOf("from" to period.from.toString(), "to" to period.to.toString()))) }
                    })
                    PdfAction.entries.forEach { a -> DropdownMenuItem(text = { Text("PDF: ${a.label}") }, onClick = { menu = false; pdf(a) }) }
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.xs)) {
                PeriodPicker(period, container.today(), session.settings.financialYearStartMonth) { period = it }
                Text(periodLabel(period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val r = result
            when {
                r == null -> ProgressOverlay(progress, "Preparing ${kind.title.lowercase()}", onCancel = onBack)
                r.isFailure -> Text(r.exceptionOrNull()?.message ?: "Could not run the report", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(pageGutter))
                else -> ReportTableView(r.getOrThrow())
            }
        }
    }
}

/** Header, rows and totals in one horizontally scrollable grid; numbers right-aligned. */
@Composable
fun ReportTableView(table: ReportTable, modifier: Modifier = Modifier) {
    val cells = remember(table) { table.rows.map { r -> r.mapIndexed { i, c -> reportCell(table.columns[i].kind, c) } } }
    val totals = remember(table) { table.totals?.mapIndexed { i, c -> reportCell(table.columns[i].kind, c) } }
    val widths = remember(table) {
        table.columns.indices.map { i ->
            val chars = (listOf(table.columns[i].label) + cells.map { it[i] } + listOfNotNull(totals?.get(i))).maxOf { it.length }
            (chars * 8 + 20).coerceIn(64, 260).dp
        }
    }
    val total = widths.fold(0.dp) { a, b -> a + b }
    @Composable
    fun line(values: List<String>, bold: Boolean, shade: Boolean) {
        Row(if (shade) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier) {
            values.forEachIndexed { i, v ->
                Text(
                    v,
                    modifier = Modifier.width(widths[i]).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (bold) FontWeight.SemiBold else null,
                    textAlign = if (table.columns[i].numeric) TextAlign.End else TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Box(modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(maxOf(total, 360.dp)), contentPadding = PaddingValues(bottom = Spacing.xl)) {
            item { line(table.columns.map { it.label }, bold = true, shade = true) }
            if (cells.isEmpty()) item { Text("Nothing in this period", Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodyMedium) }
            items(cells.size) { i ->
                line(cells[i], bold = false, shade = false)
                HorizontalDivider()
            }
            totals?.let { t -> item { line(t, bold = true, shade = true) } }
            items(table.notes) { n ->
                Text(n, Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
