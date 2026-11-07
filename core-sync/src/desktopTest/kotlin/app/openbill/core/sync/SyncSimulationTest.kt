package app.openbill.core.sync

import app.openbill.core.domain.model.PartyItemPrice
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Multi-device simulation (docs/10-server.md section 16): devices on real SQLite files run the
 * real repositories through a seeded random business day with offline gaps, lost and repeated
 * requests, repeated and reordered pages, clock skew, partial pages, server restarts and
 * restores; then everything syncs and the invariants are checked on every replica.
 *
 * Defaults are CI-sized. Long run: `-Popenbill.sim.seeds=500 -Popenbill.sim.steps=400`.
 * Practical check: `-Popenbill.sim.keep=<dir>` keeps one realistic day's databases for the CLI.
 */
class SyncSimulationTest {
    private val seeds = System.getProperty("openbill.sim.seeds")?.toInt() ?: 12
    private val steps = System.getProperty("openbill.sim.steps")?.toInt() ?: 150

    @Test
    fun randomBusinessDaysConvergeAndKeepTheBooksValid(): Unit = runBlocking {
        val failures = ArrayList<String>()
        var ops = 0L
        var rejected = 0L
        // -Popenbill.sim.seed=<n> replays one seed.
        val chosen = System.getProperty("openbill.sim.seed")?.toInt()?.let { it..it } ?: 1..seeds
        for (seed in chosen) {
            val root = Files.createTempDirectory("openbill-sim-$seed").toFile()
            try {
                val result = Simulation(seed.toLong(), root, steps, faults = 0.12).run()
                failures += result.failures.map { "seed $seed: $it" }
                ops += result.serverOps
                rejected += result.rejectedGroups
            } finally {
                root.deleteRecursively()
            }
        }
        println("sync simulation: $seeds seeds x $steps steps, $ops server ops, $rejected rejected groups, ${failures.size} invariant failures")
        assertEquals(emptyList(), failures.take(20))
    }

    /** A day of three counters kept on disk for `openbill db check` and reports (run only with -Popenbill.sim.keep). */
    @Test
    fun realisticDayKeptForTheCli(): Unit = runBlocking {
        val keep = System.getProperty("openbill.sim.keep") ?: return@runBlocking
        val root = File(keep).apply { deleteRecursively(); mkdirs() }
        val result = Simulation(2026L, root, steps = 240, faults = 0.08).run(close = true)
        println("realistic day: ${result.serverOps} server ops, ${result.rejectedGroups} rejected groups, databases in $root: ${result.files}")
        assertEquals(emptyList(), result.failures)
    }
}

private class Simulation(seed: Long, private val root: File, private val steps: Int, private val faults: Double) {
    class Result(val failures: List<String>, val serverOps: Long, val rejectedGroups: Long, val files: List<String>)

    private class Posted(val number: String, val total: String, val journal: Map<String, Long>, val stock: Map<String, Long>)

    private val random = Random(seed)
    private val server = InMemorySyncServer(File(root, "server"))
    private val devices = listOf("A", "B", "C").mapIndexed { i, s -> SimDevice(s, File(root, "device-$s"), s, server, seed * 31 + i) }
    private val posted = HashMap<String, Posted>()
    private val failures = ArrayList<String>()
    private var backedUp = false
    private var counter = 0

    suspend fun run(close: Boolean = true): Result {
        val (a) = devices
        a.open()
        seedFirm(a.repos)
        val seedFile = File(root, "seed.db")
        SyncSnapshots.vacuumInto(a.file, seedFile)
        server.createWorkspace(WS, seedFile.readBytes())
        SyncOutbox(a.db).enable(a.series)
        devices.drop(1).forEach { it.join() }
        devices.forEach { it.transport.faults = faults }

        for (step in 1..steps) {
            devices.forEach { it.step = step }
            val d = devices[random.nextInt(devices.size)]
            try {
                act(d)
            } catch (e: SyncTransportException) {
                // The link stayed down through every retry: the device tries again later.
            } catch (e: IllegalArgumentException) {
                // A refusal by the app's own rules on this device's view (e.g. nothing left to allocate).
            } catch (e: IllegalStateException) {
            } catch (e: Exception) {
                failures += "step $step on ${d.name}: ${e::class.simpleName}: ${e.message}"
            }
        }

        // Back online, link healthy: everything must settle.
        devices.forEach { it.online = true; it.transport.faults = 0.0 }
        repeat(6) { devices.forEach { d -> runCatching { d.sync() }.onFailure { failures += "final sync ${d.name}: ${it.message}" } } }
        check()
        val head = server.head(WS)
        val rejected = server.database(WS).ids("SELECT COUNT(*) FROM audit_events WHERE action = 'sync.rejected'").single().toLong()
        if (close) {
            devices.forEach { it.close() }
            server.close()
        }
        return Result(failures, head, rejected, devices.map { it.file.path })
    }

