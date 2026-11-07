package app.openbill.core.sync

import app.openbill.server.Passwords
import app.openbill.server.ServerConfig
import app.openbill.server.startServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Contract test (docs/10-server.md 16): the multi-device simulation with every push and pull going
 * through [HttpSyncTransport] to the real server (Ktor, accounts, tokens, SQLite op log) on a
 * random local port. Joins still copy the server's database in process (snapshot download is S1c).
 * More seeds: `-Popenbill.sim.seeds=<n>` also applies here.
 */
class HttpContractTest {
    @Test
    fun simulationOverHttpConverges(): Unit = runBlocking {
        val seeds = (System.getProperty("openbill.sim.seeds")?.toInt() ?: 3).coerceAtMost(50)
        val steps = System.getProperty("openbill.sim.steps")?.toInt() ?: 120
        for (seed in 1..seeds) {
            val root = Files.createTempDirectory("openbill-http-$seed").toFile()
            val server = startServer(ServerConfig(File(root, "data"), port = 0))
            val client = HttpClient(OkHttp)
            try {
                val owner = server.app.instance.createUser("owner", Passwords.hash("correct horse battery"), admin = true)
                server.app.instance.createWorkspace("Sim Traders", owner, id = WS)
                val refresh = HttpSyncTransport.login(client, server.baseUrl, "owner", "correct horse battery")
                val http = HttpSyncTransport(server.baseUrl, refresh, client)
                val result = Simulation(seed.toLong(), root, steps, faults = 0.12, server = server.app.sync, link = http, seedWith = { http.seed(WS, it) }).run()
                println("http simulation seed $seed: ${result.serverOps} server ops, ${result.rejectedGroups} rejected groups")
                assertEquals(emptyList(), result.failures.take(10), "seed $seed")
                // Seeding twice, snapshots before S1c and unknown workspaces are refusals, not retries.
                assertFailsWith<SyncRefusedException> { http.seed(WS, ByteArray(1)) }
                assertFailsWith<SyncRefusedException> { http.snapshot(WS) }.let { assertEquals(true, it.message?.contains("S1c")) }
                assertFailsWith<SyncRefusedException> { http.pull("nope", 0, 10) }
                assertFailsWith<SyncRefusedException> { HttpSyncTransport(server.baseUrl, "obrt_forged", client).pull(WS, 0, 10) }
            } finally {
                client.close()
                server.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun aStoppedServerIsARetryableFailure(): Unit = runBlocking {
        val client = HttpClient(OkHttp)
        try {
            // Nothing listens on port 1: the client must back off and retry, never treat it as a refusal.
            assertFailsWith<SyncTransportException> { HttpSyncTransport("http://127.0.0.1:1", "obrt_x", client).pull(WS, 0, 1) }
        } finally {
            client.close()
        }
    }
}
