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
import app.openbill.core.model.Progress
import app.openbill.ui.components.ProgressOverlay
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.domain.csv.attachmentExport
import app.openbill.core.domain.csv.attachmentOwners
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

/** Imported items' opening stock as one posted "Opening stock" adjustment dated today (inside the import transaction). */
private suspend fun postOpenings(container: AppContainer, firmId: String, items: List<Item>, openings: Map<String, app.openbill.core.domain.csv.OpeningStock>) {
    val repos = container.repositories
    val today = container.today()
    val fy = repos.firms.observeFinancialYears(firmId).first().firstOrNull { today >= it.startDate && today <= it.endDate }
        ?: error("No financial year covers today; add one before importing opening stock")
    val firm = repos.firms.findFirm(firmId) ?: error("Unknown firm")
    app.openbill.core.domain.usecase.StockVouchers(repos.vouchers, container.postVoucher).postOpenings(
        container.newId(), firmId, fy.id, firm.stateCode, today, items, openings,
        { app.openbill.ui.inventory.Inventory.ensureDefaultGodown(container, firmId) }, container.now(),
    )
}

// attachmentOwners and attachmentExport live in core-domain (csv/AttachmentExport.kt), shared with the CLI.

/** CSV import of parties and items (dry-run preview first, all rows or none), CSV export, and the recycle bin. */
@Composable
fun CsvSection(container: AppContainer, session: Session, listColors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val repos = container.repositories
    val firmId = session.firm.id
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var preview by remember { mutableStateOf<Pair<String, CsvImport<*>>?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true
        scope.launch {
            val msg = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
            busy = false
            progress = null
            msg?.let { messages?.show(it) }
        }
    }

    fun import(entity: String) = run {
        val text = container.platform.openTextFile() ?: return@run null
        progress = Progress("Checking rows")
        preview = entity to withContext(Dispatchers.Default) {
            if (entity == "parties") MasterCsv.parties(text, firmId, container::newId, container.now())
            else if (entity == "contacts") MasterCsv.contacts(text, firmId, repos.parties.observeParties(firmId).first().filter { it.deletedAt == null }, container::newId, container.now())
            else MasterCsv.items(
                text, firmId, repos.catalog.observeUnits(firmId).first(), repos.catalog.observeItems(firmId).first().filter { it.deletedAt == null },
                container::newId, container.now(), repos.catalog.observeGodowns(firmId).first(),
            )
        }
        null
    }

    // Each row needs the right its write or read does; the recycle bin is for whoever bills.
    val can = { p: app.openbill.core.domain.model.Permission -> app.openbill.core.domain.model.Access.can(p) }
    val imports = listOf("parties", "contacts", "items").filter {
        can(if (it == "items") app.openbill.core.domain.model.Permission.MANAGE_MASTERS else app.openbill.core.domain.model.Permission.EDIT_PARTIES)
    }
    val canExport = can(app.openbill.core.domain.model.Permission.EXPORT)
    if (imports.isNotEmpty() || canExport) SectionHeader("Import and export")
    imports.forEach { entity ->
        ListItem(
            headlineContent = { Text(if (entity == "contacts") "Import contacts (vCard)" else "Import $entity from CSV") },
            supportingContent = {
                Text(
                    if (entity == "contacts") "A .vcf file exported from your phone or address book; new contacts become customers, ones already here are skipped"
                    else if (entity == "parties") "Header: ${MasterCsv.PARTY_COLUMNS.joinToString(", ")}; optional ${MasterCsv.PARTY_OPENING_COLUMNS.joinToString(", ")}. Exports from other billing software or Excel (saved as CSV) are matched by column name"
                    else "Header: ${MasterCsv.ITEM_COLUMNS.joinToString(", ")}; optional opening stock: ${MasterCsv.OPENING_COLUMNS.joinToString(", ")} (expiry as YYYY-MM-DD). Other software's column names such as Item Name, HSN Code, GST % are recognised",
                )
            },
            leadingContent = { Icon(Icons.Outlined.AddCircle, contentDescription = null) },
            colors = listColors,
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { import(entity) },
        )
    }
    if (imports.any { it != "contacts" }) ListItem(
        headlineContent = { Text("Save CSV templates") },
        supportingContent = { Text("Empty party and item sheets with every column and an example row, to fill in a spreadsheet and import") },
        leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
            run {
                listOfNotNull(
                    if ("parties" in imports) container.platform.saveTextFile("parties-template.csv", MasterCsv.partiesTemplate()) else null,
                    if ("items" in imports) container.platform.saveTextFile("items-template.csv", MasterCsv.itemsTemplate()) else null,
                ).joinToString("; ").ifEmpty { null }
            }
        },
    )
    if (canExport) ListItem(
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
    if (canExport && container.platform.supportsAttachments) ListItem(
        headlineContent = { Text("Export attachments") },
        supportingContent = { Text("Every file attached to this business's documents and parties as one ZIP, with an index.csv of what each file belongs to") },
        leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
            run {
                progress = Progress("Collecting attachments")
                val owners = attachmentOwners(
                    repos.vouchers.observeVouchers(firmId, LocalDate(2000, 1, 1), LocalDate(2099, 12, 31)).first(),
                    repos.parties.observeParties(firmId).first(),
                )
                val files = owners.associate { o -> o.id to container.platform.attachments(o.id) }.filterValues { it.isNotEmpty() }
                if (files.isEmpty()) return@run "No attachments in ${session.firm.name}"
                // Streamed into the chosen file one attachment at a time: no size cap from memory.
                container.platform.saveStream("attachments-${container.today()}.zip") { sink ->
                    progress = Progress("Writing ZIP")
                    val zip = app.openbill.core.domain.report.ZipWriter.Stream(sink)
                    attachmentExport(owners, files, { id, name -> container.platform.attachmentBytes(id, name) }, zip::add)
                    zip.finish()
                }
            }
        },
    )

    val bin by remember(firmId) { repos.vouchers.observeRecycleBin(firmId) }.collectAsState(initial = emptyList())
    var binOpen by remember { mutableStateOf(false) }
    if (can(app.openbill.core.domain.model.Permission.CREATE_VOUCHERS)) ListItem(
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
                        if (result.skipped > 0) append("\n${result.skipped} skipped: already a party with that phone or name.")
                        if (ok && result.openings.isNotEmpty()) append("\n${result.openings.size} with opening stock, posted as one stock adjustment dated today.")
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
                            val total = result.valid.size.toLong()
                            progress = Progress("Importing $entity", 0, total)
                            withContext(Dispatchers.Default) {
                                repos.transaction {
                                    result.valid.forEachIndexed { i, it ->
                                        if (it is Party) repos.parties.saveParty(it) else repos.catalog.saveItem(it as Item)
                                        if (i % 200 == 199 || i + 1L == total) progress = Progress("Importing $entity", i + 1L, total)
                                    }
                                    if (result.openings.isNotEmpty()) postOpenings(container, firmId, result.valid.filterIsInstance<Item>(), result.openings)
                                }
                            }
                            "Imported ${result.valid.size} $entity"
                        }
                    }) { Text("Import") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(if (ok) "Cancel" else "Close") } },
        )
    }
    // Import writes in one transaction (all rows or none), so it is not cancellable.
    if (busy && progress != null) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        ProgressOverlay(progress, "CSV import")
    }
}
