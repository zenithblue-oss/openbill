package app.openbill.core.data

internal object AuditDetails {
    fun json(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${render(value)}"
        }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
}
