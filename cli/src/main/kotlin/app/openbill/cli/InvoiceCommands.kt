package app.openbill.cli

import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.usecase.CancelVoucher
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.InvoiceTaxCalculator
import app.openbill.core.tax.InvoiceTaxInput
import app.openbill.core.tax.TaxContext
import app.openbill.core.tax.TaxLineInput
import app.openbill.core.tax.TaxMode
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.pdf.InvoiceLayoutEngine
import app.openbill.ui.pdf.InvoiceStyle
import app.openbill.ui.pdf.InvoiceTemplate
import app.openbill.ui.pdf.LayoutInput
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.PdfBoxPainter
import app.openbill.ui.pdf.PdfFonts
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun invoiceCommands(cli: CliState) = Group("invoice", "Sales invoices").subcommands(
    InvoiceList(cli), InvoiceShow(cli), InvoiceCreate(cli), InvoicePost(cli), InvoiceCancel(cli), InvoicePdfCmd(cli),
)

private val ALL_FROM = LocalDate(1900, 1, 1)
private val ALL_TO = LocalDate(9999, 12, 31)

suspend fun Data.invoices(firmId: String, from: LocalDate = ALL_FROM, to: LocalDate = ALL_TO): List<Voucher> =
    repos.vouchers.observeVouchers(firmId, from, to).first()
        .filter { it.type == VoucherType.SALE && it.deletedAt == null }
        .sortedWith(compareBy({ it.date }, { it.number ?: "￿" }, { it.id }))

/** Sale invoice headers (no lines), oldest first, matched in SQL like the app's list. */
suspend fun Data.invoiceHeaders(firmId: String, status: VoucherStatus? = null, query: String = "", limit: Int = Int.MAX_VALUE): List<Voucher> =
    repos.vouchers.observeVoucherPage(firmId, setOf(VoucherType.SALE), status, query, limit).first()
        .sortedWith(compareBy({ it.date }, { it.number ?: "￿" }, { it.id }))

/** By id or invoice number. */
suspend fun Data.findInvoice(firmId: String, ref: String): Voucher =
    repos.vouchers.findVoucher(ref)?.takeIf { it.firmId == firmId && it.type == VoucherType.SALE }
        ?: invoiceHeaders(firmId, query = ref).filter { it.number.equals(ref, true) }.let {
            when (it.size) {
                0 -> fail(Exit.NOT_FOUND, "No invoice '$ref'")
                1 -> repos.vouchers.findVoucher(it.single().id)!!
                else -> fail(Exit.USAGE, "Invoice number '$ref' is ambiguous; use its id")
            }
        }

suspend fun Data.invoiceJson(v: Voucher, withLines: Boolean): JsonObject {
    val party = v.partyId?.let { repos.parties.findParty(it) }
    val base = mapOf(
        "id" to v.id, "number" to v.number, "date" to v.date, "status" to v.status.name.lowercase(),
        "partyId" to v.partyId, "partyName" to party?.name, "placeOfSupply" to v.placeOfSupplyStateCode,
        "subtotal" to v.subtotal, "discount" to v.discount, "taxable" to v.taxable, "tax" to v.tax,
        "roundOff" to v.roundOff, "total" to v.total, "notes" to v.notes, "cancellationReason" to v.cancellationReason,
    )
    val lines = if (!withLines) emptyMap() else mapOf(
        "lines" to v.lines.map {
            jsonOf(
                "lineNumber" to it.lineNumber, "itemId" to it.itemId, "description" to it.description, "hsnSac" to it.hsnSac,
                "quantity" to it.quantity.format(), "rate" to it.rate, "discount" to it.discount, "gstRate" to it.taxRate.percent(),
                "inclusive" to (it.taxMode == TaxMode.INCLUSIVE), "taxable" to it.taxable, "tax" to it.tax, "total" to it.total,
            )
        },
    )
    return JsonObject((base + lines).mapValues { toJson(it.value) })
}

