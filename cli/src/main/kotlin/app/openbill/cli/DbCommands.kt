package app.openbill.cli

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.openBillDatabase
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

fun dbCommands(cli: CliState) = Group("db", "Database checks and migration").subcommands(DbInfo(cli), DbCheck(cli), DbMigrate(cli))

/**
 * Runs [block] on a consistent read-only copy of the data: the extracted `--backup` archive, or a
 * `VACUUM INTO` snapshot of the live database (so the running app is never blocked).
 */
fun <T> CliState.withSnapshot(block: (File) -> T): T {
    val tmp = Files.createTempDirectory("openbill-check").toFile()
    try {
        val db = File(tmp, "openbill.db")
        val archive = backup
        if (archive != null) {
            OpenBillBackup.extract(archive, File(tmp, "x"), OPENBILL_DATABASE_VERSION, backupPassword(archive)).databaseFile.copyTo(db)
        } else {
            val live = location().databaseFile
            if (!live.isFile) fail(Exit.NOT_FOUND, "No OpenBill database in ${live.parentFile}")
            OpenBillBackup.snapshot(live, db)
        }
        return block(db)
    } finally {
        tmp.deleteRecursively()
    }
}

/** Rows of a read-only query, columns joined by spaces. */
fun <T> readOnlySql(db: File, block: ((String) -> List<String>) -> T): T {
    val c = BundledSQLiteDriver().open(db.path, SQLITE_OPEN_READONLY)
    try {
        return block { sql ->
            c.prepare(sql).use { s ->
                buildList { while (s.step()) add((0 until s.getColumnCount()).joinToString(" ") { if (s.isNull(it)) "null" else s.getText(it) }) }
            }
        }
    } finally {
        c.close()
    }
}

class DbInfo(private val cli: CliState) : CliktCommand("info") {
    override fun help(context: Context) = "Path, size, schema version, firm count and rows per table"

    override fun run() {
        val path = cli.backup ?: cli.location().databaseFile
        val size = if (cli.backup != null) path.length() else listOf("", "-wal").sumOf { File(path.path + it).takeIf(File::isFile)?.length() ?: 0L }
        val (schema, firms, counts) = cli.withSnapshot { db ->
            readOnlySql(db) { rows ->
                val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%' ORDER BY name")
                Triple(
                    rows("PRAGMA user_version").single().toInt(),
                    rows("SELECT COUNT(*) FROM firms WHERE deletedAt IS NULL").single().toLong(),
                    tables.associateWith { rows("SELECT COUNT(*) FROM `$it`").single().toLong() },
                )
            }
        }
        if (cli.json) {
            cli.printJson(
                jsonOf(
                    "path" to path.path, "sizeBytes" to size, "schemaVersion" to schema, "currentSchemaVersion" to OPENBILL_DATABASE_VERSION,
                    "firms" to firms, "tables" to kotlinx.serialization.json.JsonObject(counts.mapValues { toJson(it.value) }),
                ),
            )
        } else if (cli.quiet) {
            cli.out.println(path.path)
        } else {
            cli.printFields(
                listOf(
                    "Path" to path.path, "Size" to "$size bytes", "Schema" to (if (schema == OPENBILL_DATABASE_VERSION) "$schema (current)" else "$schema (this openbill: $OPENBILL_DATABASE_VERSION)"),
                    "Firms" to firms,
                ),
            )
            cli.out.println()
            cli.printTable(listOf(Col("TABLE"), Col("ROWS", true)), counts.map { listOf(it.key, it.value.toString()) })
        }
    }
}

/** Opens the database through Room once, which runs any pending migrations. */
fun openAndMigrate(db: File) {
    val database = openBillDatabase(db.absolutePath)
    try {
        runBlocking { database.roomRepositories().firms.findFirm("") }
    } finally {
        database.close()
    }
}

class DbCheck(private val cli: CliState) : CliktCommand("check") {
    override fun help(context: Context) = "Integrity, foreign keys, journal balance, and audit triggers (exit 7 on failure)"

    override fun run() {
        val results = cli.withSnapshot(::check)
        if (cli.json) {
            cli.printJson(jsonOf("ok" to results.all { it.second == null }, "checks" to results.map { jsonOf("name" to it.first, "ok" to (it.second == null), "detail" to it.second) }))
        } else {
            cli.printTable(listOf(Col("CHECK"), Col("RESULT")), results.map { listOf(it.first, it.second ?: "ok") })
        }
        if (results.any { it.second != null }) fail(Exit.INTEGRITY, "Database check failed")
    }

    private fun check(db: File): List<Pair<String, String?>> = readOnlySql(db) { rows ->
            val integrity = rows("PRAGMA integrity_check")
            val fk = rows("PRAGMA foreign_key_check")
            val unbalanced = rows(
                "SELECT voucherId, SUM(debit), SUM(credit) FROM journal_entries GROUP BY voucherId HAVING SUM(debit) <> SUM(credit)",
            )
            val triggers = rows("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            val schema = rows("PRAGMA user_version").single().toInt()
            listOf(
                "schema version" to (if (schema == OPENBILL_DATABASE_VERSION) null else "$schema, expected $OPENBILL_DATABASE_VERSION"),
                "integrity" to (if (integrity == listOf("ok")) null else integrity.take(5).joinToString("; ")),
                "foreign keys" to (if (fk.isEmpty()) null else "${fk.size} violation(s): ${fk.take(3).joinToString("; ")}"),
                "journal balance" to (if (unbalanced.isEmpty()) null else "${unbalanced.size} unbalanced voucher(s): ${unbalanced.take(3).joinToString("; ")}"),
                "audit triggers" to (if (triggers.isNotEmpty()) null else "missing"),
            )
    }
}

class DbMigrate(private val cli: CliState) : CliktCommand("migrate") {
    override fun help(context: Context) = "Back up, then migrate the database to the current schema"
    private val dryRun by option("--dry-run", help = "List pending migrations only").flag()

    override fun run() {
        cli.exclusive { loc ->
            if (!loc.databaseFile.isFile) fail(Exit.NOT_FOUND, "No OpenBill database in ${loc.databaseFile.parentFile}")
            val from = OpenBillBackup.schemaVersion(loc.databaseFile)
            if (from > OPENBILL_DATABASE_VERSION) fail(Exit.SCHEMA, "Database schema $from is newer than this openbill ($OPENBILL_DATABASE_VERSION)")
            val pending = (from until OPENBILL_DATABASE_VERSION).map { "$it -> ${it + 1}" }
            if (dryRun || pending.isEmpty()) {
                if (cli.json) cli.printJson(jsonOf("from" to from, "to" to OPENBILL_DATABASE_VERSION, "pending" to pending, "applied" to false))
                else cli.out.println(if (pending.isEmpty()) "Schema $from is current" else "Pending: ${pending.joinToString(", ")}")
                return@exclusive
            }
            val backup = OpenBillBackup.backupBeforeMigration(loc, File(loc.databaseFile.parentFile, "backups"), OPENBILL_DATABASE_VERSION, cli.env.appVersion, cli.backupProgress).also { cli.progressDone() }
            cli.progress(app.openbill.core.model.Progress("Upgrading database"))
            openAndMigrate(loc.databaseFile)
            cli.progressDone()
            if (cli.json) cli.printJson(jsonOf("from" to from, "to" to OPENBILL_DATABASE_VERSION, "pending" to pending, "applied" to true, "backup" to backup?.path))
            else cli.out.println("Migrated $from -> $OPENBILL_DATABASE_VERSION; backup ${backup?.path}")
        }
    }
}
