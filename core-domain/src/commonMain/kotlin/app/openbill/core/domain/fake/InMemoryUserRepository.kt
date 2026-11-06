package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.Role
import app.openbill.core.domain.repository.UserRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryUserRepository : UserRepository {
    private val users = MutableStateFlow(listOf(AppUser(Access.OWNER_ID, "Owner", Role.OWNER, createdAt = Instant.fromEpochMilliseconds(0))))

    override fun observeUsers(): Flow<List<AppUser>> = users.map { all -> all.sortedWith(compareBy({ !it.isOwner }, { it.name.lowercase() })) }

    override suspend fun saveUser(user: AppUser) {
        Access.require(Permission.MANAGE_USERS)
        user.requireValid()
        users.update { all -> all.filter { it.id != user.id } + user }
    }
}
