package app.openbill.core.domain.repository

import app.openbill.core.domain.model.AppUser
import kotlinx.coroutines.flow.Flow

/** Install-wide users; never deleted (deactivated instead) so their history keeps a name. */
interface UserRepository {
    /** Owner first, then by name. */
    fun observeUsers(): Flow<List<AppUser>>

    /** Insert or update; needs [app.openbill.core.domain.model.Permission.MANAGE_USERS]. */
    suspend fun saveUser(user: AppUser)
}
