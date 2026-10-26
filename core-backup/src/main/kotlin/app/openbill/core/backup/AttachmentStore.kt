package app.openbill.core.backup

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Files attached to a voucher (or any record), kept as `<root>/<ownerId>/<name>`. [root] is the
 * `attachments` entry of a [DataLocation], so backups and restores carry the files.
 * shortcut: the folder is the index (no attachments table); add one when sync or search needs it.
 */
class AttachmentStore(private val root: File) {
    fun list(ownerId: String): List<String> =
        dir(ownerId).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name }?.sorted().orEmpty()

    fun file(ownerId: String, name: String): File = File(dir(ownerId), safe(name)).also { require(it.isFile) { "Attachment not found" } }

    /** Copies [input] in atomically; a name clash gets a numeric suffix. Returns the stored name. */
    fun add(ownerId: String, name: String, input: InputStream): String {
        val d = dir(ownerId).apply { mkdirs() }
        val base = safe(name)
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val target = generateSequence(0) { it + 1 }.map { File(d, if (it == 0) base else "$stem-$it$ext") }.first { !it.exists() }
        val tmp = File(d, target.name + ".tmp")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                if (size > MAX_BYTES) { out.close(); tmp.delete(); throw IllegalArgumentException("Attachments are limited to 20 MB") }
                out.write(buf, 0, n)
            }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return target.name
    }

    fun delete(ownerId: String, name: String) {
        File(dir(ownerId), safe(name)).delete()
    }

    private fun dir(ownerId: String) = File(root, safe(ownerId))

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024

        /** Original names are metadata only: no separators, `..` or control characters reach the disk. */
        internal fun safe(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(120).ifBlank { "file" }
    }
}
