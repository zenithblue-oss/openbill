package app.openbill.core.data

import androidx.room.Room
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.Role
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Money
import app.openbill.core.sync.SyncOperation
import app.openbill.core.sync.SyncOutbox
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive

/** Every repository write records its ops atomically through the sync triggers (docs/10-server.md 6.2). */
class SyncOutboxTest {
    private val opened = mutableListOf<OpenBillDatabase>()

    private fun open(): Triple<OpenBillDatabase, Repositories, SyncOutbox> {
        val db = openBillDatabase(Room.inMemoryDatabaseBuilder<OpenBillDatabase>()).also { opened += it }
        return Triple(db, db.roomRepositories(), SyncOutbox(db))
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
    }

    /** Ops recorded by [block] (the outbox is acknowledged first), as (entity, kind, action). */
    private suspend fun SyncOutbox.recorded(block: suspend () -> Unit): List<SyncOperation> {
        pending(Int.MAX_VALUE).lastOrNull()?.let { acknowledge(it.seq) }
        block()
        return pending(Int.MAX_VALUE)
    }

    private fun List<SyncOperation>.shape() = map { Triple(it.entity, it.kind.code, it.action) }

    private suspend fun seed(repos: Repositories) {
        repos.firms.saveFirm(testFirm())
        repos.firms.saveSettings(testSettings(defaultGodownId = null))
        repos.firms.saveFinancialYear(testFinancialYear())
        repos.parties.saveParty(testParty())
    }

    private fun sale(id: String, total: Long = 10_000L) = testVoucher(
        id = id,
        lines = listOf(testLine(id = "line-$id", rate = Money.of(total)).copy(taxable = Money.of(total), tax = Money.ZERO, total = Money.of(total))),
        charges = emptyList(),
    ).copy(subtotal = Money.of(total), taxable = Money.of(total), tax = Money.ZERO, roundOff = Money.ZERO, total = Money.of(total))

    @Test
    fun standaloneInstallRecordsNothing(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        assertFalse(outbox.isEnabled())
        assertEquals(26, outbox.deviceId().length)
        seed(repos)
        assertEquals(0L, outbox.size())
    }

    @Test
    fun masterWritesRecordOneGroupPerWriteWithDeviceUserAndClock(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        val firm = outbox.recorded { repos.firms.saveFirm(testFirm()) }
        assertEquals(listOf(Triple("audit_events", "upsert", "firm.save"), Triple("firms", "upsert", "firm.save")), firm.shape().sortedBy { it.first })
        assertEquals(1, firm.map { it.groupId }.distinct().size)
        assertTrue(firm.all { it.firmId == "firm-1" && it.deviceId == outbox.deviceId() && it.userId == Access.OWNER_ID && it.schemaVersion == 20 })
        val row = firm.single { it.entity == "firms" }
        assertEquals("firm-1", row.entityId)
        assertEquals("OpenBill Traders", row.payload.getValue("name").jsonPrimitive.content)
        assertEquals(row.payload.keys.sorted(), row.payload.keys.toList(), "payload keys in name order")

        repos.firms.saveSettings(testSettings(defaultGodownId = null))
        repos.firms.saveFinancialYear(testFinancialYear())
        val party = outbox.recorded { repos.parties.saveParty(testParty()) }
        assertEquals(setOf("parties", "party_addresses", "audit_events"), party.map { it.entity }.toSet())
        assertEquals(2, party.count { it.entity == "party_addresses" && it.kind == SyncOperation.Kind.UPSERT })
        assertTrue(party.all { it.action == "party.save" && it.firmId == "firm-1" })

        val edit = outbox.recorded { repos.parties.saveParty(testParty(name = "Acme Renamed")) }
        val update = edit.single { it.entity == "parties" }
        assertEquals(1L, update.baseRevision, "an edit carries the revision it was based on")
        assertEquals(2L, update.payload.getValue("revision").jsonPrimitive.content.toLong())
        // Addresses are replaced: two deletes (key only) and two inserts.
        assertEquals(listOf("delete", "delete", "upsert", "upsert"), edit.filter { it.entity == "party_addresses" }.map { it.kind.code })
        assertEquals(setOf("id"), edit.first { it.kind == SyncOperation.Kind.DELETE }.payload.keys)
        assertTrue(edit.zipWithNext().all { (a, b) -> a.seq < b.seq && a.hlc <= b.hlc })
    }

