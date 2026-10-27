package app.openbill.ui

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.ui.format.AmountInWords
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.forms.Validation
import app.openbill.ui.more.SampleData
import app.openbill.ui.parties.PartyForm
import app.openbill.ui.parties.PartyRules
import app.openbill.ui.setup.FirmSetupRules
import app.openbill.ui.setup.SetupForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class FormsTest {
    private val validGstin = SampleData.demoGstin("29", "AAKFL4821M")

    @Test
    fun gstinValidationUsesChecksumAndImpliesState() {
        assertNull(Validation.gstinError(validGstin))
        assertNull(Validation.gstinError(""))
        assertEquals("29", Validation.stateFromGstin(validGstin))
        val broken = validGstin.dropLast(1) + if (validGstin.last() == 'A') 'B' else 'A'
        assertNotNull(Validation.gstinError(broken))
        assertNotNull(Validation.gstinStateMismatch(validGstin, "27"))
        assertNull(Validation.gstinStateMismatch(validGstin, "29"))
    }

    @Test
    fun stateFollowsGstinCorrectionsButKeepsManualChoice() {
        val mh = SampleData.demoGstin("27", "AAKFL4821M")
        // Empty state is filled from a valid GSTIN.
        assertEquals("29" to true, Validation.syncStateWithGstin(null, null, false, validGstin))
        // A derived state follows a GSTIN that is retyped character by character.
        var state: Pair<String?, Boolean> = "29" to true
        for (i in 0..mh.length) {
            state = Validation.syncStateWithGstin(state.first, state.first, state.second, mh.take(i))
        }
        assertEquals("27" to true, state)
        // A state picked by hand is kept even when the GSTIN changes later.
        val picked = Validation.syncStateWithGstin("27", "33", true, mh)
        assertEquals("33" to false, picked)
        assertEquals("33" to false, Validation.syncStateWithGstin("33", "33", picked.second, validGstin))
    }

    @Test
    fun setupModelFormRetypedGstinMovesAutoFilledState() {
        var form = SetupForm()
        fun type(text: String) {
            val old = form
            val next = old.copy(gstin = text)
            val (s, d) = Validation.syncStateWithGstin(old.stateCode, next.stateCode, old.stateFromGstin, next.gstin)
            form = next.copy(stateCode = s, stateFromGstin = d)
        }
        validGstin.indices.forEach { type(validGstin.take(it + 1)) }
        assertEquals("29", form.stateCode)
        type("")
        val other = SampleData.demoGstin("33", "AAKFL4821M")
        other.indices.forEach { type(other.take(it + 1)) }
        assertEquals("33", form.stateCode)
    }

    @Test
    fun defaultUnitSeedingResumesAfterPartialRun() {
        val t = Instant.fromEpochMilliseconds(0)
        val nos = app.openbill.core.domain.model.UnitOfMeasure("u1", "f1", "Numbers", "NOS", 0, t, t)
        val missing = FirmSetupRules.missingDefaultUnits(listOf(nos)).map { it.second }
        assertEquals(listOf("PCS", "KG", "LTR", "MTR", "BOX", "HRS"), missing)
        assertEquals(FirmSetupRules.DEFAULT_UNITS.size, FirmSetupRules.missingDefaultUnits(emptyList()).size)
        val all = FirmSetupRules.DEFAULT_UNITS.mapIndexed { i, (n, sym, dp) ->
            app.openbill.core.domain.model.UnitOfMeasure("u$i", "f1", n, sym, dp, t, t)
        }
        assertTrue(FirmSetupRules.missingDefaultUnits(all).isEmpty())
    }

    @Test
    fun setupRequiresNameStateAddressCity() {
        val errors = FirmSetupRules.validate(SetupForm())
        assertTrue(errors.keys.containsAll(listOf("name", "state", "address", "city")))
        val ok = FirmSetupRules.validate(
            SetupForm(name = "A", stateCode = "29", addressLine1 = "x", city = "y", gstin = validGstin),
        )
        assertTrue(ok.isEmpty(), ok.toString())
    }

    @Test
    fun financialYearRunsAprilToMarch() {
        assertEquals(2026, FirmSetupRules.fyStartYearFor(LocalDate(2026, 10, 22)))
        assertEquals(2025, FirmSetupRules.fyStartYearFor(LocalDate(2026, 3, 31)))
        val fy = FirmSetupRules.financialYear("fy", "firm", 2026)
        assertEquals(LocalDate(2026, 4, 1), fy.startDate)
        assertEquals(LocalDate(2027, 3, 31), fy.endDate)
    }

    @Test
    fun partyAddressNeedsStateWhenGiven() {
        assertTrue(PartyRules.validate(PartyForm(name = "X")).isEmpty())
        val e = PartyRules.validate(PartyForm(name = "X", addressLine1 = "1 Road"))
        assertTrue("city" in e && "state" in e)
        assertTrue("opening" in PartyRules.validate(PartyForm(name = "X", openingBalance = "12.345")))
    }

    @Test
    fun shippingAddressNeedsStreetCityStateAndAValidShipToGstin() {
        val ok = app.openbill.ui.parties.ShipForm(line1 = "Plot 4", city = "Pune", stateCode = "27")
        assertTrue(PartyRules.validate(PartyForm(name = "X", shipping = listOf(ok))).isEmpty())
        val e = PartyRules.validate(PartyForm(name = "X", shipping = listOf(ok, app.openbill.ui.parties.ShipForm(gstin = "BAD"))))
        assertTrue(listOf("ship1.address", "ship1.city", "ship1.gstin", "ship1.state").all { it in e }, e.toString())
    }

    @Test
    fun indianFormattingAndWords() {
        assertEquals("₹1,23,45,678.90", Fmt.inr(Money.parse("12345678.90")))
        assertEquals("₹999.00", Fmt.inr(Money.parse("999")))
        assertEquals("0.25", Fmt.percent(Rate.ofBasisPoints(25)))
        assertEquals("18", Fmt.percent(Rate.ofPercent(18)))
        assertEquals("22-10-2026", Fmt.date(LocalDate(2026, 10, 22)))
        assertEquals("One Lakh Twenty Three Thousand Four Hundred Fifty Six Rupees and Seven Paise Only", AmountInWords.rupees(Money.parse("123456.07")))
        assertEquals("Two Crore Rupees Only", AmountInWords.rupees(Money.parse("20000000")))
        assertEquals(LocalDate(2026, 4, 1), Parse.date("01-04-2026"))
        assertEquals(Rate.ofBasisPoints(1250), Parse.percent("12.5"))
    }
}
