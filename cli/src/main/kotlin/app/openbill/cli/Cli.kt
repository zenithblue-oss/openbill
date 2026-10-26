package app.openbill.cli

import app.openbill.core.backup.BackupException
import app.openbill.core.backup.BackupFailure
import app.openbill.core.backup.DataLocation
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Ulid
import java.io.File
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Exit codes, a public contract (docs/11-cli.md section 4). */
object Exit {
    const val OK = 0
    const val INTERNAL = 1
    const val USAGE = 2
    const val VALIDATION = 3
    const val NOT_FOUND = 4
    const val CONFLICT = 5
    const val LOCKED = 6
    const val INTEGRITY = 7
    const val DENIED = 8
    const val SCHEMA = 10
}

class CliFailure(val code: Int, message: String) : Exception(message)

fun fail(code: Int, message: String): Nothing = throw CliFailure(code, message)

/** Process environment, injected so tests get a fixed clock and deterministic ids. */
class CliEnv(
    val out: PrintStream = System.out,
    val err: PrintStream = System.err,
    val clock: Clock = Clock.System,
    val newId: () -> String = { Ulid.create(Clock.System.now().toEpochMilliseconds()).toString() },
    val interactive: Boolean = System.console() != null,
    val readLine: () -> String? = { readlnOrNull() },
    val appVersion: String = CliEnv::class.java.`package`?.implementationVersion ?: "dev",
    val defaultDataDir: () -> File = ::desktopDataDirectory,
)

/** Global options, filled by the root command before a subcommand runs. */
class CliState(val env: CliEnv) {
    var dataDir: File? = null
    var backup: File? = null
    var json = false
    var quiet = false
    var yes = false
    var firm: String? = null

    val out get() = env.out

    fun info(msg: String) {
        if (!quiet) env.err.println(msg)
    }

    /** Confirmation for destructive operations: `--yes`, else a terminal prompt, else exit 2. */
    fun confirm(question: String) {
        if (yes) return
        if (!env.interactive) fail(Exit.USAGE, "$question Refusing without --yes (not a terminal)")
        env.err.print("$question [y/N] ")
        env.err.flush()
        if (env.readLine()?.trim()?.lowercase() !in setOf("y", "yes")) fail(Exit.USAGE, "Cancelled")
    }

    fun location(): DataLocation {
        val dir = dataDir ?: env.defaultDataDir()
        return DataLocation(File(dir, "openbill.db"), mapOf("logos" to File(dir, "logos"), "attachments" to File(dir, "attachments")))
    }

    /**
     * Runs [block] against the data source. Direct mode takes the desktop app's lock for writes
     * (exit 6 when the app runs); reads while the app runs use a `VACUUM INTO` snapshot. Backup
     * mode verifies and extracts the archive to a temporary directory and rejects writes.
     */
    fun <T> withData(write: Boolean, block: suspend Data.() -> T): T {
        backup?.let { archive ->
            if (write) fail(Exit.DENIED, "A backup file is read-only; writes are rejected")
            val tmp = Files.createTempDirectory("openbill-cli").toFile()
            try {
                val prepared = OpenBillBackup.extract(archive, tmp, OPENBILL_DATABASE_VERSION)
                return open(prepared.databaseFile, File(tmp, "attachments"), block)
            } finally {
                tmp.deleteRecursively()
            }
        }
        val loc = location()
        val dataDir = loc.databaseFile.parentFile
        if (!loc.databaseFile.isFile) fail(Exit.NOT_FOUND, "No OpenBill database in $dataDir")
        checkSchema(loc.databaseFile)
        val lock = InstanceLock.tryAcquire(dataDir)
        try {
            if (lock != null) return open(loc.databaseFile, dataDir, block)
            if (write) fail(Exit.LOCKED, "OpenBill is running for $dataDir (lock ${InstanceLock.file(dataDir)}); close it first")
            val tmp = Files.createTempDirectory("openbill-cli").toFile()
            try {
                val copy = File(tmp, "openbill.db")
                OpenBillBackup.snapshot(loc.databaseFile, copy)
                return open(copy, dataDir, block)
            } finally {
                tmp.deleteRecursively()
            }
        } finally {
            lock?.release()
        }
    }

