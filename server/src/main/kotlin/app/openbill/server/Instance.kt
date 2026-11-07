package app.openbill.server

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.openbill.core.model.Ulid
import java.io.File
import java.security.MessageDigest

/**
 * `instance.sqlite` (docs/10-server.md section 3): local accounts, hashed tokens, workspaces,
 * memberships and the server audit log (section 10, hash-chained). One connection behind one
 * lock: every call is a few rows. ponytail: global lock, a connection pool if sign-ins ever queue.
 */
class Instance(file: File, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    class User(val id: String, val username: String, val admin: Boolean)

    class Workspace(val id: String, val name: String, val createdAt: Long)

    private val c: SQLiteConnection = BundledSQLiteDriver().open(file.path)

    init {
        c.prepare("PRAGMA journal_mode = WAL").use { it.step() }
        c.execSQL("PRAGMA synchronous = FULL")
        c.execSQL("PRAGMA foreign_keys = ON")
        val version = c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        check(version <= SCHEMA) { "instance.sqlite is from a newer server (schema $version); downgrade refused" }
        if (version < 1) {
            tx {
                c.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                c.execSQL(
                    "CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE, passwordHash TEXT NOT NULL, " +
                        "admin INTEGER NOT NULL, disabled INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)",
                )
                c.execSQL(
                    "CREATE TABLE tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, userId TEXT NOT NULL REFERENCES users (id), " +
                        "parent TEXT, expiresAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
                )
                c.execSQL("CREATE INDEX tokens_parent ON tokens (parent)")
                c.execSQL("CREATE TABLE workspaces (id TEXT PRIMARY KEY, name TEXT NOT NULL, createdBy TEXT NOT NULL REFERENCES users (id), createdAt INTEGER NOT NULL)")
                c.execSQL(
                    "CREATE TABLE members (workspaceId TEXT NOT NULL REFERENCES workspaces (id), userId TEXT NOT NULL REFERENCES users (id), " +
                        "PRIMARY KEY (workspaceId, userId))",
                )
                c.execSQL(
                    "CREATE TABLE audit (n INTEGER PRIMARY KEY, at INTEGER NOT NULL, actor TEXT, action TEXT NOT NULL, target TEXT, " +
                        "address TEXT, detail TEXT, prevHash TEXT NOT NULL, hash TEXT NOT NULL)",
                )
                c.execSQL("PRAGMA user_version = $SCHEMA")
            }
        }
    }

    @Synchronized
    fun ping(): Boolean = c.prepare("SELECT 1").use { it.step() }

    @Synchronized
    fun hasUsers(): Boolean = c.prepare("SELECT EXISTS (SELECT 1 FROM users)").use { it.step(); it.getLong(0) == 1L }

    /** A new setup token replaces any earlier one; valid only while there is no user. */
    @Synchronized
    fun newSetupToken(): String = Tokens.new(Tokens.SETUP).also { token ->
        run("INSERT OR REPLACE INTO meta (key, value) VALUES ('setupToken', ?)", Tokens.hash(token))
    }

    /** Creates the first admin with the setup token (single use); false when the token is wrong or setup is done. */
    @Synchronized
    fun setup(token: String, username: String, passwordHash: String): User? = tx {
        val stored = c.prepare("SELECT value FROM meta WHERE key = 'setupToken'").use { if (it.step()) it.getText(0) else null }
        if (stored == null || hasUsers() || !Tokens.same(stored, Tokens.hash(token))) return@tx null
        run("DELETE FROM meta WHERE key = 'setupToken'")
        insertUser(username, passwordHash, admin = true)
    }

    @Synchronized
    fun createUser(username: String, passwordHash: String, admin: Boolean): User = tx {
        require(findUser(username) == null) { "User $username exists" }
        run("DELETE FROM meta WHERE key = 'setupToken'")
        insertUser(username, passwordHash, admin)
    }

    private fun insertUser(username: String, passwordHash: String, admin: Boolean): User {
        require(username.matches(USERNAME)) { "A username is 3-64 letters, digits, dots, dashes or underscores" }
        val id = Ulid.create(now()).toString()
        run("INSERT INTO users (id, username, passwordHash, admin, createdAt) VALUES (?, ?, ?, ?, ?)", id, username, passwordHash, if (admin) 1L else 0L, now())
        return User(id, username, admin)
    }

    /** (user, password hash) of an enabled account. */
    @Synchronized
    fun findUser(username: String): Pair<User, String>? =
        c.prepare("SELECT id, username, admin, passwordHash FROM users WHERE username = ? AND disabled = 0").use { s ->
            s.bindText(1, username)
            if (s.step()) User(s.getText(0), s.getText(1), s.getLong(2) == 1L) to s.getText(3) else null
        }

    /** A refresh token (a session) and its first access token. */
    @Synchronized
    fun signIn(user: User, accessMinutes: Long, refreshDays: Long): Pair<String, String> = tx {
        val refresh = Tokens.new(Tokens.REFRESH)
        run("INSERT INTO tokens (hash, kind, userId, expiresAt, createdAt) VALUES (?, 'refresh', ?, ?, ?)", Tokens.hash(refresh), user.id, now() + refreshDays * DAY, now())
        issueAccess(user.id, Tokens.hash(refresh), accessMinutes) to refresh
    }

    /** A fresh access token for a live refresh token; null when it is unknown, expired or revoked. */
    @Synchronized
    fun renew(refresh: String, accessMinutes: Long): String? = tx {
        val hash = Tokens.hash(refresh)
        val userId = c.prepare("SELECT t.userId FROM tokens t JOIN users u ON u.id = t.userId WHERE t.hash = ? AND t.kind = 'refresh' AND t.expiresAt > ? AND u.disabled = 0").use { s ->
            s.bindText(1, hash)
            s.bindLong(2, now())
            if (s.step()) s.getText(0) else null
        } ?: return@tx null
        run("DELETE FROM tokens WHERE expiresAt <= ?", now())
        issueAccess(userId, hash, accessMinutes)
    }

    private fun issueAccess(userId: String, parent: String, minutes: Long): String {
        val access = Tokens.new(Tokens.ACCESS)
        run("INSERT INTO tokens (hash, kind, userId, parent, expiresAt, createdAt) VALUES (?, 'access', ?, ?, ?, ?)", Tokens.hash(access), userId, parent, now() + minutes * 60_000, now())
        return access
    }

    @Synchronized
    fun userForAccess(access: String): User? =
        c.prepare("SELECT u.id, u.username, u.admin FROM tokens t JOIN users u ON u.id = t.userId WHERE t.hash = ? AND t.kind = 'access' AND t.expiresAt > ? AND u.disabled = 0").use { s ->
            s.bindText(1, Tokens.hash(access))
            s.bindLong(2, now())
            if (s.step()) User(s.getText(0), s.getText(1), s.getLong(2) == 1L) else null
        }

    /** Signs out the session [access] belongs to: its refresh token and every access token from it. */
    @Synchronized
    fun signOut(access: String) = tx {
        val parent = c.prepare("SELECT parent FROM tokens WHERE hash = ? AND kind = 'access'").use { s ->
            s.bindText(1, Tokens.hash(access))
            if (s.step()) s.getText(0) else null
        }
        run("DELETE FROM tokens WHERE hash = ?", Tokens.hash(access))
        if (parent != null) {
            run("DELETE FROM tokens WHERE parent = ?", parent)
            run("DELETE FROM tokens WHERE hash = ?", parent)
        }
    }

    @Synchronized
    fun createWorkspace(name: String, owner: User, id: String = Ulid.create(now()).toString()): Workspace = tx {
        require(name.isNotBlank() && name.length <= 100) { "A workspace name is 1-100 characters" }
        val ws = Workspace(id, name.trim(), now())
        run("INSERT INTO workspaces (id, name, createdBy, createdAt) VALUES (?, ?, ?, ?)", ws.id, ws.name, owner.id, ws.createdAt)
        run("INSERT INTO members (workspaceId, userId) VALUES (?, ?)", ws.id, owner.id)
        ws
    }

    @Synchronized
    fun workspaces(user: User): List<Workspace> =
        c.prepare("SELECT w.id, w.name, w.createdAt FROM workspaces w JOIN members m ON m.workspaceId = w.id WHERE m.userId = ? ORDER BY w.createdAt").use { s ->
            s.bindText(1, user.id)
            buildList { while (s.step()) add(Workspace(s.getText(0), s.getText(1), s.getLong(2))) }
        }

    @Synchronized
    fun isMember(workspaceId: String, user: User): Boolean =
        c.prepare("SELECT EXISTS (SELECT 1 FROM members WHERE workspaceId = ? AND userId = ?)").use { s ->
            s.bindText(1, workspaceId)
            s.bindText(2, user.id)
            s.step()
            s.getLong(0) == 1L
        }

    /**
     * Appends a server audit event (section 10). Each row hashes the previous row's hash with its
     * own fields, so an edit or a removed row breaks the chain ([verifyAudit]). Never pass secrets.
     */
    @Synchronized
    fun audit(actor: String?, action: String, target: String? = null, address: String? = null, detail: String? = null) = tx {
        val prev = c.prepare("SELECT hash FROM audit ORDER BY n DESC LIMIT 1").use { if (it.step()) it.getText(0) else GENESIS }
        val at = now()
        run(
            "INSERT INTO audit (at, actor, action, target, address, detail, prevHash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            at, actor, action, target, address, detail, prev, chain(prev, at, actor, action, target, address, detail),
        )
    }

    /** Null when the chain is intact, else the first broken row number. */
    @Synchronized
    fun verifyAudit(): Long? = c.prepare("SELECT n, at, actor, action, target, address, detail, prevHash, hash FROM audit ORDER BY n").use { s ->
        var prev = GENESIS
        while (s.step()) {
            fun t(i: Int) = if (s.isNull(i)) null else s.getText(i)
            val ok = s.getText(7) == prev && s.getText(8) == chain(prev, s.getLong(1), t(2), s.getText(3), t(4), t(5), t(6))
            if (!ok) return@use s.getLong(0)
            prev = s.getText(8)
        }
        null
    }

    /** Recent audit rows as (time, actor, action, target), newest first. */
    @Synchronized
    fun auditTail(limit: Int): List<List<String?>> = c.prepare("SELECT at, actor, action, target FROM audit ORDER BY n DESC LIMIT ?").use { s ->
        s.bindLong(1, limit.toLong())
        buildList { while (s.step()) add((0..3).map { if (s.isNull(it)) null else s.getText(it) }) }
    }

    @Synchronized
    override fun close() = c.close()

    private fun chain(prev: String, at: Long, vararg fields: String?): String {
        val text = (listOf(prev, at.toString()) + fields.map { it?.replace("\\", "\\\\")?.replace("|", "\\|") ?: "\\0" }).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun run(sql: String, vararg args: Any?) = c.prepare(sql).use { s ->
        args.forEachIndexed { i, a -> s.bind(i + 1, a) }
        s.step()
    }

    private fun SQLiteStatement.bind(i: Int, a: Any?) = when (a) {
        null -> bindNull(i)
        is String -> bindText(i, a)
        is Long -> bindLong(i, a)
        is Int -> bindLong(i, a.toLong())
        else -> throw IllegalArgumentException("Cannot bind ${a::class.simpleName}")
    }

    private fun <T> tx(block: () -> T): T {
        c.execSQL("BEGIN IMMEDIATE")
        try {
            return block().also { c.execSQL("COMMIT") }
        } catch (e: Throwable) {
            runCatching { c.execSQL("ROLLBACK") }
            throw e
        }
    }

    private companion object {
        const val SCHEMA = 1L
        const val DAY = 86_400_000L
        const val GENESIS = "0"
        val USERNAME = Regex("[A-Za-z0-9._-]{3,64}")
    }
}
