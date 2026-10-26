package app.openbill.core.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password envelope around a backup zip (docs/03 section 14), JDK only:
 * `OPENBILL-ENC1` | iterations (int) | salt (16) | nonce prefix (8) | segments.
 * The key is PBKDF2-HMAC-SHA256 (AES-256). The zip is cut into 64 KiB segments, each sealed
 * with AES-GCM under nonce = prefix + counter and the final-segment flag as associated data,
 * so memory stays flat and truncation or reordering fails authentication.
 */
object BackupCrypto {
    private val MAGIC = "OPENBILL-ENC1".toByteArray()
    private const val SEGMENT = 64 * 1024
    private const val TAG = 16
    private const val ITERATIONS = 600_000

    fun isEncrypted(file: File): Boolean =
        file.isFile && file.inputStream().use { i -> ByteArray(MAGIC.size).also { read(i, it, it.size) } }.contentEquals(MAGIC)

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val key = key(password, salt, ITERATIONS)
        DataOutputStream(output).apply { write(MAGIC); writeInt(ITERATIONS); write(salt); write(prefix); flush() }
        val buf = ByteArray(SEGMENT)
        var counter = 0
        while (true) {
            val n = read(input, buf, SEGMENT)
            val last = n < SEGMENT // a full final read is followed by an empty last segment
            output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, last).doFinal(buf, 0, n))
            if (last) break
        }
        output.flush()
    }

    /** Throws [BackupException] (INVALID) on a wrong password or a damaged file. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        if (read(data, magic, magic.size) < magic.size || !magic.contentEquals(MAGIC)) throw BackupException(BackupFailure.INVALID, "Not an encrypted OpenBill backup")
        val iterations = data.readInt()
        if (iterations !in 1..10_000_000) throw BackupException(BackupFailure.INVALID, "Damaged encrypted backup header")
        val salt = ByteArray(16).also { data.readFully(it) }
        val prefix = ByteArray(8).also { data.readFully(it) }
        val key = key(password, salt, iterations)
        val buf = ByteArray(SEGMENT + TAG)
        var counter = 0
        try {
            while (true) {
                val n = read(data, buf, buf.size)
                val last = n < buf.size
                if (n == 0) throw BackupException(BackupFailure.INVALID, "Encrypted backup is truncated")
                output.write(cipher(Cipher.DECRYPT_MODE, key, prefix, counter++, last).doFinal(buf, 0, n))
                if (last) break
            }
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupFailure.INVALID, "Wrong password, or the backup is damaged", e)
        }
        output.flush()
    }

    /** Decrypts [archive] into a temporary file in [dir] when it is encrypted; else returns it as is. */
    internal fun plain(archive: File, password: CharArray?, dir: File): File {
        if (!isEncrypted(archive)) return archive
        if (password == null || password.isEmpty()) throw BackupException(BackupFailure.INVALID, "This backup is encrypted; enter its password")
        val out = File.createTempFile(".openbill-plain", ".zip", dir)
        try {
            archive.inputStream().buffered().use { i -> out.outputStream().buffered().use { decrypt(i, it, password) } }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    /** Reads up to [len] bytes, short only at end of stream (InputStream.readNBytes needs Android 13). */
    private fun read(input: InputStream, buf: ByteArray, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(buf, n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded,
        "AES",
    )

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, counter: Int, last: Boolean) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG * 8, ByteBuffer.allocate(12).put(prefix).putInt(counter).array()))
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }
}
