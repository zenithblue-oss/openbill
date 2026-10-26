package app.openbill.ui.invoice

import kotlinx.coroutines.launch

import app.openbill.ui.barcode.BarcodeScanButton

import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.Batch
import app.openbill.core.model.Quantity
import app.openbill.ui.inventory.Inventory
import app.openbill.ui.components.SelectableChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Party
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.tax.TaxMode
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AmountRow
import app.openbill.ui.components.AnimatedMoney
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.DateField
import app.openbill.ui.components.DiscardChangesDialog
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.FullScreenEditor
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.LocalWidthClass
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.items.GstRateSheet
import app.openbill.ui.items.gstSlabsOn
import app.openbill.ui.nav.WidthClass
import app.openbill.ui.parties.QuickAddPartyEditor
import app.openbill.ui.parties.summaryLine
import app.openbill.ui.setup.StatePickerSheet
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween

@Composable
fun InvoiceEditorScreen(
    container: AppContainer,
    session: Session,
    voucherId: String?,
    onClose: () -> Unit,
    onFinished: (voucherId: String, posted: Boolean) -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
    newType: VoucherType = VoucherType.SALE,
    returnOf: String? = null,
) {
    val scope = rememberCoroutineScope()
    val model = remember(voucherId) { InvoiceEditorModel(container, session, voucherId, scope, newType, returnOf) }
    val state by model.state.collectAsState()
    val doc = docLabels(state.type)
    val purchaseSide = state.type == VoucherType.PURCHASE || state.type == VoucherType.DEBIT_NOTE
    val godowns by remember { container.repositories.catalog.observeGodowns(session.firm.id) }.collectAsState(initial = emptyList())
    var pickGodown by remember { mutableStateOf(false) }
    var scanText by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val parties by remember { container.repositories.parties.observeParties(session.firm.id) }.collectAsState(initial = emptyList())
    val items by remember { container.repositories.catalog.observeItems(session.firm.id) }.collectAsState(initial = emptyList())
    val units by remember { container.repositories.catalog.observeUnits(session.firm.id) }.collectAsState(initial = emptyList())
    val unitSymbol = units.associate { it.id to it.symbol }
    val messages = LocalAppMessages.current

    var pickParty by remember { mutableStateOf(false) }
    var pickItem by remember { mutableStateOf(false) }
    var pickPos by remember { mutableStateOf(false) }
    var quickAddName by remember { mutableStateOf<String?>(null) }
    var lineForm by remember { mutableStateOf<LineForm?>(null) }
    var chargeForm by remember { mutableStateOf<ChargeForm?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var totalsOpen by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (state.dirty && !state.saving) confirmDiscard = true else onClose() }
    backHandler(state.dirty && !state.saving) { confirmDiscard = true }
    val scroll = rememberScrollState()
    LaunchedEffect(state.rejectedSaves) {
        // Party, date and item errors are all near the top of the form.
        if (state.rejectedSaves > 0) scroll.animateScrollTo(0)
    }
    LaunchedEffect(state.failure) {
        // Save/post failures render below the notes, off-screen on most windows.
        if (state.failure != null) scroll.animateScrollTo(scroll.maxValue)
    }
    val finished: (String, Boolean) -> Unit = { id, posted ->
        messages?.show(if (posted) "${doc.title} ${if (state.type.isIssueOnly()) "issued" else "posted"}" else "Draft saved")
        onFinished(id, posted)
    }
    val wide = LocalWidthClass.current != WidthClass.COMPACT

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { AppTopBar(if (voucherId == null) "New ${doc.noun}" else "Edit draft", onBack = requestClose) },
            bottomBar = {
                if (state.loaded) {
                    TotalsBar(
                        state = state,
                        open = totalsOpen,
                        onToggle = { totalsOpen = !totalsOpen },
                        canSave = !state.saving && model.canSave(state),
                        onSaveDraft = { model.save(post = false, onDone = finished) },
                        onPost = { model.save(post = true, onDone = finished) },
                    )
                }
            },
        ) { padding ->
            if (!state.loaded) {
                LoadingState(Modifier.padding(padding))
                return@Scaffold
            }
            CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
                FormColumn(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                    PartyCard(state.party, state.errors["party"], if (purchaseSide) "Supplier *" else "Bill to *") { pickParty = true }
                    if (purchaseSide) {
                        FormField(
                            if (state.type == VoucherType.PURCHASE) "Supplier bill number" else "Debit note against",
                            state.referenceNumber,
                            model::setReferenceNumber,
                            supporting = if (state.type == VoucherType.PURCHASE) "As printed on the supplier's invoice" else null,
                        )
                    }
                    if (godowns.size > 1 && !state.type.isIssueOnly()) {
                        PickerField(
                            "Godown",
                            godowns.firstOrNull { it.id == state.godownId }?.name.orEmpty(),
                            { pickGodown = true },
                            supporting = if (purchaseSide && state.type == VoucherType.PURCHASE) "Stock is received here" else "Stock moves from here",
                        )
                    }

                    val date = Parse.date(state.dateText)
                    val supplyNote = if (state.placeOfSupply == session.firm.stateCode) "Intra-state: CGST + SGST" else "Inter-state: IGST"
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            DateField("${doc.title} date", date, { model.setDate(it.toString()) }, Modifier.weight(1f), error = state.errors["date"])
                            PickerField("Place of supply", Fmt.stateName(state.placeOfSupply), { pickPos = true }, Modifier.weight(1f), supporting = supplyNote)
                        }
                    } else {
                        // Stacked on phones so a long state name never wraps inside a half-width field.
                        DateField("${doc.title} date", date, { model.setDate(it.toString()) }, error = state.errors["date"])
                        PickerField("Place of supply", Fmt.stateName(state.placeOfSupply), { pickPos = true }, supporting = supplyNote)
                    }

                    SectionHeader(if (state.lines.isEmpty()) "Items" else "Items (${state.lines.size})")
                    Column(Modifier.animateContentSize(motionTween(Motion.MEDIUM1, Motion.Emphasized)), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (state.lines.isEmpty()) {
                            Text(
                                "No items yet. Add one from your item list, or type a custom line.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.lines.forEachIndexed { index, line ->
                            val result = state.summary?.lineResults?.getOrNull(index)
                            DraftLineCard(line, result?.totalAmount) { lineForm = LineRules.fromDraft(line, items.firstOrNull { it.id == line.itemId }) }
                        }
                    }
                    state.errors["lines"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.calcError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilledTonalButton(onClick = { pickItem = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Add item")
                        }
                        OutlinedButton(onClick = { lineForm = LineForm() }) { Text("Custom line") }
                    }
                    // Barcode entry: HID scanners type the code and press Enter here; on Android the
                    // camera button scans with ZXing.
                    val lookup: (String) -> Unit = { code ->
                        scope.launch {
                            val item = container.repositories.catalog.findItemByBarcode(session.firm.id, code)
                            if (item == null) {
                                scanError = "No item has barcode \"${code.trim()}\". Add it to an item first."
                            } else {
                                scanError = null
                                scanText = ""
                                lineForm = LineRules.fromItem(item, unitSymbol[item.unitId], purchaseSide)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormField(
                            "Scan or type barcode",
                            scanText,
                            { scanText = it; scanError = null },
                            Modifier.weight(1f),
                            error = scanError,
                            imeAction = ImeAction.Done,
                            onImeAction = { if (scanText.isNotBlank()) lookup(scanText) },
                        )
                        BarcodeScanButton(onScanned = lookup)
                    }

                    ChargesSection(state, onAdd = { chargeForm = ChargeForm() }, onEdit = { chargeForm = ChargeRules.fromDraft(it) })

                    SectionHeader("Notes")
                    FormField("Notes / terms (printed on ${doc.noun})", state.notes, model::setNotes, singleLine = false)
                    state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "The ${doc.noun} number is assigned when you ${if (state.type.isIssueOnly()) "issue" else "post"}." +
                            when {
                                state.type.isIssueOnly() -> " It does not affect stock or accounts."
                                state.type != VoucherType.SALE || godowns.isNotEmpty() -> " Posting updates stock."
                                else -> ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }

        // Full-screen line editor drawn over the form; Done is in its top bar, never under the keyboard.
        // Outward lines pick an existing batch, first-expiring first, instead of typing one.
        val batchItemId = lineForm?.takeIf { it.trackBatch && (state.type == VoucherType.SALE || state.type == VoucherType.DEBIT_NOTE) }?.itemId
        val batches by produceState(emptyList<Pair<Batch, Quantity>>(), batchItemId, state.godownId) {
            value = if (batchItemId == null) emptyList() else runCatching {
                val repos = container.repositories
                Inventory.fefo(repos.stock.observeBatches(batchItemId).first(), repos.stock.observeItemMovements(session.firm.id, batchItemId).first(), state.godownId)
            }.getOrDefault(emptyList())
        }
        LineEditor(
            form = lineForm,
            batches = batches,
            slabs = remember { gstSlabsOn(container.today()) },
            preview = { line -> runCatching { model.previewTotal(line) }.getOrNull() },
            onSave = { form2 ->
                val (line, errors) = LineRules.validate(form2, model::newKey)
                if (line != null) {
                    model.upsertLine(line)
                    lineForm = null
                }
                errors
            },
            onDelete = { key ->
                model.removeLine(key)
                lineForm = null
            },
            onDismiss = { lineForm = null },
        )
        ChargeEditor(
            form = chargeForm,
            slabs = remember { gstSlabsOn(container.today()) },
            onSave = { f ->
                val (charge, errors) = ChargeRules.validate(f, model::newKey)
                if (charge != null) {
                    model.upsertCharge(charge)
                    chargeForm = null
                }
                errors
            },
            onDelete = { key ->
                model.removeCharge(key)
                chargeForm = null
            },
            onDismiss = { chargeForm = null },
        )
        QuickAddPartyEditor(
            container = container,
            session = session,
            visible = quickAddName != null,
            initialName = quickAddName.orEmpty(),
            onClose = { quickAddName = null },
            onCreated = { party ->
                model.setParty(party)
                quickAddName = null
                messages?.show("Added ${party.name}")
            },
        )
    }
    // Registered after the screen's own handler so an open overlay closes first.
    backHandler(lineForm != null) { lineForm = null }
    backHandler(chargeForm != null) { chargeForm = null }
    backHandler(quickAddName != null) { quickAddName = null }

    if (confirmDiscard) {
        DiscardChangesDialog(
            message = "Your changes to this ${doc.noun} have not been saved.",
            onDiscard = { confirmDiscard = false; onClose() },
            onKeepEditing = { confirmDiscard = false },
            onSave = { confirmDiscard = false; model.save(post = false, onDone = finished) },
            saveLabel = "Save draft",
        )
    }
    if (pickParty) {
        PickerSheet(
            title = "Bill to",
            options = parties,
            label = { it.name },
            supporting = { it.summaryLine() },
            selected = { it.id == state.party?.id },
            key = { it.id },
            searchable = true,
            emptyText = "No parties yet. Add one above.",
            onSelect = { model.setParty(it); pickParty = false },
            onDismiss = { pickParty = false },
            header = {
                ListItem(
                    headlineContent = { Text("New party") },
                    supportingContent = { Text("Add a customer without leaving the invoice") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable(role = Role.Button) {
                        pickParty = false
                        quickAddName = ""
                    },
                )
            },
        )
    }
    if (pickItem) {
        PickerSheet(
            title = "Add item",
            options = items,
            label = { it.name },
            supporting = { "${Fmt.inr(it.salePrice)} · GST ${Fmt.percent(it.taxRate)}%" + (it.hsnSac?.let { h -> " · HSN $h" } ?: "") },
            key = { it.id },
            searchable = true,
            emptyText = "No items yet. Add them from the Items tab, or use a custom line.",
            onSelect = { item ->
                pickItem = false
                lineForm = LineRules.fromItem(item, unitSymbol[item.unitId], purchaseSide)
            },
            onDismiss = { pickItem = false },
        )
    }
    if (pickGodown) {
        PickerSheet(
            title = "Godown",
            options = godowns,
            label = { it.name },
            selected = { it.id == state.godownId },
            key = { it.id },
            onSelect = { model.setGodown(it.id); pickGodown = false },
            onDismiss = { pickGodown = false },
        )
    }
    if (pickPos) {
        StatePickerSheet(state.placeOfSupply, onPick = { model.setPlaceOfSupply(it); pickPos = false }, onDismiss = { pickPos = false })
    }
}

/** The chosen party with its GSTIN and state, or a prompt to choose one. */
@Composable
private fun PartyCard(party: Party?, error: String?, label: String, onClick: () -> Unit) {
    val borderColor = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(
        onClick = onClick,
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            overlineContent = { Text(label) },
            headlineContent = {
                Text(party?.name ?: "Choose a party", maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                if (party != null) {
                    Column {
                        Text(party.gstin?.let { "GSTIN $it" } ?: "Unregistered (no GSTIN)")
                        party.stateCode?.let { Text(Fmt.stateName(it)) }
                    }
                } else {
                    Text(error ?: "Customer or supplier this invoice is for")
                }
            },
            leadingContent = { Icon(Icons.Outlined.Person, contentDescription = null) },
            trailingContent = { if (party != null) Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                supportingColor = if (error != null && party == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun DraftLineCard(line: DraftLine, total: Money?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
                Text(line.description, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                AnimatedMoney(total, MaterialTheme.typography.titleSmall)
            }
            Text(
                "${Fmt.qtyUnit(line.quantity, line.unit)} × ${Fmt.inr(line.rate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildList {
                    if (line.discount.paise != 0L) {
                        add("Discount " + (line.discountPercent?.let { "${Fmt.percent(it)}% " } ?: "") + Fmt.inrDeduction(line.discount))
                    }
                    add("GST ${Fmt.percent(line.gstRate)}%" + if (line.taxMode == TaxMode.INCLUSIVE) " incl." else "")
                    line.hsnSac?.let { add("HSN $it") }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sticky footer: grand total that rolls on change, an expandable tax breakdown and the save actions. */
@Composable
private fun TotalsBar(
    state: InvoiceEditorState,
    open: Boolean,
    onToggle: () -> Unit,
    canSave: Boolean,
    onSaveDraft: () -> Unit,
    onPost: () -> Unit,
) {
    val s = state.summary
    val reduced = LocalReducedMotion.current
    val rotation by animateFloatAsState(if (open) 180f else 0f, motionTween(reduced, Motion.SHORT4), label = "chevron")
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = pageGutter, vertical = Spacing.sm)
                .animateContentSize(motionTween(Motion.MEDIUM1, Motion.Emphasized)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = open, role = Role.Button, onValueChange = { onToggle() })
                    .semantics { stateDescription = if (open) "Breakdown shown" else "Breakdown hidden" }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                    if (s != null) {
                        Text(
                            "incl. GST ${Fmt.inr(s.totalTax)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedMoney(s?.grandTotal, MaterialTheme.typography.headlineSmall)
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (open) "Hide tax breakdown" else "Show tax breakdown",
                    modifier = Modifier.padding(start = Spacing.xs).rotate(rotation),
                )
            }
            if (open && s != null) {
                Column(Modifier.padding(bottom = Spacing.xs)) {
                    AmountRow(s.grossLabel, s.gross)
                    if (s.discount.paise != 0L) AmountRow("Discount", s.discount, negative = true)
                    AmountRow(if (s.chargeResults.isEmpty()) "Taxable value" else "Items taxable value", s.taxable)
                    state.charges.forEachIndexed { i, c ->
                        s.chargeResults.getOrNull(i)?.let { AmountRow(c.name, it.result.totalAmount - it.result.totalTax) }
                    }
                    if (s.isIntraState) {
                        AmountRow("CGST", s.cgst)
                        AmountRow("SGST", s.sgst)
                    } else {
                        AmountRow("IGST", s.igst)
                    }
                    if (s.cess.paise != 0L) AmountRow("Cess", s.cess)
                    if (s.roundOff.paise != 0L) AmountRow("Round off", Fmt.inrSigned(s.roundOff))
                    HorizontalDivider(Modifier.padding(top = Spacing.xs))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onSaveDraft, enabled = canSave, modifier = Modifier.weight(1f)) {
                    Text("Save draft", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PrimaryButton(
                    if (state.type.isIssueOnly()) "Save & issue" else "Save & post",
                    onPost,
                    Modifier.weight(1f),
                    enabled = canSave || state.saving,
                    busy = state.saving,
                    busyText = "Saving…",
                )
            }
        }
    }
}

@Composable
private fun LineEditor(
    form: LineForm?,
    batches: List<Pair<Batch, Quantity>>,
    slabs: List<Rate>,
    preview: (LineForm) -> Money?,
    onSave: (LineForm) -> Map<String, String>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // While the editor animates out (form == null) it keeps showing the last edited values.
    val holder = remember { object { var last = LineForm() } }
    var edit by remember(form) { mutableStateOf(form ?: holder.last) }
    var errors by remember(form) { mutableStateOf(emptyMap<String, String>()) }
    var pickRate by remember { mutableStateOf(false) }
    if (form != null) holder.last = edit
    val current = edit
    FullScreenEditor(
        visible = form != null,
        title = if (current.key == null) "Add line" else "Edit line",
        confirmLabel = "Done",
        onConfirm = { errors = onSave(edit) },
        onClose = onDismiss,
    ) {
        FormColumn {
            FormField("Description *", current.description, { edit = edit.copy(description = it) }, error = errors["description"])
            FormField(
                "HSN/SAC",
                current.hsnSac.orEmpty(),
                { edit = edit.copy(hsnSac = it.filter(Char::isDigit).take(8)) },
                error = errors["hsn"],
                keyboardType = KeyboardType.Number,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FormField(
                    "Quantity",
                    current.quantity,
                    { edit = edit.copy(quantity = it) },
                    error = errors["quantity"],
                    keyboardType = KeyboardType.Decimal,
                    suffix = current.unit,
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    "Rate",
                    current.rate,
                    { edit = edit.copy(rate = it) },
                    error = errors["rate"],
                    keyboardType = KeyboardType.Decimal,
                    prefix = "₹",
                    modifier = Modifier.weight(1f),
                )
            }
            FormField(
                "Discount",
                current.discount,
                { edit = edit.copy(discount = it) },
                error = errors["discount"],
                supporting = "Amount, or a percentage like 10%",
                imeAction = ImeAction.Done,
                onImeAction = { errors = onSave(edit) },
            )
            PickerField("GST rate", "${Fmt.percent(current.gstRate)}%", { pickRate = true })
            LaunchedEffect(batches) {
                val first = batches.firstOrNull()?.first
                if (first != null && edit.batchNumber.isBlank()) edit = edit.copy(batchNumber = first.batchNumber, expiryDate = first.expiryDate)
            }
            if (current.trackBatch && batches.isNotEmpty()) {
                Text("Batch (first to expire first)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    batches.forEach { (b, qty) ->
                        val label = listOfNotNull(b.batchNumber, b.expiryDate?.let { "exp ${Fmt.date(it)}" }, "${Fmt.qty(qty)} left").joinToString(" · ")
                        SelectableChip(label, current.batchNumber == b.batchNumber, { edit = edit.copy(batchNumber = b.batchNumber, expiryDate = b.expiryDate) })
                    }
                }
                errors["batch"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else if (current.trackBatch) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FormField("Batch number *", current.batchNumber, { edit = edit.copy(batchNumber = it) }, Modifier.weight(1f), error = errors["batch"])
                    DateField("Expiry", current.expiryDate, { edit = edit.copy(expiryDate = it) }, Modifier.weight(1f))
                }
            }
            if (current.trackSerial) {
                FormField(
                    "Serial / IMEI numbers *",
                    current.serials,
                    { edit = edit.copy(serials = it) },
                    error = errors["serials"],
                    singleLine = false,
                    supporting = "One per unit, on separate lines or comma-separated (${LineRules.serialList(current.serials).size} entered)",
                )
                BarcodeScanButton(onScanned = { code -> edit = edit.copy(serials = (edit.serials.trim() + "\n" + code).trim()) })
            }
            ListItem(
                headlineContent = { Text("Rate includes GST") },
                supportingContent = { Text("Turn on when the rate above already has GST in it") },
                trailingContent = { Switch(checked = current.inclusive, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.toggleable(value = current.inclusive, role = Role.Switch) { edit = edit.copy(inclusive = it) },
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text("Line total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AnimatedMoney(preview(current), MaterialTheme.typography.titleLarge)
                }
            }
            val key = current.key
            if (key != null) {
                TextButton(onClick = { onDelete(key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Remove line")
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
    if (pickRate) {
        GstRateSheet(slabs, current.gstRate, { r -> edit = edit.copy(gstRate = r); pickRate = false }, { pickRate = false })
    }
}

/** Wording for each document the editor can make. */
internal data class DocLabels(val title: String, val noun: String)

internal fun docLabels(type: VoucherType): DocLabels = when (type) {
    VoucherType.PURCHASE -> DocLabels("Purchase bill", "purchase bill")
    VoucherType.DEBIT_NOTE -> DocLabels("Purchase return", "purchase return")
    VoucherType.CREDIT_NOTE -> DocLabels("Sales return", "sales return")
    VoucherType.QUOTATION -> DocLabels("Quotation", "quotation")
    VoucherType.PROFORMA -> DocLabels("Proforma invoice", "proforma invoice")
    VoucherType.DELIVERY_CHALLAN -> DocLabels("Delivery challan", "delivery challan")
    else -> DocLabels("Invoice", "invoice")
}

/** Non-posting documents: numbered when issued, no stock or ledger effect. */
internal fun VoucherType.isIssueOnly(): Boolean =
    this == VoucherType.QUOTATION || this == VoucherType.PROFORMA || this == VoucherType.DELIVERY_CHALLAN
