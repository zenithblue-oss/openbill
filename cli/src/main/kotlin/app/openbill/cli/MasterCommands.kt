package app.openbill.cli

import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.flow.first

// ---- Shared views (JSON field names are a public contract) ----

fun firmJson(f: Firm) = jsonOf(
    "id" to f.id, "name" to f.name, "legalName" to f.legalName, "gstin" to f.gstin, "stateCode" to f.stateCode,
    "addressLine1" to f.addressLine1, "addressLine2" to f.addressLine2, "city" to f.city, "pincode" to f.pincode,
    "phone" to f.phone, "email" to f.email,
)

fun partyJson(p: Party) = jsonOf(
    "id" to p.id, "name" to p.name, "type" to p.type.name.lowercase(), "phone" to p.phone, "email" to p.email,
    "gstin" to p.gstin, "stateCode" to p.stateCode, "openingBalance" to p.openingBalance,
    "openingBalanceType" to p.openingBalanceType.name.lowercase(), "notes" to p.notes,
)

fun itemJson(i: Item, unit: String?) = jsonOf(
    "id" to i.id, "name" to i.name, "sku" to i.sku, "unit" to unit, "hsnSac" to i.hsnSac, "isService" to i.isService,
    "gstRate" to i.taxRate.percent(), "salePrice" to i.salePrice, "purchasePrice" to i.purchasePrice, "mrp" to i.mrp,
)

/** Rate as a percent string, e.g. "18" or "12.5". */
fun Rate.percent(): String = Quantity.ofScaledUnits(units).format()

/** Percent text ("18", "0.25") to a [Rate]; same 4-decimal scale as [Quantity]. */
fun parseRate(text: String): Rate = MasterCsv.parseRate(text)

fun parseMoney(text: String): Money = runCatching { Money.parse(text.trim()) }
    .getOrElse { fail(Exit.VALIDATION, "Invalid amount '$text' (use a decimal like 1500.00)") }

suspend fun Data.parties(firmId: String) = repos.parties.observeParties(firmId).first().filter { it.deletedAt == null }
suspend fun Data.items(firmId: String) = repos.catalog.observeItems(firmId).first().filter { it.deletedAt == null }
suspend fun Data.units(firmId: String) = repos.catalog.observeUnits(firmId).first()

/** By id, exact name, GSTIN, or phone. */
suspend fun Data.findParty(firmId: String, ref: String): Party {
    val all = parties(firmId)
    val m = all.filter { it.id == ref }.ifEmpty { all.filter { it.name.equals(ref, true) || it.gstin.equals(ref, true) || it.phone == ref } }
    return when (m.size) {
        0 -> fail(Exit.NOT_FOUND, "No party matches '$ref'")
        1 -> m.single()
        else -> fail(Exit.USAGE, "Party '$ref' is ambiguous (${m.size} matches); use its id")
    }
}

/** By id, SKU, or exact name. */
suspend fun Data.findItem(firmId: String, ref: String): Item {
    val all = items(firmId)
    val m = all.filter { it.id == ref }.ifEmpty { all.filter { it.sku.equals(ref, true) } }.ifEmpty { all.filter { it.name.equals(ref, true) } }
    return when (m.size) {
        0 -> fail(Exit.NOT_FOUND, "No item matches '$ref'")
        1 -> m.single()
        else -> fail(Exit.USAGE, "Item '$ref' is ambiguous (${m.size} matches); use its id or SKU")
    }
}

fun List<UnitOfMeasure>.symbolOf(id: String) = firstOrNull { it.id == id }?.symbol

// ---- firm ----

class FirmList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List firms"
    override fun run() = cli.withData(write = false) {
        val firms = repos.firms.observeFirms().first().filter { it.deletedAt == null }
        when {
            cli.json -> cli.printJson(toJson(firms.map(::firmJson)))
            cli.quiet -> firms.forEach { cli.out.println(it.id) }
            else -> cli.printTable(listOf(Col("ID"), Col("NAME"), Col("GSTIN"), Col("STATE")), firms.map { listOf(it.id, it.name, it.gstin ?: "-", it.stateCode) })
        }
    }
}

class FirmShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show a firm (default: --firm or the only firm)"
    private val ref by argument().optional()
    override fun run() = cli.withData(write = false) {
        ref?.let { cli.firm = it }
        val f = firm()
        if (cli.json) cli.printJson(firmJson(f)) else cli.printFields(firmJson(f).map { it.key to it.value.toString().trim('"').takeUnless { v -> v == "null" } })
    }
}

// ---- party ----

class PartyList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List parties"
    private val search by option("--search", help = "Name, phone, or GSTIN contains")
    override fun run() = cli.withData(write = false) {
        val q = search?.lowercase()
        val list = parties(firm().id).filter { q == null || listOfNotNull(it.name, it.phone, it.gstin).any { s -> q in s.lowercase() } }.sortedBy { it.name.lowercase() }
        when {
            cli.json -> cli.printJson(toJson(list.map(::partyJson)))
            cli.quiet -> list.forEach { cli.out.println(it.id) }
            else -> cli.printTable(
                listOf(Col("ID"), Col("NAME"), Col("TYPE"), Col("PHONE"), Col("GSTIN")),
                list.map { listOf(it.id, it.name, it.type.name.lowercase(), it.phone ?: "-", it.gstin ?: "-") },
            )
        }
    }
}

class PartyShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show a party by id, name, GSTIN, or phone"
    private val ref by argument()
    override fun run() = cli.withData(write = false) {
        val p = findParty(firm().id, ref)
        if (cli.json) cli.printJson(partyJson(p)) else cli.printFields(partyJson(p).map { it.key to it.value.toString().trim('"').takeUnless { v -> v == "null" } })
    }
}

class PartyAdd(private val cli: CliState) : CliktCommand("add") {
    override fun help(context: Context) = "Add a party; prints its id"
    private val name by option("--name").required()
    private val type by option("--type").choice("customer", "supplier", "both").default("customer")
    private val phone by option("--phone")
    private val email by option("--email")
    private val gstin by option("--gstin")
    private val state by option("--state", help = "Two-digit GST state code")

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val now = now()
        val p = Party(
            id = cli.env.newId(), firmId = f.id, name = name.trim(), type = PartyType.valueOf(type.uppercase()),
            phone = phone, email = email, gstin = gstin?.uppercase(), stateCode = state ?: gstin?.take(2),
            createdAt = now, updatedAt = now,
        )
        validateParty(p)
        repos.parties.saveParty(p)
        if (cli.json) cli.printJson(partyJson(p)) else cli.out.println(p.id)
    }
}

fun validateParty(p: Party) = MasterCsv.validateParty(p)

// ---- item ----

class ItemList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List items"
    private val search by option("--search", help = "Name or SKU contains")
    override fun run() = cli.withData(write = false) {
        val f = firm()
        val units = units(f.id)
        val q = search?.lowercase()
        val list = items(f.id).filter { q == null || q in it.name.lowercase() || q in (it.sku ?: "").lowercase() }.sortedBy { it.name.lowercase() }
        when {
            cli.json -> cli.printJson(toJson(list.map { itemJson(it, units.symbolOf(it.unitId)) }))
            cli.quiet -> list.forEach { cli.out.println(it.id) }
            else -> cli.printTable(
                listOf(Col("ID"), Col("NAME"), Col("SKU"), Col("UNIT"), Col("GST%", true), Col("PRICE", true)),
                list.map { listOf(it.id, it.name, it.sku ?: "-", units.symbolOf(it.unitId) ?: "-", it.taxRate.percent(), it.salePrice.indian()) },
            )
        }
    }
}

class ItemShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show an item by id, SKU, or name"
    private val ref by argument()
    override fun run() = cli.withData(write = false) {
        val f = firm()
        val i = findItem(f.id, ref)
        val j = itemJson(i, units(f.id).symbolOf(i.unitId))
        if (cli.json) cli.printJson(j) else cli.printFields(j.map { it.key to it.value.toString().trim('"').takeUnless { v -> v == "null" } })
    }
}

class ItemAdd(private val cli: CliState) : CliktCommand("add") {
    override fun help(context: Context) = "Add an item or service; prints its id"
    private val name by option("--name").required()
    private val sku by option("--sku")
    private val unit by option("--unit", help = "Unit symbol or name (default: the firm's first unit)")
    private val hsn by option("--hsn")
    private val gstRate by option("--gst-rate", help = "Percent, e.g. 18").default("0")
    private val salePrice by option("--sale-price").default("0")
    private val purchasePrice by option("--purchase-price").default("0")
    private val service by option("--service").flag()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val i = newItem(f.id, name, sku, unit, hsn, gstRate, salePrice, purchasePrice, service)
        repos.catalog.saveItem(i)
        if (cli.json) cli.printJson(itemJson(i, units(f.id).symbolOf(i.unitId))) else cli.out.println(i.id)
    }
}

suspend fun Data.newItem(
    firmId: String, name: String, sku: String?, unit: String?, hsn: String?,
    gstRate: String, salePrice: String, purchasePrice: String, service: Boolean,
): Item {
    val units = units(firmId)
    val u = if (unit.isNullOrBlank()) units.firstOrNull() ?: fail(Exit.NOT_FOUND, "Firm has no units")
    else units.firstOrNull { it.symbol.equals(unit, true) || it.name.equals(unit, true) } ?: fail(Exit.NOT_FOUND, "No unit '$unit'")
    if (!sku.isNullOrBlank() && items(firmId).any { it.sku.equals(sku, true) }) fail(Exit.CONFLICT, "SKU '$sku' already exists")
    val now = now()
    return Item(
        id = cli.env.newId(), firmId = firmId, name = name.trim(), sku = sku?.trim()?.ifBlank { null }, unitId = u.id,
        hsnSac = hsn?.trim()?.ifBlank { null }, isService = service, trackInventory = !service,
        taxRate = runCatching { parseRate(gstRate) }.getOrElse { fail(Exit.VALIDATION, "Invalid GST rate '$gstRate'") },
        salePrice = parseMoney(salePrice), purchasePrice = parseMoney(purchasePrice), createdAt = now, updatedAt = now,
    )
}
