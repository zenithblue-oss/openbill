package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.GodownEntity
import app.openbill.core.database.ItemCategoryEntity
import app.openbill.core.database.UnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Insert
    suspend fun insertCategory(category: ItemCategoryEntity)

    @Update
    suspend fun updateCategory(category: ItemCategoryEntity)

    @Query("SELECT * FROM item_categories WHERE id = :id LIMIT 1")
    suspend fun findCategoryById(id: String): ItemCategoryEntity?

    @Query(
        "SELECT * FROM item_categories WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeCategories(firmId: String): Flow<List<ItemCategoryEntity>>

    @Query(
        "SELECT * FROM item_categories WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE, id",
    )
    suspend fun searchCategories(firmId: String, query: String): List<ItemCategoryEntity>

    @Query(
        "UPDATE item_categories SET deletedAt = :deletedAt, updatedAt = :updatedAt, " +
            "revision = revision + 1 WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDeleteCategory(id: String, deletedAt: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertUnit(unit: UnitEntity)

    @Update
    suspend fun updateUnit(unit: UnitEntity)

    @Query("SELECT * FROM units WHERE id = :id LIMIT 1")
    suspend fun findUnitById(id: String): UnitEntity?

    @Query(
        "SELECT * FROM units WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeUnits(firmId: String): Flow<List<UnitEntity>>

    @Query(
        "SELECT * FROM units WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND (name LIKE '%' || :query || '%' OR symbol LIKE '%' || :query || '%') " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    suspend fun searchUnits(firmId: String, query: String): List<UnitEntity>

    @Insert
    suspend fun insertGodown(godown: GodownEntity)

    @Update
    suspend fun updateGodown(godown: GodownEntity)

    @Query("SELECT * FROM godowns WHERE id = :id LIMIT 1")
    suspend fun findGodownById(id: String): GodownEntity?

    @Query(
        "SELECT * FROM godowns WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY isDefault DESC, name COLLATE NOCASE, id",
    )
    fun observeGodowns(firmId: String): Flow<List<GodownEntity>>

    @Query(
        "SELECT * FROM godowns WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND name LIKE '%' || :query || '%' ORDER BY isDefault DESC, name COLLATE NOCASE, id",
    )
    suspend fun searchGodowns(firmId: String, query: String): List<GodownEntity>
}
