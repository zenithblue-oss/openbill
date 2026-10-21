package app.openbill.core.tax

@JvmInline
value class Gstin private constructor(val value: String) {
    val stateCode: IndianState
        get() = requireNotNull(IndianState.fromCode(value.substring(0, 2))) {
            "GSTIN has an unknown state code: $value"
        }

    val pan: String
        get() = value.substring(2, 12)

    override fun toString(): String = value

    companion object {
        private const val LENGTH = 15
        private const val FIRST_14_LENGTH = 14
        private const val BASE = 36
        private const val STATE_CODE_LENGTH = 2
        private const val PAN_START = 2
        private const val PAN_END = 12
        private const val ENTITY_INDEX = 12
        private const val DEFAULT_Z_INDEX = 13
        private const val CHECKSUM_INDEX = 14
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val PAN_DIGIT_START = 7
        private const val PAN_LETTER_START = 11

        fun normalize(raw: String): String =
            raw.trim()
                .uppercase()
                .filter { char -> char != ' ' && char != '-' }

        fun validate(raw: String): GstinValidation {
            val normalized = normalize(raw)
            if (normalized.length != LENGTH) {
                return GstinValidation.Invalid(GstinError.LENGTH)
            }

            val invalidIndexes = normalized.indices.filter { index ->
                !isAsciiAlphanumeric(normalized[index])
            }
            if (invalidIndexes.isNotEmpty()) {
                val reason = if (invalidIndexes.all { index -> index == ENTITY_INDEX }) {
                    GstinError.ENTITY_CODE
                } else {
                    GstinError.CHARACTERS
                }
                return GstinValidation.Invalid(reason)
            }

            if (IndianState.fromCode(normalized.substring(0, STATE_CODE_LENGTH)) == null) {
                return GstinValidation.Invalid(GstinError.STATE_CODE)
            }

            if (!hasValidPan(normalized)) {
                return GstinValidation.Invalid(GstinError.PAN_FORMAT)
            }

            val entity = normalized[ENTITY_INDEX]
            if (entity !in '1'..'9' && entity !in 'A'..'Z') {
                return GstinValidation.Invalid(GstinError.ENTITY_CODE)
            }

            if (normalized[DEFAULT_Z_INDEX] != 'Z') {
                return GstinValidation.Invalid(GstinError.DEFAULT_Z)
            }

            val expected = checksum(normalized.substring(0, FIRST_14_LENGTH))
            if (normalized[CHECKSUM_INDEX] != expected) {
                return GstinValidation.Invalid(GstinError.CHECKSUM)
            }

            return GstinValidation.Valid(Gstin(normalized))
        }

        fun isValid(raw: String): Boolean = validate(raw) is GstinValidation.Valid

        fun parse(raw: String): Gstin =
            when (val result = validate(raw)) {
                is GstinValidation.Valid -> result.gstin
                is GstinValidation.Invalid ->
                    throw IllegalArgumentException(
                        "Invalid GSTIN '$raw' (normalized '${normalize(raw)}'): ${result.reason}",
                    )
            }

        fun checksum(first14: String): Char {
            require(first14.length == FIRST_14_LENGTH) {
                "GSTIN checksum input must be exactly $FIRST_14_LENGTH characters, " +
                    "got ${first14.length}"
            }

            var sum = 0
            first14.forEachIndexed { index, char ->
                val value = requireNotNull(charValue(char)) {
                    "GSTIN checksum input must be normalized uppercase letters and digits, " +
                        "found '$char' at index $index"
                }
                val factor = if (index % 2 == 0) 1 else 2
                val product = value * factor
                sum += product / BASE + product % BASE
            }
            return ALPHABET[(BASE - sum % BASE) % BASE]
        }

        private fun hasValidPan(normalized: String): Boolean {
            for (index in PAN_START until PAN_END) {
                val char = normalized[index]
                val valid = when (index) {
                    in PAN_START until PAN_DIGIT_START -> char in 'A'..'Z'
                    in PAN_DIGIT_START until PAN_LETTER_START -> char in '0'..'9'
                    else -> char in 'A'..'Z'
                }
                if (!valid) return false
            }
            return true
        }

        private fun isAsciiAlphanumeric(char: Char): Boolean =
            char in '0'..'9' || char in 'A'..'Z'

        private fun charValue(char: Char): Int? =
            when (char) {
                in '0'..'9' -> char - '0'
                in 'A'..'Z' -> char - 'A' + 10
                else -> null
            }
    }
}
