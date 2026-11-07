package app.openbill.cli

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.OrderStatus
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.orderTarget
import app.openbill.core.domain.usecase.CancelVoucher
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.ui.invoice.DocFieldRules
import app.openbill.ui.invoice.InvoiceMath
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `order list|show|create|post|convert|short-close|cancel`: sales and purchase orders as in the app. */
fun orderCommands(cli: CliState) = Group("order", "Sales and purchase orders: create, convert (in part) to invoices or bills, short-close").subcommands(
    OrderList(cli), OrderShow(cli), OrderCreate(cli), OrderPost(cli), OrderConvert(cli), OrderShortClose(cli), OrderCancel(cli),
)

private val ORDER_TYPES = setOf(VoucherType.SALES_ORDER, VoucherType.PURCHASE_ORDER)

private fun orderType(kind: String) = if (kind == "purchase") VoucherType.PURCHASE_ORDER else VoucherType.SALES_ORDER

private suspend fun Data.findOrder(ref: String) = findDocument(firm().id, ref, ORDER_TYPES, "order")

class OrderList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List orders, newest last, with their fulfilment status"
    private val type by option("--type").choice("sales", "purchase", "all").default("all")
    private val status by option("--status").choice(*OrderStatus.entries.map { it.name.lowercase() }.toTypedArray())
    private val search by option("--search", help = "Number, reference or party name contains this")

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val types = if (type == "all") ORDER_TYPES else setOf(orderType(type))
        val list = invoiceHeaders(f.id, query = search.orEmpty(), types = types).filter { status == null || Orders.status(it).name.lowercase() == status }
        val names = list.mapNotNull { it.partyId }.distinct().associateWith { repos.parties.findParty(it)?.name }
        when {
            cli.json -> cli.printJson(toJson(list.map { invoiceJson(it, withLines = false) + ("type" to it.type.label()) }))
            cli.quiet -> list.forEach { cli.out.println(it.number ?: it.id) }
            else -> cli.printTable(
                listOf(Col("NUMBER"), Col("TYPE"), Col("DATE"), Col("PARTY"), Col("STATUS"), Col("TOTAL", true), Col("ID")),
                list.map { listOf(it.number ?: "(draft)", it.type.label(), it.date.toString(), it.partyId?.let(names::get) ?: "-", Orders.status(it).name.lowercase(), it.total.indian(), it.id) },
            )
        }
    }
}

private operator fun JsonObject.plus(pair: Pair<String, Any?>) = JsonObject(this + mapOf(pair.first to toJson(pair.second)))

class OrderShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show an order with delivered and pending quantity per line"
    private val ref by argument()

    override fun run() = cli.withData(write = false) {
        val o = findOrder(ref)
        val conversions = repos.vouchers.observeConversions(o.id).first()
        val done = Orders.converted(conversions)
        val pending = Orders.pending(o, conversions)
        val closed = Orders.shortClosed(o, conversions)
        if (cli.json) {
            val base = invoiceJson(o, withLines = true) + ("type" to o.type.label())
            cli.printJson(
                base + ("progress" to o.lines.map { l ->
                    jsonOf("lineId" to l.id, "lineNumber" to l.lineNumber, "ordered" to l.quantity.format(), "delivered" to (done[l.id] ?: Quantity.of(0)).format(),
                        "pending" to (if (o.shortClosedAt != null) Quantity.of(0) else pending.getValue(l.id)).format(), "shortClosed" to closed[l.id]?.format())
                }) + ("conversions" to conversions.map { jsonOf("id" to it.id, "number" to it.number, "status" to it.status.name.lowercase(), "total" to it.total) }),
            )
            return@withData
        }
        val party = o.partyId?.let { repos.parties.findParty(it) }
        cli.printFields(
            listOf(
                "Number" to (o.number ?: "(draft)"), "Id" to o.id, "Type" to o.type.label(), "Date" to o.date, "Party" to party?.name,
                "Status" to Orders.status(o).label,
            ) + listOfNotNull(o.shortCloseNote?.let { "Closed" to it }, o.cancellationReason?.let { "Cancelled" to it }) + o.detailRows(),
        )
        cli.out.println()
        cli.printTable(
            listOf(Col("#", true), Col("DESCRIPTION"), Col("ORDERED", true), Col("DELIVERED", true), Col("PENDING", true), Col("RATE", true), Col("TOTAL", true)),
            o.lines.map {
                listOf(
                    it.lineNumber.toString(), it.description, it.quantity.format(), (done[it.id] ?: Quantity.of(0)).format(),
                    if (o.shortClosedAt != null) "closed" else pending.getValue(it.id).format(), it.rate.indian(), it.total.indian(),
                )
            },
        )
        cli.out.println()
        cli.printFields(listOf("Taxable" to o.taxable.indian(), "Tax" to o.tax.indian(), "Total" to o.total.indian()))
        if (conversions.isNotEmpty()) cli.out.println("Converted: " + conversions.joinToString { "${it.number ?: it.id} (${it.status.name.lowercase()})" })
    }
}

