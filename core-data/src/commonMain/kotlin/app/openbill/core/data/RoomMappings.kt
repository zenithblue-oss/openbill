package app.openbill.core.data

import app.openbill.core.database.AccountEntity
import app.openbill.core.database.FinancialYearEntity
import app.openbill.core.database.FirmEntity
import app.openbill.core.database.FirmSettingsEntity
import app.openbill.core.database.GodownEntity
import app.openbill.core.database.ItemCategoryEntity
import app.openbill.core.database.BatchEntity
import app.openbill.core.database.ItemEntity
import app.openbill.core.database.SerialNumberEntity
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.SerialStatus
import app.openbill.core.database.JournalEntryEntity
import app.openbill.core.database.PartyAddressEntity
import app.openbill.core.database.PartyEntity
import app.openbill.core.database.PaymentAllocationEntity
import app.openbill.core.database.PaymentDetailsEntity
import app.openbill.core.database.StockMovementEntity
import app.openbill.core.database.SyncMetadata
import app.openbill.core.database.UnitEntity
import app.openbill.core.database.VoucherChargeEntity
import app.openbill.core.database.VoucherChargePortionEntity
import app.openbill.core.database.VoucherEntity
import app.openbill.core.database.VoucherLineEntity
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentAllocation
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherCharge
import app.openbill.core.domain.model.VoucherChargePortion
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.TaxTreatment
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

internal inline fun <reified E : Enum<E>> decodeEnum(raw: String, label: String): E {
    val normalized = raw.trim().lowercase().replace('-', '_')
    return enumValues<E>().firstOrNull { it.name.lowercase() == normalized }
        ?: throw IllegalStateException("Unknown $label '$raw'")
}

internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun Instant.toEpochMillis(): Long = toEpochMilliseconds()

internal fun syncFor(
    createdAt: Instant,
    updatedAt: Instant,
    deletedAt: Instant?,
    existing: SyncMetadata?,
): SyncMetadata = SyncMetadata(
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillis(),
    revision = (existing?.revision ?: 0L) + 1L,
    originDeviceId = existing?.originDeviceId,
)

