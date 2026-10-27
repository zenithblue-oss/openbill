package app.openbill.cli

import app.openbill.core.domain.report.ColumnKind
import app.openbill.core.domain.report.Gstr1
import app.openbill.core.domain.report.ReportData
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.ReportTable
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.report.loadReportData
import app.openbill.core.domain.report.writeRegisterCsv
import app.openbill.core.domain.report.writeTallyXml
import app.openbill.core.model.Money
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** `report list | run NAME | NAME`, `gst gstr1|gstr3b|hsn`, `tally export`, `audit list` (docs/11-cli.md). */
fun reportCommands(cli: CliState) = Group("report", "Business and GST reports").subcommands(
    listOf(ReportList(cli), ReportRun(cli)) + ReportKind.entries.map { ReportNamed(cli, it) },
)

fun gstCommands(cli: CliState) = Group("gst", "GST return working and exports").subcommands(
    Gstr1Cmd(cli), PeriodReport(cli, "gstr3b", ReportKind.GSTR3B), PeriodReport(cli, "hsn", ReportKind.HSN_SUMMARY),
)

fun tallyCommands(cli: CliState) = Group("tally", "Tally-compatible export").subcommands(TallyExport(cli, "export"))

fun auditCommands(cli: CliState) = Group("audit", "Append-only audit trail").subcommands(AuditList(cli))

/** Loads the snapshot for `--from/--to` (default: the current financial year). */
private suspend fun Data.reportData(from: String?, to: String?, kind: ReportKind? = null): ReportData =
    repos.loadReportData(firm().id, reportPeriod(from, to), kind, cli.progress).also { cli.progressDone() }

/** Writes [text] to [out] (reporting the path) or stdout. */
private fun CliState.emit(text: String, out: File?) = emitStreamed(out) { it.append(text) }

/** Streams [write] to [out] (reporting the path) or stdout, without building the whole text first. */
private inline fun CliState.emitStreamed(out: File?, write: (Appendable) -> Unit) {
    if (out == null) {
        val w = this.out.bufferedWriter()
        write(w)
        w.flush()
        return
    }
    out.absoluteFile.parentFile.mkdirs()
    out.bufferedWriter().use(write)
    info("Wrote ${out.path}")
    if (quiet) this.out.println(out.path)
}

/** Loads the period for `--from/--to` (default: the current financial year). */
private suspend fun Data.reportPeriod(from: String?, to: String?): ReportPeriod {
    val f = firm()
    val today = now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val fy = ReportPeriod.financialYearOf(today, repos.firms.findSettings(f.id)?.financialYearStartMonth ?: 4)
    return runCatching { ReportPeriod(from?.let(::parseDate) ?: fy.from, to?.let(::parseDate) ?: fy.to) }
        .getOrElse { fail(Exit.USAGE, it.message ?: "Invalid period") }
}

private fun CliState.render(table: ReportTable, format: String, out: File?, period: ReportPeriod) {
    val extra = mapOf("from" to period.from.toString(), "to" to period.to.toString())
    when (format) {
        "csv" -> emit(table.toCsv(), out)
        "json" -> emit(table.toJson(extra), out)
        else -> {
            if (out != null) return emit(table.toCsv(), out)
            this.out.println("${table.title} (${period.from} to ${period.to})")
            val cols = table.columns.map { Col(it.label.uppercase(), it.numeric) }
            fun cells(r: List<String>) = r.mapIndexed { i, c ->
                if (table.columns[i].kind == ColumnKind.MONEY && c.isNotEmpty()) Money.parse(c).indian() else c
            }
            printTable(cols, table.rows.map(::cells) + listOfNotNull(table.totals?.let(::cells)))
            table.notes.forEach { this.out.println("Note: $it") }
        }
    }
}

class ReportList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List report names"
    override fun run() {
        if (cli.json) cli.printJson(toJson(ReportKind.entries.map { jsonOf("name" to it.key, "title" to it.title, "group" to it.group.label) }))
        else cli.printTable(listOf(Col("NAME"), Col("TITLE")), ReportKind.entries.map { listOf(it.key, it.title) })
    }
}

/** `report NAME` and (via [ReportRun]) `report run NAME`. */
open class ReportNamed(private val cli: CliState, private val fixed: ReportKind?, name: String = fixed?.key ?: "run") : CliktCommand(name) {
    override fun help(context: Context) = fixed?.title ?: "Run a report by name (see `report list`)"
    protected open val kindKey: String get() = fixed!!.key
    private val from by option("--from", help = "YYYY-MM-DD (default: start of this financial year)")
    private val to by option("--to", help = "YYYY-MM-DD (default: end of this financial year)")
    private val format by option("--format").choice("table", "csv", "json").default("table")
    private val out by option("--out", help = "File (default: stdout)").file(canBeDir = false)
    private val party by option("--party", help = "Party id or name (party-statement)")

