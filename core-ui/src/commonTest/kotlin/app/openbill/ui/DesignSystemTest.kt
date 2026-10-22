package app.openbill.ui

import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.PickerDates
import app.openbill.ui.invoice.LineForm
import app.openbill.ui.invoice.LineRules
import app.openbill.ui.invoice.SalesSummary
import app.openbill.ui.items.ItemForm
import app.openbill.ui.items.ItemRules
import app.openbill.ui.more.SampleData
import app.openbill.ui.nav.BackDispatcher
import app.openbill.ui.nav.Navigator
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.Tab
import app.openbill.ui.nav.WidthClass
import app.openbill.ui.parties.PartyFilter
import app.openbill.ui.parties.PartyForm
import app.openbill.ui.parties.PartyRules
import app.openbill.ui.settings.AppearanceController
import app.openbill.ui.settings.AppearanceSettings
import app.openbill.ui.settings.InMemorySettingsStore
import app.openbill.ui.settings.SettingsStore
import app.openbill.ui.settings.ThemeMode
import app.openbill.ui.setup.FirmSetupRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class DesignSystemTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    // --- Appearance settings ---------------------------------------------------------------

    @Test
    fun appearanceDefaultsFollowSystemWithBrandColours() {
        val s = AppearanceSettings.read(InMemorySettingsStore())
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertFalse(s.useDeviceColors)
        assertTrue(s.isDark(systemDark = true))
        assertFalse(s.isDark(systemDark = false))
    }

    @Test
    fun appearanceRoundTripsThroughTheStore() {
        val store = InMemorySettingsStore()
        val written = AppearanceSettings(ThemeMode.DARK, useDeviceColors = true)
        AppearanceSettings.write(store, written)
        assertEquals(written, AppearanceSettings.read(store))
        assertEquals("DARK", store.getString(AppearanceSettings.KEY_THEME))
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val store = InMemorySettingsStore(
            mapOf(AppearanceSettings.KEY_THEME to "SEPIA", AppearanceSettings.KEY_DEVICE_COLORS to "maybe"),
        )
        assertEquals(AppearanceSettings(), AppearanceSettings.read(store))
    }

    @Test
    fun forcedModesIgnoreTheSystemAndDynamicNeedsPlatformSupport() {
        assertFalse(AppearanceSettings(ThemeMode.LIGHT).isDark(systemDark = true))
        assertTrue(AppearanceSettings(ThemeMode.DARK).isDark(systemDark = false))
        val wantsDevice = AppearanceSettings(useDeviceColors = true)
        assertTrue(wantsDevice.usesDynamicColor(platformSupportsDynamic = true))
        assertFalse(wantsDevice.usesDynamicColor(platformSupportsDynamic = false))
        assertFalse(AppearanceSettings().usesDynamicColor(platformSupportsDynamic = true))
    }

    @Test
    fun controllerPersistsChangesAndSkipsNoOps() {
        var writes = 0
        val backing = InMemorySettingsStore()
        val store = object : SettingsStore {
            override fun getString(key: String) = backing.getString(key)
            override fun putString(key: String, value: String) {
                writes++
                backing.putString(key, value)
            }
        }
        val controller = AppearanceController(store)
        controller.setThemeMode(ThemeMode.LIGHT)
        controller.setUseDeviceColors(true)
        assertEquals(AppearanceSettings(ThemeMode.LIGHT, true), controller.settings.value)
        val afterChanges = writes
        controller.setThemeMode(ThemeMode.LIGHT)
        assertEquals(afterChanges, writes, "setting the same value must not write")
        // A new controller (next app start) sees the persisted values.
        assertEquals(AppearanceSettings(ThemeMode.LIGHT, true), AppearanceController(store).settings.value)
    }

    @Test
    fun controllerSurvivesAFailingStore() {
        val broken = object : SettingsStore {
            override fun getString(key: String): String? = throw IllegalStateException("disk")
            override fun putString(key: String, value: String) = throw IllegalStateException("disk")
        }
        val controller = AppearanceController(broken)
        assertEquals(AppearanceSettings(), controller.settings.value)
        controller.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, controller.settings.value.themeMode)
    }

    // --- Adaptive layout and navigation -----------------------------------------------------

    @Test
    fun widthClassesUseMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(411f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599.9f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840f))
        assertFalse(WidthClass.COMPACT.usesRail)
        assertTrue(WidthClass.MEDIUM.usesRail)
        assertTrue(WidthClass.EXPANDED.usesRail)
    }

    @Test
    fun businessRouteSurvivesStateSavingAndDepthTracksTheStack() {
        assertEquals(Route.BusinessEdit, Route.fromKey(Route.BusinessEdit.key))
        val nav = Navigator()
        assertEquals(1, nav.depth)
        nav.selectTab(Tab.MORE)
        nav.push(Route.BusinessEdit)
        assertEquals(2, nav.depth)
        nav.pop()
        assertEquals(Route.Home(Tab.MORE), nav.current)
    }

    @Test
    fun escapeRunsTheMostRecentEnabledBackHandler() {
        val calls = mutableListOf<String>()
        val back = BackDispatcher()
        assertFalse(back.dispatch(), "nothing registered")
        back.register(true) { calls += "root" }
        val removeEditor = back.register(true) { calls += "editor" }
        back.register(false) { calls += "closed overlay" }
        assertTrue(back.dispatch())
        removeEditor()
        assertTrue(back.dispatch())
        assertEquals(listOf("editor", "root"), calls)
    }

    // --- Formatting -------------------------------------------------------------------------

    @Test
    fun datePickerMillisRoundTripIncludingBefore1970() {
        for (d in listOf(LocalDate(2026, 10, 22), LocalDate(2024, 2, 29), LocalDate(1969, 12, 31), LocalDate(2000, 1, 1))) {
            assertEquals(d, PickerDates.fromUtcMillis(PickerDates.toUtcMillis(d)))
        }
        assertEquals(0L, PickerDates.toUtcMillis(LocalDate(1970, 1, 1)))
        // Any instant within the UTC day maps back to that day.
        assertEquals(LocalDate(1970, 1, 2), PickerDates.fromUtcMillis(86_400_000L + 3_600_000L))
    }

    @Test
    fun displayHelpersForAmountsDatesAndQuantities() {
        assertEquals("22 Oct 2026", Fmt.dateLong(LocalDate(2026, 10, 22)))
        assertEquals("1 Apr 2026", Fmt.dateLong(LocalDate(2026, 4, 1)))
        assertEquals("−₹1,250.00", Fmt.inrDeduction(Money.parse("1250")))
        assertEquals("₹0.00", Fmt.inrDeduction(Money.ZERO))
        assertEquals("2.5 KG", Fmt.qtyUnit(Quantity.parse("2.5"), "KG"))
        assertEquals("3", Fmt.qtyUnit(Quantity.parse("3"), null))
        assertEquals("3", Fmt.qtyUnit(Quantity.parse("3"), " "))
        assertEquals("₹12,34,567.89", Fmt.inr(Money.parse("1234567.89")))
    }

    // --- Unsaved-changes guards -------------------------------------------------------------

    @Test
    fun partyFormChangesIgnoreTransientFields() {
        val base = PartyForm(loaded = true, name = "Lotus Stores")
        assertFalse(PartyRules.hasChanges(base, base.copy(errors = mapOf("x" to "y"), rejectedSaves = 3, failure = "f")))
        assertFalse(PartyRules.hasChanges(base, base.copy(stateFromGstin = true)))
        assertTrue(PartyRules.hasChanges(base, base.copy(phone = "9876543210")))
        assertFalse(PartyRules.hasChanges(null, base.copy(name = "Other")), "nothing loaded yet")
    }

    @Test
    fun itemFormChangesIgnoreTransientFields() {
        val base = ItemForm(loaded = true, name = "Rice", unitId = "u1")
        assertFalse(ItemRules.hasChanges(base, base.copy(saving = true, errors = mapOf("name" to "x"))))
        assertTrue(ItemRules.hasChanges(base, base.copy(gstRate = Rate.ofPercent(5))))
    }

    @Test
    fun businessFormLoadsFromFirmAndAppliesOnlyEditedFields() {
        val gstin = SampleData.demoGstin("29", "AAKFL4821M")
        val firm = Firm(
            id = "f", name = "Old Name", gstin = gstin, stateCode = "29", addressLine1 = "1 Main Rd",
            city = "Mysuru", bankName = "Some Bank", createdAt = at, updatedAt = at,
        )
        val form = FirmSetupRules.formFor(firm, FirmSettings(firmId = "f", invoicePrefix = "MYS"))
        assertEquals("MYS", form.invoicePrefix)
        assertTrue(form.stateFromGstin)
        assertFalse(FirmSetupRules.hasChanges(form, form.copy(errors = mapOf("name" to "x"))))
        val edited = form.copy(name = "New Name", pincode = "570001")
        assertTrue(FirmSetupRules.hasChanges(form, edited))
        val later = Instant.fromEpochMilliseconds(1_800_000_000_000)
        val saved = FirmSetupRules.applyTo(firm, edited, later)
        assertEquals("New Name", saved.name)
        assertEquals("570001", saved.pincode)
        assertEquals("Some Bank", saved.bankName, "fields not on the form are kept")
        assertEquals(later, saved.updatedAt)
        assertEquals(at, saved.createdAt)
    }

    // --- Lists ------------------------------------------------------------------------------

    @Test
    fun partyFilterTreatsBothAsCustomerAndSupplier() {
        fun p(type: PartyType) = Party(id = type.name, firmId = "f", name = type.name, type = type, createdAt = at, updatedAt = at)
        val all = PartyType.entries.map(::p)
        assertEquals(3, all.count(PartyFilter.ALL::matches))
        assertEquals(setOf("CUSTOMER", "BOTH"), all.filter(PartyFilter.CUSTOMERS::matches).map { it.id }.toSet())
        assertEquals(setOf("SUPPLIER", "BOTH"), all.filter(PartyFilter.SUPPLIERS::matches).map { it.id }.toSet())
    }

    @Test
    fun salesSummaryCountsOnlyThisMonthsPostedSales() {
        fun v(id: String, date: LocalDate, status: VoucherStatus, total: String, type: VoucherType = VoucherType.SALE) = Voucher(
            id = id, firmId = "f", financialYearId = "fy", type = type,
            number = if (status == VoucherStatus.DRAFT) null else "INV-$id",
            date = date, partyId = null, status = status, placeOfSupplyStateCode = "29",
            postedAt = if (status != VoucherStatus.DRAFT) at else null,
            cancelledAt = if (status == VoucherStatus.CANCELLED) at else null,
            cancellationReason = if (status == VoucherStatus.CANCELLED) "dup" else null,
            total = Money.parse(total), createdAt = at, updatedAt = at,
        )
        val today = LocalDate(2026, 10, 22)
        val sales = listOf(
            v("1", LocalDate(2026, 10, 1), VoucherStatus.POSTED, "100"),
            v("2", LocalDate(2026, 10, 20), VoucherStatus.POSTED, "250.50"),
            v("3", LocalDate(2026, 9, 30), VoucherStatus.POSTED, "999"),
            v("4", LocalDate(2026, 10, 5), VoucherStatus.CANCELLED, "50"),
            v("5", LocalDate(2026, 10, 6), VoucherStatus.DRAFT, "70"),
            v("6", LocalDate(2025, 10, 6), VoucherStatus.POSTED, "70"),
        )
        val summary = SalesSummary.of(sales, today)
        assertEquals(Money.parse("350.50"), summary.monthTotal)
        assertEquals(2, summary.postedThisMonth)
        assertEquals(1, summary.drafts)
    }

    // --- Line editor preview ----------------------------------------------------------------

    @Test
    fun linePreviewShowsTaxInclusiveTotalOrNullWhileIncomplete() {
        val form = LineForm(description = "Tea", quantity = "2", rate = "100", discount = "10%", gstRate = Rate.ofPercent(5))
        // 200 - 10% = 180 taxable, 5% GST = 9, total 189 (intra-state split does not change the total).
        assertEquals(Money.parse("189"), LineRules.previewTotal(form, "29", "29", RoundingMode.HALF_UP))
        assertEquals(Money.parse("189"), LineRules.previewTotal(form, "29", "27", RoundingMode.HALF_UP))
        assertNull(LineRules.previewTotal(form.copy(rate = "abc"), "29", "29", RoundingMode.HALF_UP))
        assertNull(LineRules.previewTotal(form.copy(description = ""), "29", "29", RoundingMode.HALF_UP))
    }
}
