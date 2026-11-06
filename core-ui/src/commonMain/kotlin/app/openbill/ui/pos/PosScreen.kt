package app.openbill.ui.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import app.openbill.ui.nav.LocalKeyRootFocus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.service.StockPosition
import app.openbill.core.domain.usecase.PosCheckout
import app.openbill.core.domain.usecase.PosCheckout.label
import app.openbill.core.domain.usecase.PosSettlement
import app.openbill.core.domain.usecase.PosTender
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.barcode.BarcodeScanButton
import app.openbill.ui.components.AmountRow
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.inventory.Inventory
import app.openbill.ui.invoice.DraftLine
import app.openbill.ui.invoice.InvoiceEditorModel
import app.openbill.ui.invoice.InvoiceMath
import app.openbill.ui.invoice.LineEditor
import app.openbill.ui.invoice.LineForm
import app.openbill.ui.invoice.LineRules
import app.openbill.ui.items.gstSlabsOn
import app.openbill.ui.money.refLabel
import app.openbill.ui.nav.LocalSearchFocus
import app.openbill.ui.parties.summaryLine
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/** Keys of the POS counter, shown by F1 and the keyboard button. */
internal val POS_SHORTCUTS = listOf(
    "F2 or Ctrl+F" to "Search items (scanners type here; Enter adds)",
    "F3" to "Quantity of the selected line",
    "F4" to "Discount of the selected line",
    "F6" to "Held bills",
    "F8" to "Hold this bill",
    "F9 or Ctrl+N" to "New bill",
    "F12 or Ctrl+Enter" to "Pay",
    "Enter (in Pay)" to "Complete the sale",
    "Esc" to "Close a dialog or leave POS",
    "F1" to "This list",
)

/** One payment line in the pay dialog, as typed. */
private data class TenderRow(val method: PaymentMethod, val accountId: String?, val amount: String, val reference: String = "")

/**
 * Stock on hand for a POS item tile in the bill's [godownId] (all godowns when none is set), as
 * text plus whether it is short (out of stock or at/below the low-stock level). Null for services
 * and items without stock tracking. The words carry the state, so it is not colour-only.
 */
internal fun posStockLabel(item: Item, position: StockPosition?, godownId: String?, unit: String?): Pair<String, Boolean>? {
    if (!item.trackInventory || item.isService) return null
    val byGodown = position?.byGodown.orEmpty()
    val onHand = if (godownId != null) byGodown[godownId] ?: Quantity.of(0)
    else Quantity.ofScaledUnits(byGodown.values.sumOf { it.scaledUnits })
    return when {
        onHand <= Quantity.of(0) -> (if (onHand < Quantity.of(0)) "Out of stock (${Fmt.qtyUnit(onHand, unit)})" else "Out of stock") to true
        item.lowStockLevel?.let { onHand <= it } == true -> "Low stock: ${Fmt.qtyUnit(onHand, unit)}" to true
        else -> "In stock: ${Fmt.qtyUnit(onHand, unit)}" to false
    }
}

private data class SaleDone(val voucherId: String, val number: String?, val settlement: PosSettlement)

