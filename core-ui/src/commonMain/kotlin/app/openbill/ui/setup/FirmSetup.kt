package app.openbill.ui.setup

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.ui.AppContainer
import app.openbill.ui.forms.Validation
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.LogoImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

data class SetupForm(
    val name: String = "",
    val stateCode: String? = null,
    /** True while [stateCode] was derived from the GSTIN rather than picked by hand. */
    val stateFromGstin: Boolean = false,
    val gstin: String = "",
    val addressLine1: String = "",
    val city: String = "",
    val pincode: String = "",
    val phone: String = "",
    val invoicePrefix: String = "INV",
    val fyStartYear: Int = 0,
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val failure: String? = null,
    /** Bumped on each rejected submit so the screen can bring the first error into view. */
    val rejectedSubmits: Int = 0,
)

object FirmSetupRules {
    const val FY_START_MONTH = 4

    fun fyStartYearFor(date: LocalDate, startMonth: Int = FY_START_MONTH): Int {
        val month = date.toString().substring(5, 7).toInt()
        return if (month >= startMonth) date.year else date.year - 1
    }

    fun financialYear(id: String, firmId: String, startYear: Int, startMonth: Int = FY_START_MONTH): FinancialYear {
        val start = LocalDate(startYear, startMonth, 1)
        val end = LocalDate(startYear + 1, startMonth, 1).minus(1, DateTimeUnit.DAY)
        return FinancialYear(id = id, firmId = firmId, startDate = start, endDate = end)
    }

    /** Applies an edit, keeping the state in step with the GSTIN and re-validating after a failed submit. */
    fun applyEdit(old: SetupForm, transform: (SetupForm) -> SetupForm): SetupForm {
        val next = transform(old)
        // A valid GSTIN implies the state; fill it in when the user has not chosen one.
        val withState = Validation.syncStateWithGstin(old.stateCode, next.stateCode, old.stateFromGstin, next.gstin)
            .let { (state, derived) -> next.copy(stateCode = state, stateFromGstin = derived) }
        return withState.copy(errors = if (old.errors.isEmpty()) emptyMap() else validate(withState))
    }

    /** The user-editable part of the form, for "unsaved changes" checks. */
    fun content(form: SetupForm): SetupForm =
        form.copy(stateFromGstin = false, errors = emptyMap(), saving = false, failure = null, rejectedSubmits = 0)

    fun hasChanges(baseline: SetupForm?, current: SetupForm): Boolean =
        baseline != null && content(baseline) != content(current)

    /** Form pre-filled from an existing business, for More > Edit business details. */
    fun formFor(firm: Firm, settings: FirmSettings?): SetupForm = SetupForm(
        name = firm.name,
        stateCode = firm.stateCode,
        stateFromGstin = firm.gstin?.let { Validation.stateFromGstin(it) } == firm.stateCode,
        gstin = firm.gstin.orEmpty(),
        addressLine1 = firm.addressLine1,
        city = firm.city,
        pincode = firm.pincode.orEmpty(),
        phone = firm.phone.orEmpty(),
        invoicePrefix = settings?.invoicePrefix ?: "INV",
    )

    /** Applies the edited details to the stored business, leaving every other field untouched. */
    fun applyTo(firm: Firm, form: SetupForm, now: kotlin.time.Instant): Firm = firm.copy(
        name = form.name.trim(),
        gstin = Validation.normalizedGstin(form.gstin),
        stateCode = form.stateCode ?: firm.stateCode,
        addressLine1 = form.addressLine1.trim(),
        city = form.city.trim(),
        pincode = form.pincode.trim().ifBlank { null },
        phone = form.phone.trim().ifBlank { null },
        updatedAt = now,
    )

    fun validate(form: SetupForm): Map<String, String> = buildMap {
        if (form.name.isBlank()) put("name", "Business name is required")
        if (form.stateCode == null) put("state", "Choose the state you are registered in")
        if (form.addressLine1.isBlank()) put("address", "Address is required for invoices")
        if (form.city.isBlank()) put("city", "City is required")
        Validation.gstinError(form.gstin)?.let { put("gstin", it) }
            ?: Validation.gstinStateMismatch(form.gstin, form.stateCode)?.let { put("gstin", it) }
        Validation.phoneError(form.phone)?.let { put("phone", it) }
        if (form.invoicePrefix.isBlank()) put("prefix", "Prefix is required")
        else if (form.invoicePrefix.trim().length > 12) put("prefix", "Keep the prefix under 12 characters")
    }

