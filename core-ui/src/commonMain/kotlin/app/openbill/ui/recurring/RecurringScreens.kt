package app.openbill.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.openbill.core.domain.model.RecurrenceFrequency
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.usecase.GenerateRecurring
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.DateField
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Makes every invoice the firm's schedules owe up to today; safe to call any number of times. */
suspend fun generateRecurring(container: AppContainer, session: Session): Int =
    GenerateRecurring(container.repositories.recurring, container.repositories.vouchers) { id, at -> container.postVoucher(id, at) }
        .invoke(session.firm.id, container.today(), container.now(), container::newId) { session.financialYearFor(it)?.id }

/** "Monthly from 1 Jan 2026 until 31 Dec 2026", "Every 2 weeks from ..., 6 invoices". */
fun RecurringSchedule.describe(): String = buildString {
    append(if (interval == 1) frequency.label else "Every $interval ${frequency.unit}s")
    append(" from ").append(Fmt.date(startDate))
    endDate?.let { append(" until ").append(Fmt.date(it)) }
    maxCount?.let { append(", ").append(if (it == 1) "1 invoice" else "$it invoices") }
}

internal enum class EndKind(val label: String) { NEVER("No end"), ON_DATE("On a date"), AFTER("After a number") }

/** Inputs of the schedule form, checked by [validate]. */
internal data class ScheduleForm(
    val name: String,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val every: String = "1",
    val start: LocalDate?,
    val end: LocalDate? = null,
    val count: String = "",
    val autoPost: Boolean = false,
) {
    fun validate(endKind: EndKind): Map<String, String> = buildMap {
        if (name.isBlank()) put("name", "Give the schedule a name")
        if (every.toIntOrNull()?.takeIf { it in 1..999 } == null) put("every", "1 to 999")
        if (start == null) put("start", "Choose the first invoice date")
        if (endKind == EndKind.ON_DATE && (end == null || (start != null && end < start))) put("end", "Choose an end date on or after the start")
        if (endKind == EndKind.AFTER && count.toIntOrNull()?.takeIf { it in 1..9_999 } == null) put("count", "1 to 9999 invoices")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurringScheduleDialog(
    container: AppContainer,
    session: Session,
    template: Voucher,
    partyName: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(ScheduleForm(name = listOfNotNull(partyName, "monthly invoice").joinToString(" "), start = container.today())) }
    var endKind by remember { mutableStateOf(EndKind.NEVER) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Repeat this invoice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("A copy of ${template.number ?: "this draft"} is made on each date, with the same party, items and prices.", style = MaterialTheme.typography.bodyMedium)
                FormField("Name", form.name, { form = form.copy(name = it.take(60)) }, error = errors["name"])
                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RecurrenceFrequency.entries.forEach { f -> SelectableChip(f.label, form.frequency == f, { form = form.copy(frequency = f) }) }
                }
                FormField(
                    "Every", form.every, { form = form.copy(every = it.filter(Char::isDigit).take(3)) },
                    error = errors["every"], keyboardType = KeyboardType.Number, suffix = form.frequency.unit + "(s)",
                )
                DateField("First invoice date", form.start, { form = form.copy(start = it) }, error = errors["start"])
                Text("Ends", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    EndKind.entries.forEach { k -> SelectableChip(k.label, endKind == k, { endKind = k }) }
                }
                when (endKind) {
                    EndKind.ON_DATE -> DateField("Last invoice on or before", form.end, { form = form.copy(end = it) }, error = errors["end"])
                    EndKind.AFTER -> FormField("Number of invoices", form.count, { form = form.copy(count = it.filter(Char::isDigit).take(4)) }, error = errors["count"], keyboardType = KeyboardType.Number)
                    EndKind.NEVER -> {}
                }
                ListItem(
                    headlineContent = { Text("Post automatically") },
                    supportingContent = { Text(if (form.autoPost) "Invoices are numbered and posted on their date" else "Invoices are saved as drafts for you to check and post") },
                    trailingContent = { Switch(checked = form.autoPost, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(value = form.autoPost, role = Role.Switch) { form = form.copy(autoPost = it) },
                )
                Text(
                    "Missed dates (while the app was closed) are made the next time it opens, once each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val e = form.validate(endKind)
                errors = e
                if (e.isNotEmpty()) return@TextButton
                saving = true
                scope.launch {
                    runCatching {
                        val now = container.now()
                        container.repositories.recurring.saveSchedule(
                            RecurringSchedule(
                                id = container.newId(), firmId = session.firm.id, templateVoucherId = template.id, name = form.name.trim(),
                                frequency = form.frequency, interval = form.every.toInt(), startDate = form.start!!,
                                endDate = form.end.takeIf { endKind == EndKind.ON_DATE }, maxCount = form.count.toIntOrNull().takeIf { endKind == EndKind.AFTER },
                                autoPost = form.autoPost, createdAt = now, updatedAt = now,
                            ),
                        )
                        generateRecurring(container, session)
                    }.onSuccess { onSaved() }.onFailure { failure = it.message ?: "Could not save the schedule" }
                    saving = false
                }
            }) { Text("Save schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
fun RecurringListScreen(container: AppContainer, session: Session, onBack: () -> Unit, onOpenInvoice: (String) -> Unit) {
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val schedules by remember { repos.recurring.observeSchedules(session.firm.id) }.collectAsState(initial = null)
    var deleting by remember { mutableStateOf<RecurringSchedule?>(null) }
    fun update(s: RecurringSchedule, message: String) {
        scope.launch {
            runCatching {
                repos.recurring.saveSchedule(s)
                generateRecurring(container, session)
            }.onSuccess { made -> messages?.show(if (made > 0) "$message; $made invoice(s) made" else message) }
                .onFailure { messages?.show(it.message ?: "Could not update the schedule") }
        }
    }
    Scaffold(topBar = { AppTopBar("Recurring invoices", onBack = onBack) }, snackbarHost = { AppSnackbarHost() }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            val list = schedules
            if (list != null && list.isEmpty()) {
                EmptyState(
                    Icons.Filled.DateRange,
                    "No recurring invoices",
                    "Open an invoice and choose \"Repeat on a schedule\" to bill a customer every day, week, month or year.",
                )
            }
            LazyColumn {
                items(list.orEmpty(), key = { it.id }) { s ->
                    val status = when {
                        s.finished -> "Finished"
                        s.paused -> "Paused"
                        else -> "Next ${Fmt.date(s.nextDate!!)}"
                    }
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = {
                            Column {
                                Text(s.describe())
                                Text("$status · ${s.generatedCount} made · ${if (s.autoPost) "posted" else "drafts"}")
                                s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        },
                        trailingContent = {
                            Column {
                                if (!s.finished) {
                                    TextButton(onClick = {
                                        val now = container.now()
                                        if (s.paused) update(GenerateRecurring.resumed(s, container.today(), now), "Resumed")
                                        else update(s.copy(paused = true, updatedAt = now), "Paused")
                                    }) { Text(if (s.paused) "Resume" else "Pause") }
                                }
                                TextButton(onClick = { deleting = s }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        },
                        modifier = Modifier.clickable(onClickLabel = "Open the invoice it copies") { onOpenInvoice(s.templateVoucherId) },
                    )
                }
            }
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("No more invoices are made from it. The ${s.generatedCount} invoice(s) it already made are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { repos.recurring.deleteSchedule(s.id) }
                            .onSuccess { messages?.show("Schedule deleted") }
                            .onFailure { messages?.show(it.message ?: "Could not delete") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}
