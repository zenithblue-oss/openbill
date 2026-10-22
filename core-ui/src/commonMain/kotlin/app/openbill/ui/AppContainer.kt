package app.openbill.ui

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.usecase.CancelVoucher
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Ulid
import androidx.compose.ui.graphics.ImageBitmap
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.InvoiceStyleController
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.PdfAction
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

    /**
     * Paints laid-out invoice pages to a PDF and runs [action] (share sheet or save dialog,
     * print dialog, save as, open in an external viewer). Returns a message for the user when
     * there is something to report (for example where the file was saved), else null.
     */
    suspend fun invoicePdfAction(document: InvoiceDocument, layout: InvoiceLayout, action: PdfAction): String?

    /** Text measurement with the bundled PDF fonts (Noto Sans), matching the PDF painter. */
    val pdfFontMetrics: FontMetrics

    /** Renders one laid-out page to an image [widthPx] wide, for template previews. */
    suspend fun renderPagePreview(layout: InvoiceLayout, pageIndex: Int, widthPx: Int): ImageBitmap?

    /**
     * Lets the user choose a PNG or JPEG (Android photo picker, desktop file dialog) and returns
     * it downscaled to at most [maxEdgePx] on the long edge and re-encoded as PNG; null if cancelled.
     */
    suspend fun pickImage(maxEdgePx: Int = 600): LogoImage?

    /** Firm images (logo, signature) kept in app-private storage, keyed by firm id. */
    suspend fun loadFirmImage(firmId: String, kind: ImageKind): LogoImage?

    /** Stores [image] for the firm, or deletes it when null. */
    suspend fun saveFirmImage(firmId: String, kind: ImageKind, image: LogoImage?)

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

    /** Per-firm invoice template, accent and print options (device settings, not the database). */
    val invoiceStyles: InvoiceStyleController by lazy { InvoiceStyleController(platform.settingsStore) }

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
