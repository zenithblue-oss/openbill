package app.openbill.cli

import app.openbill.core.backup.BackupManifest
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.time.Instant

fun backupCommands(cli: CliState) = Group("backup", "Create, verify, and restore backup archives").subcommands(
    BackupCreate(cli), BackupVerify(cli), BackupRestore(cli),
)

private fun CliState.printManifest(m: BackupManifest, path: File?) {
    if (json) {
        printJson(
            jsonOf(
                "path" to path?.path, "formatVersion" to m.formatVersion, "appVersion" to m.appVersion,
                "schemaVersion" to m.schemaVersion, "createdAt" to m.createdAt,
                "firms" to m.firms.map { jsonOf("id" to it.id, "name" to it.name) },
                "recordCounts" to kotlinx.serialization.json.JsonObject(m.recordCounts.mapValues { toJson(it.value) }),
            ),
        )
    } else if (quiet) {
        path?.let { out.println(it.path) }
    } else {
        printFields(
            listOfNotNull(
                path?.let { "Archive" to it.path },
                "Created" to m.createdAt,
                "App version" to m.appVersion,
                "Schema" to m.schemaVersion,
                "Firms" to m.firms.joinToString { it.name },
            ) + m.recordCounts.map { "  ${it.key}" to it.value },
        )
    }
}

class BackupCreate(private val cli: CliState) : CliktCommand("create") {
    override fun help(context: Context) = "Snapshot the database and attachments into a verified archive; prints its path"
    private val out by option("--out", help = "Directory or .openbill.zip file (default: current directory)").file()

    override fun run() {
        if (cli.backup != null) fail(Exit.USAGE, "backup create needs a data directory, not --backup")
        val loc = cli.location()
        if (!loc.databaseFile.isFile) fail(Exit.NOT_FOUND, "No OpenBill database in ${loc.databaseFile.parentFile}")
        // VACUUM INTO is a consistent read: safe while the app is running, so no lock is needed.
        val firmName = cli.withData(write = false) { runCatching { firm().name }.getOrNull() }
        val now = Instant.ofEpochMilli(cli.env.clock.now().toEpochMilliseconds())
        val target = out.let { o ->
            when {
                o == null -> File(OpenBillBackup.defaultFileName(firmName, now))
                o.isDirectory || !o.name.endsWith(".zip") -> File(o.apply { mkdirs() }, OpenBillBackup.defaultFileName(firmName, now))
                else -> o
            }
        }
        val manifest = OpenBillBackup.create(loc, target, cli.env.appVersion, now, cli.env.backupPassword)
        cli.printManifest(manifest, target)
    }
}

class BackupVerify(private val cli: CliState) : CliktCommand("verify") {
    override fun help(context: Context) = "Check manifest, checksums, schema version, and database integrity (exit 7 on failure)"
    private val file by argument().file(mustExist = true, canBeDir = false)

    override fun run() {
        val m = OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, cli.env.backupPassword)
        cli.printManifest(m, file)
        if (!cli.json) cli.info("OK")
    }
}

class BackupRestore(private val cli: CliState) : CliktCommand("restore") {
    override fun help(context: Context) = "Replace local data with an archive; keeps a safety copy of the current data"
    private val file by argument().file(mustExist = true, canBeDir = false)

    override fun run() {
        val m = OpenBillBackup.verify(file, OPENBILL_DATABASE_VERSION, cli.env.backupPassword)
        cli.exclusive { loc ->
            if (!cli.json && !cli.quiet) cli.printManifest(m, file)
            cli.confirm("Replace the data in ${loc.databaseFile.parentFile} with this backup?")
            val safety = OpenBillBackup.restore(file, loc, OPENBILL_DATABASE_VERSION, password = cli.env.backupPassword)
            // Bring an older archive up to the current schema now, while we hold the lock.
            openAndMigrate(loc.databaseFile)
            if (cli.json) cli.printJson(jsonOf("restored" to file.path, "safetyCopy" to safety.path))
            else {
                cli.info("Restored. Previous data kept in $safety")
                if (cli.quiet) cli.out.println(safety.path)
            }
        }
    }
}

