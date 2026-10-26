package app.openbill.ui.items

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HsnCodesTest {
    @Test
    fun searchByCodeAndWords() {
        assertTrue(HsnCodes.all.all { it.first.length in 4..8 && it.first.all(Char::isDigit) && it.second.isNotBlank() })
        assertEquals(HsnCodes.all.size, HsnCodes.all.map { it.first }.toSet().size)
        assertEquals("Mobile phones and network equipment", HsnCodes.search("8517").single().second)
        assertEquals(listOf("9983", "998311", "998313", "998314", "998315"), HsnCodes.search("99831").map { it.first })
        assertEquals("0902", HsnCodes.search("tea").first().first)
        assertTrue(HsnCodes.search("it consulting").any { it.first == "998313" })
    }
}
