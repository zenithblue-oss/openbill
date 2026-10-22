package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.VoucherEntity
import app.openbill.core.database.VoucherNumberSequenceEntity
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherNumbering
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.domain.service.StockMovementDerivation
import app.openbill.core.model.Quantity
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

class RoomVoucherRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : VoucherRepository {
    private val vouchers get() = database.voucherDao()
    private val sequences get() = database.voucherNumberSequenceDao()
    private val audits get() = database.auditDao()
    private val settings get() = database.firmSettingsDao()
    private val items get() = database.itemDao()
    private val stockMovements get() = database.stockMovementDao()
    private val derivation = StockMovementDerivation()

    override fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>> = vouchers.observeByFirmAndDateRange(
        firmId = firmId,
        fromDate = fromDate.toString(),
        toDate = toDate.toString(),
    ).map { values -> values.map { it.toDomain(vouchers.linesFor(it.id), vouchers.chargesFor(it.id)) } }

    override fun observeVoucher(id: String): Flow<Voucher?> =
        vouchers.observeById(id).map { value ->
            value?.toDomain(vouchers.linesFor(value.id), vouchers.chargesFor(value.id))
        }

    override suspend fun findVoucher(id: String): Voucher? {
        val voucher = vouchers.findById(id) ?: return null
        return voucher.toDomain(vouchers.linesFor(id), vouchers.chargesFor(id))
    }

    override suspend fun saveDraft(voucher: Voucher) {
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be saved here" }
        require(voucher.number == null) { "Draft voucher must not have a number" }
        transactions.write {
            val existing = vouchers.findById(voucher.id)
            if (existing != null) {
                require(
                    decodeEnum<VoucherStatus>(existing.status, "voucher status") ==
                        VoucherStatus.DRAFT,
                ) {
                    "Only drafts can be saved here"
                }
            }
            val existingLines = if (existing == null) {
                emptyMap()
            } else {
                vouchers.linesFor(voucher.id).associateBy { it.id }
            }
            val entity = voucher.toEntity(existing)
            if (existing == null) {
                vouchers.insert(entity)
            } else {
                vouchers.update(entity)
            }

            vouchers.deleteLinesFor(voucher.id)
            vouchers.deleteChargesFor(voucher.id)
            if (voucher.lines.isNotEmpty()) {
                vouchers.insertLines(
                    voucher.lines.map { it.toEntity(voucher.id, voucher, existingLines[it.id]) },
                )
            }
            if (voucher.charges.isNotEmpty()) {
                vouchers.insertCharges(voucher.charges.map { it.toEntity(voucher.id) })
            }
        }
    }

