package app.openbill.core.domain.report

import app.openbill.core.model.Money

enum class ColumnKind { TEXT, DATE, MONEY, QUANTITY, NUMBER }

data class ReportColumn(val key: String, val label: String, val kind: ColumnKind = ColumnKind.TEXT) {
    val numeric: Boolean get() = kind == ColumnKind.MONEY || kind == ColumnKind.QUANTITY || kind == ColumnKind.NUMBER
}

/**
 * Output of every report: plain string cells so table, CSV, JSON and PDF render the same
 * values. Money cells are decimal strings with two places and a leading minus ("-12.50"),
 * dates are ISO (YYYY-MM-DD), empty means "no value".
 */
data class ReportTable(
    val title: String,
    val columns: List<ReportColumn>,
    val rows: List<List<String>>,
    val totals: List<String>? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(rows.all { it.size == columns.size }) { "Every report row needs ${columns.size} cells" }
        require(totals == null || totals.size == columns.size) { "Totals need ${columns.size} cells" }
    }

    /** RFC 4180, CRLF line ends, header row; the totals row (if any) comes last. */
    fun toCsv(): String = buildString {
        (listOf(columns.map { it.label }) + rows + listOfNotNull(totals)).forEach { append(csvRow(it)).append("\r\n") }
    }

    /** `{"title", "columns": [{key,label,kind}], "rows": [{key: value}], "totals": {...}}`; money as strings. */
    fun toJson(extra: Map<String, Any?> = emptyMap()): String {
        fun obj(cells: List<String>) = columns.indices.associate { columns[it].key to cells[it] }
        val doc = linkedMapOf<String, Any?>("title" to title)
        doc.putAll(extra)
        doc["columns"] = columns.map { linkedMapOf("key" to it.key, "label" to it.label, "kind" to it.kind.name.lowercase()) }
        doc["rows"] = rows.map(::obj)
        doc["totals"] = totals?.let(::obj)
        if (notes.isNotEmpty()) doc["notes"] = notes
        return JsonText.write(doc)
    }

    companion object {
        fun csvRow(cells: List<String>): String = cells.joinToString(",") { v ->
            if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
    }
}

/** Money cell text; zero prints as "0.00". */
internal fun Money.cell(): String = format()

/** Money cell that is blank when zero (debit/credit columns). */
internal fun Money.cellOrBlank(): String = if (paise == 0L) "" else format()

/**
 * Tiny deterministic JSON writer (2-space indent, insertion order). Maps, lists, strings,
 * booleans, Int/Long and [Num] (a pre-formatted number such as "1180.00") are supported.
 */
object JsonText {
    class Num(val text: String)

    fun write(value: Any?): String = StringBuilder().also { write(value, 0, it) }.append('\n').toString()

    private fun write(value: Any?, indent: Int, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Num -> sb.append(value.text)
            is String -> string(value, sb)
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Map<*, *> -> container(value.entries.toList(), '{', '}', indent, sb) { e, b ->
                e as Map.Entry<*, *>
                string(e.key.toString(), b)
                b.append(": ")
                write(e.value, indent + 1, b)
            }
            is List<*> -> container(value, '[', ']', indent, sb) { e, b -> write(e, indent + 1, b) }
            else -> string(value.toString(), sb)
        }
    }

    private inline fun <T> container(items: List<T>, open: Char, close: Char, indent: Int, sb: StringBuilder, each: (T, StringBuilder) -> Unit) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open).append('\n')
        items.forEachIndexed { i, item ->
            repeat(indent + 1) { sb.append("  ") }
            each(item, sb)
            if (i < items.lastIndex) sb.append(',')
            sb.append('\n')
        }
        repeat(indent) { sb.append("  ") }
        sb.append(close)
    }

    private fun string(s: String, sb: StringBuilder) {
        sb.append('"')
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
