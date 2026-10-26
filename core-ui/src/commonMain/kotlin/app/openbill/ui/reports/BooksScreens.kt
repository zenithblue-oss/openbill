package app.openbill.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.report.ReportTable
import app.openbill.core.domain.usecase.BooksLock
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Books lock date and financial-year close/reopen. */
@Composable
fun BooksLockScreen(container: AppContainer, session: Session, onBack: () -> Unit) {
    val repos = container.repositories
    val firmId = session.firm.id
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val books = remember { BooksLock(repos.firms, repos.vouchers, container::newId) }
    val settings by remember { repos.firms.observeSettings(firmId) }.collectAsState(initial = null)
    val years by remember { repos.firms.observeFinancialYears(firmId) }.collectAsState(initial = emptyList())
    var date by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<FinancialYear?>(null) }

    fun run(done: String, block: suspend () -> Unit) = scope.launch {
        messages?.show(runCatching { block(); done }.getOrElse { it.message ?: "Not allowed" })
    }

    Scaffold(topBar = { AppTopBar("Lock books and close year", onBack = onBack) }, snackbarHost = { AppSnackbarHost() }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item {
                    Column(Modifier.padding(horizontal = pageGutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SectionHeader("Lock date")
                        val lock = settings?.lockDate
                        Text(
                            lock?.let { "Books are locked up to ${Fmt.date(it)}. Vouchers on or before it cannot be posted, cancelled or restored." }
                                ?: "Books are not locked by date.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FormField("Lock up to", date, { date = it }, placeholder = "dd-mm-yyyy", supporting = "Usually the last day of a filed return period")
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            val parsed = Parse.date(date)
                            FilledTonalButton(enabled = parsed != null, onClick = { run("Books locked") { books.setLockDate(firmId, parsed) } }) { Text("Lock") }
                            OutlinedButton(enabled = lock != null, onClick = { run("Lock removed") { books.setLockDate(firmId, null) } }) { Text("Unlock") }
                        }
                        SectionHeader("Financial years")
                        Text(
                            "Closing a year locks it. Balances carry forward automatically. A closed year can be reopened until a voucher of a later year is posted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(years, key = { it.id }) { fy ->
                    ListItem(
                        headlineContent = { Text(Fmt.financialYearLabel(fy.startDate.year)) },
                        supportingContent = {
                            Text(
                                "${Fmt.date(fy.startDate)} to ${Fmt.date(fy.endDate)} · " +
                                    (fy.closedAt?.let { "closed ${Fmt.date(it.toLocalDateTime(container.timeZone).date)}" } ?: "open"),
                            )
                        },
                        trailingContent = {
                            if (fy.closedAt == null) {
                                TextButton(onClick = { confirm = fy }) { Text("Close") }
                            } else {
                                TextButton(onClick = { run("Year reopened") { books.reopenYear(fy.id) } }) { Text("Reopen") }
                            }
                        },
                    )
                }
            }
        }
    }
    confirm?.let { fy ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Close ${Fmt.financialYearLabel(fy.startDate.year)}?") },
            text = { Text("Vouchers dated ${Fmt.date(fy.startDate)} to ${Fmt.date(fy.endDate)} will be locked. Take a backup first.") },
            confirmButton = { TextButton(onClick = { confirm = null; run("Year closed") { books.closeYear(fy.id, container.now()) } }) { Text("Close year") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Newest audit events, exportable as CSV. */
@Composable
fun AuditLogScreen(container: AppContainer, session: Session, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val events by remember { container.repositories.audit.observeRecent(session.firm.id, 1000) }.collectAsState(initial = emptyList())
    fun at(e: app.openbill.core.domain.model.AuditEvent) = e.createdAt.toLocalDateTime(container.timeZone).let { "${Fmt.date(it.date)} ${it.time.toString().take(5)}" }
    Scaffold(
        topBar = {
            AppTopBar("Audit log", onBack = onBack) {
                IconButton(onClick = {
                    scope.launch {
                        val table = ReportTable(
                            "Audit log",
                            listOf("at", "action", "entity_type", "entity_id", "voucher_id", "details").map { app.openbill.core.domain.report.ReportColumn(it, it) },
                            events.map { listOf(it.createdAt.toLocalDateTime(TimeZone.UTC).toString() + "Z", it.action, it.entityType, it.entityId, it.voucherId.orEmpty(), it.detailsJson.orEmpty()) },
                        )
                        (runCatching { container.platform.saveTextFile("audit-log.csv", table.toCsv()) }.getOrElse { it.message })?.let { messages?.show(it) }
                    }
                }) { Icon(Icons.Filled.Share, contentDescription = "Export CSV") }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                if (events.isEmpty()) item { SectionHeader("No audit events yet", Modifier.padding(horizontal = pageGutter)) }
                items(events, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.action.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        supportingContent = {
                            Text(listOfNotNull(at(e), e.entityType, e.detailsJson).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }
}
