package app.openbill.core.tax

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GstinTest {
    @Test
    fun knownValidGstinsPassAndCarryTheirChecksum() {
        val cases = listOf(
            "27AAPFU0939F1ZV" to IndianState.MAHARASHTRA,
            "29AABCT1332L1ZA" to IndianState.KARNATAKA,
            "24AAACC1206D1ZM" to IndianState.GUJARAT,
            "27AAACR5055K1Z7" to IndianState.MAHARASHTRA,
            "29AAACI1195H1ZI" to IndianState.KARNATAKA,
            "99AAACR5055KAZN" to IndianState.CENTRE_JURISDICTION,
            "07AABCU9603R1ZP" to IndianState.DELHI,
        )

        for ((gstin, state) in cases) {
            assertTrue(Gstin.isValid(gstin), "expected $gstin to be valid")
            val parsed = Gstin.parse(gstin)
            assertEquals(gstin, parsed.value)
            assertEquals(state, parsed.stateCode)
            assertEquals(gstin.substring(2, 12), parsed.pan)
            assertEquals(gstin[14], Gstin.checksum(gstin.substring(0, 14)))
        }
    }

    @Test
    fun publishedSampleWithMismatchedChecksumIsRejected() {
        assertEquals('A', Gstin.checksum("29AABCT1332L1Z"))
        assertEquals(GstinError.CHECKSUM, invalidReason("29AABCT1332L1ZM"))
        assertTrue(Gstin.isValid("29AABCT1332L1ZA"))
    }

    @Test
    fun normalizationTrimsUppercasesAndRemovesOnlyAsciiSpacesAndHyphens() {
        assertEquals("27AAPFU0939F1ZV", Gstin.normalize(" 27aa-pfu-0939f-1zv "))
        assertEquals("27AAPFU0939F1ZV", Gstin.normalize("27 AAP FU 0939 F 1 ZV"))
        assertEquals("27_AAPFU", Gstin.normalize("27_aapfu"))
        assertEquals("27\tAPFU", Gstin.normalize("27\tapfu"))
        assertEquals("27APFUÄ", Gstin.normalize("27apfuä"))
    }

    @Test
    fun lowercaseAndSeparatedGstinsAreAcceptedAndNormalized() {
        val parsed = Gstin.parse(" 27-aa-pfu-0939-f-1-zv ")
        assertEquals("27AAPFU0939F1ZV", parsed.value)
        assertEquals("27AAPFU0939F1ZV", parsed.toString())
        assertEquals(IndianState.MAHARASHTRA, parsed.stateCode)
        assertEquals("AAPFU0939F", parsed.pan)
    }

    @Test
    fun extractsPanAndStateFromParsedGstin() {
        val parsed = Gstin.parse("29AABCT1332L1ZA")
        assertEquals(IndianState.KARNATAKA, parsed.stateCode)
        assertEquals("AABCT1332L", parsed.pan)
    }

    @Test
    fun reportsLengthFailures() {
        assertEquals(GstinError.LENGTH, invalidReason(""))
        assertEquals(GstinError.LENGTH, invalidReason("   "))
        assertEquals(GstinError.LENGTH, invalidReason("27AAPFU0939F1Z"))
        assertEquals(GstinError.LENGTH, invalidReason("27AAPFU0939F1ZVX"))
        assertEquals(GstinError.LENGTH, invalidReason("27-AAPFU-0939F-1Z"))
        assertEquals(GstinError.LENGTH, invalidReason("A".repeat(1_000)))
    }

    @Test
    fun reportsCharacterFailures() {
        assertEquals(GstinError.CHARACTERS, invalidReason("27AAPFU0939F1@V"))
        assertEquals(GstinError.CHARACTERS, invalidReason("@7AAPFU0939F1ZV"))
        assertEquals(GstinError.CHARACTERS, invalidReason("27AAPFU0939F1ZΩ"))
        assertEquals(GstinError.CHARACTERS, invalidReason("27AAPFU0939F1\tV"))
        assertEquals(GstinError.CHARACTERS, invalidReason("27AAPFU0939F1Z\u0000"))
    }

    @Test
    fun reportsNonAsciiInputWithoutThrowing() {
        assertEquals(GstinError.LENGTH, invalidReason(""))
        assertEquals(GstinError.CHARACTERS, invalidReason("２７AAPFU0939F1ZV"))
        assertEquals(GstinError.CHARACTERS, invalidReason("27AAPFU0939F1ＺV"))
    }

    @Test
    fun reportsUnknownStateCode() {
        assertEquals(GstinError.STATE_CODE, invalidReason("00AAPFU0939F1ZV"))
        assertEquals(GstinError.STATE_CODE, invalidReason("98AAPFU0939F1ZV"))
        assertEquals(GstinError.STATE_CODE, invalidReason("39AAPFU0939F1ZV"))
        assertEquals(GstinError.STATE_CODE, invalidReason("AAAAPFU0939F1ZV"))
    }

    @Test
    fun reportsPanFormatFailures() {
        assertEquals(GstinError.PAN_FORMAT, invalidReason("27AAPFU09391FZV"))
        assertEquals(GstinError.PAN_FORMAT, invalidReason("27AAPF0O939F1ZV"))
        assertEquals(GstinError.PAN_FORMAT, invalidReason("27AAPFUO939F1ZV"))
        assertEquals(GstinError.PAN_FORMAT, invalidReason("271APFU0939F1ZV"))
    }

    @Test
    fun reportsEntityCodeFailures() {
        assertEquals(GstinError.ENTITY_CODE, invalidReason("27AAPFU0939F*ZV"))
        assertEquals(GstinError.ENTITY_CODE, invalidReason("27AAPFU0939F_ZV"))
    }

    @Test
    fun reportsNonZDefaultCharacter() {
        assertEquals(GstinError.DEFAULT_Z, invalidReason("27AAPFU0939F1AV"))
        assertEquals(GstinError.DEFAULT_Z, invalidReason("27AAPFU0939F1YV"))
    }

    @Test
    fun reportsChecksumFailures() {
        assertEquals(GstinError.CHECKSUM, invalidReason("27AAPFU0939F1ZW"))
        assertEquals(GstinError.CHECKSUM, invalidReason("27AAPFU0939F1Z0"))
        assertEquals(GstinError.CHECKSUM, invalidReason("24AAACC1206D1ZA"))
    }

    @Test
    fun validateReturnsTypedResultsInsteadOfThrowingForUserInput() {
        assertIs<GstinValidation.Valid>(Gstin.validate("27AAPFU0939F1ZV"))
        assertIs<GstinValidation.Invalid>(Gstin.validate("not a gstin"))
        assertIs<GstinValidation.Invalid>(Gstin.validate("😀"))
        assertIs<GstinValidation.Invalid>(Gstin.validate("A".repeat(10_000)))
    }

    @Test
    fun parseThrowsHelpfulIllegalArgumentForInvalidInput() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Gstin.parse("27AAPFU0939F1ZW")
        }
        assertTrue(exception.message!!.contains("CHECKSUM"))
        assertTrue(exception.message!!.contains("27AAPFU0939F1ZW"))

        val lengthException = assertFailsWith<IllegalArgumentException> {
            Gstin.parse("short")
        }
        assertTrue(lengthException.message!!.contains("LENGTH"))
    }

    @Test
    fun checksumMatchesReferenceVectors() {
        assertEquals('V', Gstin.checksum("27AAPFU0939F1Z"))
        assertEquals('M', Gstin.checksum("24AAACC1206D1Z"))
        assertEquals('7', Gstin.checksum("27AAACR5055K1Z"))
        assertEquals('I', Gstin.checksum("29AAACI1195H1Z"))
        assertEquals('N', Gstin.checksum("99AAACR5055KAZ"))
        assertEquals('P', Gstin.checksum("07AABCU9603R1Z"))
    }

    @Test
    fun checksumWrapsToZeroWhenTheSumIsAMultipleOfTheBase() {
        assertEquals('0', Gstin.checksum("00000000000000"))
    }

    @Test
    fun entityCodeAcceptsOneThroughNineAndLetters() {
        assertTrue(Gstin.isValid("27AAPFU0939F1ZV"))
        assertTrue(Gstin.isValid("27AAPFU0939FAZM"))
        assertTrue(Gstin.isValid("27AAPFU0939FZZX"))
        assertEquals(GstinError.ENTITY_CODE, invalidReason("27AAPFU0939F0ZW"))
    }

    @Test
    fun checksumRequiresExactlyFourteenNormalizedCharacters() {
        assertFailsWith<IllegalArgumentException> { Gstin.checksum("") }
        assertFailsWith<IllegalArgumentException> { Gstin.checksum("27AAPFU0939F1") }
        assertFailsWith<IllegalArgumentException> { Gstin.checksum("27AAPFU0939F1ZV") }
        assertFailsWith<IllegalArgumentException> { Gstin.checksum("27aapfu0939f1z") }
        assertFailsWith<IllegalArgumentException> { Gstin.checksum("27AAPFU-939F1Z") }
    }

    @Test
    fun fiftySeededGstinsRoundTripAndRejectAChecksumFlip() {
        val random = Random(20261021)
        val states = IndianState.entries
        val entityCharacters = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val generated = mutableSetOf<String>()

        repeat(50) {
            val state = states[random.nextInt(states.size)]
            val pan = buildString {
                repeat(5) { append('A' + random.nextInt(26)) }
                repeat(4) { append('0' + random.nextInt(10)) }
                append('A' + random.nextInt(26))
            }
            val entity = entityCharacters[random.nextInt(entityCharacters.length)]
            val first14 = state.code + pan + entity + "Z"
            val gstin = first14 + Gstin.checksum(first14)
            generated += gstin

            val parsed = Gstin.parse(gstin)
            assertEquals(gstin, parsed.value)
            assertEquals(state, parsed.stateCode)
            assertEquals(pan, parsed.pan)
            assertEquals(gstin, Gstin.parse(gstin.lowercase()).value)
            assertEquals(
                gstin,
                Gstin.parse(gstin.lowercase().chunked(5).joinToString(" - ")).value,
            )

            val flipped = gstin.dropLast(1) + if (gstin.last() == '0') '1' else '0'
            assertEquals(GstinError.CHECKSUM, invalidReason(flipped))
        }

        assertEquals(50, generated.size)
    }

    @Test
    fun arbitraryFifteenCharacterStringsAreNotAccepted() {
        val samples = listOf(
            "AAAAAAAAAAAAAAA",
            "000000000000000",
            "123456789012345",
            "ABCDEFGHIJKLMNO",
            "27AAAAAAAAAAAAA",
            "27AAPFU0939F1ZZ",
            "27AAPFU0939F1Z0",
            "999999999999999",
        )
        for (sample in samples) {
            assertFalse(Gstin.isValid(sample), "unexpectedly accepted $sample")
        }

        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = Random(8675309)
        repeat(1_000) {
            val candidate = buildString {
                repeat(15) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            assertFalse(Gstin.isValid(candidate), "unexpectedly accepted $candidate")
        }
    }

    private fun invalidReason(raw: String): GstinError {
        val result = Gstin.validate(raw)
        assertIs<GstinValidation.Invalid>(result)
        return result.reason
    }
}
