package app.openbill.cli

import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.usecase.CancelVoucher
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.domain.usecase.RestoreVoucher
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
import app.openbill.core.domain.model.ExportType
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.isOrder
import app.openbill.ui.invoice.DocFieldRules
import app.openbill.ui.invoice.DocFieldsForm
import app.openbill.ui.invoice.DraftLine
import app.openbill.ui.invoice.InvoiceDocument
import app.openbill.ui.invoice.InvoiceMath
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
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
    InvoiceList(cli), InvoiceShow(cli), InvoiceCreate(cli), InvoicePost(cli), InvoiceCancel(cli), InvoiceRestore(cli),
    InvoiceDelete(cli), InvoiceRecycleBin(cli), InvoicePdfCmd(cli),
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
    val base: Map<String, Any?> = mapOf(
        "id" to v.id, "number" to v.number, "date" to v.date, "status" to v.status.name.lowercase(),
        "partyId" to v.partyId, "partyName" to party?.name, "placeOfSupply" to v.placeOfSupplyStateCode,
        "subtotal" to v.subtotal, "discount" to v.discount, "taxable" to v.taxable, "tax" to v.tax,
        "roundOff" to v.roundOff, "total" to v.total, "notes" to v.notes, "cancellationReason" to v.cancellationReason,
    ) + buildMap<String, Any?> {
        // Document fields appear only when set, so older scripts see the same keys.
        if (v.reverseCharge) put("reverseCharge", true)
        v.export?.let { put("exportType", it.type.code) }
        v.currency?.let { put("currency", it); put("exchangeRate", v.exchangeRate!!.format()) }
        v.dueDate?.let { put("dueDate", it) }
        if (v.type.isOrder()) {
            put("orderStatus", Orders.status(v).name.lowercase())
            v.shortCloseNote?.let { put("shortCloseNote", it) }
        }
        v.detailRows().takeIf { it.isNotEmpty() }?.let { rows -> put("details", jsonOf(*rows.toTypedArray())) }
    }
    val lines = if (!withLines) emptyMap() else mapOf(
        "lines" to v.lines.map {
            jsonOf(
                *listOfNotNull(
                    "lineNumber" to it.lineNumber, "itemId" to it.itemId, "description" to it.description, "hsnSac" to it.hsnSac,
                    "quantity" to it.quantity.format(), "rate" to it.rate, "discount" to it.discount, "gstRate" to it.taxRate.percent(),
                    "inclusive" to (it.taxMode == TaxMode.INCLUSIVE), "taxable" to it.taxable, "tax" to it.tax, "total" to it.total,
                    ("freeQuantity" to it.freeQuantity.format()).takeIf { _ -> it.freeQuantity.scaledUnits > 0 },
                    ("foreignRate" to it.foreignRate).takeIf { _ -> it.foreignRate != null },
                    ("lineId" to it.id).takeIf { _ -> v.type.isOrder() },
                    ("itcEligible" to false).takeIf { _ -> !it.itcEligible },
                ).toTypedArray(),
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
            ) + listOfNotNull(v.dueDate?.let { "Due" to it }, v.cancellationReason?.let { "Cancelled" to it }, ("Reverse charge" to "Yes").takeIf { v.reverseCharge }) + v.detailRows(),
        )
        cli.printVoucherBody(v)
    }
}

/** Lines and totals of a document; a FREE column only when some line has free units. */
fun CliState.printVoucherBody(v: Voucher) {
    val free = v.lines.any { it.freeQuantity.scaledUnits > 0 }
    out.println()
    printTable(
        listOfNotNull(Col("#", true), Col("DESCRIPTION"), Col("QTY", true), Col("FREE", true).takeIf { free }, Col("RATE", true), Col("GST%", true), Col("TAXABLE", true), Col("TAX", true), Col("TOTAL", true)),
        v.lines.map {
            listOfNotNull(
                it.lineNumber.toString(), it.description, it.quantity.format(), it.freeQuantity.format().takeIf { free },
                it.foreignRate?.let { r -> "${v.currency} ${r.format()}" } ?: it.rate.indian(), it.taxRate.percent(), it.taxable.indian(), it.tax.indian(), it.total.indian(),
            )
        },
    )
    out.println()
    printFields(
        listOfNotNull(
            "Taxable" to v.taxable.indian(), "Tax" to v.tax.indian(), "Round off" to v.roundOff.indian(), "Total" to v.total.indian(),
            ("Reverse charge" to "tax ${v.tax.indian()} payable by the recipient").takeIf { v.reverseCharge },
        ),
    )
}

