package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.openbill.core.database.FirmSettingsEntity

@Dao
interface FirmSettingsDao {
    @Upsert
    suspend fun upsert(settings: FirmSettingsEntity)

    @Query("SELECT * FROM firm_settings WHERE firmId = :firmId LIMIT 1")
    suspend fun findByFirm(firmId: String): FirmSettingsEntity?
}
