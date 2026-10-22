package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomRecycleBinTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var vouchers: RoomVoucherRepository
    private lateinit var firms: RoomFirmRepository
    private lateinit var parties: RoomPartyRepository

    private val deletedFirstAt = Instant.parse("2026-05-15T10:00:00Z")
    private val deletedSecondAt = Instant.parse("2026-05-16T10:00:00Z")
    private val restoredAt = Instant.parse("2026-05-17T10:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val runner = DatabaseTransactionRunner(db)
        vouchers = RoomVoucherRepository(db, runner)
        firms = RoomFirmRepository(db, runner)
        parties = RoomPartyRepository(db, runner)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun softDeleteRestoreAndPurgeDraft(): Unit = runBlocking {
        seedMasterData()
        val first = draft("draft-1", "line-1", "charge-1")
        val second = draft("draft-2", "line-2", "charge-2")
        vouchers.saveDraft(first)
        vouchers.saveDraft(second)
        assertEquals(setOf("draft-1", "draft-2"), activeIds().toSet())

        val deleted = vouchers.softDeleteDraft(first.id, deletedFirstAt)
        assertEquals(deletedFirstAt, deleted.deletedAt)
        assertEquals(deletedFirstAt, deleted.updatedAt)
        assertEquals(listOf("draft-2"), activeIds())
        assertEquals(listOf("draft-1"), recycleIds())
        assertEquals(deleted, vouchers.findVoucher(first.id))
        val deletedEntity = assertNotNull(db.voucherDao().findById(first.id))
        assertEquals(deletedFirstAt.toEpochMilliseconds(), deletedEntity.sync.deletedAt)
        assertEquals(2L, deletedEntity.sync.revision)
        assertEquals(
            listOf("voucher.draft_deleted"),
            auditEvents(first.id).map { it.action },
        )
        assertTrue(
            assertNotNull(auditEvents(first.id).single().detailsJson)
                .contains("\"permanent\":false"),
        )

        assertFailsWith<IllegalArgumentException> {
            vouchers.softDeleteDraft(first.id, deletedSecondAt)
        }

        vouchers.softDeleteDraft(second.id, deletedSecondAt)
        assertEquals(listOf("draft-2", "draft-1"), recycleIds())

        val restored = vouchers.restoreDraft(first.id, restoredAt)
        assertNull(restored.deletedAt)
        assertEquals(restoredAt, restored.updatedAt)
        assertEquals(listOf("draft-1"), activeIds())
        assertEquals(listOf("draft-2"), recycleIds())
        val restoredEntity = assertNotNull(db.voucherDao().findById(first.id))
        assertNull(restoredEntity.sync.deletedAt)
        assertEquals(3L, restoredEntity.sync.revision)
        assertEquals(
            listOf("voucher.draft_restored", "voucher.draft_deleted"),
            auditEvents(first.id).map { it.action },
        )

        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreDraft(first.id, restoredAt)
        }

        vouchers.purgeDraft(second.id)
        assertNull(vouchers.findVoucher(second.id))
        assertEquals(0, db.voucherDao().lineCount(second.id))
        assertTrue(db.voucherDao().chargesFor(second.id).isEmpty())
        assertTrue(recycleIds().isEmpty())
        assertEquals(
            setOf("voucher.purged", "voucher.draft_deleted"),
            auditEvents(second.id).map { it.action }.toSet(),
        )
        assertFailsWith<IllegalArgumentException> {
            vouchers.purgeDraft(second.id)
        }
    }

    @Test
    fun postedAndCancelledVouchersCannotBeRecycledOrPurged(): Unit = runBlocking {
        seedMasterData()
        val posted = draft("posted-1", "posted-line-1", "posted-charge-1")
        vouchers.saveDraft(posted)
        vouchers.post(posted.id, postedAt)

        assertFailsWith<IllegalArgumentException> {
            vouchers.softDeleteDraft(posted.id, deletedFirstAt)
        }
        assertFailsWith<IllegalArgumentException> {
            vouchers.purgeDraft(posted.id)
        }
        assertFailsWith<IllegalArgumentException> {
            vouchers.restoreDraft(posted.id, deletedFirstAt)
        }
        assertEquals(VoucherStatus.POSTED, vouchers.findVoucher(posted.id)?.status)
        assertTrue(recycleIds().isEmpty())

        vouchers.cancel(posted.id, "Voided", deletedFirstAt)
        assertFailsWith<IllegalArgumentException> {
            vouchers.softDeleteDraft(posted.id, deletedSecondAt)
        }
        assertFailsWith<IllegalArgumentException> {
            vouchers.purgeDraft(posted.id)
        }
        assertEquals(VoucherStatus.CANCELLED, vouchers.findVoucher(posted.id)?.status)
        assertTrue(recycleIds().isEmpty())

        val activeDraft = draft("draft-active", "active-line", "active-charge")
        vouchers.saveDraft(activeDraft)
        assertFailsWith<IllegalArgumentException> {
            vouchers.purgeDraft(activeDraft.id)
        }
        vouchers.deleteDraft(activeDraft.id)
        assertNull(vouchers.findVoucher(activeDraft.id))
        val permanent = auditEvents(activeDraft.id).single()
        assertEquals("voucher.draft_deleted", permanent.action)
        assertTrue(assertNotNull(permanent.detailsJson).contains("\"permanent\":true"))
    }

    @Test
    fun purgingPaymentDraftRemovesPaymentDetails(): Unit = runBlocking {
        seedMasterData()
        val payment = testPaymentVoucher(id = "payment-draft")
        vouchers.saveDraft(payment)
        val ledger = RoomLedgerRepository(db, DatabaseTransactionRunner(db))
        val cash = ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        db.paymentDetailsDao().upsert(
            testPaymentDetails(payment.id, cash.id).toEntity(),
        )
        assertNotNull(db.paymentDetailsDao().findForVoucher(payment.id))

        vouchers.softDeleteDraft(payment.id, deletedFirstAt)
        vouchers.purgeDraft(payment.id)

        assertNull(db.paymentDetailsDao().findForVoucher(payment.id))
        assertNull(vouchers.findVoucher(payment.id))
        assertTrue(
            auditEvents(payment.id).any {
                it.action == "voucher.purged" &&
                    it.detailsJson?.contains("\"permanent\":true") == true
            },
        )
    }

    @Test
    fun recycleBinIsScopedToFirmAndOrdersByDeletionTime(): Unit = runBlocking {
        seedMasterData()
        firms.saveFirm(testFirm(id = "firm-2", name = "Other Firm"))
        val first = draft("firm-1-a", "line-1-a", "charge-1-a")
        val second = draft("firm-1-b", "line-1-b", "charge-1-b")
        val other = draft("firm-2-a", "line-2-a", "charge-2-a", firmId = "firm-2")
        vouchers.saveDraft(first)
        vouchers.saveDraft(second)
        vouchers.saveDraft(other)

        vouchers.softDeleteDraft(first.id, deletedFirstAt)
        vouchers.softDeleteDraft(second.id, deletedSecondAt)
        vouchers.softDeleteDraft(other.id, restoredAt)

        assertEquals(listOf("firm-1-b", "firm-1-a"), recycleIds())
        assertEquals(
            listOf("firm-2-a"),
            vouchers.observeRecycleBin("firm-2").first().map { it.id },
        )
        assertTrue(activeIds("firm-2").isEmpty())
    }

    private suspend fun seedMasterData() {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings())
        firms.saveFinancialYear(testFinancialYear())
        parties.saveParty(testParty())
    }

    private suspend fun activeIds(firmId: String = "firm-1"): List<String> =
        vouchers.observeVouchers(
            firmId,
            LocalDate(2026, 1, 1),
            LocalDate(2026, 12, 31),
        ).first().map { it.id }

    private suspend fun recycleIds(): List<String> =
        vouchers.observeRecycleBin("firm-1").first().map { it.id }

    private suspend fun auditEvents(voucherId: String): List<AuditEventEntity> =
        db.auditDao().observeForEntity("firm-1", "voucher", voucherId).first()

    private fun draft(
        id: String,
        lineId: String,
        chargeId: String,
        firmId: String = "firm-1",
    ): Voucher = testVoucher(
        id = id,
        firmId = firmId,
        lines = listOf(testLine(id = lineId)),
        charges = listOf(testCharge(id = chargeId)),
    )
}
