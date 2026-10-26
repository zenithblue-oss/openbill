package app.openbill.core.domain.service

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.SerialStatus
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Quantity

/** Posting-time inventory invariants that need no stored state (checked before any movement). */
object InventoryRules {
    private const val UNIT = 10_000L

    val STOCK_TYPES = setOf(
        VoucherType.SALE,
        VoucherType.PURCHASE,
        VoucherType.CREDIT_NOTE,
        VoucherType.DEBIT_NOTE,
        VoucherType.STOCK_ADJUSTMENT,
        VoucherType.STOCK_TRANSFER,
    )

    fun validate(voucher: Voucher, items: Map<String, Item>) {
        if (voucher.type !in STOCK_TYPES) return
        val seen = mutableSetOf<Pair<String, String>>()
        for (line in voucher.lines) {
            val item = line.itemId?.let { items[it] } ?: continue
            if (item.isService || !item.trackInventory) continue
            if (voucher.type == VoucherType.STOCK_TRANSFER) {
                require(line.toGodownId != null && line.toGodownId != line.godownId) {
                    "Transfer line ${line.lineNumber} needs a different destination godown"
                }
            }
            if (item.trackBatch) {
                require(!line.batchNumber.isNullOrBlank()) { "${item.name}: batch number is required" }
            }
            if (item.trackSerial) {
                val units = line.quantity.scaledUnits
                require(units % UNIT == 0L) { "${item.name}: serial-tracked quantity must be whole" }
                require(line.serialNumbers.size.toLong() == units / UNIT) {
                    "${item.name}: enter one serial number per unit (${line.quantity.format()})"
                }
                for (serial in line.serialNumbers) {
                    require(seen.add(item.id to serial.trim())) {
                        "${item.name}: serial $serial appears twice"
                    }
                }
            }
        }
    }

    /**
     * Next status of a serial number. Inward rejects a serial already in stock (duplicate);
     * outward needs it in stock unless [allowUnknown] (negative stock allowed) and it was never
     * seen; a transfer (inward == null) only needs it in stock.
     */
    fun serialTransition(
        itemName: String,
        serial: String,
        current: SerialStatus?,
        inward: Boolean?,
        allowUnknown: Boolean,
    ): SerialStatus = when (inward) {
        true -> {
            require(current != SerialStatus.IN_STOCK) { "$itemName: serial $serial is already in stock" }
            SerialStatus.IN_STOCK
        }
        false -> {
            require(current == SerialStatus.IN_STOCK || (current == null && allowUnknown)) {
                "$itemName: serial $serial is not in stock"
            }
            SerialStatus.OUT
        }
        null -> {
            require(current == SerialStatus.IN_STOCK) { "$itemName: serial $serial is not in stock" }
            SerialStatus.IN_STOCK
        }
    }

    fun isWhole(quantity: Quantity): Boolean = quantity.scaledUnits % UNIT == 0L
}