class OrderCreate(private val cli: CliState) : CliktCommand("create") {
    override fun help(context: Context) = "Create a draft order from --input FILE.json or flags (same as `invoice create`); prints its id"
    private val type by option("--type").choice("sales", "purchase").default("sales")
    private val input by option("--input", help = "JSON document").file(mustExist = true, canBeDir = false)
    private val party by option("--party")
    private val date by option("--date", help = "YYYY-MM-DD (default: today)")
    private val placeOfSupply by option("--place-of-supply")
    private val notes by option("--notes")
    private val lineFlags by option("--line", help = "SKU:QTY[@RATE][+FREE], repeatable").multiple()
    private val post by option("--post", help = "Issue (number) it right after creating").flag()
    private val doc by DocOptions()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val json = input?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrElse { e -> fail(Exit.USAGE, "Bad --input JSON: ${e.message}") } }
        fun str(key: String) = json?.get(key)?.jsonPrimitive?.content
        val lines = lineSpecs(json, lineFlags)
        if (lines.isEmpty()) fail(Exit.USAGE, "No lines; give --line or --input")
        val v = buildDraft(f, orderType(type), party ?: str("party"), date ?: str("date"), placeOfSupply ?: str("placeOfSupply"), notes ?: str("notes"), lines, doc.form(json), post)
        repos.vouchers.saveDraft(v)
        val result = if (post) PostVoucher(repos.vouchers)(v.id, now()) else v
        if (cli.json) cli.printJson(invoiceJson(result, withLines = true)) else cli.out.println(if (post) result.number else result.id)
    }
}

class OrderPost(private val cli: CliState) : CliktCommand("post") {
    override fun help(context: Context) = "Issue a draft order (assigns its number)"
    private val ref by argument()

    override fun run() = cli.withData(write = true) {
        val o = findOrder(ref)
        if (o.status != VoucherStatus.DRAFT) fail(Exit.CONFLICT, "Order ${o.number ?: o.id} is ${o.status.name.lowercase()}, not a draft")
        val posted = PostVoucher(repos.vouchers)(o.id, now())
        if (cli.json) cli.printJson(invoiceJson(posted, withLines = false)) else cli.out.println(posted.number)
    }
}

/**
 * `order convert REF [--qty LINE=QTY ...] [--post]`: a draft invoice (sales order) or purchase bill
 * for what is still pending, built as the app's "Convert" does (Orders.conversionLines); `--qty`
 * bills less of a line (0 leaves it out) for a partial delivery.
 */
