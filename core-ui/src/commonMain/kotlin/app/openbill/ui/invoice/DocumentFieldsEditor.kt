package app.openbill.ui.invoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.openbill.core.domain.model.DocumentField
import app.openbill.core.domain.model.ExportDetails
import app.openbill.core.domain.model.ExportType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.TransportDetails
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Quantity
import app.openbill.core.tax.Gstin
import app.openbill.ui.components.DateField
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalWidthClass
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.nav.WidthClass
import app.openbill.ui.settings.SettingsStore
import app.openbill.ui.theme.Spacing
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// DocFieldsForm and DocFieldRules live in core-printing (DocFields.kt), shared with the CLI.

/** Custom header labels remembered per firm on this device (the values live on each document). */
object CustomFieldLabels {
    private fun key(firmId: String) = "docFieldLabels.$firmId"
    fun load(store: SettingsStore, firmId: String): List<String> =
        store.getString(key(firmId)).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun save(store: SettingsStore, firmId: String, labels: List<String>) = store.putString(key(firmId), labels.distinct().joinToString("\n"))
}

/** GSTR-1 of a month is due on the 11th of the next month. */
object FilingPeriod {
    fun gstr1DueDate(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1).plus(DatePeriod(months = 1, days = 10))

    /** A warning when an outward document dated [date] falls in a period whose GSTR-1 is already due, else null. */
    fun warning(type: VoucherType, date: LocalDate, today: LocalDate): String? {
        if (type != VoucherType.SALE && type != VoucherType.CREDIT_NOTE) return null
        val due = gstr1DueDate(date)
        if (today <= due) return null
        return "This document is dated ${Fmt.date(date)}. GSTR-1 for that month was due on ${Fmt.date(due)}; " +
            "if it is already filed, report this change in your next return."
    }
}

