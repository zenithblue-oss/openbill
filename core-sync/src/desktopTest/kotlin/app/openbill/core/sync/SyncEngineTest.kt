package app.openbill.core.sync

import app.openbill.core.domain.model.PartyItemPrice
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Two and three devices through the engine and the in-process server, one conflict rule at a time (docs/10-server.md 6.3-6.5). */
class SyncEngineTest {
    private val root: File = Files.createTempDirectory("openbill-sync").toFile()
    private val server = InMemorySyncServer(File(root, "server"))
    private val devices = mutableListOf<SimDevice>()

    @AfterTest
    fun tearDown() = runBlocking {
        devices.forEach { runCatching { it.close() } }
        server.close()
        root.deleteRecursively()
        Unit
    }

    private suspend fun workspace(count: Int): List<SimDevice> {
        val a = SimDevice("A", File(root, "a"), "A", server, 1).also { devices += it }
        a.open()
        seedFirm(a.repos)
        val snapshot = File(root, "seed.db")
        SyncSnapshots.vacuumInto(a.file, snapshot)
        server.createWorkspace(WS, snapshot.readBytes())
        SyncOutbox(a.db).enable("A")
        return listOf(a) + (1 until count).map { i ->
            SimDevice("D$i", File(root, "d$i"), ('A' + i).toString(), server, 10L + i).also { devices += it; it.join() }
        }
    }

    private suspend fun syncAll(all: List<SimDevice>) = repeat(3) { all.forEach { it.sync() } }

    private suspend fun assertConverged(all: List<SimDevice>) {
        val expected = server.database(WS).canonicalDump(BlobRefs.None)
        all.forEach { d -> assertEquals("", dumpDiff(expected, d.db.canonicalDump(d.blobs)), "device ${d.name} matches the server") }
    }

    @Test
    fun postsDraftsAndNumbersStayConsistentAcrossDevices(): Unit = runBlocking {
        val (a, b) = workspace(2)
        a.repos.vouchers.saveDraft(bill("s-a", VoucherType.SALE, "cust-1", a.today(), a.now(), listOf(Triple("item-widget", 2L, 1_000L))))
        b.repos.vouchers.saveDraft(bill("s-b", VoucherType.SALE, "cust-1", b.today(), b.now(), listOf(Triple("item-bolt", 1L, 2_000L))))
        a.repos.vouchers.post("s-a", a.now())
        b.repos.vouchers.post("s-b", b.now())
        b.repos.vouchers.saveDraft(bill("draft-b", VoucherType.SALE, "cust-2", b.today(), b.now(), listOf(Triple("item-bolt", 1L, 2_000L))))
        syncAll(listOf(a, b))
        assertConverged(listOf(a, b))
        assertEquals(listOf("INV-A-0001", "INV-B-0001"), server.database(WS).ids("SELECT number FROM vouchers WHERE status = 'posted' ORDER BY number"))
        assertEquals(emptyList(), a.db.ids("SELECT id FROM vouchers WHERE id = 'draft-b'"), "drafts stay on their device")
        // Both devices created the system accounts on their first post, offline from each other: one set, same ids.
        assertEquals(8, server.database(WS).ids("SELECT id FROM accounts").size)
        assertEquals(2, a.db.ids("SELECT id FROM voucher_lines WHERE voucherId IN ('s-a', 's-b')").size)
    }

