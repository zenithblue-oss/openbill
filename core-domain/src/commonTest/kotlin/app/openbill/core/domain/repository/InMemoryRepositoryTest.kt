package app.openbill.core.domain.repository

import app.openbill.core.domain.fake.InMemoryCatalogRepository
import app.openbill.core.domain.fake.InMemoryFirmRepository
import app.openbill.core.domain.fake.InMemoryPartyRepository
import app.openbill.core.domain.fake.InMemoryStockRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
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
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.seconds

class InMemoryRepositoryTest {
    private val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    private val archivedAt = Instant.fromEpochMilliseconds(1_700_000_200_000)

    @Test
    fun firmRepositoryTracksActiveFirmsSettingsAndYears(): kotlin.Unit = runBlocking {
        val repository = InMemoryFirmRepository()
        repository.saveFirm(firm("firm-1", "Zeta Traders"))
        repository.saveFirm(firm("firm-2", "Alpha Traders"))
        repository.saveFirm(firm("firm-3", "Archived Traders"))

        assertEquals(
            listOf("Alpha Traders", "Archived Traders", "Zeta Traders"),
            repository.observeFirms().first().map { it.name },
        )

        repository.archiveFirm("firm-3", archivedAt)
        assertEquals(2, repository.observeFirms().first().size)
        assertNotNull(repository.findFirm("firm-3")?.deletedAt)

        repository.saveSettings(FirmSettings(firmId = "firm-1", invoicePrefix = "SALE"))
        assertEquals("SALE", repository.observeSettings("firm-1").first()?.invoicePrefix)
        assertNull(repository.findSettings("firm-2"))

        repository.saveFinancialYear(
            FinancialYear("fy-2", "firm-1", LocalDate(2027, 4, 1), LocalDate(2028, 3, 31)),
        )
        repository.saveFinancialYear(
            FinancialYear("fy-1", "firm-1", LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)),
        )
        assertEquals(
            listOf("fy-2", "fy-1"),
            repository.observeFinancialYears("firm-1").first().map { it.id },
        )
    }

    @Test
    fun partyRepositorySearchesByContactFieldsAndScopesFirm(): kotlin.Unit = runBlocking {
        val repository = InMemoryPartyRepository()
        repository.saveParty(party("party-1", "firm-1", "Acme Traders", "9876543210"))
        repository.saveParty(party("party-2", "firm-1", "Beta Supplies", "9123456789"))
        repository.saveParty(party("party-3", "firm-2", "Acme Other", "9876500000"))

        assertEquals(listOf("party-1"), repository.searchParties("firm-1", "acme").map { it.id })
        assertEquals(listOf("party-2"), repository.searchParties("firm-1", "9123").map { it.id })
        assertEquals(
            listOf("party-1", "party-2"),
            repository.searchParties("firm-1", "27aaapl").map { it.id },
        )
        assertTrue(repository.searchParties("firm-1", "   ").isEmpty())

        repository.archiveParty("party-1", archivedAt)
        assertTrue(repository.searchParties("firm-1", "acme").isEmpty())
        assertEquals(1, repository.observeParties("firm-1").first().size)
        assertNotNull(repository.observeParty("party-1").first()?.deletedAt)
    }

    @Test
    fun catalogRepositoryFiltersArchivesAndSearchesItemFields(): kotlin.Unit = runBlocking {
        val repository = InMemoryCatalogRepository()
        repository.saveUnit(unit("unit-1", "firm-1", "Piece"))
        repository.saveGodown(godown("godown-1", "firm-1", "Main", isDefault = true))
        repository.saveCategory(category("category-1", "firm-1", "Hardware"))
        repository.saveItem(item("item-1", "firm-1", "Steel Widget", sku = "WID-001"))
        repository.saveItem(
            item("item-2", "firm-1", "Copper Wire", sku = "WIR-002", hsnSac = "8544"),
        )

        assertEquals(
            listOf("item-2", "item-1"),
            repository.searchItems("firm-1", "e").map { it.id },
        )
        assertEquals(listOf("item-1"), repository.searchItems("firm-1", "wid").map { it.id })
        assertEquals(listOf("item-1"), repository.searchItems("firm-1", "7326").map { it.id })

        repository.archiveItem("item-1", archivedAt)
        assertEquals(listOf("item-2"), repository.observeItems("firm-1").first().map { it.id })
        assertTrue(repository.searchItems("firm-1", "wid").isEmpty())

        repository.archiveCategory("category-1", archivedAt)
        assertTrue(repository.observeCategories("firm-1").first().isEmpty())
        assertTrue(repository.observeUnits("firm-1").first().isNotEmpty())
        assertTrue(repository.observeGodowns("firm-1").first().single().isDefault)
    }

    @Test
    fun voucherRepositoryFiltersDateRangeAndScopesFirm(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1", "firm-1", LocalDate(2026, 5, 10)))
        repository.saveDraft(voucher("voucher-2", "firm-1", LocalDate(2026, 6, 10)))
        repository.saveDraft(voucher("voucher-3", "firm-2", LocalDate(2026, 5, 10)))

        assertEquals(
            listOf("voucher-2", "voucher-1"),
            repository.observeVouchers(
                "firm-1",
                LocalDate(2026, 5, 1),
                LocalDate(2026, 6, 30),
            ).first().map { it.id },
        )
        assertEquals(
            listOf("voucher-1"),
            repository.observeVouchers(
                "firm-1",
                LocalDate(2026, 5, 1),
                LocalDate(2026, 5, 31),
            ).first().map { it.id },
        )
        assertNull(repository.findVoucher("missing"))
        assertEquals("voucher-1", repository.observeVoucher("voucher-1").first()?.id)
    }

    @Test
    fun voucherRepositoryReplacesDraftsAndRejectsPostedMutations(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val draft = voucher("voucher-1", "firm-1", LocalDate(2026, 5, 10))
        repository.saveDraft(draft)
        repository.saveDraft(
            draft.copy(notes = "Revised", updatedAt = updatedAt + 1.seconds),
        )

        assertEquals("Revised", repository.findVoucher("voucher-1")?.notes)
        assertEquals(1, repository.observeVouchers(
            "firm-1",
            LocalDate(2026, 1, 1),
            LocalDate(2026, 12, 31),
        ).first().size)

        repository.deleteDraft("voucher-1")
        assertNull(repository.findVoucher("voucher-1"))

        val posted = draft.copy(
            status = VoucherStatus.POSTED,
            number = "INV-0001",
            postedAt = updatedAt,
        )
        assertFailsWith<IllegalArgumentException> { repository.saveDraft(posted) }
        assertFailsWith<IllegalArgumentException> { repository.deleteDraft("missing") }
    }

    @Test
    fun repositoriesCanBeUsedThroughPorts(): kotlin.Unit = runBlocking {
        val repositories = Repositories(
            firms = InMemoryFirmRepository(),
            parties = InMemoryPartyRepository(),
            catalog = InMemoryCatalogRepository(),
            vouchers = InMemoryVoucherRepository(),
            stock = InMemoryStockRepository(),
        )
        repositories.firms.saveFirm(firm("firm-1", "OpenBill"))
        repositories.parties.saveParty(party("party-1", "firm-1", "Acme", "9876543210"))

        assertEquals("OpenBill", repositories.firms.observeFirms().first().single().name)
        assertEquals(1, repositories.parties.observeParties("firm-1").first().size)
    }

    private fun firm(id: String, name: String): Firm = Firm(
        id = id,
        name = name,
        legalName = "$name Private Limited",
        gstin = "27ABCDE1234F1Z5",
        stateCode = "27",
        addressLine1 = "1 Market Road",
        city = "Pune",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun party(id: String, firmId: String, name: String, phone: String): Party = Party(
        id = id,
        firmId = firmId,
        name = name,
        type = PartyType.CUSTOMER,
        phone = phone,
        gstin = "27AAAPL1234C1Z5",
        stateCode = "27",
        openingBalance = Money.ZERO,
        openingBalanceType = OpeningBalanceType.DEBIT,
        addresses = listOf(
            PartyAddress(
                id = "address-$id",
                kind = AddressKind.BILLING,
                line1 = "2 Buyer Street",
                city = "Pune",
                stateCode = "27",
                isDefault = true,
            ),
        ),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun unit(id: String, firmId: String, name: String): UnitOfMeasure = UnitOfMeasure(
        id = id,
        firmId = firmId,
        name = name,
        symbol = "pc",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun godown(
        id: String,
        firmId: String,
        name: String,
        isDefault: Boolean,
    ): Godown = Godown(
        id = id,
        firmId = firmId,
        name = name,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun category(id: String, firmId: String, name: String): ItemCategory = ItemCategory(
        id = id,
        firmId = firmId,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun item(
        id: String,
        firmId: String,
        name: String,
        sku: String,
        hsnSac: String = "7326",
    ): Item = Item(
        id = id,
        firmId = firmId,
        name = name,
        sku = sku,
        categoryId = "category-1",
        unitId = "unit-1",
        hsnSac = hsnSac,
        taxRate = Rate.ofBasisPoints(1_800),
        salePrice = Money.of(10_000),
        purchasePrice = Money.of(8_000),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun voucher(id: String, firmId: String, date: LocalDate): Voucher = Voucher(
        id = id,
        firmId = firmId,
        financialYearId = "fy-1",
        type = VoucherType.SALE,
        date = date,
        partyId = "party-1",
        placeOfSupplyStateCode = "27",
        lines = listOf(
            VoucherLine(
                id = "line-$id",
                lineNumber = 1,
                itemId = "item-1",
                description = "Steel Widget",
                quantity = Quantity.of(2),
                rate = Money.of(10_000),
                taxRate = Rate.ofBasisPoints(1_800),
                taxable = Money.of(20_000),
                tax = Money.of(3_600),
                total = Money.of(23_600),
            ),
        ),
        subtotal = Money.of(20_000),
        taxable = Money.of(20_000),
        tax = Money.of(3_600),
        total = Money.of(23_600),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
