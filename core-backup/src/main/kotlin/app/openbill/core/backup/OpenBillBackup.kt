package app.openbill.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why a backup operation failed; the CLI maps each kind to an exit code (docs/11-cli.md). */
enum class BackupFailure { INVALID, INTEGRITY, SCHEMA_TOO_NEW, NO_SPACE }

class BackupException(val kind: BackupFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where an installation keeps its data: the SQLite file plus attachment directories (firm logos
 * and signatures), each archived as `attachments/<name>/...`.
 */
data class DataLocation(val databaseFile: File, val attachmentDirs: Map<String, File>)

/** A verified archive extracted to a staging directory, ready to [OpenBillBackup.commit]. */
class PreparedRestore(val manifest: BackupManifest, val stagingDir: File) {
    val databaseFile: File get() = File(stagingDir, DATABASE_ENTRY)
    fun attachmentDir(name: String): File = File(stagingDir, "$ATTACHMENTS/$name")
}

internal const val DATABASE_ENTRY = "database.sqlite"
internal const val MANIFEST_ENTRY = "manifest.json"
internal const val CHECKSUMS_ENTRY = "checksums.sha256"
internal const val ATTACHMENTS = "attachments"

/**
 * Backup archive (`*.openbill.zip`): manifest.json, database.sqlite (a `VACUUM INTO` snapshot),
 * attachments/, and checksums.sha256 for `sha256sum -c`. See docs/03-architecture.md section 14.
 */
object OpenBillBackup {
    const val FORMAT_VERSION = 1
    private val driver = BundledSQLiteDriver()

    /** `backup-<firm>-<timestamp>.openbill.zip` */
    fun defaultFileName(firmName: String?, at: Instant): String {
        val slug = firmName?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.take(40)?.ifBlank { null } ?: "openbill"
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(at)
        return "backup-$slug-$stamp.openbill.zip"
    }

    /** `PRAGMA user_version` of [db] (Room stores the schema version there); 0 for a new file. */
    fun schemaVersion(db: File): Int = if (!db.isFile) 0 else readOnly(db) { it.longQuery("PRAGMA user_version").toInt() }

    /**
     * Writes a verified archive to [out]: snapshot with `VACUUM INTO` (safe while the app has the
     * database open), zip to a temporary file next to [out], verify it, then rename atomically.
     * With a [password] the verified zip is sealed by [BackupCrypto] before the rename.
     */
    fun create(source: DataLocation, out: File, appVersion: String, now: Instant = Instant.now(), password: CharArray? = null): BackupManifest {
        require(source.databaseFile.isFile) { "No database at ${source.databaseFile}" }
        val dir = out.absoluteFile.parentFile.apply { mkdirs() }
        val work = Files.createTempDirectory(dir.toPath(), ".openbill-backup").toFile()
        try {
            val snapshot = File(work, DATABASE_ENTRY)
            snapshot(source.databaseFile, snapshot)
            val entries = linkedMapOf(DATABASE_ENTRY to snapshot)
            source.attachmentDirs.forEach { (name, d) ->
                d.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.path }.forEach {
                    entries["$ATTACHMENTS/$name/${it.relativeTo(d).invariantSeparatorsPath}"] = it
                }
            }
            val manifest = describe(snapshot, appVersion, now).copy(files = entries.mapValues { sha256(it.value) })
            val tmp = File(work, "archive.zip")
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toJson().toByteArray())
                zip.closeEntry()
                entries.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(CHECKSUMS_ENTRY))
                zip.write(manifest.files.entries.joinToString("") { "${it.value}  ${it.key}\n" }.toByteArray())
                zip.closeEntry()
            }
            verify(tmp, Int.MAX_VALUE)
            val sealed = if (password == null || password.isEmpty()) tmp else File(work, "archive.enc").also { enc ->
                tmp.inputStream().buffered().use { i -> enc.outputStream().buffered().use { BackupCrypto.encrypt(i, it, password) } }
            }
            Files.move(sealed.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return manifest
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Checks the manifest, every checksum, the schema version against [currentSchemaVersion], and
     * `integrity_check` on a temporary copy of the database. Returns the manifest.
     */
    fun verify(archive: File, currentSchemaVersion: Int, password: CharArray? = null): BackupManifest {
        val staging = Files.createTempDirectory("openbill-verify").toFile()
        try {
            return extract(archive, staging, currentSchemaVersion, password).manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Verifies [archive] and extracts it into [stagingDir] (created; must be empty). An encrypted
     * archive needs its [password]; it is decrypted to a temporary file beside [stagingDir].
     */
    fun extract(archive: File, stagingDir: File, currentSchemaVersion: Int, password: CharArray? = null): PreparedRestore {
        if (!archive.isFile) throw BackupException(BackupFailure.INVALID, "No such file: $archive")
        stagingDir.mkdirs()
        val plain = BackupCrypto.plain(archive, password, stagingDir.absoluteFile.parentFile)
        try {
            return extractPlain(plain, stagingDir, currentSchemaVersion)
        } finally {
            if (plain != archive) plain.delete()
        }
    }

    private fun extractPlain(archive: File, stagingDir: File, currentSchemaVersion: Int): PreparedRestore {
        val root = stagingDir.canonicalFile
        var manifest: BackupManifest? = null
        val actual = mutableMapOf<String, String>()
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when (entry.name) {
                        MANIFEST_ENTRY -> manifest = BackupManifest.parse(zip.readBytes().decodeToString())
                        CHECKSUMS_ENTRY -> zip.readBytes()
                        else -> {
                            val target = File(root, entry.name).canonicalFile
                            if (!target.path.startsWith(root.path + File.separator)) {
                                throw BackupException(BackupFailure.INVALID, "Unsafe entry ${entry.name}")
                            }
                            target.parentFile.mkdirs()
                            actual[entry.name] = target.outputStream().use { copyHashing(zip, it) }
                        }
                    }
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupFailure.INVALID, "Not a readable OpenBill backup: ${e.message}", e)
        }
        val m = manifest ?: throw BackupException(BackupFailure.INVALID, "Archive has no manifest.json")
        if (m.formatVersion > FORMAT_VERSION) {
            throw BackupException(BackupFailure.SCHEMA_TOO_NEW, "Backup format ${m.formatVersion} is newer than this OpenBill supports ($FORMAT_VERSION)")
        }
        if (DATABASE_ENTRY !in m.files) throw BackupException(BackupFailure.INVALID, "Manifest lists no database")
        if (m.files.keys != actual.keys) {
            throw BackupException(BackupFailure.INTEGRITY, "Archive entries do not match the manifest")
        }
        m.files.forEach { (name, sum) ->
            if (actual[name] != sum) throw BackupException(BackupFailure.INTEGRITY, "Checksum mismatch for $name")
        }
        if (m.schemaVersion > currentSchemaVersion) {
            throw BackupException(
                BackupFailure.SCHEMA_TOO_NEW,
                "Backup schema ${m.schemaVersion} is newer than this OpenBill ($currentSchemaVersion); update OpenBill first",
            )
        }
        val db = File(root, DATABASE_ENTRY)
        val check = readOnly(db) { c -> c.textQuery("PRAGMA integrity_check") to c.longQuery("PRAGMA user_version").toInt() }
        if (check.first != "ok") throw BackupException(BackupFailure.INTEGRITY, "Database integrity check failed: ${check.first}")
        if (check.second != m.schemaVersion) throw BackupException(BackupFailure.INTEGRITY, "Database schema does not match the manifest")
        return PreparedRestore(m, root)
    }

    /**
     * Restores [archive] over [target]: verify and extract next to the database (same file
     * system), then [commit]. Validation failures leave the current data untouched. The caller
     * must have closed the database. Returns the safety copy directory.
     */
    fun restore(archive: File, target: DataLocation, currentSchemaVersion: Int, now: Instant = Instant.now(), password: CharArray? = null): File {
        val dataDir = target.databaseFile.absoluteFile.parentFile.apply { mkdirs() }
        val needed = archive.length() * 3
        if (dataDir.usableSpace in 1 until needed) throw BackupException(BackupFailure.NO_SPACE, "Not enough free space to restore")
        val staging = Files.createTempDirectory(dataDir.toPath(), ".restore-").toFile()
        try {
            return commit(extract(archive, staging, currentSchemaVersion, password), target, now)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Swaps the staged data in. The current database (with -wal/-shm) and attachment directories
     * move to `restore-safety-<timestamp>/` next to the database, kept until the user deletes it;
     * on any failure every move is undone. [prepared] must be on the same file system.
     */
    fun commit(prepared: PreparedRestore, target: DataLocation, now: Instant = Instant.now()): File {
        val db = target.databaseFile.absoluteFile
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now)
        val safety = File(db.parentFile, "restore-safety-$stamp").let { base ->
            generateSequence(0) { it + 1 }.map { if (it == 0) base else File("${base.path}-$it") }.first { !it.exists() }
        }.apply { mkdirs() }
        val done = ArrayList<Pair<File, File>>() // from -> to, for rollback
        fun move(from: File, to: File) {
            to.parentFile.mkdirs()
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            done += from to to
        }
        try {
            listOf("", "-wal", "-shm", "-journal").map { File(db.path + it) }.filter { it.exists() }.forEach { move(it, File(safety, it.name)) }
            target.attachmentDirs.forEach { (name, dir) ->
                if (dir.exists()) move(dir, File(safety, "$ATTACHMENTS/$name"))
            }
            move(prepared.databaseFile, db)
            target.attachmentDirs.forEach { (name, dir) ->
                prepared.attachmentDir(name).takeIf { it.isDirectory }?.let { move(it, dir) }
            }
        } catch (e: Exception) {
            done.asReversed().forEach { (from, to) -> runCatching { Files.move(to.toPath(), from.toPath(), StandardCopyOption.ATOMIC_MOVE) } }
            throw BackupException(BackupFailure.INVALID, "Restore failed and was rolled back: ${e.message}", e)
        }
        return safety
    }

    /**
     * Pre-migration hook: when the database exists with an older schema than [currentSchemaVersion],
     * writes a backup into [backupDir] before Room migrates it. Returns the archive, or null.
     */
    fun backupBeforeMigration(source: DataLocation, backupDir: File, currentSchemaVersion: Int, appVersion: String): File? {
        val v = runCatching { schemaVersion(source.databaseFile) }.getOrDefault(0)
        if (v == 0 || v >= currentSchemaVersion) return null
        val now = Instant.now()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now)
        val out = File(backupDir, "pre-migration-v$v-$stamp.openbill.zip")
        create(source, out, appVersion, now)
        return out
    }

    /**
     * Scheduled backup: when the newest `auto-*.openbill.zip` in [backupDir] is older than
     * [everyDays], writes a new one, then keeps only the newest [keep]. Returns the new archive, or null.
     */
    fun autoBackup(source: DataLocation, backupDir: File, appVersion: String, everyDays: Int = 1, keep: Int = 7, now: Instant = Instant.now()): File? {
        if (!source.databaseFile.isFile) return null
        fun autos() = backupDir.listFiles { f -> f.name.startsWith("auto-") && f.name.endsWith(".openbill.zip") }.orEmpty().sortedByDescending { it.name }
        val newest = autos().firstOrNull()?.lastModified() ?: 0L
        if (now.toEpochMilli() - newest < everyDays * 86_400_000L) return null
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now)
        val out = File(backupDir, "auto-$stamp.openbill.zip")
        create(source, out, appVersion, now)
        out.setLastModified(now.toEpochMilli())
        autos().drop(keep).forEach { it.delete() }
        return out
    }

    /** Consistent copy of a live database. */
    fun snapshot(db: File, out: File) {
        out.delete()
        val c = driver.open(db.path)
        try {
            c.prepare("VACUUM INTO ?").use { it.bindText(1, out.path); it.step() }
        } finally {
            c.close()
        }
    }

    private fun describe(snapshot: File, appVersion: String, now: Instant): BackupManifest = readOnly(snapshot) { c ->
        val firms = c.prepare("SELECT id, name FROM firms ORDER BY createdAt, id").use { s ->
            buildList { while (s.step()) add(BackupFirm(s.getText(0), s.getText(1))) }
        }
        val counts = COUNTED_TABLES.associateWith { t -> runCatching { c.longQuery("SELECT COUNT(*) FROM `$t`") }.getOrDefault(0L) }
        BackupManifest(
            formatVersion = FORMAT_VERSION,
            appVersion = appVersion,
            schemaVersion = c.longQuery("PRAGMA user_version").toInt(),
            createdAt = now.toString(),
            firms = firms,
            recordCounts = counts,
            files = emptyMap(),
        )
    }

    private val COUNTED_TABLES = listOf("firms", "parties", "items", "vouchers", "voucher_lines", "journal_entries", "stock_movements", "audit_events")

    private fun <T> readOnly(db: File, block: (SQLiteConnection) -> T): T {
        val c = try {
            driver.open(db.path, SQLITE_OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupException(BackupFailure.INTEGRITY, "Cannot open database: ${e.message}", e)
        }
        try {
            return block(c)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupFailure.INTEGRITY, "Database is not readable: ${e.message}", e)
        } finally {
            c.close()
        }
    }

    internal fun sha256(file: File): String = file.inputStream().use { copyHashing(it, null) }

    private fun copyHashing(input: InputStream, out: java.io.OutputStream?): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            out?.write(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }
internal fun SQLiteConnection.textQuery(sql: String): String = prepare(sql).use { it.step(); it.getText(0) }
