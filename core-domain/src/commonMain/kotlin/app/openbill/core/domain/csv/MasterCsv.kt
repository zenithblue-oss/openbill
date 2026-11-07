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
    /** Rows left out on purpose (contacts already saved as parties), not errors. */
    val skipped: Int = 0,
) {
    val rows: Int get() = valid.size + errors.size + skipped
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

    /** Optional party import columns: opening balance (positive = they owe you, or with balance_type dr/cr) and credit days. */
    val PARTY_OPENING_COLUMNS = listOf("opening_balance", "balance_type", "credit_days")

    /**
     * Column names used by spreadsheets and other billing software's exports, mapped to ours
     * (compared lowercased with punctuation as spaces). Our own names always win over an alias.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "name" to listOf("party name", "customer name", "supplier name", "vendor name", "ledger name", "account name", "item name", "product name", "name of item", "item", "product", "particulars"),
        "type" to listOf("party type", "ledger type", "customer type", "group", "under"),
        "phone" to listOf("mobile", "mobile no", "mobile number", "phone no", "phone number", "contact", "contact no", "contact number", "whatsapp"),
        "email" to listOf("email id", "e mail", "email address", "mail"),
        "gstin" to listOf("gst no", "gst number", "gstin no", "gstin uin", "gst in", "gstin number", "gst registration no"),
        "state_code" to listOf("state code", "pos", "place of supply code"),
        "sku" to listOf("item code", "product code", "code", "sku code", "part no", "part number"),
        "unit" to listOf("uom", "unit of measure", "units", "primary unit", "base unit"),
        "hsn_sac" to listOf("hsn", "hsn code", "sac", "sac code", "hsn sac", "hsn sac code"),
        "gst_rate" to listOf("gst rate", "gst percent", "gst %", "tax rate", "tax", "tax percent", "igst rate", "gst rate percent"),
        "sale_price" to listOf("selling price", "sales price", "sale rate", "selling rate", "rate", "price", "mrp", "unit price"),
        "purchase_price" to listOf("purchase rate", "cost price", "cost", "buying price", "purchase cost"),
        "is_service" to listOf("service", "item type service"),
        "opening_qty" to listOf("opening stock", "opening quantity", "opening qty", "stock", "quantity", "qty", "current stock"),
        "opening_cost" to listOf("opening rate", "stock rate", "opening value rate"),
        "opening_balance" to listOf("opening balance", "op balance", "balance", "outstanding", "closing balance"),
        "balance_type" to listOf("dr cr", "balance type", "type of balance"),
        "credit_days" to listOf("credit period", "credit days", "payment terms days"),
    ).flatMap { (canonical, list) -> list.map { normalize(it) to canonical } }.toMap()

    private fun normalize(header: String) = header.lowercase().replace('%', ' ').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex(" +"), " ")

    /** The header in our column names: exact names (with _ or spaces) kept, known aliases translated. */
    internal fun canonicalHeader(raw: List<String>): List<String> {
        val ours = (PARTY_COLUMNS + ITEM_COLUMNS + OPENING_COLUMNS + PARTY_OPENING_COLUMNS).toSet()
        val direct = raw.map { normalize(it).replace(' ', '_') }
        return direct.mapIndexed { i, d ->
            when {
                d in ours -> d
                else -> ALIASES[normalize(raw[i])]?.takeIf { it !in direct } ?: d
            }
        }
    }

    private val GSTIN = Regex("^[0-9]{2}[A-Z0-9]{10}[0-9A-Z]Z[0-9A-Z]$")

    fun validateParty(p: Party) {
        require(p.gstin == null || GSTIN.matches(p.gstin!!)) { "Invalid GSTIN '${p.gstin}'" }
        require(p.stateCode == null || Regex("^[0-9]{2}$").matches(p.stateCode!!)) { "State code must be two digits" }
    }

    /** Percent text ("18", "0.25") to a [Rate]; same 4-decimal scale as [Quantity]. */
    fun parseRate(text: String): Rate = Rate.ofBasisPointsHundredths(Quantity.parse(text.trim().removeSuffix("%")).scaledUnits)

    /** Amount text; spreadsheet forms such as "₹1,500.00" or "Rs. 1500" are accepted. */
    fun parseMoney(text: String): Money = runCatching { Money.parse(text.trim().removePrefix("₹").removePrefix("Rs.").removePrefix("Rs").replace(",", "").trim()) }
        .getOrElse { throw IllegalArgumentException("Invalid amount '$text' (use a decimal like 1500.00)") }

    fun parties(text: String, firmId: String, newId: () -> String, now: Instant): CsvImport<Party> =
        rows(text) { col ->
            // Signed balance, or an amount with balance_type: positive / Dr = they owe you.
            val balance = col("opening_balance")?.let { b ->
                val t = b.trim()
                val suffix = t.takeLast(2).lowercase().takeIf { it == "dr" || it == "cr" }
                parseMoney(if (suffix != null) t.dropLast(2) else t).let { m -> if (suffix == "cr") -m else m }
            } ?: Money.ZERO
            val credit = when (col("balance_type")?.lowercase()?.trim()) {
                null, "dr", "debit", "receivable", "to receive" -> false
                "cr", "credit", "payable", "to pay" -> true
                else -> throw IllegalArgumentException("balance_type must be dr or cr")
            }
            val signed = if (credit) -balance else balance
            Party(
                id = newId(), firmId = firmId, name = col("name") ?: throw IllegalArgumentException("name is required"),
                type = col("type")?.let(::partyType) ?: PartyType.CUSTOMER,
                phone = col("phone"), email = col("email"), gstin = col("gstin")?.uppercase()?.replace(" ", ""),
                stateCode = col("state_code")?.padStart(2, '0') ?: col("gstin")?.take(2),
                openingBalance = if (signed < Money.ZERO) -signed else signed,
                openingBalanceType = if (signed < Money.ZERO) app.openbill.core.domain.model.OpeningBalanceType.CREDIT else app.openbill.core.domain.model.OpeningBalanceType.DEBIT,
                creditDays = col("credit_days")?.let { d -> d.removeSuffix("days").trim().toIntOrNull()?.takeIf { it in 0..3650 } ?: throw IllegalArgumentException("credit_days must be 0 to 3650") },
                createdAt = now, updatedAt = now,
            ).also(::validateParty)
        }

    /** Our type names, plus the ledger groups other software exports (Sundry Debtors / Creditors). */
    private fun partyType(text: String): PartyType {
        val t = normalize(text)
        return PartyType.entries.firstOrNull { it.name.equals(t, true) } ?: when {
            "debtor" in t || t == "buyer" || t == "client" -> PartyType.CUSTOMER
            "creditor" in t || t == "vendor" || t == "seller" -> PartyType.SUPPLIER
            t == "customer and supplier" || t == "customer supplier" -> PartyType.BOTH
            else -> throw IllegalArgumentException("type must be customer, supplier, or both")
        }
    }

    /** Header and one example row for each import, to fill in a spreadsheet. */
    fun partiesTemplate(): String = table(
        listOf(
            PARTY_COLUMNS + PARTY_OPENING_COLUMNS,
            listOf("Sharma Stores", "customer", "9876543210", "sharma@example.com", "27AAPFU0939F1ZV", "27", "1500.00", "dr", "30"),
        ),
    )

    fun itemsTemplate(): String = table(
        listOf(
            ITEM_COLUMNS + OPENING_COLUMNS,
            listOf("Basmati rice 5 kg", "RICE5", "pcs", "1006", "5", "650.00", "560.00", "false", "20", "560.00", "", "", ""),
        ),
    )

    /**
     * Address-book contacts (vCard) as new customers. A contact whose phone, or name when it has
     * no phone, matches an existing party or an earlier card is skipped, so importing twice is safe.
     */
    fun contacts(text: String, firmId: String, existing: List<Party>, newId: () -> String, now: Instant): CsvImport<Party> {
        val cards = VCard.parse(text)
        require(cards.isNotEmpty()) { "No contacts with a name found in the file" }
        fun digits(p: String?) = p?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.length >= 6 }
        val phones = existing.mapNotNull { digits(it.phone) }.toHashSet()
        val names = existing.map { it.name.trim().lowercase() }.toHashSet()
        var skipped = 0
        val valid = cards.mapNotNull { c ->
            val phone = digits(c.phone)
            val fresh = if (phone != null) phones.add(phone) else names.add(c.name.lowercase())
            if (!fresh) { skipped++; return@mapNotNull null }
            names.add(c.name.lowercase())
            Party(id = newId(), firmId = firmId, name = c.name, type = PartyType.CUSTOMER, phone = c.phone, email = c.email, createdAt = now, updatedAt = now)
        }
        return CsvImport(valid, emptyList(), skipped = skipped)
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
        val header = canonicalHeader(rows.first().map { it.trim().removePrefix("﻿") })
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
