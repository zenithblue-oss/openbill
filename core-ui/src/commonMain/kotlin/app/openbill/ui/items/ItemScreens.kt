package app.openbill.ui.items

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.usecase.StockLineInput
import app.openbill.core.domain.usecase.StockVouchers
import app.openbill.ui.barcode.BarcodeScanButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.inventory.Inventory
import app.openbill.ui.inventory.SwitchRow
import app.openbill.ui.inventory.StockDashboardCard
import androidx.compose.material3.OutlinedButton
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.tax.GstRateSchedules
import app.openbill.ui.AppContainer
import app.openbill.ui.components.LocalWidthClass
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppSearchBar
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.CollapsingTopBar
import app.openbill.ui.components.DiscardChangesDialog
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.forms.Validation
import app.openbill.ui.invoice.Monogram
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

fun Item.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, true) || (hsnSac?.contains(q) ?: false) || (sku?.contains(q, true) ?: false) ||
        (barcode?.contains(q, true) ?: false)
}

/** Selectable GST slabs on a date, from the versioned schedule in core-tax. */
fun gstSlabsOn(date: LocalDate): List<Rate> =
    GstRateSchedules.standard.ratesOn(date).ifEmpty { listOf(Rate.ofPercent(0)) }

/** GST slab picker sheet shared by the item form and the invoice line editor. */
@Composable
fun GstRateSheet(slabs: List<Rate>, selected: Rate, onPick: (Rate) -> Unit, onDismiss: () -> Unit) {
    PickerSheet(
        title = "GST rate",
        options = (slabs + selected).distinct().sorted(),
        label = { "${Fmt.percent(it)}%" },
        supporting = { if (it == Rate.ofPercent(0)) "Nil-rated or exempt" else null },
        selected = { it == selected },
        searchable = false,
        onSelect = onPick,
        onDismiss = onDismiss,
    )
}

/** Unit picker sheet shared by the item form. */
@Composable
fun UnitSheet(units: List<UnitOfMeasure>, selectedId: String?, onPick: (UnitOfMeasure) -> Unit, onDismiss: () -> Unit) {
    PickerSheet(
        title = "Unit",
        options = units,
        label = { "${it.name} (${it.symbol})" },
        selected = { it.id == selectedId },
        key = { it.id },
        onSelect = onPick,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemListScreen(
    container: AppContainer,
    session: Session,
    onOpen: (String?) -> Unit,
    onStock: (lowOnly: Boolean) -> Unit = {},
) {
    val firmId = session.firm.id
    val items by remember(firmId) { container.repositories.catalog.observeItems(firmId) }.collectAsState(initial = null)
    val units by remember(firmId) { container.repositories.catalog.observeUnits(firmId) }.collectAsState(initial = emptyList())
    val unitById = units.associateBy { it.id }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(0) } // 0 all, 1 goods, 2 services
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CollapsingTopBar("Items", scrollBehavior) },
        snackbarHost = { AppSnackbarHost() },
        floatingActionButton = {
            // With a navigation rail, its header FAB is the screen's one create action.
            if (!LocalWidthClass.current.usesRail) ExtendedFloatingActionButton(
                onClick = { onOpen(null) },
                expanded = !listState.lastScrolledForward || !listState.canScrollBackward,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New item") },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            val list = items
            if (list == null) {
                LoadingState()
                return@CenteredContent
            }
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Outlined.ShoppingCart,
                    "No items yet",
                    "Add the goods and services you sell, with their HSN/SAC and GST rate.",
                    Modifier.align(Alignment.Center),
                )
                return@CenteredContent
            }
            val filtered = list.filter {
                it.matches(query) && when (kind) {
                    1 -> !it.isService
                    2 -> it.isService
                    else -> true
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Spacing.fabClearance)) {
                item(key = "stock") { StockDashboardCard(container, session, onStock) }
                item(key = "search") { AppSearchBar(query, { query = it }, "Search name, HSN/SAC or barcode") }
                item(key = "filters") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        listOf("All", "Goods", "Services").forEachIndexed { i, label -> SelectableChip(label, kind == i, { kind = i }) }
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") { EmptyState(Icons.Outlined.Search, "No match", "No item matches your search or filter.") }
                }
                items(filtered, key = { it.id }) { item ->
                    ListItem(
                        leadingContent = { Monogram(item.name) },
                        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    item.hsnSac?.let { (if (item.isService) "SAC " else "HSN ") + it },
                                    "GST ${Fmt.percent(item.taxRate)}%",
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Text(
                                Fmt.inr(item.salePrice) + (unitById[item.unitId]?.let { " / ${it.symbol}" } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = if (reduced) null else motionTween(false, Motion.SHORT4),
                                placementSpec = if (reduced) null else motionTween(false, Motion.MEDIUM1, Motion.Emphasized),
                                fadeOutSpec = if (reduced) null else motionTween(false, Motion.SHORT3),
                            )
                            .clickable(onClickLabel = "Edit item") { onOpen(item.id) },
                    )
                }
            }
        }
    }
}