    @Test
    fun concurrentCancelsKeepOneReversalAndTheLoserGetsCorrected(): Unit = runBlocking {
        val (a, b) = workspace(2)
        a.repos.vouchers.saveDraft(bill("s-1", VoucherType.SALE, "cust-1", a.today(), a.now(), listOf(Triple("item-widget", 3L, 1_000L))))
        a.repos.vouchers.post("s-1", a.now())
        syncAll(listOf(a, b))
        a.repos.vouchers.cancel("s-1", "Wrong rate", a.now())
        b.repos.vouchers.cancel("s-1", "Duplicate", b.now())
        a.sync()
        val report = b.sync()!!
        assertTrue(report.rejected > 0, "the second cancel is refused")
        syncAll(listOf(a, b))
        assertConverged(listOf(a, b))
        val db = server.database(WS)
        assertEquals(listOf("0"), db.ids("SELECT COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) FROM journal_entries WHERE voucherId = 's-1'"))
        assertEquals(listOf("Wrong rate"), db.ids("SELECT cancellationReason FROM vouchers WHERE id = 's-1'"))
        assertEquals(1, db.ids("SELECT id FROM audit_events WHERE action = 'sync.rejected'").size, "the refused cancel leaves a review event")
        // Every account nets to zero: exactly one reversal.
        assertTrue(db.ids("SELECT SUM(debit) - SUM(credit) FROM journal_entries WHERE voucherId = 's-1' GROUP BY accountId").all { it == "0" })
    }

    @Test
    fun twoRestoresAtOneClockTimeRepostOnce(): Unit = runBlocking {
        val (a, b) = workspace(2)
        a.repos.vouchers.saveDraft(bill("s-1", VoucherType.SALE, "cust-1", a.today(), a.now(), listOf(Triple("item-widget", 2L, 1_000L))))
        a.repos.vouchers.post("s-1", a.now())
        val cancelledAt = a.now()
        a.repos.vouchers.cancel("s-1", "Wrong rate", cancelledAt)
        syncAll(listOf(a, b))
        // Equal clocks write equal voucher rows; only the revision tells the second restore apart.
        a.repos.vouchers.restoreCancelled("s-1", "Wrong rate", "Cancelled by mistake", cancelledAt)
        b.repos.vouchers.restoreCancelled("s-1", "Wrong rate", "Cancelled by mistake", cancelledAt)
        syncAll(listOf(a, b))
        assertConverged(listOf(a, b))
        val db = server.database(WS)
        assertEquals(listOf("posted"), db.ids("SELECT status FROM vouchers WHERE id = 's-1'"))
        // Post, reversal, one re-post: 3 x 2360 of debits (a second re-post would make 4).
        assertEquals(listOf("7080"), db.ids("SELECT SUM(debit) FROM journal_entries WHERE voucherId = 's-1'"))
        assertEquals(1, db.ids("SELECT id FROM audit_events WHERE action = 'sync.rejected'").size)
    }

    @Test
    fun overAllocationIsDroppedButThePaymentStands(): Unit = runBlocking {
        val (a, b) = workspace(2)
        a.repos.vouchers.saveDraft(bill("s-1", VoucherType.SALE, "cust-1", a.today(), a.now(), listOf(Triple("item-widget", 1L, 1_000L))))
        a.repos.vouchers.post("s-1", a.now())
        syncAll(listOf(a, b))
        receive(a.repos, "p-a", "cust-1", 1_180L, "s-1", a.today(), a.now(), together = true)
        receive(b.repos, "p-b", "cust-1", 1_180L, "s-1", b.today(), b.now(), together = true)
        syncAll(listOf(a, b))
        assertConverged(listOf(a, b))
        val db = server.database(WS)
        assertEquals(listOf("p-a", "p-b"), db.ids("SELECT id FROM vouchers WHERE type = 'payment_in' AND status = 'posted' ORDER BY id"), "both receipts are kept")
        assertEquals(listOf("1180"), db.ids("SELECT SUM(amount) FROM payment_allocations WHERE invoiceVoucherId = 's-1'"))
    }

