package app.openbill.ui.invoice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.pdf.InvoicePdf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.components.LocalWidthClass
import app.openbill.ui.Session
import app.openbill.ui.components.AnimatedMoney
import app.openbill.ui.components.AppSearchBar
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CollapsingTopBar
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.StatusChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween
import kotlinx.datetime.LocalDate

enum class StatusFilter(val label: String, val status: VoucherStatus?) {
    ALL("All", null),
    DRAFT("Drafts", VoucherStatus.DRAFT),
    POSTED("Posted", VoucherStatus.POSTED),
    CANCELLED("Cancelled", VoucherStatus.CANCELLED),
}

object InvoiceSearch {
    fun filter(
        vouchers: List<Voucher>,
        partiesById: Map<String, Party>,
        query: String,
        status: VoucherStatus?,
        types: Set<VoucherType> = setOf(VoucherType.SALE),
    ): List<Voucher> {
        val q = query.trim()
        return vouchers.filter { v ->
            v.type in types &&
                (status == null || v.status == status) &&
                (
                    q.isEmpty() ||
                        (v.number?.contains(q, ignoreCase = true) ?: false) ||
                        (v.referenceNumber?.contains(q, ignoreCase = true) ?: false) ||
                        (v.partyId?.let { partiesById[it]?.name }?.contains(q, ignoreCase = true) ?: false)
                    )
        }
    }
}

private val ALL_FROM = LocalDate(2000, 1, 1)
private val ALL_TO = LocalDate(2099, 12, 31)

/** Month-to-date figures for the summary card. */
data class SalesSummary(val monthTotal: Money, val postedThisMonth: Int, val drafts: Int) {
    companion object {
        fun of(sales: List<Voucher>, today: LocalDate): SalesSummary {
            val posted = sales.filter { it.type == VoucherType.SALE && it.status == VoucherStatus.POSTED }
            val thisMonth = posted.filter { it.date.year == today.year && it.date.month == today.month }
            return SalesSummary(
                monthTotal = thisMonth.fold(Money.ZERO) { a, v -> a + v.total },
                postedThisMonth = thisMonth.size,
                drafts = sales.count { it.type == VoucherType.SALE && it.status == VoucherStatus.DRAFT },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceListScreen(
    container: AppContainer,
    session: Session,
    onNew: () -> Unit,
    onOpen: (Voucher) -> Unit,
    onAddParty: () -> Unit,
    /** Purchase bills and purchase returns instead of sales invoices. */
    purchases: Boolean = false,
    onBack: (() -> Unit)? = null,
    onStock: ((lowOnly: Boolean) -> Unit)? = null,
    /** Quotations, proformas or challans instead of invoices. */
    docType: VoucherType? = null,
    onMoney: ((app.openbill.ui.money.MoneyCard) -> Unit)? = null,
) {
    val types = when {
        docType != null -> setOf(docType)
        purchases -> setOf(VoucherType.PURCHASE, VoucherType.DEBIT_NOTE)
        else -> setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE)
    }
    val title = docType?.let { app.openbill.ui.money.DOC_LIST_TITLES[it] } ?: if (purchases) "Purchases" else "Invoices"
    val secondary = purchases || docType != null
    val firmId = session.firm.id
    val vouchers by remember(firmId) { container.repositories.vouchers.observeVouchers(firmId, ALL_FROM, ALL_TO) }
        .collectAsState(initial = null)
    val parties by remember(firmId) { container.repositories.parties.observeParties(firmId) }.collectAsState(initial = emptyList())
    // Removed (archived) parties drop out of the live list but their invoices still need a name.
    val archivedParties = remember(firmId) { mutableStateMapOf<String, Party>() }
    val partiesById = archivedParties + parties.associateBy { it.id }
    val missingPartyIds = vouchers.orEmpty().mapNotNull { it.partyId }.filterNot { it in partiesById }.toSet()
    LaunchedEffect(missingPartyIds) {
        missingPartyIds.forEach { id -> container.repositories.parties.findParty(id)?.let { archivedParties[id] = it } }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(StatusFilter.ALL.name) }
    val filter = StatusFilter.valueOf(filterName)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (onBack != null) AppTopBar(title, onBack = onBack)
            else CollapsingTopBar(title, scrollBehavior)
        },
        snackbarHost = { AppSnackbarHost() },
        floatingActionButton = {
            // With a navigation rail, its header FAB is the screen's one create action.
            if (!LocalWidthClass.current.usesRail || secondary) ExtendedFloatingActionButton(
                onClick = onNew,
                expanded = !listState.lastScrolledForward || !listState.canScrollBackward,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(if (docType != null) "New ${docLabels(docType).noun}" else if (purchases) "New purchase" else "New invoice") },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            val all = vouchers
            if (all == null) {
                LoadingState()
                return@CenteredContent
            }
            val sales = all.filter { it.type in types }
            if (sales.isEmpty() && secondary) {
                EmptyState(
                    Icons.Outlined.Create,
                    "No ${title.lowercase()} yet",
                    if (docType != null) "Issue one to the party; convert it to an invoice when they confirm."
                    else "Record a supplier's bill to add its items to stock and claim input GST.",
                    Modifier.align(Alignment.Center),
                )
                return@CenteredContent
            }
            if (sales.isEmpty()) {
                EmptyState(
                    Icons.Outlined.Create,
                    "No invoices yet",
                    if (parties.isEmpty()) {
                        "Add the customer you bill first, then create your first GST invoice."
                    } else {
                        "Create your first GST invoice. Pick items from your list or type a custom line."
                    },
                    Modifier.align(Alignment.Center),
                ) {
                    if (parties.isEmpty()) OutlinedButton(onClick = onAddParty) { Text("Add a party") }
                }
                return@CenteredContent
            }
            val shown = InvoiceSearch.filter(sales, partiesById, query, filter.status, types)
            val summary = remember(sales) { SalesSummary.of(sales, container.today()) }
            // Header rows scroll with the list so the list stays usable in landscape and at large font sizes.
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Spacing.fabClearance)) {
                if (!secondary) item(key = "summary") { SummaryCard(session.firm.name, summary) }
                if (onMoney != null) item(key = "money") { app.openbill.ui.money.MoneyDashboardCard(container, session, onMoney) }
                if (onStock != null) item(key = "stock") { app.openbill.ui.inventory.StockDashboardCard(container, session, onStock) }
                item(key = "search") { AppSearchBar(query, { query = it }, "Search number or party") }
                item(key = "filters") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        StatusFilter.entries.forEach { f ->
                            val count = if (f.status == null) sales.size else sales.count { it.status == f.status }
                            SelectableChip("${f.label} ($count)", filter == f, { filterName = f.name })
                        }
                        if (container.platform.supportsBulkPdf && shown.isNotEmpty()) {
                            BulkPdfButtons(container, session, shown)
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(Icons.Outlined.Search, "No match", "No invoice matches your search or filter.")
                    }
                }
                items(shown, key = { it.id }) { v ->
                    val partyName = v.partyId?.let { partiesById[it]?.name } ?: "No party"
                    ListItem(
                        leadingContent = { Monogram(partyName) },
                        headlineContent = { Text(partyName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    docLabels(v.type).title.takeIf { v.type != VoucherType.SALE && v.type != VoucherType.PURCHASE },
                                    v.number ?: "Not numbered",
                                    v.referenceNumber?.takeIf { v.type == VoucherType.PURCHASE }?.let { "Bill $it" },
                                    Fmt.dateLong(v.date),
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(Fmt.inr(v.total), style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                                StatusChip(v.status, issued = v.type.isIssueOnly())
                            }
                        },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = if (reduced) null else motionTween(false, Motion.SHORT4),
                                placementSpec = if (reduced) null else motionTween(false, Motion.MEDIUM1, Motion.Emphasized),
                                fadeOutSpec = if (reduced) null else motionTween(false, Motion.SHORT3),
                            )
                            .clickable(onClickLabel = "Open invoice") { onOpen(v) },
                    )
                }
            }
        }
    }
}

