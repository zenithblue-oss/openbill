package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.time.Instant

data class ItemCategory(
    val id: String,
    val firmId: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "ItemCategory id cannot be blank" }
        require(firmId.isNotBlank()) { "ItemCategory firmId cannot be blank" }
        require(name.isNotBlank()) { "ItemCategory name cannot be blank" }
        require(parentId != id) { "ItemCategory cannot be its own parent" }
    }
}

data class UnitOfMeasure(
    val id: String,
    val firmId: String,
    val name: String,
    val symbol: String,
    val decimalPlaces: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "UnitOfMeasure id cannot be blank" }
        require(firmId.isNotBlank()) { "UnitOfMeasure firmId cannot be blank" }
        require(name.isNotBlank()) { "UnitOfMeasure name cannot be blank" }
        require(symbol.isNotBlank()) { "UnitOfMeasure symbol cannot be blank" }
        require(decimalPlaces in 0..4) { "UnitOfMeasure decimalPlaces must be between 0 and 4" }
    }
}

data class Godown(
    val id: String,
    val firmId: String,
    val name: String,
    val address: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Godown id cannot be blank" }
        require(firmId.isNotBlank()) { "Godown firmId cannot be blank" }
        require(name.isNotBlank()) { "Godown name cannot be blank" }
    }
}

data class Item(
    val id: String,
    val firmId: String,
    val name: String,
    val sku: String? = null,
    /** Code128 payload printed on labels and matched by scanners; unique per firm when set. */
    val barcode: String? = null,
    val categoryId: String? = null,
    val unitId: String,
    val hsnSac: String? = null,
    val isService: Boolean = false,
    val taxRate: Rate = Rate.ofBasisPoints(0),
    val cessRate: Rate = Rate.ofBasisPoints(0),
    val salePrice: Money = Money.ZERO,
    val purchasePrice: Money = Money.ZERO,
    val mrp: Money? = null,
    val wholesalePrice: Money? = null,
    val trackInventory: Boolean = true,
    val trackBatch: Boolean = false,
    val trackSerial: Boolean = false,
    val lowStockLevel: Quantity? = null,
    val notes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Item id cannot be blank" }
        require(firmId.isNotBlank()) { "Item firmId cannot be blank" }
        require(name.isNotBlank()) { "Item name cannot be blank" }
        require(unitId.isNotBlank()) { "Item unitId cannot be blank" }
        require(salePrice >= Money.ZERO) { "Item sale price cannot be negative" }
        require(purchasePrice >= Money.ZERO) { "Item purchase price cannot be negative" }
        require(!trackBatch || trackInventory) { "Batch tracking requires inventory tracking" }
        require(!trackSerial || trackInventory) { "Serial tracking requires inventory tracking" }
        require(updatedAt >= createdAt) { "Item updatedAt cannot precede createdAt" }
    }
}
