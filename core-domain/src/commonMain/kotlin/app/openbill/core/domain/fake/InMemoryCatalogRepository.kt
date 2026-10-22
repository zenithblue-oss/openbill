package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

class InMemoryCatalogRepository : CatalogRepository {
    private val items = MutableStateFlow<List<Item>>(emptyList())
    private val categories = MutableStateFlow<List<ItemCategory>>(emptyList())
    private val units = MutableStateFlow<List<UnitOfMeasure>>(emptyList())
    private val godowns = MutableStateFlow<List<Godown>>(emptyList())

    override fun observeItems(firmId: String): Flow<List<Item>> =
        items.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override suspend fun findItem(id: String): Item? = items.value.firstOrNull { it.id == id }

    override suspend fun saveItem(item: Item) {
        items.update { current -> current.filterNot { it.id == item.id } + item }
    }

    override suspend fun archiveItem(id: String, at: Instant) {
        items.update { current ->
            current.map { item ->
                if (item.id == id && item.deletedAt == null) {
                    item.copy(deletedAt = at, updatedAt = at)
                } else {
                    item
                }
            }
        }
    }

    override suspend fun searchItems(firmId: String, query: String): List<Item> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return items.value
            .filter { item ->
                item.firmId == firmId &&
                    item.deletedAt == null &&
                    listOfNotNull(item.name, item.sku, item.hsnSac)
                        .any { it.lowercase().contains(needle) }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override fun observeCategories(firmId: String): Flow<List<ItemCategory>> =
        categories.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override suspend fun findCategory(id: String): ItemCategory? =
        categories.value.firstOrNull { it.id == id }

    override suspend fun saveCategory(category: ItemCategory) {
        categories.update { current -> current.filterNot { it.id == category.id } + category }
    }

    override suspend fun archiveCategory(id: String, at: Instant) {
        categories.update { current ->
            current.map { category ->
                if (category.id == id && category.deletedAt == null) {
                    category.copy(deletedAt = at, updatedAt = at)
                } else {
                    category
                }
            }
        }
    }

    override fun observeUnits(firmId: String): Flow<List<UnitOfMeasure>> =
        units.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override suspend fun findUnit(id: String): UnitOfMeasure? = units.value.firstOrNull { it.id == id }

    override suspend fun saveUnit(unit: UnitOfMeasure) {
        units.update { current -> current.filterNot { it.id == unit.id } + unit }
    }

    override fun observeGodowns(firmId: String): Flow<List<Godown>> =
        godowns.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareByDescending<Godown> { it.isDefault }.thenBy { it.name.lowercase() })
        }

    override suspend fun findGodown(id: String): Godown? =
        godowns.value.firstOrNull { it.id == id }

    override suspend fun saveGodown(godown: Godown) {
        godowns.update { current -> current.filterNot { it.id == godown.id } + godown }
    }
}
