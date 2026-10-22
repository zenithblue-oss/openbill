package app.openbill.core.database

internal object Fixture {
    const val CREATED_AT = 1_700_000_000_000L
    const val UPDATED_AT = 1_700_000_100_000L
    const val DELETED_AT = 1_700_000_200_000L

    const val FIRM_ID = "firm-1"
    const val OTHER_FIRM_ID = "firm-2"
    const val FINANCIAL_YEAR_ID = "fy-2026"
    const val PARTY_ID = "party-1"
    const val ADDRESS_ID = "address-1"
    const val CATEGORY_ID = "category-1"
    const val UNIT_ID = "unit-1"
    const val GODOWN_ID = "godown-1"
    const val ITEM_ID = "item-1"
    const val BATCH_ID = "batch-1"
    const val SERIAL_ID = "serial-1"
    const val VOUCHER_ID = "voucher-1"
    const val PAYMENT_VOUCHER_ID = "voucher-payment-1"
    const val CHARGE_ID = "charge-1"
    const val CASH_ACCOUNT_ID = "account-cash"
    const val SALES_ACCOUNT_ID = "account-sales"
    const val ATTACHMENT_ID = "attachment-1"
    const val AUDIT_ID = "audit-1"

    const val INVOICE_DATE = "2026-05-10"
    const val PAYMENT_DATE = "2026-05-12"
    const val FY_START = "2026-04-01"
    const val FY_END = "2027-03-31"
}

internal fun testSync(
    createdAt: Long = Fixture.CREATED_AT,
    updatedAt: Long = Fixture.UPDATED_AT,
    deletedAt: Long? = null,
    revision: Long = 1,
): SyncMetadata = SyncMetadata(
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    revision = revision,
    originDeviceId = "test-device",
)

