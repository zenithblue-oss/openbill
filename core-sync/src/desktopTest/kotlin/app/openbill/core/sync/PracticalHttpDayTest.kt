package app.openbill.core.sync

import androidx.room.Room
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.VoucherType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Practical check against a running `openbill-server` (docs/server-setup.md): two counters with
 * file-backed databases sync a business day over HTTP. Run in phases so the server can be
 * restarted in between (runs only with -Popenbill.sim.url):
 *
 *   OPENBILL_SIM_USER=admin OPENBILL_SIM_PASSWORD=... ./gradlew :core-sync:desktopTest --tests '*PracticalHttpDay*' \
 *     -Popenbill.sim.url=http://127.0.0.1:8090 -Popenbill.sim.workspace=<id> -Popenbill.sim.keep=<dir> -Popenbill.sim.phase=1|2|final
 *
 * `final` (server stopped, -Popenbill.sim.serverdb=<copy of projection.sqlite>) also compares with the server's database.
 */
class PracticalHttpDayTest {
    @Test
    fun practicalDayOverHttp(): Unit = runBlocking {
        val url = System.getProperty("openbill.sim.url") ?: return@runBlocking
        val ws = System.getProperty("openbill.sim.workspace")!!
        val root = File(System.getProperty("openbill.sim.keep")!!)
        val phase = System.getProperty("openbill.sim.phase") ?: "1"
        val client = HttpClient(OkHttp)
        try {
            // Like a paired device: sign in once, keep the refresh token, work offline when the server is down.
            val tokenFile = File(root, "refresh.token")
            val http = when (phase) {
                "final" -> null
                "1" -> {
                    root.mkdirs()
                    val refresh = HttpSyncTransport.login(client, url, System.getenv("OPENBILL_SIM_USER"), System.getenv("OPENBILL_SIM_PASSWORD"))
                    tokenFile.writeText(refresh)
                    HttpSyncTransport(url, refresh, client)
                }
                else -> HttpSyncTransport(url, tokenFile.readText(), client)
            }
            val unused = SyncServer(File(root, "unused"))
            val devices = listOf("A", "B").mapIndexed { i, s -> SimDevice(s, File(root, "device-$s"), s, unused, 2026L + i, http ?: unused, ws) }
            val (a, b) = devices
            when (phase) {
                "1" -> {
                    root.mkdirs()
                    a.open()
                    seedFirm(a.repos)
                    val seed = File(root, "seed.db").also { it.delete() }
                    SyncSnapshots.vacuumInto(a.file, seed)
                    http!!.seed(ws, seed.readBytes())
                    SyncOutbox(a.db).enable("A")
                    // B joins from the same seed (snapshot download is S1c): a prepared copy at seq 0.
                    b.dir.mkdirs()
                    seed.copyTo(b.file, overwrite = true)
                    SyncSnapshots.prepare(b.file)
                    b.open()
                    SyncOutbox(b.db).enable("B")
                    day(devices, 1..120)
                }
                "2" -> {
                    devices.forEach { it.open() }
                    day(devices, 121..240)
                }
                "final" -> {
                    devices.forEach { it.open() }
                    val expected = a.db.canonicalDump(a.blobs)
                    assertEquals("", dumpDiff(expected, b.db.canonicalDump(b.blobs)), "B equals A")
                    devices.forEach { assertEquals(0L, it.db.syncDao().pendingCount(), "${it.name} has nothing pending") }
                    System.getProperty("openbill.sim.serverdb")?.let { path ->
                        val server = openBillDatabase(Room.databaseBuilder<OpenBillDatabase>(name = path))
                        try {
                            assertEquals("", dumpDiff(server.canonicalDump(BlobRefs.None), expected), "server equals the devices")
                            assertEquals(a.db.syncDao().state()!!.cursor, server.syncDao().state()!!.cursor, "devices are at the server's head")
                        } finally {
                            server.close()
                        }
                    }
                    val posted = a.db.ids("SELECT COUNT(*) FROM vouchers WHERE status = 'posted'").single()
                    println("practical day: converged, $posted posted vouchers, cursor ${a.db.syncDao().state()!!.cursor}")
                }
            }
            if (phase != "final") {
                // Settle once the server is up (it may be restarting): everything pushed and pulled.
                val deadline = System.currentTimeMillis() + 180_000
                while (true) {
                    try {
                        repeat(4) { devices.forEach { it.sync() } }
                        break
                    } catch (e: SyncTransportException) {
                        if (System.currentTimeMillis() > deadline) throw e
                        delay(2_000)
                    }
                }
                val head = http!!.pull(ws, 0, 1).head
                devices.forEach { d ->
                    assertEquals(0L, d.db.syncDao().pendingCount(), "${d.name} pending")
                    assertEquals(head, d.db.syncDao().state()!!.cursor, "${d.name} at head")
                }
                println("practical day phase $phase: server head $head")
            }
            devices.forEach { runCatching { it.close() } }
            unused.close()
        } finally {
            client.close()
        }
    }

    /** Sales, purchases, receipts, cancels and master edits at two counters, syncing now and then. */
    private suspend fun day(devices: List<SimDevice>, steps: IntRange) {
        val random = kotlin.random.Random(steps.first)
        var n = steps.first * 10
        for (step in steps) {
            // A day's pace, compressed: about 30 s per phase, so a server restart lands mid-way.
            delay(250)
            devices.forEach { it.step = step }
            val d = devices[random.nextInt(devices.size)]
            val at = d.now()
            try {
                when (random.nextInt(100)) {
                    in 0..34 -> {
                        val id = "sale-${d.name}-${++n}"
                        val lines = (1..random.nextInt(1, 4)).map { Triple(ITEMS.random(random), random.nextLong(1, 6), 100L * random.nextLong(5, 40)) }
                        d.repos.vouchers.saveDraft(bill(id, VoucherType.SALE, CUSTOMERS.random(random), d.today(), at, lines, batch = "LOT-1"))
                        d.repos.vouchers.post(id, at)
                    }
                    in 35..44 -> {
                        val id = "pur-${d.name}-${++n}"
                        d.repos.vouchers.saveDraft(bill(id, VoucherType.PURCHASE, "supp-1", d.today(), at, listOf(Triple(ITEMS.random(random), 20L, 800L)), batch = "LOT-1"))
                        d.repos.vouchers.post(id, at)
                    }
                    in 45..59 -> {
                        val party = CUSTOMERS.random(random)
                        val invoice = d.repos.payments.outstandingInvoices(FIRM, party, VoucherType.SALE).randomOrNull(random)
                        val amount = invoice?.let { random.nextLong(1, it.outstanding.paise + 1) } ?: 5_000L
                        receive(d.repos, "rcpt-${d.name}-${++n}", party, amount, invoice?.voucherId, d.today(), at, together = true)
                    }
                    in 60..64 -> d.db.ids("SELECT id FROM vouchers WHERE status = 'posted' AND type = 'sale'").randomOrNull(random)?.let { d.repos.vouchers.cancel(it, "Entered twice", at) }
                    in 65..74 -> {
                        val party = d.repos.parties.findParty(CUSTOMERS.random(random)) ?: continue
                        d.repos.parties.saveParty(party.copy(phone = "98${random.nextInt(10_000_000, 99_999_999)}", updatedAt = maxOf(at, party.updatedAt)))
                    }
                    else -> d.sync()
                }
            } catch (e: SyncTransportException) {
                // Server down (restart): keep working offline.
            } catch (e: IllegalArgumentException) {
            } catch (e: IllegalStateException) {
            }
        }
    }
}
