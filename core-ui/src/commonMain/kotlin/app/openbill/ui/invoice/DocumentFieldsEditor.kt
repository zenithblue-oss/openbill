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

/** Optional header fields of a document as the user types them. */
data class DocFieldsForm(
    val reverseCharge: Boolean = false,
    val transporterName: String = "",
    val transporterId: String = "",
    val transportMode: String = "",
    val vehicleNumber: String = "",
    val lrNumber: String = "",
    val lrDate: LocalDate? = null,
    val distance: String = "",
    val exportType: ExportType? = null,
    val shippingBillNumber: String = "",
    val shippingBillDate: LocalDate? = null,
    val portCode: String = "",
    val lutReference: String = "",
    val currencyNote: String = "",
    /** Foreign-currency invoice: ISO code (blank for rupees) and rupees per unit. */
    val currency: String = "",
    val exchangeRate: String = "",
    val ecommerceGstin: String = "",
    val poNumber: String = "",
    val poDate: LocalDate? = null,
    val ewayBillNumber: String = "",
    /** Firm-defined labels in order, with what was typed for each (blank values are not saved). */
    val custom: List<Pair<String, String>> = emptyList(),
)

object DocFieldRules {
    const val MAX = 60
    val MODES = listOf("Road", "Rail", "Air", "Ship")