/** One requested line before tax. */
data class LineSpec(
    val item: String?, val description: String?, val quantity: String, val rate: String?, val discount: String?,
    val gstRate: String?, val inclusive: Boolean, val hsn: String?, val free: String? = null,
)

/** Optional document header fields, the same set as the app's document editor (DocFieldRules). */
class DocOptions : OptionGroup("Document fields") {
    val reverseCharge by option("--reverse-charge", help = "GST payable by the recipient").flag()
    val export by option("--export", help = "Export or SEZ supply type").choice(*ExportType.entries.map { it.code.lowercase() }.toTypedArray())
    val currency by option("--currency", help = "Export invoice currency (ISO code, not INR); rates are entered in it")
    val exchangeRate by option("--exchange-rate", help = "Rupees per unit of --currency, e.g. 83.1050")
    val shippingBill by option("--shipping-bill")
    val shippingBillDate by option("--shipping-bill-date", help = "YYYY-MM-DD")
    val port by option("--port", help = "Six-character port code, e.g. INBOM1")
    val lut by option("--lut", help = "LUT or bond reference")
    val transporter by option("--transporter")
    val transporterId by option("--transporter-id")
    val transportMode by option("--transport-mode").choice(*DocFieldRules.MODES.toTypedArray(), ignoreCase = true)
    val vehicle by option("--vehicle")
    val lrNumber by option("--lr-number")
    val lrDate by option("--lr-date", help = "YYYY-MM-DD")
    val distance by option("--distance", help = "Kilometres")
    val poNumber by option("--po-number")
    val poDate by option("--po-date", help = "YYYY-MM-DD")
    val ewayBill by option("--eway-bill")
    val ecommerceGstin by option("--ecommerce-gstin")
    val fields by option("--field", help = "Extra header field LABEL=VALUE, repeatable").multiple()

    /** Flags win over the same keys in an --input document. */
    fun form(doc: JsonObject?): DocFieldsForm {
        fun s(o: JsonObject?, k: String) = o?.get(k)?.jsonPrimitive?.content
        val ex = doc?.get("export")?.jsonObject
        val tr = doc?.get("transport")?.jsonObject
        val exportCode = export ?: s(ex, "type")
        return DocFieldsForm(
            reverseCharge = reverseCharge || s(doc, "reverseCharge") == "true",
            exportType = exportCode?.let { c -> ExportType.fromCode(c.uppercase()) ?: fail(Exit.USAGE, "Unknown export type '$c' (wpay, wopay, sezwp, sezwop)") },
            currency = currency ?: s(ex, "currency").orEmpty(),
            exchangeRate = exchangeRate ?: s(ex, "exchangeRate").orEmpty(),
            shippingBillNumber = shippingBill ?: s(ex, "shippingBillNumber").orEmpty(),
            shippingBillDate = (shippingBillDate ?: s(ex, "shippingBillDate"))?.let(::parseDate),
            portCode = port ?: s(ex, "portCode").orEmpty(),
            lutReference = lut ?: s(ex, "lutReference").orEmpty(),
            transporterName = transporter ?: s(tr, "transporterName").orEmpty(),
            transporterId = transporterId ?: s(tr, "transporterId").orEmpty(),
            transportMode = (transportMode ?: s(tr, "mode"))?.let { m -> DocFieldRules.MODES.firstOrNull { it.equals(m, true) } ?: fail(Exit.USAGE, "Transport mode is Road, Rail, Air or Ship") }.orEmpty(),
            vehicleNumber = vehicle ?: s(tr, "vehicleNumber").orEmpty(),
            lrNumber = lrNumber ?: s(tr, "docNumber").orEmpty(),
            lrDate = (lrDate ?: s(tr, "docDate"))?.let(::parseDate),
            distance = distance ?: s(tr, "distanceKm").orEmpty(),
            poNumber = poNumber ?: s(doc, "poNumber").orEmpty(),
            poDate = (poDate ?: s(doc, "poDate"))?.let(::parseDate),
            ewayBillNumber = ewayBill ?: s(doc, "ewayBillNumber").orEmpty(),
            ecommerceGstin = ecommerceGstin ?: s(doc, "ecommerceGstin").orEmpty(),
            custom = fields.map { f ->
                val i = f.indexOf('=')
                if (i <= 0) fail(Exit.USAGE, "Bad --field '$f' (use LABEL=VALUE)")
                f.substring(0, i) to f.substring(i + 1)
            }.ifEmpty { doc?.get("fields")?.jsonObject?.map { (k, v) -> k to v.jsonPrimitive.content }.orEmpty() },
        )
    }
}

