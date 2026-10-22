package app.openbill.core.domain.model

object VoucherNumbering {
    fun format(prefix: String, sequence: Long, padding: Int): String {
        require(prefix.isNotBlank()) { "Number prefix cannot be blank" }
        require(sequence > 0L) { "Number sequence must be positive" }
        require(padding in 1..12) { "Number padding must be between 1 and 12" }

        val base = prefix.trimEnd()
        val separator = if (base.last().isLetterOrDigit()) "-" else ""
        return base + separator + sequence.toString().padStart(padding, '0')
    }
}
