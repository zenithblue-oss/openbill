package app.openbill.cli

import app.openbill.core.domain.csv.Csv
import app.openbill.core.domain.csv.CsvImport
import app.openbill.core.domain.csv.MasterCsv
import app.openbill.core.domain.csv.attachmentExport
import app.openbill.core.domain.csv.attachmentOwners
import com.github.ajalt.clikt.parameters.options.required
import app.openbill.core.model.Progress
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.domain.usecase.StockVouchers
import kotlinx.coroutines.flow.first
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
    override fun help(context: Context) = "Import $entity from CSV (header: ${(if (entity == "parties") PARTY_COLUMNS else ITEM_COLUMNS).joinToString(",")})" +
        if (entity == "parties") "" else "; optional opening stock columns: ${MasterCsv.OPENING_COLUMNS.joinToString(",")}"
    private val file by argument().file(mustExist = true, canBeDir = false)
    private val dryRun by option("--dry-run", help = "Validate only; write nothing").flag()

    override fun run() = cli.withData(write = !dryRun) {
        val f = firm()
        val text = file.readText()
        cli.progress(Progress("Checking rows"))
        val result: CsvImport<*> = if (entity == "parties") MasterCsv.parties(text, f.id, cli.env.newId, now())
        else MasterCsv.items(text, f.id, units(f.id), items(f.id), cli.env.newId, now(), repos.catalog.observeGodowns(f.id).first())
        val errors = result.errors
        if (cli.json) {
            cli.printJson(jsonOf("rows" to result.rows, "valid" to result.valid.size, "errors" to errors.map { jsonOf("row" to it.first, "message" to it.second) }, "imported" to (!dryRun && errors.isEmpty())))
        } else {
            errors.forEach { (row, msg) -> cli.env.err.println("row $row: $msg") }
        }
        if (errors.isNotEmpty()) fail(Exit.VALIDATION, "${errors.size} invalid row(s); nothing imported")
        // Opening stock is posted as one "Opening stock" adjustment dated today, as in the app.
        val openingYear = if (result.openings.isEmpty()) null else dateAndYear(f, null)
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
                if (openingYear != null) {
                    val (today, fy) = openingYear
                    StockVouchers(repos.vouchers, PostVoucher(repos.vouchers)).postOpenings(
                        cli.env.newId(), f.id, fy, f.stateCode, today, result.valid.filterIsInstance<Item>(), result.openings,
                        {
                            val godowns = repos.catalog.observeGodowns(f.id).first()
                            repos.firms.findSettings(f.id)?.defaultGodownId?.takeIf { id -> godowns.any { it.id == id } }
                                ?: godowns.firstOrNull { it.isDefault }?.id ?: godowns.firstOrNull()?.id
                                ?: error("No godown; add one before importing opening stock")
                        },
                        now(),
                    )
                }
            }
        }
        cli.progressDone()
        if (!cli.json) cli.out.println("Imported ${result.valid.size} $entity" + if (result.openings.isEmpty()) "" else " (${result.openings.size} with opening stock)")
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

    override fun run() = cli.withData(write = false, app.openbill.core.domain.model.Permission.EXPORT) {
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

/** `export attachments --out FILE.zip`: the app's attachment export (one folder per record plus index.csv). */
class ExportAttachments(private val cli: CliState) : CliktCommand("attachments") {
    override fun help(context: Context) = "Export every file attached to the firm's documents and parties as one ZIP with an index.csv"
    private val out by option("--out", help = "ZIP file to write").file(canBeDir = false).required()

    override fun run() = cli.withData(write = false, app.openbill.core.domain.model.Permission.EXPORT) {
        val f = firm()
        val store = app.openbill.core.backup.AttachmentStore(File(dataDir, "attachments"))
        val owners = attachmentOwners(
            repos.vouchers.observeVouchers(f.id, kotlinx.datetime.LocalDate(2000, 1, 1), kotlinx.datetime.LocalDate(2099, 12, 31)).first(),
            repos.parties.observeParties(f.id).first(),
        )
        val files = owners.associate { o -> o.id to store.list(o.id) }.filterValues { it.isNotEmpty() }
        if (files.isEmpty()) fail(Exit.NOT_FOUND, "No attachments in ${f.name}")
        val target = out.absoluteFile
        target.parentFile.mkdirs()
        // Streamed one attachment at a time into a temp file, moved into place only when complete.
        val tmp = File(target.parentFile, target.name + ".tmp")
        val count = try {
            tmp.outputStream().buffered().use { os ->
                val zip = app.openbill.core.domain.report.ZipWriter.Stream(os::write)
                attachmentExport(owners, files, { id, name -> runCatching { store.file(id, name) }.getOrNull()?.readBytes() }, zip::add).also { zip.finish() }
            }.also { java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            tmp.delete()
        }
        when {
            cli.json -> cli.printJson(jsonOf("file" to target.path, "files" to count, "records" to files.size))
            cli.quiet -> cli.out.println(target.path)
            else -> cli.out.println("Wrote $count file(s) of ${files.size} record(s) to ${target.path}")
        }
    }
}
