package app.openbill.core.domain.csv

import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

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

/** Dry-run result: every valid row, and every invalid row's 1-based line number and reason. */
class CsvImport<T>(
    val valid: List<T>,
    val errors: List<Pair<Int, String>>,
    /** Opening stock by item id, from an item import's optional opening columns. */
    val openings: Map<String, OpeningStock> = emptyMap(),
) {
    val rows: Int get() = valid.size + errors.size
}

/** Opening stock of one imported item; [godownId] null means the business's default godown. */
data class OpeningStock(val quantity: Quantity, val unitCost: Money, val godownId: String?, val batch: String?, val expiry: LocalDate?)

/**
 * Parties and items CSV import (CLI and app). Validates every row and writes nothing; the caller
 * writes [CsvImport.valid] only when [CsvImport.errors] is empty (all rows or none).
 */
object MasterCsv {
    val PARTY_COLUMNS = listOf("name", "type", "phone", "email", "gstin", "state_code")
    val ITEM_COLUMNS = listOf("name", "sku", "unit", "hsn_sac", "gst_rate", "sale_price", "purchase_price", "is_service")

    /** Optional item import columns: opening stock posted as one adjustment (not exported). */
    val OPENING_COLUMNS = listOf("opening_qty", "opening_cost", "godown", "batch", "expiry")

    private val GSTIN = Regex("^[0-9]{2}[A-Z0-9]{10}[0-9A-Z]Z[0-9A-Z]$")

    fun validateParty(p: Party) {
        require(p.gstin == null || GSTIN.matches(p.gstin!!)) { "Invalid GSTIN '${p.gstin}'" }
        require(p.stateCode == null || Regex("^[0-9]{2}$").matches(p.stateCode!!)) { "State code must be two digits" }
    }

    /** Percent text ("18", "0.25") to a [Rate]; same 4-decimal scale as [Quantity]. */
    fun parseRate(text: String): Rate = Rate.ofBasisPointsHundredths(Quantity.parse(text.trim().removeSuffix("%")).scaledUnits)

    fun parseMoney(text: String): Money = runCatching { Money.parse(text.trim()) }
        .getOrElse { throw IllegalArgumentException("Invalid amount '$text' (use a decimal like 1500.00)") }

    fun parties(text: String, firmId: String, newId: () -> String, now: Instant): CsvImport<Party> =
        rows(text) { col ->
            Party(
                id = newId(), firmId = firmId, name = col("name") ?: throw IllegalArgumentException("name is required"),
                type = col("type")?.let { t -> PartyType.entries.firstOrNull { it.name.equals(t, true) } ?: throw IllegalArgumentException("type must be customer, supplier, or both") } ?: PartyType.CUSTOMER,
                phone = col("phone"), email = col("email"), gstin = col("gstin")?.uppercase(),
                stateCode = col("state_code") ?: col("gstin")?.take(2), createdAt = now, updatedAt = now,
            ).also(::validateParty)
        }

