package app.openbill.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing with the JDK's PBKDF2-HMAC-SHA256 at 600,000 iterations (OWASP 2023), so the
 * server needs no native or extra crypto library. Stored as `pbkdf2-sha256$<iterations>$<salt>$<hash>`;
 * the iteration count travels with the hash, so it can be raised later.
 */
object Passwords {
    const val ITERATIONS = 600_000
    const val MIN_LENGTH = 10
    private val random = SecureRandom()

    fun requireStrong(password: String) {
        require(password.length in MIN_LENGTH..1024) { "A password needs at least $MIN_LENGTH characters" }
    }

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256\$$ITERATIONS\$${b64(salt)}\$${b64(derive(password, salt, ITERATIONS))}"
    }

    /** Constant-time comparison; a malformed stored hash never matches. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    /** Spends the same time as [verify] when there is no such user, so timing does not reveal usernames. */
    val DUMMY: String by lazy { hash("dummy-password-for-timing") }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Opaque bearer tokens (section 7.1): 256 random bits, prefixed by type for secret scanning
 * (`obat_` access, `obrt_` refresh, `obst_` setup), stored only as SHA-256 hashes.
 */
object Tokens {
    const val ACCESS = "obat_"
    const val REFRESH = "obrt_"
    const val SETUP = "obst_"
    private val random = SecureRandom()

    fun new(prefix: String): String = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Constant-time string comparison (for secrets compared directly, not looked up by hash). */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/**
 * Sign-in throttle (section 11): more than [perMinute] failures within a minute for one key (an
 * account or an address) locks it for 1, 2, 4 ... up to 64 minutes. In memory: a restart clears it.
 */
class LoginLimiter(private val perMinute: Int, private val now: () -> Long = System::currentTimeMillis) {
    private class State {
        val failures = ArrayDeque<Long>()
        var lockouts = 0
        var until = 0L
    }

    private val states = HashMap<String, State>()

    /** Seconds until [key] may try again; 0 when allowed. */
    @Synchronized
    fun blockedFor(key: String): Long {
        val until = states[key]?.until ?: return 0
        return ((until - now() + 999) / 1000).coerceAtLeast(0)
    }

    @Synchronized
    fun failed(key: String) {
        val t = now()
        if (states.size > MAX_KEYS) states.entries.removeIf { (_, s) -> s.until < t && (s.failures.lastOrNull() ?: 0) < t - MINUTE }
        val s = states.getOrPut(key) { State() }
        s.failures.addLast(t)
        while (s.failures.first() < t - MINUTE) s.failures.removeFirst()
        if (s.failures.size >= perMinute) {
            s.until = t + (MINUTE shl s.lockouts.coerceAtMost(6))
            s.lockouts++
            s.failures.clear()
        }
    }

    @Synchronized
    fun succeeded(key: String) {
        states.remove(key)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val MAX_KEYS = 10_000
    }
}
