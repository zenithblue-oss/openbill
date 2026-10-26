package app.openbill.cli

import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.usecase.MoneyVoucherInput
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.domain.usecase.StockLineInput
import app.openbill.core.domain.usecase.StockVouchers
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** `payment in|out|list` and `stock summary|ledger|adjust|transfer` (docs/11-cli.md, milestone C2). */
fun paymentCommands(cli: CliState) = Group("payment", "Payments in and out, with allocation").subcommands(
    PaymentRecord(cli, VoucherType.PAYMENT_IN), PaymentRecord(cli, VoucherType.PAYMENT_OUT), PaymentList(cli),
)

fun stockCommands(cli: CliState) = Group("stock", "Stock levels, movements, adjustments and transfers").subcommands(
    ReportNamed(cli, ReportKind.STOCK_SUMMARY, "summary"), StockLedger(cli), StockChange(cli, transfer = false), StockChange(cli, transfer = true),
)

private suspend fun Data.dateAndYear(f: Firm, text: String?): Pair<LocalDate, String> {
    val date = text?.let(::parseDate) ?: now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val fy = repos.firms.observeFinancialYears(f.id).first().firstOrNull { date >= it.startDate && date <= it.endDate }
        ?: fail(Exit.VALIDATION, "No financial year covers $date")
    return date to fy.id
}

class PaymentRecord(private val cli: CliState, private val type: VoucherType) :
    CliktCommand(if (type == VoucherType.PAYMENT_IN) "in" else "out") {
    override fun help(context: Context) = if (type == VoucherType.PAYMENT_IN) "Record money received from a customer" else "Record money paid to a supplier"
    private val party by option("--party").required()
    private val amount by option("--amount").required()
    private val account by option("--account", help = "Cash or bank account name or id (default: the firm's default)")
    private val method by option("--method").choice("cash", "bank", "upi", "cheque").default("cash")
    private val reference by option("--reference", help = "Cheque, UTR or UPI reference")
    private val date by option("--date", help = "YYYY-MM-DD (default: today)")
    private val notes by option("--notes")
    private val allocate by option("--allocate", help = "INVOICE:AMOUNT, repeatable").multiple()
    private val auto by option("--auto", help = "Allocate oldest invoices first").flag()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val p = findParty(f.id, party)
        val amt = parseMoney(amount)
        repos.ledger.ensureSystemAccounts(f.id, now())
        val accounts = repos.ledger.observeAccounts(f.id).first().filter { it.deletedAt == null && (it.type == AccountType.CASH || it.type == AccountType.BANK) }
        val acc = account?.let { a -> accounts.firstOrNull { it.id == a || it.name.equals(a, true) } ?: fail(Exit.NOT_FOUND, "No cash or bank account '$a'") }
            ?: accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull() ?: fail(Exit.NOT_FOUND, "No cash or bank account")
        val open = repos.payments.outstandingInvoices(f.id, p.id, MoneyVouchers.invoiceTypeFor(type))
        val requests = if (auto) MoneyVouchers.fifo(open, amt) else allocate.map { spec ->
            val ref = spec.substringBeforeLast(':', "")
            if (ref.isEmpty()) fail(Exit.USAGE, "Bad --allocate '$spec' (use INVOICE:AMOUNT)")
            val inv = open.firstOrNull { it.voucherId == ref || it.number.equals(ref, true) } ?: fail(Exit.NOT_FOUND, "No outstanding invoice '$ref' for ${p.name}")
            AllocationRequest(inv.voucherId, parseMoney(spec.substringAfterLast(':')))
        }
        val (d, fy) = dateAndYear(f, date)
        val m = PaymentMethod.valueOf(method.uppercase())
        val v = MoneyVouchers(repos.vouchers, repos.payments, repos.ledger, PostVoucher(repos.vouchers)).record(
            MoneyVoucherInput(
                type = type, id = cli.env.newId(), firmId = f.id, financialYearId = fy, stateCode = f.stateCode, date = d,
                amount = amt, accountId = acc.id, method = m, partyId = p.id, instrumentNumber = reference,
                instrumentDate = d.takeIf { m == PaymentMethod.CHEQUE || m == PaymentMethod.BANK }, notes = notes, allocations = requests,
            ),
            now(),
        )
        val allocated = requests.fold(Money.ZERO) { a, r -> a + r.amount }
        when {
            cli.json -> cli.printJson(jsonOf("id" to v.id, "number" to v.number, "amount" to v.total, "allocated" to allocated, "advance" to (v.total - allocated)))
            cli.quiet -> cli.out.println(v.number ?: v.id)
            else -> cli.out.println("Recorded ${v.number ?: v.id}  ${v.total.indian()}  allocated ${allocated.indian()}")
        }
    }
}

