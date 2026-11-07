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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import app.openbill.core.domain.report.RegisterPager
import app.openbill.core.domain.report.registerPager
import app.openbill.core.domain.report.registerTotals
import app.openbill.core.domain.report.writeRegisterCsv
import app.openbill.core.domain.report.writeTallyXml
import app.openbill.core.domain.report.loadReportData
import app.openbill.core.domain.report.ReportPack
import app.openbill.core.domain.report.caPack
import app.openbill.core.domain.report.reportFiles
import app.openbill.core.domain.report.zipBytes
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Permission
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
    val canExport = Access.can(Permission.EXPORT)
    var bundle by remember { mutableStateOf<BundleChoice?>(null) }
    var selected by remember { mutableStateOf(setOf(ReportKind.SALES_REGISTER, ReportKind.PURCHASE_REGISTER, ReportKind.DAY_SUMMARY)) }

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
                    if (canExport) {
                        ListItem(
                            headlineContent = { Text("CA pack") },
                            supportingContent = { Text("Registers, day book, GSTR-1, GSTR-2, GSTR-3B, HSN, outstanding, party ledgers and Tally XML in one ZIP") },
                            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { bundle = BundleChoice.CA_PACK },
                        )
                        ListItem(
                            headlineContent = { Text("Several reports") },
                            supportingContent = { Text("Choose reports to download as a ZIP, share or email together") },
                            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { bundle = BundleChoice.SELECTED },
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
    bundle?.let { choice ->
        val ca = choice == BundleChoice.CA_PACK
        fun deliver(action: BundleAction) {
            bundle = null
            export { onProgress ->
                val files = withContext(Dispatchers.Default) {
                    if (ca) repos.caPack(session.firm.id, period, onProgress)
                    else repos.reportFiles(session.firm.id, period, ReportPack.BUNDLE_KINDS.filter { it in selected }, onProgress)
                }
                val name = (if (ca) "CA-pack" else "Reports") + "-${period.from}-${period.to}"
                val zip = withContext(Dispatchers.Default) { files.zipBytes() }
                when {
                    action == BundleAction.ZIP -> container.platform.saveFile("$name.zip", zip)
                    // The CA pack travels as its ZIP; a few chosen reports as separate CSV files.
                    ca -> container.platform.shareFiles(name, listOf("$name.zip" to zip), email = action == BundleAction.EMAIL)
                    else -> container.platform.shareFiles(name, files.map { it.name to it.bytes }, email = action == BundleAction.EMAIL)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { bundle = null },
            title = { Text(if (ca) "CA pack" else "Choose reports") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(periodLabel(period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ca) {
                        Text("Every file matches the single report of the same name. GSTR-1 JSON is made per calendar month.")
                    } else {
                        ReportPack.BUNDLE_KINDS.forEach { kind ->
                            val on = kind in selected
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(on, role = Role.Checkbox) { selected = if (it) selected + kind else selected - kind },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Text(kind.title, Modifier.padding(start = Spacing.sm))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    BundleAction.entries.forEach { a -> TextButton(enabled = ca || selected.isNotEmpty(), onClick = { deliver(a) }) { Text(a.label) } }
                }
            },
            dismissButton = { TextButton(onClick = { bundle = null }) { Text("Cancel") } },
        )
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

private enum class BundleChoice { CA_PACK, SELECTED }

private enum class BundleAction(val label: String) { ZIP("Download ZIP"), SHARE("Share"), EMAIL("Email") }

private val REPORT_HINTS = mapOf(
    ReportKind.DAY_SUMMARY to "Sales, cash in and cash out per day",
    ReportKind.BATCH_EXPIRY to "Stock by batch with expiry dates",
    ReportKind.GSTR2 to "Purchases by supplier and rate with input credit and reverse charge",
    ReportKind.SALES_REGISTER to "Invoices and credit notes with tax heads",
    ReportKind.PURCHASE_REGISTER to "Supplier bills and debit notes",
    ReportKind.DAY_BOOK to "Every voucher by date",
    ReportKind.SALES_BY_USER to "Sales and credit notes per user who posted them",
    ReportKind.DAY_BOOK_BY_USER to "Every voucher, grouped by who posted it",
    ReportKind.CASH_BANK_BOOK to "Receipts, payments and running balance per account",
    ReportKind.EXPENSES to "Expenses by category",
    ReportKind.OUTSTANDING to "Receivables and payables with ageing",
    ReportKind.PARTY_STATEMENT to "One party's ledger with running balance",
    ReportKind.STOCK_SUMMARY to "Opening, in, out, closing and value",
    ReportKind.LOW_STOCK to "Items at or below their low-stock level, with what to reorder",
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
    // Registers page their rows (one week of vouchers at a time) instead of holding the whole period.
    val paged = kind == ReportKind.SALES_REGISTER || kind == ReportKind.PURCHASE_REGISTER
    var pager by remember(period, partyId) { mutableStateOf<RegisterPager?>(null) }
    var pageRows by remember(period, partyId) { mutableStateOf<List<List<String>>>(emptyList()) }
    var pageTotals by remember(period, partyId) { mutableStateOf<List<String>?>(null) }
    var loadingMore by remember(period, partyId) { mutableStateOf(false) }
    val result by produceState<Result<ReportTable>?>(null, period, partyId) {
        value = null
        progress = null
        // Off the main thread; leaving the screen (Cancel) cancels the coroutine.
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (paged) {
                    val p = container.repositories.registerPager(session.firm.id, period, kind == ReportKind.SALES_REGISTER)
                    pageRows = p.next()
                    pager = p
                    ReportTable(p.title, p.columns, emptyList())
                } else {
                    Reports.run(kind, container.repositories.loadReportData(session.firm.id, period, kind) { progress = it }, partyId)
                }
            }
        }
        // After the first page is on screen: the totals row, from one streamed pass over the period.
        if (paged) pageTotals = withContext(Dispatchers.Default) { runCatching { container.repositories.registerTotals(session.firm.id, period, kind == ReportKind.SALES_REGISTER) }.getOrNull() }
    }
    val table = result?.getOrNull()?.let { if (paged) it.copy(rows = pageRows, totals = pageTotals.takeIf { _ -> pager?.exhausted == true }) else it }
    val stem = "${kind.key}-${period.from}-${period.to}"

    fun loadMore() {
        val p = pager ?: return
        if (loadingMore || p.exhausted) return
        loadingMore = true
        scope.launch {
            pageRows = pageRows + withContext(Dispatchers.Default) { p.next() }
            loadingMore = false
        }
    }
    // Exports of a register read the whole period again rather than the loaded page.
    suspend fun fullTable(t: ReportTable) =
        if (paged) withContext(Dispatchers.Default) { Reports.run(kind, container.repositories.loadReportData(session.firm.id, period, kind), partyId) } else t

    fun act(block: suspend (ReportTable) -> String?) {
        val t = table ?: return
        scope.launch { (runCatching { block(fullTable(t)) }.getOrElse { it.message ?: "Export failed" })?.let { messages?.show(it) } }
    }
    // A register's CSV is streamed from the database rather than built from the whole period in memory.
    fun csvAct(block: suspend (title: String, csv: String) -> String?) {
        val t = table ?: return
        scope.launch {
            (runCatching {
                val text = if (paged) withContext(Dispatchers.Default) { buildString { container.repositories.writeRegisterCsv(session.firm.id, period, kind == ReportKind.SALES_REGISTER, this) } }
                else t.toCsv()
                block(t.title, text)
            }.getOrElse { it.message ?: "Export failed" })?.let { messages?.show(it) }
        }
    }
    fun pdf(action: PdfAction) = act { t ->
        container.platform.pdfAction(stem, ReportPdf.layout(t, session.firm.name, periodLabel(period), container.platform.pdfFontMetrics), action)
    }

    Scaffold(
        topBar = {
            AppTopBar(table?.title ?: kind.title, onBack = onBack) {
                IconButton(onClick = { menu = true }, enabled = table != null) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export CSV") }, onClick = { menu = false; csvAct { _, csv -> container.platform.saveTextFile("$stem.csv", csv) } })
                    DropdownMenuItem(text = { Text("Share CSV") }, onClick = { menu = false; csvAct { title, csv -> container.platform.shareFiles(title, listOf("$stem.csv" to csv.encodeToByteArray())) } })
                    DropdownMenuItem(text = { Text("Email CSV") }, onClick = { menu = false; csvAct { title, csv -> container.platform.shareFiles(title, listOf("$stem.csv" to csv.encodeToByteArray()), email = true) } })
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
                else -> ReportTableView(table ?: r.getOrThrow(), onEnd = if (paged) ::loadMore else null)
            }
        }
    }
}

/** Header, rows and totals in one horizontally scrollable grid; numbers right-aligned. */
@Composable
fun ReportTableView(table: ReportTable, modifier: Modifier = Modifier, onEnd: (() -> Unit)? = null) {
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
    val listState = rememberLazyListState()
    if (onEnd != null) {
        // Ask for the next page when the last rows come into view (and again if the page was short).
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
        LaunchedEffect(nearEnd, cells.size) { if (nearEnd >= cells.size - 20) onEnd() }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
      // Notes wrap within the screen, not across the (possibly wider) scrolled table.
      val noteWidth = minOf(maxWidth, maxOf(total, 360.dp))
      Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(maxOf(total, 360.dp)), listState, contentPadding = PaddingValues(bottom = Spacing.xl)) {
            item { line(table.columns.map { it.label }, bold = true, shade = true) }
            if (cells.isEmpty()) item { Text("Nothing in this period", Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodyMedium) }
            items(cells.size) { i ->
                line(cells[i], bold = false, shade = false)
                HorizontalDivider()
            }
            totals?.let { t -> item { line(t, bold = true, shade = true) } }
            items(table.notes) { n ->
                Text(n, Modifier.width(noteWidth).padding(horizontal = Spacing.sm, vertical = Spacing.xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
      }
    }
}
