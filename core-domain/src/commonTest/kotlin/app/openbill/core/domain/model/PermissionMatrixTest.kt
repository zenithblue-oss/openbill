package app.openbill.core.domain.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class PermissionMatrixTest {
    @AfterTest
    fun reset() {
        Access.current = null
    }

    @Test
    fun everyRoleHasExactlyTheDocumentedPermissions() {
        val o = "O"; val m = "M"; val c = "C"; val a = "A"
        // Rows: permission -> roles holding it (docs/01 section 11).
        val expected = mapOf(
            Permission.CREATE_VOUCHERS to setOf(o, m, c),
            Permission.EDIT_PARTIES to setOf(o, m, c),
            Permission.MANAGE_MASTERS to setOf(o, m),
            Permission.CANCEL_DELETE to setOf(o, m),
            Permission.VIEW_REPORTS to setOf(o, m, a),
            Permission.EXPORT to setOf(o, m, a),
            Permission.VIEW_ACTIVITY to setOf(o, m, a),
            Permission.SETTINGS to setOf(o),
            Permission.MANAGE_USERS to setOf(o),
            Permission.BACKUP_RESTORE to setOf(o),
            Permission.OWNER_CONTROLS to setOf(o),
        )
        assertEquals(Permission.entries.toSet(), expected.keys, "every permission is in the matrix")
        val code = mapOf(Role.OWNER to o, Role.MANAGER to m, Role.CASHIER to c, Role.ACCOUNTANT to a)
        for ((permission, roles) in expected) {
            for (role in Role.entries) {
                assertEquals(code.getValue(role) in roles, permission in role.permissions, "$role / $permission")
            }
        }
    }

    @Test
    fun managersGetOnlyOperationalSettingsWhenTheOwnerAllowsIt() {
        val at = Instant.fromEpochMilliseconds(0)
        val manager = AppUser("u-m", "Asha", Role.MANAGER, pinHash = "x", createdAt = at)
        val cashier = AppUser("u-c", "Ravi", Role.CASHIER, pinHash = "x", createdAt = at)
        try {
            Access.current = manager
            assertEquals(false, Access.can(Permission.SETTINGS), "off by default")
            assertFailsWith<PermissionDeniedException>("only the owner flips the switch") { Access.setManagersChangeSettings(true) }

            Access.current = null
            Access.setManagersChangeSettings(true)
            val withSwitch = Role.entries.associateWith { r ->
                Access.current = if (r == Role.OWNER) null else AppUser("u", "U", r, pinHash = "x", createdAt = at)
                Permission.entries.filter { Access.can(it) }.toSet()
            }
            assertEquals(Role.MANAGER.permissions + Permission.SETTINGS, withSwitch[Role.MANAGER])
            for (p in listOf(Permission.MANAGE_USERS, Permission.BACKUP_RESTORE, Permission.OWNER_CONTROLS)) assertEquals(false, p in withSwitch.getValue(Role.MANAGER), "$p")
            assertEquals(Role.CASHIER.permissions, withSwitch[Role.CASHIER], "cashiers never get settings")
            assertEquals(Role.ACCOUNTANT.permissions, withSwitch[Role.ACCOUNTANT])
            assertEquals(Permission.entries.toSet(), withSwitch[Role.OWNER])

            Access.current = manager
            assertFailsWith<PermissionDeniedException>("a manager cannot turn it off either") { Access.setManagersChangeSettings(false) }
            Access.current = cashier
            assertEquals(false, Access.can(Permission.SETTINGS))
        } finally {
            Access.current = null
            Access.setManagersChangeSettings(false)
        }
    }

    @Test
    fun noUserMeansTheOwnerAndARoleIsEnforced() {
        assertEquals(Role.OWNER, Access.role)
        assertEquals(Access.OWNER_ID, Access.actorId)
        Access.require(Permission.BACKUP_RESTORE)

        Access.current = AppUser("u-1", "Ravi", Role.CASHIER, pinHash = "x", createdAt = Instant.fromEpochMilliseconds(0))
        assertEquals("u-1", Access.actorId)
        Access.require(Permission.CREATE_VOUCHERS)
        val denied = assertFailsWith<PermissionDeniedException> { Access.require(Permission.CANCEL_DELETE) }
        assertEquals("A salesperson / cashier cannot cancel, restore or delete", denied.message)
    }

    @Test
    fun userRules() {
        val at = Instant.fromEpochMilliseconds(0)
        AppUser(Access.OWNER_ID, "Owner", Role.OWNER, createdAt = at).requireValid()
        AppUser("u", "Ravi", Role.CASHIER, pinHash = "h", createdAt = at).requireValid()
        assertFailsWith<IllegalArgumentException> { AppUser(Access.OWNER_ID, "Owner", Role.MANAGER, createdAt = at).requireValid() }
        assertFailsWith<IllegalArgumentException> { AppUser(Access.OWNER_ID, "Owner", Role.OWNER, active = false, createdAt = at).requireValid() }
        assertFailsWith<IllegalArgumentException> { AppUser("u", "Ravi", Role.OWNER, pinHash = "h", createdAt = at).requireValid() }
        assertFailsWith<IllegalArgumentException> { AppUser("u", " ", Role.CASHIER, pinHash = "h", createdAt = at).requireValid() }
        assertFailsWith<IllegalArgumentException> { AppUser("u", "Ravi", Role.CASHIER, createdAt = at).requireValid() }
    }
}