    override fun run() = cli.withData(write = false) {
        val kind = ReportKind.of(kindKey)!!
        val fmt = if (cli.json && format == "table") "json" else format
        if ((kind == ReportKind.SALES_REGISTER || kind == ReportKind.PURCHASE_REGISTER) && (fmt == "csv" || (fmt == "table" && out != null))) {
            // Registers grow with the books: stream them week by week (docs/14-performance.md).
            val period = reportPeriod(from, to)
            cli.emitStreamed(out) { w -> repos.writeRegisterCsv(firm().id, period, kind == ReportKind.SALES_REGISTER, w, cli.progress) }
            cli.progressDone()
            return@withData
        }
        val data = reportData(from, to, kind)
        val partyId = party?.let { p ->
            data.parties.filter { it.id == p || it.name.equals(p, true) }.singleOrNull()?.id ?: fail(Exit.NOT_FOUND, "No single party matches '$p'")
        }
        if (kind.needsParty && partyId == null) fail(Exit.USAGE, "${kind.key} needs --party")
        cli.render(Reports.run(kind, data, partyId), fmt, out, data.period)
    }
}

class ReportRun(cli: CliState) : ReportNamed(cli, null) {
    private val name by argument(help = "Report name").choice(*ReportKind.entries.map { it.key }.toTypedArray())
    override val kindKey: String get() = name
}

/** `gst gstr3b|hsn` with `--period YYYY-MM` or `--from/--to`. */
class PeriodReport(private val cli: CliState, name: String, private val kind: ReportKind) : CliktCommand(name) {
    override fun help(context: Context) = kind.title
    private val period by option("--period", help = "YYYY-MM")
    private val from by option("--from")
    private val to by option("--to")
    private val format by option("--format").choice("table", "csv", "json").default("table")
    private val out by option("--out").file(canBeDir = false)

    override fun run() = cli.withData(write = false) {
        val p = period?.let { runCatching { ReportPeriod.month(it) }.getOrElse { e -> fail(Exit.USAGE, e.message!!) } }
        val data = reportData(p?.from?.toString() ?: from, p?.to?.toString() ?: to, kind)
        cli.render(Reports.run(kind, data), if (cli.json && format == "table") "json" else format, out, data.period)
    }
}

/** `gst gstr1 --period YYYY-MM [--format json|csv] [--out FILE]`: GSTN offline-tool JSON. */
class Gstr1Cmd(private val cli: CliState) : CliktCommand("gstr1") {
    override fun help(context: Context) = "GSTR-1 for one month: offline-tool JSON (default) or a CSV section summary"
    private val period by option("--period", help = "YYYY-MM").required()
    private val format by option("--format").choice("json", "csv", "table").default("json")
    private val out by option("--out", help = "File (default: stdout)").file(canBeDir = false)

    override fun run() = cli.withData(write = false) {
        val p = runCatching { ReportPeriod.month(period) }.getOrElse { fail(Exit.USAGE, it.message!!) }
        val data = reportData(p.from.toString(), p.to.toString(), ReportKind.GSTR1)
        val g = Gstr1.build(data)
        if (format == "json") {
            if (data.firm.gstin.isNullOrBlank()) fail(Exit.VALIDATION, "The firm has no GSTIN; GSTR-1 needs one")
            cli.emit(g.toJson(), out)
            g.skipped.forEach { cli.info("warning: $it") }
        } else {
            cli.render(g.summaryTable(), format, out, data.period)
        }
    }
}

/** `tally export --from --to --out FILE.xml` (also `export tally`). */
class TallyExport(private val cli: CliState, name: String) : CliktCommand(name) {
    override fun help(context: Context) = "Tally XML (vouchers and ledgers) for a date range"
    private val from by option("--from")
    private val to by option("--to")
    private val out by option("--out", help = "File (default: stdout)").file(canBeDir = false)

    override fun run() = cli.withData(write = false) {
        val period = reportPeriod(from, to)
        val firmId = firm().id
        cli.emitStreamed(out) { w -> repos.writeTallyXml(firmId, period, w, cli.progress) }
        cli.progressDone()
    }
}

class AuditList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "Recent audit events, newest first"
    private val limit by option("--limit").int().default(50)

    override fun run() = cli.withData(write = false) {
        val events = repos.audit.observeRecent(firm().id, limit).first()
        if (cli.json) {
            cli.printJson(toJson(events.map { jsonOf("id" to it.id, "at" to it.createdAt.toString(), "action" to it.action, "entityType" to it.entityType, "entityId" to it.entityId, "voucherId" to it.voucherId, "details" to it.detailsJson) }))
        } else {
            cli.printTable(listOf(Col("AT"), Col("ACTION"), Col("ENTITY"), Col("DETAILS")), events.map { listOf(it.createdAt.toString(), it.action, "${it.entityType}:${it.entityId}", it.detailsJson.orEmpty()) })
        }
    }
}