data class ItemForm(
    val loaded: Boolean = false,
    val name: String = "",
    val isService: Boolean = false,
    val unitId: String? = null,
    val hsnSac: String = "",
    val gstRate: Rate = Rate.ofPercent(18),
    val salePrice: String = "",
    val purchasePrice: String = "",
    val trackInventory: Boolean = false,
    val trackBatch: Boolean = false,
    val trackSerial: Boolean = false,
    val lowStock: String = "",
    val barcode: String = "",
    /** Optional second unit and how many primary units one of it holds ("12" for a box of 12). */
    val altUnitId: String? = null,
    val altFactor: String = "",
    /** Opening stock, recorded as a posted adjustment when a new tracked item is saved. */
    val openingQty: String = "",
    val openingCost: String = "",
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val failure: String? = null,
    /** Bumped on each rejected save so the screen can scroll the first error into view. */
    val rejectedSaves: Int = 0,
)

object ItemRules {
    /** The user-editable part of the form, for "unsaved changes" checks. */
    fun content(form: ItemForm): ItemForm =
        form.copy(loaded = true, errors = emptyMap(), saving = false, failure = null, rejectedSaves = 0)

    fun hasChanges(baseline: ItemForm?, current: ItemForm): Boolean =
        baseline != null && content(baseline) != content(current)

    fun validate(form: ItemForm): Map<String, String> = buildMap {
        if (form.name.isBlank()) put("name", "Name is required")
        if (form.unitId == null) put("unit", "Choose a unit")
        Validation.hsnError(form.hsnSac)?.let { put("hsn", it) }
        Validation.moneyError(form.salePrice)?.let { put("sale", it) }
        Validation.moneyError(form.purchasePrice)?.let { put("purchase", it) }
        if (form.lowStock.isNotBlank() && Parse.quantity(form.lowStock) == null) put("low", "Enter a quantity")
        if (form.openingQty.isNotBlank() && (Parse.quantity(form.openingQty)?.let { it.scaledUnits < 0 } != false)) put("opening", "Enter a quantity")
        Validation.moneyError(form.openingCost)?.let { put("openingCost", it) }
        // The opening adjustment needs batch or serial numbers it cannot collect; failing after the item is saved would duplicate it on retry.
        if (form.openingQty.isNotBlank() && form.trackInventory && !form.isService && (form.trackBatch || form.trackSerial) && "opening" !in this)
            put("opening", "Batch and serial items get stock from a purchase, not an opening quantity")
        if (form.barcode.isNotBlank() && form.barcode.any { it.code !in 32..126 }) put("barcode", "Use letters, digits and symbols only")
        if (form.trackInventory && !form.isService && form.trackBatch && form.trackSerial) put("serial", "Use batches or serial numbers, not both")
        if (form.altUnitId != null) {
            if (form.altUnitId == form.unitId) put("altUnit", "Choose a different unit")
            if (Parse.quantity(form.altFactor)?.let { it.scaledUnits > 0 } != true) put("altFactor", "Enter how many units it holds")
        }
    }
}

