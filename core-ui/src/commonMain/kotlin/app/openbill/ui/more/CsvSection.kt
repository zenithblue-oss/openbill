package app.openbill.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import app.openbill.ui.format.Fmt
import app.openbill.ui.invoice.docLabels
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.openbill.core.domain.csv.CsvImport
import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.VoucherType
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** CSV import of parties and items (dry-run preview first, all rows or none), CSV export, and the recycle bin. */
@Composable
fun CsvSection(container: AppContainer, session: Session, listColors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val repos = container.repositories
    val firmId = session.firm.id
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<String, CsvImport<*>>?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true
        scope.launch {
            val msg = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
            busy = false
            msg?.let { messages?.show(it) }
        }
    }

    fun import(entity: String) = run {
        val text = container.platform.openTextFile() ?: return@run null
        preview = entity to if (entity == "parties") MasterCsv.parties(text, firmId, container::newId, container.now())
        else MasterCsv.items(text, firmId, repos.catalog.observeUnits(firmId).first(), repos.catalog.observeItems(firmId).first().filter { it.deletedAt == null }, container::newId, container.now())
        null
    }

    SectionHeader("Import and export")
    listOf("parties", "items").forEach { entity ->
        ListItem(
            headlineContent = { Text("Import $entity from CSV") },
            supportingContent = { Text("Header: ${(if (entity == "parties") MasterCsv.PARTY_COLUMNS else MasterCsv.ITEM_COLUMNS).joinToString(", ")}") },
            leadingContent = { Icon(Icons.Outlined.AddCircle, contentDescription = null) },
            colors = listColors,
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { import(entity) },
        )
    }
    ListItem(
        headlineContent = { Text("Export CSV") },
        supportingContent = { Text("Parties, items and sales invoices, for your accountant or another app") },
        leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
            run {
                val parties = repos.parties.observeParties(firmId).first().filter { it.deletedAt == null }
                val units = repos.catalog.observeUnits(firmId).first().associate { it.id to it.symbol }
                val items = repos.catalog.observeItems(firmId).first().filter { it.deletedAt == null }
                val names = parties.associate { it.id to it.name }
                val invoices = repos.vouchers.observeVouchers(firmId, LocalDate(2000, 1, 1), LocalDate(2099, 12, 31)).first()
                    .filter { it.type == VoucherType.SALE && it.deletedAt == null }.sortedWith(compareBy({ it.date }, { it.number ?: "" }))
                listOf(
                    container.platform.saveTextFile("parties.csv", MasterCsv.partiesCsv(parties)),
                    container.platform.saveTextFile("items.csv", MasterCsv.itemsCsv(items) { units[it] }),
                    container.platform.saveTextFile("invoices.csv", MasterCsv.invoicesCsv(invoices) { names[it] }),
                ).filterNotNull().joinToString("; ").ifEmpty { null }
            }
        },
    )

    val bin by remember(firmId) { repos.vouchers.observeRecycleBin(firmId) }.collectAsState(initial = emptyList())
    var binOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Recycle bin (${bin.size})") },
        supportingContent = { Text("Deleted drafts; restore them to keep working") },
        leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = bin.isNotEmpty(), role = Role.Button) { binOpen = true },
    )
    if (binOpen) {
        AlertDialog(
            onDismissRequest = { binOpen = false },
            title = { Text("Recycle bin") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (bin.isEmpty()) Text("Empty")
                    bin.forEach { v ->
                        ListItem(
                            headlineContent = { Text("${docLabels(v.type).title} draft · ${Fmt.inr(v.total)}") },
                            supportingContent = { Text("Dated ${Fmt.date(v.date)}") },
                            trailingContent = {
                                TextButton(onClick = { run { repos.vouchers.restoreDraft(v.id, container.now()); "Draft restored" } }) { Text("Restore") }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { binOpen = false }) { Text("Close") } },
        )
    }

    preview?.let { (entity, result) ->
        val ok = result.errors.isEmpty() && result.valid.isNotEmpty()
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(if (ok) "Import ${result.valid.size} $entity?" else "Nothing imported") },
            text = {
                Text(
                    buildString {
                        append("${result.rows} rows checked: ${result.valid.size} valid, ${result.errors.size} with errors.")
                        result.errors.take(8).forEach { (row, msg) -> append("\nRow $row: $msg") }
                        if (result.errors.size > 8) append("\n…and ${result.errors.size - 8} more")
                        if (ok) {
                            append("\n\n")
                            append(result.valid.take(5).joinToString { (it as? Party)?.name ?: (it as Item).name })
                            if (result.valid.size > 5) append(", …")
                        } else if (result.errors.isNotEmpty()) {
                            append("\n\nFix these rows and import the file again; no row is imported until all are valid.")
                        }
                    },
                )
            },
            confirmButton = {
                if (ok) {
                    TextButton(onClick = {
                        preview = null
                        run {
                            // shortcut: rows are saved one by one, not in one transaction; the dry run above
                            // makes a mid-way failure unlikely. Upgrade when repositories expose transactions.
                            result.valid.forEach { if (it is Party) repos.parties.saveParty(it) else repos.catalog.saveItem(it as Item) }
                            "Imported ${result.valid.size} $entity"
                        }
                    }) { Text("Import") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(if (ok) "Cancel" else "Close") } },
        )
    }
}
