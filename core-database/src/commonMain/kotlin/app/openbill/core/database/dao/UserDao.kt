package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.openbill.core.database.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY id != 'owner', name COLLATE NOCASE")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findById(id: String): UserEntity?

    @Upsert
    suspend fun upsert(user: UserEntity)
}
