package app.openbill.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.openbill.core.domain.model.VoucherType

/** Actions and screens offered by the desktop command palette (Ctrl/Cmd+K). */
fun paletteCommands(nav: Navigator, focusSearch: () -> Unit): List<Pair<String, () -> Unit>> = buildList {
    add("New invoice" to { nav.push(Route.InvoiceEditor(null)) })
    add("New purchase bill" to { nav.push(Route.InvoiceEditor(null, VoucherType.PURCHASE)) })
    add("Record payment in" to { nav.push(Route.MoneyEditor(VoucherType.PAYMENT_IN)) })
    add("Record payment out" to { nav.push(Route.MoneyEditor(VoucherType.PAYMENT_OUT)) })
    add("Record expense" to { nav.push(Route.MoneyEditor(VoucherType.EXPENSE)) })
    add("New party" to { nav.push(Route.PartyEdit(null)) })
    add("New item" to { nav.push(Route.ItemEdit(null)) })
    add("Search the current list" to focusSearch)
    Tab.entries.forEach { t -> add("Go to ${t.label}" to { nav.selectTab(t) }) }
    add("Purchases" to { nav.push(Route.Purchases) })
    add("Quotations" to { nav.push(Route.Documents(VoucherType.QUOTATION)) })
    add("Proforma invoices" to { nav.push(Route.Documents(VoucherType.PROFORMA)) })
    add("Delivery challans" to { nav.push(Route.Documents(VoucherType.DELIVERY_CHALLAN)) })
    add("Receivables" to { nav.push(Route.Outstanding(receivable = true)) })
    add("Payables" to { nav.push(Route.Outstanding(receivable = false)) })
    add("Stock summary" to { nav.push(Route.StockSummary()) })
    add("Low stock" to { nav.push(Route.StockSummary(lowOnly = true)) })
    add("Godowns" to { nav.push(Route.Godowns) })
    add("Reports" to { nav.push(Route.Reports) })
    add("Audit log" to { nav.push(Route.AuditLog) })
    add("Books lock" to { nav.push(Route.BooksLock) })
    add("Business details" to { nav.push(Route.BusinessEdit) })
    add("Invoice templates" to { nav.push(Route.InvoiceTemplates) })
}

/** Every word of [query] must appear in the label, in any order. */
fun filterCommands(commands: List<Pair<String, () -> Unit>>, query: String): List<Pair<String, () -> Unit>> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    return commands.filter { (label, _) -> words.all { label.lowercase().contains(it) } }
}

@Composable
fun CommandPalette(commands: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shown = filterCommands(commands, query)
    fun run(action: () -> Unit) { onDismiss(); action() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Type a command or screen; Enter runs the first") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { shown.firstOrNull()?.let { run(it.second) }; true } else false
                    },
                )
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { (label, action) ->
                        ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable(role = Role.Button) { run(action) })
                    }
                    if (shown.isEmpty()) Text("No matching command", Modifier.padding(16.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