    @Test
    fun sameNaturalKeysOfflineMergeIntoOneRow(): Unit = runBlocking {
        val (a, b) = workspace(2)
        a.repos.parties.savePrice(FIRM, PartyItemPrice("cust-1", "item-bolt", Money.of(1_900L)))
        b.repos.parties.savePrice(FIRM, PartyItemPrice("cust-1", "item-bolt", Money.of(1_800L)))
        a.repos.vouchers.saveDraft(bill("pur-a", VoucherType.PURCHASE, "supp-1", a.today(), a.now(), listOf(Triple("item-syrup", 5L, 800L)), batch = "LOT-7"))
        b.repos.vouchers.saveDraft(bill("pur-b", VoucherType.PURCHASE, "supp-1", b.today(), b.now(), listOf(Triple("item-syrup", 4L, 800L)), batch = "LOT-7"))
        a.repos.vouchers.post("pur-a", a.now())
        b.repos.vouchers.post("pur-b", b.now())
        syncAll(listOf(a, b))
        assertConverged(listOf(a, b))
        val db = server.database(WS)
        assertEquals(1, db.ids("SELECT id FROM party_item_prices").size)
        assertEquals(1, db.ids("SELECT id FROM batches WHERE batchNumber = 'LOT-7'").size)
        assertEquals(listOf(app.openbill.core.model.Quantity.of(9).scaledUnits.toString()), db.ids("SELECT SUM(quantity) FROM stock_movements WHERE batchId IS NOT NULL"))
    }

    @Test
    fun aRestoredServerIsRefilledFromTheDevices(): Unit = runBlocking {
        val (a, b, c) = workspace(3)
        a.repos.vouchers.saveDraft(bill("s-1", VoucherType.SALE, "cust-1", a.today(), a.now(), listOf(Triple("item-widget", 1L, 1_000L))))
        a.repos.vouchers.post("s-1", a.now())
        syncAll(listOf(a, b, c))
        server.backup(WS)
        b.repos.vouchers.saveDraft(bill("s-2", VoucherType.SALE, "cust-2", b.today(), b.now(), listOf(Triple("item-bolt", 1L, 2_000L))))
        b.repos.vouchers.post("s-2", b.now())
        b.sync()
        c.sync()
        b.online = false
        server.restore(WS)
        // b is away; c pulled b's invoice before the restore and puts it back.
        c.sync()
        assertEquals(listOf("s-1", "s-2"), server.database(WS).ids("SELECT id FROM vouchers WHERE status = 'posted' ORDER BY id"))
        b.online = true
        syncAll(listOf(a, b, c))
        assertConverged(listOf(a, b, c))
    }

    @Test
    fun aCopiedDataFolderIsRefused(): Unit = runBlocking {
        val (a) = workspace(1)
        a.repos.parties.saveParty(parties(a.repos).first().copy(phone = "1"))
        a.sync()
        a.close()
        val clone = SimDevice("clone", File(root, "clone"), "A", server, 99).also { devices += it }
        a.file.copyTo(clone.file)
        a.open()
        clone.open()
        a.repos.parties.saveParty(parties(a.repos).first().copy(phone = "2"))
        clone.repos.parties.saveParty(parties(clone.repos).first().copy(phone = "3"))
        a.sync()
        assertFailsWith<SyncRefusedException> { clone.sync() }
        assertTrue(clone.client.refused != null)
    }

    @Test
    fun logoTravelsAsAContentReference(): Unit = runBlocking {
        val (a, b) = workspace(2)
        val logo = File(a.dir, "logo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        a.repos.firms.saveFirm(a.repos.firms.findFirm(FIRM)!!.copy(logoPath = logo.path))
        syncAll(listOf(a, b))
        val ref = server.database(WS).ids("SELECT logoPath FROM firms").single()
        assertTrue(ref.startsWith("blob:sha256:"), ref)
        assertEquals(listOf(ref), b.db.ids("SELECT logoPath FROM firms"), "b has no copy yet: it keeps the reference")
        assertTrue(File(a.db.ids("SELECT logoPath FROM firms").single()).readBytes().contentEquals(byteArrayOf(1, 2, 3)), "a resolves its own blob")
        assertConverged(listOf(a, b))
    }
}
