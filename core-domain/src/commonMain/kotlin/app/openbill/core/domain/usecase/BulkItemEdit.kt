package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Item
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import kotlin.time.Instant

/** A sale-price change for many items: a new price, or a percentage up or down (rounded to the paisa). */
sealed interface PriceChange {
    data class SetTo(val price: Money) : PriceChange
    data class ByPercent(val basisPoints: Long) : PriceChange

    fun apply(old: Money): Money = when (this) {
        is SetTo -> price
        is ByPercent -> Money.of(maxOf(0L, (old.paise * (10_000 + basisPoints) + 5_000).floorDiv(10_000)))
    }

    companion object {
        /** "120" or "120.50" sets the price; "+10%", "-5%" or "2.5%" changes it; null when it cannot be read. */
        fun parse(text: String): PriceChange? {
            val t = text.trim().replace(" ", "")
            if (t.isEmpty()) return null
            if (t.endsWith("%")) {
                val n = t.dropLast(1).removePrefix("+")
                // A percentage with up to two decimals is a whole number of basis points (2.5% = 250).
                val bp = runCatching { Money.parse(n).paise }.getOrNull() ?: return null
                return if (bp in -9_999L..100_000L) ByPercent(bp) else null
            }
            val m = runCatching { Money.parse(t) }.getOrNull() ?: return null
            return if (m >= Money.ZERO) SetTo(m) else null
        }
    }
}

/**
 * What a bulk edit changes; a null field stays as it is. [categoryId] uses [NO_CATEGORY] to clear
 * the category. Price rules apply to the sale price only.
 */
data class BulkItemEdit(
    val categoryId: String? = null,
    val taxRate: Rate? = null,
    val unitId: String? = null,
    val salePrice: PriceChange? = null,
) {
    val isEmpty: Boolean get() = categoryId == null && taxRate == null && unitId == null && salePrice == null

    /** [item] with the changes, or null when nothing about it changes. An alternate unit equal to the new unit is dropped. */
    fun apply(item: Item, at: Instant): Item? {
        val unit = unitId ?: item.unitId
        val dropAlt = item.alternateUnitId == unit
        val next = item.copy(
            categoryId = when (categoryId) { null -> item.categoryId; NO_CATEGORY -> null; else -> categoryId },
            taxRate = taxRate ?: item.taxRate,
            unitId = unit,
            alternateUnitId = if (dropAlt) null else item.alternateUnitId,
            alternateUnitFactor = if (dropAlt) null else item.alternateUnitFactor,
            salePrice = salePrice?.apply(item.salePrice) ?: item.salePrice,
        )
        return if (next == item) null else next.copy(updatedAt = maxOf(at, item.updatedAt))
    }

    companion object {
        const val NO_CATEGORY = ""
    }
}
