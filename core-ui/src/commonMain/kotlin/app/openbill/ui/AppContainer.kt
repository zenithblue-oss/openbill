package app.openbill.ui

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.usecase.CancelVoucher
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Ulid
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.settings.AppearanceController
import app.openbill.ui.settings.SettingsStore
import app.openbill.ui.theme.defaultSettingsStore
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Platform services the shared UI needs but cannot implement itself. */
interface PlatformServices {
    /** True for debug builds; gates developer-only actions such as sample data. */
    val isDebugBuild: Boolean

    /** Renders the invoice to a PDF and opens the platform share/save flow. */
    suspend fun shareInvoicePdf(document: InvoiceDocument)

    val appVersion: String

    /** Per-device UI preferences (theme). Platforms override this with persistent storage. */
    val settingsStore: SettingsStore get() = defaultSettingsStore()
}

/** Manual dependency container. One instance per process. */
class AppContainer(
    val repositories: Repositories,
    val platform: PlatformServices,
    val clock: Clock = Clock.System,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val postVoucher = PostVoucher(repositories.vouchers)
    val cancelVoucher = CancelVoucher(repositories.vouchers)

    /** Theme preferences, read once and persisted on change. */
    val appearance: AppearanceController by lazy { AppearanceController(platform.settingsStore) }

    /** Serializes first-run setup with default seeding so they never race. */
    val setupLock = Mutex()

    fun now(): Instant = clock.now()

    fun today(): LocalDate = clock.now().toLocalDateTime(timeZone).date

    fun newId(): String = Ulid.create(clock.now().toEpochMilliseconds()).toString()
}

/** The active business and its configuration, loaded once the firm exists. */
data class Session(
    val firm: Firm,
    val settings: FirmSettings,
    val financialYears: List<FinancialYear>,
) {
    fun financialYearFor(date: LocalDate): FinancialYear? =
        financialYears.firstOrNull { date >= it.startDate && date <= it.endDate }
}
