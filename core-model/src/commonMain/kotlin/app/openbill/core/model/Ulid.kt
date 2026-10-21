package app.openbill.core.model

import kotlin.random.Random

@JvmInline
value class Ulid private constructor(val value: String) : Comparable<Ulid> {
    val timestampMillis: Long
        get() = decodeTimestamp(value)

    override fun compareTo(other: Ulid): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private const val LENGTH = 26
        private const val TIMESTAMP_LENGTH = 10
        private const val RANDOM_BYTES = 10
        private const val MAX_TIMESTAMP = 0xFFFF_FFFF_FFFFL
        private const val MAX_FIRST_CHAR = '7'
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun parse(text: String): Ulid {
            val trimmed = text.trim()
            if (trimmed.length != LENGTH) {
                throw IllegalArgumentException(
                    "ULID must be exactly $LENGTH characters after trimming, " +
                        "got ${trimmed.length}: '$text'",
                )
            }

            val canonical = StringBuilder(LENGTH)
            trimmed.forEachIndexed { index, raw ->
                val upper = asciiUppercase(raw)
                val char = when (upper) {
                    'I', 'L' -> '1'
                    'O' -> '0'
                    else -> upper
                }
                if (alphabetValue(char) < 0) {
                    val detail =
                        if (char == 'U') {
                            " ('U' is excluded from Crockford Base32)"
                        } else {
                            " (expected 0-9 or A-Z excluding I, L, O and U)"
                        }
                    throw IllegalArgumentException(
                        "Invalid ULID '$text': character '$raw' at index $index is not valid " +
                            "Crockford Base32$detail",
                    )
                }
                canonical.append(char)
            }

            val parsed = canonical.toString()
            if (parsed[0] > MAX_FIRST_CHAR) {
                throw IllegalArgumentException(
                    "Invalid ULID '$text': first character '${parsed[0]}' must be 0-7 " +
                        "because the timestamp is limited to 48 bits",
                )
            }
            return Ulid(parsed)
        }

        fun isValid(text: String): Boolean = tryParse(text) != null

        fun tryParse(text: String): Ulid? =
            try {
                parse(text)
            } catch (_: IllegalArgumentException) {
                null
            }

        fun fromParts(epochMillis: Long, random: ByteArray): Ulid {
            require(epochMillis in 0..MAX_TIMESTAMP) {
                "ULID timestamp must be between 0 and $MAX_TIMESTAMP, got $epochMillis"
            }
            require(random.size == RANDOM_BYTES) {
                "ULID randomness must be exactly $RANDOM_BYTES bytes, got ${random.size}"
            }

            val chars = CharArray(LENGTH)
            var remainingTimestamp = epochMillis
            for (index in TIMESTAMP_LENGTH - 1 downTo 0) {
                chars[index] = ALPHABET[(remainingTimestamp and 0x1F).toInt()]
                remainingTimestamp = remainingTimestamp ushr 5
            }

            var high = 0L
            for (index in 0 until 5) {
                high = (high shl 8) or (random[index].toLong() and 0xFF)
            }
            for (index in 0 until 8) {
                chars[TIMESTAMP_LENGTH + index] =
                    ALPHABET[((high ushr (35 - index * 5)) and 0x1F).toInt()]
            }

            var low = 0L
            for (index in 5 until RANDOM_BYTES) {
                low = (low shl 8) or (random[index].toLong() and 0xFF)
            }
            for (index in 0 until 8) {
                chars[TIMESTAMP_LENGTH + 8 + index] =
                    ALPHABET[((low ushr (35 - index * 5)) and 0x1F).toInt()]
            }

            return Ulid(chars.concatToString())
        }

        fun create(epochMillis: Long, random: Random = Random.Default): Ulid {
            val bytes = ByteArray(RANDOM_BYTES)
            random.nextBytes(bytes)
            return fromParts(epochMillis, bytes)
        }

        private fun decodeTimestamp(canonical: String): Long {
            var timestamp = 0L
            for (index in 0 until TIMESTAMP_LENGTH) {
                timestamp = (timestamp shl 5) or alphabetValue(canonical[index]).toLong()
            }
            return timestamp
        }

        private fun asciiUppercase(char: Char): Char =
            if (char in 'a'..'z') char - ('a' - 'A') else char

        private fun alphabetValue(char: Char): Int = ALPHABET.indexOf(char)
    }
}
