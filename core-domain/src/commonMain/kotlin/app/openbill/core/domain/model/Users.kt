package app.openbill.core.domain.model

import kotlin.concurrent.Volatile
import kotlin.time.Instant

/** Local staff roles on one install (docs/01 section 11). The owner is the app-lock holder. */
enum class Role(val label: String, val summary: String) {
    OWNER("Owner", "Everything, including settings, users and backups"),
    MANAGER("Manager", "Billing, masters, prices, cancels, reports"),
    CASHIER("Salesperson / cashier", "Billing and parties; no price changes, cancels or reports"),
    ACCOUNTANT("Accountant / CA", "Read-only: reports, exports and the activity log"),
}

enum class Permission(val verb: String) {
    CREATE_VOUCHERS("create or post documents"),
    EDIT_PARTIES("add or edit parties"),
    /** Items, prices (including a line rate other than the item price), accounts, godowns, schedules. */
    MANAGE_MASTERS("change items, prices or accounts"),
    CANCEL_DELETE("cancel, restore or delete"),
    VIEW_REPORTS("open reports"),
    EXPORT("export"),
    VIEW_ACTIVITY("view the activity log"),
    SETTINGS("change settings"),
    MANAGE_USERS("manage users"),
    BACKUP_RESTORE("back up or restore"),
    /** Owner-only even when managers may change settings: app lock, adding or erasing a business, that switch itself. */
    OWNER_CONTROLS("change the app lock, add or erase a business, or who changes settings"),
}

val Role.permissions: Set<Permission>
    get() = when (this) {
        Role.OWNER -> Permission.entries.toSet()
        Role.MANAGER -> setOf(
            Permission.CREATE_VOUCHERS, Permission.EDIT_PARTIES, Permission.MANAGE_MASTERS, Permission.CANCEL_DELETE,
            Permission.VIEW_REPORTS, Permission.EXPORT, Permission.VIEW_ACTIVITY,
        )
        Role.CASHIER -> setOf(Permission.CREATE_VOUCHERS, Permission.EDIT_PARTIES)
        Role.ACCOUNTANT -> setOf(Permission.VIEW_REPORTS, Permission.EXPORT, Permission.VIEW_ACTIVITY)
    }

/** A person who can sign in. [pinHash] is a salted PBKDF2 hash; the owner's PIN is the app-lock PIN instead. */
data class AppUser(
    val id: String,
    val name: String,
    val role: Role,
    val active: Boolean = true,
    val pinHash: String? = null,
    val createdAt: Instant,
) {
    val isOwner get() = id == Access.OWNER_ID

    /** One owner (the fixed row), always active; everyone else has a PIN of their own. */
    fun requireValid() {
        require(name.isNotBlank() && name.length <= 60) { "Enter a name of up to 60 characters" }
        if (isOwner) {
            require(role == Role.OWNER && active) { "The owner stays the owner and cannot be deactivated" }
        } else {
            require(role != Role.OWNER) { "There is one owner; choose another role" }
            require(!pinHash.isNullOrEmpty()) { "Set a PIN for this user" }
        }
    }
}

class PermissionDeniedException(val permission: Permission, role: Role) :
    IllegalStateException("A ${role.label.lowercase()} cannot ${permission.verb}")

/**
 * Who is using the app right now, checked by repositories and use cases before writes.
 * ponytail: one process-wide user (one person at the screen at a time); per-request context
 * when a server serves several users at once.
 */
object Access {
    const val OWNER_ID = "owner"

    /** Null = the owner: single-user installs, the CLI and anything before sign-in. */
    @Volatile
    var current: AppUser? = null

    val role: Role get() = current?.role ?: Role.OWNER
    val actorId: String get() = current?.id ?: OWNER_ID

    /**
     * Owner's per-business switch "Managers can change this business's settings" (off by default):
     * managers then also hold [Permission.SETTINGS], never [Permission.OWNER_CONTROLS],
     * [Permission.MANAGE_USERS] or [Permission.BACKUP_RESTORE]. Stored in the business's settings
     * (so it travels with backups) and loaded when that business opens.
     */
    @Volatile
    var managersChangeSettings: Boolean = false
        private set

    /** Load of the active business's stored switch (no permission check: it only mirrors the database). */
    fun loadManagersChangeSettings(on: Boolean) {
        managersChangeSettings = on
    }

    /** The owner turns the switch on or off. */
    fun setManagersChangeSettings(on: Boolean) {
        require(Permission.OWNER_CONTROLS)
        managersChangeSettings = on
    }

    fun can(permission: Permission): Boolean = permission in role.permissions ||
        (permission == Permission.SETTINGS && role == Role.MANAGER && managersChangeSettings)

    fun require(permission: Permission) {
        if (!can(permission)) throw PermissionDeniedException(permission, role)
    }
}
