package app.openbill.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.SerialStatus
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.ItemStock
import app.openbill.core.domain.service.StockValuation
import app.openbill.core.domain.usecase.StockLineInput
import app.openbill.core.domain.usecase.StockVouchers
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppSearchBar
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.DateField
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Inventory helpers shared by the stock screens, the item editor and the invoice editor. */
object Inventory {

    /**
     * Batches with stock in [godownId] (any godown when null) for picking on an outward line,
     * first-expiring first (FEFO); batches without an expiry date come last.
     */
    fun fefo(batches: List<Batch>, movements: List<StockMovement>, godownId: String?): List<Pair<Batch, Quantity>> {
        val balance = movements.filter { it.batchId != null }.groupBy { it.batchId!! }.mapValues { (_, l) -> l.sumOf { it.quantity.scaledUnits } }
        return batches.filter { godownId == null || it.godownId == godownId }
            .mapNotNull { b -> balance[b.id]?.takeIf { it > 0 }?.let { b to Quantity.ofScaledUnits(it) } }
            .sortedWith(compareBy({ it.first.expiryDate == null }, { it.first.expiryDate }, { it.first.batchNumber }))
    }

    /** A new Code128-friendly barcode: "OB" plus 11 digits from the clock and a counter. */
    fun generateBarcode(millis: Long, salt: Int = 0): String =
        "OB" + ((millis * 10 + salt) % 100_000_000_000L).toString().padStart(11, '0')

    /** The firm's default godown, creating "Main godown" (and the setting) on first use. */
    suspend fun ensureDefaultGodown(container: AppContainer, firmId: String): String {
        val repos = container.repositories
        val settings = repos.firms.findSettings(firmId) ?: app.openbill.core.domain.model.FirmSettings(firmId = firmId)
        val godowns = repos.catalog.observeGodowns(firmId).first()
        settings.defaultGodownId?.takeIf { id -> godowns.any { it.id == id } }?.let { return it }
        val godown = godowns.firstOrNull { it.isDefault } ?: godowns.firstOrNull() ?: Godown(
            id = container.newId(),
            firmId = firmId,
            name = "Main godown",
            isDefault = true,
            createdAt = container.now(),
            updatedAt = container.now(),
        ).also { repos.catalog.saveGodown(it) }
        repos.firms.saveSettings(settings.copy(defaultGodownId = godown.id))
        return godown.id
    }

    fun splitSerials(text: String): List<String> =
        text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun voucherLabel(v: Voucher?): String {
        if (v == null) return "Movement"
        val kind = when (v.type) {
            VoucherType.SALE -> "Sale"
            VoucherType.PURCHASE -> "Purchase"
            VoucherType.CREDIT_NOTE -> "Sales return"
            VoucherType.DEBIT_NOTE -> "Purchase return"
            VoucherType.STOCK_ADJUSTMENT -> v.notes ?: "Adjustment"
            VoucherType.STOCK_TRANSFER -> "Transfer"
            else -> v.type.name.lowercase()
        }
        return listOfNotNull(kind, v.number).joinToString(" · ")
    }
}

/** Live stock positions of every inventory item of the firm. */
@Composable
fun rememberStock(container: AppContainer, firmId: String): State<List<ItemStock>?> = remember(firmId) {
    combine(
        container.repositories.catalog.observeItems(firmId),
        container.repositories.stock.observeStockPositions(firmId),
    ) { items, positions -> StockValuation.summarize(items, positions) }
}.collectAsState(initial = null)

