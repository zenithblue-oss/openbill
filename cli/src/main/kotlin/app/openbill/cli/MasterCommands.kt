package app.openbill.cli

import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.DocumentField
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyItemPrice
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import app.openbill.core.domain.model.VoucherType
import kotlinx.coroutines.flow.first

// ---- Shared views (JSON field names are a public contract) ----

fun firmJson(f: Firm) = jsonOf(
    "id" to f.id, "name" to f.name, "legalName" to f.legalName, "gstin" to f.gstin, "stateCode" to f.stateCode,
    "addressLine1" to f.addressLine1, "addressLine2" to f.addressLine2, "city" to f.city, "pincode" to f.pincode,
    "phone" to f.phone, "email" to f.email,
)

// Fields added after 1.0.1 appear only when set, so existing scripts see the same keys.
fun partyJson(p: Party) = jsonOf(
    "id" to p.id, "name" to p.name, "type" to p.type.name.lowercase(), "phone" to p.phone, "email" to p.email,
    "gstin" to p.gstin, "stateCode" to p.stateCode, "openingBalance" to p.openingBalance,
    "openingBalanceType" to p.openingBalanceType.name.lowercase(), "notes" to p.notes,
    *listOfNotNull(
        p.creditLimit?.let { "creditLimit" to it }, p.creditDays?.let { "creditDays" to it },
        p.category?.let { "group" to it }, p.defaultCurrency?.let { "defaultCurrency" to it },
    ).toTypedArray(),
)

fun itemJson(i: Item, unit: String?, category: String? = null) = jsonOf(
    "id" to i.id, "name" to i.name, "sku" to i.sku, "unit" to unit, "hsnSac" to i.hsnSac, "isService" to i.isService,
    "gstRate" to i.taxRate.percent(), "salePrice" to i.salePrice, "purchasePrice" to i.purchasePrice, "mrp" to i.mrp,
    *listOfNotNull(
        category?.let { "category" to it }, i.lowStockLevel?.let { "lowStockLevel" to it.format() },
        i.reorderQuantity?.let { "reorderQuantity" to it.format() },
        i.customFields.takeIf { it.isNotEmpty() }?.let { f -> "fields" to jsonOf(*f.map { it.label to it.value }.toTypedArray()) },
    ).toTypedArray(),
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
    private val terms by PartyTerms()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val now = now()
        val p = terms.apply(
            Party(
                id = cli.env.newId(), firmId = f.id, name = name.trim(), type = PartyType.valueOf(type.uppercase()),
                phone = phone, email = email, gstin = gstin?.uppercase(), stateCode = state ?: gstin?.take(2),
                createdAt = now, updatedAt = now,
            ),
        )
        validateParty(p)
        repos.parties.saveParty(p)
        if (cli.json) cli.printJson(partyJson(p)) else cli.out.println(p.id)
    }
}

/** Credit terms (owner or manager only, as in the app), group and default export currency. `none` clears one. */
class PartyTerms : OptionGroup("Terms") {
    val creditLimit by option("--credit-limit", help = "Amount, or none")
    val creditDays by option("--credit-days", help = "0 to 365 days, or none")
    val group by option("--group", help = "Party group, or none")
    val currency by option("--currency", help = "Default currency of export invoices (ISO code), or none")

    fun apply(p: Party): Party {
        fun clear(v: String?) = v?.trim()?.equals("none", true) == true
        val days = creditDays?.let { if (clear(it)) null else it.trim().toIntOrNull() ?: fail(Exit.VALIDATION, "Credit days must be a whole number") }
        return runCatching {
            p.copy(
                creditLimit = if (creditLimit == null) p.creditLimit else if (clear(creditLimit)) null else parseMoney(creditLimit!!),
                creditDays = if (creditDays == null) p.creditDays else days,
                category = if (group == null) p.category else if (clear(group)) null else group!!.trim(),
                defaultCurrency = if (currency == null) p.defaultCurrency else if (clear(currency)) null else currency!!.trim().uppercase(),
            )
        }.getOrElse { fail(Exit.VALIDATION, it.message ?: "Invalid party terms") }
    }
}

/** `party update REF`: contact details and terms; options not given keep their value. */
class PartyUpdate(private val cli: CliState) : CliktCommand("update") {
    override fun help(context: Context) = "Change a party's details, credit terms, group or default currency"
    private val ref by argument()
    private val name by option("--name")
    private val phone by option("--phone")
    private val email by option("--email")
    private val gstin by option("--gstin")
    private val state by option("--state")
    private val terms by PartyTerms()