    @Test
    fun invoiceLifecycleAndPaymentRecordDomainActions(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        // Drafts stay on the device (docs/10-server.md section 18).
        val draft = outbox.recorded { repos.vouchers.saveDraft(sale("sale-1")) }
        assertEquals(emptyList(), draft)

        val post = outbox.recorded { repos.vouchers.post("sale-1", POSTED_AT) }
        assertTrue(post.all { it.action == "voucher.post" } && post.map { it.groupId }.distinct().size == 1)
        val posted = post.last { it.entity == "vouchers" }
        assertEquals(VoucherStatus.POSTED.name.lowercase(), posted.payload.getValue("status").jsonPrimitive.content.lowercase())
        assertEquals("INV-A-0001", posted.payload.getValue("number").jsonPrimitive.content, "numbered in the device's series")
        assertFalse("fulfilment" in posted.payload.keys, "derived cache stays out of ops")
        // The post carries the whole document: its lines are touched after the voucher row.
        assertTrue(post.indexOfFirst { it.entity == "voucher_lines" && it.entityId == "line-sale-1" } > post.indexOf(posted))
        assertTrue(post.any { it.entity == "journal_entries" })
        assertTrue(post.none { it.entity == "voucher_number_sequences" }, "number counters are per device")

        val cancel = outbox.recorded { repos.vouchers.cancel("sale-1", "Wrong rate", CANCELLED_AT) }
        assertTrue(cancel.all { it.action == "voucher.cancel" })
        assertTrue(cancel.any { it.entity == "vouchers" && it.payload.getValue("cancellationReason").jsonPrimitive.content == "Wrong rate" })
        val restore = outbox.recorded { repos.vouchers.restoreCancelled("sale-1", "Wrong rate", "Rate was right", CANCELLED_AT) }
        assertTrue(restore.isNotEmpty() && restore.all { it.action == "voucher.restore" })

        repos.vouchers.saveDraft(testPaymentVoucher(total = 4_000L))
        val cash = repos.ledger.ensureSystemAccounts("firm-1", CREATED_AT).cash
        repos.payments.savePaymentDetails(testPaymentDetails("payment-1", cash.id))
        repos.vouchers.post("payment-1", POSTED_AT)
        val allocation = outbox.recorded { repos.payments.allocate("payment-1", listOf(AllocationRequest("sale-1", Money.of(4_000L))), POSTED_AT) }
        assertEquals("allocation.add", allocation.single { it.entity == "payment_allocations" }.action)

        repos.vouchers.saveDraft(sale("sale-2"))
        val purge = outbox.recorded {
            repos.vouchers.softDeleteDraft("sale-2", POSTED_AT)
            repos.vouchers.purgeDraft("sale-2")
        }
        assertEquals(emptyList(), purge, "trashing and purging a draft is local")
    }

