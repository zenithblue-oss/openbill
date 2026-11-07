package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.UserEntity
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.Role
import app.openbill.core.domain.repository.UserRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomUserRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : UserRepository {
    /** The owner row is seeded on open; listed even before that so callers can rely on it. */
    override fun observeUsers(): Flow<List<AppUser>> = database.userDao().observeAll().map { rows ->
        val users = rows.map { it.toDomain() }
        if (users.any { it.isOwner }) users else listOf(AppUser(Access.OWNER_ID, "Owner", Role.OWNER, createdAt = Instant.fromEpochMilliseconds(0))) + users
    }

    override suspend fun saveUser(user: AppUser) {
        Access.require(Permission.MANAGE_USERS)
        user.requireValid()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        transactions.write("user.save") {
            database.userDao().upsert(
                UserEntity(user.id, user.name.trim(), user.role.name.lowercase(), user.active, user.pinHash, user.createdAt.toEpochMilliseconds(), now),
            )
        }
    }
}

/**
 * Only the fixed owner row is ever the owner, and an unknown role reads as the least one, so a
 * hand-edited database or backup cannot mint a second owner.
 */
internal fun UserEntity.toDomain(): AppUser {
    val stored = Role.entries.firstOrNull { it.name.equals(role, ignoreCase = true) }
    val safe = when {
        id == Access.OWNER_ID -> Role.OWNER
        stored == null || stored == Role.OWNER -> Role.ACCOUNTANT
        else -> stored
    }
    return AppUser(id, name, safe, active = active || id == Access.OWNER_ID, pinHash = pinHash, createdAt = Instant.fromEpochMilliseconds(createdAt))
}