    override fun run() = cli.withData(write = true) {
        val old = findParty(firm().id, ref)
        val p = terms.apply(
            old.copy(
                name = name?.trim() ?: old.name, phone = phone ?: old.phone, email = email ?: old.email,
                gstin = gstin?.uppercase() ?: old.gstin, stateCode = state ?: gstin?.take(2) ?: old.stateCode, updatedAt = now(),
            ),
        )
        validateParty(p)
        repos.parties.saveParty(p)
        if (cli.json) cli.printJson(partyJson(p)) else cli.out.println(p.id)
    }
}

/** `party price list|set|remove`: special sale rates new invoice lines start at. */
fun partyPriceCommands(cli: CliState) = Group("price", "A party's special rates for items").subcommands(PartyPriceList(cli), PartyPriceSet(cli, remove = false), PartyPriceSet(cli, remove = true))

class PartyPriceList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List a party's special rates"
    private val ref by argument("PARTY")
    override fun run() = cli.withData(write = false) {
        val f = firm()
        val p = findParty(f.id, ref)
        val names = items(f.id).associateBy { it.id }
        val prices = repos.parties.observePrices(p.id).first()
        if (cli.json) cli.printJson(toJson(prices.map { jsonOf("itemId" to it.itemId, "item" to names[it.itemId]?.name, "price" to it.price, "listPrice" to names[it.itemId]?.salePrice) }))
        else cli.printTable(listOf(Col("ITEM"), Col("PRICE", true), Col("LIST", true)), prices.map { listOf(names[it.itemId]?.name ?: it.itemId, it.price.indian(), names[it.itemId]?.salePrice?.indian() ?: "-") })
    }
}

class PartyPriceSet(private val cli: CliState, private val remove: Boolean) : CliktCommand(if (remove) "remove" else "set") {
    override fun help(context: Context) = if (remove) "Remove a party's special rate for an item" else "Set a party's special rate for an item (owner or manager)"
    private val ref by argument("PARTY")
    private val item by argument("ITEM")
    private val price by argument("PRICE").optional()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val p = findParty(f.id, ref)
        val i = findItem(f.id, item)
        if (remove) {
            repos.parties.findPrice(p.id, i.id) ?: fail(Exit.NOT_FOUND, "${p.name} has no special rate for ${i.name}")
            repos.parties.deletePrice(p.id, i.id)
        } else {
            val amount = parseMoney(price ?: fail(Exit.USAGE, "Give the PRICE"))
            repos.parties.savePrice(f.id, runCatching { PartyItemPrice(p.id, i.id, amount) }.getOrElse { fail(Exit.VALIDATION, it.message!!) })
        }
        if (cli.json) cli.printJson(jsonOf("party" to p.name, "item" to i.name, "price" to if (remove) null else parseMoney(price!!)))
        else cli.out.println(if (remove) "Removed" else "Set ${p.name} / ${i.name} to ${parseMoney(price!!).indian()}")
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
        val j = itemJson(i, units(f.id).symbolOf(i.unitId), categoryName(i))
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
    private val details by ItemDetails()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val i = details.apply(this, newItem(f.id, name, sku, unit, hsn, gstRate, salePrice, purchasePrice, service))
        repos.catalog.saveItem(i)
        if (cli.json) cli.printJson(itemJson(i, units(f.id).symbolOf(i.unitId), categoryName(i))) else cli.out.println(i.id)
    }
}

/** Stock levels, category and custom field values. `none` clears one; `--field LABEL=` clears a field. */
class ItemDetails : OptionGroup("Details") {
    val lowStock by option("--low-stock", help = "Low-stock level, or none")
    val reorderQty by option("--reorder-qty", help = "Quantity to reorder at the low-stock level, or none")
    val category by option("--category", help = "Category name (created if missing), or none")
    val fields by option("--field", help = "LABEL=VALUE for a custom item field set up in the app's settings (repeatable)").multiple()

