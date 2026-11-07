package app.openbill.core.domain.report

import app.openbill.core.model.OnProgress
import app.openbill.core.model.Progress
import app.openbill.core.domain.repository.Repositories
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** One file of a report bundle. */
class ReportFile(val name: String, val text: String) {
    val bytes: ByteArray get() = text.encodeToByteArray()
}

/**
 * Report bundles (several reports shared or saved together, and the CA pack). Every file is the
 * same text the single-report export writes, so a bundle never disagrees with the screens.
 */
object ReportPack {
    /** Reports that can be bundled: all but the party statement, which needs a party. */
    val BUNDLE_KINDS: List<ReportKind> = ReportKind.entries.filter { !it.needsParty }

    /** The CA pack's tables (CSV); GSTR-1 JSON, GSTR-2 JSON, party ledgers and Tally XML are added by [caPack]. */
    val CA_KINDS = listOf(
        ReportKind.SALES_REGISTER, ReportKind.PURCHASE_REGISTER, ReportKind.DAY_BOOK, ReportKind.GSTR1, ReportKind.GSTR2,
        ReportKind.GSTR3B, ReportKind.HSN_SUMMARY, ReportKind.OUTSTANDING,
    )

    fun stem(kind: ReportKind, period: ReportPeriod) = "${kind.key}-${period.from}-${period.to}"

    /** Calendar months overlapping [period], oldest first (GSTR-1 is monthly). */
    fun months(period: ReportPeriod): List<ReportPeriod> {
        val out = ArrayList<ReportPeriod>()
        var m = ReportPeriod.monthOf(period.from)
        while (m.from <= period.to) {
            out += m
            m = ReportPeriod.monthOf(m.to.plus(DatePeriod(days = 1)))
        }
        return out
    }
}

/** CSV of each of [kinds] for [period], named like the single-report export. */
suspend fun Repositories.reportFiles(firmId: String, period: ReportPeriod, kinds: List<ReportKind>, onProgress: OnProgress = {}, today: LocalDate? = null): List<ReportFile> =
    kinds.mapIndexed { i, kind ->
        require(!kind.needsParty) { "${kind.title} needs a party" }
        onProgress(Progress("Preparing ${kind.title.lowercase()}", i.toLong(), kinds.size.toLong()))
        ReportFile("${ReportPack.stem(kind, period)}.csv", Reports.run(kind, loadReportData(firmId, period, kind, today = today)).toCsv())
    }

/**
 * The CA pack for [period]: registers, day book, GSTR-1 (summary CSV and portal JSON per month),
 * GSTR-2 working (CSV and JSON), GSTR-3B, HSN summary, outstanding, every party's ledger and
 * Tally XML, plus a README listing what was left out.
 */
suspend fun Repositories.caPack(firmId: String, period: ReportPeriod, onProgress: OnProgress = {}, today: LocalDate? = null): List<ReportFile> {
    val files = ArrayList(reportFiles(firmId, period, ReportPack.CA_KINDS, onProgress, today))
    val notes = ArrayList<String>()
    onProgress(Progress("Preparing GSTR-2 JSON"))
    val extra = mapOf("from" to period.from.toString(), "to" to period.to.toString())
    files += ReportFile("${ReportPack.stem(ReportKind.GSTR2, period)}.json", Reports.run(ReportKind.GSTR2, loadReportData(firmId, period, ReportKind.GSTR2)).toJson(extra))
    val firm = firms.findFirm(firmId) ?: throw IllegalArgumentException("Unknown firm: $firmId")
    if (firm.gstin.isNullOrBlank()) {
        notes += "GSTR-1 JSON: not included, the business has no GSTIN."
    } else {
        ReportPack.months(period).forEach { month ->
            onProgress(Progress("Preparing GSTR-1 JSON ${month.returnPeriod}"))
            val g = Gstr1.build(loadReportData(firmId, month, ReportKind.GSTR1))
            files += ReportFile("GSTR1-${firm.gstin}-${month.returnPeriod}.json", g.toJson())
            g.skipped.forEach { notes += "GSTR-1 ${month.returnPeriod}: $it" }
        }
        if (ReportPack.months(period).let { it.first().from != period.from || it.last().to != period.to }) {
            notes += "GSTR-1 JSON covers whole calendar months; the other files cover ${period.from} to ${period.to}."
        }
    }
    onProgress(Progress("Preparing party ledgers"))
    files += ReportFile("party-ledgers-${period.from}-${period.to}.csv", partyLedgers(loadReportData(firmId, period, ReportKind.PARTY_STATEMENT)).toCsv())
    onProgress(Progress("Preparing Tally XML"))
    files += ReportFile("Tally-${period.from}-${period.to}.xml", buildString { writeTallyXml(firmId, period, this, onProgress) })
    files += ReportFile(
        "README.txt",
        (listOf("${firm.name}: reports for ${period.from} to ${period.to}.", "") + files.map { it.name } + listOf("") + notes).joinToString("\r\n", postfix = "\r\n"),
    )
    return files
}

