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

/** RFC 4180 CSV. */
object Csv {
    fun row(cells: List<String?>): String = cells.joinToString(",") { c ->
        val v = c ?: ""
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val c = t[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