    suspend fun apply(d: Data, item: Item): Item = with(d) {
        fun clear(v: String) = v.trim().equals("none", true)
        fun qty(v: String?, old: Quantity?, what: String) = when {
            v == null -> old
            clear(v) -> null
            else -> runCatching { Quantity.parse(v.trim()) }.getOrElse { fail(Exit.VALIDATION, "Invalid $what '$v'") }
        }
        val categoryId = when {
            category == null -> item.categoryId
            clear(category!!) -> null
            else -> {
                val name = category!!.trim()
                val all = repos.catalog.observeCategories(item.firmId).first().filter { it.deletedAt == null }
                all.firstOrNull { it.name.equals(name, true) }?.id ?: ItemCategory(cli.env.newId(), item.firmId, name, createdAt = now(), updatedAt = now())
                    .also { repos.catalog.saveCategory(it) }.id
            }
        }
        var custom = item.customFields
        if (fields.isNotEmpty()) {
            val labels = repos.firms.findSettings(item.firmId)?.itemFieldLabels.orEmpty()
            for (spec in fields) {
                val (label, value) = spec.split('=', limit = 2).takeIf { it.size == 2 } ?: fail(Exit.USAGE, "--field takes LABEL=VALUE")
                val known = labels.firstOrNull { it.equals(label.trim(), true) }
                    ?: fail(Exit.VALIDATION, "No custom item field '$label'" + if (labels.isEmpty()) " (set them up in the app's settings)" else "; fields: ${labels.joinToString()}")
                custom = custom.filterNot { it.label.equals(known, true) } +
                    listOfNotNull(value.trim().ifEmpty { null }?.let { runCatching { DocumentField(known, it) }.getOrElse { e -> fail(Exit.VALIDATION, e.message!!) } })
            }
            custom = labels.mapNotNull { l -> custom.firstOrNull { it.label == l } }
        }
        runCatching {
            item.copy(
                lowStockLevel = qty(lowStock, item.lowStockLevel, "low-stock level"),
                reorderQuantity = qty(reorderQty, item.reorderQuantity, "reorder quantity"),
                categoryId = categoryId, customFields = custom,
            )
        }.getOrElse { fail(Exit.VALIDATION, it.message ?: "Invalid item details") }
    }
}

suspend fun Data.categoryName(i: Item) = i.categoryId?.let { id -> repos.catalog.observeCategories(i.firmId).first().firstOrNull { it.id == id }?.name }

/** `item update REF`: options not given keep their value. */
class ItemUpdate(private val cli: CliState) : CliktCommand("update") {
    override fun help(context: Context) = "Change an item's prices, tax, stock levels, category or custom fields"
    private val ref by argument()
    private val name by option("--name")
    private val sku by option("--sku")
    private val hsn by option("--hsn")
    private val gstRate by option("--gst-rate", help = "Percent, e.g. 18")
    private val salePrice by option("--sale-price")
    private val purchasePrice by option("--purchase-price")
    private val details by ItemDetails()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val old = findItem(f.id, ref)
        if (!sku.isNullOrBlank() && items(f.id).any { it.id != old.id && it.sku.equals(sku, true) }) fail(Exit.CONFLICT, "SKU '$sku' already exists")
        val i = details.apply(
            this,
            runCatching {
                old.copy(
                    name = name?.trim() ?: old.name, sku = sku?.trim()?.ifBlank { null } ?: old.sku, hsnSac = hsn?.trim()?.ifBlank { null } ?: old.hsnSac,
                    taxRate = gstRate?.let { runCatching { parseRate(it) }.getOrElse { _ -> fail(Exit.VALIDATION, "Invalid GST rate '$it'") } } ?: old.taxRate,
                    salePrice = salePrice?.let(::parseMoney) ?: old.salePrice, purchasePrice = purchasePrice?.let(::parseMoney) ?: old.purchasePrice,
                    updatedAt = now(),
                )
            }.getOrElse { if (it is CliFailure) throw it else fail(Exit.VALIDATION, it.message ?: "Invalid item") },
        )
        repos.catalog.saveItem(i)
        if (cli.json) cli.printJson(itemJson(i, units(f.id).symbolOf(i.unitId), categoryName(i))) else cli.out.println(i.id)
    }
}

class CategoryList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List item categories with their item counts"
    override fun run() = cli.withData(write = false) {
        val f = firm()
        val counts = items(f.id).groupingBy { it.categoryId }.eachCount()
        val list = repos.catalog.observeCategories(f.id).first().filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
        if (cli.json) cli.printJson(toJson(list.map { jsonOf("id" to it.id, "name" to it.name, "items" to (counts[it.id] ?: 0)) }))
        else cli.printTable(listOf(Col("NAME"), Col("ITEMS", true), Col("ID")), list.map { listOf(it.name, (counts[it.id] ?: 0).toString(), it.id) })
    }
}

