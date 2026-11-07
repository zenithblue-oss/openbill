package app.openbill.server

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** TLS material for the built-in HTTPS listener (docs/10-server.md section 3). */
class TlsMaterial(val keyStore: KeyStore, val password: CharArray) {
    val certificate get() = keyStore.getCertificate(ALIAS) as X509Certificate

    /** SHA-256 of the certificate, `AB:CD:...`: what devices pin (pairing QR, S1b). */
    val fingerprint: String get() = fingerprint(certificate)

    companion object {
        const val ALIAS = "openbill"

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        /** A certificate chain and a PKCS#8 key in PEM files (Let's Encrypt `fullchain.pem` and `privkey.pem`). */
        fun fromPem(certFile: File, keyFile: File): TlsMaterial {
            val chain = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate } }
            require(chain.isNotEmpty()) { "No certificate in $certFile" }
            val pem = keyFile.readText()
            require("BEGIN PRIVATE KEY" in pem) {
                "$keyFile is not a PKCS#8 key; convert it: openssl pkcs8 -topk8 -nocrypt -in ${keyFile.name} -out key.pk8.pem"
            }
            val der = Base64.getMimeDecoder().decode(pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"))
            val key: PrivateKey = listOf("RSA", "EC", "Ed25519").firstNotNullOfOrNull { alg ->
                runCatching { KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(der)) }.getOrNull()
            } ?: throw IllegalArgumentException("Cannot read the private key in $keyFile (RSA, EC or Ed25519 expected)")
            val password = randomPassword()
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, key, password, chain.toTypedArray())
            }
            return TlsMaterial(store, password)
        }

        /**
         * LAN mode: a self-signed certificate kept in `<data-dir>/keys` (mode 0600), created on
         * first start for localhost, this machine's addresses and [hosts]; the same one is reused,
         * so its fingerprint stays stable for pinning.
         */
        fun selfSigned(keysDir: File, hosts: List<String>): TlsMaterial {
            val storeFile = File(keysDir, "self-signed.p12")
            val passFile = File(keysDir, "self-signed.pass")
            if (storeFile.isFile && passFile.isFile) {
                val password = passFile.readText().trim().toCharArray()
                val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
                return TlsMaterial(store, password)
            }
            privateDir(keysDir)
            val password = randomPassword()
            val names = (listOf("localhost", InetAddress.getLocalHost().hostName) + hosts.filterNot(::isIp)).distinct()
            val ips = (listOf("127.0.0.1", "::1") + localAddresses() + hosts.filter(::isIp)).distinct().map { InetAddress.getByName(it) }
            val store = buildKeyStore {
                certificate(ALIAS) {
                    this.password = String(password)
                    domains = names
                    ipAddresses = ips
                    daysValid = 3650
                    keySizeInBits = 2048
                }
            }
            passFile.writeText(String(password))
            privateFile(passFile)
            store.saveToFile(storeFile, String(password))
            privateFile(storeFile)
            return TlsMaterial(store, password)
        }

        private fun isIp(host: String) = host.matches(Regex("[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:.]*"))

        private fun localAddresses(): List<String> = runCatching {
            NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterNot { it.isLinkLocalAddress }.map { it.hostAddress.substringBefore('%') }
        }.getOrDefault(emptyList())

        private fun randomPassword(): CharArray =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes)).toCharArray()
    }
}

/** Creates [dir] readable by the server's user only (POSIX; elsewhere the platform default). */
fun privateDir(dir: File) {
    dir.mkdirs()
    runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
}

fun privateFile(file: File) {
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
}
