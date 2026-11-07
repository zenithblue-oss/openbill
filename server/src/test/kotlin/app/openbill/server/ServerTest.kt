package app.openbill.server

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServerTest {
    private val root: File = Files.createTempDirectory("openbill-server").toFile()
    private val started = mutableListOf<RunningServer>()

    @AfterTest
    fun tearDown() {
        started.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun start(config: ServerConfig = ServerConfig(File(root, "data"), port = 0)) = startServer(config).also { started += it }

    private class Reply(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
        fun str(key: String) = json.getValue(key).jsonPrimitive.content
    }

    private val http = HttpClient.newHttpClient()

    private fun call(s: RunningServer, method: String, path: String, body: String? = null, token: String? = null, client: HttpClient = http): Reply {
        val b = HttpRequest.newBuilder(URI(s.baseUrl + path)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) b.header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Reply(r.statusCode(), r.body(), r.headers().map())
    }

    private fun setupAdmin(s: RunningServer, password: String = "correct horse battery"): String {
        val token = s.app.instance.newSetupToken()
        assertEquals(201, call(s, "POST", "/api/v1/setup", """{"setupToken":"$token","username":"owner","password":"$password"}""").status)
        return password
    }

    private fun login(s: RunningServer, user: String = "owner", password: String = "correct horse battery") =
        call(s, "POST", "/api/v1/auth/login", """{"username":"$user","password":"$password"}""")

    @Test
    fun tomlEnvironmentAndInsecureHttpRules() {
        val parsed = Toml.parse(
            """
            # comment
            [server]
            bind = "0.0.0.0" # LAN
            port = 9000
            allow_insecure_http = true
            [tls]
            hosts = "a.local, \"q\""
            """.trimIndent(),
        )
        assertEquals(mapOf("server.bind" to "0.0.0.0", "server.port" to "9000", "server.allow_insecure_http" to "true", "tls.hosts" to "a.local, \"q\""), parsed)
        assertFailsWith<IllegalArgumentException> { Toml.parse("port = [1]") }
        assertFailsWith<IllegalArgumentException> { Toml.parse("a = 1\na = 2") }

        val dir = File(root, "cfg").apply { mkdirs() }
        File(dir, "server.toml").writeText("[server]\nbind = \"0.0.0.0\"\nport = 9000\n")
        val env = mapOf("OPENBILL_DATA_DIR" to dir.path, "OPENBILL_SERVER_PORT" to "9100", "OPENBILL_TLS_MODE" to "self_signed")
        val config = ServerConfig.load(env)
        assertEquals(9100, config.port, "environment wins over the file")
        assertEquals(ServerConfig.TlsMode.SELF_SIGNED, config.tls)
        config.validate()

        val plain = ServerConfig.load(env - "OPENBILL_TLS_MODE")
        assertTrue(assertFailsWith<IllegalArgumentException> { plain.validate() }.message!!.contains("Refusing plain HTTP"))
        plain.copy(allowInsecureHttp = true).validate()
        plain.copy(bind = "127.0.0.1").validate()
        File(dir, "server.toml").writeText("[server]\nbindd = \"x\"\n")
        assertFailsWith<IllegalArgumentException> { ServerConfig.load(env) }
    }

    @Test
    fun passwordsAndThrottle() {
        val hash = Passwords.hash("correct horse battery")
        assertTrue(Passwords.verify("correct horse battery", hash))
        assertFalse(Passwords.verify("correct horse batterz", hash))
        assertFalse(Passwords.verify("x", "garbage"))
        assertFailsWith<IllegalArgumentException> { Passwords.requireStrong("short") }
        var now = 0L
        val limiter = LoginLimiter(5) { now }
        repeat(4) { limiter.failed("k") }
        assertEquals(0, limiter.blockedFor("k"))
        limiter.failed("k")
        assertEquals(60, limiter.blockedFor("k"))
        now += 61_000
        assertEquals(0, limiter.blockedFor("k"))
        repeat(5) { limiter.failed("k") }
        assertEquals(120, limiter.blockedFor("k"), "lockouts double")
        limiter.succeeded("k")
        assertEquals(0, limiter.blockedFor("k"))
    }

    @Test
    fun setupLoginTokensWorkspacesAndAudit() {
        val s = start()
        assertEquals("ok", call(s, "GET", "/healthz").body)
        assertEquals(200, call(s, "GET", "/readyz").status)
        assertEquals("true", call(s, "GET", "/api/v1/version").str("setupRequired"))
        val noCors = call(s, "GET", "/api/v1/version").headers.keys.none { it.lowercase().startsWith("access-control") }
        assertTrue(noCors, "no CORS headers")

        assertEquals(403, call(s, "POST", "/api/v1/setup", """{"setupToken":"obst_wrong","username":"owner","password":"correct horse battery"}""").status)
        val token = s.app.instance.newSetupToken()
        assertEquals(400, call(s, "POST", "/api/v1/setup", """{"setupToken":"$token","username":"owner","password":"short"}""").status)
        assertEquals(201, call(s, "POST", "/api/v1/setup", """{"setupToken":"$token","username":"owner","password":"correct horse battery"}""").status)
        assertEquals(403, call(s, "POST", "/api/v1/setup", """{"setupToken":"$token","username":"second","password":"correct horse battery"}""").status, "single use")

        assertEquals(401, call(s, "GET", "/api/v1/me").status, "deny by default")
        assertEquals(401, login(s, password = "wrong password!").status)
        val ok = login(s)
        assertEquals(200, ok.status)
        val access = ok.str("accessToken")
        val refresh = ok.str("refreshToken")
        assertTrue(access.startsWith("obat_") && refresh.startsWith("obrt_"))
        assertEquals("owner", call(s, "GET", "/api/v1/me", token = access).str("username"))
        assertEquals(401, call(s, "GET", "/api/v1/me", token = access + "x").status)

        // Tokens are stored only as hashes.
        val db = File(s.app.config.dataDir, "instance.sqlite")
        val stored = BundledSQLiteDriver().open(db.path).let { c ->
            try {
                c.prepare("SELECT hash FROM tokens").use { st -> buildList { while (st.step()) add(st.getText(0)) } }
            } finally {
                c.close()
            }
        }
        assertTrue(stored.isNotEmpty() && stored.none { it == access || it == refresh } && Tokens.hash(access) in stored)
        assertFalse(db.readBytes().decodeToString().contains(access.removePrefix("obat_")), "raw token never on disk")

        val renewed = call(s, "POST", "/api/v1/auth/token", """{"refreshToken":"$refresh"}""").str("accessToken")
        assertEquals(200, call(s, "GET", "/api/v1/me", token = renewed).status)

        val ws = call(s, "POST", "/api/v1/workspaces", """{"name":"Sharma Traders"}""", access)
        assertEquals(201, ws.status)
        val wsId = ws.str("id")
        assertEquals(listOf(wsId), call(s, "GET", "/api/v1/workspaces", token = access).json.getValue("workspaces").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })

        // Another account sees neither the workspace nor may create one.
        s.app.instance.createUser("clerk", Passwords.hash("clerk password 1"), admin = false)
        val clerk = login(s, "clerk", "clerk password 1").str("accessToken")
        assertEquals(403, call(s, "POST", "/api/v1/workspaces", """{"name":"X"}""", clerk).status)
        assertEquals(404, call(s, "GET", "/api/v1/sync/workspaces/$wsId/ops?after=0", token = clerk).status)
        assertEquals(404, call(s, "GET", "/api/v1/sync/workspaces/..%2F..%2Fetc/ops?after=0", token = access).status)

        // Sync routes before seeding, snapshot placeholder, bad seed.
        assertEquals(409, call(s, "GET", "/api/v1/sync/workspaces/$wsId/ops?after=0", token = access).status)
        assertEquals(400, call(s, "POST", "/api/v1/sync/workspaces/$wsId/seed", "not a database", access).status)
        assertFalse(s.app.sync.exists(wsId))

        // Sign-out ends the session: access and refresh.
        assertEquals(204, call(s, "POST", "/api/v1/auth/logout", token = access).status)
        assertEquals(401, call(s, "GET", "/api/v1/me", token = access).status)
        assertEquals(401, call(s, "GET", "/api/v1/me", token = renewed).status)
        assertEquals(401, call(s, "POST", "/api/v1/auth/token", """{"refreshToken":"$refresh"}""").status)

        // Admin actions are audited in an intact hash chain; an edit breaks it.
        val actions = s.app.instance.auditTail(20).map { it[2] }
        assertTrue(listOf("admin.setup", "login.failed", "login", "workspace.create", "logout").all { it in actions }, actions.toString())
        assertNull(s.app.instance.verifyAudit())
        BundledSQLiteDriver().open(db.path).let { c ->
            try {
                c.execSQL("UPDATE audit SET target = 'someone else' WHERE action = 'workspace.create'")
            } finally {
                c.close()
            }
        }
        assertNotNull(s.app.instance.verifyAudit())
    }

    @Test
    fun loginIsThrottledPerAccount() {
        val s = start()
        setupAdmin(s)
        repeat(5) { assertEquals(401, login(s, password = "wrong password $it").status) }
        val blocked = login(s)
        assertEquals(429, blocked.status, "even the right password waits out the lockout")
        assertEquals("too_many_attempts", blocked.str("code"))
        assertTrue(blocked.headers.keys.any { it.equals("retry-after", true) })
    }

    @Test
    fun oversizedRequestsAreRefused() {
        val s = start(ServerConfig(File(root, "data"), port = 0, maxRequestBytes = 1L shl 20, maxSeedBytes = 1L shl 20))
        setupAdmin(s)
        val access = login(s).str("accessToken")
        val wsId = call(s, "POST", "/api/v1/workspaces", """{"name":"W"}""", access).str("id")
        val big = "x".repeat((1 shl 20) + 10)
        assertEquals(413, call(s, "POST", "/api/v1/sync/workspaces/$wsId/seed", big, access).status)
        assertEquals(413, call(s, "POST", "/api/v1/auth/login", "{\"username\":\"${"a".repeat(70_000)}\"}").status)
    }

    @Test
    fun selfSignedTlsIsStableAndPemFilesWork() {
        val data = File(root, "tls")
        val first = start(ServerConfig(data, port = 0, tls = ServerConfig.TlsMode.SELF_SIGNED))
        val fingerprint = first.tls!!.fingerprint
        val trusting = clientTrusting(first.tls!!)
        assertEquals("ok", call(first, "GET", "/healthz", client = trusting).body)
        assertFailsWith<javax.net.ssl.SSLHandshakeException> { call(first, "GET", "/healthz") }
        first.close()
        started.remove(first)
        val again = start(ServerConfig(data, port = 0, tls = ServerConfig.TlsMode.SELF_SIGNED))
        assertEquals(fingerprint, again.tls!!.fingerprint, "same certificate after a restart: pinning keeps working")
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(File(data, "keys").toPath())))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(File(data, "keys/self-signed.p12").toPath())))

        // The same key and certificate as PEM files ("files" mode, as from Let's Encrypt).
        val material = again.tls!!
        val certPem = File(root, "cert.pem").apply {
            writeText("-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(material.certificate.encoded) + "\n-----END CERTIFICATE-----\n")
        }
        val key = material.keyStore.getKey(TlsMaterial.ALIAS, material.password)
        val keyPem = File(root, "key.pem").apply {
            writeText("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val fromFiles = start(ServerConfig(File(root, "files"), port = 0, tls = ServerConfig.TlsMode.FILES, certFile = certPem, keyFile = keyPem))
        assertEquals(fingerprint, fromFiles.tls!!.fingerprint)
        assertEquals("ok", call(fromFiles, "GET", "/healthz", client = trusting).body)
    }

    private fun clientTrusting(material: TlsMaterial): HttpClient {
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", material.certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        return HttpClient.newBuilder().sslContext(ssl).build()
    }
}