class CategoryAdd(private val cli: CliState) : CliktCommand("add") {
    override fun help(context: Context) = "Add an item category; prints its id"
    private val name by argument()
    override fun run() = cli.withData(write = true) {
        val f = firm()
        if (name.isBlank()) fail(Exit.USAGE, "Give a category name")
        if (repos.catalog.observeCategories(f.id).first().any { it.deletedAt == null && it.name.equals(name.trim(), true) }) fail(Exit.CONFLICT, "Category '$name' already exists")
        val c = ItemCategory(cli.env.newId(), f.id, name.trim(), createdAt = now(), updatedAt = now())
        repos.catalog.saveCategory(c)
        if (cli.json) cli.printJson(jsonOf("id" to c.id, "name" to c.name)) else cli.out.println(c.id)
    }
}

// ---- search ----

/** `search QUERY`: the app's list searches (number, reference or party name; name, phone or GSTIN; name, SKU, HSN, barcode or fields) in one go. */
class Search(private val cli: CliState) : CliktCommand("search") {
    override fun help(context: Context) = "Search invoices, parties, items, payments, expenses and other documents"
    private val query by argument()
    private val type by option("--type").choice(*SEARCH_TYPES.keys.toTypedArray(), "parties", "items", "all").default("all")
    private val limit by option("--limit", help = "Matches per kind (default 20)").int().default(20)

    override fun run() = cli.withData(write = false) {
        if (query.isBlank()) fail(Exit.USAGE, "Give something to search for")
        if (limit < 1) fail(Exit.USAGE, "--limit must be 1 or more")
        val f = firm()
        val rows = buildList {
            if (type == "all" || type == "parties") {
                repos.parties.observePartyPage(f.id, PartyType.entries.toSet(), query, limit).first()
                    .forEach { add(listOf("party", it.name, null, it.phone ?: it.gstin, null, null, it.id)) }
            }
            if (type == "all" || type == "items") {
                repos.catalog.observeItemPage(f.id, null, query, limit).first()
                    .forEach { add(listOf("item", it.name, null, it.sku, it.salePrice, null, it.id)) }
            }
            val kinds = if (type == "all") SEARCH_TYPES.values.toList() else listOfNotNull(SEARCH_TYPES[type])
            for (types in kinds) {
                val docs = repos.vouchers.observeVoucherPage(f.id, types, null, query, limit).first()
                val names = docs.mapNotNull { it.partyId }.distinct().associateWith { repos.parties.findParty(it)?.name }
                docs.forEach { v -> add(listOf(v.type.label(), v.number ?: "(draft)", v.date, v.partyId?.let(names::get), v.total, v.status.name.lowercase(), v.id)) }
            }
        }
        when {
            cli.json -> cli.printJson(toJson(rows.map { r -> jsonOf("kind" to r[0], "title" to r[1], "date" to r[2], "detail" to r[3], "amount" to r[4], "status" to r[5], "id" to r[6]) }))
            cli.quiet -> rows.forEach { cli.out.println(it[6]) }
            else -> cli.printTable(
                listOf(Col("KIND"), Col("TITLE"), Col("DATE"), Col("DETAIL"), Col("AMOUNT", true), Col("STATUS"), Col("ID")),
                rows.map { r -> r.map { c -> (c as? Money)?.indian() ?: c?.toString() ?: "-" } },
            )
        }
    }
}

/** `search --type` names of document kinds. */
val SEARCH_TYPES = linkedMapOf(
    "invoices" to setOf(VoucherType.SALE),
    "purchases" to setOf(VoucherType.PURCHASE),
    "payments" to setOf(VoucherType.PAYMENT_IN, VoucherType.PAYMENT_OUT),
    "expenses" to setOf(VoucherType.EXPENSE),
    "orders" to setOf(VoucherType.SALES_ORDER, VoucherType.PURCHASE_ORDER),
    "documents" to setOf(VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE, VoucherType.QUOTATION, VoucherType.PROFORMA, VoucherType.DELIVERY_CHALLAN, VoucherType.CONTRA, VoucherType.STOCK_ADJUSTMENT, VoucherType.STOCK_TRANSFER),
)

/** Lower-case, dash-separated voucher type: `sale`, `payment-in`, `sales-order`. */
fun VoucherType.label() = name.lowercase().replace('_', '-')

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