/** Reverse charge, export/SEZ, transport, e-commerce operator, PO, e-way bill and custom fields. */
@Composable
fun DocumentFieldsSection(
    type: VoucherType,
    form: DocFieldsForm,
    errors: Map<String, String>,
    onChange: (DocFieldsForm) -> Unit,
    onLabelsChange: (List<String>) -> Unit,
) {
    val wide = LocalWidthClass.current != WidthClass.COMPACT
    var pickExport by remember { mutableStateOf(false) }
    var addLabel by remember { mutableStateOf<String?>(null) }
    val hasTransport = DocFieldRules.transport(form) != null
    var transportOpen by rememberSaveable { mutableStateOf(hasTransport) }
    val hasOther = form.poNumber.isNotBlank() || form.poDate != null || form.ewayBillNumber.isNotBlank() || form.ecommerceGstin.isNotBlank() || form.custom.isNotEmpty()
    var otherOpen by rememberSaveable { mutableStateOf(hasOther) }

    @Composable
    fun pair(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { a(Modifier.weight(1f)); b(Modifier.weight(1f)) }
        } else {
            a(Modifier); b(Modifier)
        }
    }

    @Composable
    fun toggle(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) = ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
    )

    SectionHeader("Document details")
    if (DocFieldRules.supportsReverseCharge(type)) {
        toggle(
            "Reverse charge",
            if (type == VoucherType.PURCHASE || type == VoucherType.DEBIT_NOTE) "You pay this GST to the government, not to the supplier"
            else "GST is shown but the customer pays it; the total leaves it out",
            form.reverseCharge,
        ) { onChange(form.copy(reverseCharge = it)) }
        errors["reverseCharge"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (DocFieldRules.supportsExport(type)) {
        PickerField(
            "Export or SEZ supply",
            form.exportType?.label ?: "No (regular supply)",
            { pickExport = true },
            error = errors["export"],
            supporting = form.exportType?.let { if (it.withTax) "IGST is charged" else "Zero-rated: no IGST" },
        )
        if (form.exportType != null) {
            pair(
                { m -> FormField("Shipping bill no.", form.shippingBillNumber, { onChange(form.copy(shippingBillNumber = it)) }, m, capitalization = KeyboardCapitalization.Characters) },
                { m -> DateField("Shipping bill date", form.shippingBillDate, { onChange(form.copy(shippingBillDate = it)) }, m, onClear = { onChange(form.copy(shippingBillDate = null)) }) },
            )
            pair(
                { m -> FormField("Port code", form.portCode, { onChange(form.copy(portCode = it.take(6))) }, m, error = errors["port"], capitalization = KeyboardCapitalization.Characters, placeholder = "INBOM1") },
                { m -> FormField("LUT / bond reference", form.lutReference, { onChange(form.copy(lutReference = it)) }, m, capitalization = KeyboardCapitalization.Characters) },
            )
            val code = form.currency.trim().uppercase()
            // The exchange rate is a price: without price rights the currency and rate stay as set
            // by a manager (on this draft or its order); the repository enforces the same.
            val canSetRate = app.openbill.core.domain.model.Access.can(app.openbill.core.domain.model.Permission.MANAGE_MASTERS)
            pair(
                { m ->
                    FormField(
                        "Invoice currency", form.currency, { onChange(form.copy(currency = it.filter(Char::isLetter).take(3).uppercase())) }, m,
                        error = errors["currency"], capitalization = KeyboardCapitalization.Characters, placeholder = "INR",
                        supporting = when {
                            !canSetRate -> "Only an owner or manager can set the currency and exchange rate"
                            code.isEmpty() -> "Blank for rupees; e.g. USD to bill in dollars"
                            else -> "Rates and charges on this invoice are in $code"
                        },
                        readOnly = !canSetRate,
                    )
                },
                { m ->
                    if (code.isNotEmpty()) {
                        FormField(
                            "Exchange rate (₹ per 1 $code) *", form.exchangeRate, { onChange(form.copy(exchangeRate = it)) }, m,
                            error = errors["exchangeRate"], keyboardType = KeyboardType.Decimal, prefix = "₹",
                            supporting = "GST, ledger and returns use rupees at this rate",
                            readOnly = !canSetRate,
                        )
                    }
                },
            )
            // Free-text currency notes from before foreign-currency invoices stay editable.
            if (form.currencyNote.isNotEmpty()) FormField("Currency note (printed only)", form.currencyNote, { onChange(form.copy(currencyNote = it)) })
        }
    }
    toggle("Transport details", "Transporter, vehicle, LR number and distance", transportOpen || hasTransport) { transportOpen = it }
    if (transportOpen || hasTransport) {
        pair(
            { m -> FormField("Transporter name", form.transporterName, { onChange(form.copy(transporterName = it)) }, m) },
            { m -> FormField("Transporter ID / GSTIN", form.transporterId, { onChange(form.copy(transporterId = it.take(15))) }, m, error = errors["transporterId"], capitalization = KeyboardCapitalization.Characters) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            DocFieldRules.MODES.forEach { mode ->
                SelectableChip(mode, form.transportMode == mode, { onChange(form.copy(transportMode = if (form.transportMode == mode) "" else mode)) })
            }
        }
        pair(
            { m -> FormField("Vehicle no.", form.vehicleNumber, { onChange(form.copy(vehicleNumber = it.take(15))) }, m, error = errors["vehicle"], capitalization = KeyboardCapitalization.Characters, placeholder = "MH12AB1234") },
            { m -> FormField("Distance", form.distance, { onChange(form.copy(distance = it.filter(Char::isDigit).take(4))) }, m, error = errors["distance"], keyboardType = KeyboardType.Number, suffix = "km") },
        )
        pair(
            { m -> FormField("LR / doc no.", form.lrNumber, { onChange(form.copy(lrNumber = it)) }, m) },
            { m -> DateField("LR / doc date", form.lrDate, { onChange(form.copy(lrDate = it)) }, m, onClear = { onChange(form.copy(lrDate = null)) }) },
        )
    }
    toggle("Other details", "PO, e-way bill, e-commerce operator, your own fields", otherOpen || hasOther) { otherOpen = it }
    if (otherOpen || hasOther) {
        pair(
            { m -> FormField("PO no.", form.poNumber, { onChange(form.copy(poNumber = it)) }, m) },
            { m -> DateField("PO date", form.poDate, { onChange(form.copy(poDate = it)) }, m, onClear = { onChange(form.copy(poDate = null)) }) },
        )
        FormField("E-way bill no.", form.ewayBillNumber, { onChange(form.copy(ewayBillNumber = it.filter { c -> c.isDigit() || c == ' ' }.take(14))) }, error = errors["eway"], keyboardType = KeyboardType.Number, supporting = "Generated on the e-way bill portal; printed as text")
        if (DocFieldRules.supportsEcommerce(type)) {
            FormField("E-commerce operator GSTIN", form.ecommerceGstin, { onChange(form.copy(ecommerceGstin = it.take(15))) }, error = errors["eco"], capitalization = KeyboardCapitalization.Characters, supporting = "When sold through a marketplace")
        }
        form.custom.forEachIndexed { i, (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormField(label, value, { v -> onChange(form.copy(custom = form.custom.toMutableList().also { it[i] = label to v })) }, Modifier.weight(1f))
                IconButton(onClick = {
                    val next = form.custom.filterIndexed { j, _ -> j != i }
                    onChange(form.copy(custom = next))
                    onLabelsChange(next.map { it.first })
                }) { Icon(Icons.Filled.Close, contentDescription = "Remove field $label") }
            }
        }
        TextButton(onClick = { addLabel = "" }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add your own field")
        }
    }

    if (pickExport) {
        PickerSheet(
            title = "Export or SEZ supply",
            options = listOf<ExportType?>(null) + ExportType.entries,
            label = { it?.label ?: "No (regular supply)" },
            supporting = { if (it == null) null else "GSTR-1: ${if (it.sez) "B2B, ${it.code}" else "exports, ${it.code}"}" },
            selected = { it == form.exportType },
            key = { it?.code ?: "none" },
            onSelect = { onChange(form.copy(exportType = it)); pickExport = false },
            onDismiss = { pickExport = false },
        )
    }
    addLabel?.let { text ->
        val clash = form.custom.any { it.first.equals(text.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { addLabel = null },
            title = { Text("Add a field") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("The label is remembered for this business; fill its value on each document.")
                    FormField("Label", text, { addLabel = it.take(30) }, error = if (clash) "This field already exists" else null, placeholder = "e.g. Order ref")
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && !clash, onClick = {
                    val next = form.custom + (DocFieldRules.clean(text, 30) to "")
                    onChange(form.copy(custom = next))
                    onLabelsChange(next.map { it.first })
                    addLabel = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addLabel = null }) { Text("Cancel") } },
        )
    }
}
