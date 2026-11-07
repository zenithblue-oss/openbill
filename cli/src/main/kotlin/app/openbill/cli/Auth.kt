package app.openbill.cli

import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.Role
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.first

/**
 * Sign-in for databases with staff users (docs/11-cli.md section 5.1), mirroring the app's lock
 * screen: staff PINs are the salted PBKDF2 hashes in the database; the owner's PIN is the desktop
 * app-lock PIN, kept in the desktop app's preferences on this computer, never in the database.
 * Wrong PINs share the app's persisted back-off. PINs never come from argv.
 */
object Pins {
    const val KEY_PIN = "lock.pin"
    const val KEY_FAILURES = "lock.failures"
    const val KEY_RETRY_AT = "lock.retryAt"
    const val MIN = 4
    const val MAX = 8
    private const val ITERATIONS = 120_000
    private const val FREE_TRIES = 3

    /** Same rules as the app's AppLockController.pinError. */
    fun error(pin: String): String? = when {
        !pin.all(Char::isDigit) -> "Use digits only"
        pin.length < MIN -> "Use at least $MIN digits"
        pin.length > MAX -> "Use at most $MAX digits"
        else -> null
    }

    /** "iterations:salt:hash", the app's AppLockController.hashPin format. */
    fun hash(pin: String): String {
        error(pin)?.let { throw IllegalArgumentException(it) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$ITERATIONS:${salt.hex()}:${pbkdf2(pin, salt, ITERATIONS).hex()}"
    }

    /** Constant-time check against [hash] output; false for a missing or malformed hash. */
    fun verify(pin: String, stored: String?): Boolean {
        val (iterations, salt, expected) = stored?.split(':')?.takeIf { it.size == 3 } ?: return false
        val rounds = iterations.toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val saltBytes = runCatching { salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() ?: return false
        val actual = pbkdf2(pin, saltBytes, rounds).hex()
        return java.security.MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
    }

    /** No wait for the first 3 failures, then 30 s doubling, capped at an hour (the app's backoffMs). */
    fun backoffMs(failures: Int): Long =
        if (failures < FREE_TRIES) 0 else minOf(30_000L shl minOf(failures - FREE_TRIES, 7), 3_600_000L)

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** The desktop app's preferences node (Platform.desktop.kt): app-lock PIN hash and back-off. */
interface LockStore {
    fun get(key: String): String?
    fun put(key: String, value: String)

    companion object {
        fun desktop(): LockStore = object : LockStore {
            private val prefs by lazy {
                val node = System.getProperty("openbill.prefsNode")?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "app/openbill/ui"
                java.util.prefs.Preferences.userRoot().node(node)
            }
            override fun get(key: String): String? = runCatching { prefs.get(key, null) }.getOrNull()
            override fun put(key: String, value: String) {
                runCatching { prefs.put(key, value); prefs.flush() }
            }
        }
    }
}

/** Reads the PIN for [who]: `--pin-stdin`, else OPENBILL_PIN, else a hidden prompt on a terminal. */
private fun CliState.readPin(who: String): String {
    val pin = when {
        pinStdin -> env.readLine()
        env.pin != null -> env.pin
        env.interactive -> env.readSecret("PIN for $who: ")?.let(::String)
        else -> null
    }
    return pin?.trim()?.takeIf { it.isNotEmpty() }
        ?: fail(Exit.DENIED, "PIN needed for $who: use --pin-stdin, set OPENBILL_PIN, or run on a terminal")
}

/** Checks [user]'s PIN with the app's shared back-off; exit 8 on a wrong PIN or while backing off. */
private fun CliState.checkPin(user: AppUser) {
    val store = env.lockStore
    val nowMs = env.clock.now().toEpochMilliseconds()
    val wait = (store.get(Pins.KEY_RETRY_AT)?.toLongOrNull() ?: 0L) - nowMs
    if (wait > 0) fail(Exit.DENIED, "Too many wrong PINs; wait ${(wait + 999) / 1000} s")
    val hash = if (user.isOwner) {
        store.get(Pins.KEY_PIN)?.takeIf { it.isNotEmpty() }
            ?: fail(Exit.DENIED, "The owner's PIN is the desktop app-lock PIN, which is not set on this computer; turn on App lock in OpenBill first")
    } else {
        user.pinHash
    }
    if (Pins.verify(readPin(user.name), hash)) {
        store.put(Pins.KEY_FAILURES, "0")
        store.put(Pins.KEY_RETRY_AT, "0")
        return
    }
    val failures = (store.get(Pins.KEY_FAILURES)?.toIntOrNull() ?: 0) + 1
    store.put(Pins.KEY_FAILURES, failures.toString())
    store.put(Pins.KEY_RETRY_AT, (nowMs + Pins.backoffMs(failures)).toString())
    fail(Exit.DENIED, "Wrong PIN for ${user.name}")
}

/**
 * Signs in before any data is read. Without active staff users and without `--user`, the CLI is
 * the owner, as before. With staff, `--user NAME` (or OPENBILL_USER) and that user's PIN are
 * required, and the user's role applies to every command, as in the app.
 */
fun CliState.signIn(users: List<AppUser>) {
    if (signedInWithPin) return
    val ref = user ?: env.user
    val staff = users.any { it.active && !it.isOwner }
    if (ref == null) {
        if (staff) fail(Exit.DENIED, "This data has several users; sign in with --user NAME (PIN from --pin-stdin, OPENBILL_PIN or a prompt)")
        Access.current = null
        return
    }
    val u = users.firstOrNull { it.active && (it.id == ref || it.name.equals(ref.trim(), ignoreCase = true)) }
        ?: fail(Exit.DENIED, "No active user '$ref'")
    checkPin(u)
    Access.current = u
    signedInWithPin = true
}

/** Owner-only actions (users): the signed-in owner, or the owner's PIN asked now even on a single-user install. */
fun CliState.requireOwnerPin(users: List<AppUser>) {
    if (Access.role != Role.OWNER) throw app.openbill.core.domain.model.PermissionDeniedException(Permission.MANAGE_USERS, Access.role)
    if (signedInWithPin) return
    checkPin(users.firstOrNull { it.isOwner } ?: fail(Exit.INTERNAL, "No owner row"))
    signedInWithPin = true
}

/**
 * For commands that open the database without Room (restore, migrate): with active staff users,
 * only the owner may run them, signed in with the owner PIN.
 */
fun CliState.signInOwnerRaw(db: File) {
    val staff = if (!db.isFile) 0L else runCatching {
        readOnlySql(db) { rows -> rows("SELECT COUNT(*) FROM users WHERE id <> '${Access.OWNER_ID}' AND active = 1").single().toLong() }
    }.getOrDefault(0L)
    val ref = user ?: env.user
    if (staff == 0L && ref == null) return
    val owner = AppUser(Access.OWNER_ID, "Owner", Role.OWNER, createdAt = kotlin.time.Instant.fromEpochMilliseconds(0))
    val name = runCatching { readOnlySql(db) { rows -> rows("SELECT name FROM users WHERE id = '${Access.OWNER_ID}'").singleOrNull() } }.getOrNull() ?: owner.name
    if (ref == null || !(ref == Access.OWNER_ID || ref.trim().equals(name, ignoreCase = true))) {
        fail(Exit.DENIED, "Only the owner can do this here; sign in with --user \"$name\"")
    }
    checkPin(owner.copy(name = name))
    signedInWithPin = true
}

// ---- users ----

fun userCommands(cli: CliState) = Group("users", "Staff users and roles (owner only; needs the owner PIN)").subcommands(
    UsersList(cli), UsersAdd(cli), UsersSetActive(cli, active = false), UsersSetActive(cli, active = true), UsersRole(cli),
)

private fun userJson(u: AppUser) = jsonOf("id" to u.id, "name" to u.name, "role" to u.role.name.lowercase(), "active" to u.active)

private val STAFF_ROLES = Role.entries.filter { it != Role.OWNER }.map { it.name.lowercase() }.toTypedArray()

private suspend fun Data.findUser(ref: String): AppUser {
    val all = repos.users.observeUsers().first()
    return all.firstOrNull { it.id == ref } ?: all.filter { it.name.equals(ref.trim(), true) }.singleOrNull()
        ?: fail(Exit.NOT_FOUND, "No user '$ref'")
}

/** Saves [user] and logs the same activity entry as the app's Users screen. */
private suspend fun Data.saveUserAudited(user: AppUser, action: String) {
    repos.users.saveUser(user)
    val details = "{\"name\":\"${user.name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"role\":\"${user.role.name.lowercase()}\"}"
    val firmId = runCatching { firm().id }.getOrNull() ?: repos.firms.observeFirms().first().firstOrNull()?.id ?: return
    repos.audit.record(AuditEvent(cli.env.newId(), firmId, "user", user.id, action = action, actor = Access.actorId, detailsJson = details, createdAt = now()))
}

class UsersList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List users with role and status"
    override fun run() = cli.withData(write = false) {
        Access.require(Permission.MANAGE_USERS)
        val users = repos.users.observeUsers().first()
        when {
            cli.json -> cli.printJson(toJson(users.map(::userJson)))
            cli.quiet -> users.forEach { cli.out.println(it.id) }
            else -> cli.printTable(listOf(Col("ID"), Col("NAME"), Col("ROLE"), Col("ACTIVE")), users.map { listOf(it.id, it.name, it.role.name.lowercase(), if (it.active) "yes" else "no") })
        }
    }
}

class UsersAdd(private val cli: CliState) : CliktCommand("add") {
    override fun help(context: Context) = "Add a staff user; their new PIN comes from --new-pin-stdin, OPENBILL_NEW_PIN, or a prompt (twice)"
    private val name by option("--name").required()
    private val role by option("--role").choice(*STAFF_ROLES).required()
    private val newPinStdin by option("--new-pin-stdin", help = "Read the new user's PIN from the next stdin line").flag()

    override fun run() = cli.withData(write = true) {
        val users = repos.users.observeUsers().first()
        cli.requireOwnerPin(users)
        if (cli.env.lockStore.get(Pins.KEY_PIN).isNullOrEmpty()) fail(Exit.CONFLICT, "Turn on App lock in OpenBill first: its PIN is the owner's, and staff sign in through it")
        val clean = name.filterNot(Char::isISOControl).trim()
        if (users.any { it.name.trim().equals(clean, ignoreCase = true) }) fail(Exit.CONFLICT, "Someone already has the name '$clean'")
        val pin = when {
            newPinStdin -> cli.env.readLine()?.trim()
            cli.env.newPin != null -> cli.env.newPin
            cli.env.interactive -> {
                val a = cli.env.readSecret("New PIN for $clean: ")?.let(::String)
                val b = cli.env.readSecret("Repeat PIN: ")?.let(::String)
                if (a != b) fail(Exit.VALIDATION, "PINs do not match")
                a
            }
            else -> null
        } ?: fail(Exit.USAGE, "New PIN needed: use --new-pin-stdin, set OPENBILL_NEW_PIN, or run on a terminal")
        Pins.error(pin)?.let { fail(Exit.VALIDATION, it) }
        val u = AppUser(cli.env.newId(), clean, Role.valueOf(role.uppercase()), pinHash = Pins.hash(pin), createdAt = now())
        saveUserAudited(u, "user.added")
        if (cli.json) cli.printJson(userJson(u)) else cli.out.println(u.id)
    }
}

class UsersSetActive(private val cli: CliState, private val active: Boolean) : CliktCommand(if (active) "activate" else "deactivate") {
    override fun help(context: Context) = if (active) "Let a deactivated user sign in again" else "Stop a user from signing in; their history keeps the name"
    private val ref by argument("USER")

    override fun run() = cli.withData(write = true) {
        cli.requireOwnerPin(repos.users.observeUsers().first())
        val u = findUser(ref)
        if (u.isOwner) fail(Exit.CONFLICT, "The owner stays the owner and cannot be deactivated")
        if (u.active == active) fail(Exit.CONFLICT, "${u.name} is already ${if (active) "active" else "inactive"}")
        if (!active) cli.confirm("Deactivate ${u.name}?")
        saveUserAudited(u.copy(active = active), if (active) "user.reactivated" else "user.deactivated")
        if (cli.json) cli.printJson(userJson(u.copy(active = active))) else cli.out.println("${u.name} ${if (active) "activated" else "deactivated"}")
    }
}

class UsersRole(private val cli: CliState) : CliktCommand("role") {
    override fun help(context: Context) = "Change a staff user's role"
    private val ref by argument("USER")
    private val role by argument("ROLE").choice(*STAFF_ROLES)

    override fun run() = cli.withData(write = true) {
        cli.requireOwnerPin(repos.users.observeUsers().first())
        val u = findUser(ref)
        if (u.isOwner) fail(Exit.CONFLICT, "There is one owner; the owner's role does not change")
        val changed = u.copy(role = Role.valueOf(role.uppercase()))
        saveUserAudited(changed, "user.updated")
        if (cli.json) cli.printJson(userJson(changed)) else cli.out.println("${u.name} is now ${changed.role.label}")
    }
}
