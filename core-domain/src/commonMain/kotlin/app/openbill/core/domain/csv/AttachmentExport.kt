package app.openbill.core.domain.csv

/** A record that can hold attachments (a voucher or a party), as the attachment export lists it. */
data class AttachmentOwner(val id: String, val kind: String, val number: String?, val date: String?, val status: String?, val party: String?)

/**
 * ZIP entries for the attachment export: one folder per owner ("kind_number_id") holding its
 * files, plus index.csv listing each file with its record. [files] maps owner id to (name, bytes).
 * Shared by the app's Export attachments and `openbill export attachments`.
 */
fun attachmentExport(owners: List<AttachmentOwner>, files: Map<String, List<Pair<String, ByteArray>>>): List<Pair<String, ByteArray>> {
    val safe = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]"), "_") }
    val rows = mutableListOf(Csv.row(listOf("file", "record", "number", "date", "status", "party", "bytes")))
    val entries = owners.flatMap { o ->
        val folder = listOfNotNull(o.kind, o.number, o.id).joinToString("_") { safe(it) }
        files[o.id].orEmpty().map { (name, bytes) ->
            val path = "$folder/$name"
            rows += Csv.row(listOf(path, o.kind, o.number, o.date, o.status, o.party, bytes.size.toString()))
            path to bytes
        }
    }
    return listOf("index.csv" to (rows.joinToString("\r\n") + "\r\n").encodeToByteArray()) + entries
}
