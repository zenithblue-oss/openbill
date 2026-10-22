package app.openbill.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.openbill.ui.theme.BrandLogo
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.openbill.core.tax.IndianState
import app.openbill.ui.AppContainer
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.theme.Spacing

/** Searchable state / union territory picker. */
@Composable
fun StatePickerSheet(selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    PickerSheet(
        title = "Choose state",
        options = IndianState.entries.toList(),
        label = { it.displayName },
        supporting = { "State code ${it.code}" },
        selected = { it.code == selected },
        key = { it.code },
        searchable = true,
        onSelect = { onPick(it.code) },
        onDismiss = onDismiss,
    )
}

/**
 * Business details fields shared by first-run setup and More > Edit business details.
 * [showFinancialYear] is false when editing, because the first year cannot change afterwards.
 */
@Composable
fun BusinessFields(
    form: SetupForm,
    edit: ((SetupForm) -> SetupForm) -> Unit,
    onPickState: () -> Unit,
    onPickYear: (() -> Unit)?,
    stateLockedReason: String? = null,
) {
    SectionHeader("Business")
    FormField("Business name *", form.name, { v -> edit { it.copy(name = v) } }, error = form.errors["name"], capitalization = KeyboardCapitalization.Words)
    FormField(
        "GSTIN (optional)",
        form.gstin,
        { v -> edit { it.copy(gstin = v.uppercase()) } },
        error = form.errors["gstin"],
        capitalization = KeyboardCapitalization.Characters,
        supporting = "Leave blank if you are not GST registered",
    )
    PickerField(
        "State *",
        form.stateCode?.let { Fmt.stateName(it) } ?: "",
        if (stateLockedReason == null) onPickState else ({}),
        error = form.errors["state"],
        supporting = stateLockedReason,
        placeholder = "Choose a state",
    )
    FormField("Address *", form.addressLine1, { v -> edit { it.copy(addressLine1 = v) } }, error = form.errors["address"])
    FormField("City *", form.city, { v -> edit { it.copy(city = v) } }, error = form.errors["city"], capitalization = KeyboardCapitalization.Words)
    FormField("PIN code", form.pincode, { v -> edit { it.copy(pincode = v.filter(Char::isDigit).take(6)) } }, keyboardType = KeyboardType.Number)
    FormField("Phone", form.phone, { v -> edit { it.copy(phone = v) } }, error = form.errors["phone"], keyboardType = KeyboardType.Phone)

    SectionHeader("Invoicing")
    FormField(
        "Invoice number prefix",
        form.invoicePrefix,
        { v -> edit { it.copy(invoicePrefix = v.uppercase()) } },
        error = form.errors["prefix"],
        capitalization = KeyboardCapitalization.Characters,
        supporting = "Invoices will be numbered like ${form.invoicePrefix.ifBlank { "INV" }}-0001",
        imeAction = ImeAction.Done,
    )
    if (onPickYear != null) {
        PickerField("Financial year", Fmt.financialYearLabel(form.fyStartYear) + " (April to March)", onPickYear)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val model = remember { FirmSetupModel(container, scope) }
    val form by model.state.collectAsState()
    var pickState by remember { mutableStateOf(false) }
    var pickYear by remember { mutableStateOf(false) }
    val currentFy = remember { FirmSetupRules.fyStartYearFor(container.today()) }
    val scroll = rememberScrollState()
    LaunchedEffect(form.rejectedSubmits) { if (form.rejectedSubmits > 0) scroll.animateScrollTo(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Set up your business") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            FormColumn(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = pageGutter)) {
                Image(rememberVectorPainter(BrandLogo.Mark), contentDescription = "OpenBill logo", modifier = Modifier.size(56.dp))
                Text(
                    "Your data stays on this computer or phone; no account or internet connection is needed. Check these details carefully: they are printed on every invoice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BusinessFields(form, model::edit, onPickState = { pickState = true }, onPickYear = { pickYear = true })
                form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("Start billing", model::save, Modifier.fillMaxWidth(), busy = form.saving, busyText = "Setting up…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    if (pickState) {
        StatePickerSheet(form.stateCode, onPick = { code -> model.edit { it.copy(stateCode = code) }; pickState = false }, onDismiss = { pickState = false })
    }
    if (pickYear) {
        PickerSheet(
            title = "Financial year",
            options = listOf(currentFy, currentFy - 1),
            label = { Fmt.financialYearLabel(it) },
            supporting = { "1 April $it to 31 March ${it + 1}" },
            selected = { it == form.fyStartYear },
            searchable = false,
            onSelect = { y -> model.edit { it.copy(fyStartYear = y) }; pickYear = false },
            onDismiss = { pickYear = false },
        )
    }
}