    private suspend fun act(d: SimDevice) {
        val roll = random.nextInt(100)
        val at = d.now()
        when {
            roll < 22 -> {
                val id = "sale-${d.name}-${++counter}"
                val lines = (1..random.nextInt(1, 4)).map { Triple(ITEMS.random(random), random.nextLong(1, 6), 100L * random.nextLong(5, 40)) }
                d.repos.vouchers.saveDraft(bill(id, VoucherType.SALE, CUSTOMERS.random(random), d.today(), at, lines, batch = "LOT-${random.nextInt(3)}"))
                d.repos.vouchers.post(id, at)
                track(d, id)
            }
            roll < 30 -> {
                val id = "pur-${d.name}-${++counter}"
                val lines = (1..random.nextInt(1, 3)).map { Triple(ITEMS.random(random), random.nextLong(5, 20), 100L * random.nextLong(5, 30)) }
                d.repos.vouchers.saveDraft(bill(id, VoucherType.PURCHASE, "supp-1", d.today(), at, lines, batch = "LOT-${random.nextInt(3)}"))
                d.repos.vouchers.post(id, at)
                track(d, id)
            }
            roll < 42 -> {
                val party = CUSTOMERS.random(random)
                val open = d.repos.payments.outstandingInvoices(FIRM, party, VoucherType.SALE)
                val invoice = open.randomOrNull(random)
                val amount = invoice?.let { random.nextLong(1, it.outstanding.paise + 1) } ?: (100L * random.nextLong(1, 50))
                val id = "rcpt-${d.name}-${++counter}"
                receive(d.repos, id, party, amount, invoice?.voucherId, d.today(), at, together = random.nextBoolean())
                track(d, id)
            }
            roll < 50 -> {
                val id = d.db.ids("SELECT id FROM vouchers WHERE status = 'posted' AND type IN ('sale', 'purchase', 'payment_in')").randomOrNull(random) ?: return
                d.repos.vouchers.cancel(id, "Entered twice", at)
            }
            roll < 54 -> {
                val row = d.db.query("SELECT id, cancellationReason FROM vouchers WHERE status = 'cancelled'").randomOrNull(random) ?: return
                d.repos.vouchers.restoreCancelled(row[0]!!, row[1]!!, "Cancelled by mistake", at)
            }
            roll < 61 -> {
                val party = d.repos.parties.findParty(CUSTOMERS.random(random)) ?: return
                d.repos.parties.saveParty(party.copy(phone = "98${random.nextInt(10_000_000, 99_999_999)}", updatedAt = maxOf(at, party.updatedAt)))
            }
            roll < 65 -> {
                val item = d.repos.catalog.findItem(ITEMS.random(random)) ?: return
                d.repos.catalog.saveItem(item.copy(salePrice = Money.of(100L * random.nextLong(5, 50)), updatedAt = maxOf(at, item.updatedAt)))
            }
            roll < 68 -> d.repos.parties.savePrice(FIRM, PartyItemPrice(CUSTOMERS.random(random), ITEMS.random(random), Money.of(100L * random.nextLong(5, 40))))
            roll < 71 -> {
                // A draft or held bill: stays on this device until posted.
                val id = "draft-${d.name}-${++counter}"
                d.repos.vouchers.saveDraft(bill(id, VoucherType.SALE, CUSTOMERS.random(random), d.today(), at, listOf(Triple(ITEMS.random(random), 1L, 1_500L))))
            }
            roll < 74 -> {
                val id = d.db.ids("SELECT id FROM vouchers WHERE status = 'draft' AND deletedAt IS NULL AND type = 'sale'").randomOrNull(random) ?: return
                d.repos.vouchers.post(id, at)
                track(d, id)
            }
            roll < 75 -> {
                val logo = File(d.dir, "logo-${++counter}.png").apply { writeBytes(random.nextBytes(32)) }
                val firm = d.repos.firms.findFirm(FIRM)!!
                d.repos.firms.saveFirm(firm.copy(logoPath = logo.path, updatedAt = maxOf(at, firm.updatedAt)))
            }
            roll < 82 -> d.online = !d.online
            roll < 96 -> d.sync()
            roll < 98 -> {
                server.backup(WS)
                backedUp = true
            }
            roll < 99 -> if (backedUp) server.restore(WS)
            else -> server.restart(WS)
        }
    }

