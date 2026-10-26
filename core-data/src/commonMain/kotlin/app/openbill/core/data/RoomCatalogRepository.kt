package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.repository.CatalogRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCatalogRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : CatalogRepository {
    private val catalog get() = database.catalogDao()
    private val items get() = database.itemDao()

    override fun observeItems(firmId: String): Flow<List<Item>> =
        items.observeActive(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun findItem(id: String): Item? = items.findById(id)?.toDomain()

    override suspend fun saveItem(item: Item) {
        transactions.write {
            val existing = items.findById(item.id)
            item.barcode?.let { code ->
                val clash = items.findByBarcode(item.firmId, code)
                require(clash == null || clash.id == item.id || clash.barcode != code) {
                    "Barcode $code is already used by ${clash?.name}"
                }
            }
            val entity = item.toEntity(existing)
            if (existing == null) {
                items.insert(entity)
            } else {
                items.update(entity)
            }
        }
    }

    override suspend fun archiveItem(id: String, at: Instant) {
        transactions.write {
            items.softDelete(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
        }
    }

    override suspend fun searchItems(firmId: String, query: String): List<Item> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return items.search(firmId, needle).map { it.toDomain() }
    }

    override suspend fun findItemByBarcode(firmId: String, code: String): Item? =
        code.trim().takeIf { it.isNotEmpty() }?.let { items.findByBarcode(firmId, it)?.toDomain() }

    override fun observeCategories(firmId: String): Flow<List<ItemCategory>> =
        catalog.observeCategories(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun findCategory(id: String): ItemCategory? =
        catalog.findCategoryById(id)?.toDomain()

    override suspend fun saveCategory(category: ItemCategory) {
        transactions.write {
            val existing = catalog.findCategoryById(category.id)
            val entity = category.toEntity(existing)
            if (existing == null) {
                catalog.insertCategory(entity)
            } else {
                catalog.updateCategory(entity)
            }
        }
    }

    override suspend fun archiveCategory(id: String, at: Instant) {
        transactions.write {
            catalog.softDeleteCategory(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
        }
    }

    override fun observeUnits(firmId: String): Flow<List<UnitOfMeasure>> =
        catalog.observeUnits(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun findUnit(id: String): UnitOfMeasure? =
        catalog.findUnitById(id)?.toDomain()

    override suspend fun saveUnit(unit: UnitOfMeasure) {
        transactions.write {
            val existing = catalog.findUnitById(unit.id)
            val entity = unit.toEntity(existing)
            if (existing == null) {
                catalog.insertUnit(entity)
            } else {
                catalog.updateUnit(entity)
            }
        }
    }

    override fun observeGodowns(firmId: String): Flow<List<Godown>> =
        catalog.observeGodowns(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun findGodown(id: String): Godown? =
        catalog.findGodownById(id)?.toDomain()

    override suspend fun saveGodown(godown: Godown) {
        transactions.write {
            val existing = catalog.findGodownById(godown.id)
            val entity = godown.toEntity(existing)
            if (existing == null) {
                catalog.insertGodown(entity)
            } else {
                catalog.updateGodown(entity)
            }
        }
    }
}