    fun supportsReverseCharge(type: VoucherType) = type in setOf(VoucherType.SALE, VoucherType.PURCHASE, VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE)
    fun supportsExport(type: VoucherType) = type in setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE, VoucherType.QUOTATION, VoucherType.PROFORMA, VoucherType.SALES_ORDER)
    fun supportsEcommerce(type: VoucherType) = type == VoucherType.SALE || type == VoucherType.CREDIT_NOTE

    /** Single line, trimmed, capped: these fields print in one PDF row. */
    fun clean(text: String, max: Int = MAX): String = text.replace('\n', ' ').replace('\t', ' ').trim().take(max)

    fun from(v: Voucher, labels: List<String>, keepShipping: Boolean = true): DocFieldsForm {
        val t = v.transport.takeIf { keepShipping }
        val values = v.customFields.associate { it.label to it.value }
        return DocFieldsForm(
            reverseCharge = v.reverseCharge,
            transporterName = t?.transporterName.orEmpty(),
            transporterId = t?.transporterId.orEmpty(),
            transportMode = t?.mode.orEmpty(),
            vehicleNumber = t?.vehicleNumber.orEmpty(),
            lrNumber = t?.docNumber.orEmpty(),
            lrDate = t?.docDate,
            distance = t?.distanceKm?.toString().orEmpty(),
            exportType = v.export?.type,
            shippingBillNumber = v.export?.shippingBillNumber.orEmpty(),
            shippingBillDate = v.export?.shippingBillDate,
            portCode = v.export?.portCode.orEmpty(),
            lutReference = v.export?.lutReference.orEmpty(),
            currencyNote = v.export?.currencyNote.orEmpty(),
            currency = v.export?.currency.orEmpty(),
            exchangeRate = v.export?.exchangeRate?.format().orEmpty(),
            ecommerceGstin = v.ecommerceGstin.orEmpty(),
            poNumber = v.poNumber.takeIf { keepShipping }.orEmpty(),
            poDate = v.poDate.takeIf { keepShipping },
            ewayBillNumber = v.ewayBillNumber.takeIf { keepShipping }.orEmpty(),
            custom = ((labels + values.keys).distinct()).map { it to (values[it].takeIf { keepShipping }.orEmpty()) },
        )
    }

    /** Errors keyed like the editor's other fields; [post] adds the checks only a posted document needs. */
    fun validate(f: DocFieldsForm, type: VoucherType, party: Party?, post: Boolean): Map<String, String> = buildMap {
        val gstin = f.ecommerceGstin.trim().uppercase()
        if (gstin.isNotEmpty() && !Gstin.isValid(gstin)) put("eco", "Enter a valid 15-character GSTIN")
        val tid = f.transporterId.trim().uppercase()
        if (tid.isNotEmpty() && (tid.length != 15 || !tid.all { it.isLetterOrDigit() })) put("transporterId", "Transporter ID is 15 letters and digits")
        val vehicle = f.vehicleNumber.filterNot { it == ' ' || it == '-' }
        if (vehicle.isNotEmpty() && (vehicle.length !in 4..15 || !vehicle.all { it.isLetterOrDigit() })) put("vehicle", "Use letters and digits, e.g. MH12AB1234")
        if (f.distance.isNotBlank() && f.distance.trim().toIntOrNull()?.takeIf { it in 0..9_999 } == null) put("distance", "Whole kilometres, 0 to 9999")
        val eway = f.ewayBillNumber.filterNot { it == ' ' }
        if (eway.isNotEmpty() && (eway.length != 12 || !eway.all(Char::isDigit))) put("eway", "E-way bill numbers are 12 digits")
        val port = f.portCode.trim()
        if (f.exportType != null && port.isNotEmpty() && (port.length != 6 || !port.all { it.isLetterOrDigit() })) put("port", "Port codes are 6 letters and digits, e.g. INBOM1")
        if (f.exportType != null && supportsExport(type)) {
            val code = f.currency.trim().uppercase()
            if (code.isNotEmpty() && (!ExportDetails.isCurrencyCode(code) || code == "INR")) put("currency", "Use a 3-letter code such as USD, or leave blank for rupees")
            if (code.isNotEmpty() && Parse.quantity(f.exchangeRate.trim())?.takeIf { it > Quantity.of(0) } == null) put("exchangeRate", "Enter the rupees one $code buys, e.g. 83.1050")
        }
        if (post && f.reverseCharge && type == VoucherType.SALE && party?.gstin?.trim()?.uppercase()?.let(Gstin::isValid) != true) {
            put("reverseCharge", "Reverse charge needs a registered customer (with a GSTIN)")
        }
        if (post && f.exportType?.sez == true && party?.gstin?.trim()?.uppercase()?.let(Gstin::isValid) != true) {
            put("export", "SEZ supplies need the SEZ unit's GSTIN on the party")
        }
    }

    fun transport(f: DocFieldsForm): TransportDetails? = TransportDetails(
        transporterName = clean(f.transporterName).ifEmpty { null },
        transporterId = clean(f.transporterId).uppercase().ifEmpty { null },
        mode = f.transportMode.ifEmpty { null },
        vehicleNumber = f.vehicleNumber.filterNot { it == ' ' || it == '-' }.uppercase().ifEmpty { null },
        docNumber = clean(f.lrNumber).ifEmpty { null },
        docDate = f.lrDate,
        distanceKm = f.distance.trim().toIntOrNull(),
    ).takeUnless { it.isEmpty() }

    fun export(f: DocFieldsForm): ExportDetails? = f.exportType?.let {
        val fx = exchange(f)
        ExportDetails(
            type = it,
            shippingBillNumber = clean(f.shippingBillNumber, 20).ifEmpty { null },
            shippingBillDate = f.shippingBillDate,
            portCode = clean(f.portCode, 6).uppercase().ifEmpty { null },
            lutReference = clean(f.lutReference).ifEmpty { null },
            currencyNote = clean(f.currencyNote, 80).ifEmpty { null },
            currency = fx?.first,
            exchangeRate = fx?.second,
        )
    }

    /** The foreign currency and rupees per unit of an export, or null for a rupee invoice (or while invalid). */
    fun exchange(f: DocFieldsForm): Pair<String, Quantity>? {
        if (f.exportType == null) return null
        val code = f.currency.trim().uppercase().takeIf { ExportDetails.isCurrencyCode(it) && it != "INR" } ?: return null
        val rate = Parse.quantity(f.exchangeRate.trim())?.takeIf { it > Quantity.of(0) } ?: return null
        return code to rate
    }

    fun custom(f: DocFieldsForm): List<DocumentField> = f.custom.mapNotNull { (label, value) ->
        val l = clean(label, 30)
        val v = clean(value)
        if (l.isEmpty() || v.isEmpty()) null else DocumentField(l, v)
    }

    fun ecommerceGstin(f: DocFieldsForm): String? = f.ecommerceGstin.trim().uppercase().ifEmpty { null }
}

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
