package app.openbill.ui.parties

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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.PartyType
import app.openbill.core.model.Money
import app.openbill.core.tax.IndianState
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
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.forms.Validation
import app.openbill.ui.invoice.Monogram
import app.openbill.ui.setup.StatePickerSheet
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import app.openbill.ui.components.GrowLimitOnScroll
import app.openbill.ui.components.PAGE_SIZE

/** List filter by party role; "Both" parties appear under customers and suppliers. */
enum class PartyFilter(val label: String) {
    ALL("All"),
    CUSTOMERS("Customers"),
    SUPPLIERS("Suppliers");

    val types: Set<PartyType> get() = when (this) {
        ALL -> PartyType.entries.toSet()
        CUSTOMERS -> setOf(PartyType.CUSTOMER, PartyType.BOTH)
        SUPPLIERS -> setOf(PartyType.SUPPLIER, PartyType.BOTH)
    }

    fun matches(party: Party): Boolean = party.type in types
}

/** One-line summary for list rows and pickers: "98xxxxxx · 29AAGFN… · Karnataka". */
fun Party.summaryLine(): String =
    listOfNotNull(phone, gstin, stateCode?.let { IndianState.fromCode(it)?.displayName ?: it })
        .joinToString(" · ")
        .ifEmpty { "No contact details" }

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun PartyListScreen(
    container: AppContainer,
    session: Session,
    onOpen: (String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(PartyFilter.ALL.name) }
    val filter = PartyFilter.valueOf(filterName)
    // Paged by name, searched in SQL; grows as the list scrolls.
    val limit = remember(session.firm.id) { mutableIntStateOf(PAGE_SIZE) }
    val parties by remember(session.firm.id) {
        snapshotFlow { Triple(query, PartyFilter.valueOf(filterName).types, limit.intValue) }
            .flatMapLatest { (q, types, n) -> container.repositories.parties.observePartyPage(session.firm.id, types, q, n) }
    }.collectAsState(initial = null)
    val anyParty by remember(session.firm.id) {
        container.repositories.parties.observePartyPage(session.firm.id, PartyType.entries.toSet(), "", 1)
    }.collectAsState(initial = null)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CollapsingTopBar("Parties", scrollBehavior) },
        snackbarHost = { AppSnackbarHost() },
        floatingActionButton = {
            // With a navigation rail, its header FAB is the screen's one create action.
            if (!LocalWidthClass.current.usesRail) ExtendedFloatingActionButton(
                onClick = { onOpen(null) },
                expanded = !listState.lastScrolledForward || !listState.canScrollBackward,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New party") },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            val filtered = parties
            if (filtered == null || anyParty == null) {
                LoadingState()
                return@CenteredContent
            }
            if (anyParty.isNullOrEmpty()) {
                EmptyState(
                    Icons.Outlined.Person,
                    "No parties yet",
                    "Add the customers and suppliers you bill, with their GSTIN so tax is split correctly.",
                    Modifier.align(androidx.compose.ui.Alignment.Center),
                )
                return@CenteredContent
            }
            GrowLimitOnScroll(listState, filtered.size, limit)
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Spacing.fabClearance)) {
                item(key = "search") { AppSearchBar(query, { query = it; limit.intValue = PAGE_SIZE }, "Search name, phone or GSTIN") }
                item(key = "filters") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pageGutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        PartyFilter.entries.forEach { f -> SelectableChip(f.label, filter == f, { filterName = f.name; limit.intValue = PAGE_SIZE }) }
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") { EmptyState(Icons.Outlined.Search, "No match", "No party matches your search or filter.") }
                }
                items(filtered, key = { it.id }) { party ->
                    ListItem(
                        leadingContent = { Monogram(party.name) },
                        headlineContent = { Text(party.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(party.summaryLine(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = if (party.openingBalance.paise != 0L) {
                            {
                                Text(
                                    Fmt.inr(party.openingBalance) +
                                        if (party.openingBalanceType == OpeningBalanceType.DEBIT) " Dr" else " Cr",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = if (reduced) null else motionTween(false, Motion.SHORT4),
                                placementSpec = if (reduced) null else motionTween(false, Motion.MEDIUM1, Motion.Emphasized),
                                fadeOutSpec = if (reduced) null else motionTween(false, Motion.SHORT3),
                            )
                            .clickable(onClickLabel = "Edit party") { onOpen(party.id) },
                    )
                }
            }
        }
    }
}

data class PartyForm(
    val loaded: Boolean = false,
    val name: String = "",
    val type: PartyType = PartyType.CUSTOMER,
    val phone: String = "",
    val email: String = "",
    val gstin: String = "",
    val stateCode: String? = null,
    /** True while [stateCode] was derived from the GSTIN rather than picked by hand. */
    val stateFromGstin: Boolean = false,
    val addressLine1: String = "",
    val city: String = "",
    val pincode: String = "",
    val openingBalance: String = "",
    val openingType: OpeningBalanceType = OpeningBalanceType.DEBIT,
    val notes: String = "",
    /** Delivery sites; the first is the default ship-to on documents. */
    val shipping: List<ShipForm> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val failure: String? = null,
    /** Bumped on each rejected save so the screen can scroll the first error into view. */
    val rejectedSaves: Int = 0,
)

/** One shipping address with its own GSTIN (blank = unregistered site). */
data class ShipForm(
    val id: String? = null,
    val line1: String = "",
    val city: String = "",
    val pincode: String = "",
    val stateCode: String? = null,
    val gstin: String = "",
)

object PartyRules {
    /** The user-editable part of the form, for "unsaved changes" checks. */
    fun content(form: PartyForm): PartyForm =
        form.copy(loaded = true, stateFromGstin = false, errors = emptyMap(), saving = false, failure = null, rejectedSaves = 0)

    fun hasChanges(baseline: PartyForm?, current: PartyForm): Boolean =
        baseline != null && content(baseline) != content(current)

    fun validate(form: PartyForm): Map<String, String> = buildMap {
        if (form.name.isBlank()) put("name", "Name is required")
        Validation.phoneError(form.phone)?.let { put("phone", it) }
        Validation.gstinError(form.gstin)?.let { put("gstin", it) }
            ?: Validation.gstinStateMismatch(form.gstin, form.stateCode)?.let { put("gstin", it) }
        Validation.moneyError(form.openingBalance)?.let { put("opening", it) }
        val anyAddress = form.addressLine1.isNotBlank() || form.city.isNotBlank()
        if (anyAddress) {
            if (form.addressLine1.isBlank()) put("address", "Add the street address or clear the city")
            if (form.city.isBlank()) put("city", "City is required with an address")
            if (form.stateCode == null) put("state", "Choose a state for the address")
        }
        form.shipping.forEachIndexed { i, s ->
            if (s.line1.isBlank()) put("ship$i.address", "Street address is required")
            if (s.city.isBlank()) put("ship$i.city", "City is required")
            Validation.gstinError(s.gstin)?.let { put("ship$i.gstin", it) }
                ?: Validation.gstinStateMismatch(s.gstin, s.stateCode)?.let { put("ship$i.gstin", it) }
            if (s.stateCode == null) put("ship$i.state", "Choose a state")
        }
    }
}

class PartyEditorModel(
    private val container: AppContainer,
    private val firmId: String,
    private val partyId: String?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PartyForm(loaded = partyId == null))
    val state: StateFlow<PartyForm> = _state
    private var existing: Party? = null

    /** The form as loaded (or blank for a new party); compared against to detect unsaved edits. */
    var baseline: PartyForm? = if (partyId == null) PartyForm(loaded = true) else null
        private set

    fun hasChanges(): Boolean = PartyRules.hasChanges(baseline, _state.value)

    init {
        if (partyId != null) {
            scope.launch {
                val p = container.repositories.parties.findParty(partyId)
                existing = p
                if (p != null) {
                    val a = p.addresses.firstOrNull { it.kind == AddressKind.BILLING } ?: p.addresses.firstOrNull()
                    _state.value = PartyForm(
                        loaded = true,
                        name = p.name,
                        type = p.type,
                        phone = p.phone.orEmpty(),
                        email = p.email.orEmpty(),
                        gstin = p.gstin.orEmpty(),
                        stateCode = p.stateCode,
                        stateFromGstin = p.stateCode != null && p.stateCode == Validation.stateFromGstin(p.gstin.orEmpty()),
                        addressLine1 = a?.line1.orEmpty(),
                        city = a?.city.orEmpty(),
                        pincode = a?.pincode.orEmpty(),
                        openingBalance = if (p.openingBalance.paise == 0L) "" else p.openingBalance.format(),
                        openingType = p.openingBalanceType,
                        notes = p.notes.orEmpty(),
                        shipping = p.addresses.filter { it.kind == AddressKind.SHIPPING }.sortedByDescending { it.isDefault }.map {
                            ShipForm(it.id, it.line1, it.city, it.pincode.orEmpty(), it.stateCode, it.gstin.orEmpty())
                        },
                    )
                    baseline = _state.value
                } else {
                    _state.update { it.copy(loaded = true, failure = "Party not found") }
                }
            }
        }
    }

    fun edit(transform: (PartyForm) -> PartyForm) {
        _state.update { old ->
            val next = transform(old)
            val withState = Validation.syncStateWithGstin(old.stateCode, next.stateCode, old.stateFromGstin, next.gstin)
                .let { (state, derived) -> next.copy(stateCode = state, stateFromGstin = derived) }
            withState.copy(errors = if (old.errors.isEmpty()) emptyMap() else PartyRules.validate(withState))
        }
    }

    /**
     * Moves the party to the recycle bin. Invoices that already use it keep their copy of its
     * details. [onDone] receives the party as it was, so the caller can offer an undo.
     */
    fun archive(onDone: (Party?) -> Unit) {
        val id = partyId ?: return
        _state.update { it.copy(saving = true, failure = null) }
        scope.launch {
            runCatching { container.repositories.parties.archiveParty(id, container.now()) }
                .onSuccess { onDone(existing) }
                .onFailure { e -> _state.update { it.copy(saving = false, failure = e.message ?: "Could not remove") } }
        }
    }

    fun save(onSaved: (Party) -> Unit) {
        val form = _state.value
        val errors = PartyRules.validate(form)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, rejectedSaves = it.rejectedSaves + 1) }
            return
        }
        _state.update { it.copy(saving = true, failure = null) }
        scope.launch {
            runCatching {
                val now = container.now()
                val old = existing
                val oldAddress = old?.addresses?.firstOrNull { it.kind == AddressKind.BILLING }
                val address = if (form.addressLine1.isNotBlank()) {
                    PartyAddress(
                        id = oldAddress?.id ?: container.newId(),
                        kind = AddressKind.BILLING,
                        line1 = form.addressLine1.trim(),
                        city = form.city.trim(),
                        stateCode = form.stateCode!!,
                        pincode = form.pincode.trim().ifBlank { null },
                        isDefault = true,
                    )
                } else {
                    null
                }
                val shipping = form.shipping.mapIndexed { i, s ->
                    PartyAddress(
                        id = s.id ?: container.newId(),
                        kind = AddressKind.SHIPPING,
                        line1 = s.line1.trim(),
                        city = s.city.trim(),
                        stateCode = s.stateCode!!,
                        pincode = s.pincode.trim().ifBlank { null },
                        isDefault = i == 0,
                        gstin = Validation.normalizedGstin(s.gstin),
                    )
                }
                val otherAddresses = shipping + old?.addresses.orEmpty().filter { it.kind == AddressKind.OTHER }
                    .map { it.copy(isDefault = false) }
                val party = Party(
                    id = old?.id ?: container.newId(),
                    firmId = firmId,
                    name = form.name.trim(),
                    type = form.type,
                    phone = form.phone.trim().ifBlank { null },
                    email = form.email.trim().ifBlank { null },
                    gstin = Validation.normalizedGstin(form.gstin),
                    stateCode = form.stateCode,
                    openingBalance = Parse.money(form.openingBalance) ?: Money.ZERO,
                    openingBalanceType = form.openingType,
                    addresses = listOfNotNull(address) + otherAddresses,
                    notes = form.notes.trim().ifBlank { null },
                    creditLimit = old?.creditLimit,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
                container.repositories.parties.saveParty(party)
                party
            }.onSuccess { saved ->
                baseline = _state.value
                onSaved(saved)
            }
                .onFailure { e -> _state.update { it.copy(saving = false, failure = e.message ?: "Could not save") } }
        }
    }
}