/** `--line SKU:QTY[@RATE][+FREE]` or an --input document's "lines" (with "freeQuantity"). */
fun lineSpecs(doc: JsonObject?, flags: List<String>): List<LineSpec> =
    doc?.get("lines")?.jsonArray?.map { e ->
        val o = e.jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.content
        LineSpec(s("item"), s("description"), s("quantity") ?: "1", s("rate"), s("discount"), s("gstRate"), s("inclusive") == "true", s("hsnSac"), s("freeQuantity"))
    } ?: flags.map { spec ->
        val m = Regex("^(.+?):([0-9.]+)(?:@([0-9.]+))?(?:\\+([0-9.]+))?$").matchEntire(spec.trim()) ?: fail(Exit.USAGE, "Bad --line '$spec' (use SKU:QTY[@RATE][+FREE])")
        LineSpec(m.groupValues[1], null, m.groupValues[2], m.groupValues[3].ifEmpty { null }, null, null, false, null, m.groupValues[4].ifEmpty { null })
    }

class InvoiceCreate(private val cli: CliState) : CliktCommand("create") {
    override fun help(context: Context) = """
        Create a draft invoice from --input FILE.json or flags; prints its id. Lines: --line "SKU:QTY[@RATE][+FREE]".
        JSON: {"party": "...", "date": "YYYY-MM-DD", "placeOfSupply": "29", "notes": "...", "reverseCharge": false,
        "export": {"type": "WOPAY", "currency": "USD", "exchangeRate": "83.10", "shippingBillNumber": "...", "portCode": "INBOM1"},
        "transport": {"transporterName": "...", "mode": "Road", "vehicleNumber": "...", "distanceKm": "120"},
        "poNumber": "...", "ewayBillNumber": "...", "fields": {"Label": "Value"},
        "lines": [{"item": "SKU", "quantity": "2", "rate": "100.00", "discount": "0", "gstRate": "18", "inclusive": false, "freeQuantity": "1"}]}
    """.trimIndent()
    private val input by option("--input", help = "JSON document").file(mustExist = true, canBeDir = false)
    private val party by option("--party")
    private val date by option("--date", help = "YYYY-MM-DD (default: today)")
    private val placeOfSupply by option("--place-of-supply", help = "Two-digit state code (default: party's state; 96 for exports)")
    private val notes by option("--notes")
    private val lineFlags by option("--line", help = "SKU:QTY[@RATE][+FREE], repeatable").multiple()
    private val post by option("--post", help = "Post right after creating").flag()
    private val doc by DocOptions()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val json = input?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrElse { e -> fail(Exit.USAGE, "Bad --input JSON: ${e.message}") } }
        fun str(key: String) = json?.get(key)?.jsonPrimitive?.content
        val lines = lineSpecs(json, lineFlags)
        if (lines.isEmpty()) fail(Exit.USAGE, "No lines; give --line or --input")
        val v = buildDraft(f, VoucherType.SALE, party ?: str("party"), date ?: str("date"), placeOfSupply ?: str("placeOfSupply"), notes ?: str("notes"), lines, doc.form(json), post)
        repos.vouchers.saveDraft(v)
        val result = if (post) PostVoucher(repos.vouchers)(v.id, now()) else v
        when {
            cli.json -> cli.printJson(invoiceJson(result, withLines = true))
            else -> cli.out.println(if (post) result.number else result.id)
        }
    }
}

/**
 * A draft of [type] built like the app's document editor (InvoiceEditorModel.save): rates default
 * to the party's special rate, else the item's sale (or, for purchases, purchase) price; totals,
 * reverse charge, zero-rated exports and foreign currency come from [InvoiceMath]; header fields
 * are checked and cleaned by [DocFieldRules].
 */
