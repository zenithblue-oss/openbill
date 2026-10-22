package app.openbill.ui.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.DiscardChangesDialog
import app.openbill.ui.components.FormColumn
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PrimaryButton
import app.openbill.ui.components.pageGutter
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.launch

/** Edits the existing business with the same form and rules as first-run setup. */
class BusinessEditModel(
    private val container: AppContainer,
    private val firmId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SetupForm?>(null)
    val state: StateFlow<SetupForm?> = _state
    var baseline: SetupForm? = null
        private set
    private var firm: Firm? = null
    private var settings: FirmSettings? = null

    /**
     * True once any invoice has been posted. Stored invoices do not record the seller's state,
     * so changing it afterwards would re-split their tax heads on screen; the state is locked.
     */
    var stateLocked: Boolean = true
        private set

    init {
        scope.launch {
            val repos = container.repositories.firms
            val f = repos.findFirm(firmId) ?: return@launch
            firm = f
            settings = repos.findSettings(firmId)
            stateLocked = container.repositories.vouchers
                .observeVouchers(firmId, LocalDate(2000, 1, 1), LocalDate(2099, 12, 31))
                .first()
                .any { it.status != VoucherStatus.DRAFT }
            val form = FirmSetupRules.formFor(f, settings)
            baseline = form
            _state.value = form
        }
    }

    val originalStateCode: String? get() = firm?.stateCode

    fun edit(transform: (SetupForm) -> SetupForm) {
        _state.update { old ->
            old?.let { o ->
                val next = FirmSetupRules.applyEdit(o, transform)
                if (stateLocked) next.copy(stateCode = firm?.stateCode ?: next.stateCode) else next
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val form = _state.value ?: return
        val f = firm ?: return
        val errors = FirmSetupRules.validate(form)
        if (errors.isNotEmpty()) {
            _state.update { it?.copy(errors = errors, rejectedSubmits = it.rejectedSubmits + 1) }
            return
        }
        _state.update { it?.copy(saving = true, failure = null) }
        scope.launch {
            runCatching {
                val repos = container.repositories.firms
                repos.saveFirm(FirmSetupRules.applyTo(f, form, container.now()))
                val s = settings ?: FirmSettings(firmId = firmId)
                repos.saveSettings(s.copy(invoicePrefix = form.invoicePrefix.trim()))
            }.onSuccess {
                baseline = _state.value?.copy(saving = false)
                onSaved()
            }.onFailure { e ->
                _state.update { it?.copy(saving = false, failure = e.message ?: "Could not save") }
            }
        }
    }
}

@Composable
fun BusinessEditScreen(
    container: AppContainer,
    session: Session,
    onDone: () -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val model = remember(session.firm.id) { BusinessEditModel(container, session.firm.id, scope) }
    val state by model.state.collectAsState()
    var pickState by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val messages = LocalAppMessages.current
    val form = state
    LaunchedEffect(form?.rejectedSubmits) { if ((form?.rejectedSubmits ?: 0) > 0) scroll.animateScrollTo(0) }
    val dirty = form != null && !form.saving && FirmSetupRules.hasChanges(model.baseline, form)
    val requestClose = { if (dirty) confirmDiscard = true else onDone() }
    backHandler(dirty) { confirmDiscard = true }
    val save = {
        model.save {
            messages?.show("Business details saved")
            onDone()
        }
    }

    Scaffold(topBar = { AppTopBar("Business details", onBack = requestClose) }) { padding ->
        if (form == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            FormColumn(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = pageGutter)) {
                Text(
                    "These details are printed on every new invoice and PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FirmImagePicker(container, session.firm.id, ImageKind.LOGO, "Business logo", "PNG or JPG, shown on invoice PDFs. Saved right away.")
                FirmImagePicker(container, session.firm.id, ImageKind.SIGNATURE, "Signature", "A scan of the authorised signature, ideally on white.")
                BusinessFields(
                    form,
                    model::edit,
                    onPickState = { pickState = true },
                    onPickYear = null,
                    stateLockedReason = if (model.stateLocked) "Locked: posted invoices already use this state" else null,
                )
                if (form.stateCode != null && form.stateCode != model.originalStateCode) {
                    Text(
                        "Changing the state changes how GST is split (CGST + SGST or IGST) on invoices you create from now on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton("Save details", { save() }, Modifier.fillMaxWidth(), busy = form.saving, busyText = "Saving…")
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
    if (pickState) {
        StatePickerSheet(form?.stateCode, onPick = { code -> model.edit { it.copy(stateCode = code) }; pickState = false }, onDismiss = { pickState = false })
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            message = "Your changes to the business details have not been saved.",
            onDiscard = { confirmDiscard = false; onDone() },
            onKeepEditing = { confirmDiscard = false },
            onSave = { confirmDiscard = false; save() },
            saveLabel = "Save details",
        )
    }
}
