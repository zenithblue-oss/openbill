package app.openbill.cli

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

private val PARTY_COLUMNS = listOf("name", "type", "phone", "email", "gstin", "state_code")
private val ITEM_COLUMNS = listOf("name", "sku", "unit", "hsn_sac", "gst_rate", "sale_price", "purchase_price", "is_service")

/** `import parties|items FILE.csv [--dry-run]`: every row validated; all rows or none written. */
class ImportCsv(private val cli: CliState, private val entity: String) : CliktCommand(entity) {
    override fun help(context: Context) = "Import $entity from CSV (header: ${(if (entity == "parties") PARTY_COLUMNS else ITEM_COLUMNS).joinToString(",")})"
    private val file by argument().file(mustExist = true, canBeDir = false)
    private val dryRun by option("--dry-run", help = "Validate only; write nothing").flag()

    override fun run() = cli.withData(write = !dryRun) {
        val f = firm()
        val rows = Csv.parse(file.readText())
        if (rows.isEmpty()) fail(Exit.VALIDATION, "${file.name} is empty")
        val header = rows.first().map { it.trim().lowercase() }
        val required = listOf("name")
        required.filter { it !in header }.takeIf { it.isNotEmpty() }?.let { fail(Exit.VALIDATION, "Missing column(s): ${it.joinToString()}") }
        val errors = ArrayList<Pair<Int, String>>()
        val parsed = ArrayList<Any>()
        val seenSku = HashSet<String>()
        rows.drop(1).forEachIndexed { i, r ->
            val rowNo = i + 2 // 1-based, after the header
            fun col(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { r.getOrNull(it)?.trim() }?.ifEmpty { null }
            try {
                parsed += if (entity == "parties") {
                    val now = now()
                    Party(
                        id = cli.env.newId(), firmId = f.id, name = col("name") ?: throw IllegalArgumentException("name is required"),
                        type = col("type")?.let { t -> PartyType.entries.firstOrNull { it.name.equals(t, true) } ?: throw IllegalArgumentException("type must be customer, supplier, or both") } ?: PartyType.CUSTOMER,
                        phone = col("phone"), email = col("email"), gstin = col("gstin")?.uppercase(),
                        stateCode = col("state_code") ?: col("gstin")?.take(2), createdAt = now, updatedAt = now,
                    ).also(::validateParty)
                } else {
                    newItem(
                        f.id, col("name") ?: throw IllegalArgumentException("name is required"), col("sku"), col("unit"), col("hsn_sac"),
                        col("gst_rate") ?: "0", col("sale_price") ?: "0", col("purchase_price") ?: "0",
                        col("is_service")?.lowercase() in setOf("true", "yes", "1"),
                    ).also { item -> item.sku?.lowercase()?.let { require(seenSku.add(it)) { "duplicate SKU '${item.sku}' in file" } } }
                }
            } catch (e: CliFailure) {
                errors += rowNo to e.message!!
            } catch (e: IllegalArgumentException) {
                errors += rowNo to (e.message ?: "invalid")
            }
        }
        if (cli.json) {
            cli.printJson(jsonOf("rows" to parsed.size + errors.size, "valid" to parsed.size, "errors" to errors.map { jsonOf("row" to it.first, "message" to it.second) }, "imported" to (!dryRun && errors.isEmpty())))
        } else {
            errors.forEach { (row, msg) -> cli.env.err.println("row $row: $msg") }
        }
        if (errors.isNotEmpty()) fail(Exit.VALIDATION, "${errors.size} invalid row(s); nothing imported")
        if (dryRun) {
            if (!cli.json) cli.out.println("${parsed.size} row(s) valid (dry run; nothing written)")
            return@withData
        }
        // One outer transaction: repository writes nest inside it, so a failure rolls back every row.
        database.useWriterConnection { t ->
            t.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                parsed.forEach { if (it is Party) repos.parties.saveParty(it) else repos.catalog.saveItem(it as Item) }
            }
        }
        if (!cli.json) cli.out.println("Imported ${parsed.size} $entity")
    }
}

/** `export parties|items|invoices --format csv|json [--out FILE]` */
class ExportCmd(private val cli: CliState, private val entity: String) : CliktCommand(entity) {
    override fun help(context: Context) = "Export $entity"
    private val format by option("--format").choice("csv", "json").default("csv")
    private val out by option("--out", help = "File (default: stdout)").file(canBeDir = false)

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val units = units(f.id)
        val text = if (format == "json") {
            val list = when (entity) {
                "parties" -> parties(f.id).sortedBy { it.name.lowercase() }.map(::partyJson)
                "items" -> items(f.id).sortedBy { it.name.lowercase() }.map { itemJson(it, units.symbolOf(it.unitId)) }
                else -> invoices(f.id).map { invoiceJson(it, withLines = true) }
            }
            kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), toJson(list))
        } else {
            val rows: List<List<String?>> = when (entity) {
                "parties" -> listOf(PARTY_COLUMNS) + parties(f.id).sortedBy { it.name.lowercase() }.map {
                    listOf(it.name, it.type.name.lowercase(), it.phone, it.email, it.gstin, it.stateCode)
                }
                "items" -> listOf(ITEM_COLUMNS) + items(f.id).sortedBy { it.name.lowercase() }.map {
                    listOf(it.name, it.sku, units.symbolOf(it.unitId), it.hsnSac, it.taxRate.percent(), it.salePrice.format(), it.purchasePrice.format(), it.isService.toString())
                }
                else -> {
                    val names = parties(f.id).associate { it.id to it.name }
                    listOf(listOf("id", "number", "date", "status", "party", "place_of_supply", "taxable", "tax", "round_off", "total")) +
                        invoices(f.id).map {
                            listOf(it.id, it.number, it.date.toString(), it.status.name.lowercase(), it.partyId?.let(names::get), it.placeOfSupplyStateCode, it.taxable.format(), it.tax.format(), it.roundOff.format(), it.total.format())
                        }
                }
            }
            rows.joinToString("") { Csv.row(it) + "\r\n" }.trimEnd('\r', '\n')
        }
        val target: File? = out
        if (target == null) cli.out.print(text + if (format == "csv") "\r\n" else "\n")
        else {
            target.absoluteFile.parentFile.mkdirs()
            target.writeText(text + if (format == "csv") "\r\n" else "\n")
            cli.info("Wrote ${target.path}")
            if (cli.quiet) cli.out.println(target.path)
        }
    }
}