class InvoiceList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List sales invoices"
    private val from by option("--from", help = "YYYY-MM-DD")
    private val to by option("--to", help = "YYYY-MM-DD")
    private val status by option("--status").choice("draft", "posted", "cancelled")
    private val search by option("--search", help = "Number, reference or party name contains this")
    private val limit by option("--limit", help = "Only the newest N matches").int()

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val fromDate = from?.let(::parseDate) ?: ALL_FROM
        val toDate = to?.let(::parseDate) ?: ALL_TO
        val list = invoiceHeaders(f.id, status?.let { VoucherStatus.valueOf(it.uppercase()) }, search.orEmpty(), limit ?: Int.MAX_VALUE)
            .filter { it.date in fromDate..toDate }
        val names = HashMap<String, String?>()
        list.mapNotNull { it.partyId }.distinct().forEach { id -> names[id] = repos.parties.findParty(id)?.name }
        when {
            cli.json -> cli.printJson(toJson(list.map { invoiceJson(it, withLines = false) }))
            cli.quiet -> list.forEach { cli.out.println(it.number ?: it.id) }
            else -> cli.printTable(
                listOf(Col("NUMBER"), Col("DATE"), Col("PARTY"), Col("STATUS"), Col("TOTAL", true), Col("ID")),
                list.map { listOf(it.number ?: "(draft)", it.date.toString(), it.partyId?.let(names::get) ?: "-", it.status.name.lowercase(), it.total.indian(), it.id) },
            )
        }
    }
}

class InvoiceShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show an invoice by number or id"
    private val ref by argument()

    override fun run() = cli.withData(write = false) {
        val v = findInvoice(firm().id, ref)
        if (cli.json) return@withData cli.printJson(invoiceJson(v, withLines = true))
        val party = v.partyId?.let { repos.parties.findParty(it) }
        cli.printFields(
            listOf(
                "Number" to (v.number ?: "(draft)"), "Id" to v.id, "Date" to v.date, "Party" to party?.name,
                "Status" to v.status.name.lowercase(), "Place of supply" to v.placeOfSupplyStateCode,
            ) + listOfNotNull(v.cancellationReason?.let { "Cancelled" to it }),
        )
        cli.out.println()
        cli.printTable(
            listOf(Col("#", true), Col("DESCRIPTION"), Col("QTY", true), Col("RATE", true), Col("GST%", true), Col("TAXABLE", true), Col("TAX", true), Col("TOTAL", true)),
            v.lines.map { listOf(it.lineNumber.toString(), it.description, it.quantity.format(), it.rate.indian(), it.taxRate.percent(), it.taxable.indian(), it.tax.indian(), it.total.indian()) },
        )
        cli.out.println()
        cli.printFields(listOf("Taxable" to v.taxable.indian(), "Tax" to v.tax.indian(), "Round off" to v.roundOff.indian(), "Total" to v.total.indian()))
    }
}

/** One requested line before tax. */
data class LineSpec(val item: String?, val description: String?, val quantity: String, val rate: String?, val discount: String?, val gstRate: String?, val inclusive: Boolean, val hsn: String?)

class InvoiceCreate(private val cli: CliState) : CliktCommand("create") {
    override fun help(context: Context) = """
        Create a draft invoice from --input FILE.json or flags; prints its id. Lines: --line "SKU:QTY[@RATE]".
        JSON: {"party": "...", "date": "YYYY-MM-DD", "placeOfSupply": "29", "notes": "...",
        "lines": [{"item": "SKU", "quantity": "2", "rate": "100.00", "discount": "0", "gstRate": "18", "inclusive": false}]}
    """.trimIndent()
    private val input by option("--input", help = "JSON document").file(mustExist = true, canBeDir = false)
    private val party by option("--party")
    private val date by option("--date", help = "YYYY-MM-DD (default: today)")
    private val placeOfSupply by option("--place-of-supply", help = "Two-digit state code (default: party's state)")
    private val notes by option("--notes")
    private val lineSpecs by option("--line", help = "SKU:QTY[@RATE], repeatable").multiple()
    private val post by option("--post", help = "Post right after creating").flag()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val doc = input?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        fun str(key: String) = doc?.get(key)?.jsonPrimitive?.content
        val lines = doc?.get("lines")?.jsonArray?.map { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            LineSpec(s("item"), s("description"), s("quantity") ?: "1", s("rate"), s("discount"), s("gstRate"), s("inclusive") == "true", s("hsnSac"))
        } ?: lineSpecs.map { spec ->
            val m = Regex("^(.+?):([0-9.]+)(?:@([0-9.]+))?$").matchEntire(spec.trim()) ?: fail(Exit.USAGE, "Bad --line '$spec' (use SKU:QTY[@RATE])")
            LineSpec(m.groupValues[1], null, m.groupValues[2], m.groupValues[3].ifEmpty { null }, null, null, false, null)
        }
        if (lines.isEmpty()) fail(Exit.USAGE, "No lines; give --line or --input")
        val v = buildDraft(f, party ?: str("party"), date ?: str("date"), placeOfSupply ?: str("placeOfSupply"), notes ?: str("notes"), lines)
        repos.vouchers.saveDraft(v)
        val result = if (post) PostVoucher(repos.vouchers)(v.id, now()) else v
        when {
            cli.json -> cli.printJson(invoiceJson(result, withLines = true))
            else -> cli.out.println(if (post) result.number else result.id)
        }
    }
}

