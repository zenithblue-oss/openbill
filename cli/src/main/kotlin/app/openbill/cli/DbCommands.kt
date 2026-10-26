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

fun dbCommands(cli: CliState) = Group("db", "Database checks and migration").subcommands(DbCheck(cli), DbMigrate(cli))

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
        val tmp = Files.createTempDirectory("openbill-check").toFile()
        try {
            // Check a consistent snapshot so the running app is never blocked.
            val db = File(tmp, "openbill.db")
            if (cli.backup != null) {
                OpenBillBackup.extract(cli.backup!!, tmp, OPENBILL_DATABASE_VERSION, cli.env.backupPassword).databaseFile.copyTo(db)
            } else {
                val live = cli.location().databaseFile
                if (!live.isFile) fail(Exit.NOT_FOUND, "No OpenBill database in ${live.parentFile}")
                OpenBillBackup.snapshot(live, db)
            }
            val results = check(db)
            if (cli.json) {
                cli.printJson(jsonOf("ok" to results.all { it.second == null }, "checks" to results.map { jsonOf("name" to it.first, "ok" to (it.second == null), "detail" to it.second) }))
            } else {
                cli.printTable(listOf(Col("CHECK"), Col("RESULT")), results.map { listOf(it.first, it.second ?: "ok") })
            }
            if (results.any { it.second != null }) fail(Exit.INTEGRITY, "Database check failed")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun check(db: File): List<Pair<String, String?>> {
        val c = BundledSQLiteDriver().open(db.path, SQLITE_OPEN_READONLY)
        try {
            fun rows(sql: String): List<String> = c.prepare(sql).use { s ->
                buildList { while (s.step()) add((0 until s.getColumnCount()).joinToString(" ") { if (s.isNull(it)) "null" else s.getText(it) }) }
            }
            val integrity = rows("PRAGMA integrity_check")
            val fk = rows("PRAGMA foreign_key_check")
            val unbalanced = rows(
                "SELECT voucherId, SUM(debit), SUM(credit) FROM journal_entries GROUP BY voucherId HAVING SUM(debit) <> SUM(credit)",
            )
            val triggers = rows("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            val schema = rows("PRAGMA user_version").single().toInt()
            return listOf(
                "schema version" to (if (schema == OPENBILL_DATABASE_VERSION) null else "$schema, expected $OPENBILL_DATABASE_VERSION"),
                "integrity" to (if (integrity == listOf("ok")) null else integrity.take(5).joinToString("; ")),
                "foreign keys" to (if (fk.isEmpty()) null else "${fk.size} violation(s): ${fk.take(3).joinToString("; ")}"),
                "journal balance" to (if (unbalanced.isEmpty()) null else "${unbalanced.size} unbalanced voucher(s): ${unbalanced.take(3).joinToString("; ")}"),
                "audit triggers" to (if (triggers.isNotEmpty()) null else "missing"),
            )
        } finally {
            c.close()
        }
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
            val backup = OpenBillBackup.backupBeforeMigration(loc, File(loc.databaseFile.parentFile, "backups"), OPENBILL_DATABASE_VERSION, cli.env.appVersion)
            openAndMigrate(loc.databaseFile)
            if (cli.json) cli.printJson(jsonOf("from" to from, "to" to OPENBILL_DATABASE_VERSION, "pending" to pending, "applied" to true, "backup" to backup?.path))
            else cli.out.println("Migrated $from -> $OPENBILL_DATABASE_VERSION; backup ${backup?.path}")
        }
    }
}
