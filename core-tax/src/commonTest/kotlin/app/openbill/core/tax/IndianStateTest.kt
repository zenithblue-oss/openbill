package app.openbill.core.tax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndianStateTest {
    @Test
    fun everyStateHasATwoDigitCodeAndRoundTrips() {
        assertEquals(40, IndianState.entries.size)

        for (state in IndianState.entries) {
            assertEquals(2, state.code.length)
            assertTrue(state.code.all { it in '0'..'9' }, "code ${state.code} must be digits")
            assertEquals(state, IndianState.fromCode(state.code))
        }
    }

    @Test
    fun codesCoverAllRequestedStatesAndUnionTerritories() {
        val expectedCodes = (1..38).map { number -> number.toString().padStart(2, '0') } +
            listOf("97", "99")

        assertEquals(expectedCodes.toSet(), IndianState.entries.map { it.code }.toSet())
    }

    @Test
    fun mapsLegacyCurrentAndSpecialCodes() {
        assertEquals(IndianState.DAMAN_AND_DIU_LEGACY, IndianState.fromCode("25"))
        assertEquals(
            IndianState.DADRA_AND_NAGAR_HAVELI_AND_DAMAN_AND_DIU,
            IndianState.fromCode("26"),
        )
        assertEquals(IndianState.MAHARASHTRA, IndianState.fromCode("27"))
        assertEquals(IndianState.ANDHRA_PRADESH_LEGACY, IndianState.fromCode("28"))
        assertEquals(IndianState.TELANGANA, IndianState.fromCode("36"))
        assertEquals(IndianState.ANDHRA_PRADESH, IndianState.fromCode("37"))
        assertEquals(IndianState.LADAKH, IndianState.fromCode("38"))
        assertEquals(IndianState.OTHER_TERRITORY, IndianState.fromCode("97"))
        assertEquals(IndianState.CENTRE_JURISDICTION, IndianState.fromCode("99"))
    }

    @Test
    fun unknownOrMalformedCodesReturnNull() {
        assertNull(IndianState.fromCode("00"))
        assertNull(IndianState.fromCode("98"))
        assertNull(IndianState.fromCode(""))
        assertNull(IndianState.fromCode("7"))
        assertNull(IndianState.fromCode("027"))
        assertNull(IndianState.fromCode(" 27"))
        assertNull(IndianState.fromCode("27 "))
        assertNull(IndianState.fromCode("aa"))
    }

    @Test
    fun usesOfficialDisplayNames() {
        assertEquals("Jammu and Kashmir", IndianState.fromCode("01")?.displayName)
        assertEquals("Odisha", IndianState.fromCode("21")?.displayName)
        assertEquals("Puducherry", IndianState.fromCode("34")?.displayName)
        assertEquals(
            "Andaman and Nicobar Islands",
            IndianState.fromCode("35")?.displayName,
        )
        assertEquals(
            "Dadra and Nagar Haveli and Daman and Diu",
            IndianState.fromCode("26")?.displayName,
        )
        assertEquals("Daman and Diu", IndianState.fromCode("25")?.displayName)
        assertEquals("Andhra Pradesh", IndianState.fromCode("28")?.displayName)
        assertEquals("Andhra Pradesh", IndianState.fromCode("37")?.displayName)
        assertEquals("Other Territory", IndianState.fromCode("97")?.displayName)
        assertEquals("Centre Jurisdiction", IndianState.fromCode("99")?.displayName)
    }
}
