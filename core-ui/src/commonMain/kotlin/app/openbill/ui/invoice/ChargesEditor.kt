package app.openbill.ui.invoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.ui.components.AnimatedMoney
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.FullScreenEditor
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.items.GstRateSheet
import app.openbill.ui.theme.Spacing

/** The charge dialog's text inputs. */
data class ChargeForm(
    val key: String? = null,
    val name: String = "",
    val amount: String = "",
    val basis: ChargeTaxBasis = ChargeTaxBasis.PRINCIPAL_SUPPLY,
    val gstRate: Rate = Rate.ofPercent(18),
    val inclusive: Boolean = false,
)

object ChargeRules {
    val suggestions = listOf("Freight", "Packing", "Insurance", "Other")

    fun fromDraft(c: DraftCharge) = ChargeForm(c.key, c.name, c.amount.format(), c.basis, c.gstRate, c.inclusive)

    fun basisLabel(b: ChargeTaxBasis): String = when (b) {
        ChargeTaxBasis.NON_TAXABLE -> "Non-taxable"
        ChargeTaxBasis.EXPLICIT_RATE -> "Explicit GST rate"
        ChargeTaxBasis.PRINCIPAL_SUPPLY -> "Follow principal item"
    }

    fun validate(form: ChargeForm, newKey: () -> String): Pair<DraftCharge?, Map<String, String>> {
        val errors = mutableMapOf<String, String>()
        if (form.name.isBlank()) errors["name"] = "Name is required"
        val amount = Parse.money(form.amount)
        if (amount == null || amount.paise <= 0) errors["amount"] = "Enter an amount above zero"
        if (errors.isNotEmpty()) return null to errors
        return DraftCharge(
            key = form.key ?: newKey(),
            name = form.name.trim(),
            amount = amount!!,
            basis = form.basis,
            gstRate = form.gstRate,
            inclusive = form.inclusive && form.basis != ChargeTaxBasis.NON_TAXABLE,
        ) to emptyMap()
    }
}

/** "Additional charges" section: one card per charge plus an add button. */
@Composable
fun ChargesSection(state: InvoiceEditorState, onAdd: () -> Unit, onEdit: (DraftCharge) -> Unit) {
    SectionHeader(if (state.charges.isEmpty()) "Additional charges" else "Additional charges (${state.charges.size})")
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.charges.forEachIndexed { index, c ->
            val result = state.summary?.chargeResults?.getOrNull(index)
            OutlinedCard(onClick = { onEdit(c) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        AnimatedMoney(result?.result?.totalAmount, MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        buildList {
                            add(Fmt.inr(c.amount))
                            add(
                                when (c.basis) {
                                    ChargeTaxBasis.NON_TAXABLE -> "No GST"
                                    ChargeTaxBasis.EXPLICIT_RATE -> "GST ${Fmt.percent(c.gstRate)}%"
                                    ChargeTaxBasis.PRINCIPAL_SUPPLY -> "GST as items"
                                } + if (c.inclusive && c.basis != ChargeTaxBasis.NON_TAXABLE) " incl." else "",
                            )
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Add charge")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChargeEditor(
    form: ChargeForm?,
    slabs: List<Rate>,
    onSave: (ChargeForm) -> Map<String, String>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // While the editor animates out (form == null) it keeps showing the last edited values.
    val holder = remember { object { var last = ChargeForm() } }
    var edit by remember(form) { mutableStateOf(form ?: holder.last) }
    var errors by remember(form) { mutableStateOf(emptyMap<String, String>()) }
    var pickRate by remember { mutableStateOf(false) }
    var pickBasis by remember { mutableStateOf(false) }
    if (form != null) holder.last = edit
    val current = edit
    FullScreenEditor(
        visible = form != null,
        title = if (current.key == null) "Add charge" else "Edit charge",
        confirmLabel = "Done",
        onConfirm = { errors = onSave(edit) },
        onClose = onDismiss,
    ) {
        FormColumn {
            FormField("Name *", current.name, { edit = edit.copy(name = it) }, error = errors["name"])
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ChargeRules.suggestions.forEach { s ->
                    SelectableChip(s, current.name == s, { edit = edit.copy(name = s) })
                }
            }
            FormField(
                "Amount *",
                current.amount,
                { edit = edit.copy(amount = it) },
                error = errors["amount"],
                keyboardType = KeyboardType.Decimal,
                prefix = "₹",
                imeAction = ImeAction.Done,
                onImeAction = { errors = onSave(edit) },
            )
            PickerField("Tax", ChargeRules.basisLabel(current.basis), { pickBasis = true })
            if (current.basis == ChargeTaxBasis.EXPLICIT_RATE) {
                PickerField("GST rate", "${Fmt.percent(current.gstRate)}%", { pickRate = true })
            }
            if (current.basis != ChargeTaxBasis.NON_TAXABLE) {
                ListItem(
                    headlineContent = { Text("Amount includes GST") },
                    supportingContent = { Text("Turn on when the amount above already has GST in it") },
                    trailingContent = { Switch(checked = current.inclusive, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.toggleable(value = current.inclusive, role = Role.Switch) { edit = edit.copy(inclusive = it) },
                )
            }
            val key = current.key
            if (key != null) {
                TextButton(onClick = { onDelete(key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Remove charge")
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
    if (pickBasis) {
        app.openbill.ui.components.PickerSheet(
            title = "Tax on this charge",
            options = ChargeTaxBasis.entries.toList(),
            label = { ChargeRules.basisLabel(it) },
            supporting = {
                when (it) {
                    ChargeTaxBasis.NON_TAXABLE -> "Outside the value of supply, no GST"
                    ChargeTaxBasis.EXPLICIT_RATE -> "Pick a GST slab"
                    ChargeTaxBasis.PRINCIPAL_SUPPLY -> "Taxed like the items it travels with"
                }
            },
            selected = { it == current.basis },
            searchable = false,
            onSelect = { edit = edit.copy(basis = it); pickBasis = false },
            onDismiss = { pickBasis = false },
        )
    }
    if (pickRate) {
        GstRateSheet(slabs, current.gstRate, { r -> edit = edit.copy(gstRate = r); pickRate = false }, { pickRate = false })
    }
}