class ItemEditorModel(
    private val container: AppContainer,
    private val firmId: String,
    private val itemId: String?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ItemForm())
    val state: StateFlow<ItemForm> = _state
    private var existing: Item? = null

    /** The form as loaded; compared against to detect unsaved edits. */
    var baseline: ItemForm? = null
        private set

    init {
        scope.launch {
            val units = container.repositories.catalog.observeUnits(firmId).first()
            val defaultUnit = units.firstOrNull { it.symbol == "NOS" }?.id ?: units.firstOrNull()?.id
            if (itemId == null) {
                _state.value = ItemForm(loaded = true, unitId = defaultUnit)
            } else {
                val item = container.repositories.catalog.findItem(itemId)
                existing = item
                _state.value = if (item == null) {
                    ItemForm(loaded = true, failure = "Item not found")
                } else {
                    ItemForm(
                        loaded = true,
                        name = item.name,
                        isService = item.isService,
                        unitId = item.unitId,
                        hsnSac = item.hsnSac.orEmpty(),
                        gstRate = item.taxRate,
                        salePrice = if (item.salePrice.paise == 0L) "" else item.salePrice.format(),
                        purchasePrice = if (item.purchasePrice.paise == 0L) "" else item.purchasePrice.format(),
                        trackInventory = item.trackInventory,
                        trackBatch = item.trackBatch,
                        trackSerial = item.trackSerial,
                        lowStock = item.lowStockLevel?.format().orEmpty(),
                        barcode = item.barcode.orEmpty(),
                        altUnitId = item.alternateUnitId,
                        altFactor = item.alternateUnitFactor?.format().orEmpty(),
                    )
                }
            }
            baseline = _state.value
        }
    }

    fun edit(transform: (ItemForm) -> ItemForm) {
        _state.update { old ->
            val next = transform(old)
            next.copy(errors = if (old.errors.isEmpty()) emptyMap() else ItemRules.validate(next))
        }
    }

    /** Moves the item to the recycle bin; [onDone] gets the item as it was, for undo. */
    fun archive(onDone: (Item?) -> Unit) {
        val id = itemId ?: return
        _state.update { it.copy(saving = true, failure = null) }
        scope.launch {
            runCatching { container.repositories.catalog.archiveItem(id, container.now()) }
                .onSuccess { onDone(existing) }
                .onFailure { e -> _state.update { it.copy(saving = false, failure = e.message ?: "Could not remove") } }
        }
    }

    fun save(onSaved: (Item) -> Unit) {
        val form = _state.value
        val errors = ItemRules.validate(form)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, rejectedSaves = it.rejectedSaves + 1) }
            return
        }
        _state.update { it.copy(saving = true, failure = null) }
        scope.launch {
            runCatching {
                val now = container.now()
                val old = existing
                val item = (old ?: Item(
                    id = container.newId(),
                    firmId = firmId,
                    name = form.name.trim(),
                    unitId = form.unitId!!,
                    createdAt = now,
                    updatedAt = now,
                )).copy(
                    name = form.name.trim(),
                    unitId = form.unitId!!,
                    isService = form.isService,
                    hsnSac = form.hsnSac.trim().ifBlank { null },
                    taxRate = form.gstRate,
                    salePrice = Parse.money(form.salePrice) ?: Money.ZERO,
                    purchasePrice = Parse.money(form.purchasePrice) ?: Money.ZERO,
                    trackInventory = form.trackInventory && !form.isService,
                    trackBatch = form.trackBatch && form.trackInventory && !form.isService,
                    trackSerial = form.trackSerial && form.trackInventory && !form.isService,
                    lowStockLevel = Parse.quantity(form.lowStock).takeIf { form.lowStock.isNotBlank() },
                    barcode = form.barcode.trim().ifBlank { null },
                    alternateUnitId = form.altUnitId,
                    alternateUnitFactor = form.altUnitId?.let { Parse.quantity(form.altFactor) },
                    updatedAt = now,
                )
                container.repositories.catalog.saveItem(item)
                val opening = Parse.quantity(form.openingQty)
                if (old == null && item.trackInventory && opening != null && opening.scaledUnits > 0) {
                    val godown = Inventory.ensureDefaultGodown(container, firmId)
                    val today = container.today()
                    val fy = container.repositories.firms.observeFinancialYears(firmId).first()
                        .firstOrNull { today >= it.startDate && today <= it.endDate }
                        ?: error("No financial year covers today")
                    val firm = container.repositories.firms.findFirm(firmId) ?: error("Unknown firm")
                    val id = container.newId()
                    StockVouchers(container.repositories.vouchers, container.postVoucher).adjust(
                        id, firmId, fy.id, firm.stateCode, today, "Opening stock",
                        listOf(
                            StockLineInput(
                                itemId = item.id,
                                description = item.name,
                                quantity = opening,
                                godownId = godown,
                                unitCost = Parse.money(form.openingCost) ?: item.purchasePrice,
                            ),
                        ),
                        { i -> "$id-$i" },
                        container.now(),
                    )
                }
                item
            }.onSuccess { saved ->
                baseline = _state.value
                onSaved(saved)
            }
                .onFailure { e -> _state.update { it.copy(saving = false, failure = e.message ?: "Could not save") } }
        }
    }
}

