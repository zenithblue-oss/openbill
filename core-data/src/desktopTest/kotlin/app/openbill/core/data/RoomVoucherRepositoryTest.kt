package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.VoucherNumberSequenceEntity
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.model.Money
import app.openbill.core.model.Ulid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomVoucherRepositoryTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repository: RoomVoucherRepository
    private lateinit var firms: RoomFirmRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val runner = DatabaseTransactionRunner(db)
        repository = RoomVoucherRepository(db, runner)
        firms = RoomFirmRepository(db, runner)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveDraftReplacesLinesAndChargesAndObservesDateRange(): Unit = runBlocking {
        seedMasterData()
        val draft = testVoucher()
        repository.saveDraft(draft)
        assertEquals(draft, repository.findVoucher(draft.id))

        val updated = draft.copy(
            lines = listOf(testLine(id = "line-2", lineNumber = 1, rate = Money.of(9_000L))),
            charges = listOf(testCharge(id = "charge-2")),
            notes = "Updated draft",
            updatedAt = Instant.fromEpochMilliseconds(UPDATED_MILLIS + 1_000L),
        )
        repository.saveDraft(updated)

        assertEquals(updated, repository.findVoucher(draft.id))
        assertEquals(listOf("line-2"), db.voucherDao().linesFor(draft.id).map { it.id })
        assertEquals(listOf("charge-2"), db.voucherDao().chargesFor(draft.id).map { it.id })
        assertEquals(
            listOf(updated),
            repository.observeVouchers(
                firmId = "firm-1",
                fromDate = LocalDate(2026, 5, 1),
                toDate = LocalDate(2026, 5, 31),
            ).first(),
        )
        assertTrue(
            repository.observeVouchers(
                firmId = "firm-1",
                fromDate = LocalDate(2026, 6, 1),
                toDate = LocalDate(2026, 6, 30),
            ).first().isEmpty(),
        )
        assertEquals(updated, repository.observeVoucher(draft.id).first())
    }

    @Test
    fun deleteDraftRemovesDraftAndRejectsPostedVouchers(): Unit = runBlocking {
        seedMasterData()
        val draft = testVoucher()
        repository.saveDraft(draft)
        repository.deleteDraft(draft.id)
        assertNull(repository.findVoucher(draft.id))
        assertEquals(0, db.voucherDao().lineCount(draft.id))
        assertTrue(db.voucherDao().chargesFor(draft.id).isEmpty())

        val postedDraft = testVoucher(id = "voucher-2")
        repository.saveDraft(postedDraft)
        repository.post(postedDraft.id, POSTED_AT)
        val failure = runCatching { repository.deleteDraft(postedDraft.id) }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())
        assertEquals(VoucherStatus.POSTED, repository.findVoucher(postedDraft.id)?.status)

        val overwrite = runCatching { repository.saveDraft(postedDraft) }
        assertIs<IllegalArgumentException>(overwrite.exceptionOrNull())
        assertEquals(VoucherStatus.POSTED, repository.findVoucher(postedDraft.id)?.status)

        val missing = runCatching { repository.deleteDraft("missing") }
        assertIs<IllegalArgumentException>(missing.exceptionOrNull())
    }

    @Test
    fun postAllocatesNumberWritesAuditAndRejectsSecondPost(): Unit = runBlocking {
        seedMasterData()
        val draft = testVoucher()
        repository.saveDraft(draft)

        val posted = repository.post(draft.id, POSTED_AT)

        assertEquals(VoucherStatus.POSTED, posted.status)
        assertEquals("INV-0001", posted.number)
        assertEquals(POSTED_AT, posted.postedAt)
        assertEquals(POSTED_AT, posted.updatedAt)
        assertEquals(draft.lines, posted.lines)
        assertEquals(draft.charges, posted.charges)

        val entity = assertNotNull(db.voucherDao().findById(draft.id))
        assertEquals("posted", entity.status)
        assertEquals("INV-0001", entity.number)
        assertEquals(2L, entity.sync.revision)
        assertEquals(
            2L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1")?.nextNumber,
        )

        val audit = auditEvents(draft.id).single()
        assertEquals("voucher.posted", audit.action)
        assertEquals("voucher", audit.entityType)
        assertEquals(draft.id, audit.entityId)
        assertEquals(draft.id, audit.voucherId)
        assertEquals(POSTED_MILLIS, audit.createdAt)
        assertTrue(Ulid.isValid(audit.id))

        val second = runCatching { repository.post(draft.id, POSTED_AT) }
        assertIs<IllegalArgumentException>(second.exceptionOrNull())
        assertEquals(
            2L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1")?.nextNumber,
        )
        assertEquals(1, auditEvents(draft.id).size)
    }

    @Test
    fun sequencesAreScopedByFinancialYear(): Unit = runBlocking {
        seedMasterData()
        firms.saveFinancialYear(
            testFinancialYear(
                id = "fy-2",
                startDate = LocalDate(2027, 4, 1),
                endDate = LocalDate(2028, 3, 31),
            ),
        )

        repository.saveDraft(
            testVoucher(
                id = "voucher-1",
                lines = listOf(testLine(id = "line-1")),
                charges = listOf(testCharge(id = "charge-1")),
            ),
        )
        repository.saveDraft(
            testVoucher(
                id = "voucher-2",
                lines = listOf(testLine(id = "line-2")),
                charges = listOf(testCharge(id = "charge-2")),
            ),
        )
        repository.saveDraft(
            testVoucher(
                id = "voucher-3",
                financialYearId = "fy-2",
                date = LocalDate(2027, 5, 10),
                lines = listOf(testLine(id = "line-3")),
                charges = listOf(testCharge(id = "charge-3")),
            ),
        )

        val numbers = listOf("voucher-1", "voucher-2", "voucher-3").map {
            repository.post(it, POSTED_AT).number
        }

        assertEquals(listOf("INV-0001", "INV-0002", "INV-0001"), numbers)
        assertEquals(
            3L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1")?.nextNumber,
        )
        assertEquals(
            2L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-2")?.nextNumber,
        )
    }

    @Test
    fun cancelRetainsNumberWritesAuditAndRejectsDraft(): Unit = runBlocking {
        seedMasterData()
        val draft = testVoucher()
        repository.saveDraft(draft)
        repository.post(draft.id, POSTED_AT)

        val cancelled = repository.cancel(draft.id, "Customer cancelled", CANCELLED_AT)

        assertEquals(VoucherStatus.CANCELLED, cancelled.status)
        assertEquals("INV-0001", cancelled.number)
        assertEquals("Customer cancelled", cancelled.cancellationReason)
        assertEquals(CANCELLED_AT, cancelled.cancelledAt)
        assertEquals(POSTED_AT, cancelled.postedAt)
        assertEquals(CANCELLED_AT, cancelled.updatedAt)

        val entity = assertNotNull(db.voucherDao().findById(draft.id))
        assertEquals("cancelled", entity.status)
        assertEquals("INV-0001", entity.number)
        assertEquals("Customer cancelled", entity.cancellationReason)
        assertEquals(3L, entity.sync.revision)

        val audits = auditEvents(draft.id)
        assertEquals(2, audits.size)
        val cancellation = audits.single { it.action == "voucher.cancelled" }
        assertEquals(draft.id, cancellation.voucherId)
        assertEquals(CANCELLED_MILLIS, cancellation.createdAt)
        assertTrue(Ulid.isValid(cancellation.id))
        assertEquals(
            2L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1")?.nextNumber,
        )

        val draft2 = testVoucher(
            id = "voucher-2",
            lines = listOf(testLine(id = "line-2")),
            charges = listOf(testCharge(id = "charge-2")),
        )
        repository.saveDraft(draft2)
        val failure = runCatching {
            repository.cancel(draft2.id, "Not posted", CANCELLED_AT)
        }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())
        assertEquals(VoucherStatus.DRAFT, repository.findVoucher(draft2.id)?.status)
        assertTrue(auditEvents(draft2.id).isEmpty())
    }

    @Test
    fun saveDraftRollsBackVoucherAndLinesOnFailure(): Unit = runBlocking {
        seedMasterData()
        val original = testVoucher()
        repository.saveDraft(original)

        val broken = original.copy(
            lines = listOf(testLine(id = "bad-line", itemId = "missing-item")),
            charges = emptyList(),
            notes = "Should roll back",
            updatedAt = Instant.fromEpochMilliseconds(UPDATED_MILLIS + 2_000L),
        )
        val failure = runCatching { repository.saveDraft(broken) }
        assertTrue(failure.isFailure)

        assertEquals(original, repository.findVoucher(original.id))
        assertEquals(listOf("line-1"), db.voucherDao().linesFor(original.id).map { it.id })
        assertEquals(listOf("charge-1"), db.voucherDao().chargesFor(original.id).map { it.id })
    }

    @Test
    fun postRollsBackSequenceAndAuditWhenVoucherUpdateFails(): Unit = runBlocking {
        seedMasterData()
        db.voucherNumberSequenceDao().upsert(
            VoucherNumberSequenceEntity(
                firmId = "firm-1",
                documentType = "sale",
                financialYearId = "fy-1",
                nextNumber = 1L,
                prefix = "INV",
                padding = 4,
                createdAt = CREATED_MILLIS,
                updatedAt = UPDATED_MILLIS,
            ),
        )
        val conflicting = testVoucher(
            id = "voucher-conflict",
            lines = emptyList(),
            charges = emptyList(),
        ).toEntity(null).copy(
            number = "INV-0001",
            status = "posted",
            postedAt = POSTED_MILLIS,
        )
        db.voucherDao().insert(conflicting)

        val draft = testVoucher(
            id = "voucher-draft",
            lines = listOf(testLine(id = "line-draft")),
            charges = listOf(testCharge(id = "charge-draft")),
        )
        repository.saveDraft(draft)

        val failure = runCatching { repository.post(draft.id, POSTED_AT) }
        assertTrue(failure.isFailure)

        val unchanged = assertNotNull(repository.findVoucher(draft.id))
        assertEquals(VoucherStatus.DRAFT, unchanged.status)
        assertNull(unchanged.number)
        assertNull(unchanged.postedAt)
        assertEquals(
            1L,
            db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1")?.nextNumber,
        )
        assertTrue(auditEvents(draft.id).isEmpty())
    }

    private suspend fun seedMasterData(invoicePrefix: String = "INV") {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings(invoicePrefix = invoicePrefix))
        firms.saveFinancialYear(testFinancialYear())
    }

    private suspend fun auditEvents(voucherId: String): List<AuditEventEntity> =
        db.auditDao().observeForEntity("firm-1", "voucher", voucherId).first()
}