internal fun sampleFirm(
    id: String = Fixture.FIRM_ID,
    name: String = "OpenBill Traders",
    gstin: String? = "27ABCDE1234F1Z5",
    deletedAt: Long? = null,
): FirmEntity = FirmEntity(
    id = id,
    name = name,
    legalName = "$name Private Limited",
    gstin = gstin,
    stateCode = "27",
    addressLine1 = "1 Market Road",
    addressLine2 = "Near Station",
    city = "Pune",
    pincode = "411001",
    phone = "02012345678",
    email = "hello@openbill.test",
    logoPath = null,
    signaturePath = null,
    bankName = "Test Bank",
    bankAccountNumber = "000123456789",
    bankIfsc = "TEST0000001",
    bankBranch = "Pune",
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleSettings(
    firmId: String = Fixture.FIRM_ID,
    invoicePrefix: String = "INV",
    defaultGodownId: String? = Fixture.GODOWN_ID,
): FirmSettingsEntity = FirmSettingsEntity(
    firmId = firmId,
    financialYearStartMonth = 4,
    invoicePrefix = invoicePrefix,
    defaultGodownId = defaultGodownId,
    taxEnabled = true,
    roundToRupee = true,
    roundingMode = "half_up",
    negativeStockAllowed = false,
    createdAt = Fixture.CREATED_AT,
    updatedAt = Fixture.UPDATED_AT,
)

internal fun sampleFinancialYear(
    id: String = Fixture.FINANCIAL_YEAR_ID,
    firmId: String = Fixture.FIRM_ID,
    startDate: String = Fixture.FY_START,
    endDate: String = Fixture.FY_END,
): FinancialYearEntity = FinancialYearEntity(
    id = id,
    firmId = firmId,
    startDate = startDate,
    endDate = endDate,
    closedAt = null,
    createdAt = Fixture.CREATED_AT,
    updatedAt = Fixture.UPDATED_AT,
)

internal fun sampleParty(
    id: String = Fixture.PARTY_ID,
    firmId: String = Fixture.FIRM_ID,
    name: String = "Acme Traders",
    phone: String? = "9876543210",
    gstin: String? = "27AAAPL1234C1Z5",
    deletedAt: Long? = null,
): PartyEntity = PartyEntity(
    id = id,
    firmId = firmId,
    name = name,
    type = "customer",
    phone = phone,
    email = "acme@example.test",
    gstin = gstin,
    stateCode = "27",
    openingBalance = 0,
    openingBalanceType = "debit",
    creditLimit = null,
    notes = null,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleAddress(
    id: String = Fixture.ADDRESS_ID,
    partyId: String = Fixture.PARTY_ID,
    kind: String = "billing",
    isDefault: Boolean = true,
): PartyAddressEntity = PartyAddressEntity(
    id = id,
    partyId = partyId,
    kind = kind,
    label = "Head Office",
    line1 = "2 Buyer Street",
    line2 = null,
    city = "Pune",
    stateCode = "27",
    pincode = "411002",
    country = "IN",
    isDefault = isDefault,
    sync = testSync(),
)

internal fun sampleCategory(
    id: String = Fixture.CATEGORY_ID,
    firmId: String = Fixture.FIRM_ID,
    name: String = "Hardware",
    deletedAt: Long? = null,
): ItemCategoryEntity = ItemCategoryEntity(
    id = id,
    firmId = firmId,
    name = name,
    parentId = null,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleUnit(
    id: String = Fixture.UNIT_ID,
    firmId: String = Fixture.FIRM_ID,
    name: String = "Piece",
    deletedAt: Long? = null,
): UnitEntity = UnitEntity(
    id = id,
    firmId = firmId,
    name = name,
    symbol = "pc",
    decimalPlaces = 0,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleGodown(
    id: String = Fixture.GODOWN_ID,
    firmId: String = Fixture.FIRM_ID,
    name: String = "Main Godown",
    isDefault: Boolean = true,
    deletedAt: Long? = null,
): GodownEntity = GodownEntity(
    id = id,
    firmId = firmId,
    name = name,
    address = "Warehouse Road",
    isDefault = isDefault,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleItem(
    id: String = Fixture.ITEM_ID,
    firmId: String = Fixture.FIRM_ID,
    name: String = "Steel Widget",
    sku: String? = "WID-001",
    hsnSac: String? = "7326",
    deletedAt: Long? = null,
): ItemEntity = ItemEntity(
    id = id,
    firmId = firmId,
    name = name,
    sku = sku,
    categoryId = Fixture.CATEGORY_ID,
    unitId = Fixture.UNIT_ID,
    hsnSac = hsnSac,
    isService = false,
    taxRateUnits = 1800,
    cessRateUnits = 0,
    salePrice = 10_000,
    purchasePrice = 8_000,
    mrp = 12_000,
    wholesalePrice = 9_500,
    trackInventory = true,
    trackBatch = false,
    trackSerial = false,
    lowStockLevel = 5,
    notes = null,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleBatch(
    id: String = Fixture.BATCH_ID,
    itemId: String = Fixture.ITEM_ID,
    godownId: String = Fixture.GODOWN_ID,
    batchNumber: String = "BATCH-001",
    deletedAt: Long? = null,
): BatchEntity = BatchEntity(
    id = id,
    itemId = itemId,
    godownId = godownId,
    batchNumber = batchNumber,
    expiryDate = "2027-01-31",
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleSerial(
    id: String = Fixture.SERIAL_ID,
    itemId: String = Fixture.ITEM_ID,
    serialNumber: String = "SN-0001",
    status: String = "in_stock",
): SerialNumberEntity = SerialNumberEntity(
    id = id,
    itemId = itemId,
    serialNumber = serialNumber,
    status = status,
    voucherLineId = null,
    sync = testSync(),
)

internal fun sampleVoucher(
    id: String = Fixture.VOUCHER_ID,
    firmId: String = Fixture.FIRM_ID,
    type: String = "sale",
    number: String? = "INV-0001",
    date: String = Fixture.INVOICE_DATE,
    partyId: String? = Fixture.PARTY_ID,
    status: String = "draft",
    total: Long = 23_600,
    postedAt: Long? = null,
): VoucherEntity = VoucherEntity(
    id = id,
    firmId = firmId,
    financialYearId = Fixture.FINANCIAL_YEAR_ID,
    type = type,
    number = number,
    date = date,
    dueDate = "2026-06-09",
    partyId = partyId,
    referenceVoucherId = null,
    status = status,
    placeOfSupplyStateCode = "27",
    subtotal = 20_000,
    discount = 0,
    taxable = 20_000,
    tax = 3_600,
    roundOff = 0,
    total = total,
    notes = null,
    cancellationReason = null,
    postedAt = postedAt,
    cancelledAt = null,
    restoredAt = null,
    sync = testSync(),
)

internal fun sampleLine(
    voucherId: String = Fixture.VOUCHER_ID,
    lineNumber: Int,
    id: String = "line-$lineNumber",
    itemId: String? = Fixture.ITEM_ID,
    quantity: Long = 5,
    rate: Long = 2_000,
    godownId: String? = Fixture.GODOWN_ID,
    batchId: String? = null,
    stockDirection: String? = null,
): VoucherLineEntity = VoucherLineEntity(
    id = id,
    voucherId = voucherId,
    lineNumber = lineNumber,
    itemId = itemId,
    description = "Steel Widget",
    hsnSac = "7326",
    quantity = quantity,
    rate = rate,
    discount = 0,
    taxRateUnits = 1800,
    cessRateUnits = 0,
    taxMode = "exclusive",
    treatment = "taxable",
    taxable = quantity * rate,
    tax = quantity * rate * 18 / 100,
    total = quantity * rate * 118 / 100,
    godownId = godownId,
    batchId = batchId,
    stockDirection = stockDirection,
    sync = testSync(),
)

internal fun sampleCharge(
    id: String = Fixture.CHARGE_ID,
    voucherId: String = Fixture.VOUCHER_ID,
    sortOrder: Int = 1,
): VoucherChargeEntity = VoucherChargeEntity(
    id = id,
    voucherId = voucherId,
    name = "Freight",
    amount = 500,
    taxRateUnits = 1800,
    cessRateUnits = 0,
    taxAmount = 90,
    total = 590,
    sortOrder = sortOrder,
)

internal fun sampleStockMovement(
    id: String,
    voucherId: String = Fixture.VOUCHER_ID,
    voucherLineId: String? = "line-1",
    date: String = Fixture.INVOICE_DATE,
    quantity: Long,
): StockMovementEntity = StockMovementEntity(
    id = id,
    firmId = Fixture.FIRM_ID,
    itemId = Fixture.ITEM_ID,
    godownId = Fixture.GODOWN_ID,
    batchId = null,
    voucherId = voucherId,
    voucherLineId = voucherLineId,
    date = date,
    quantity = quantity,
    createdAt = Fixture.CREATED_AT,
)

internal fun sampleAccount(
    id: String = Fixture.CASH_ACCOUNT_ID,
    name: String = "Cash",
    type: String = "cash",
    isDefault: Boolean = true,
    deletedAt: Long? = null,
): AccountEntity = AccountEntity(
    id = id,
    firmId = Fixture.FIRM_ID,
    name = name,
    type = type,
    openingBalance = 0,
    isDefault = isDefault,
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleJournalEntry(
    id: String,
    accountId: String,
    debit: Long,
    credit: Long,
    voucherId: String = Fixture.VOUCHER_ID,
    date: String = Fixture.INVOICE_DATE,
): JournalEntryEntity = JournalEntryEntity(
    id = id,
    firmId = Fixture.FIRM_ID,
    voucherId = voucherId,
    accountId = accountId,
    date = date,
    debit = debit,
    credit = credit,
    narration = null,
    createdAt = Fixture.CREATED_AT,
)

internal fun sampleAllocation(
    id: String = "allocation-1",
    amount: Long = 2_500,
): PaymentAllocationEntity = PaymentAllocationEntity(
    id = id,
    firmId = Fixture.FIRM_ID,
    paymentVoucherId = Fixture.PAYMENT_VOUCHER_ID,
    invoiceVoucherId = Fixture.VOUCHER_ID,
    amount = amount,
    createdAt = Fixture.CREATED_AT,
    updatedAt = Fixture.UPDATED_AT,
)

internal fun sampleAuditEvent(
    id: String = Fixture.AUDIT_ID,
    firmId: String = Fixture.FIRM_ID,
    entityType: String = "voucher",
    entityId: String = Fixture.VOUCHER_ID,
    action: String = "created",
    createdAt: Long = Fixture.CREATED_AT,
): AuditEventEntity = AuditEventEntity(
    id = id,
    firmId = firmId,
    entityType = entityType,
    entityId = entityId,
    voucherId = if (entityType == "voucher") entityId else null,
    action = action,
    detailsJson = null,
    actor = "test",
    createdAt = createdAt,
)

internal fun sampleAttachment(
    id: String = Fixture.ATTACHMENT_ID,
    firmId: String = Fixture.FIRM_ID,
    entityType: String = "voucher",
    entityId: String = Fixture.VOUCHER_ID,
    deletedAt: Long? = null,
): AttachmentEntity = AttachmentEntity(
    id = id,
    firmId = firmId,
    entityType = entityType,
    entityId = entityId,
    fileName = "invoice.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1_024,
    storagePath = "attachments/invoice.pdf",
    sha256 = "abc123",
    sync = testSync(deletedAt = deletedAt),
)

internal fun sampleSequence(
    firmId: String = Fixture.FIRM_ID,
    documentType: String = "sale",
    financialYearId: String = Fixture.FINANCIAL_YEAR_ID,
    nextNumber: Long = 1,
    prefix: String = "INV",
): VoucherNumberSequenceEntity = VoucherNumberSequenceEntity(
    firmId = firmId,
    documentType = documentType,
    financialYearId = financialYearId,
    nextNumber = nextNumber,
    prefix = prefix,
    padding = 4,
    createdAt = Fixture.CREATED_AT,
    updatedAt = Fixture.UPDATED_AT,
)

internal suspend fun OpenBillDatabase.seedBaseline() {
    firmDao().insert(sampleFirm())
    firmSettingsDao().upsert(sampleSettings())
    financialYearDao().insert(sampleFinancialYear())
    partyDao().insert(sampleParty())
    partyDao().insertAddress(sampleAddress())
    catalogDao().insertCategory(sampleCategory())
    catalogDao().insertUnit(sampleUnit())
    catalogDao().insertGodown(sampleGodown())
    itemDao().insert(sampleItem())
    batchDao().insert(sampleBatch())
    serialNumberDao().insert(sampleSerial())
    voucherDao().insertWithLines(
        voucher = sampleVoucher(),
        lines = listOf(sampleLine(lineNumber = 1), sampleLine(lineNumber = 2, quantity = 3)),
        charges = listOf(sampleCharge()),
    )
    voucherDao().insert(
        sampleVoucher(
            id = Fixture.PAYMENT_VOUCHER_ID,
            type = "payment",
            number = "PAY-0001",
            date = Fixture.PAYMENT_DATE,
            status = "posted",
            total = 2_500,
            postedAt = Fixture.UPDATED_AT,
        ),
    )
    stockMovementDao().insertAll(
        listOf(
            sampleStockMovement(id = "movement-1", voucherLineId = "line-1", quantity = 5),
            sampleStockMovement(
                id = "movement-2",
                voucherLineId = "line-2",
                date = "2026-05-11",
                quantity = -2,
            ),
        ),
    )
    ledgerDao().insertAccount(sampleAccount())
    ledgerDao().insertAccount(
        sampleAccount(
            id = Fixture.SALES_ACCOUNT_ID,
            name = "Sales",
            type = "income",
            isDefault = false,
        ),
    )
    ledgerDao().insertEntries(
        listOf(
            sampleJournalEntry(
                id = "journal-1",
                accountId = Fixture.CASH_ACCOUNT_ID,
                debit = 23_600,
                credit = 0,
            ),
            sampleJournalEntry(
                id = "journal-2",
                accountId = Fixture.SALES_ACCOUNT_ID,
                debit = 0,
                credit = 23_600,
            ),
        ),
    )
    paymentAllocationDao().insertAll(listOf(sampleAllocation()))
    auditDao().insert(sampleAuditEvent())
    attachmentDao().insert(sampleAttachment())
    voucherNumberSequenceDao().upsert(sampleSequence())
}