@Composable
fun ItemEditScreen(
    container: AppContainer,
    session: Session,
    itemId: String?,
    onDone: () -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
    onOpenStock: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val model = remember(itemId) { ItemEditorModel(container, session.firm.id, itemId, scope) }
    val form by model.state.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(form.rejectedSaves) { if (form.rejectedSaves > 0) scroll.animateScrollTo(0) }
    val units by remember { container.repositories.catalog.observeUnits(session.firm.id) }.collectAsState(initial = emptyList<UnitOfMeasure>())
    var pickUnit by remember { mutableStateOf(false) }
    var pickAltUnit by remember { mutableStateOf(false) }
    var pickRate by remember { mutableStateOf(false) }
    val slabs = remember { gstSlabsOn(container.today()) }
    val messages = LocalAppMessages.current
    val dirty = ItemRules.hasChanges(model.baseline, form) && !form.saving
    val requestClose = { if (dirty) confirmDiscard = true else onDone() }
    backHandler(dirty) { confirmDiscard = true }
    val save = {
        model.save { saved ->
            messages?.show(if (itemId == null) "Added ${saved.name}" else "Saved ${saved.name}")
            onDone()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(if (itemId == null) "New item" else "Edit item", onBack = requestClose) {
                if (itemId != null && form.trackInventory) {
                    TextButton(onClick = { onOpenStock(itemId) }) { Text("Stock") }
                }
                if (itemId != null) {
                    IconButton(onClick = { confirmRemove = true }, enabled = !form.saving) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove item")
                    }
                }
            }
        },
    ) { padding ->
        if (!form.loaded) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            FormColumn(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                FormField("Name *", form.name, { v -> model.edit { it.copy(name = v) } }, error = form.errors["name"], capitalization = KeyboardCapitalization.Words)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SelectableChip("Goods", !form.isService, { model.edit { it.copy(isService = false) } })
                    SelectableChip("Service", form.isService, { model.edit { it.copy(isService = true) } })
                }
                PickerField(
                    "Unit *",
                    units.firstOrNull { it.id == form.unitId }?.let { "${it.name} (${it.symbol})" } ?: "",
                    { pickUnit = true },
                    error = form.errors["unit"],
                )
                PickerField(
                    "Alternate unit",
                    units.firstOrNull { it.id == form.altUnitId }?.let { "${it.name} (${it.symbol})" } ?: "",
                    { pickAltUnit = true },
                    error = form.errors["altUnit"],
                    placeholder = "Optional, such as box or dozen",
                )
                if (form.altUnitId != null) {
                    val alt = units.firstOrNull { it.id == form.altUnitId }?.symbol.orEmpty()
                    val unit = units.firstOrNull { it.id == form.unitId }?.symbol.orEmpty()
                    FormField(
                        "1 $alt holds", form.altFactor, { v -> model.edit { it.copy(altFactor = v) } },
                        error = form.errors["altFactor"], keyboardType = KeyboardType.Decimal, suffix = unit,
                        supporting = "Bills can enter quantity in $alt; stock stays in $unit",
                    )
                    TextButton(onClick = { model.edit { it.copy(altUnitId = null, altFactor = "") } }) { Text("No alternate unit") }
                }
                FormField(
                    if (form.isService) "SAC code" else "HSN code",
                    form.hsnSac,
                    { v -> model.edit { it.copy(hsnSac = v.filter(Char::isDigit).take(8)) } },
                    error = form.errors["hsn"],
                    keyboardType = KeyboardType.Number,
                    supporting = "4 to 8 digits",
                )
                var hsnSearch by remember { mutableStateOf(false) }
                TextButton(onClick = { hsnSearch = true }) { Text("Search HSN/SAC codes (offline)") }
                if (hsnSearch) {
                    var q by remember { mutableStateOf(form.hsnSac) }
                    AlertDialog(
                        onDismissRequest = { hsnSearch = false },
                        title = { Text("HSN/SAC codes") },
                        text = {
                            Column {
                                FormField("Code or description", q, { q = it })
                                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                    HsnCodes.search(q).forEach { (code, desc) ->
                                        ListItem(
                                            headlineContent = { Text(code) },
                                            supportingContent = { Text(desc) },
                                            modifier = Modifier.clickable(role = Role.Button) {
                                                model.edit { it.copy(hsnSac = code) }
                                                hsnSearch = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { hsnSearch = false }) { Text("Close") } },
                    )
                }
                PickerField("GST rate", "${Fmt.percent(form.gstRate)}%", { pickRate = true })
                FormField(
                    "Sale price (before GST)",
                    form.salePrice,
                    { v -> model.edit { it.copy(salePrice = v) } },
                    error = form.errors["sale"],
                    keyboardType = KeyboardType.Decimal,
                    prefix = "₹",
                    supporting = "You can switch a line to GST-inclusive pricing on the invoice",
                )
                FormField(
                    "Purchase price",
                    form.purchasePrice,
                    { v -> model.edit { it.copy(purchasePrice = v) } },
                    error = form.errors["purchase"],
                    keyboardType = KeyboardType.Decimal,
                    prefix = "₹",
                    imeAction = ImeAction.Done,
                )
                if (!form.isService) {
                    SectionHeader("Stock")
                    SwitchRow("Track stock", "Purchases add stock, sales deduct it", form.trackInventory, { v -> model.edit { it.copy(trackInventory = v) } })
                    if (form.trackInventory) {
                        // An item uses batches or serials, never both: switching one on turns the other off.
                        SwitchRow("Batch and expiry", "Enter a batch number on each purchase and sale", form.trackBatch, { v -> model.edit { it.copy(trackBatch = v, trackSerial = it.trackSerial && !v) } })
                        SwitchRow("Serial / IMEI numbers", "One number per unit; duplicates are rejected", form.trackSerial, { v -> model.edit { it.copy(trackSerial = v, trackBatch = it.trackBatch && !v) } })
                        form.errors["serial"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FormField(
                            "Low-stock level",
                            form.lowStock,
                            { v -> model.edit { it.copy(lowStock = v) } },
                            error = form.errors["low"],
                            keyboardType = KeyboardType.Decimal,
                            supporting = "Alert when stock falls to this quantity",
                        )
                        if (itemId == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                FormField(
                                    "Opening stock",
                                    form.openingQty,
                                    { v -> model.edit { it.copy(openingQty = v) } },
                                    Modifier.weight(1f),
                                    error = form.errors["opening"],
                                    keyboardType = KeyboardType.Decimal,
                                )
                                FormField(
                                    "Cost per unit",
                                    form.openingCost,
                                    { v -> model.edit { it.copy(openingCost = v) } },
                                    Modifier.weight(1f),
                                    error = form.errors["openingCost"],
                                    keyboardType = KeyboardType.Decimal,
                                    prefix = "₹",
                                )
                            }
                        }
                    }
                }
                FormField(
                    "Barcode",
                    form.barcode,
                    { v -> model.edit { it.copy(barcode = v.trim()) } },
                    error = form.errors["barcode"],
                    supporting = "Scan or type the product's code, or generate one for your own labels",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { model.edit { it.copy(barcode = Inventory.generateBarcode(container.now().toEpochMilliseconds())) } }) {
                        Text("Generate barcode")
                    }
                    BarcodeScanButton(onScanned = { code -> model.edit { it.copy(barcode = code) } })
                }
                form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("Save item", { save() }, Modifier.fillMaxWidth(), busy = form.saving, busyText = "Saving…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this item?") },
            text = { Text("It will no longer appear in lists or pickers. Invoices that already use it are not changed. You can undo this right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    model.archive { removed ->
                        if (removed != null) {
                            messages?.show("Removed ${removed.name}", actionLabel = "Undo") {
                                container.repositories.catalog.saveItem(removed.copy(deletedAt = null, updatedAt = container.now()))
                            }
                        }
                        onDone()
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            message = "Your changes to this item have not been saved.",
            onDiscard = { confirmDiscard = false; onDone() },
            onKeepEditing = { confirmDiscard = false },
            onSave = { confirmDiscard = false; save() },
            saveLabel = "Save item",
        )
    }
    if (pickUnit) {
        UnitSheet(units, form.unitId, { u -> model.edit { it.copy(unitId = u.id) }; pickUnit = false }, { pickUnit = false })
    }
    if (pickAltUnit) {
        UnitSheet(units, form.altUnitId, { u -> model.edit { it.copy(altUnitId = u.id) }; pickAltUnit = false }, { pickAltUnit = false })
    }
    if (pickRate) {
        GstRateSheet(slabs, form.gstRate, { r -> model.edit { it.copy(gstRate = r) }; pickRate = false }, { pickRate = false })
    }
}