/** Every party statement with an opening balance or entries in the period, one table with a Party column. */
fun partyLedgers(data: ReportData): ReportTable {
    val active = data.partyMovements.filter { it.date <= data.to }.map { it.partyId }.toSet()
    val tables = data.parties.filter { it.id in active || it.signedOpening.paise != 0L }.sortedBy { it.name.lowercase() }
        .map { it.name to Reports.partyStatement(data, it.id) }
    // Same columns as [Reports.partyStatement], with the party first.
    val columns = listOf(
        ReportColumn("party", "Party"), ReportColumn("date", "Date", ColumnKind.DATE), ReportColumn("type", "Type"), ReportColumn("number", "Number"),
        ReportColumn("debit", "Debit", ColumnKind.MONEY), ReportColumn("credit", "Credit", ColumnKind.MONEY), ReportColumn("balance", "Balance", ColumnKind.MONEY),
    )
    return ReportTable(
        "Party ledgers",
        columns,
        tables.flatMap { (name, t) -> t.rows.map { listOf(name) + it } + listOfNotNull(t.totals?.let { listOf(name) + it }) },
        notes = listOf("Positive balance = the party owes the business."),
    )
}

/**
 * Minimal ZIP writer (stored entries, no compression): enough for text report bundles and
 * readable by every unzip tool. [files] names must be unique.
 */
object ZipWriter {
    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc32(bytes: ByteArray): Int {
        var c = -1
        for (b in bytes) c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    fun write(files: List<Pair<String, ByteArray>>): ByteArray {
        require(files.map { it.first }.toSet().size == files.size) { "Duplicate file names in the bundle" }
        val out = Bytes()
        val central = Bytes()
        files.forEach { (name, data) ->
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val offset = out.size
            // Local header: version 20, UTF-8 names (bit 11), stored, 1980-01-01 00:00.
            out.int(0x04034b50); out.short(20); out.short(0x0800); out.short(0); out.short(0); out.short(0x21)
            out.int(crc); out.int(data.size); out.int(data.size); out.short(nameBytes.size); out.short(0)
            out.bytes(nameBytes); out.bytes(data)
            central.int(0x02014b50); central.short(20); central.short(20); central.short(0x0800); central.short(0); central.short(0); central.short(0x21)
            central.int(crc); central.int(data.size); central.int(data.size); central.short(nameBytes.size); central.short(0); central.short(0)
            central.short(0); central.short(0); central.int(0); central.int(offset); central.bytes(nameBytes)
        }
        val cdOffset = out.size
        val cd = central.toByteArray()
        out.bytes(cd)
        out.int(0x06054b50); out.short(0); out.short(0); out.short(files.size); out.short(files.size)
        out.int(cd.size); out.int(cdOffset); out.short(0)
        return out.toByteArray()
    }

    private class Bytes {
        private var buf = ByteArray(1024)
        var size = 0
            private set

        private fun ensure(n: Int) {
            if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }

        fun bytes(b: ByteArray) {
            ensure(b.size)
            b.copyInto(buf, size)
            size += b.size
        }

        fun short(v: Int) = bytes(byteArrayOf(v.toByte(), (v ushr 8).toByte()))
        fun int(v: Int) = bytes(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
        fun toByteArray() = buf.copyOf(size)
    }
}

/** Zips [files] (see [ZipWriter]). */
fun List<ReportFile>.zipBytes(): ByteArray = ZipWriter.write(map { it.name to it.bytes })
