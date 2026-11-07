package app.openbill.core.domain.model

object VoucherNumbering {
    /** GST: an invoice (or note) number is at most 16 characters, unique per financial year. */
    const val MAX_LENGTH = 16

    fun format(prefix: String, sequence: Long, padding: Int): String {
        require(prefix.isNotBlank()) { "Number prefix cannot be blank" }
        require(sequence > 0L) { "Number sequence must be positive" }
        require(padding in 1..12) { "Number padding must be between 1 and 12" }

        val base = prefix.trimEnd()
        val separator = if (base.last().isLetterOrDigit()) "-" else ""
        val number = base + separator + sequence.toString().padStart(padding, '0')
        require(number.length <= MAX_LENGTH) {
            "Document number $number is ${number.length} characters; GST allows at most $MAX_LENGTH. Use a shorter prefix in settings"
        }
        return number
    }

    /**
     * The prefix a synced device numbers with (docs/10-server.md 6.5): its [series] appended,
     * "INV" becomes "INV-B", "INV/" becomes "INV/B". A standalone install ([series] null) keeps [base].
     */
    fun seriesPrefix(base: String, series: String?): String {
        val trimmed = base.trimEnd()
        return when {
            series == null -> trimmed
            trimmed.lastOrNull()?.isLetterOrDigit() == true -> "$trimmed-$series"
            else -> trimmed + series
        }
    }
}