/** Shows "Party removed" with an undo that restores the party exactly as it was. */
@Composable
fun rememberPartyRemovedMessage(container: AppContainer): (Party?) -> Unit {
    val messages = LocalAppMessages.current
    return { removed ->
        if (removed != null && messages != null) {
            messages.show("Removed ${removed.name}", actionLabel = "Undo") {
                container.repositories.parties.saveParty(removed.copy(deletedAt = null, updatedAt = container.now()))
            }
        }
    }
}

@Composable
fun PartyEditScreen(
    container: AppContainer,
    session: Session,
    partyId: String?,
    onDone: () -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
    onLedger: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val model = remember(partyId) { PartyEditorModel(container, session.firm.id, partyId, scope) }
    val form by model.state.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(form.rejectedSaves) { if (form.rejectedSaves > 0) scroll.animateScrollTo(0) }
    var pickState by remember { mutableStateOf(false) }
    var pickShipState by remember { mutableStateOf<Int?>(null) }
    val messages = LocalAppMessages.current
    val onRemoved = rememberPartyRemovedMessage(container)
    val dirty = PartyRules.hasChanges(model.baseline, form) && !form.saving
    val requestClose = { if (dirty) confirmDiscard = true else onDone() }
    backHandler(dirty) { confirmDiscard = true }
    val save = {
        model.save { saved ->
            messages?.show(if (partyId == null) "Added ${saved.name}" else "Saved ${saved.name}")
            onDone()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(if (partyId == null) "New party" else "Edit party", onBack = requestClose) {
                if (partyId != null && onLedger != null) {
                    androidx.compose.material3.TextButton(onClick = { onLedger(partyId) }) { Text("Ledger") }
                }
                if (partyId != null) {
                    IconButton(onClick = { confirmRemove = true }, enabled = !form.saving) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove party")
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
                PartyCoreFields(form, model::edit, onPickState = { pickState = true })

                SectionHeader("Billing address")
                FormField("Street address", form.addressLine1, { v -> model.edit { it.copy(addressLine1 = v) } }, error = form.errors["address"])
                FormField("City", form.city, { v -> model.edit { it.copy(city = v) } }, error = form.errors["city"], capitalization = KeyboardCapitalization.Words)
                FormField("PIN code", form.pincode, { v -> model.edit { it.copy(pincode = v.filter(Char::isDigit).take(6)) } }, keyboardType = KeyboardType.Number)

                SectionHeader("Shipping addresses")
                form.shipping.forEachIndexed { i, s ->
                    fun ship(change: (ShipForm) -> ShipForm) = model.edit { f -> f.copy(shipping = f.shipping.toMutableList().also { it[i] = change(it[i]) }) }
                    Text(if (i == 0) "Default delivery site" else "Delivery site ${i + 1}", style = MaterialTheme.typography.labelLarge)
                    FormField("Street address", s.line1, { v -> ship { it.copy(line1 = v) } }, error = form.errors["ship$i.address"])
                    FormField("City", s.city, { v -> ship { it.copy(city = v) } }, error = form.errors["ship$i.city"], capitalization = KeyboardCapitalization.Words)
                    FormField("PIN code", s.pincode, { v -> ship { it.copy(pincode = v.filter(Char::isDigit).take(6)) } }, keyboardType = KeyboardType.Number)
                    FormField(
                        "Ship-to GSTIN", s.gstin,
                        { v -> ship { it.copy(gstin = v.uppercase(), stateCode = Validation.stateFromGstin(v.uppercase()) ?: it.stateCode) } },
                        error = form.errors["ship$i.gstin"], capitalization = KeyboardCapitalization.Characters,
                        supporting = "Leave blank for an unregistered site",
                    )
                    PickerField("State", s.stateCode?.let { Fmt.stateName(it) } ?: "", { pickShipState = i }, error = form.errors["ship$i.state"], placeholder = "Choose a state")
                    TextButton(onClick = { model.edit { f -> f.copy(shipping = f.shipping.filterIndexed { j, _ -> j != i }) } }) { Text("Remove delivery site") }
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { model.edit { it.copy(shipping = it.shipping + ShipForm(stateCode = it.stateCode)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add shipping address") }

                SectionHeader("Opening balance")
                FormField(
                    "Amount",
                    form.openingBalance,
                    { v -> model.edit { it.copy(openingBalance = v) } },
                    error = form.errors["opening"],
                    keyboardType = KeyboardType.Decimal,
                    prefix = "₹",
                    imeAction = ImeAction.Done,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SelectableChip("They owe me (Dr)", form.openingType == OpeningBalanceType.DEBIT, { model.edit { it.copy(openingType = OpeningBalanceType.DEBIT) } })
                    SelectableChip("I owe them (Cr)", form.openingType == OpeningBalanceType.CREDIT, { model.edit { it.copy(openingType = OpeningBalanceType.CREDIT) } })
                }
                SectionHeader("Notes")
                FormField("Notes", form.notes, { v -> model.edit { it.copy(notes = v) } }, singleLine = false)
                if (partyId != null && container.platform.supportsAttachments) app.openbill.ui.invoice.AttachmentsSection(container, partyId)
                form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("Save party", { save() }, Modifier.fillMaxWidth(), busy = form.saving, busyText = "Saving…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this party?") },
            text = { Text("It will no longer appear in lists or pickers. Invoices that already use it are not changed. You can undo this right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    model.archive { removed ->
                        onRemoved(removed)
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
            message = "Your changes to this party have not been saved.",
            onDiscard = { confirmDiscard = false; onDone() },
            onKeepEditing = { confirmDiscard = false },
            onSave = { confirmDiscard = false; save() },
            saveLabel = "Save party",
        )
    }
    if (pickState) {
        StatePickerSheet(
            selected = form.stateCode,
            onPick = { code -> model.edit { it.copy(stateCode = code) }; pickState = false },
            onDismiss = { pickState = false },
        )
    }
    pickShipState?.let { i ->
        StatePickerSheet(
            selected = form.shipping.getOrNull(i)?.stateCode,
            onPick = { code -> model.edit { f -> f.copy(shipping = f.shipping.mapIndexed { j, s -> if (j == i) s.copy(stateCode = code) else s }) }; pickShipState = null },
            onDismiss = { pickShipState = null },
        )
    }
}

/** Name, role, phone, email, GSTIN and state: shared by the full form and the quick add. */
@Composable
fun PartyCoreFields(
    form: PartyForm,
    edit: ((PartyForm) -> PartyForm) -> Unit,
    onPickState: () -> Unit,
    compact: Boolean = false,
) {
    FormField("Name *", form.name, { v -> edit { it.copy(name = v) } }, error = form.errors["name"], capitalization = KeyboardCapitalization.Words)
    FormField("Phone", form.phone, { v -> edit { it.copy(phone = v) } }, error = form.errors["phone"], keyboardType = KeyboardType.Phone)
    if (!compact) {
        FormField("Email", form.email, { v -> edit { it.copy(email = v) } }, keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None)
    }
    FormField(
        "GSTIN",
        form.gstin,
        { v -> edit { it.copy(gstin = v.uppercase()) } },
        error = form.errors["gstin"],
        capitalization = KeyboardCapitalization.Characters,
        supporting = "Optional. A valid GSTIN fills in the state.",
    )
    PickerField(
        "State (place of supply)",
        form.stateCode?.let { Fmt.stateName(it) } ?: "",
        onPickState,
        error = form.errors["state"],
        placeholder = "Choose a state",
    )
    // Role chips come after the text fields so Tab goes Name -> Phone -> ... without detours.
    if (!compact) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PartyType.entries.forEach { t ->
                val label = when (t) {
                    PartyType.CUSTOMER -> "Customer"
                    PartyType.SUPPLIER -> "Supplier"
                    PartyType.BOTH -> "Customer & supplier"
                }
                SelectableChip(label, form.type == t, { edit { it.copy(type = t) } })
            }
        }
    }
}

/**
 * Quick "new party" form used from the invoice party picker. Saves a customer with just the
 * essentials and hands it back so the invoice can use it straight away.
 */
@Composable
fun QuickAddPartyEditor(
    container: AppContainer,
    session: Session,
    visible: Boolean,
    initialName: String,
    onClose: () -> Unit,
    onCreated: (Party) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember(visible) { PartyEditorModel(container, session.firm.id, null, scope) }
    LaunchedEffect(model, initialName) { if (initialName.isNotBlank()) model.edit { it.copy(name = initialName) } }
    val form by model.state.collectAsState()
    var pickState by remember { mutableStateOf(false) }
    app.openbill.ui.components.FullScreenEditor(
        visible = visible,
        title = "New party",
        confirmLabel = "Save",
        onConfirm = { if (!form.saving) model.save(onCreated) },
        onClose = onClose,
    ) {
        FormColumn {
            Text(
                "Add the essentials now; you can fill in the address later from Parties.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PartyCoreFields(form, model::edit, onPickState = { pickState = true }, compact = true)
            form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (pickState) {
        StatePickerSheet(
            selected = form.stateCode,
            onPick = { code -> model.edit { it.copy(stateCode = code) }; pickState = false },
            onDismiss = { pickState = false },
        )
    }
}
