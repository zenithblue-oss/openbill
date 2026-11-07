package app.openbill.core.domain.csv

/** One contact from an address-book export: a full name and its first phone and email. */
data class Contact(val name: String, val phone: String?, val email: String?)

/**
 * vCard (.vcf, versions 2.1 to 4.0) reader for contact import: FN (or N), the first TEL and the
 * first EMAIL of each card. Folded lines are joined and escapes undone; QUOTED-PRINTABLE names
 * (old phone exports) are decoded as UTF-8. Cards without a name are skipped.
 */
object VCard {
    fun parse(text: String): List<Contact> {
        // Unfold: a line starting with a space or tab continues the previous one.
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val out = ArrayList<Contact>()
        var name: String? = null
        var structured: String? = null
        var phone: String? = null
        var email: String? = null
        var inCard = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.equals("BEGIN:VCARD", true)) {
                inCard = true; name = null; structured = null; phone = null; email = null
                continue
            }
            if (!inCard) continue
            if (line.equals("END:VCARD", true)) {
                val n = (name ?: structured)?.trim()?.takeIf { it.isNotEmpty() }
                if (n != null) out += Contact(n.take(100), phone, email)
                inCard = false
                continue
            }
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val head = line.substring(0, colon)
            val params = head.split(';')
            val key = params.first().substringAfter('.').uppercase() // "item1.TEL" grouping
            val qp = params.any { it.equals("ENCODING=QUOTED-PRINTABLE", true) }
            val value = line.substring(colon + 1).let { if (qp) quotedPrintable(it) else it }
            when (key) {
                "FN" -> if (name == null) name = unescape(value)
                "N" -> if (structured == null) structured = value.split(';').let { p ->
                    listOf(p.getOrNull(3), p.getOrNull(1), p.getOrNull(2), p.getOrNull(0)).mapNotNull { it?.let(::unescape)?.trim()?.ifEmpty { null } }.joinToString(" ")
                }
                "TEL" -> if (phone == null) phone = value.removePrefix("tel:").filter { it.isDigit() || it == '+' }.takeIf { it.length >= 6 }
                "EMAIL" -> if (email == null) email = value.trim().takeIf { '@' in it }
            }
        }
        return out
    }

    private fun unescape(v: String) = v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun quotedPrintable(v: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        val s = v.removeSuffix("=")
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '=' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
                continue
            }
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}
