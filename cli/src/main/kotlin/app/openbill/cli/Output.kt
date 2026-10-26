package app.openbill.cli

import app.openbill.core.model.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val prettyJson = Json { prettyPrint = true }

fun CliState.printJson(element: JsonElement) = out.println(prettyJson.encodeToString(JsonElement.serializer(), element))

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Money -> JsonPrimitive(v.format())
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> kotlinx.serialization.json.JsonArray(v.map(::toJson))
    else -> JsonPrimitive(v.toString())
}

/** A column of a human table; [right] aligns numbers. */
class Col(val title: String, val right: Boolean = false)

fun CliState.printTable(cols: List<Col>, rows: List<List<String>>) {
    if (rows.isEmpty()) {
        info("(none)")
        return
    }
    val widths = cols.indices.map { i -> maxOf(cols[i].title.length, rows.maxOf { it[i].length }) }
    fun line(cells: List<String>) = cells.indices.joinToString("  ") { i ->
        if (cols[i].right) cells[i].padStart(widths[i]) else cells[i].padEnd(widths[i])
    }.trimEnd()
    out.println(line(cols.map { it.title }))
    out.println(widths.joinToString("  ") { "-".repeat(it) })
    rows.forEach { out.println(line(it)) }
}

/** Key/value block for `show` commands. */
fun CliState.printFields(fields: List<Pair<String, Any?>>) {
    val w = fields.maxOf { it.first.length }
    fields.forEach { (k, v) -> out.println("${k.padEnd(w)}  ${v ?: "-"}") }
}

/** Indian digit grouping for tables: 12,34,567.00 */
fun Money.indian(): String {
    val s = format()
    val neg = s.startsWith("-")
    val (int, frac) = s.removePrefix("-").split('.').let { it[0] to it.getOrElse(1) { "00" } }
    val grouped = if (int.length <= 3) int else {
        val head = int.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + int.takeLast(3)
    }
    return (if (neg) "-" else "") + grouped + "." + frac
}
