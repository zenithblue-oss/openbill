package app.openbill.cli

import app.openbill.core.domain.csv.Csv
import app.openbill.core.domain.csv.CsvImport
import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.model.Progress
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
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

private val PARTY_COLUMNS = MasterCsv.PARTY_COLUMNS
private val ITEM_COLUMNS = MasterCsv.ITEM_COLUMNS

/** `import parties|items FILE.csv [--dry-run]`: every row validated; all rows or none written. */
class ImportCsv(private val cli: CliState, private val entity: String) : CliktCommand(entity) {
    override fun help(context: Context) = "Import $entity from CSV (header: ${(if (entity == "parties") PARTY_COLUMNS else ITEM_COLUMNS).joinToString(",")})"
    private val file by argument().file(mustExist = true, canBeDir = false)
    private val dryRun by option("--dry-run", help = "Validate only; write nothing").flag()

    override fun run() = cli.withData(write = !dryRun) {
        val f = firm()
        val text = file.readText()
        cli.progress(Progress("Checking rows"))
        val result: CsvImport<*> = if (entity == "parties") MasterCsv.parties(text, f.id, cli.env.newId, now())
        else MasterCsv.items(text, f.id, units(f.id), items(f.id), cli.env.newId, now())
        val errors = result.errors
        if (cli.json) {
            cli.printJson(jsonOf("rows" to result.rows, "valid" to result.valid.size, "errors" to errors.map { jsonOf("row" to it.first, "message" to it.second) }, "imported" to (!dryRun && errors.isEmpty())))
        } else {
            errors.forEach { (row, msg) -> cli.env.err.println("row $row: $msg") }
        }
        if (errors.isNotEmpty()) fail(Exit.VALIDATION, "${errors.size} invalid row(s); nothing imported")
        if (dryRun) {
            if (!cli.json) cli.out.println("${result.valid.size} row(s) valid (dry run; nothing written)")
            return@withData
        }
        // One outer transaction: repository writes nest inside it, so a failure rolls back every row.
        database.useWriterConnection { t ->
            t.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val total = result.valid.size.toLong()
                result.valid.forEachIndexed { i, it ->
                    if (it is Party) repos.parties.saveParty(it) else repos.catalog.saveItem(it as Item)
                    cli.progress(Progress("Importing $entity", i + 1L, total))
                }
            }
        }
        cli.progressDone()
        if (!cli.json) cli.out.println("Imported ${result.valid.size} $entity")
    }
}

/** `export csv parties|items|invoices [--out FILE]` (docs/11-cli.md). */
class ExportCsv(cli: CliState) : ExportCmd(cli, null) {
    private val name by argument(help = "Entity").choice("parties", "items", "invoices")
    override val entity: String get() = name
}

/** `export parties|items|invoices --format csv|json [--out FILE]` */
open class ExportCmd(private val cli: CliState, private val fixed: String?) : CliktCommand(fixed ?: "csv") {
    override fun help(context: Context) = "Export ${fixed ?: "parties, items or invoices as CSV"}"
    protected open val entity: String get() = fixed!!
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
            when (entity) {
                "parties" -> MasterCsv.partiesCsv(parties(f.id))
                "items" -> MasterCsv.itemsCsv(items(f.id)) { units.symbolOf(it) }
                else -> parties(f.id).associate { it.id to it.name }.let { names -> MasterCsv.invoicesCsv(invoices(f.id)) { names[it] } }
            }.trimEnd('\r', '\n')
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
