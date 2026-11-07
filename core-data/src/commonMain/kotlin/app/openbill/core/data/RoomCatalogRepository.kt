package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.repository.CatalogRepository
import app.openbill.core.domain.repository.ItemPriceChange
import app.openbill.core.model.Money
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

    override fun observeItemPage(firmId: String, services: Boolean?, query: String, limit: Int, categoryId: String?): Flow<List<Item>> =
        items.observePage(firmId, services, query.trim(), limit, categoryId).map { values -> values.map { it.toDomain() } }

    override suspend fun findItem(id: String): Item? = items.findById(id)?.toDomain()

    override fun observePriceChanges(firmId: String, itemId: String): Flow<List<ItemPriceChange>> =
        database.auditDao().observeForEntity(firmId, "item", itemId).map { rows ->
            rows.filter { it.action == "item.updated" || it.action == "item.bulk_updated" }.mapNotNull { e ->
                val json = e.detailsJson ?: return@mapNotNull null
                fun paise(key: String) = Regex("\"$key\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toLong()?.let(Money::of)
                val sale = paise("salePrice") ?: return@mapNotNull null
                val oldSale = paise("oldSalePrice") ?: return@mapNotNull null
                val purchase = paise("purchasePrice")
                val oldPurchase = paise("oldPurchasePrice")
                if (sale == oldSale && purchase == oldPurchase) return@mapNotNull null
                ItemPriceChange(Instant.fromEpochMilliseconds(e.createdAt), e.actor, oldSale, sale, oldPurchase, purchase)
            }
        }

    override suspend fun saveItem(item: Item) {
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write("item.save") { write(item, bulk = false) }
    }

    override suspend fun saveItems(items: List<Item>) {
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write("item.saveAll") { items.forEach { write(it, bulk = true) } }
    }

    private suspend fun write(item: Item, bulk: Boolean) {
        run {
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
            val priceChanged = existing != null && (existing.salePrice != entity.salePrice || existing.purchasePrice != entity.purchasePrice)
            database.audit(
                item.firmId, "item", item.id, if (existing == null) "item.created" else if (bulk) "item.bulk_updated" else "item.updated",
                when {
                    bulk && existing != null -> AuditDetails.json(
                        "salePrice" to entity.salePrice, "oldSalePrice" to existing.salePrice, "taxRateUnits" to entity.taxRateUnits,
                        "oldTaxRateUnits" to existing.taxRateUnits, "categoryId" to entity.categoryId, "oldCategoryId" to existing.categoryId,
                        "unitId" to entity.unitId, "oldUnitId" to existing.unitId,
                    )
                    priceChanged -> AuditDetails.json(
                        "salePrice" to entity.salePrice, "oldSalePrice" to existing?.salePrice,
                        "purchasePrice" to entity.purchasePrice, "oldPurchasePrice" to existing?.purchasePrice,
                    )
                    else -> null
                },
            )
        }
    }

    override suspend fun archiveItem(id: String, at: Instant) {
        Access.require(Permission.CANCEL_DELETE)
        transactions.write("item.archive") {
            val firmId = items.findById(id)?.firmId
            items.softDelete(
                id = id,
                deletedAt = at.toEpochMillis(),
                updatedAt = at.toEpochMillis(),
            )
            firmId?.let { database.audit(it, "item", id, "item.deleted", at = at) }
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
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write("category.save") {
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
        Access.require(Permission.CANCEL_DELETE)
        transactions.write("category.archive") {
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
        transactions.write("unit.save") {
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
        Access.require(Permission.MANAGE_MASTERS)
        transactions.write("godown.save") {
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