    /** Default units as (name, symbol, decimal places). */
    val DEFAULT_UNITS = listOf(
        Triple("Numbers", "NOS", 0),
        Triple("Pieces", "PCS", 0),
        Triple("Kilograms", "KG", 3),
        Triple("Litres", "LTR", 3),
        Triple("Metres", "MTR", 2),
        Triple("Boxes", "BOX", 0),
        Triple("Hours", "HRS", 2),
    )

    /** Default units not yet present (matched by name or symbol), so seeding can resume after an interruption. */
    fun missingDefaultUnits(existing: List<UnitOfMeasure>): List<Triple<String, String, Int>> {
        val names = existing.map { it.name.lowercase() }.toSet()
        val symbols = existing.map { it.symbol.uppercase() }.toSet()
        return DEFAULT_UNITS.filter { (name, symbol, _) -> name.lowercase() !in names && symbol !in symbols }
    }

    fun defaultUnits(firmId: String, container: AppContainer, existing: List<UnitOfMeasure> = emptyList()): List<UnitOfMeasure> {
        val now = container.now()
        return missingDefaultUnits(existing).map { (name, symbol, dp) ->
            UnitOfMeasure(container.newId(), firmId, name, symbol, dp, now, now)
        }
    }
}

class FirmSetupModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SetupForm(fyStartYear = FirmSetupRules.fyStartYearFor(container.today())))
    val state: StateFlow<SetupForm> = _state

    /** Optional images held until the business id exists; written on save. */
    val logo = MutableStateFlow<LogoImage?>(null)
    val signature = MutableStateFlow<LogoImage?>(null)

    fun edit(transform: (SetupForm) -> SetupForm) {
        _state.update { old -> FirmSetupRules.applyEdit(old, transform) }
    }

    fun save() {
        val form = _state.value
        val errors = FirmSetupRules.validate(form)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, rejectedSubmits = it.rejectedSubmits + 1) }
            return
        }
        _state.update { it.copy(saving = true, failure = null) }
        // NonCancellable: saving the firm makes the setup screen leave composition, which cancels
        // its scope; the remaining first-run seeding must still complete.
        scope.launch(NonCancellable) {
            runCatching {
                val newFirmId = container.newId()
                val firmId = container.setupLock.withLock {
                    val repos = container.repositories
                    val now = container.now()
                    repos.firms.saveFirm(
                        Firm(
                            id = newFirmId,
                            name = form.name.trim(),
                            gstin = Validation.normalizedGstin(form.gstin),
                            stateCode = form.stateCode!!,
                            addressLine1 = form.addressLine1.trim(),
                            city = form.city.trim(),
                            pincode = form.pincode.trim().ifBlank { null },
                            phone = form.phone.trim().ifBlank { null },
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    repos.firms.saveSettings(
                        FirmSettings(firmId = newFirmId, invoicePrefix = form.invoicePrefix.trim()),
                    )
                    repos.firms.saveFinancialYear(
                        FirmSetupRules.financialYear(container.newId(), newFirmId, form.fyStartYear),
                    )
                    newFirmId
                }
                logo.value?.let { container.platform.saveFirmImage(firmId, ImageKind.LOGO, it) }
                signature.value?.let { container.platform.saveFirmImage(firmId, ImageKind.SIGNATURE, it) }
                if (logo.value != null || signature.value != null) container.invoiceStyles.imageVersion.value++
                ensureDefaults(container, firmId)
                container.activeFirm.select(firmId)
            }.onFailure { e ->
                _state.update { it.copy(saving = false, failure = e.message ?: "Could not save") }
            }
        }
    }

    companion object {
        /** Idempotent defaults needed by the billing screens. */
        suspend fun ensureDefaults(container: AppContainer, firmId: String) = container.setupLock.withLock {
            val repos = container.repositories
            // Seed per unit, not "only when empty": an interrupted first run must be able to finish.
            val existingUnits = repos.catalog.observeUnits(firmId).first()
            FirmSetupRules.defaultUnits(firmId, container, existingUnits).forEach { repos.catalog.saveUnit(it) }
            // Make sure today's financial year exists so invoices can always be dated today.
            val today = container.today()
            val years = repos.firms.observeFinancialYears(firmId).first()
            if (years.none { today >= it.startDate && today <= it.endDate }) {
                val startMonth = repos.firms.findSettings(firmId)?.financialYearStartMonth ?: FirmSetupRules.FY_START_MONTH
                repos.firms.saveFinancialYear(
                    FirmSetupRules.financialYear(
                        container.newId(),
                        firmId,
                        FirmSetupRules.fyStartYearFor(today, startMonth),
                        startMonth,
                    ),
                )
            }
            repos.ledger.ensureSystemAccounts(firmId, container.now())
        }
    }
}