suspend fun Data.buildDraft(f: Firm, partyRef: String?, dateText: String?, pos: String?, notes: String?, specs: List<LineSpec>): Voucher {
    val settings = repos.firms.findSettings(f.id)
    val party = partyRef?.let { findParty(f.id, it) }
    val date = dateText?.let(::parseDate) ?: now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val fy = repos.firms.observeFinancialYears(f.id).first().firstOrNull { date >= it.startDate && date <= it.endDate }
        ?: fail(Exit.VALIDATION, "No financial year covers $date")
    val place = pos ?: party?.shipToStateCode() ?: party?.stateCode ?: f.stateCode
    val resolved = specs.mapIndexed { index, s ->
        val item = s.item?.let { findItem(f.id, it) }
        val qty = runCatching { Quantity.parse(s.quantity) }.getOrElse { fail(Exit.VALIDATION, "Invalid quantity '${s.quantity}'") }
        val rate = s.rate?.let(::parseMoney) ?: item?.salePrice ?: fail(Exit.VALIDATION, "Line needs a rate")
        val gst = s.gstRate?.let { runCatching { parseRate(it) }.getOrElse { _ -> fail(Exit.VALIDATION, "Invalid GST rate '$it'") } }
            ?: item?.taxRate ?: Rate.ofBasisPoints(0)
        val desc = s.description ?: item?.name ?: fail(Exit.VALIDATION, "Line needs an item or a description")
        val gross = Money.of(Rounding.multiplyDivide(rate.paise, qty.scaledUnits, 10_000L, RoundingMode.HALF_UP))
        Triple(s, item, VoucherLine(
            id = cli.env.newId(), lineNumber = index + 1, itemId = item?.id, description = desc, hsnSac = s.hsn ?: item?.hsnSac,
            quantity = qty, rate = rate, discount = s.discount?.let(::parseMoney) ?: Money.ZERO, taxRate = gst,
            taxMode = if (s.inclusive) TaxMode.INCLUSIVE else TaxMode.EXCLUSIVE, godownId = settings?.defaultGodownId,
        )) to gross
    }
    val result = InvoiceTaxCalculator.calculate(
        InvoiceTaxInput(
            lines = resolved.map { (t, gross) -> TaxLineInput(grossAmount = gross, discount = t.third.discount, gstRate = t.third.taxRate, mode = t.third.taxMode) },
            roundToRupee = settings?.roundToRupee ?: true,
        ),
        TaxContext(f.stateCode, place),
        settings?.roundingMode ?: RoundingMode.HALF_UP,
    )
    val lines = resolved.mapIndexed { i, (t, _) ->
        val r = result.lines[i]
        t.third.copy(taxable = r.taxableValue, tax = r.totalTax, total = r.totalAmount)
    }
    val now = now()
    return Voucher(
        id = cli.env.newId(), firmId = f.id, financialYearId = fy.id, type = VoucherType.SALE, date = date,
        partyId = party?.id, status = VoucherStatus.DRAFT, placeOfSupplyStateCode = place, lines = lines,
        subtotal = resolved.fold(Money.ZERO) { a, p -> a + p.second }, discount = lines.fold(Money.ZERO) { a, l -> a + l.discount },
        taxable = result.totalTaxable, tax = result.totalTax, roundOff = result.roundOff, total = result.grandTotal,
        notes = notes?.trim()?.ifBlank { null }, createdAt = now, updatedAt = now,
    )
}

class InvoicePost(private val cli: CliState) : CliktCommand("post") {
    override fun help(context: Context) = "Post a draft (assigns the number; irreversible except by cancel)"
    private val ref by argument()