    override suspend fun deleteDraft(id: String) {
        transactions.write {
            val entity = vouchers.findById(id)
                ?: throw IllegalArgumentException("Unknown voucher: $id")
            require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.DRAFT) {
                "Only drafts can be deleted here"
            }
            vouchers.deleteLinesFor(id)
            vouchers.deleteChargesFor(id)
            vouchers.deleteById(id)
        }
    }

    override suspend fun post(voucherId: String, at: Instant): Voucher = transactions.write {
        val entity = vouchers.findById(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.DRAFT) {
            "Only drafts can be posted"
        }
        require(entity.number == null) { "Draft voucher must not have a number" }

        val lines = vouchers.linesFor(voucherId)
        val charges = vouchers.chargesFor(voucherId)
        val voucher = entity.toDomain(lines, charges)
        val firmSettings = settingsFor(entity.firmId)
        val movements = derivePosted(voucher, firmSettings, at)
        enforceStock(movements, firmSettings)

        val type = decodeEnum<VoucherType>(entity.type, "voucher type")
        val number = allocateNumber(entity, type, at)
        val atMillis = at.toEpochMillis()
        val updated = entity.copy(
            status = VoucherStatus.POSTED.code(),
            number = number,
            postedAt = atMillis,
            sync = entity.sync.copy(
                updatedAt = atMillis,
                revision = entity.sync.revision + 1L,
            ),
        )
        vouchers.update(updated)
        if (movements.isNotEmpty()) {
            stockMovements.insertAll(movements.map { it.toEntity() })
        }
        audits.insert(auditEvent(updated, POSTED_ACTION, at))
        updated.toDomain(lines, charges)
    }

    override suspend fun cancel(
        voucherId: String,
        reason: String,
        at: Instant,
    ): Voucher = transactions.write {
        val entity = vouchers.findById(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.POSTED) {
            "Only posted vouchers can be cancelled"
        }
        require(reason.isNotBlank()) { "Cancellation reason cannot be blank" }

        val lines = vouchers.linesFor(voucherId)
        val charges = vouchers.chargesFor(voucherId)
        val voucher = entity.toDomain(lines, charges)
        val firmSettings = settingsFor(entity.firmId)
        val originalMovements = stockMovements.movementsForVoucher(voucherId)
            .map { it.toDomain() }
        val reversals = derivation.deriveCancellation(voucher, originalMovements, at)
        enforceStock(reversals, firmSettings)

        val atMillis = at.toEpochMillis()
        val updated = entity.copy(
            status = VoucherStatus.CANCELLED.code(),
            cancellationReason = reason,
            cancelledAt = atMillis,
            sync = entity.sync.copy(
                updatedAt = atMillis,
                revision = entity.sync.revision + 1L,
            ),
        )
        if (reversals.isNotEmpty()) {
            stockMovements.insertAll(reversals.map { it.toEntity() })
        }
        vouchers.update(updated)
        audits.insert(auditEvent(updated, CANCELLED_ACTION, at))
        updated.toDomain(lines, charges)
    }

    private suspend fun settingsFor(firmId: String): FirmSettings =
        settings.findByFirm(firmId)?.toDomain() ?: FirmSettings(firmId = firmId)

    private suspend fun derivePosted(
        voucher: Voucher,
        settings: FirmSettings,
        at: Instant,
    ): List<StockMovement> {
        val itemIds = voucher.lines.mapNotNull { it.itemId }.distinct()
        val itemsById = itemIds
            .mapNotNull { itemId ->
                items.findById(itemId)?.let { item -> itemId to item.toDomain() }
            }
            .toMap()
        return derivation.derivePosted(voucher, itemsById, settings, at)
    }

    private suspend fun enforceStock(
        movements: List<StockMovement>,
        settings: FirmSettings,
    ) {
        if (settings.negativeStockAllowed) return
        val balances = mutableMapOf<StockKey, Quantity>()
        for (movement in movements) {
            val key = StockKey(movement.firmId, movement.itemId, movement.godownId)
            val current = balances.getOrPut(key) {
                Quantity.ofScaledUnits(
                    stockMovements.balanceForItemGodown(
                        movement.firmId,
                        movement.itemId,
                        movement.godownId,
                    ),
                )
            }
            val next = current + movement.quantity
            require(next >= Quantity.of(0)) {
                "Insufficient stock for item ${movement.itemId} in godown ${movement.godownId}"
            }
            balances[key] = next
        }
    }

    private suspend fun allocateNumber(
        voucher: VoucherEntity,
        type: VoucherType,
        at: Instant,
    ): String {
        val documentType = type.documentTypeCode()
        val atMillis = at.toEpochMillis()
        val existing = sequences.get(voucher.firmId, documentType, voucher.financialYearId)
        val sequence = existing ?: VoucherNumberSequenceEntity(
            firmId = voucher.firmId,
            documentType = documentType,
            financialYearId = voucher.financialYearId,
            nextNumber = 1L,
            prefix = prefixFor(type, settings.findByFirm(voucher.firmId)?.invoicePrefix),
            padding = DEFAULT_PADDING,
            createdAt = atMillis,
            updatedAt = atMillis,
        ).also { sequences.upsert(it) }
        val allocated = sequences.increment(
            firmId = voucher.firmId,
            documentType = documentType,
            financialYearId = voucher.financialYearId,
            updatedAt = atMillis,
        )
        return VoucherNumbering.format(sequence.prefix, allocated, sequence.padding)
    }

    private fun prefixFor(type: VoucherType, invoicePrefix: String?): String = when (type) {
        VoucherType.SALE -> invoicePrefix ?: "INV"
        VoucherType.PURCHASE -> "PUR"
        VoucherType.CREDIT_NOTE -> "CRN"
        VoucherType.DEBIT_NOTE -> "DBN"
        VoucherType.QUOTATION -> "QTN"
        VoucherType.PROFORMA -> "PRF"
        VoucherType.PAYMENT_IN -> "RCP"
        VoucherType.PAYMENT_OUT -> "PAY"
        VoucherType.EXPENSE -> "EXP"
        VoucherType.STOCK_ADJUSTMENT -> "ADJ"
        VoucherType.STOCK_TRANSFER -> "TRF"
    }

    private fun auditEvent(voucher: VoucherEntity, action: String, at: Instant): AuditEventEntity =
        AuditEventEntity(
            id = Ulid.create(at.toEpochMilliseconds()).toString(),
            firmId = voucher.firmId,
            entityType = VOUCHER_ENTITY_TYPE,
            entityId = voucher.id,
            voucherId = voucher.id,
            action = action,
            detailsJson = null,
            actor = null,
            createdAt = at.toEpochMilliseconds(),
        )

    private data class StockKey(
        val firmId: String,
        val itemId: String,
        val godownId: String,
    )

    private companion object {
        const val DEFAULT_PADDING = 4
        const val VOUCHER_ENTITY_TYPE = "voucher"
        const val POSTED_ACTION = "voucher.posted"
        const val CANCELLED_ACTION = "voucher.cancelled"
    }
}