/**
 * POS counter billing. The bill is an ordinary sale invoice edited through [InvoiceEditorModel]
 * (same tax engine, validation and posting), entered item-first with the "Cash sale" customer
 * by default. Pay splits the total across tenders via [PosCheckout]; held bills are drafts with
 * [Voucher.heldAt], so they survive restarts and are in backups.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosScreen(
    container: AppContainer,
    session: Session,
    voucherId: String?,
    onClose: () -> Unit,
    onReceipt: (voucherId: String) -> Unit,
    onOpenInvoice: (voucherId: String) -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
) {
    val firm = session.firm
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    // A bill is a draft id (resumed) or null (new); the counter starts a fresh model per bill.
    var billId by rememberSaveable { mutableStateOf(voucherId) }
    var billSeq by rememberSaveable { mutableIntStateOf(0) }
    val model = remember(billId, billSeq) { InvoiceEditorModel(container, session, billId, scope) }
    val state by model.state.collectAsState()
    val items by remember { repos.catalog.observeItems(firm.id) }.collectAsState(initial = emptyList())
    val units by remember { repos.catalog.observeUnits(firm.id) }.collectAsState(initial = emptyList())
    val parties by remember { repos.parties.observeParties(firm.id) }.collectAsState(initial = emptyList())
    val accounts by remember { repos.ledger.observeAccounts(firm.id) }.collectAsState(initial = emptyList())
    val held by remember { repos.vouchers.observeHeldBills(firm.id) }.collectAsState(initial = emptyList())
    val positions by remember { repos.stock.observeStockPositions(firm.id) }.collectAsState(initial = emptyMap())
    val unitSymbol = units.associate { it.id to it.symbol }
    LaunchedEffect(firm.id) { runCatching { repos.ledger.ensureSystemAccounts(firm.id, container.now()) } }

    var query by remember(billId, billSeq) { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember(billId, billSeq) { mutableStateOf<String?>(null) }
    var lineForm by remember { mutableStateOf<LineForm?>(null) }
    var pickParty by remember { mutableStateOf(false) }
    var showHeld by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<SaleDone?>(null) }
    var discarding by remember { mutableStateOf<Voucher?>(null) }
    /** What to do once the user decides about the unsaved bill (hold, discard or cancel). */
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val unsaved = state.loaded && state.dirty && state.lines.isNotEmpty()
    val cashParty = PosCheckout.isCashSale(state.party)
    val total = state.summary?.grandTotal

    // New bills start on the walk-in "Cash sale" customer.
    LaunchedEffect(model) {
        if (billId != null) return@LaunchedEffect
        runCatching {
            val (party, isNew) = PosCheckout.cashSaleParty(repos.parties.observeParties(firm.id).first(), firm.id, firm.stateCode, container::newId, container.now())
            if (isNew) repos.parties.saveParty(party)
            party
        }.onSuccess { model.setParty(it) }.onFailure { messages?.show("Could not set up the Cash sale customer: ${it.message}") }
    }

    fun newBill() {
        billId = null
        billSeq++
        searchError = null
    }

    fun guarded(action: () -> Unit) {
        if (unsaved) pending = action else action()
    }

    fun hold(then: () -> Unit = ::newBill) {
        if (state.lines.isEmpty()) {
            messages?.show("Add an item before holding the bill")
            return
        }
        model.save(post = false, hold = true) { _, _ ->
            messages?.show("Bill held. Resume it from Held bills (F6)")
            then()
        }
    }

    fun bump(line: DraftLine, delta: Int) {
        selectedKey = line.key
        if (line.serialNumbers.isNotEmpty()) {
            // Serial-tracked units are picked one by one in the line editor.
            lineForm = LineRules.fromDraft(line, items.firstOrNull { it.id == line.itemId })
            return
        }
        val units = line.quantity.scaledUnits + delta * 10_000L
        if (units <= 0L) {
            model.removeLine(line.key)
            return
        }
        val qty = Quantity.ofScaledUnits(units)
        val gross = InvoiceMath.gross(qty, line.rate)
        val discount = line.discountPercent?.let { InvoiceMath.percentOf(gross, it) } ?: if (line.discount > gross) gross else line.discount
        model.upsertLine(line.copy(quantity = qty, discount = discount))
    }

    fun addItem(item: Item) {
        searchError = null
        query = ""
        val alt = item.alternateUnitId?.let { unitSymbol[it] }
        val form = LineRules.fromItem(item, unitSymbol[item.unitId], false, alt)
        if (item.trackInventory && !item.isService && (item.trackBatch || item.trackSerial)) {
            lineForm = form
            return
        }
        state.lines.lastOrNull { it.itemId == item.id && it.serialNumbers.isEmpty() }?.let { bump(it, 1); return }
        val (line, _) = LineRules.validate(form, model::newKey)
        if (line == null) {
            lineForm = form // e.g. no sale price yet: type the rate
        } else {
            model.upsertLine(line)
            selectedKey = line.key
        }
    }

    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter {
        it.name.contains(q, true) || it.sku?.contains(q, true) == true || it.barcode?.equals(q, true) == true || it.hsnSac?.startsWith(q) == true
    }

    fun enter() {
        if (q.isEmpty()) return
        scope.launch {
            val item = items.firstOrNull { it.barcode.equals(q, true) || it.sku.equals(q, true) }
                ?: runCatching { repos.catalog.findItemByBarcode(firm.id, q) }.getOrNull()
                ?: shown.firstOrNull()
            if (item == null) searchError = "No item matches \"$q\"" else addItem(item)
        }
    }

    fun selectedLine(): DraftLine? = state.lines.firstOrNull { it.key == selectedKey } ?: state.lines.lastOrNull()

    fun editSelected() {
        val line = selectedLine() ?: run { messages?.show("Add an item first"); return }
        val item = items.firstOrNull { it.id == line.itemId }
        lineForm = LineRules.fromDraft(line, item, item?.alternateUnitId?.let { unitSymbol[it] })
    }

    fun pay() {
        if (state.lines.isEmpty()) {
            messages?.show("Add an item first")
            return
        }
        if (total == null || total <= Money.ZERO) {
            messages?.show(state.calcError ?: "The bill total must be above zero")
            return
        }
        paying = true
    }

    val requestClose = { guarded(onClose) }
    backHandler(unsaved) { pending = onClose }
    backHandler(lineForm != null) { lineForm = null }

    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sharedSearch = LocalSearchFocus.current
    DisposableEffect(sharedSearch) {
        val unregister = sharedSearch?.register(searchFocus)
        onDispose { unregister?.invoke() }
    }
    val overlay = lineForm != null || pickParty || showHeld || showHelp || paying || done != null || pending != null || discarding != null
    // Keyboard users (desktop, or a tablet with a keyboard or scanner) get the search field
    // focused. Touch users get a focused root instead, so the on-screen keyboard does not cover
    // the counter; typing or scanning there still lands in the search field (see [keys]).
    val rootFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val keyboardFirst = LocalKeyRootFocus.current != null || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(overlay, state.loaded, billSeq, billId) {
        if (!overlay && state.loaded) {
            runCatching { if (keyboardFirst) searchFocus.requestFocus() else rootFocus.requestFocus() }
            if (!keyboardFirst) keyboard?.hide()
        }
    }

    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || overlay) return@onPreviewKeyEvent false
        val mod = e.isCtrlPressed || e.isMetaPressed
        val ch = e.utf16CodePoint
        when {
            !mod && !searchFocused && ch >= 32 && ch != 127 && e.key != Key.Spacebar -> {
                // A scanner or keyboard typing while the search field is not focused.
                query += ch.toChar()
                searchError = null
                runCatching { searchFocus.requestFocus() }
                true
            }
            !mod && !searchFocused && (e.key == Key.Enter || e.key == Key.NumPadEnter) && query.isNotBlank() -> { enter(); true }
            e.key == Key.F1 -> { showHelp = true; true }
            e.key == Key.F2 || (mod && e.key == Key.F) -> { runCatching { searchFocus.requestFocus() }; true }
            e.key == Key.F3 || e.key == Key.F4 -> { editSelected(); true }
            e.key == Key.F6 -> { showHeld = true; true }
            e.key == Key.F8 -> { hold(); true }
            e.key == Key.F9 || (mod && e.key == Key.N) -> { guarded(::newBill); true }
            e.key == Key.F12 || (mod && (e.key == Key.Enter || e.key == Key.NumPadEnter)) -> { pay(); true }
            else -> false
        }
    }

    Scaffold(
        modifier = keys.focusRequester(rootFocus).focusable(),
        topBar = {
            AppTopBar(
                if (billId == null) "POS" else "POS · held bill",
                onBack = requestClose,
                actions = {
                    TextButton(onClick = { showHeld = true }) { Text("Held bills (${held.size})") }
                    TextButton(onClick = { showHelp = true }) { Text("Keys") }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        if (!state.loaded) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        if (state.failure != null && billId != null && state.voucherId == null) {
            // A held bill that can no longer be opened (posted or deleted elsewhere).
            Column(Modifier.padding(padding).padding(Spacing.lg)) {
                Text(state.failure!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = ::newBill) { Text("Start a new bill") }
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            val itemsPane: @Composable (Modifier) -> Unit = { m ->
                Column(m.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; searchError = null },
                            label = { Text("Scan or search items (F2)") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            isError = searchError != null,
                            supportingText = searchError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { enter() }),
                            modifier = Modifier.weight(1f).focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused }.onPreviewKeyEvent { e ->
                                // Hardware Enter (and HID barcode scanners) add the match.
                                if (e.type == KeyEventType.KeyDown && !e.isCtrlPressed && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { enter(); true } else false
                            },
                        )
                        BarcodeScanButton(onScanned = { code -> query = code; enter() })
                    }
                    if (items.isEmpty()) {
                        Text("No items yet. Add them in the Items tab to bill them here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (shown.isEmpty()) {
                        Text("No item matches \"$q\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyVerticalGrid(
                        GridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            val stock = posStockLabel(item, positions[item.id], state.godownId, unitSymbol[item.unitId])
                            OutlinedCard(
                                onClick = { addItem(item) },
                                modifier = Modifier.heightIn(min = 76.dp).semantics {
                                    contentDescription = "Add ${item.name}, ${Fmt.inr(item.salePrice)}" + (stock?.let { ", ${it.first}" } ?: "")
                                },
                            ) {
                                Column(Modifier.padding(Spacing.sm)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (item.salePrice.paise == 0L) "Set price" else Fmt.inr(item.salePrice),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    stock?.let { (text, short) ->
                                        Text(
                                            text,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (short) FontWeight.Bold else null,
                                            color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val cartPane: @Composable (Modifier) -> Unit = { m ->
                Column(m.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(onClick = { pickParty = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Customer: ${state.party?.name ?: "choose"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.lines.size) { if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                        if (state.lines.isEmpty()) {
                            item { Text("Scan or tap an item to start the bill.", Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        items(state.lines, key = { it.key }) { line ->
                            val idx = state.lines.indexOf(line)
                            val lineTotal = state.summary?.lineResults?.getOrNull(idx)?.totalAmount
                            val selected = line.key == (selectedLine()?.key)
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                    .clickable(onClickLabel = "Edit ${line.description}") {
                                        selectedKey = line.key
                                        val item = items.firstOrNull { it.id == line.itemId }
                                        lineForm = LineRules.fromDraft(line, item, item?.alternateUnitId?.let { unitSymbol[it] })
                                    }
                                    .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.description, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${Fmt.qtyUnit(line.quantity, line.unit)} × ${Fmt.inr(line.rate)}" +
                                            if (line.discount.paise != 0L) " · disc ${Fmt.inr(line.discount)}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { bump(line, -1) }) {
                                    Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "One less ${line.description}" })
                                }
                                Text(line.quantity.format(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 28.dp))
                                IconButton(onClick = { bump(line, 1) }) {
                                    Icon(Icons.Filled.Add, contentDescription = "One more ${line.description}")
                                }
                                Text(lineTotal?.let { Fmt.inr(it) } ?: "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 88.dp))
                            }
                            HorizontalDivider()
                        }
                    }
                    val errors = (state.errors.values + listOfNotNull(state.calcError, state.failure)).distinct()
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.summary?.let { s ->
                        if (s.discount.paise != 0L) AmountRow("Discount", s.discount, negative = true)
                        AmountRow("Taxable value", s.taxable + s.chargesTaxable)
                        if (s.isIntraState) AmountRow("CGST + SGST", s.cgst + s.sgst) else AmountRow("IGST", s.igst)
                        if (s.roundOff.paise != 0L) AmountRow("Round off", Fmt.inrSigned(s.roundOff))
                        AmountRow("Total (${state.lines.size} item${if (state.lines.size == 1) "" else "s"})", s.grandTotal, emphasize = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = { hold() }, enabled = !state.saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Hold (F8)") }
                        OutlinedButton(onClick = { guarded(::newBill) }, enabled = !state.saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("New (F9)") }
                        PrimaryButton(
                            "Pay ${total?.let { Fmt.inr(it) } ?: ""} (F12)", ::pay,
                            Modifier.weight(1.6f).heightIn(min = 56.dp),
                            enabled = state.lines.isNotEmpty(), busy = state.saving, busyText = "Saving…",
                        )
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    itemsPane(Modifier.weight(1.1f).fillMaxHeight())
                    VerticalDivider()
                    cartPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    itemsPane(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    cartPane(Modifier.weight(1.3f).fillMaxWidth())
                }
            }
        }
    }

    // Line editor (quantity, discount, rate, batch, serials) shared with the invoice editor.
    val batchItemId = lineForm?.takeIf { it.trackBatch }?.itemId
    val batches by produceState(emptyList<Pair<Batch, Quantity>>(), batchItemId, state.godownId) {
        value = if (batchItemId == null) emptyList() else runCatching {
            Inventory.fefo(repos.stock.observeBatches(batchItemId).first(), repos.stock.observeItemMovements(firm.id, batchItemId).first(), state.godownId)
        }.getOrDefault(emptyList())
    }
    val today = container.today()
    LineEditor(
        form = lineForm,
        batches = batches,
        slabs = remember(today) { gstSlabsOn(today) },
        slabDate = today,
        purchase = false,
        preview = { f -> runCatching { model.previewTotal(f) }.getOrNull() },
        onSave = { f ->
            val (line, errors) = LineRules.validate(f, model::newKey)
            if (line != null) {
                model.upsertLine(line)
                selectedKey = line.key
                lineForm = null
            }
            errors
        },
        onDelete = { key -> model.removeLine(key); lineForm = null },
        onDismiss = { lineForm = null },
    )

    if (pickParty) {
        PickerSheet(
            title = "Customer",
            options = parties.filter { it.deletedAt == null },
            label = { it.name },
            supporting = { if (PosCheckout.isCashSale(it)) "Walk-in customer, paid in full" else it.summaryLine() },
            selected = { it.id == state.party?.id },
            key = { it.id },
            searchable = true,
            onSelect = { model.setParty(it); pickParty = false },
            onDismiss = { pickParty = false },
        )
    }

    if (paying && total != null) {
        PayDialog(
            total = total,
            accounts = accounts,
            creditParty = state.party?.takeIf { !cashParty },
            busy = state.saving,
            failure = (state.errors.values + listOfNotNull(state.failure)).firstOrNull(),
            onDismiss = { if (!state.saving) paying = false },
            onComplete = { tenders, settlement ->
                // Posting and payments are one transaction: a failure leaves the bill an unposted draft.
                var number: String? = null
                model.save(
                    post = true,
                    poster = { id ->
                        number = PosCheckout.postAndSettle(repos, container.postVoucher, id, firm.stateCode, tenders, settlement, container::newId, container.now()).first.number
                    },
                ) { id, posted ->
                    if (!posted) return@save
                    paying = false
                    done = SaleDone(id, number, settlement)
                }
            },
        )
    }

    done?.let { d ->
        val finish = { done = null; newBill() }
        AlertDialog(
            onDismissRequest = finish,
            title = { Text("Sale complete${d.number?.let { " · $it" } ?: ""}") },
            text = {
                Column(Modifier.onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { finish(); true } else false }) {
                    if (d.settlement.change > Money.ZERO) {
                        Text("Change due", style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.inr(d.settlement.change), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                    AmountRow("Paid", d.settlement.paid)
                    if (d.settlement.credit > Money.ZERO) AmountRow("On the customer's account", d.settlement.credit)
                }
            },
            confirmButton = { Button(onClick = finish) { Text("New bill") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { done = null; newBill(); onOpenInvoice(d.voucherId) }) { Text("Open invoice") }
                    TextButton(onClick = { done = null; newBill(); onReceipt(d.voucherId) }) { Text("Print receipt") }
                }
            },
        )
    }

    if (showHeld) {
        AlertDialog(
            onDismissRequest = { showHeld = false },
            title = { Text("Held bills") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (held.isEmpty()) Text("No bills on hold. Hold a bill (F8) to serve the next customer and come back to it.")
                    held.forEach { v ->
                        val at = v.heldAt!!.toLocalDateTime(container.timeZone)
                        val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}" + if (at.date != today) " ${Fmt.date(at.date)}" else ""
                        val who = parties.firstOrNull { it.id == v.partyId }?.name ?: "No customer"
                        ListItem(
                            headlineContent = { Text("${Fmt.inr(v.total)} · ${v.lines.size} item${if (v.lines.size == 1) "" else "s"}") },
                            supportingContent = { Text("$who · held $time" + if (v.id == billId) " · open now" else "") },
                            trailingContent = {
                                IconButton(onClick = { discarding = v }) { Icon(Icons.Filled.Delete, contentDescription = "Discard held bill of ${Fmt.inr(v.total)}") }
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Resume") {
                                showHeld = false
                                if (v.id != billId) guarded { billId = v.id; billSeq++ }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHeld = false }) { Text("Close") } },
        )
    }

    discarding?.let { v ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("Discard this held bill?") },
            text = { Text("The ${Fmt.inr(v.total)} bill moves to the recycle bin (More → Recycle bin) and can be restored from there.") },
            confirmButton = {
                TextButton(onClick = {
                    discarding = null
                    scope.launch {
                        runCatching { repos.vouchers.softDeleteDraft(v.id, container.now()) }
                            .onSuccess {
                                messages?.show("Held bill discarded")
                                if (v.id == billId) newBill()
                            }
                            .onFailure { messages?.show(it.message ?: "Could not discard") }
                    }
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("Keep") } },
        )
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Hold this bill?") },
            text = { Text("The current bill has ${state.lines.size} item(s) not yet paid. Hold it to come back to it, or discard the changes.") },
            confirmButton = { TextButton(onClick = { pending = null; hold(then = action) }) { Text("Hold") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                    TextButton(onClick = { pending = null; action() }) { Text("Discard changes", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("POS keyboard shortcuts") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    POS_SHORTCUTS.forEach { (k, what) -> AmountRow(what, k) }
                    Text("Shortcuts work on desktop and with a hardware keyboard on tablets.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayDialog(
    total: Money,
    accounts: List<Account>,
    creditParty: Party?,
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onComplete: (List<PosTender>, PosSettlement) -> Unit,
) {
    val cash = accounts.filter { it.type == AccountType.CASH }
    val bank = accounts.filter { it.type == AccountType.BANK }
    fun accountsFor(m: PaymentMethod) = if (m == PaymentMethod.CASH) cash else bank
    val rows = remember { mutableStateListOf(TenderRow(PaymentMethod.CASH, cash.firstOrNull()?.id, total.format())) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    val amounts = rows.map { Parse.money(it.amount) }
    val tendered = amounts.fold(Money.ZERO) { t, a -> t + (a ?: Money.ZERO) }

    fun add(m: PaymentMethod) {
        val left = total - tendered
        rows.add(TenderRow(m, accountsFor(m).firstOrNull()?.id, if (left > Money.ZERO) left.format() else ""))
        error = null
    }

    fun submit() {
        if (busy) return
        error = null
        val tenders = rows.mapIndexed { i, r ->
            val amount = amounts[i]?.takeIf { it > Money.ZERO } ?: run { error = "Enter an amount above zero for each payment"; return }
            val account = r.accountId ?: run {
                error = if (r.method == PaymentMethod.CASH) "Add a cash account in Money first" else "Add a bank account in Money to take ${r.method.label()} payments"
                return
            }
            PosTender(r.method, account, amount, r.reference.trim().ifEmpty { null })
        }
        runCatching { PosCheckout.settle(total, tenders, creditAllowed = creditParty != null) }
            .onSuccess { onComplete(tenders, it) }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay ${Fmt.inr(total)}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { submit(); true } else false
                },
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rows.forEachIndexed { i, r ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.Center) {
                            listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.BANK).forEach { m ->
                                SelectableChip(m.label(), r.method == m, {
                                    rows[i] = r.copy(method = m, accountId = accountsFor(m).firstOrNull()?.id)
                                    error = null
                                })
                            }
                            if (rows.size > 1) {
                                IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove this ${r.method.label()} payment") }
                            }
                        }
                        val choices = accountsFor(r.method)
                        if (choices.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                choices.forEach { a -> SelectableChip(a.name, r.accountId == a.id, { rows[i] = r.copy(accountId = a.id) }) }
                            }
                        } else if (choices.isEmpty()) {
                            Text(
                                if (r.method == PaymentMethod.CASH) "No cash account" else "No bank account yet: add one in Money to take ${r.method.label()}",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedTextField(
                                r.amount, { v -> rows[i] = r.copy(amount = v); error = null },
                                label = { Text("${r.method.label()} amount") },
                                prefix = { Text("₹") },
                                singleLine = true,
                                isError = amounts[i] == null && r.amount.isNotBlank(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f).let { if (i == 0) it.focusRequester(firstField) else it },
                            )
                            if (r.method != PaymentMethod.CASH) {
                                OutlinedTextField(
                                    r.reference, { v -> rows[i] = r.copy(reference = v.take(40)); error = null },
                                    label = { Text(refLabel(r.method)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.BANK).forEach { m ->
                        TextButton(onClick = { add(m) }) { Text("+ ${m.label()}") }
                    }
                }
                AmountRow("Tendered", tendered)
                when {
                    tendered > total -> AmountRow("Change due", tendered - total, emphasize = true)
                    tendered < total && creditParty != null -> AmountRow("On ${creditParty.name}'s account", total - tendered, emphasize = true)
                    tendered < total -> AmountRow("Still to pay", total - tendered, emphasize = true)
                }
                (error ?: failure)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            PrimaryButton("Complete sale (Enter)", ::submit, busy = busy, busyText = "Posting…")
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Back") } },
    )
}