    override fun run() = cli.withData(write = true) {
        val v = findInvoice(firm().id, ref)
        if (v.status != VoucherStatus.DRAFT) fail(Exit.CONFLICT, "Invoice ${v.number ?: v.id} is ${v.status.name.lowercase()}, not a draft")
        val posted = PostVoucher(repos.vouchers)(v.id, now())
        if (cli.json) cli.printJson(invoiceJson(posted, withLines = false))
        else if (cli.quiet) cli.out.println(posted.number)
        else cli.out.println("Posted ${posted.number}  total ${posted.total.indian()}")
    }
}

class InvoiceCancel(private val cli: CliState) : CliktCommand("cancel") {
    override fun help(context: Context) = "Cancel a posted invoice (reverses stock and ledger)"
    private val ref by argument()
    private val reason by option("--reason").required()

    override fun run() = cli.withData(write = true) {
        val v = findInvoice(firm().id, ref)
        if (v.status != VoucherStatus.POSTED) fail(Exit.CONFLICT, "Invoice ${v.number ?: v.id} is ${v.status.name.lowercase()}, not posted")
        cli.confirm("Cancel invoice ${v.number} (${v.total.indian()})?")
        val cancelled = CancelVoucher(repos.vouchers)(v.id, reason, now())
        if (cli.json) cli.printJson(invoiceJson(cancelled, withLines = false))
        else if (cli.quiet) cli.out.println(cancelled.number)
        else cli.out.println("Cancelled ${cancelled.number}")
    }
}

fun parseDate(text: String): LocalDate = runCatching { LocalDate.parse(text.trim()) }
    .getOrElse { fail(Exit.USAGE, "Invalid date '$text' (use YYYY-MM-DD)") }

/** `invoice pdf REF [--template ID] [--out FILE]`: the same layout engine and PDFBox painter as the desktop app. */
class InvoicePdfCmd(private val cli: CliState) : CliktCommand("pdf") {
    override fun help(context: Context) = "Write an invoice as PDF (default file: <title>-<number>.pdf)"
    private val ref by argument()
    private val template by option("--template", help = "Layout id (default: ${InvoiceTemplate.DEFAULT.id})")
        .choice(*InvoiceTemplate.entries.map { it.id }.toTypedArray())
    private val out by option("--out", help = "PDF file").file(canBeDir = false)

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val v = findInvoice(f.id, ref)
        val target = out ?: File("${invoiceDocument(f, v).fileStem}.pdf")
        writeAtomically(target, invoicePdf(f, v, InvoiceTemplate.fromId(template)))
        if (cli.json) cli.printJson(jsonOf("number" to v.number, "path" to target.path))
        else if (cli.quiet) cli.out.println(target.path)
        else cli.out.println("Wrote ${target.path}")
    }
}

suspend fun Data.invoiceDocument(f: Firm, v: Voucher): InvoiceDocument {
    val units = units(f.id)
    val unitsByItem = v.lines.mapNotNull { it.itemId }.distinct()
        .mapNotNull { id -> repos.catalog.findItem(id)?.let { item -> units.symbolOf(item.unitId)?.let { id to it } } }.toMap()
    return InvoiceDocument.build(v, f, repos.firms.findSettings(f.id) ?: FirmSettings(f.id), v.partyId?.let { repos.parties.findParty(it) }, unitsByItem)
}

suspend fun Data.invoicePdf(f: Firm, v: Voucher, template: InvoiceTemplate): ByteArray {
    // Logo and signature from the desktop app's data directory (DesktopPlatformServices.imageFile).
    fun image(suffix: String): LogoImage? {
        val file = File(File(dataDir, "logos"), f.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "$suffix.png")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val img = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
        return LogoImage(bytes, img.width, img.height)
    }
    val layout = InvoiceLayoutEngine.layout(LayoutInput(invoiceDocument(f, v), InvoiceStyle(template = template), PdfFonts.metrics, image(""), image("-signature")))
    return PdfBoxPainter.toPdf(layout)
}

fun writeAtomically(target: File, bytes: ByteArray) {
    target.absoluteFile.parentFile.mkdirs()
    val tmp = File(target.absoluteFile.parentFile, target.name + ".tmp")
    tmp.writeBytes(bytes)
    java.nio.file.Files.move(tmp.toPath(), target.absoluteFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}