internal fun FirmEntity.toDomain(): Firm = Firm(
    id = id,
    name = name,
    legalName = legalName,
    gstin = gstin,
    stateCode = stateCode,
    addressLine1 = addressLine1,
    addressLine2 = addressLine2,
    city = city,
    pincode = pincode,
    phone = phone,
    email = email,
    logoPath = logoPath,
    signaturePath = signaturePath,
    bankName = bankName,
    bankAccountNumber = bankAccountNumber,
    bankIfsc = bankIfsc,
    bankBranch = bankBranch,
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Firm.toEntity(existing: FirmEntity?): FirmEntity = FirmEntity(
    id = id,
    name = name,
    legalName = legalName,
    gstin = gstin,
    stateCode = stateCode,
    addressLine1 = addressLine1,
    addressLine2 = addressLine2,
    city = city,
    pincode = pincode,
    phone = phone,
    email = email,
    logoPath = logoPath,
    signaturePath = signaturePath,
    bankName = bankName,
    bankAccountNumber = bankAccountNumber,
    bankIfsc = bankIfsc,
    bankBranch = bankBranch,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun FirmSettingsEntity.toDomain(): FirmSettings = FirmSettings(
    firmId = firmId,
    financialYearStartMonth = financialYearStartMonth,
    invoicePrefix = invoicePrefix,
    defaultGodownId = defaultGodownId,
    taxEnabled = taxEnabled,
    roundToRupee = roundToRupee,
    roundingMode = decodeEnum<RoundingMode>(roundingMode, "rounding mode"),
    negativeStockAllowed = negativeStockAllowed,
    lockDate = lockDate?.let(LocalDate::parse),
)

internal fun FirmSettings.toEntity(
    existing: FirmSettingsEntity?,
    now: Instant,
): FirmSettingsEntity = FirmSettingsEntity(
    firmId = firmId,
    financialYearStartMonth = financialYearStartMonth,
    invoicePrefix = invoicePrefix,
    defaultGodownId = defaultGodownId,
    taxEnabled = taxEnabled,
    roundToRupee = roundToRupee,
    roundingMode = roundingMode.name.lowercase(),
    negativeStockAllowed = negativeStockAllowed,
    lockDate = lockDate?.toString(),
    createdAt = existing?.createdAt ?: now.toEpochMillis(),
    updatedAt = now.toEpochMillis(),
)

internal fun FinancialYearEntity.toDomain(): FinancialYear = FinancialYear(
    id = id,
    firmId = firmId,
    startDate = LocalDate.parse(startDate),
    endDate = LocalDate.parse(endDate),
    closedAt = closedAt?.toInstant(),
)

internal fun FinancialYear.toEntity(
    existing: FinancialYearEntity?,
    now: Instant,
): FinancialYearEntity = FinancialYearEntity(
    id = id,
    firmId = firmId,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    closedAt = closedAt?.toEpochMillis(),
    createdAt = existing?.createdAt ?: now.toEpochMillis(),
    updatedAt = now.toEpochMillis(),
)

internal fun PartyEntity.toDomain(addresses: List<PartyAddressEntity>): Party = Party(
    id = id,
    firmId = firmId,
    name = name,
    type = decodeEnum<PartyType>(type, "party type"),
    phone = phone,
    email = email,
    gstin = gstin,
    stateCode = stateCode,
    openingBalance = Money.of(openingBalance),
    openingBalanceType = decodeEnum<OpeningBalanceType>(
        openingBalanceType,
        "opening balance type",
    ),
    creditLimit = creditLimit?.let(Money::of),
    notes = notes,
    addresses = addresses.map { it.toDomain() },
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Party.toEntity(existing: PartyEntity?): PartyEntity = PartyEntity(
    id = id,
    firmId = firmId,
    name = name,
    type = type.name.lowercase(),
    phone = phone,
    email = email,
    gstin = gstin,
    stateCode = stateCode,
    openingBalance = openingBalance.paise,
    openingBalanceType = openingBalanceType.name.lowercase(),
    creditLimit = creditLimit?.paise,
    notes = notes,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun PartyAddressEntity.toDomain(): PartyAddress = PartyAddress(
    id = id,
    kind = decodeEnum<AddressKind>(kind, "address kind"),
    label = label,
    line1 = line1,
    line2 = line2,
    city = city,
    stateCode = stateCode,
    pincode = pincode,
    country = country,
    isDefault = isDefault,
    gstin = gstin,
)

internal fun PartyAddress.toEntity(
    partyId: String,
    party: Party,
    existing: PartyAddressEntity?,
): PartyAddressEntity = PartyAddressEntity(
    id = id,
    partyId = partyId,
    kind = kind.name.lowercase(),
    label = label,
    line1 = line1,
    line2 = line2,
    city = city,
    stateCode = stateCode,
    pincode = pincode,
    country = country,
    isDefault = isDefault,
    gstin = gstin,
    sync = syncFor(
        createdAt = party.createdAt,
        updatedAt = party.updatedAt,
        deletedAt = null,
        existing = existing?.sync,
    ),
)

internal fun ItemCategoryEntity.toDomain(): ItemCategory = ItemCategory(
    id = id,
    firmId = firmId,
    name = name,
    parentId = parentId,
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun ItemCategory.toEntity(existing: ItemCategoryEntity?): ItemCategoryEntity =
    ItemCategoryEntity(
        id = id,
        firmId = firmId,
        name = name,
        parentId = parentId,
        sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
    )

internal fun UnitEntity.toDomain(): UnitOfMeasure = UnitOfMeasure(
    id = id,
    firmId = firmId,
    name = name,
    symbol = symbol,
    decimalPlaces = decimalPlaces,
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun UnitOfMeasure.toEntity(existing: UnitEntity?): UnitEntity = UnitEntity(
    id = id,
    firmId = firmId,
    name = name,
    symbol = symbol,
    decimalPlaces = decimalPlaces,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun GodownEntity.toDomain(): Godown = Godown(
    id = id,
    firmId = firmId,
    name = name,
    address = address,
    isDefault = isDefault,
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Godown.toEntity(existing: GodownEntity?): GodownEntity = GodownEntity(
    id = id,
    firmId = firmId,
    name = name,
    address = address,
    isDefault = isDefault,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun ItemEntity.toDomain(): Item = Item(
    id = id,
    firmId = firmId,
    name = name,
    sku = sku,
    barcode = barcode,
    categoryId = categoryId,
    unitId = unitId,
    hsnSac = hsnSac,
    isService = isService,
    taxRate = Rate.ofBasisPointsHundredths(taxRateUnits),
    cessRate = Rate.ofBasisPointsHundredths(cessRateUnits),
    salePrice = Money.of(salePrice),
    purchasePrice = Money.of(purchasePrice),
    mrp = mrp?.let(Money::of),
    wholesalePrice = wholesalePrice?.let(Money::of),
    trackInventory = trackInventory,
    trackBatch = trackBatch,
    trackSerial = trackSerial,
    lowStockLevel = lowStockLevel?.let(Quantity::ofScaledUnits),
    notes = notes,
    alternateUnitId = alternateUnitId,
    alternateUnitFactor = alternateUnitFactor?.let(Quantity::ofScaledUnits),
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Item.toEntity(existing: ItemEntity?): ItemEntity = ItemEntity(
    id = id,
    firmId = firmId,
    name = name,
    sku = sku,
    barcode = barcode,
    categoryId = categoryId,
    unitId = unitId,
    hsnSac = hsnSac,
    isService = isService,
    taxRateUnits = taxRate.units,
    cessRateUnits = cessRate.units,
    salePrice = salePrice.paise,
    purchasePrice = purchasePrice.paise,
    mrp = mrp?.paise,
    wholesalePrice = wholesalePrice?.paise,
    trackInventory = trackInventory,
    trackBatch = trackBatch,
    trackSerial = trackSerial,
    lowStockLevel = lowStockLevel?.scaledUnits,
    notes = notes,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
    alternateUnitId = alternateUnitId,
    alternateUnitFactor = alternateUnitFactor?.scaledUnits,
)

internal fun VoucherEntity.toDomain(
    lines: List<VoucherLineEntity>,
    charges: List<VoucherChargeEntity>,
    chargePortions: List<VoucherChargePortionEntity> = emptyList(),
): Voucher = Voucher(
    id = id,
    firmId = firmId,
    financialYearId = financialYearId,
    type = decodeEnum<VoucherType>(type, "voucher type"),
    number = number,
    date = LocalDate.parse(date),
    dueDate = dueDate?.let(LocalDate::parse),
    partyId = partyId,
    referenceVoucherId = referenceVoucherId,
    referenceNumber = referenceNumber,
    ledgerAccountId = ledgerAccountId,
    status = decodeEnum<VoucherStatus>(status, "voucher status"),
    placeOfSupplyStateCode = placeOfSupplyStateCode,
    lines = lines.map { it.toDomain() },
    charges = charges.map { charge ->
        charge.toDomain(
            chargePortions.filter { it.chargeId == charge.id }.sortedBy { it.portionIndex },
        )
    },
    subtotal = Money.of(subtotal),
    discount = Money.of(discount),
    taxable = Money.of(taxable),
    tax = Money.of(tax),
    roundOff = Money.of(roundOff),
    total = Money.of(total),
    notes = notes,
    cancellationReason = cancellationReason,
    postedAt = postedAt?.toInstant(),
    cancelledAt = cancelledAt?.toInstant(),
    restoredAt = restoredAt?.toInstant(),
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Voucher.toEntity(existing: VoucherEntity?): VoucherEntity = VoucherEntity(
    id = id,
    firmId = firmId,
    financialYearId = financialYearId,
    type = type.name.lowercase(),
    number = number,
    date = date.toString(),
    dueDate = dueDate?.toString(),
    partyId = partyId,
    referenceVoucherId = referenceVoucherId,
    referenceNumber = referenceNumber,
    ledgerAccountId = ledgerAccountId,
    status = status.name.lowercase(),
    placeOfSupplyStateCode = placeOfSupplyStateCode,
    subtotal = subtotal.paise,
    discount = discount.paise,
    taxable = taxable.paise,
    tax = tax.paise,
    roundOff = roundOff.paise,
    total = total.paise,
    notes = notes,
    cancellationReason = cancellationReason,
    postedAt = postedAt?.toEpochMillis(),
    cancelledAt = cancelledAt?.toEpochMillis(),
    restoredAt = restoredAt?.toEpochMillis(),
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun VoucherLineEntity.toDomain(): VoucherLine = VoucherLine(
    id = id,
    lineNumber = lineNumber,
    itemId = itemId,
    description = description,
    hsnSac = hsnSac,
    quantity = Quantity.ofScaledUnits(quantity),
    rate = Money.of(rate),
    discount = Money.of(discount),
    taxRate = Rate.ofBasisPointsHundredths(taxRateUnits),
    cessRate = Rate.ofBasisPointsHundredths(cessRateUnits),
    taxMode = decodeEnum<TaxMode>(taxMode, "tax mode"),
    treatment = decodeEnum<TaxTreatment>(treatment, "tax treatment"),
    taxable = Money.of(taxable),
    tax = Money.of(tax),
    total = Money.of(total),
    godownId = godownId,
    batchId = batchId,
    stockDirection = stockDirection?.let {
        decodeEnum<StockDirection>(it, "stock direction")
    },
    batchNumber = batchNumber,
    expiryDate = expiryDate?.let(LocalDate::parse),
    serialNumbers = serialNumbers?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
    toGodownId = toGodownId,
)

internal fun VoucherLine.toEntity(
    voucherId: String,
    voucher: Voucher,
    existing: VoucherLineEntity?,
): VoucherLineEntity = VoucherLineEntity(
    id = id,
    voucherId = voucherId,
    lineNumber = lineNumber,
    itemId = itemId,
    description = description,
    hsnSac = hsnSac,
    quantity = quantity.scaledUnits,
    rate = rate.paise,
    discount = discount.paise,
    taxRateUnits = taxRate.units,
    cessRateUnits = cessRate.units,
    taxMode = taxMode.name.lowercase(),
    treatment = treatment.name.lowercase(),
    taxable = taxable.paise,
    tax = tax.paise,
    total = total.paise,
    godownId = godownId,
    batchId = batchId,
    stockDirection = this.stockDirection?.name?.lowercase(),
    batchNumber = batchNumber,
    expiryDate = expiryDate?.toString(),
    serialNumbers = serialNumbers.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    toGodownId = toGodownId,
    sync = syncFor(
        createdAt = voucher.createdAt,
        updatedAt = voucher.updatedAt,
        deletedAt = null,
        existing = existing?.sync,
    ),
)

internal fun VoucherChargePortionEntity.toDomain(): VoucherChargePortion = VoucherChargePortion(
    treatment = decodeEnum<TaxTreatment>(treatment, "charge portion tax treatment"),
    taxRate = Rate.ofBasisPointsHundredths(taxRateUnits),
    cessRate = Rate.ofBasisPointsHundredths(cessRateUnits),
    taxableValue = Money.of(taxableValue),
    taxAmount = Money.of(taxAmount),
    total = Money.of(total),
)

internal fun VoucherCharge.portionEntities(voucherId: String): List<VoucherChargePortionEntity> =
    portions.mapIndexed { index, portion ->
        VoucherChargePortionEntity(
            id = "$id:$index",
            chargeId = id,
            voucherId = voucherId,
            portionIndex = index,
            treatment = portion.treatment.name.lowercase(),
            taxRateUnits = portion.taxRate.units,
            cessRateUnits = portion.cessRate.units,
            taxableValue = portion.taxableValue.paise,
            taxAmount = portion.taxAmount.paise,
            total = portion.total.paise,
        )
    }

internal fun VoucherChargeEntity.toDomain(
    portions: List<VoucherChargePortionEntity> = emptyList(),
): VoucherCharge = VoucherCharge(
    id = id,
    name = name,
    amount = Money.of(amount),
    taxRate = Rate.ofBasisPointsHundredths(taxRateUnits),
    cessRate = Rate.ofBasisPointsHundredths(cessRateUnits),
    taxAmount = Money.of(taxAmount),
    total = Money.of(total),
    sortOrder = sortOrder,
    taxBasis = decodeEnum<ChargeTaxBasis>(taxBasis, "charge tax basis"),
    taxMode = decodeEnum<TaxMode>(taxMode, "charge tax mode"),
    treatment = decodeEnum<TaxTreatment>(treatment, "charge tax treatment"),
    hsnSac = hsnSac,
    portions = portions.map { it.toDomain() },
)

internal fun VoucherCharge.toEntity(voucherId: String): VoucherChargeEntity =
    VoucherChargeEntity(
        id = id,
        voucherId = voucherId,
        name = name,
        amount = amount.paise,
        taxRateUnits = taxRate.units,
        cessRateUnits = cessRate.units,
        taxAmount = taxAmount.paise,
        total = total.paise,
        sortOrder = sortOrder,
        taxBasis = taxBasis.name.lowercase(),
        taxMode = taxMode.name.lowercase(),
        treatment = treatment.name.lowercase(),
        hsnSac = hsnSac,
    )

internal fun VoucherType.documentTypeCode(): String = name.lowercase()

internal fun VoucherStatus.code(): String = name.lowercase()

internal fun StockMovementEntity.toDomain(): StockMovement = StockMovement(
    id = id,
    firmId = firmId,
    itemId = itemId,
    godownId = godownId,
    batchId = batchId,
    voucherId = voucherId,
    voucherLineId = voucherLineId,
    date = LocalDate.parse(date),
    quantity = Quantity.ofScaledUnits(quantity),
    createdAt = createdAt.toInstant(),
    unitCost = unitCost?.let(Money::of),
)

internal fun StockMovement.toEntity(): StockMovementEntity = StockMovementEntity(
    id = id,
    firmId = firmId,
    itemId = itemId,
    godownId = godownId,
    batchId = batchId,
    voucherId = voucherId,
    voucherLineId = voucherLineId,
    date = date.toString(),
    quantity = quantity.scaledUnits,
    createdAt = createdAt.toEpochMillis(),
    unitCost = unitCost?.paise,
)

internal fun AccountEntity.toDomain(): Account = Account(
    id = id,
    firmId = firmId,
    name = name,
    type = decodeEnum<AccountType>(type, "account type"),
    code = code?.let { decodeEnum<AccountCode>(it, "account code") },
    openingBalance = Money.of(openingBalance),
    isDefault = isDefault,
    createdAt = sync.createdAt.toInstant(),
    updatedAt = sync.updatedAt.toInstant(),
    deletedAt = sync.deletedAt?.toInstant(),
)

internal fun Account.toEntity(existing: AccountEntity?): AccountEntity = AccountEntity(
    id = id,
    firmId = firmId,
    name = name,
    type = type.name.lowercase(),
    code = code?.name,
    openingBalance = openingBalance.paise,
    isDefault = isDefault,
    sync = syncFor(createdAt, updatedAt, deletedAt, existing?.sync),
)

internal fun JournalEntryEntity.toDomain(): JournalEntry = JournalEntry(
    id = id,
    firmId = firmId,
    voucherId = voucherId,
    accountId = accountId,
    date = LocalDate.parse(date),
    debit = Money.of(debit),
    credit = Money.of(credit),
    narration = narration,
    createdAt = createdAt.toInstant(),
    clearedOn = clearedOn?.let(LocalDate::parse),
)

internal fun JournalEntry.toEntity(): JournalEntryEntity = JournalEntryEntity(
    id = id,
    firmId = firmId,
    voucherId = voucherId,
    accountId = accountId,
    date = date.toString(),
    debit = debit.paise,
    credit = credit.paise,
    narration = narration,
    createdAt = createdAt.toEpochMillis(),
    clearedOn = clearedOn?.toString(),
)

internal fun PaymentDetailsEntity.toDomain(): PaymentDetails = PaymentDetails(
    voucherId = voucherId,
    firmId = firmId,
    accountId = accountId,
    method = decodeEnum<PaymentMethod>(method, "payment method"),
    instrumentNumber = instrumentNumber,
    instrumentDate = instrumentDate?.let(LocalDate::parse),
    bankName = bankName,
    branch = branch,
    upiId = upiId,
    reference = reference,
    notes = notes,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

internal fun PaymentDetails.toEntity(): PaymentDetailsEntity = PaymentDetailsEntity(
    voucherId = voucherId,
    firmId = firmId,
    accountId = accountId,
    method = method.name.lowercase(),
    instrumentNumber = instrumentNumber,
    instrumentDate = instrumentDate?.toString(),
    bankName = bankName,
    branch = branch,
    upiId = upiId,
    reference = reference,
    notes = notes,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
)

internal fun PaymentAllocationEntity.toDomain(): PaymentAllocation = PaymentAllocation(
    id = id,
    firmId = firmId,
    paymentVoucherId = paymentVoucherId,
    invoiceVoucherId = invoiceVoucherId,
    amount = Money.of(amount),
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

internal fun PaymentAllocation.toEntity(): PaymentAllocationEntity = PaymentAllocationEntity(
    id = id,
    firmId = firmId,
    paymentVoucherId = paymentVoucherId,
    invoiceVoucherId = invoiceVoucherId,
    amount = amount.paise,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
)

internal fun BatchEntity.toDomain(): Batch = Batch(
    id = id,
    itemId = itemId,
    godownId = godownId,
    batchNumber = batchNumber,
    expiryDate = expiryDate?.let(LocalDate::parse),
)

internal fun SerialNumberEntity.toDomain(): SerialNumber = SerialNumber(
    id = id,
    itemId = itemId,
    serialNumber = serialNumber,
    status = decodeEnum<SerialStatus>(status, "serial status"),
    voucherLineId = voucherLineId,
)