class OrderConvert(private val cli: CliState) : CliktCommand("convert") {
    override fun help(context: Context) = "Make a draft invoice or bill from an order's pending quantity; --qty LINE=QTY for part of a line"
    private val ref by argument()
    private val qty by option("--qty", help = "Order line number=quantity to bill now, repeatable (default: all pending)").multiple()
    private val date by option("--date", help = "YYYY-MM-DD (default: today)")
    private val post by option("--post", help = "Post the invoice or bill right away").flag()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val order = findOrder(ref)
        if (order.status != VoucherStatus.POSTED) fail(Exit.CONFLICT, "Issue the order before converting it")
        if (order.shortClosedAt != null) fail(Exit.CONFLICT, "Order ${order.number} is short-closed")
        val earlier = repos.vouchers.observeConversions(order.id).first()
        var lines = Orders.conversionLines(order, earlier, cli.env.newId)
            ?: fail(Exit.CONFLICT, "Nothing is pending on this order: it is fully invoiced, or a draft already holds the rest")
        val byNumber = order.lines.associateBy { it.lineNumber }
        qty.forEach { spec ->
            val (n, q) = spec.split('=', limit = 2).takeIf { it.size == 2 } ?: fail(Exit.USAGE, "Bad --qty '$spec' (use LINE=QTY)")
            val src = n.trim().toIntOrNull()?.let(byNumber::get) ?: fail(Exit.NOT_FOUND, "Order has no line $n")
            val want = runCatching { Quantity.parse(q.trim()) }.getOrElse { fail(Exit.VALIDATION, "Invalid quantity '$q'") }
            val line = lines.firstOrNull { it.sourceLineId == src.id } ?: fail(Exit.CONFLICT, "Line $n has nothing pending")
            if (want > line.quantity) fail(Exit.VALIDATION, "Line $n has only ${line.quantity.format()} pending")
            if (want < Quantity.of(0)) fail(Exit.VALIDATION, "Quantity cannot be negative")
            // A part of the line takes its share of the discount; free units go only with the whole line (as in the app).
            lines = lines.mapNotNull { l ->
                if (l !== line) l else if (want.scaledUnits == 0L) null else if (want == l.quantity) l else l.copy(
                    quantity = want,
                    discount = Money.of(Rounding.multiplyDivide(src.discount.paise, want.scaledUnits, src.quantity.scaledUnits, RoundingMode.HALF_UP)),
                    foreignDiscount = src.foreignDiscount?.let { d -> Money.of(Rounding.multiplyDivide(d.paise, want.scaledUnits, src.quantity.scaledUnits, RoundingMode.HALF_UP)) },
                    freeQuantity = if (want == src.quantity) src.freeQuantity else Quantity.of(0),
                )
            }
        }
        if (lines.isEmpty()) fail(Exit.USAGE, "Nothing to bill; every line is 0")
        lines = lines.mapIndexed { i, l -> l.copy(lineNumber = i + 1) }
        val target = order.type.orderTarget()!!
        val settings = repos.firms.findSettings(f.id) ?: FirmSettings(f.id)
        val (d, fy) = dateAndYear(f, date)
        val fx = order.exchangeRate
        val export = order.export?.type
        // Charges (freight, packing) are billed once, with the first conversion of an order.
        val chargesOnce = earlier.none { it.status != VoucherStatus.CANCELLED }
        val drafts = lines.map { InvoiceMath.fromVoucherLine(it, null) }
        val charges = if (chargesOnce) order.charges.sortedBy { it.sortOrder }.map { InvoiceMath.fromVoucherCharge(it).copy(key = cli.env.newId()) } else emptyList()
        val reverse = order.reverseCharge && DocFieldRules.supportsReverseCharge(target)
        val summary = runCatching {
            InvoiceMath.summarize(drafts, f.stateCode, order.placeOfSupplyStateCode, settings.roundToRupee && fx == null, settings.roundingMode, charges, reverse, export, fx)
        }.getOrElse { fail(Exit.VALIDATION, it.message ?: "Cannot compute totals") }
        val party = order.partyId?.let { repos.parties.findParty(it) }
        val at = now()
        val draft = Voucher(
            id = cli.env.newId(), firmId = f.id, financialYearId = fy, type = target, date = d,
            dueDate = if (target == VoucherType.SALE) party?.dueDateFor(d) else null,
            partyId = order.partyId, referenceVoucherId = order.id, status = VoucherStatus.DRAFT,
            placeOfSupplyStateCode = order.placeOfSupplyStateCode,
            lines = InvoiceMath.toVoucherLines(drafts, summary, settings.defaultGodownId, export, fx) { it.key },
            charges = InvoiceMath.toVoucherCharges(charges, summary, fx) { it.key },
            subtotal = summary.gross, discount = summary.discount, taxable = summary.taxable + summary.chargesTaxable,
            tax = summary.totalTax, roundOff = summary.roundOff, total = summary.grandTotal, notes = order.notes,
            createdAt = at, updatedAt = at, reverseCharge = reverse, transport = order.transport, export = order.export,
            ecommerceGstin = order.ecommerceGstin.takeIf { DocFieldRules.supportsEcommerce(target) },
            // A bill made from our purchase order quotes it.
            poNumber = if (order.type == VoucherType.PURCHASE_ORDER) order.number else order.poNumber,
            poDate = if (order.type == VoucherType.PURCHASE_ORDER) order.date else order.poDate,
            ewayBillNumber = order.ewayBillNumber, customFields = order.customFields,
        )
        repos.vouchers.saveDraft(draft)
        val result = if (post) PostVoucher(repos.vouchers)(draft.id, now()) else draft
        if (cli.json) cli.printJson(invoiceJson(result, withLines = true) + ("type" to result.type.label()))
        else cli.out.println(if (post) result.number else result.id)
    }
}

class OrderShortClose(private val cli: CliState) : CliktCommand("short-close") {
    override fun help(context: Context) = "Close what is still pending on an issued order (no further conversions post)"
    private val ref by argument()
    private val note by option("--note", help = "Printed on the order (up to ${Orders.SHORT_CLOSE_NOTE_MAX} characters)")

    override fun run() = cli.withData(write = true) {
        val o = findOrder(ref)
        if (!Orders.canShortClose(o)) fail(Exit.CONFLICT, "Only issued orders with something pending can be short-closed (this one is ${Orders.status(o).label.lowercase()})")
        if ((note?.length ?: 0) > Orders.SHORT_CLOSE_NOTE_MAX) fail(Exit.VALIDATION, "Note is up to ${Orders.SHORT_CLOSE_NOTE_MAX} characters")
        cli.confirm("Short-close order ${o.number}?")
        val closed = repos.vouchers.shortCloseOrder(o.id, note?.trim()?.ifEmpty { null }, now())
        if (cli.json) cli.printJson(invoiceJson(closed, withLines = false)) else cli.out.println("Closed ${closed.number}")
    }
}

class OrderCancel(private val cli: CliState) : CliktCommand("cancel") {
    override fun help(context: Context) = "Cancel an issued order"
    private val ref by argument()
    private val reason by option("--reason").required()

    override fun run() = cli.withData(write = true) {
        val o = findOrder(ref)
        if (o.status != VoucherStatus.POSTED) fail(Exit.CONFLICT, "Order ${o.number ?: o.id} is ${o.status.name.lowercase()}, not issued")
        cli.confirm("Cancel order ${o.number} (${o.total.indian()})?")
        val c = CancelVoucher(repos.vouchers)(o.id, reason, now())
        if (cli.json) cli.printJson(invoiceJson(c, withLines = false)) else cli.out.println("Cancelled ${c.number}")
    }
}
