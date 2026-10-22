package app.openbill.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private suspend fun <T> Flow<T>.firstValue(): T = withTimeout(10_000) { first() }

class DaoQueryTest {
    private lateinit var db: OpenBillDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun firmAndSettingsDao(): Unit = runBlocking {
        db.firmDao().insert(sampleFirm())
        db.firmSettingsDao().upsert(sampleSettings())

        assertEquals("OpenBill Traders", db.firmDao().findById(Fixture.FIRM_ID)?.name)
        assertEquals(listOf(Fixture.FIRM_ID), db.firmDao().observeActive().firstValue().map { it.id })
        assertEquals("INV", db.firmSettingsDao().findByFirm(Fixture.FIRM_ID)?.invoicePrefix)

        db.firmSettingsDao().upsert(
            sampleSettings(invoicePrefix = "BILL").copy(updatedAt = Fixture.UPDATED_AT + 1),
        )
        assertEquals("BILL", db.firmSettingsDao().findByFirm(Fixture.FIRM_ID)?.invoicePrefix)

        assertEquals(
            1,
            db.firmDao().softDelete(Fixture.FIRM_ID, Fixture.DELETED_AT, Fixture.DELETED_AT),
        )
        assertTrue(db.firmDao().observeActive().firstValue().isEmpty())
        assertNotNull(db.firmDao().findById(Fixture.FIRM_ID))
    }

