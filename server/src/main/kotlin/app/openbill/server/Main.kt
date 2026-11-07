package app.openbill.server

import app.openbill.core.sync.SyncServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("openbill.server")

val VERSION: String = ServerApp::class.java.`package`?.implementationVersion ?: "dev"

/** A started server: [port] is the bound one (useful with port 0). */
class RunningServer(val engine: EmbeddedServer<*, *>, val port: Int, val app: ServerApp, val tls: TlsMaterial?) : AutoCloseable {
    val baseUrl get() = (if (tls != null) "https" else "http") + "://" + (if (app.config.bind == "0.0.0.0" || app.config.bind == "::") "localhost" else app.config.bind) + ":" + port

    override fun close() {
        engine.stop(500, 3_000)
        runBlocking { app.sync.close() }
        app.instance.close()
    }
}

/** Opens the data directory (section 3 layout), prints the first-run setup token, and starts listening. */
fun startServer(config: ServerConfig): RunningServer {
    config.validate()
    privateDir(config.dataDir)
    val instance = Instance(File(config.dataDir, "instance.sqlite"))
    privateFile(File(config.dataDir, "instance.sqlite"))
    val app = ServerApp(config, instance, SyncServer(File(config.dataDir, "workspaces")), VERSION)
    val tls = when (config.tls) {
        ServerConfig.TlsMode.OFF -> null
        ServerConfig.TlsMode.FILES -> TlsMaterial.fromPem(config.certFile!!, config.keyFile!!)
        ServerConfig.TlsMode.SELF_SIGNED -> TlsMaterial.selfSigned(File(config.dataDir, "keys"), config.tlsHosts)
    }
    if (!config.secure && !ServerConfig.isLoopback(config.bind)) {
        log.warn("Plain HTTP on {} (allow_insecure_http): only safe behind a TLS reverse proxy on a private network", config.bind)
    }
    val engine = embeddedServer(Netty, configure = {
        if (tls == null) {
            connector {
                host = config.bind
                port = config.port
            }
        } else {
            // Copies: the engine wipes the arrays it is given.
            sslConnector(tls.keyStore, TlsMaterial.ALIAS, { tls.password.copyOf() }, { tls.password.copyOf() }) {
                host = config.bind
                port = config.port
                enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
            }
        }
        requestReadTimeoutSeconds = 60
        responseWriteTimeoutSeconds = 60
    }) { openBillApi(app) }
    engine.start(wait = false)
    val port = runBlocking { engine.engine.resolvedConnectors().first().port }
    val running = RunningServer(engine, port, app, tls)
    log.info("OpenBill Server {} on {} (data in {})", VERSION, running.baseUrl, config.dataDir)
    tls?.let { log.info("TLS certificate SHA-256 fingerprint: {}", it.fingerprint) }
    if (!instance.hasUsers()) {
        val token = instance.newSetupToken()
        // Printed once, on purpose: the operator needs it to create the first admin. It stops working once an admin exists.
        log.warn(
            "No admin yet. Create one within this run:\n  curl{} -X POST {}/api/v1/setup -H 'Content-Type: application/json' " +
                "-d '{\"setupToken\":\"{}\",\"username\":\"admin\",\"password\":\"<at least {} characters>\"}'\n  or: openbill-server admin create <username>",
            if (tls != null && config.tls == ServerConfig.TlsMode.SELF_SIGNED) " -k" else "", running.baseUrl, token, Passwords.MIN_LENGTH,
        )
    }
    return running
}

fun main(args: Array<String>) {
    try {
        exitProcess(run(args.toList()))
    } catch (e: IllegalArgumentException) {
        System.err.println("openbill-server: ${e.message}")
        exitProcess(2)
    } catch (e: IllegalStateException) {
        System.err.println("openbill-server: ${e.message}")
        exitProcess(1)
    }
}

private const val USAGE = """Usage: openbill-server [serve]                 run the server (settings: OPENBILL_DATA_DIR, <data-dir>/server.toml)
       openbill-server admin create <username> create an admin (password from OPENBILL_ADMIN_PASSWORD or typed twice)
       openbill-server audit verify            check the server audit log's hash chain
       openbill-server version"""

private fun run(args: List<String>): Int = when (args) {
    emptyList<String>(), listOf("serve") -> {
        val server = startServer(ServerConfig.load())
        Runtime.getRuntime().addShutdownHook(Thread { log.info("Stopping"); server.close() })
        Thread.currentThread().join()
        0
    }
    listOf("version"), listOf("--version") -> 0.also { println("openbill-server $VERSION") }
    listOf("audit", "verify") -> withInstance { instance ->
        val broken = instance.verifyAudit()
        if (broken == null) 0.also { println("Audit log intact") } else 1.also { println("Audit log broken at row $broken") }
    }
    else -> if (args.size == 3 && args[0] == "admin" && args[1] == "create") {
        withInstance { instance ->
            val password = System.getenv("OPENBILL_ADMIN_PASSWORD") ?: readPassword()
            Passwords.requireStrong(password)
            val user = instance.createUser(args[2], Passwords.hash(password), admin = true)
            instance.audit(null, "admin.create", user.username, "cli")
            println("Admin ${user.username} created")
            0
        }
    } else {
        2.also { System.err.println(USAGE) }
    }
}

/** Offline admin work on the data directory (the server may be stopped or running; SQLite locks keep it safe). */
private fun withInstance(block: (Instance) -> Int): Int {
    val config = ServerConfig.load()
    privateDir(config.dataDir)
    return Instance(File(config.dataDir, "instance.sqlite")).use(block)
}

private fun readPassword(): String {
    val console = System.console() ?: throw IllegalArgumentException("No console: set OPENBILL_ADMIN_PASSWORD")
    val first = String(console.readPassword("Password: "))
    val second = String(console.readPassword("Again: "))
    require(first == second) { "The passwords differ" }
    return first
}
