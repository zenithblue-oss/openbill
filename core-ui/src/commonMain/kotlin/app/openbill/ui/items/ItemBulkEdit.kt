package app.openbill.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.usecase.BulkItemEdit
import app.openbill.core.domain.usecase.PriceChange
import app.openbill.core.model.Rate
import app.openbill.ui.AppContainer
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Category picker with "No category" first and, for users who may change masters, a New
 * button that adds one and picks it. [onPick] gets null for "No category".
 */
@Composable
fun ItemCategorySheet(container: AppContainer, firmId: String, selectedId: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val categories by remember(firmId) { container.repositories.catalog.observeCategories(firmId) }.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PickerSheet<ItemCategory?>(
        title = "Category",
        options = listOf<ItemCategory?>(null) + categories.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() },
        label = { it?.name ?: "No category" },
        selected = { it?.id == selectedId },
        key = { it?.id ?: "none" },
        onSelect = { onPick(it?.id) },
        onDismiss = onDismiss,
        header = if (Access.can(Permission.MANAGE_MASTERS)) ({ TextButton(onClick = { adding = "" }) { Text("New category") } }) else null,
    )
    adding?.let { text ->
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("New category") },
            text = { FormField("Name", text, { adding = it.take(40); error = null }, error = error, placeholder = "e.g. Grocery") },
            confirmButton = {
                TextButton(onClick = {
                    val name = text.trim()
                    when {
                        name.isEmpty() -> error = "Name is required"
                        categories.any { it.deletedAt == null && it.name.equals(name, true) } -> error = "This category exists"
                        else -> scope.launch {
                            val now = container.now()
                            val c = ItemCategory(container.newId(), firmId, name, createdAt = now, updatedAt = now)
                            runCatching { container.repositories.catalog.saveCategory(c) }
                                .onSuccess { adding = null; onPick(c.id) }
                                .onFailure { error = it.message ?: "Could not add" }
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }
}

private const val UNCHANGED = "\u0000unchanged"

/**
 * Bulk edit of the selected items: category, GST rate, unit and sale price (a new price or a
 * percentage). Shows each change before applying; all items are saved together or none.
 */
@Composable
fun BulkItemEditDialog(container: AppContainer, firmId: String, selected: List<Item>, slabs: List<Rate>, onDone: (applied: Boolean) -> Unit) {
    val categories by remember(firmId) { container.repositories.catalog.observeCategories(firmId) }.collectAsState(initial = emptyList())
    val units by remember(firmId) { container.repositories.catalog.observeUnits(firmId) }.collectAsState(initial = emptyList())
    var categoryId by remember { mutableStateOf<String?>(null) } // null = unchanged
    var rate by remember { mutableStateOf<Rate?>(null) }
    var unitId by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val priceChange = PriceChange.parse(price)
    val priceError = if (price.isNotBlank() && priceChange == null) "Enter a price such as 120, or a change such as +10% or -5%" else null
    val edit = BulkItemEdit(categoryId, rate, unitId, priceChange)
    val changes = remember(edit, selected) { selected.mapNotNull { old -> edit.apply(old, container.now())?.let { old to it } } }
    val catName = { id: String? -> if (id == null) "None" else categories.firstOrNull { it.id == id }?.name ?: "?" }
    val unitName = { id: String -> units.firstOrNull { it.id == id }?.symbol ?: "?" }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone(false) },
        title = { Text(if (preview) "Check ${changes.size} ${if (changes.size == 1) "change" else "changes"}" else "Edit ${selected.size} items") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (!preview) {
                    Text("Leave a field as it is to keep each item's own value.", style = MaterialTheme.typography.bodyMedium)
                    PickerField("Category", when (categoryId) { null -> "Unchanged"; BulkItemEdit.NO_CATEGORY -> "No category"; else -> catName(categoryId) }, { pick = "category" })
                    PickerField("GST rate", rate?.let { "${Fmt.percent(it)}%" } ?: "Unchanged", { pick = "rate" })
                    PickerField("Unit", unitId?.let { id -> units.firstOrNull { it.id == id }?.let { "${it.name} (${it.symbol})" } } ?: "Unchanged", { pick = "unit" })
                    FormField(
                        "Sale price", price, { price = it.take(12) }, error = priceError,
                        placeholder = "Unchanged", supporting = "New price (120) or change (+10%, -5%), before GST",
                    )
                    if (categoryId != null || rate != null || unitId != null) {
                        TextButton(onClick = { categoryId = null; rate = null; unitId = null; price = "" }) { Text("Reset all to unchanged") }
                    }
                    if (unitId != null) Text("Changing the unit does not convert stock or past documents; quantities keep their numbers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    if (changes.isEmpty()) Text("Nothing would change: the items already have these values.")
                    changes.forEach { (old, new) ->
                        val parts = buildList {
                            if (old.categoryId != new.categoryId) add("Category ${catName(old.categoryId)} → ${catName(new.categoryId)}")
                            if (old.taxRate != new.taxRate) add("GST ${Fmt.percent(old.taxRate)}% → ${Fmt.percent(new.taxRate)}%")
                            if (old.unitId != new.unitId) add("Unit ${unitName(old.unitId)} → ${unitName(new.unitId)}")
                            if (old.salePrice != new.salePrice) add("Price ${Fmt.inr(old.salePrice)} → ${Fmt.inr(new.salePrice)}")
                        }
                        ListItem(
                            headlineContent = { Text(old.name) },
                            supportingContent = { Text(parts.joinToString("\n")) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                        )
                    }
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (!preview) {
                TextButton(enabled = !edit.isEmpty && priceError == null, onClick = { preview = true }) { Text("Preview") }
            } else {
                TextButton(enabled = changes.isNotEmpty() && !saving, onClick = {
                    saving = true
                    failure = null
                    scope.launch {
                        runCatching { container.repositories.catalog.saveItems(changes.map { it.second }) }
                            .onSuccess { messages?.show("Updated ${changes.size} ${if (changes.size == 1) "item" else "items"}"); onDone(true) }
                            .onFailure { failure = it.message ?: "Could not save; nothing was changed"; saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Apply to ${changes.size}") }
            }
        },
        dismissButton = {
            if (preview) TextButton(enabled = !saving, onClick = { preview = false }) { Text("Back") }
            else TextButton(onClick = { onDone(false) }) { Text("Cancel") }
        },
    )
    when (pick) {
        // "Unchanged" marks nothing as selected; "No category" marks the none row.
        "category" -> ItemCategorySheet(container, firmId, when (categoryId) { null -> UNCHANGED; BulkItemEdit.NO_CATEGORY -> null; else -> categoryId }, { categoryId = it ?: BulkItemEdit.NO_CATEGORY; pick = null }, { pick = null })
        "rate" -> GstRateSheet(slabs, rate ?: Rate.ofPercent(18), { rate = it; pick = null }, { pick = null })
        "unit" -> UnitSheet(units, unitId, { unitId = it.id; pick = null }, { pick = null })
    }
}
