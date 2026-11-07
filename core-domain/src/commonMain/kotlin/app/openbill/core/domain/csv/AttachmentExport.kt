package app.openbill.core.domain.csv

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType

/** A record that can hold attachments (a voucher or a party), as the attachment export lists it. */
data class AttachmentOwner(val id: String, val kind: String, val number: String?, val date: String?, val status: String?, val party: String?)

/** The record name an exported attachment folder starts with, e.g. "Sales invoice". */
fun attachmentKind(type: VoucherType): String = when (type) {
    VoucherType.SALE -> "Sales invoice"
    VoucherType.PURCHASE -> "Purchase bill"
    VoucherType.CREDIT_NOTE -> "Sales return"
    VoucherType.DEBIT_NOTE -> "Purchase return"
    VoucherType.QUOTATION -> "Quotation"
    VoucherType.PROFORMA -> "Proforma invoice"
    VoucherType.PAYMENT_IN -> "Payment in"
    VoucherType.PAYMENT_OUT -> "Payment out"
    VoucherType.EXPENSE -> "Expense"
    VoucherType.STOCK_ADJUSTMENT -> "Stock adjustment"
    VoucherType.STOCK_TRANSFER -> "Stock transfer"
    VoucherType.DELIVERY_CHALLAN -> "Delivery challan"
    VoucherType.CONTRA -> "Transfer"
    VoucherType.SALES_ORDER -> "Sales order"
    VoucherType.PURCHASE_ORDER -> "Purchase order"
}

/** Every record the export walks: vouchers by date and number, then parties by name. */
fun attachmentOwners(vouchers: List<Voucher>, parties: List<Party>): List<AttachmentOwner> {
    val names = parties.associate { it.id to it.name }
    return vouchers.sortedWith(compareBy({ it.date }, { it.number ?: "" }))
        .map { AttachmentOwner(it.id, attachmentKind(it.type), it.number ?: "draft", it.date.toString(), it.status.name.lowercase(), it.partyId?.let(names::get)) } +
        parties.sortedBy { it.name.lowercase() }.map { AttachmentOwner(it.id, "Party", null, null, null, it.name) }
}

/** Letters, digits, `.`, `_`, `-` and spaces only (valid on Windows, macOS and Linux), no trailing dot or space. */
private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimEnd('.', ' ').ifEmpty { "_" }

/**
 * ZIP entries for the attachment export: one folder per owner ("Kind_number_id") holding its
 * files, plus index.csv listing each file with its record. [files] maps owner id to (name, bytes).
 * Shared by the app's Export attachments and `openbill export attachments`.
 */
fun attachmentExport(owners: List<AttachmentOwner>, files: Map<String, List<Pair<String, ByteArray>>>): List<Pair<String, ByteArray>> {
    val rows = mutableListOf(Csv.row(listOf("file", "record", "number", "date", "status", "party", "bytes")))
    val entries = owners.flatMap { o ->
        val folder = listOfNotNull(o.kind, o.number, o.id).joinToString("_") { safeName(it) }
        files[o.id].orEmpty().map { (name, bytes) ->
            val path = "$folder/${safeName(name)}"
            rows += Csv.row(listOf(path, o.kind, o.number, o.date, o.status, o.party, bytes.size.toString()))
            path to bytes
        }
    }
    return listOf("index.csv" to (rows.joinToString("\r\n") + "\r\n").encodeToByteArray()) + entries
}