    @Test
    fun cacheOnlyUpdatesAndStartupSeedingRecordNothingAndRawWritesAreMarked(): Unit = runBlocking {
        val (db, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        repos.vouchers.saveDraft(sale("sale-1"))
        repos.vouchers.post("sale-1", POSTED_AT)
        val fulfilment = outbox.recorded { db.voucherDao().setFulfilment("sale-1", "partial") }
        assertEquals(emptyList(), fulfilment)
        // A write outside a repository (import, maintenance SQL) is recorded with an explicit action.
        val raw = outbox.recorded { db.partyDao().findById("party-1")!!.let { db.partyDao().update(it.copy(phone = "99")) } }
        assertEquals(listOf("unattributed"), raw.map { it.action }.distinct())
    }

    @Test
    fun writesInOneOuterTransactionShareOneGroup(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        val ops = outbox.recorded {
            repos.transaction {
                repos.parties.saveParty(testParty("p-2", addresses = emptyList()))
                repos.users.saveUser(AppUser("user-asha", "Asha", Role.ACCOUNTANT, pinHash = "h", createdAt = CREATED_AT))
            }
            repos.parties.saveParty(testParty("p-3", addresses = emptyList()))
        }
        val (inside, after) = ops.partition { it.entityId != "p-3" && !(it.entity == "audit_events" && it.payload["entityId"]?.jsonPrimitive?.content == "p-3") }
        assertEquals(1, inside.map { it.groupId }.distinct().size)
        assertEquals(setOf("party.save", "user.save"), inside.mapNotNull { it.action }.toSet())
        assertTrue(after.isNotEmpty() && after.none { it.groupId == inside.first().groupId })
    }

    @Test
    fun failedWritesLeaveNoOps(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        val failed = outbox.recorded {
            assertFailsWith<IllegalStateException> {
                repos.transaction {
                    repos.parties.saveParty(testParty("p-2", addresses = emptyList()))
                    error("boom")
                }
            }
            assertFailsWith<IllegalArgumentException> { repos.vouchers.post("no-such-voucher", POSTED_AT) }
        }
        assertEquals(emptyList(), failed)
    }

    @Test
    fun userPinHashNeverLeavesTheDeviceAndUserIsStamped(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        repos.firms.saveFirm(testFirm())
        val user = AppUser("user-asha", "Asha", Role.ACCOUNTANT, pinHash = "secret-hash", createdAt = CREATED_AT)
        val saved = outbox.recorded { repos.users.saveUser(user) }.single()
        assertEquals(null, saved.firmId)
        assertFalse("pinHash" in saved.payload.keys)
        assertEquals("Asha", saved.payload.getValue("name").jsonPrimitive.content)
        try {
            Access.current = user
            val asAsha = outbox.recorded { repos.firms.saveFinancialYear(testFinancialYear()) }
            assertEquals(listOf("user-asha"), asAsha.map { it.userId }.distinct())
        } finally {
            Access.current = null
        }
    }

    @Test
    fun erasingAFirmLeavesNoCopyOfItsRowsInTheOutbox(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        repos.firms.saveFirm(testFirm(id = "firm-2", name = "Kept"))
        repos.vouchers.saveDraft(sale("sale-1"))
        repos.firms.eraseFirm("firm-1")
        val left = outbox.pending(Int.MAX_VALUE)
        assertTrue(left.isNotEmpty())
        assertTrue(left.all { it.firmId == "firm-2" }, left.map { it.entity to it.firmId }.toString())
    }

    @Test
    fun disablingDropsUnsentOpsAndStopsRecording(): Unit = runBlocking {
        val (_, repos, outbox) = open()
        outbox.enable("A")
        seed(repos)
        assertTrue(outbox.size() > 0)
        outbox.disable()
        assertEquals(0L, outbox.size())
        repos.parties.saveParty(testParty("p-3", addresses = emptyList()))
        assertEquals(0L, outbox.size())
    }

    @Test
    fun sameWritesGiveTheSameOpsOnAnyDeviceAndJsonRoundTrips(): Unit = runBlocking {
        suspend fun run(): List<SyncOperation> {
            val (_, repos, outbox) = open()
            outbox.enable("A")
            seed(repos)
            repos.vouchers.saveDraft(sale("sale-1"))
            repos.vouchers.post("sale-1", POSTED_AT)
            return outbox.pending(Int.MAX_VALUE)
        }
        val a = run()
        val b = run()
        assertTrue(a.first().deviceId != b.first().deviceId)
        // Rows stamped with the wall clock (audit ids and times, system accounts) differ; everything else is identical.
        fun stable(ops: List<SyncOperation>) = ops.filter { it.entity in setOf("firms", "parties", "party_addresses", "vouchers", "voucher_lines", "firm_settings", "financial_years") }
            .map { listOf(it.entity, it.entityId, it.kind.code, it.action, it.baseRevision, it.payload.filterKeys { k -> k != "updatedAt" && k != "createdAt" && k != "postedAt" }) }
        assertEquals(stable(a), stable(b))
        a.forEach { op ->
            val json = op.toJson()
            assertEquals(op, SyncOperation.fromJson(json))
            assertEquals(json, SyncOperation.fromJson(json).toJson(), "canonical JSON is stable")
        }
    }
}