suspend fun Data.buildDraft(
    f: Firm, type: VoucherType, partyRef: String?, dateText: String?, pos: String?, notes: String?,
    specs: List<LineSpec>, fields: DocFieldsForm = DocFieldsForm(), post: Boolean = false,
): Voucher {
    val settings = repos.firms.findSettings(f.id) ?: FirmSettings(f.id)
    val party = partyRef?.let { findParty(f.id, it) }
    val (date, fyId) = dateAndYear(f, dateText)
    if (fields.reverseCharge && !DocFieldRules.supportsReverseCharge(type)) fail(Exit.USAGE, "Reverse charge does not apply to a ${type.label()}")
    if (fields.exportType != null && !DocFieldRules.supportsExport(type)) fail(Exit.USAGE, "Export details do not apply to a ${type.label()}")
    if (fields.exportType == null && (fields.currency.isNotBlank() || fields.exchangeRate.isNotBlank())) fail(Exit.USAGE, "--currency needs --export")
    if (fields.currency.isNotBlank() && fields.exchangeRate.isBlank()) fail(Exit.USAGE, "--currency needs --exchange-rate")
    DocFieldRules.validate(fields, type, party, post).takeIf { it.isNotEmpty() }?.let { fail(Exit.VALIDATION, it.values.joinToString("; ")) }
    val export = DocFieldRules.export(fields)
    val fx = export?.exchangeRate
    // Exports (not SEZ) are supplies to place 96, other countries.
    val place = pos ?: if (export != null && !export.type.sez) "96" else party?.shipToStateCode() ?: party?.stateCode ?: party?.gstin?.take(2) ?: f.stateCode
    val purchase = type == VoucherType.PURCHASE || type == VoucherType.PURCHASE_ORDER
    val drafts = specs.map { s ->
        val item = s.item?.let { findItem(f.id, it) }
        val qty = runCatching { Quantity.parse(s.quantity) }.getOrElse { fail(Exit.VALIDATION, "Invalid quantity '${s.quantity}'") }
        if (qty <= Quantity.of(0)) fail(Exit.VALIDATION, "Enter a quantity above zero")
        val free = s.free?.let { runCatching { Quantity.parse(it) }.getOrElse { _ -> fail(Exit.VALIDATION, "Invalid free quantity '$it'") } } ?: Quantity.of(0)
        val list = item?.let { i -> if (purchase) i.purchasePrice else party?.let { p -> repos.parties.findPrice(p.id, i.id)?.price } ?: i.salePrice }
        val rate = s.rate?.let(::parseMoney) ?: list?.let { if (fx != null) InvoiceMath.fromInr(it, fx) else it } ?: fail(Exit.VALIDATION, "Line needs a rate")
        val gst = s.gstRate?.let { runCatching { parseRate(it) }.getOrElse { _ -> fail(Exit.VALIDATION, "Invalid GST rate '$it'") } }
            ?: item?.taxRate ?: Rate.ofBasisPoints(0)
        val desc = s.description ?: item?.name ?: fail(Exit.VALIDATION, "Line needs an item or a description")
        val gross = InvoiceMath.gross(qty, rate)
        val discount = s.discount?.trim()?.let { d ->
            if (d.endsWith("%")) InvoiceMath.percentOf(gross, runCatching { parseRate(d.removeSuffix("%")) }.getOrElse { _ -> fail(Exit.VALIDATION, "Invalid discount '$d'") }) else parseMoney(d)
        } ?: Money.ZERO
        if (discount > gross) fail(Exit.VALIDATION, "Discount cannot exceed the line value")
        DraftLine(
            key = cli.env.newId(), itemId = item?.id, description = desc, hsnSac = s.hsn ?: item?.hsnSac, unit = null,
            quantity = qty, rate = rate, discount = discount, gstRate = gst,
            taxMode = if (s.inclusive) TaxMode.INCLUSIVE else TaxMode.EXCLUSIVE, freeQuantity = free,
        )
    }
    val reverse = fields.reverseCharge
    val summary = runCatching {
        InvoiceMath.summarize(drafts, f.stateCode, place, settings.roundToRupee && fx == null, settings.roundingMode, reverseCharge = reverse, export = export?.type, exchangeRate = fx)
    }.getOrElse { fail(Exit.VALIDATION, it.message ?: "Cannot compute totals") }
    val lines = InvoiceMath.toVoucherLines(drafts, summary, settings.defaultGodownId, export?.type, fx) { it.key }
    val now = now()
    return Voucher(
        id = cli.env.newId(), firmId = f.id, financialYearId = fyId, type = type, date = date,
        dueDate = if (type == VoucherType.SALE) party?.dueDateFor(date) else null,
        partyId = party?.id, status = VoucherStatus.DRAFT, placeOfSupplyStateCode = place, lines = lines,
        subtotal = summary.gross, discount = summary.discount, taxable = summary.taxable + summary.chargesTaxable,
        tax = summary.totalTax, roundOff = summary.roundOff, total = summary.grandTotal,
        notes = notes?.trim()?.ifBlank { null }, createdAt = now, updatedAt = now,
        reverseCharge = reverse, transport = DocFieldRules.transport(fields), export = export,
        ecommerceGstin = DocFieldRules.ecommerceGstin(fields).takeIf { DocFieldRules.supportsEcommerce(type) },
        poNumber = DocFieldRules.clean(fields.poNumber).ifEmpty { null }, poDate = fields.poDate,
        ewayBillNumber = fields.ewayBillNumber.filterNot { it == ' ' }.ifEmpty { null }, customFields = DocFieldRules.custom(fields),
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

/**
 * `invoice restore REF --reason TEXT`, as the app does: a cancelled invoice is restored (stock and
 * ledger re-applied, reason audited); a deleted draft comes back from the recycle bin.
 */
class InvoiceRestore(private val cli: CliState) : CliktCommand("restore") {
    override fun help(context: Context) = "Restore a cancelled invoice (reason required) or a deleted draft from the recycle bin"
    private val ref by argument()
    private val reason by option("--reason", help = "Why it is restored (required for cancelled invoices)")

    override fun run() = cli.withData(write = true) {
        val v = findInvoice(firm().id, ref)
        val restored = when {
            v.deletedAt != null -> repos.vouchers.restoreDraft(v.id, now())
            v.status == VoucherStatus.CANCELLED -> {
                val why = reason?.takeIf { it.isNotBlank() } ?: fail(Exit.USAGE, "restore needs --reason")
                cli.confirm("Restore cancelled invoice ${v.number} (${v.total.indian()})?")
                RestoreVoucher(repos.vouchers)(v.id, v.cancellationReason ?: "Cancelled", why, now())
            }
            else -> fail(Exit.CONFLICT, "Invoice ${v.number ?: v.id} is ${v.status.name.lowercase()}; only cancelled invoices and deleted drafts can be restored")
        }
        if (cli.json) cli.printJson(invoiceJson(restored, withLines = false))
        else if (cli.quiet) cli.out.println(restored.number ?: restored.id)
        else cli.out.println("Restored ${restored.number ?: restored.id}")
    }
}

/** `invoice delete REF`: a draft goes to the recycle bin (posted invoices are cancelled instead). */
class InvoiceDelete(private val cli: CliState) : CliktCommand("delete") {
    override fun help(context: Context) = "Move a draft invoice to the recycle bin"
    private val ref by argument()

    override fun run() = cli.withData(write = true) {
        val v = findInvoice(firm().id, ref)
        if (v.status != VoucherStatus.DRAFT || v.deletedAt != null) fail(Exit.CONFLICT, "Only drafts can be deleted; cancel posted invoices")
        repos.vouchers.softDeleteDraft(v.id, now())
        if (cli.json) cli.printJson(jsonOf("id" to v.id, "deleted" to true)) else cli.out.println(v.id)
    }
}

/** `invoice recycle-bin [--purge ID --yes]`: deleted drafts, newest first; purge removes one for good. */
class InvoiceRecycleBin(private val cli: CliState) : CliktCommand("recycle-bin") {
    override fun help(context: Context) = "List deleted drafts (all document types); --purge ID removes one permanently"
    private val purge by option("--purge", help = "Deleted draft id to remove permanently")

    override fun run() = cli.withData(write = purge != null) {
        val f = firm()
        val bin = repos.vouchers.observeRecycleBin(f.id).first()
        purge?.let { id ->
            val v = bin.firstOrNull { it.id == id } ?: fail(Exit.NOT_FOUND, "No deleted draft '$id' in the recycle bin")
            cli.confirm("Permanently remove draft ${v.id} (${v.total.indian()})?")
            repos.vouchers.purgeDraft(v.id)
            if (cli.json) cli.printJson(jsonOf("id" to v.id, "purged" to true)) else cli.out.println(v.id)
            return@withData
        }
        when {
            cli.json -> cli.printJson(toJson(bin.map { jsonOf("id" to it.id, "type" to it.type.label(), "date" to it.date, "total" to it.total, "deletedAt" to it.deletedAt?.toString()) }))
            cli.quiet -> bin.forEach { cli.out.println(it.id) }
            else -> cli.printTable(listOf(Col("ID"), Col("TYPE"), Col("DATE"), Col("TOTAL", true), Col("DELETED")), bin.map { listOf(it.id, it.type.label(), it.date.toString(), it.total.indian(), it.deletedAt.toString()) })
        }
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
