package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.UnitOfMeasure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

interface CatalogRepository {
    fun observeItems(firmId: String): Flow<List<Item>>

    /** The first [limit] active items by name ([services] null = all) whose name, SKU, HSN/SAC or barcode contains [query]. */
    fun observeItemPage(firmId: String, services: Boolean?, query: String, limit: Int): Flow<List<Item>> =
        observeItems(firmId).map { all ->
            val q = query.trim()
            all.filter {
                it.deletedAt == null && (services == null || it.isService == services) &&
                    (q.isEmpty() || it.name.contains(q, true) || it.sku?.contains(q, true) == true || it.hsnSac?.contains(q) == true || it.barcode?.contains(q, true) == true)
            }.take(limit)
        }

    suspend fun findItem(id: String): Item?

    suspend fun saveItem(item: Item)

    suspend fun archiveItem(id: String, at: Instant)

    suspend fun searchItems(firmId: String, query: String): List<Item>

    /** Exact barcode match (then SKU) among the firm's active items, for scanners. */
    suspend fun findItemByBarcode(firmId: String, code: String): Item?

    fun observeCategories(firmId: String): Flow<List<ItemCategory>>

    suspend fun findCategory(id: String): ItemCategory?

    suspend fun saveCategory(category: ItemCategory)

    suspend fun archiveCategory(id: String, at: Instant)

    fun observeUnits(firmId: String): Flow<List<UnitOfMeasure>>

    suspend fun findUnit(id: String): UnitOfMeasure?

    suspend fun saveUnit(unit: UnitOfMeasure)

    fun observeGodowns(firmId: String): Flow<List<Godown>>

    suspend fun findGodown(id: String): Godown?

    suspend fun saveGodown(godown: Godown)
}