    /** What a posting put in the books on its own device: compared with every replica at the end. */
    private suspend fun track(d: SimDevice, id: String) {
        val row = d.db.query("SELECT number, total, status FROM vouchers WHERE id = ?", id).singleOrNull() ?: return
        if (row[2] != "posted") return
        posted[id] = Posted(row[0]!!, row[1]!!, nets(d.db, JOURNAL, id), nets(d.db, STOCK, id))
    }

    private suspend fun nets(db: app.openbill.core.database.OpenBillDatabase, sql: String, id: String) =
        db.query(sql, id).associate { it[0]!! to it[1]!!.toLong() }.filterValues { it != 0L }

    private suspend fun check() {
        val serverDb = server.database(WS)
        val expected = serverDb.canonicalDump(BlobRefs.None)
        val head = server.head(WS)
        for (d in devices) {
            dumpDiff(expected, d.db.canonicalDump(d.blobs)).takeIf { it.isNotEmpty() }?.let { failures += "${d.name} diverged: $it" }
            val state = d.db.syncDao().state()!!
            if (d.db.syncDao().pendingCount() != 0L || state.cursor != head) failures += "${d.name} not settled: ${d.db.syncDao().pendingCount()} pending, cursor ${state.cursor} of $head"
            d.db.ids("PRAGMA integrity_check").let { if (it != listOf("ok")) failures += "${d.name} integrity: $it" }
            d.db.query("PRAGMA foreign_key_check").takeIf { it.isNotEmpty() }?.let { failures += "${d.name} foreign keys: ${it.take(3)}" }
        }
        for ((id, p) in posted) {
            val row = serverDb.query("SELECT number, total, status FROM vouchers WHERE id = ?", id).singleOrNull()
            if (row == null || row[2] !in setOf("posted", "cancelled")) {
                failures += "posted voucher $id (${p.number}) lost: $row"
                continue
            }
            if (row[0] != p.number || row[1] != p.total) failures += "posted voucher $id altered: ${p.number}/${p.total} -> ${row[0]}/${row[1]}"
            val live = row[2] == "posted"
            if (nets(serverDb, JOURNAL, id) != (if (live) p.journal else emptyMap())) failures += "journal of $id (${row[2]}) is ${nets(serverDb, JOURNAL, id)}, posted ${p.journal}"
            if (nets(serverDb, STOCK, id) != (if (live) p.stock else emptyMap())) failures += "stock of $id (${row[2]}) is ${nets(serverDb, STOCK, id)}, posted ${p.stock}"
        }
        serverDb.ids("SELECT voucherId FROM journal_entries GROUP BY voucherId HAVING SUM(debit) != SUM(credit)").forEach { failures += "unbalanced journal $it" }
        serverDb.ids(
            "SELECT p.id FROM vouchers p WHERE p.total < (SELECT COALESCE(SUM(amount), 0) FROM payment_allocations WHERE paymentVoucherId = p.id)",
        ).forEach { failures += "payment $it over-allocated" }
        serverDb.ids(
            "SELECT i.id FROM vouchers i WHERE i.total < (SELECT COALESCE(SUM(pa.amount), 0) FROM payment_allocations pa JOIN vouchers p ON p.id = pa.paymentVoucherId " +
                "WHERE pa.invoiceVoucherId = i.id AND p.status = 'posted' AND p.deletedAt IS NULL)",
        ).forEach { failures += "invoice $it over-allocated" }
        serverDb.ids("SELECT number FROM vouchers WHERE number IS NOT NULL GROUP BY firmId, type, financialYearId, number HAVING COUNT(*) > 1").forEach { failures += "duplicate number $it" }
        // Prefixes differ per document type and device series, so a number string is unique across the whole day.
        posted.values.groupBy { it.number }.filterValues { it.size > 1 }.keys.forEach { failures += "number issued twice: $it" }
    }

    private companion object {
        const val JOURNAL = "SELECT accountId, SUM(debit) - SUM(credit) FROM journal_entries WHERE voucherId = ? GROUP BY accountId"
        const val STOCK = "SELECT itemId || '|' || godownId || '|' || COALESCE(batchId, ''), SUM(quantity) FROM stock_movements WHERE voucherId = ? GROUP BY 1"
    }
}