@Composable
fun SwitchRow(label: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** Dashboard card: stock value and the number of items at or below their low-stock level. */
@Composable
fun StockDashboardCard(container: AppContainer, session: Session, onOpen: (lowOnly: Boolean) -> Unit) {
    val stock by rememberStock(container, session.firm.id)
    val list = stock ?: return
    if (list.isEmpty()) return
    val low = list.count { it.isLow }
    val value = list.fold(Money.ZERO) { a, s -> a + s.valuation.value }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = pageGutter, vertical = Spacing.xs).clickable(onClickLabel = "Open stock") { onOpen(low > 0) },
        colors = CardDefaults.cardColors(
            containerColor = if (low > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        ListItem(
            leadingContent = { Icon(if (low > 0) Icons.Outlined.Warning else Icons.Outlined.ShoppingCart, contentDescription = null) },
            headlineContent = { Text(if (low > 0) "$low ${if (low == 1) "item" else "items"} low on stock" else "Stock in hand") },
            supportingContent = { Text("Stock value ${Fmt.inr(value)} · ${list.size} items tracked") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
        )
    }
}

@Composable
fun StockSummaryScreen(
    container: AppContainer,
    session: Session,
    lowOnly: Boolean,
    onOpenItem: (String) -> Unit,
    onAdjust: () -> Unit,
    onTransfer: () -> Unit,
    onGodowns: () -> Unit,
    onLabels: () -> Unit,
    onBack: () -> Unit,
) {
    val stock by rememberStock(container, session.firm.id)
    val godowns by remember { container.repositories.catalog.observeGodowns(session.firm.id) }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var low by rememberSaveable { mutableStateOf(lowOnly) }
    Scaffold(topBar = { AppTopBar("Stock", onBack = onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            val list = stock
            if (list == null) {
                LoadingState()
                return@CenteredContent
            }
            val shown = list.filter { (!low || it.isLow) && it.item.matchesStock(query) }
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item(key = "totals") {
                    val value = list.fold(Money.ZERO) { a, s -> a + s.valuation.value }
                    Column(Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                        Text("Stock value (weighted average cost)", style = MaterialTheme.typography.bodyMedium)
                        Text(Fmt.inr(value), style = MaterialTheme.typography.headlineSmall)
                    }
                }
                item(key = "actions") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        OutlinedButton(onClick = onAdjust) { Text("Adjust stock") }
                        OutlinedButton(onClick = onTransfer, enabled = godowns.size > 1) { Text("Transfer") }
                        OutlinedButton(onClick = onGodowns) { Text("Godowns (${godowns.size})") }
                        OutlinedButton(onClick = onLabels) { Text("Barcode labels") }
                    }
                }
                item(key = "search") { AppSearchBar(query, { query = it }, "Search item, SKU or barcode") }
                item(key = "filters") {
                    Row(Modifier.padding(horizontal = pageGutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SelectableChip("All (${list.size})", !low, { low = false })
                        SelectableChip("Low stock (${list.count { it.isLow }})", low, { low = true })
                    }
                }
                if (list.isEmpty()) {
                    item(key = "none") {
                        EmptyState(
                            Icons.Outlined.ShoppingCart,
                            "No stock items",
                            "Turn on stock tracking for an item to see its stock here.",
                        )
                    }
                } else if (shown.isEmpty()) {
                    item(key = "empty") { EmptyState(Icons.Outlined.Search, "No match", "No item matches your search or filter.") }
                }
                items(shown, key = { it.item.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                godowns.mapNotNull { g -> s.byGodown[g.id]?.takeIf { it.scaledUnits != 0L }?.let { "${g.name} ${Fmt.qty(it)}" } }
                                    .joinToString(" · ").ifEmpty { "No movements" } +
                                    (s.item.lowStockLevel?.let { " · low at ${Fmt.qty(it)}" } ?: ""),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    Fmt.qty(s.quantity),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (s.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(Fmt.inr(s.valuation.value), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        modifier = Modifier.clickable(onClickLabel = "Open item stock") { onOpenItem(s.item.id) },
                    )
                }
            }
        }
    }
}

private fun Item.matchesStock(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, true) || (sku?.contains(q, true) ?: false) || (barcode?.contains(q, true) ?: false)
}

/** One item's stock by godown, valuation, batches, serials and its read-only movement timeline. */
@Composable
fun ItemStockScreen(
    container: AppContainer,
    session: Session,
    itemId: String,
    onBack: () -> Unit,
    onAdjust: () -> Unit,
    onTransfer: () -> Unit,
    onEditItem: () -> Unit,
    onLabels: () -> Unit,
) {
    val firmId = session.firm.id
    val repos = container.repositories
    val items by remember { repos.catalog.observeItems(firmId) }.collectAsState(initial = null)
    val movements by remember(itemId) { repos.stock.observeItemMovements(firmId, itemId) }.collectAsState(initial = emptyList())
    val godowns by remember { repos.catalog.observeGodowns(firmId) }.collectAsState(initial = emptyList())
    val batches by remember(itemId) { repos.stock.observeBatches(itemId) }.collectAsState(initial = emptyList())
    val serials by remember(itemId) { repos.stock.observeSerials(itemId) }.collectAsState(initial = emptyList())
    // Only the vouchers behind this item's movements, looked up as they appear.
    val voucherById = remember(itemId) { androidx.compose.runtime.mutableStateMapOf<String, Voucher>() }
    androidx.compose.runtime.LaunchedEffect(movements) {
        movements.map { it.voucherId }.distinct().filterNot { it in voucherById }
            .forEach { id -> repos.vouchers.findVoucher(id)?.let { voucherById[id] = it } }
    }
    val item = items?.firstOrNull { it.id == itemId }
    val godownName = godowns.associate { it.id to it.name }
    Scaffold(
        topBar = {
            AppTopBar(item?.name ?: "Item stock", onBack = onBack) {
                TextButton(onClick = onEditItem) { Text("Edit") }
            }
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            if (items == null) {
                LoadingState()
                return@CenteredContent
            }
            if (item == null) {
                EmptyState(Icons.Outlined.Search, "Item not found", "It may have been removed.")
                return@CenteredContent
            }
            val valuation = StockValuation.value(movements)
            val byGodown = movements.groupBy { it.godownId }.mapValues { (_, l) -> Quantity.ofScaledUnits(l.sumOf { it.quantity.scaledUnits }) }
            val batchBalance = movements.filter { it.batchId != null }.groupBy { it.batchId!! }
                .mapValues { (_, l) -> Quantity.ofScaledUnits(l.sumOf { it.quantity.scaledUnits }) }
            LazyColumn(contentPadding = PaddingValues(horizontal = pageGutter, vertical = Spacing.sm)) {
                item(key = "head") {
                    Column {
                        Text("In stock", style = MaterialTheme.typography.bodyMedium)
                        Text(Fmt.qty(valuation.quantity), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Value ${Fmt.inr(valuation.value)} · average cost ${Fmt.inr(valuation.averageCost)}" +
                                (item.lowStockLevel?.let { " · low-stock level ${Fmt.qty(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.padding(top = Spacing.sm).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedButton(onClick = onAdjust) { Text("Adjust") }
                            OutlinedButton(onClick = onTransfer, enabled = godowns.size > 1) { Text("Transfer") }
                            OutlinedButton(onClick = onLabels, enabled = item.barcode != null) { Text("Print labels") }
                        }
                        if (!item.trackInventory) {
                            Text(
                                "Stock tracking is off for this item; turn it on in Edit to record stock.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
                    }
                }
                item(key = "godowns") { SectionHeader("By godown") }
                items(godowns, key = { "g" + it.id }) { g ->
                    ListItem(
                        headlineContent = { Text(g.name) },
                        trailingContent = { Text(Fmt.qty(byGodown[g.id] ?: Quantity.of(0))) },
                    )
                }
                if (batches.isNotEmpty()) {
                    item(key = "batches") { SectionHeader("Batches") }
                    items(batches, key = { "b" + it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.batchNumber) },
                            supportingContent = { Text((godownName[b.godownId] ?: "") + (b.expiryDate?.let { " · expires ${Fmt.dateLong(it)}" } ?: "")) },
                            trailingContent = { Text(Fmt.qty(batchBalance[b.id] ?: Quantity.of(0))) },
                        )
                    }
                }
                val inStock = serials.filter { it.status == SerialStatus.IN_STOCK }
                if (serials.isNotEmpty()) {
                    item(key = "serials") { SectionHeader("Serial numbers in stock (${inStock.size})") }
                    item(key = "serial-list") {
                        Text(inStock.joinToString(", ") { it.serialNumber }.ifEmpty { "None" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item(key = "timeline") { SectionHeader("Movements") }
                if (movements.isEmpty()) {
                    item(key = "no-moves") { Text("No stock movements yet.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(movements, key = { "m" + it.id }) { m -> MovementRow(m, voucherById[m.voucherId], godownName[m.godownId]) }
            }
        }
    }
}

@Composable
private fun MovementRow(m: StockMovement, voucher: Voucher?, godown: String?) {
    val inward = m.quantity.scaledUnits > 0
    ListItem(
        headlineContent = { Text(Inventory.voucherLabel(voucher), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOfNotNull(Fmt.dateLong(m.date), godown, m.unitCost?.let { "@ ${Fmt.inr(it)}" }).joinToString(" · ")) },
        trailingContent = {
            Text(
                (if (inward) "+" else "−") + Fmt.qty(Quantity.ofScaledUnits(kotlin.math.abs(m.quantity.scaledUnits))),
                style = MaterialTheme.typography.titleSmall,
                color = if (inward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
    HorizontalDivider()
}

private val REASONS = listOf("Opening stock", "Damaged", "Expired", "Lost / theft", "Found", "Stock count correction", "Own use")

/** Records a stock adjustment (with a reason) or a godown transfer as a posted voucher. */
@Composable
fun StockEntryScreen(
    container: AppContainer,
    session: Session,
    itemId: String?,
    transfer: Boolean,
    onDone: () -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
) {
    val firmId = session.firm.id
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val items by remember { repos.catalog.observeItems(firmId) }.collectAsState(initial = emptyList())
    val godowns by remember { repos.catalog.observeGodowns(firmId) }.collectAsState(initial = emptyList())
    val stockItems = items.filter { it.trackInventory && !it.isService }
    var selectedItemId by rememberSaveable { mutableStateOf(itemId) }
    val item = stockItems.firstOrNull { it.id == selectedItemId }
    var godownId by rememberSaveable { mutableStateOf(session.settings.defaultGodownId) }
    var toGodownId by rememberSaveable { mutableStateOf<String?>(null) }
    var inward by rememberSaveable { mutableStateOf(true) }
    var qty by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var batch by rememberSaveable { mutableStateOf("") }
    var expiry by remember { mutableStateOf<LocalDate?>(null) }
    var serialText by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(container.today()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) } // "item", "from", "to"
    backHandler(true) { onDone() }

    fun submit() {
        val itm = item ?: run { error = "Choose an item"; return }
        val q = Parse.quantity(qty)?.takeIf { q -> q > Quantity.of(0) } ?: run { error = "Enter a quantity above zero"; return }
        val from = godownId ?: run { error = "Choose a godown"; return }
        val fy = session.financialYearFor(date) ?: run { error = "No financial year covers this date"; return }
        if (!transfer && reason.isBlank()) { error = "Give a reason"; return }
        if (transfer && (toGodownId == null || toGodownId == from)) { error = "Choose a different destination godown"; return }
        val unitCost = if (!transfer && inward) (Parse.money(cost) ?: itm.purchasePrice) else Money.ZERO
        busy = true
        error = null
        scope.launch {
            val line = StockLineInput(
                itemId = itm.id,
                description = itm.name,
                quantity = q,
                godownId = from,
                direction = if (inward) StockDirection.INWARD else StockDirection.OUTWARD,
                unitCost = unitCost,
                toGodownId = toGodownId.takeIf { transfer },
                batchNumber = batch.takeIf { itm.trackBatch },
                expiryDate = expiry.takeIf { itm.trackBatch },
                serialNumbers = if (itm.trackSerial) Inventory.splitSerials(serialText) else emptyList(),
            )
            val sv = StockVouchers(repos.vouchers, container.postVoucher)
            val id = container.newId()
            runCatching {
                if (transfer) {
                    sv.transfer(id, firmId, fy.id, session.firm.stateCode, date, reason.ifBlank { null }, listOf(line), { i -> "$id-$i" }, container.now())
                } else {
                    sv.adjust(id, firmId, fy.id, session.firm.stateCode, date, reason, listOf(line), { i -> "$id-$i" }, container.now())
                }
            }.onSuccess {
                messages?.show(if (transfer) "Transfer recorded" else "Stock adjusted")
                onDone()
            }.onFailure { e ->
                busy = false
                error = e.message ?: "Could not record"
            }
        }
    }

    Scaffold(topBar = { AppTopBar(if (transfer) "Stock transfer" else "Adjust stock", onBack = onDone) }) { padding ->
        CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            FormColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                PickerField("Item *", item?.name.orEmpty(), { pick = "item" }, supporting = if (stockItems.isEmpty()) "Turn on stock tracking for an item first" else null)
                val godownNames = godowns.associate { it.id to it.name }
                PickerField(if (transfer) "From godown *" else "Godown *", godownNames[godownId].orEmpty(), { pick = "from" })
                if (transfer) {
                    PickerField("To godown *", godownNames[toGodownId].orEmpty(), { pick = "to" })
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SelectableChip("Add stock (in)", inward, { inward = true })
                        SelectableChip("Remove stock (out)", !inward, { inward = false })
                    }
                }
                DateField("Date", date, { date = it })
                FormField("Quantity *", qty, { qty = it }, keyboardType = KeyboardType.Decimal)
                if (!transfer && inward) {
                    FormField(
                        "Unit cost",
                        cost,
                        { cost = it },
                        keyboardType = KeyboardType.Decimal,
                        prefix = "₹",
                        supporting = "Used for stock valuation; defaults to the purchase price${item?.let { " (${Fmt.inr(it.purchasePrice)})" } ?: ""}",
                    )
                }
                if (item?.trackBatch == true) {
                    FormField("Batch number *", batch, { batch = it })
                    if (inward || transfer) DateField("Expiry date", expiry, { expiry = it })
                }
                if (item?.trackSerial == true) {
                    FormField(
                        "Serial / IMEI numbers *",
                        serialText,
                        { serialText = it },
                        singleLine = false,
                        supporting = "One per unit, separated by new lines or commas (${Inventory.splitSerials(serialText).size} entered)",
                    )
                }
                if (!transfer) {
                    SectionHeader("Reason *")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        REASONS.forEach { r -> SelectableChip(r, reason == r, { reason = r; if (r == "Opening stock") inward = true }) }
                    }
                    FormField("Reason", reason, { reason = it }, supporting = "Kept in the audit trail")
                } else {
                    FormField("Note", reason, { reason = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton(if (transfer) "Record transfer" else "Record adjustment", { submit() }, Modifier.fillMaxWidth(), busy = busy, busyText = "Saving…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
    when (pick) {
        "item" -> PickerSheet(
            title = "Item",
            options = stockItems,
            label = { it.name },
            supporting = { listOfNotNull(it.sku, it.barcode).joinToString(" · ").ifEmpty { null } },
            selected = { it.id == selectedItemId },
            key = { it.id },
            searchable = true,
            emptyText = "No items track stock yet. Turn on stock tracking in an item.",
            onSelect = { selectedItemId = it.id; pick = null },
            onDismiss = { pick = null },
        )
        "from", "to" -> PickerSheet(
            title = if (pick == "to") "To godown" else "Godown",
            options = godowns,
            label = { it.name },
            selected = { it.id == (if (pick == "to") toGodownId else godownId) },
            key = { it.id },
            onSelect = { g -> if (pick == "to") toGodownId = g.id else godownId = g.id; pick = null },
            onDismiss = { pick = null },
        )
    }
}

/** Godowns (stock locations): add, rename and choose the default used by invoices. */
@Composable
fun GodownsScreen(container: AppContainer, session: Session, onBack: () -> Unit) {
    val firmId = session.firm.id
    val repos = container.repositories
    val scope = rememberCoroutineScope()
    val godowns by remember { repos.catalog.observeGodowns(firmId) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Godown?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            AppTopBar("Godowns", onBack = onBack) {
                IconButton(onClick = { adding = true; name = ""; error = null }) { Icon(Icons.Filled.Add, contentDescription = "Add godown") }
            }
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn {
                items(godowns, key = { it.id }) { g ->
                    val isDefault = g.id == session.settings.defaultGodownId
                    ListItem(
                        headlineContent = { Text(g.name) },
                        supportingContent = { Text(if (isDefault) "Default for invoices and purchases" else "Tap to rename") },
                        trailingContent = {
                            if (!isDefault) {
                                TextButton(onClick = {
                                    scope.launch { repos.firms.saveSettings(session.settings.copy(defaultGodownId = g.id)) }
                                }) { Text("Make default") }
                            }
                        },
                        modifier = Modifier.clickable { editing = g; name = g.name; error = null },
                    )
                }
            }
        }
    }
    if (adding || editing != null) {
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (adding) "New godown" else "Rename godown") },
            text = { FormField("Name", name, { name = it }, error = error) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { error = "Name is required"; return@TextButton }
                    if (godowns.any { it.name.equals(n, true) && it.id != editing?.id }) { error = "A godown with this name exists"; return@TextButton }
                    val now = container.now()
                    val g = editing?.copy(name = n, updatedAt = now)
                        ?: Godown(id = container.newId(), firmId = firmId, name = n, createdAt = now, updatedAt = now)
                    scope.launch { runCatching { repos.catalog.saveGodown(g) }.onFailure { e -> error = e.message } }
                    adding = false
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}
