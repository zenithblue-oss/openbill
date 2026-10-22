package app.openbill.core.data

import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.ItemCategory
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherCharge
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

internal const val CREATED_MILLIS = 1_700_000_000_000L
internal const val UPDATED_MILLIS = 1_700_000_100_000L
internal const val ARCHIVED_MILLIS = 1_700_000_200_000L
internal const val POSTED_MILLIS = 1_700_000_300_000L
internal const val CANCELLED_MILLIS = 1_700_000_400_000L

internal val CREATED_AT: Instant = Instant.fromEpochMilliseconds(CREATED_MILLIS)
internal val UPDATED_AT: Instant = Instant.fromEpochMilliseconds(UPDATED_MILLIS)
internal val ARCHIVED_AT: Instant = Instant.fromEpochMilliseconds(ARCHIVED_MILLIS)
internal val POSTED_AT: Instant = Instant.fromEpochMilliseconds(POSTED_MILLIS)
internal val CANCELLED_AT: Instant = Instant.fromEpochMilliseconds(CANCELLED_MILLIS)

internal fun testFirm(
    id: String = "firm-1",
    name: String = "OpenBill Traders",
): Firm = Firm(
    id = id,
    name = name,
    legalName = "$name Private Limited",
    gstin = "27ABCDE1234F1Z5",
    stateCode = "27",
    addressLine1 = "1 Market Road",
    addressLine2 = "Near Station",
    city = "Pune",
    pincode = "411001",
    phone = "02012345678",
    email = "hello@openbill.test",
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testSettings(
    firmId: String = "firm-1",
    invoicePrefix: String = "INV",
): FirmSettings = FirmSettings(
    firmId = firmId,
    invoicePrefix = invoicePrefix,
    defaultGodownId = "godown-1",
)

internal fun testFinancialYear(
    id: String = "fy-1",
    firmId: String = "firm-1",
    startDate: LocalDate = LocalDate(2026, 4, 1),
    endDate: LocalDate = LocalDate(2027, 3, 31),
): FinancialYear = FinancialYear(
    id = id,
    firmId = firmId,
    startDate = startDate,
    endDate = endDate,
)

internal fun testAddress(
    id: String,
    kind: AddressKind = AddressKind.BILLING,
    city: String = "Pune",
    line1: String = "2 Buyer Street",
    isDefault: Boolean = false,
): PartyAddress = PartyAddress(
    id = id,
    kind = kind,
    label = "Head Office",
    line1 = line1,
    line2 = null,
    city = city,
    stateCode = "27",
    pincode = "411002",
    isDefault = isDefault,
)

internal fun testParty(
    id: String = "party-1",
    firmId: String = "firm-1",
    name: String = "Acme Traders",
    addresses: List<PartyAddress> = listOf(
        testAddress("address-1", AddressKind.BILLING, isDefault = true),
        testAddress("address-2", AddressKind.SHIPPING, city = "Mumbai"),
    ),
): Party = Party(
    id = id,
    firmId = firmId,
    name = name,
    type = PartyType.CUSTOMER,
    phone = "9876543210",
    email = "acme@example.test",
    gstin = "27AAAPL1234C1Z5",
    stateCode = "27",
    openingBalance = Money.of(12_345L),
    openingBalanceType = OpeningBalanceType.DEBIT,
    creditLimit = Money.of(500_000L),
    notes = "Net 30",
    addresses = addresses,
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testCategory(
    id: String = "category-1",
    firmId: String = "firm-1",
    name: String = "Hardware",
): ItemCategory = ItemCategory(
    id = id,
    firmId = firmId,
    name = name,
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testUnit(
    id: String = "unit-1",
    firmId: String = "firm-1",
    name: String = "Piece",
): UnitOfMeasure = UnitOfMeasure(
    id = id,
    firmId = firmId,
    name = name,
    symbol = "pc",
    decimalPlaces = 0,
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testGodown(
    id: String = "godown-1",
    firmId: String = "firm-1",
    name: String = "Main Godown",
): Godown = Godown(
    id = id,
    firmId = firmId,
    name = name,
    address = "Warehouse Road",
    isDefault = true,
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testItem(
    id: String = "item-1",
    firmId: String = "firm-1",
    name: String = "Steel Widget",
    sku: String = "WID-001",
): Item = Item(
    id = id,
    firmId = firmId,
    name = name,
    sku = sku,
    categoryId = "category-1",
    unitId = "unit-1",
    hsnSac = "7326",
    isService = false,
    taxRate = Rate.ofPercent(18),
    cessRate = Rate.ofPercent(1),
    salePrice = Money.of(10_000L),
    purchasePrice = Money.of(8_000L),
    mrp = Money.of(12_000L),
    wholesalePrice = Money.of(9_500L),
    trackInventory = true,
    trackBatch = false,
    trackSerial = false,
    lowStockLevel = Quantity.of(5),
    notes = "Fast moving",
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)

internal fun testLine(
    id: String = "line-1",
    lineNumber: Int = 1,
    itemId: String? = null,
    rate: Money = Money.of(10_000L),
): VoucherLine = VoucherLine(
    id = id,
    lineNumber = lineNumber,
    itemId = itemId,
    description = "Consulting service",
    quantity = Quantity.of(2),
    rate = rate,
    discount = Money.ZERO,
    taxRate = Rate.ofPercent(18),
    cessRate = Rate.ofPercent(1),
    taxable = Money.of(20_000L),
    tax = Money.of(3_800L),
    total = Money.of(23_800L),
    godownId = null,
    batchId = null,
)

internal fun testCharge(
    id: String = "charge-1",
    sortOrder: Int = 0,
): VoucherCharge = VoucherCharge(
    id = id,
    name = "Freight",
    amount = Money.of(500L),
    taxRate = Rate.ofPercent(18),
    taxAmount = Money.of(90L),
    total = Money.of(590L),
    sortOrder = sortOrder,
)

internal fun testVoucher(
    id: String = "voucher-1",
    firmId: String = "firm-1",
    financialYearId: String = "fy-1",
    type: VoucherType = VoucherType.SALE,
    date: LocalDate = LocalDate(2026, 5, 10),
    lines: List<VoucherLine> = listOf(testLine()),
    charges: List<VoucherCharge> = listOf(testCharge()),
): Voucher = Voucher(
    id = id,
    firmId = firmId,
    financialYearId = financialYearId,
    type = type,
    number = null,
    date = date,
    dueDate = null,
    partyId = null,
    status = VoucherStatus.DRAFT,
    placeOfSupplyStateCode = "27",
    lines = lines,
    charges = charges,
    subtotal = Money.of(20_000L),
    discount = Money.ZERO,
    taxable = Money.of(20_000L),
    tax = Money.of(3_800L),
    roundOff = Money.ZERO,
    total = Money.of(23_800L),
    notes = "Draft voucher",
    createdAt = CREATED_AT,
    updatedAt = UPDATED_AT,
)