    @Test
    fun financialYearDaoScopesAndFindsContainingDate(): Unit = runBlocking {
        db.firmDao().insert(sampleFirm())
        db.financialYearDao().insert(sampleFinancialYear())

        assertEquals(Fixture.FINANCIAL_YEAR_ID, db.financialYearDao().findById(Fixture.FINANCIAL_YEAR_ID)?.id)
        assertEquals(
            listOf(Fixture.FINANCIAL_YEAR_ID),
            db.financialYearDao().observeForFirm(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(
            Fixture.FINANCIAL_YEAR_ID,
            db.financialYearDao().findContaining(Fixture.FIRM_ID, "2026-05-10")?.id,
        )
        assertEquals(
            Fixture.FINANCIAL_YEAR_ID,
            db.financialYearDao().findContaining("2027-03-31")?.id,
        )
        assertNull(db.financialYearDao().findContaining(Fixture.FIRM_ID, "2025-05-10"))

        db.firmDao().insert(sampleFirm(id = Fixture.OTHER_FIRM_ID, name = "Other Firm"))
        db.financialYearDao().insert(
            sampleFinancialYear(id = "fy-other", firmId = Fixture.OTHER_FIRM_ID),
        )
        assertEquals(
            listOf(Fixture.FINANCIAL_YEAR_ID),
            db.financialYearDao().observeForFirm(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(
            "fy-other",
            db.financialYearDao().findContaining(Fixture.OTHER_FIRM_ID, "2026-05-10")?.id,
        )
    }

    @Test
    fun partyDaoSearchUpdateAddressAndSoftDelete(): Unit = runBlocking {
        db.seedBaseline()
        db.firmDao().insert(sampleFirm(id = Fixture.OTHER_FIRM_ID, name = "Other Firm"))
        db.partyDao().insert(
            sampleParty(id = "party-2", name = "Beta Supplies", phone = "9123456780", gstin = null),
        )
        db.partyDao().insert(
            sampleParty(id = "party-deleted", name = "Acme Archived", deletedAt = Fixture.DELETED_AT),
        )
        db.partyDao().insert(
            sampleParty(
                id = "party-other",
                firmId = Fixture.OTHER_FIRM_ID,
                name = "Acme Other",
            ),
        )

        assertEquals(
            listOf(Fixture.PARTY_ID, "party-2"),
            db.partyDao().observeActive(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(listOf(Fixture.PARTY_ID), db.partyDao().search(Fixture.FIRM_ID, "acme").map { it.id })
        assertEquals(listOf(Fixture.PARTY_ID), db.partyDao().search(Fixture.FIRM_ID, "98765").map { it.id })
        assertEquals(listOf(Fixture.PARTY_ID), db.partyDao().search(Fixture.FIRM_ID, "AAAPL").map { it.id })
        assertTrue(db.partyDao().search(Fixture.FIRM_ID, "Archived").isEmpty())

        val party = assertNotNull(db.partyDao().findById(Fixture.PARTY_ID))
        db.partyDao().update(
            party.copy(name = "Acme Prime", phone = "9000000000", sync = party.sync.copy(revision = 2)),
        )
        assertEquals("Acme Prime", db.partyDao().findById(Fixture.PARTY_ID)?.name)
        assertEquals(listOf(Fixture.PARTY_ID), db.partyDao().search(Fixture.FIRM_ID, "Prime").map { it.id })

        db.partyDao().insertAddress(
            sampleAddress(id = "address-2", kind = "shipping", isDefault = false).copy(
                city = "Mumbai",
                sync = testSync(),
            ),
        )
        assertEquals(
            listOf(Fixture.ADDRESS_ID, "address-2"),
            db.partyDao().addressesFor(Fixture.PARTY_ID).map { it.id },
        )
        db.partyDao().updateAddress(
            sampleAddress().copy(city = "Pune City", sync = testSync(updatedAt = Fixture.UPDATED_AT + 1)),
        )
        assertEquals("Pune City", db.partyDao().addressesFor(Fixture.PARTY_ID).first().city)

        assertEquals(
            1,
            db.partyDao().softDelete(Fixture.PARTY_ID, Fixture.DELETED_AT, Fixture.DELETED_AT),
        )
        assertEquals(
            listOf("party-2"),
            db.partyDao().observeActive(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertTrue(db.partyDao().search(Fixture.FIRM_ID, "acme").isEmpty())
        assertNotNull(db.partyDao().findById(Fixture.PARTY_ID))
    }

    @Test
    fun catalogDaoOrdersSearchesAndFiltersDeleted(): Unit = runBlocking {
        db.seedBaseline()
        db.catalogDao().insertCategory(sampleCategory(id = "category-2", name = "Fasteners"))
        db.catalogDao().insertCategory(
            sampleCategory(id = "category-3", name = "Archived", deletedAt = Fixture.DELETED_AT),
        )
        assertEquals(
            listOf("category-2", Fixture.CATEGORY_ID),
            db.catalogDao().observeCategories(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(
            listOf(Fixture.CATEGORY_ID),
            db.catalogDao().searchCategories(Fixture.FIRM_ID, "hard").map { it.id },
        )

        db.catalogDao().insertUnit(sampleUnit(id = "unit-2", name = "Box").copy(symbol = "bx"))
        db.catalogDao().insertUnit(
            sampleUnit(id = "unit-3", name = "Archived Unit", deletedAt = Fixture.DELETED_AT),
        )
        assertEquals(
            listOf("unit-2", Fixture.UNIT_ID),
            db.catalogDao().observeUnits(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(listOf(Fixture.UNIT_ID), db.catalogDao().searchUnits(Fixture.FIRM_ID, "pc").map { it.id })
        assertEquals(listOf("unit-2"), db.catalogDao().searchUnits(Fixture.FIRM_ID, "box").map { it.id })

        db.catalogDao().insertGodown(
            sampleGodown(id = "godown-2", name = "Branch Godown", isDefault = false),
        )
        db.catalogDao().insertGodown(
            sampleGodown(id = "godown-3", name = "Archived Godown", deletedAt = Fixture.DELETED_AT),
        )
        assertEquals(
            listOf(Fixture.GODOWN_ID, "godown-2"),
            db.catalogDao().observeGodowns(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(
            listOf("godown-2"),
            db.catalogDao().searchGodowns(Fixture.FIRM_ID, "branch").map { it.id },
        )
        assertEquals(
            Fixture.GODOWN_ID,
            db.catalogDao().findGodownById(Fixture.GODOWN_ID)?.id,
        )
    }

    @Test
    fun itemDaoSearchCoversNameSkuAndHsnAndActiveOnly(): Unit = runBlocking {
        db.seedBaseline()
        db.itemDao().insert(
            sampleItem(id = "item-2", name = "Copper Coil", sku = "COIL-9", hsnSac = "7411"),
        )
        db.itemDao().insert(
            sampleItem(
                id = "item-3",
                name = "Steel Widget Archived",
                sku = "WID-OLD",
                hsnSac = "7326",
                deletedAt = Fixture.DELETED_AT,
            ),
        )

        assertEquals(
            listOf("item-2", Fixture.ITEM_ID),
            db.itemDao().observeActive(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(listOf(Fixture.ITEM_ID), db.itemDao().search(Fixture.FIRM_ID, "WID").map { it.id })
        assertEquals(listOf("item-2"), db.itemDao().search(Fixture.FIRM_ID, "7411").map { it.id })
        assertEquals(listOf(Fixture.ITEM_ID), db.itemDao().search(Fixture.FIRM_ID, "steel").map { it.id })
        assertNull(db.itemDao().findById("missing-item"))

        val item = assertNotNull(db.itemDao().findById(Fixture.ITEM_ID))
        db.itemDao().update(item.copy(salePrice = 12_345, sync = item.sync.copy(revision = 2)))
        assertEquals(12_345, db.itemDao().findById(Fixture.ITEM_ID)?.salePrice)
    }

    @Test
    fun batchAndSerialDaoFindObserveAndSearch(): Unit = runBlocking {
        db.seedBaseline()
        db.batchDao().insert(
            sampleBatch(id = "batch-2", batchNumber = "BATCH-000").copy(expiryDate = "2026-12-31"),
        )
        assertEquals(
            listOf("batch-2", Fixture.BATCH_ID),
            db.batchDao().observeForItem(Fixture.ITEM_ID).firstValue().map { it.id },
        )
        assertEquals(
            listOf("batch-2", Fixture.BATCH_ID),
            db.batchDao().search(Fixture.FIRM_ID, "BATCH").map { it.id },
        )
        assertEquals(2, db.batchDao().search(Fixture.FIRM_ID, "Steel").size)

        db.serialNumberDao().insert(sampleSerial(id = "serial-2", serialNumber = "SN-0002"))
        assertEquals(
            listOf(Fixture.SERIAL_ID, "serial-2"),
            db.serialNumberDao().observeForItem(Fixture.ITEM_ID).firstValue().map { it.id },
        )
        assertEquals(
            "SN-0001",
            db.serialNumberDao().findByNumber(Fixture.ITEM_ID, "SN-0001")?.serialNumber,
        )
        assertEquals(
            listOf("serial-2"),
            db.serialNumberDao().search(Fixture.FIRM_ID, "SN-0002").map { it.id },
        )

        db.batchDao().update(sampleBatch(deletedAt = Fixture.DELETED_AT))
        assertEquals(
            listOf("batch-2"),
            db.batchDao().observeForItem(Fixture.ITEM_ID).firstValue().map { it.id },
        )
        assertEquals(
            listOf("batch-2"),
            db.batchDao().search(Fixture.FIRM_ID, "BATCH").map { it.id },
        )
    }

    @Test
    fun voucherDaoReadsLinesChargesRangeAndUpdates(): Unit = runBlocking {
        db.seedBaseline()

        assertEquals("INV-0001", db.voucherDao().findById(Fixture.VOUCHER_ID)?.number)
        assertEquals(
            Fixture.VOUCHER_ID,
            db.voucherDao().observeById(Fixture.VOUCHER_ID).firstValue()?.id,
        )
        assertNull(db.voucherDao().observeById("missing-voucher").firstValue())

        assertEquals(
            listOf(Fixture.PAYMENT_VOUCHER_ID, Fixture.VOUCHER_ID),
            db.voucherDao()
                .observeByFirmAndDateRange(Fixture.FIRM_ID, "2026-05-01", "2026-05-31")
                .firstValue()
                .map { it.id },
        )
        assertEquals(
            listOf(Fixture.VOUCHER_ID),
            db.voucherDao()
                .observeByFirmAndDateRange(Fixture.FIRM_ID, Fixture.INVOICE_DATE, Fixture.INVOICE_DATE)
                .firstValue()
                .map { it.id },
        )

        assertEquals(listOf(1, 2), db.voucherDao().linesFor(Fixture.VOUCHER_ID).map { it.lineNumber })
        assertEquals(listOf(Fixture.CHARGE_ID), db.voucherDao().chargesFor(Fixture.VOUCHER_ID).map { it.id })
        assertEquals(2, db.voucherDao().lineCount(Fixture.VOUCHER_ID))

        assertEquals(1, db.voucherDao().assignNumber(Fixture.VOUCHER_ID, "INV-0099", Fixture.UPDATED_AT))
        assertEquals("INV-0099", db.voucherDao().findById(Fixture.VOUCHER_ID)?.number)
        assertEquals(1, db.voucherDao().updateStatus(Fixture.VOUCHER_ID, "posted", Fixture.UPDATED_AT))
        assertEquals("posted", db.voucherDao().findById(Fixture.VOUCHER_ID)?.status)
        assertEquals(Fixture.UPDATED_AT, db.voucherDao().findById(Fixture.VOUCHER_ID)?.sync?.updatedAt)

        db.voucherDao().insertWithLines(
            voucher = sampleVoucher(id = "voucher-new", number = null, date = "2026-05-20"),
            lines = listOf(
                sampleLine(voucherId = "voucher-new", lineNumber = 1, id = "new-line-1"),
                sampleLine(voucherId = "voucher-new", lineNumber = 2, id = "new-line-2"),
            ),
            charges = listOf(sampleCharge(id = "new-charge", voucherId = "voucher-new")),
        )
        assertEquals(2, db.voucherDao().lineCount("voucher-new"))
        assertEquals(1, db.voucherDao().chargesFor("voucher-new").size)
    }

    @Test
    fun voucherRangeExcludesOtherFirms(): Unit = runBlocking {
        db.seedBaseline()
        db.firmDao().insert(sampleFirm(id = Fixture.OTHER_FIRM_ID, name = "Other Firm"))
        db.financialYearDao().insert(
            sampleFinancialYear(id = "fy-other", firmId = Fixture.OTHER_FIRM_ID),
        )
        db.voucherDao().insert(
            sampleVoucher(
                id = "voucher-other",
                firmId = Fixture.OTHER_FIRM_ID,
                number = "OTH-0001",
                date = "2026-05-15",
            ).copy(financialYearId = "fy-other"),
        )

        assertEquals(
            listOf(Fixture.PAYMENT_VOUCHER_ID, Fixture.VOUCHER_ID),
            db.voucherDao()
                .observeByFirmAndDateRange(Fixture.FIRM_ID, "2026-05-01", "2026-05-31")
                .firstValue()
                .map { it.id },
        )
        assertEquals(
            listOf("voucher-other"),
            db.voucherDao()
                .observeByFirmAndDateRange(Fixture.OTHER_FIRM_ID, "2026-05-01", "2026-05-31")
                .firstValue()
                .map { it.id },
        )
    }

    @Test
    fun voucherNumberSequenceIncrements(): Unit = runBlocking {
        db.seedBaseline()
        assertEquals(1, db.voucherNumberSequenceDao().get(Fixture.FIRM_ID, "sale", Fixture.FINANCIAL_YEAR_ID)?.nextNumber)

        val allocated = (1..5).map {
            db.voucherNumberSequenceDao().increment(
                Fixture.FIRM_ID,
                "sale",
                Fixture.FINANCIAL_YEAR_ID,
                Fixture.UPDATED_AT,
            )
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), allocated)
        assertEquals(
            6,
            db.voucherNumberSequenceDao().get(Fixture.FIRM_ID, "sale", Fixture.FINANCIAL_YEAR_ID)?.nextNumber,
        )

        db.voucherNumberSequenceDao().upsert(
            sampleSequence(nextNumber = 10, prefix = "BILL"),
        )
        assertEquals(
            10,
            db.voucherNumberSequenceDao().increment(
                Fixture.FIRM_ID,
                "sale",
                Fixture.FINANCIAL_YEAR_ID,
                Fixture.UPDATED_AT,
            ),
        )
        val sequence = assertNotNull(
            db.voucherNumberSequenceDao().get(Fixture.FIRM_ID, "sale", Fixture.FINANCIAL_YEAR_ID),
        )
        assertEquals(11, sequence.nextNumber)
        assertEquals("BILL", sequence.prefix)

        val failure = runCatching {
            db.voucherNumberSequenceDao().increment(
                Fixture.FIRM_ID,
                "purchase",
                Fixture.FINANCIAL_YEAR_ID,
                Fixture.UPDATED_AT,
            )
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun paymentAllocationDaoTotalsAndLists(): Unit = runBlocking {
        db.seedBaseline()
        assertEquals(
            listOf("allocation-1"),
            db.paymentAllocationDao().allocationsForPayment(Fixture.PAYMENT_VOUCHER_ID).map { it.id },
        )
        assertEquals(
            listOf("allocation-1"),
            db.paymentAllocationDao().allocationsForInvoice(Fixture.VOUCHER_ID).map { it.id },
        )
        assertEquals(2_500, db.paymentAllocationDao().totalAllocatedForInvoice(Fixture.VOUCHER_ID))

        db.voucherDao().insert(
            sampleVoucher(
                id = "voucher-payment-2",
                type = "payment",
                number = "PAY-0002",
                date = "2026-05-13",
                status = "posted",
                total = 1_500,
                postedAt = Fixture.UPDATED_AT,
            ),
        )
        db.paymentAllocationDao().insertAll(
            listOf(sampleAllocation(id = "allocation-2", amount = 1_500).copy(
                paymentVoucherId = "voucher-payment-2",
            )),
        )
        assertEquals(
            listOf("allocation-1", "allocation-2"),
            db.paymentAllocationDao().allocationsForInvoice(Fixture.VOUCHER_ID).map { it.id },
        )
        assertEquals(4_000, db.paymentAllocationDao().totalAllocatedForInvoice(Fixture.VOUCHER_ID))
        assertEquals(
            listOf("allocation-2"),
            db.paymentAllocationDao().allocationsForPayment("voucher-payment-2").map { it.id },
        )
        assertEquals(0, db.paymentAllocationDao().totalAllocatedForInvoice("missing-invoice"))
    }

    @Test
    fun paymentDetailsDaoUpsertsFindsAndDeletesOnlyDrafts(): Unit = runBlocking {
        db.seedBaseline()
        db.paymentDetailsDao().upsert(samplePaymentDetails(reference = "REF-1"))

        assertEquals(
            "REF-1",
            db.paymentDetailsDao().findForVoucher(Fixture.PAYMENT_VOUCHER_ID)?.reference,
        )
        assertEquals(
            "REF-1",
            db.paymentDetailsDao()
                .observeForVoucher(Fixture.PAYMENT_VOUCHER_ID)
                .firstValue()
                ?.reference,
        )

        db.paymentDetailsDao().upsert(
            samplePaymentDetails(reference = "REF-2").copy(updatedAt = Fixture.UPDATED_AT + 1),
        )
        assertEquals(
            "REF-2",
            db.paymentDetailsDao().findForVoucher(Fixture.PAYMENT_VOUCHER_ID)?.reference,
        )
        assertEquals(
            0,
            db.paymentDetailsDao().deleteForDraftVoucher(Fixture.PAYMENT_VOUCHER_ID),
        )
        assertNotNull(db.paymentDetailsDao().findForVoucher(Fixture.PAYMENT_VOUCHER_ID))

        db.voucherDao().insert(
            sampleVoucher(
                id = "payment-draft",
                type = "payment",
                number = null,
                date = Fixture.PAYMENT_DATE,
                status = "draft",
                total = 1_000,
            ),
        )
        db.paymentDetailsDao().upsert(
            samplePaymentDetails(voucherId = "payment-draft", reference = "DRAFT-REF"),
        )
        assertEquals(1, db.paymentDetailsDao().deleteForDraftVoucher("payment-draft"))
        assertNull(db.paymentDetailsDao().findForVoucher("payment-draft"))
    }

    @Test
    fun paymentAllocationDaoEffectiveTotalsAndOutstanding(): Unit = runBlocking {
        db.seedBaseline()
        db.voucherDao().insert(
            sampleVoucher(
                id = "sale-posted",
                number = "INV-POSTED",
                status = "posted",
                total = 10_000,
                postedAt = Fixture.UPDATED_AT,
            ),
        )
        db.paymentAllocationDao().insertAll(
            listOf(
                sampleAllocation(id = "allocation-posted", amount = 2_500).copy(
                    invoiceVoucherId = "sale-posted",
                ),
            ),
        )

        assertEquals(
            2_500,
            db.paymentAllocationDao().effectiveTotalAllocatedForInvoice("sale-posted"),
        )
        assertEquals(
            2_500,
            db.paymentAllocationDao()
                .observeEffectiveTotalAllocatedForInvoice("sale-posted")
                .firstValue(),
        )
        assertEquals(
            5_000,
            db.paymentAllocationDao().totalAllocatedForPayment(Fixture.PAYMENT_VOUCHER_ID),
        )
        assertEquals(
            5_000,
            db.paymentAllocationDao()
                .observeTotalAllocatedForPayment(Fixture.PAYMENT_VOUCHER_ID)
                .firstValue(),
        )

        val outstanding = db.paymentAllocationDao()
            .outstandingInvoices(Fixture.FIRM_ID, Fixture.PARTY_ID, "sale")
        assertEquals(listOf("sale-posted"), outstanding.map { it.voucherId })
        assertEquals(2_500, outstanding.single().allocated)
        assertEquals(10_000, outstanding.single().total)

        db.voucherDao().insert(
            sampleVoucher(
                id = "voucher-payment-2",
                type = "payment",
                number = "PAY-0002",
                date = Fixture.PAYMENT_DATE,
                status = "posted",
                total = 7_500,
                postedAt = Fixture.UPDATED_AT,
            ),
        )
        db.paymentAllocationDao().insertAll(
            listOf(
                sampleAllocation(id = "allocation-final", amount = 7_500).copy(
                    paymentVoucherId = "voucher-payment-2",
                    invoiceVoucherId = "sale-posted",
                ),
            ),
        )
        assertTrue(
            db.paymentAllocationDao()
                .outstandingInvoices(Fixture.FIRM_ID, Fixture.PARTY_ID, "sale")
                .isEmpty(),
        )

        db.voucherDao().update(
            sampleVoucher(
                id = Fixture.PAYMENT_VOUCHER_ID,
                type = "payment",
                number = "PAY-0001",
                date = Fixture.PAYMENT_DATE,
                status = "cancelled",
                total = 2_500,
                postedAt = Fixture.UPDATED_AT,
            ).copy(
                cancellationReason = "Bounced",
                cancelledAt = Fixture.DELETED_AT,
            ),
        )
        assertEquals(
            7_500,
            db.paymentAllocationDao().effectiveTotalAllocatedForInvoice("sale-posted"),
        )
        assertEquals(
            5_000,
            db.paymentAllocationDao().totalAllocatedForPayment(Fixture.PAYMENT_VOUCHER_ID),
        )
        assertEquals(
            listOf("sale-posted"),
            db.paymentAllocationDao()
                .outstandingInvoices(Fixture.FIRM_ID, Fixture.PARTY_ID, "sale")
                .map { it.voucherId },
        )
    }

    @Test
    fun stockMovementDaoBalancesListsAndDraftDeletion(): Unit = runBlocking {
        db.seedBaseline()

        assertEquals(
            listOf("movement-1", "movement-2"),
            db.stockMovementDao().movementsForVoucher(Fixture.VOUCHER_ID).map { it.id },
        )
        assertEquals(
            listOf("movement-2", "movement-1"),
            db.stockMovementDao()
                .movementsForItem(Fixture.FIRM_ID, Fixture.ITEM_ID)
                .map { it.id },
        )
        assertEquals(
            3,
            db.stockMovementDao().balanceForItemGodown(Fixture.FIRM_ID, Fixture.ITEM_ID, Fixture.GODOWN_ID),
        )

        assertEquals(2, db.stockMovementDao().deleteForDraftVoucher(Fixture.VOUCHER_ID))
        assertTrue(db.stockMovementDao().movementsForVoucher(Fixture.VOUCHER_ID).isEmpty())

        db.stockMovementDao().insertAll(
            listOf(
                sampleStockMovement(id = "movement-3", voucherLineId = null, quantity = 4),
            ),
        )
        db.voucherDao().updateStatus(Fixture.VOUCHER_ID, "posted", Fixture.UPDATED_AT)
        db.stockMovementDao().insertAll(
            listOf(
                sampleStockMovement(
                    id = "movement-4",
                    voucherLineId = null,
                    date = "2026-05-12",
                    quantity = 1,
                ),
            ),
        )
        assertEquals(0, db.stockMovementDao().deleteForDraftVoucher(Fixture.VOUCHER_ID))
        assertEquals(2, db.stockMovementDao().movementsForVoucher(Fixture.VOUCHER_ID).size)
    }

    @Test
    fun ledgerDaoAccountsEntriesAndTotals(): Unit = runBlocking {
        db.seedBaseline()
        db.ledgerDao().insertAccount(
            sampleAccount(
                id = "account-archived",
                name = "Archived Cash",
                deletedAt = Fixture.DELETED_AT,
            ),
        )

        assertEquals(
            listOf(Fixture.CASH_ACCOUNT_ID, Fixture.SALES_ACCOUNT_ID),
            db.ledgerDao().observeAccounts(Fixture.FIRM_ID).firstValue().map { it.id },
        )
        assertEquals(
            "Cash",
            db.ledgerDao().findAccountById(Fixture.CASH_ACCOUNT_ID)?.name,
        )
        assertEquals(
            listOf("journal-1", "journal-2"),
            db.ledgerDao().entriesForVoucher(Fixture.VOUCHER_ID).map { it.id },
        )

        val cash = db.ledgerDao().totalsForAccount(Fixture.CASH_ACCOUNT_ID)
        assertEquals(23_600, cash.debit)
        assertEquals(0, cash.credit)
        val sales = db.ledgerDao().totalsForAccount(Fixture.SALES_ACCOUNT_ID)
        assertEquals(0, sales.debit)
        assertEquals(23_600, sales.credit)

        val inRange = db.ledgerDao().totalsForAccountInRange(
            Fixture.CASH_ACCOUNT_ID,
            Fixture.INVOICE_DATE,
            Fixture.INVOICE_DATE,
        )
        assertEquals(23_600, inRange.debit)
        val emptyRange = db.ledgerDao().totalsForAccountInRange(
            Fixture.CASH_ACCOUNT_ID,
            "2026-06-01",
            "2026-06-30",
        )
        assertEquals(0, emptyRange.debit)
        assertEquals(0, emptyRange.credit)
    }

    @Test
    fun auditAndAttachmentDaosFilterAndOrder(): Unit = runBlocking {
        db.seedBaseline()
        db.auditDao().insert(
            sampleAuditEvent(
                id = "audit-2",
                action = "posted",
                createdAt = Fixture.UPDATED_AT,
            ),
        )
        db.firmDao().insert(sampleFirm(id = Fixture.OTHER_FIRM_ID, name = "Other Firm"))
        db.auditDao().insert(
            sampleAuditEvent(
                id = "audit-other",
                firmId = Fixture.OTHER_FIRM_ID,
                entityId = "voucher-other",
                createdAt = Fixture.UPDATED_AT + 1,
            ),
        )
        assertEquals(
            listOf("audit-2", Fixture.AUDIT_ID),
            db.auditDao().observeForEntity(Fixture.FIRM_ID, "voucher", Fixture.VOUCHER_ID)
                .firstValue()
                .map { it.id },
        )
        assertEquals(
            listOf("audit-2"),
            db.auditDao().observeRecent(Fixture.FIRM_ID, 1).firstValue().map { it.id },
        )

        db.attachmentDao().insert(
            sampleAttachment(id = "attachment-2").copy(
                sync = testSync(createdAt = Fixture.UPDATED_AT, updatedAt = Fixture.UPDATED_AT),
            ),
        )
        db.attachmentDao().insert(
            sampleAttachment(id = "attachment-3", deletedAt = Fixture.DELETED_AT),
        )
        assertEquals(
            listOf("attachment-2", Fixture.ATTACHMENT_ID),
            db.attachmentDao().observeForEntity(Fixture.FIRM_ID, "voucher", Fixture.VOUCHER_ID)
                .firstValue()
                .map { it.id },
        )
        assertEquals(
            1,
            db.attachmentDao().softDelete(Fixture.ATTACHMENT_ID, Fixture.DELETED_AT, Fixture.DELETED_AT),
        )
        assertEquals(
            listOf("attachment-2"),
            db.attachmentDao().observeForEntity(Fixture.FIRM_ID, "voucher", Fixture.VOUCHER_ID)
                .firstValue()
                .map { it.id },
        )
        assertNotNull(db.attachmentDao().findById(Fixture.ATTACHMENT_ID))
    }
}