/** Circle with the first letter of a name; decorative, so hidden from screen readers. */
@Composable
fun Monogram(name: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SummaryCard(firmName: String, summary: SalesSummary) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = pageGutter, vertical = Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text(firmName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Sales this month", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
            AnimatedMoney(summary.monthTotal, MaterialTheme.typography.headlineMedium)
            Text(
                buildString {
                    append(if (summary.postedThisMonth == 1) "1 invoice posted" else "${summary.postedThisMonth} invoices posted")
                    append(" · ")
                    append(if (summary.drafts == 1) "1 draft open" else "${summary.drafts} drafts open")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/**
 * Desktop bulk output of the listed invoices (current search and filter): PDFs into a folder,
 * or every invoice to one printer, with progress and cancellation.
 */
@Composable
private fun BulkPdfButtons(container: AppContainer, session: Session, shown: List<Voucher>) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    fun run(print: Boolean) {
        val list = shown.toList()
        progress = 0 to list.size
        job = scope.launch {
            val pdfs = list.map { v ->
                val stem = pdfTitle(v.type, session.firm.gstin).replace(" ", "") + "-" + (v.number ?: v.id).replace(Regex("[^A-Za-z0-9_-]"), "_")
                stem to suspend { InvoicePdf.layout(container, session.firm.id, buildInvoiceDocument(container, session, v)) }
            }
            val msg = runCatching { container.platform.bulkPdf(pdfs, print) { done -> progress = done to list.size } }
                .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) "Stopped after ${progress.first} of ${list.size}" else e.message }
            job = null
            msg?.let { messages?.show(it) }
        }
    }
    OutlinedButton(onClick = { run(print = false) }, enabled = job == null) { Text("Export ${shown.size} PDFs") }
    OutlinedButton(onClick = { run(print = true) }, enabled = job == null) { Text("Print ${shown.size}") }
    if (job != null && progress.second > 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Working on ${progress.first} of ${progress.second}") },
            text = { LinearProgressIndicator(progress = { progress.first / progress.second.toFloat() }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel() }) { Text("Cancel") } },
        )
    }
}