    fun items(
        text: String,
        firmId: String,
        units: List<UnitOfMeasure>,
        existing: List<Item>,
        newId: () -> String,
        now: Instant,
        godowns: List<Godown> = emptyList(),
    ): CsvImport<Item> {
        val seenSku = existing.mapNotNull { it.sku?.lowercase() }.toHashSet()
        val openings = HashMap<String, OpeningStock>()
        val result = rows(text) { col ->
            val unit = col("unit")
            val u = if (unit == null) units.firstOrNull() ?: throw IllegalArgumentException("Firm has no units")
            else units.firstOrNull { it.symbol.equals(unit, true) || it.name.equals(unit, true) } ?: throw IllegalArgumentException("No unit '$unit'")
            val sku = col("sku")
            require(sku == null || seenSku.add(sku.lowercase())) { "SKU '$sku' already exists" }
            val service = col("is_service")?.lowercase() in setOf("true", "yes", "1")
            val gst = col("gst_rate") ?: "0"
            val qty = col("opening_qty")?.let { q ->
                runCatching { Quantity.parse(q) }.getOrNull()?.takeIf { it > Quantity.of(0) } ?: throw IllegalArgumentException("opening_qty must be a number above zero")
            }
            val batch = col("batch")
            val expiry = col("expiry")?.let { e -> runCatching { LocalDate.parse(e) }.getOrElse { throw IllegalArgumentException("expiry must be a date like 2027-03-31") } }
            if (qty == null) require(OPENING_COLUMNS.none { col(it) != null }) { "Opening columns need opening_qty" }
            else require(!service) { "A service has no opening stock" }
            require(expiry == null || batch != null) { "expiry needs a batch" }
            require(batch == null || batch.length <= 40) { "batch is at most 40 characters" }
            val godown = col("godown")?.let { g -> godowns.firstOrNull { it.deletedAt == null && it.name.equals(g, true) } ?: throw IllegalArgumentException("No godown '$g'") }
            val purchase = parseMoney(col("purchase_price") ?: "0")
            val cost = col("opening_cost")?.let(::parseMoney) ?: purchase
            require(cost >= Money.ZERO) { "opening_cost cannot be negative" }
            Item(
                id = newId(), firmId = firmId, name = (col("name") ?: throw IllegalArgumentException("name is required")).trim(), sku = sku, unitId = u.id,
                hsnSac = col("hsn_sac"), isService = service, trackInventory = !service, trackBatch = batch != null,
                taxRate = runCatching { parseRate(gst) }.getOrElse { throw IllegalArgumentException("Invalid GST rate '$gst'") },
                salePrice = parseMoney(col("sale_price") ?: "0"), purchasePrice = purchase,
                createdAt = now, updatedAt = now,
            ).also { item -> if (qty != null) openings[item.id] = OpeningStock(qty, cost, godown?.id, batch, expiry) }
        }
        return CsvImport(result.valid, result.errors, openings.filterKeys { id -> result.valid.any { it.id == id } })
    }

    /** CSV text (CRLF, header first) that [parties] and [items] read back; invoices for accountants. */
    fun partiesCsv(parties: List<Party>): String = table(
        listOf(PARTY_COLUMNS) + parties.sortedBy { it.name.lowercase() }.map { listOf(it.name, it.type.name.lowercase(), it.phone, it.email, it.gstin, it.stateCode) },
    )

    fun itemsCsv(items: List<Item>, unitSymbol: (String) -> String?): String = table(
        listOf(ITEM_COLUMNS) + items.sortedBy { it.name.lowercase() }.map {
            listOf(it.name, it.sku, unitSymbol(it.unitId), it.hsnSac, Quantity.ofScaledUnits(it.taxRate.units).format(), it.salePrice.format(), it.purchasePrice.format(), it.isService.toString())
        },
    )

    fun invoicesCsv(invoices: List<Voucher>, partyName: (String) -> String?): String = table(
        listOf(listOf("id", "number", "date", "status", "party", "place_of_supply", "taxable", "tax", "round_off", "total")) +
            invoices.map {
                listOf(it.id, it.number, it.date.toString(), it.status.name.lowercase(), it.partyId?.let(partyName), it.placeOfSupplyStateCode, it.taxable.format(), it.tax.format(), it.roundOff.format(), it.total.format())
            },
    )

    private fun table(rows: List<List<String?>>): String = rows.joinToString("") { Csv.row(it) + "\r\n" }

    private fun <T> rows(text: String, build: (col: (String) -> String?) -> T): CsvImport<T> {
        val rows = Csv.parse(text)
        require(rows.isNotEmpty()) { "The file is empty" }
        val header = rows.first().map { it.trim().lowercase() }
        require("name" in header) { "Missing column(s): name" }
        val valid = ArrayList<T>()
        val errors = ArrayList<Pair<Int, String>>()
        rows.drop(1).forEachIndexed { i, r ->
            fun col(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { r.getOrNull(it)?.trim() }?.ifEmpty { null }
            try {
                valid += build(::col)
            } catch (e: IllegalArgumentException) {
                errors += (i + 2) to (e.message ?: "invalid") // 1-based, after the header
            }
        }
        return CsvImport(valid, errors)
    }
}