class PaymentList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List payments in and out"
    private val from by option("--from")
    private val to by option("--to")

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val list = repos.vouchers.observeVouchers(f.id, from?.let(::parseDate) ?: LocalDate(1900, 1, 1), to?.let(::parseDate) ?: LocalDate(9999, 12, 31)).first()
            .filter { (it.type == VoucherType.PAYMENT_IN || it.type == VoucherType.PAYMENT_OUT) && it.deletedAt == null }
            .sortedWith(compareBy({ it.date }, { it.number ?: "" }))
        val names = parties(f.id).associate { it.id to it.name }
        fun dir(t: VoucherType) = if (t == VoucherType.PAYMENT_IN) "in" else "out"
        if (cli.json) {
            cli.printJson(toJson(list.map { jsonOf("id" to it.id, "number" to it.number, "date" to it.date, "direction" to dir(it.type), "party" to it.partyId?.let(names::get), "amount" to it.total, "status" to it.status.name.lowercase()) }))
        } else {
            cli.printTable(
                listOf(Col("NUMBER"), Col("DATE"), Col("DIR"), Col("PARTY"), Col("AMOUNT", true), Col("STATUS")),
                list.map { listOf(it.number ?: it.id, it.date.toString(), dir(it.type), it.partyId?.let(names::get) ?: "-", it.total.indian(), it.status.name.lowercase()) },
            )
        }
    }
}

class StockLedger(private val cli: CliState) : CliktCommand("ledger") {
    override fun help(context: Context) = "Movements of one item, oldest first, with running balance"
    private val item by argument(help = "Item id, SKU or name")

    override fun run() = cli.withData(write = false) {
        val f = firm()
        val i = findItem(f.id, item)
        val godowns = repos.catalog.observeGodowns(f.id).first().associate { it.id to it.name }
        var bal = Quantity.ofScaledUnits(0)
        val rows = repos.stock.observeItemMovements(f.id, i.id).first().sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id })).map { m ->
            bal += m.quantity
            val v = repos.vouchers.findVoucher(m.voucherId)
            listOf(m.date.toString(), v?.type?.name?.lowercase() ?: "-", v?.number ?: m.voucherId, godowns[m.godownId] ?: m.godownId, m.quantity.format(), bal.format())
        }
        if (cli.json) cli.printJson(toJson(rows.map { r -> jsonOf("date" to r[0], "type" to r[1], "voucher" to r[2], "godown" to r[3], "quantity" to r[4], "balance" to r[5]) }))
        else cli.printTable(listOf(Col("DATE"), Col("TYPE"), Col("VOUCHER"), Col("GODOWN"), Col("QTY", true), Col("BALANCE", true)), rows)
    }
}

/** `stock adjust ITEM --qty +5|-2 --reason TEXT` and `stock transfer ITEM --qty 3 --from A --to B`. */
class StockChange(private val cli: CliState, private val transfer: Boolean) : CliktCommand(if (transfer) "transfer" else "adjust") {
    override fun help(context: Context) = if (transfer) "Move stock between godowns" else "Increase (+QTY) or decrease (-QTY) stock with a reason"
    private val item by argument(help = "Item id, SKU or name")
    private val qty by option("--qty").required()
    private val reason by option("--reason", help = "Required for adjustments")
    private val godown by option("--godown", "--from", help = "Godown name or id (default: the firm's default)")
    private val toGodown by option("--to", help = "Destination godown (transfer)")
    private val unitCost by option("--unit-cost", help = "Cost of inward stock (default: average cost)")
    private val batch by option("--batch")
    private val expiry by option("--expiry", help = "YYYY-MM-DD")
    private val serials by option("--serial", help = "Serial number, repeatable").multiple()
    private val date by option("--date")

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val i = findItem(f.id, item)
        val godowns = repos.catalog.observeGodowns(f.id).first()
        fun gd(ref: String) = godowns.firstOrNull { it.id == ref || it.name.equals(ref, true) }?.id ?: fail(Exit.NOT_FOUND, "No godown '$ref'")
        val from = godown?.let(::gd) ?: repos.firms.findSettings(f.id)?.defaultGodownId ?: godowns.firstOrNull()?.id ?: fail(Exit.NOT_FOUND, "No godown")
        val q = runCatching { Quantity.parse(qty.removePrefix("+")) }.getOrElse { fail(Exit.VALIDATION, "Invalid quantity '$qty'") }
        val inward = q > Quantity.ofScaledUnits(0)
        val abs = if (inward) q else Quantity.ofScaledUnits(-q.scaledUnits)
        if (transfer && toGodown == null) fail(Exit.USAGE, "transfer needs --to")
        val line = StockLineInput(
            itemId = i.id, description = i.name, quantity = abs, godownId = from,
            direction = if (inward) StockDirection.INWARD else StockDirection.OUTWARD,
            unitCost = unitCost?.let(::parseMoney) ?: Money.ZERO, toGodownId = toGodown?.let(::gd),
            batchNumber = batch, expiryDate = expiry?.let(::parseDate), serialNumbers = serials,
        )
        val (d, fy) = dateAndYear(f, date)
        val id = cli.env.newId()
        val sv = StockVouchers(repos.vouchers, PostVoucher(repos.vouchers))
        val v = if (transfer) sv.transfer(id, f.id, fy, f.stateCode, d, reason, listOf(line), { n -> "$id-$n" }, now())
        else sv.adjust(id, f.id, fy, f.stateCode, d, reason ?: fail(Exit.USAGE, "adjust needs --reason"), listOf(line), { n -> "$id-$n" }, now())
        if (cli.json) cli.printJson(jsonOf("id" to v.id, "number" to v.number)) else cli.out.println(v.number ?: v.id)
    }
}
