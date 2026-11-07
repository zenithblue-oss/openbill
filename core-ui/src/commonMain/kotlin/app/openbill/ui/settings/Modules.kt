package app.openbill.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional parts of the app a business can hide when it does not use them. */
enum class Module(val title: String, val description: String) {
    POS("POS billing", "Counter screen with scan, split payment and held bills"),
    DOCUMENTS("Quotations, challans and orders", "Quotations, proforma invoices, delivery challans, sales and purchase orders"),
    RECURRING("Recurring invoices", "Invoices made on a schedule"),
}

/**
 * Per-business module switches on this device. Turning a module off only hides its entry points;
 * its records stay, keep counting in reports, and schedules keep running.
 */
class ModulesController(private val store: SettingsStore) {
    private val flows = mutableMapOf<String, MutableStateFlow<Set<Module>>>()

    private fun flow(firmId: String) = flows.getOrPut(firmId) {
        val raw = runCatching { store.getString("modules.off.$firmId") }.getOrNull().orEmpty()
        MutableStateFlow(raw.split(',').mapNotNull { n -> Module.entries.firstOrNull { it.name == n } }.toSet())
    }

    fun disabled(firmId: String): StateFlow<Set<Module>> = flow(firmId).asStateFlow()

    fun isOn(firmId: String, module: Module): Boolean = module !in flow(firmId).value

    fun setEnabled(firmId: String, module: Module, enabled: Boolean) {
        val f = flow(firmId)
        f.value = if (enabled) f.value - module else f.value + module
        runCatching { store.putString("modules.off.$firmId", f.value.joinToString(",") { it.name }) }
    }
}

/** More screen rows: one switch per [Module]. */
@Composable
fun ModuleSwitches(modules: ModulesController, firmId: String, colors: ListItemColors) {
    val off by modules.disabled(firmId).collectAsState()
    Module.entries.forEach { m ->
        val on = m !in off
        ListItem(
            headlineContent = { Text(m.title) },
            supportingContent = { Text(if (on) m.description else "Hidden. Existing records stay and keep working.") },
            trailingContent = { Switch(checked = on, onCheckedChange = null) },
            colors = colors,
            modifier = Modifier.toggleable(value = on, role = Role.Switch) { modules.setEnabled(firmId, m, it) },
        )
    }
}
