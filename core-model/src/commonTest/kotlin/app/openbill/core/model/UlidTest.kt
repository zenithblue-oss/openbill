package app.openbill.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UlidTest {
    @Test
    fun canonicalVectorRoundTrips() {
        val ulid = Ulid.parse("01ARYZ6S41TSV4RRFFQ69G5FAV")

        assertEquals("01ARYZ6S41TSV4RRFFQ69G5FAV", ulid.value)
        assertEquals("01ARYZ6S41TSV4RRFFQ69G5FAV", ulid.toString())
        assertEquals(1469918176385L, ulid.timestampMillis)
        assertEquals(ulid, Ulid.fromParts(1469918176385L, KNOWN_RANDOM))
    }

    @Test
    fun publishedSampleVectorUsesItsEncodedTimestamp() {
        val ulid = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")

        assertEquals(1469922850259L, ulid.timestampMillis)
        assertEquals(ulid, Ulid.fromParts(1469922850259L, KNOWN_RANDOM))
    }

    @Test
    fun lowercaseInputIsNormalizedToCanonicalUppercase() {
        val canonical = Ulid.fromParts(1469918176385L, KNOWN_RANDOM)

        assertEquals(canonical, Ulid.parse("01aryz6s41tsv4rrffq69g5fav"))
        assertEquals(canonical, Ulid.parse(" 01aryz6s41tsv4rrffq69g5fav "))
        assertEquals(canonical, Ulid.parse("01ARYZ6S41TSV4RRFFQ69G5FAV"))
    }

    @Test
    fun crockfordAliasesAreAcceptedAndCanonicalized() {
        val canonical = Ulid.fromParts(1469918176385L, KNOWN_RANDOM)

        assertEquals(canonical, Ulid.parse("OIARYZ6S4LTSV4RRFFQ69G5FAV"))
        assertEquals(canonical, Ulid.parse("oiaryz6s41tsv4rrffq69g5fav"))
        assertEquals(Ulid.parse("0".repeat(26)), Ulid.parse("O".repeat(26)))
        assertEquals(Ulid.parse("1".repeat(26)), Ulid.parse("I".repeat(26)))
        assertEquals(Ulid.parse("1".repeat(26)), Ulid.parse("L".repeat(26)))
    }

    @Test
    fun invalidLengthsAreRejectedWithUsefulMessages() {
        for (text in listOf("", "   ", "short", "0".repeat(25), "0".repeat(27), "01ARZ3NDEKTSV4RRFFQ69G5FA")) {
            val exception = assertInvalid(text)
            assertTrue(exception.message!!.contains("26"), "unexpected message: ${exception.message}")
        }
    }

    @Test
    fun invalidAlphabetCharactersAreRejected() {
        val invalid = listOf(
            "01ARZ3NDEKTSV4RRFFQ69G5FAU",
            "U1ARZ3NDEKTSV4RRFFQ69G5FAV",
            "01ARZ3NDEKTSV4RRFFQ69G5FA!",
            "01ARZ3NDEKTSV4RRFFQ69G5F-V",
            "01ARZ3NDEKTSV4RRFFQ69G5FA_",
            "01ARZ3NDEKTSV4RRFFQ69G5FÄV",
        )

        for (text in invalid) {
            assertInvalid(text)
        }

        val exception = assertInvalid("01ARZ3NDEKTSV4RRFFQ69G5FAU")
        assertTrue(exception.message!!.contains("'U'"))
    }

    @Test
    fun firstCharacterAboveSevenIsRejected() {
        for (first in listOf('8', '9', 'A', 'Z')) {
            val exception = assertInvalid("${first}1ARZ3NDEKTSV4RRFFQ69G5FAV")
            assertTrue(
                exception.message!!.contains("first character"),
                "unexpected message: ${exception.message}",
            )
        }

        assertTrue(Ulid.isValid("71ARZ3NDEKTSV4RRFFQ69G5FAV"))
    }

    @Test
    fun parseRejectsTimestampsBeyondFortyEightBits() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Ulid.fromParts(1L shl 48, ByteArray(10))
        }
        assertTrue(exception.message!!.contains("0"))
        assertTrue(exception.message!!.contains((1L shl 48).toString()))

        assertFailsWith<IllegalArgumentException> {
            Ulid.fromParts(-1L, ByteArray(10))
        }
    }

    @Test
    fun timestampBoundsAreEncodedCanonically() {
        val zero = Ulid.fromParts(0L, ByteArray(10))
        assertEquals("00000000000000000000000000", zero.value)
        assertEquals(0L, zero.timestampMillis)

        val maxTimestamp = 0xFFFF_FFFF_FFFFL
        val max = Ulid.fromParts(maxTimestamp, ByteArray(10))
        assertEquals("7ZZZZZZZZZ0000000000000000", max.value)
        assertEquals(maxTimestamp, max.timestampMillis)
        assertEquals(max, Ulid.parse(max.value))
    }

    @Test
    fun fromPartsRequiresExactlyTenRandomBytes() {
        for (size in listOf(0, 9, 11, 16)) {
            assertFailsWith<IllegalArgumentException> {
                Ulid.fromParts(0L, ByteArray(size))
            }
        }
    }

    @Test
    fun fromPartsCopiesRandomBytes() {
        val random = ByteArray(10) { index -> (index * 17).toByte() }
        val ulid = Ulid.fromParts(1_469_918_176_385L, random)
        val canonical = ulid.value

        random.fill(0xFF.toByte())

        assertEquals(canonical, ulid.value)
        assertEquals(1_469_918_176_385L, ulid.timestampMillis)
    }

    @Test
    fun createIsDeterministicWithSeededRandom() {
        val first = Ulid.create(1_469_918_176_385L, Random(20261021))
        val second = Ulid.create(1_469_918_176_385L, Random(20261021))

        assertEquals(first, second)
        assertEquals(first.value, second.value)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1_469_918_176_385L, first.timestampMillis)
        assertTrue(first.value.first() in '0'..'7')
    }

    @Test
    fun twoGeneratedIdsDiffer() {
        val first = Ulid.create(1_469_918_176_385L)
        val second = Ulid.create(1_469_918_176_385L)

        assertNotEquals(first, second)
        assertNotEquals(first.value, second.value)
        assertEquals(first.timestampMillis, second.timestampMillis)
    }

    @Test
    fun orderingMatchesCanonicalStringOrder() {
        val sameTimeLow = Ulid.fromParts(1_000L, ByteArray(10))
        val sameTimeHigh = Ulid.fromParts(1_000L, ByteArray(10).also { it[9] = 1 })
        val later = Ulid.fromParts(1_001L, ByteArray(10))

        assertTrue(sameTimeLow < sameTimeHigh)
        assertTrue(sameTimeHigh < later)

        val expected = listOf(sameTimeLow, sameTimeHigh, later)
        val shuffled = listOf(later, sameTimeHigh, sameTimeLow)

        assertEquals(expected, shuffled.sorted())
        assertEquals(expected.map { it.value }, shuffled.map { it.value }.sorted())
    }

    @Test
    fun thousandSeededRoundTripsPreserveTimestampAndCanonicalString() {
        val random = Random(20261021)
        val seen = mutableSetOf<String>()

        repeat(1_000) {
            val epochMillis = random.nextLong(0L, 1L shl 48)
            val generated = Ulid.create(epochMillis, random)
            val parsed = Ulid.parse(generated.value)

            assertEquals(epochMillis, generated.timestampMillis)
            assertEquals(generated.value, parsed.value)
            assertEquals(epochMillis, parsed.timestampMillis)
            assertEquals(generated, parsed)
            assertEquals(generated.hashCode(), parsed.hashCode())
            assertEquals(26, generated.value.length)
            assertEquals(generated.value.uppercase(), generated.value)
            assertTrue(generated.value.first() in '0'..'7')
            seen += generated.value
        }

        assertEquals(1_000, seen.size)
    }

    @Test
    fun tryParseReturnsNullAndIsValidReturnsFalseForInvalidInput() {
        for (text in listOf("", "not-a-ulid", "81ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAU")) {
            assertNull(Ulid.tryParse(text))
            assertFalse(Ulid.isValid(text))
        }

        val valid = "01ARYZ6S41TSV4RRFFQ69G5FAV"
        assertTrue(Ulid.isValid(" $valid "))
        assertEquals(Ulid.parse(valid), Ulid.tryParse(valid))
    }

    private fun assertInvalid(text: String): IllegalArgumentException {
        val exception = assertFailsWith<IllegalArgumentException>("expected '$text' to be rejected") {
            Ulid.parse(text)
        }
        assertTrue(exception.message!!.contains(text), "message should quote input: ${exception.message}")
        assertFalse(Ulid.isValid(text))
        assertNull(Ulid.tryParse(text))
        return exception
    }

    private companion object {
        val KNOWN_RANDOM: ByteArray = byteArrayOf(
            0xD6.toByte(),
            0x76,
            0x4C,
            0x61,
            0xEF.toByte(),
            0xB9.toByte(),
            0x93.toByte(),
            0x02,
            0xBD.toByte(),
            0x5B,
        )
    }
}
