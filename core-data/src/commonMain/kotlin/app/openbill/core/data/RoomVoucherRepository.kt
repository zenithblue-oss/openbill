package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.BatchEntity
import app.openbill.core.database.SerialNumberEntity
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.SerialStatus
import app.openbill.core.domain.service.InventoryRules
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.VoucherEntity
import app.openbill.core.database.VoucherNumberSequenceEntity
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherNumbering
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.booksLockedThrough
import app.openbill.core.domain.model.requireBooksOpen
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.isPaymentAccount
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.domain.model.requireLedgerAccount
import app.openbill.core.domain.model.requiresParty
import app.openbill.core.domain.model.usesPaymentAccount
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.domain.service.LedgerJournalDerivation
import app.openbill.core.domain.service.StockMovementDerivation
import app.openbill.core.model.Quantity
import app.openbill.core.model.Ulid
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class RoomVoucherRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
    private val ledger: RoomLedgerRepository = RoomLedgerRepository(database, transactions),
) : VoucherRepository {
    private val vouchers get() = database.voucherDao()
    private val sequences get() = database.voucherNumberSequenceDao()
    private val audits get() = database.auditDao()
    private val settings get() = database.firmSettingsDao()
    private val items get() = database.itemDao()
    private val stockMovements get() = database.stockMovementDao()
    private val paymentDetails get() = database.paymentDetailsDao()
    private val batches get() = database.batchDao()
    private val serials get() = database.serialNumberDao()
    private val derivation = StockMovementDerivation()
    private val ledgerDerivation = LedgerJournalDerivation()

    override fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>> = vouchers.observeByFirmAndDateRange(
        firmId = firmId,
        fromDate = fromDate.toString(),
        toDate = toDate.toString(),
    ).map { values -> values.map { it.toDomain(vouchers.linesFor(it.id), vouchers.chargesFor(it.id), vouchers.portionsFor(it.id)) } }

    override fun observeVoucher(id: String): Flow<Voucher?> =
        vouchers.observeById(id).map { value ->
            value?.toDomain(vouchers.linesFor(value.id), vouchers.chargesFor(value.id), vouchers.portionsFor(value.id))
        }

    override fun observeRecycleBin(firmId: String): Flow<List<Voucher>> =
        vouchers.observeRecycleBin(firmId).map { values ->
            values.map { value ->
                value.toDomain(vouchers.linesFor(value.id), vouchers.chargesFor(value.id), vouchers.portionsFor(value.id))
            }
        }

    override suspend fun findVoucher(id: String): Voucher? {
        val voucher = vouchers.findById(id) ?: return null
        return voucher.toDomain(vouchers.linesFor(id), vouchers.chargesFor(id), vouchers.portionsFor(id))
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
                vouchers.insertChargePortions(voucher.charges.flatMap { it.portionEntities(voucher.id) })
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
            val at = Clock.System.now()
            audits.insert(
                auditEvent(
                    entity,
                    DRAFT_DELETED_ACTION,
                    at,
                    AuditDetails.json("permanent" to true),
                ),
            )
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
        requireOpen(entity.firmId, entity.date)

        val lines = vouchers.linesFor(voucherId)
        val charges = vouchers.chargesFor(voucherId)
        val voucher = entity.toDomain(lines, charges)
        val firmSettings = settingsFor(entity.firmId)
        val movements = derivePosted(voucher, firmSettings, at)
        enforceStock(movements, firmSettings)
        applySerials(voucher, firmSettings, at, reverse = false)

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
        val posted = updated.toDomain(lines, charges)
        val journalEntries = deriveJournalPosted(posted, at)
        vouchers.update(updated)
        if (movements.isNotEmpty()) {
            stockMovements.insertAll(movements.map { it.toEntity() })
        }
        if (journalEntries.isNotEmpty()) {
            ledgerDao().insertEntries(journalEntries.map { it.toEntity() })
        }
        audits.insert(auditEvent(updated, POSTED_ACTION, at))
        posted
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
        requireOpen(entity.firmId, entity.date)

        val lines = vouchers.linesFor(voucherId)
        val charges = vouchers.chargesFor(voucherId)
        val voucher = entity.toDomain(lines, charges)
        val firmSettings = settingsFor(entity.firmId)
        val originalMovements = stockMovements.movementsForVoucher(voucherId)
            .map { it.toDomain() }
        val reversals = derivation.deriveCancellation(voucher, originalMovements, at)
        enforceStock(reversals, firmSettings)
        applySerials(voucher, firmSettings, at, reverse = true)
        val journalReversals = deriveJournalCancelled(voucher, at)

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
        if (journalReversals.isNotEmpty()) {
            ledgerDao().insertEntries(journalReversals.map { it.toEntity() })
        }
        vouchers.update(updated)
        audits.insert(auditEvent(updated, CANCELLED_ACTION, at))
        updated.toDomain(lines, charges)
    }

    override suspend fun restoreCancelled(
        voucherId: String,
        cancellationReason: String,
        restoreReason: String,
        at: Instant,
    ): Voucher = transactions.write {
        val entity = vouchers.findById(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.CANCELLED) {
            "Only cancelled vouchers can be restored"
        }
        require(entity.sync.deletedAt == null) { "Deleted vouchers cannot be restored" }
        requireOpen(entity.firmId, entity.date)
        val recordedReason = entity.cancellationReason
        require(!recordedReason.isNullOrBlank()) {
            "Cancelled voucher has no cancellation reason"
        }
        require(cancellationReason.trim() == recordedReason.trim()) {
            "Cancellation reason does not match the recorded reason"
        }
        val trimmedRestoreReason = restoreReason.trim()
        require(trimmedRestoreReason.isNotEmpty()) { "Restore reason cannot be blank" }

        val lines = vouchers.linesFor(voucherId)
        val charges = vouchers.chargesFor(voucherId)
        val cancelled = entity.toDomain(lines, charges)
        val posted = cancelled.copy(status = VoucherStatus.POSTED)
        val firmSettings = settingsFor(entity.firmId)
        val restoreDate = at.toLocalDateTime(TimeZone.UTC).date
        val movements = derivePosted(posted, firmSettings, at).map { it.copy(date = restoreDate) }
        enforceStock(movements, firmSettings)
        applySerials(posted, firmSettings, at, reverse = false)
        val journalEntries = deriveJournalPosted(posted, at).map { it.copy(date = restoreDate) }

        val atMillis = at.toEpochMillis()
        val updated = entity.copy(
            status = VoucherStatus.POSTED.code(),
            restoredAt = atMillis,
            sync = entity.sync.copy(
                updatedAt = atMillis,
                revision = entity.sync.revision + 1L,
            ),
        )
        if (movements.isNotEmpty()) {
            stockMovements.insertAll(movements.map { it.toEntity() })
        }
        if (journalEntries.isNotEmpty()) {
            ledgerDao().insertEntries(journalEntries.map { it.toEntity() })
        }
        vouchers.update(updated)
        audits.insert(
            auditEvent(
                updated,
                RESTORED_ACTION,
                at,
                AuditDetails.json(
                    "cancellationReason" to recordedReason,
                    "restoreReason" to trimmedRestoreReason,
                    "restoredAt" to atMillis,
                ),
            ),
        )
        updated.toDomain(lines, charges)
    }

    override suspend fun softDeleteDraft(id: String, at: Instant): Voucher = transactions.write {
        val entity = vouchers.findById(id)
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.DRAFT) {
            "Only drafts can be moved to the recycle bin"
        }
        require(entity.sync.deletedAt == null) { "Draft is already in the recycle bin" }
        val atMillis = at.toEpochMillis()
        val changed = vouchers.softDeleteDraft(id, atMillis)
        require(changed == 1) { "Only drafts can be moved to the recycle bin" }
        val updated = vouchers.findById(id)
            ?: throw IllegalStateException("Draft disappeared while moving to the recycle bin")
        audits.insert(
            auditEvent(
                updated,
                DRAFT_DELETED_ACTION,
                at,
                AuditDetails.json("permanent" to false),
            ),
        )
        updated.toDomain(vouchers.linesFor(id), vouchers.chargesFor(id), vouchers.portionsFor(id))
    }

    override suspend fun restoreDraft(id: String, at: Instant): Voucher = transactions.write {
        val entity = vouchers.findById(id)
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.DRAFT) {
            "Only drafts can be restored from the recycle bin"
        }
        require(entity.sync.deletedAt != null) { "Draft is not in the recycle bin" }
        val atMillis = at.toEpochMilliseconds()
        val changed = vouchers.restoreDraft(id, atMillis)
        require(changed == 1) { "Only drafts can be restored from the recycle bin" }
        val updated = vouchers.findById(id)
            ?: throw IllegalStateException("Draft disappeared while leaving the recycle bin")
        audits.insert(auditEvent(updated, DRAFT_RESTORED_ACTION, at))
        updated.toDomain(vouchers.linesFor(id), vouchers.chargesFor(id), vouchers.portionsFor(id))
    }

    override suspend fun purgeDraft(id: String) {
        transactions.write {
            val entity = vouchers.findById(id)
                ?: throw IllegalArgumentException("Unknown voucher: $id")
            require(decodeEnum<VoucherStatus>(entity.status, "voucher status") == VoucherStatus.DRAFT) {
                "Only drafts can be purged"
            }
            require(entity.sync.deletedAt != null) { "Only recycle bin drafts can be purged" }
            val at = Clock.System.now()
            audits.insert(
                auditEvent(
                    entity,
                    PURGED_ACTION,
                    at,
                    AuditDetails.json("permanent" to true),
                ),
            )
            vouchers.deleteLinesFor(id)
            vouchers.deleteChargesFor(id)
            paymentDetails.delete(id)
            val changed = vouchers.purgeSoftDeletedDraft(id)
            require(changed == 1) { "Only recycle bin drafts can be purged" }
        }
    }

    private fun ledgerDao() = database.ledgerDao()

    private suspend fun deriveJournalPosted(
        voucher: Voucher,
        at: Instant,
    ): List<JournalEntry> {
        if (!voucher.type.postsLedger()) return emptyList()
        if (voucher.type.requiresParty()) {
            require(voucher.partyId != null) {
                "Voucher must have a party to post to the ledger"
            }
        }
        val paymentAccountId = if (voucher.type.usesPaymentAccount()) {
            val details = paymentDetails.findForVoucher(voucher.id)
                ?: throw IllegalArgumentException(
                    "Payment details are required before posting",
                )
            val account = ledgerDao().findAccountById(details.accountId)
                ?: throw IllegalArgumentException("Unknown account: ${details.accountId}")
            require(account.firmId == voucher.firmId) {
                "Payment account belongs to a different firm"
            }
            val domainAccount = account.toDomain()
            require(domainAccount.deletedAt == null) { "Payment account is deleted" }
            require(domainAccount.type.isPaymentAccount) {
                "Payment account must be a cash, bank, or asset account"
            }
            account.id
        } else {
            null
        }
        requireLedgerAccount(
            voucher,
            voucher.ledgerAccountId?.let { ledgerDao().findAccountById(it)?.toDomain() },
            paymentAccountId,
        )
        val accounts = ledger.ensureSystemAccountsLocked(voucher.firmId, at)
        return ledgerDerivation.derivePosted(voucher, accounts, paymentAccountId, at)
    }

    private suspend fun deriveJournalCancelled(
        voucher: Voucher,
        at: Instant,
    ): List<JournalEntry> {
        if (!voucher.type.postsLedger()) return emptyList()
        val entries = ledgerDao().entriesForVoucher(voucher.id).map { it.toDomain() }
        return ledgerDerivation.deriveReversal(voucher, entries, at)
    }

    private suspend fun settingsFor(firmId: String): FirmSettings =
        settings.findByFirm(firmId)?.toDomain() ?: FirmSettings(firmId = firmId)

    private suspend fun itemsOf(voucher: Voucher): Map<String, Item> =
        voucher.lines.mapNotNull { it.itemId }.distinct()
            .mapNotNull { itemId -> items.findById(itemId)?.let { item -> itemId to item.toDomain() } }
            .toMap()

    private suspend fun derivePosted(
        voucher: Voucher,
        settings: FirmSettings,
        at: Instant,
    ): List<StockMovement> {
        val itemsById = itemsOf(voucher)
        // Batch rows are created on demand (per item, godown and batch number) inside the
        // posting transaction, so a rejected posting leaves none behind.
        val batchIds = mutableMapOf<Triple<String, String, String>, String>()
        for (line in voucher.lines) {
            val item = line.itemId?.let { itemsById[it] } ?: continue
            val number = line.batchNumber?.trim()?.ifEmpty { null } ?: continue
            if (!item.trackInventory || item.isService) continue
            val godowns = listOfNotNull(line.godownId ?: settings.defaultGodownId, line.toGodownId)
            for (godown in godowns) {
                val key = Triple(item.id, godown, number)
                if (key in batchIds) continue
                val existing = batches.findByNumber(item.id, godown, number)
                batchIds[key] = existing?.id ?: Ulid.create(at.toEpochMilliseconds()).toString().also { id ->
                    batches.insert(
                        BatchEntity(
                            id = id,
                            itemId = item.id,
                            godownId = godown,
                            batchNumber = number,
                            expiryDate = line.expiryDate?.toString(),
                            sync = syncFor(at, at, null, null),
                        ),
                    )
                }
            }
        }
        return derivation.derivePosted(voucher, itemsById, settings, at) { line, godown ->
            line.batchNumber?.trim()?.let { batchIds[Triple(line.itemId!!, godown, it)] } ?: line.batchId
        }
    }

    /** Moves each serial number in or out of stock; [reverse] undoes a posting (cancellation). */
    private suspend fun applySerials(voucher: Voucher, settings: FirmSettings, at: Instant, reverse: Boolean) {
        val itemsById = itemsOf(voucher)
        for (line in voucher.lines) {
            val item = line.itemId?.let { itemsById[it] } ?: continue
            if (!item.trackSerial || line.serialNumbers.isEmpty()) continue
            val direction = StockMovementDerivation.directionOf(voucher.type, line) ?: continue
            val inward = when (direction) {
                StockMovementDerivation.LineDirection.INWARD -> !reverse
                StockMovementDerivation.LineDirection.OUTWARD -> reverse
                StockMovementDerivation.LineDirection.TRANSFER -> null
            }
            for (raw in line.serialNumbers) {
                val serial = raw.trim()
                val existing = serials.findByNumber(item.id, serial)
                val next = InventoryRules.serialTransition(
                    item.name,
                    serial,
                    existing?.let { decodeEnum<SerialStatus>(it.status, "serial status") },
                    inward,
                    allowUnknown = settings.negativeStockAllowed,
                ).name.lowercase()
                if (existing == null) {
                    serials.insert(
                        SerialNumberEntity(
                            id = Ulid.create(at.toEpochMilliseconds()).toString(),
                            itemId = item.id,
                            serialNumber = serial,
                            status = next,
                            voucherLineId = line.id,
                            sync = syncFor(at, at, null, null),
                        ),
                    )
                } else {
                    serials.update(
                        existing.copy(
                            status = next,
                            voucherLineId = line.id,
                            sync = existing.sync.copy(updatedAt = at.toEpochMillis(), revision = existing.sync.revision + 1),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun enforceStock(
        movements: List<StockMovement>,
        settings: FirmSettings,
    ) {
        if (settings.negativeStockAllowed) return
        val balances = mutableMapOf<StockKey, Quantity>()
        for (movement in movements) {
            // Batch stock is checked per batch; everything else per item and godown.
            val key = StockKey(movement.firmId, movement.itemId, movement.godownId, movement.batchId)
            val current = balances.getOrPut(key) {
                Quantity.ofScaledUnits(
                    movement.batchId?.let { stockMovements.balanceForBatch(it) }
                        ?: stockMovements.balanceForItemGodown(
                            movement.firmId,
                            movement.itemId,
                            movement.godownId,
                        ),
                )
            }
            val next = current + movement.quantity
            require(next >= Quantity.of(0)) {
                val name = runCatching { items.findById(movement.itemId)?.name }.getOrNull() ?: movement.itemId
                val godown = runCatching { database.catalogDao().findGodownById(movement.godownId)?.name }.getOrNull() ?: movement.godownId
                "Insufficient stock for $name in $godown (available ${current.format()}); " +
                    "allow negative stock in settings to override"
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
        VoucherType.DELIVERY_CHALLAN -> "DC"
        VoucherType.CONTRA -> "CTR"
    }

    /** Books lock: posting, cancelling and restoring are refused inside the locked period. */
    private suspend fun requireOpen(firmId: String, date: String) {
        val locked = booksLockedThrough(
            settings.findByFirm(firmId)?.lockDate?.let(LocalDate::parse),
            database.financialYearDao().latestClosedEndDate(firmId)?.let(LocalDate::parse),
        )
        requireBooksOpen(LocalDate.parse(date), locked)
    }

    private fun auditEvent(
        voucher: VoucherEntity,
        action: String,
        at: Instant,
        detailsJson: String? = null,
    ): AuditEventEntity = AuditEventEntity(
        id = Ulid.create(at.toEpochMilliseconds()).toString(),
        firmId = voucher.firmId,
        entityType = VOUCHER_ENTITY_TYPE,
        entityId = voucher.id,
        voucherId = voucher.id,
        action = action,
        detailsJson = detailsJson,
        actor = null,
        createdAt = at.toEpochMilliseconds(),
    )

    private data class StockKey(
        val firmId: String,
        val itemId: String,
        val godownId: String,
        val batchId: String?,
    )

    private companion object {
        const val DEFAULT_PADDING = 4
        const val VOUCHER_ENTITY_TYPE = "voucher"
        const val POSTED_ACTION = "voucher.posted"
        const val CANCELLED_ACTION = "voucher.cancelled"
        const val RESTORED_ACTION = "voucher.restored"
        const val DRAFT_DELETED_ACTION = "voucher.draft_deleted"
        const val DRAFT_RESTORED_ACTION = "voucher.draft_restored"
        const val PURGED_ACTION = "voucher.purged"
    }
}
