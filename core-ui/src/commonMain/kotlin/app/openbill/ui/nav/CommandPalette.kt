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
    // Only screens the signed-in user may open.
    fun go(label: String, route: Route) {
        if (route.permission?.let(app.openbill.core.domain.model.Access::can) != false) add(label to { nav.push(route) })
    }
    go("New invoice", Route.InvoiceEditor(null))
    go("POS billing (counter)", Route.Pos())
    go("New purchase bill", Route.InvoiceEditor(null, VoucherType.PURCHASE))
    go("Record payment in", Route.MoneyEditor(VoucherType.PAYMENT_IN))
    go("Record payment out", Route.MoneyEditor(VoucherType.PAYMENT_OUT))
    go("Record expense", Route.MoneyEditor(VoucherType.EXPENSE))
    go("New party", Route.PartyEdit(null))
    go("New item", Route.ItemEdit(null))
    add("Search the current list" to focusSearch)
    Tab.entries.forEach { t -> add("Go to ${t.label}" to { nav.selectTab(t) }) }
    go("Purchases", Route.Purchases)
    go("Quotations", Route.Documents(VoucherType.QUOTATION))
    go("Proforma invoices", Route.Documents(VoucherType.PROFORMA))
    go("Delivery challans", Route.Documents(VoucherType.DELIVERY_CHALLAN))
    go("Sales orders", Route.Documents(VoucherType.SALES_ORDER))
    go("Purchase orders", Route.Documents(VoucherType.PURCHASE_ORDER))
    go("Recurring invoices", Route.Recurring)
    go("Receivables", Route.Outstanding(receivable = true))
    go("Payables", Route.Outstanding(receivable = false))
    go("Stock summary", Route.StockSummary())
    go("Low stock", Route.StockSummary(lowOnly = true))
    go("Godowns", Route.Godowns)
    go("Reports", Route.Reports)
    go("Audit log", Route.AuditLog)
    go("Books lock", Route.BooksLock)
    go("Business details", Route.BusinessEdit)
    go("Invoice templates", Route.InvoiceTemplates)
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