    /** Exclusive access for schema changes and restore; no Room connection is opened. */
    fun <T> exclusive(block: (DataLocation) -> T): T {
        if (backup != null) fail(Exit.DENIED, "A backup file is read-only; writes are rejected")
        val loc = location()
        val dataDir = loc.databaseFile.parentFile.apply { mkdirs() }
        val lock = InstanceLock.tryAcquire(dataDir)
            ?: fail(Exit.LOCKED, "OpenBill is running for $dataDir (lock ${InstanceLock.file(dataDir)}); close it first")
        try {
            return block(loc)
        } finally {
            lock.release()
        }
    }

    private fun <T> open(db: File, dataDir: File, block: suspend Data.() -> T): T {
        val database = openBillDatabase(db.absolutePath)
        try {
            return runBlocking { Data(this@CliState, database, database.roomRepositories(), dataDir).block() }
        } finally {
            database.close()
        }
    }
}

fun checkSchema(db: File) {
    val v = OpenBillBackup.schemaVersion(db)
    if (v > OPENBILL_DATABASE_VERSION) fail(Exit.SCHEMA, "Database schema $v is newer than this openbill ($OPENBILL_DATABASE_VERSION); update openbill")
    if (v in 1 until OPENBILL_DATABASE_VERSION) fail(Exit.SCHEMA, "Database schema $v needs migration to $OPENBILL_DATABASE_VERSION; run `openbill db migrate`")
}

class Data(val cli: CliState, val database: OpenBillDatabase, val repos: Repositories, val dataDir: File) {
    fun now(): Instant = cli.env.clock.now()

    /** `--firm id|gstin|name`, or the only firm. */
    suspend fun firm(): Firm {
        val firms = repos.firms.observeFirms().first().filter { it.deletedAt == null }
        val sel = cli.firm
        if (sel == null) {
            return when (firms.size) {
                0 -> fail(Exit.NOT_FOUND, "No firm in this database")
                1 -> firms.single()
                else -> fail(Exit.USAGE, "Several firms; choose one with --firm <id|gstin|name>")
            }
        }
        val matches = firms.filter { it.id == sel || it.gstin.equals(sel, true) || it.name.equals(sel, true) }
        return when (matches.size) {
            0 -> fail(Exit.NOT_FOUND, "No firm matches '$sel'")
            1 -> matches.single()
            else -> fail(Exit.USAGE, "Firm '$sel' is ambiguous")
        }
    }
}

/** The desktop app's single-instance lock (desktopApp Main.kt): the CLI honours the same file. */
object InstanceLock {
    fun file(dataDir: File) = File(dataDir, "openbill.instance.lock")

    class Held(private val channel: FileChannel, private val lock: FileLock) {
        fun release() {
            runCatching { lock.release() }
            channel.close()
        }
    }

    fun tryAcquire(dataDir: File): Held? {
        val channel = FileChannel.open(file(dataDir).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) channel.close()
        return lock?.let { Held(channel, it) }
    }
}

/** Same rules as the desktop app (docs/07-desktop.md section 5). */
fun desktopDataDirectory(): File {
    System.getenv("OPENBILL_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        "win" in os -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "OpenBill") }
            ?: File(home, "AppData/Local/OpenBill")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "openbill")
    }
}

fun backupExitCode(e: BackupException): Int = when (e.kind) {
    BackupFailure.INTEGRITY -> Exit.INTEGRITY
    BackupFailure.SCHEMA_TOO_NEW -> Exit.SCHEMA
    BackupFailure.INVALID -> Exit.INTEGRITY
    BackupFailure.NO_SPACE -> Exit.INTERNAL
}
