package app.openbill.cli

import app.openbill.core.backup.BackupException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), CliEnv()))
}

/** Parses and runs one command; returns the documented exit code. Used by main and tests. */
fun runCli(args: List<String>, env: CliEnv): Int {
    val cli = CliState(env)
    val root = rootCommand(cli)
    return try {
        root.parse(args)
        Exit.OK
    } catch (e: CliFailure) {
        env.err.println("error: ${e.message}")
        e.code
    } catch (e: BackupException) {
        env.err.println("error: ${e.message}")
        backupExitCode(e)
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: PrintHelpMessage) {
        env.out.println(root.getFormattedHelp(e))
        if (e.error) Exit.USAGE else Exit.OK
    } catch (e: PrintMessage) {
        (if (e.printError) env.err else env.out).println(e.message)
        e.statusCode
    } catch (e: UsageError) {
        env.err.println(root.getFormattedHelp(e))
        Exit.USAGE
    } catch (e: CliktError) {
        env.err.println(root.getFormattedHelp(e) ?: e.message)
        if (e.statusCode == 0) Exit.OK else Exit.USAGE
    } catch (e: IllegalArgumentException) {
        // Domain `require` failures: validation (exit 3).
        env.err.println("error: ${e.message}")
        Exit.VALIDATION
    } catch (e: IllegalStateException) {
        env.err.println("error: ${e.message}")
        Exit.CONFLICT
    } catch (e: Exception) {
        env.err.println("internal error: ${e::class.simpleName}: ${e.message}")
        Exit.INTERNAL
    }
}

class Root(private val cli: CliState) : CliktCommand(name = "openbill") {
    override fun help(context: Context) = "OpenBill command-line client (direct mode)."

    private val dataDir by option("--data-dir", help = "Data directory (default: the desktop app's)").file(canBeFile = false)
    private val backup by option("--backup", help = "Read-only access to a backup archive").file(mustExist = true, canBeDir = false)
    private val json by option("--json", help = "Machine-readable JSON output").flag()
    private val quiet by option("-q", "--quiet", help = "Print only the essential value").flag()
    private val yes by option("-y", "--yes", help = "Do not prompt for destructive operations").flag()
    private val firm by option("--firm", help = "Firm id, GSTIN, or name")

    override fun run() {
        if (dataDir != null && backup != null) throw UsageError("--data-dir and --backup are mutually exclusive")
        cli.dataDir = dataDir
        cli.backup = backup
        cli.json = json
        cli.quiet = quiet
        cli.yes = yes
        cli.firm = firm
    }
}

/** A command group with help text and no action of its own. */
open class Group(name: String, private val text: String) : CliktCommand(name) {
    override fun help(context: Context) = text
    override fun run() = Unit
}

class Version(private val cli: CliState) : CliktCommand("version") {
    override fun help(context: Context) = "Print the version"
    override fun run() {
        if (cli.json) cli.printJson(jsonOf("version" to cli.env.appVersion, "schemaVersion" to app.openbill.core.database.OPENBILL_DATABASE_VERSION))
        else cli.out.println("openbill ${cli.env.appVersion} (schema ${app.openbill.core.database.OPENBILL_DATABASE_VERSION})")
    }
}

fun rootCommand(cli: CliState): CliktCommand = Root(cli).subcommands(
    Version(cli),
    backupCommands(cli),
    dbCommands(cli),
    Group("firm", "Firms (businesses)").subcommands(FirmList(cli), FirmShow(cli)),
    Group("party", "Customers and suppliers").subcommands(PartyList(cli), PartyShow(cli), PartyAdd(cli)),
    Group("item", "Items and services").subcommands(ItemList(cli), ItemShow(cli), ItemAdd(cli)),
    invoiceCommands(cli),
    Group("import", "Import master data from CSV").subcommands(ImportCsv(cli, "parties"), ImportCsv(cli, "items")),
    Group("export", "Export data as CSV, JSON or Tally XML").subcommands(ExportCmd(cli, "parties"), ExportCmd(cli, "items"), ExportCmd(cli, "invoices"), ExportCsv(cli), TallyExport(cli, "tally")),
    paymentCommands(cli),
    stockCommands(cli),
    reportCommands(cli),
    gstCommands(cli),
    tallyCommands(cli),
    auditCommands(cli),
)
