package app.openbill.server

import java.io.File
import java.net.InetAddress

/**
 * Server settings (docs/10-server.md section 3): `<data-dir>/server.toml` (or `OPENBILL_CONFIG`),
 * then environment overrides `OPENBILL_<SECTION>_<KEY>` (e.g. `OPENBILL_SERVER_PORT`,
 * `OPENBILL_TLS_MODE`). The data directory itself comes from `OPENBILL_DATA_DIR`.
 * Secrets are never inline: TLS keys are file paths.
 */
data class ServerConfig(
    val dataDir: File,
    val bind: String = "127.0.0.1",
    val port: Int = 8090,
    /** Plain HTTP on a non-loopback address (behind a TLS reverse proxy); warned about on every start. */
    val allowInsecureHttp: Boolean = false,
    /** Take the client address from the last `X-Forwarded-For` entry (only behind your own proxy). */
    val trustForwardedFor: Boolean = false,
    val tls: TlsMode = TlsMode.OFF,
    val certFile: File? = null,
    val keyFile: File? = null,
    /** Extra names and IP addresses for the self-signed certificate (this machine's are added). */
    val tlsHosts: List<String> = emptyList(),
    val loginAttemptsPerMinute: Int = 5,
    val maxPushOps: Int = 500,
    val maxRequestBytes: Long = 10L shl 20,
    val maxSeedBytes: Long = 512L shl 20,
    val accessTokenMinutes: Long = 15,
    val refreshTokenDays: Long = 90,
) {
    enum class TlsMode { OFF, FILES, SELF_SIGNED }

    val secure get() = tls != TlsMode.OFF

    /** Deny-by-default checks; the message says what to change. */
    fun validate() {
        require(port in 0..65535) { "server.port must be 0-65535" }
        require(loginAttemptsPerMinute in 1..1000 && maxPushOps in 1..5000 && accessTokenMinutes in 1..1440 && refreshTokenDays in 1..3650) { "A [limits] value is out of range" }
        if (tls == TlsMode.FILES) {
            require(certFile?.isFile == true && keyFile?.isFile == true) { "tls.mode = \"files\" needs readable tls.cert_file and tls.key_file" }
        }
        if (!secure && !isLoopback(bind)) {
            require(allowInsecureHttp) {
                "Refusing plain HTTP on $bind: other machines would send passwords and business data unencrypted. " +
                    "Use tls.mode = \"self_signed\" (LAN) or \"files\", bind 127.0.0.1 behind a TLS reverse proxy, " +
                    "or set server.allow_insecure_http = true if a proxy on a private network terminates TLS"
            }
        }
    }

    companion object {
        fun isLoopback(host: String): Boolean = runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

        fun load(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["OPENBILL_DATA_DIR"] ?: "openbill-data").absoluteFile
            val file = env["OPENBILL_CONFIG"]?.let(::File) ?: File(dataDir, "server.toml")
            val values = HashMap<String, String>()
            if (file.isFile) values += Toml.parse(file.readText())
            for (key in KEYS) env["OPENBILL_" + key.uppercase().replace('.', '_')]?.let { values[key] = it }
            (values.keys - KEYS).firstOrNull()?.let { throw IllegalArgumentException("Unknown setting $it in $file") }
            fun bool(key: String, default: Boolean) = values[key]?.let {
                it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key must be true or false")
            } ?: default
            fun long(key: String, default: Long) = values[key]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("$key must be a number") } ?: default
            val d = ServerConfig(dataDir)
            return ServerConfig(
                dataDir = dataDir,
                bind = values["server.bind"] ?: d.bind,
                port = long("server.port", d.port.toLong()).toInt(),
                allowInsecureHttp = bool("server.allow_insecure_http", false),
                trustForwardedFor = bool("server.trust_forwarded_for", false),
                tls = values["tls.mode"]?.let { m ->
                    TlsMode.entries.firstOrNull { it.name.equals(m.replace('-', '_'), ignoreCase = true) }
                        ?: throw IllegalArgumentException("tls.mode must be off, files or self_signed")
                } ?: d.tls,
                certFile = values["tls.cert_file"]?.let(::File),
                keyFile = values["tls.key_file"]?.let(::File),
                tlsHosts = values["tls.hosts"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                loginAttemptsPerMinute = long("limits.login_attempts_per_minute", d.loginAttemptsPerMinute.toLong()).toInt(),
                maxPushOps = long("limits.max_push_ops", d.maxPushOps.toLong()).toInt(),
                maxRequestBytes = long("limits.max_request_mb", d.maxRequestBytes shr 20) shl 20,
                maxSeedBytes = long("limits.max_seed_mb", d.maxSeedBytes shr 20) shl 20,
                accessTokenMinutes = long("limits.access_token_minutes", d.accessTokenMinutes),
                refreshTokenDays = long("limits.refresh_token_days", d.refreshTokenDays),
            )
        }

        private val KEYS = setOf(
            "server.bind", "server.port", "server.allow_insecure_http", "server.trust_forwarded_for",
            "tls.mode", "tls.cert_file", "tls.key_file", "tls.hosts",
            "limits.login_attempts_per_minute", "limits.max_push_ops", "limits.max_request_mb", "limits.max_seed_mb",
            "limits.access_token_minutes", "limits.refresh_token_days",
        )
    }
}

/**
 * The TOML subset server.toml needs: `[section]` headers, `key = value` with a "string"
 * (escapes \" and \\), an integer or true/false, and # comments. Anything else is an error.
 */
object Toml {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var section = ""
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            fun fail(): Nothing = throw IllegalArgumentException("server.toml line ${i + 1}: cannot read '$line'")
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("[") -> section = Regex("""\[\s*([A-Za-z0-9_]+)\s*]\s*(#.*)?""").matchEntire(line)?.groupValues?.get(1) ?: fail()
                else -> {
                    val eq = line.indexOf('=').takeIf { it > 0 } ?: fail()
                    val key = line.substring(0, eq).trim().takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: fail()
                    val rest = line.substring(eq + 1).trim()
                    val value = if (rest.startsWith("\"")) {
                        val sb = StringBuilder()
                        var j = 1
                        while (true) {
                            if (j >= rest.length) fail()
                            val c = rest[j]
                            if (c == '"') break
                            if (c == '\\') {
                                j++
                                if (j >= rest.length || rest[j] !in "\"\\") fail()
                            }
                            sb.append(rest[j])
                            j++
                        }
                        if (rest.substring(j + 1).trim().let { it.isNotEmpty() && !it.startsWith("#") }) fail()
                        sb.toString()
                    } else {
                        rest.substringBefore('#').trim().takeIf { it.matches(Regex("-?[0-9]+|true|false")) } ?: fail()
                    }
                    val full = if (section.isEmpty()) key else "$section.$key"
                    if (out.put(full, value) != null) throw IllegalArgumentException("server.toml line ${i + 1}: $full is set twice")
                }
            }
        }
        return out
    }
}
