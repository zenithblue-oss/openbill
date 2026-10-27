package app.openbill.cli

import androidx.room.Transactor
import androidx.room.useWriterConnection
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.usecase.MoneyVoucherInput
import app.openbill.core.domain.usecase.MoneyVouchers
import app.openbill.core.domain.usecase.PostVoucher
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.core.tax.InvoiceTaxCalculator
import app.openbill.core.tax.InvoiceTaxInput
import app.openbill.core.tax.TaxContext
import app.openbill.core.tax.TaxLineInput
import app.openbill.core.tax.TaxMode
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import java.io.File
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Hidden `dev` commands for scale testing (docs/14-performance.md): a firm, master CSVs for
 * `import parties|items`, and posted vouchers through the real repositories, many per
 * transaction. Seeded, so a run is reproducible apart from ids and timestamps of the masters.
 */
fun devCommands(cli: CliState) = object : Group("dev", "Scale-test data generator (development only)") {
    override val hiddenFromHelp = true
}.subcommands(DevInit(cli), DevCsv(), DevVouchers(cli))

private val FY_STARTS = listOf(LocalDate(2024, 4, 1), LocalDate(2025, 4, 1))
private val STATES = listOf("27", "27", "27", "27", "27", "27", "27", "29", "24", "33")

class DevInit(private val cli: CliState) : CliktCommand("init") {
    override fun help(context: Context) = "Create a firm with two godowns, a unit and FY 2024-25 and 2025-26 (creates the database)"
    private val name by option("--name").required()
    private val gstin by option("--gstin").default("27AAACS1234F1Z5")

    override fun run() = cli.exclusive { loc ->
        val db = openBillDatabase(loc.databaseFile.absolutePath)
        try {
            runBlocking {
                val r = db.roomRepositories()
                val at = cli.env.clock.now()
                val id = cli.env.newId()
                r.firms.saveFirm(Firm(id = id, name = name, gstin = gstin, stateCode = gstin.take(2), addressLine1 = "1 Market Road", city = "Pune", createdAt = at, updatedAt = at))
                val g1 = cli.env.newId()
                r.catalog.saveGodown(Godown(id = g1, firmId = id, name = "Main", isDefault = true, createdAt = at, updatedAt = at))
                r.catalog.saveGodown(Godown(id = cli.env.newId(), firmId = id, name = "Warehouse", createdAt = at, updatedAt = at))
                r.firms.saveSettings(FirmSettings(firmId = id, defaultGodownId = g1, negativeStockAllowed = true))
                FY_STARTS.forEach { s ->
                    r.firms.saveFinancialYear(FinancialYear(id = cli.env.newId(), firmId = id, startDate = s, endDate = s.plus(DatePeriod(years = 1, days = -1))))
                }
                r.catalog.saveUnit(UnitOfMeasure(id = cli.env.newId(), firmId = id, name = "Piece", symbol = "pcs", createdAt = at, updatedAt = at))
                r.ledger.ensureSystemAccounts(id, at)
                cli.out.println(id)
            }
        } finally {
            db.close()
        }
    }
}

class DevCsv : CliktCommand("csv") {
    override fun help(context: Context) = "Write parties.csv and items.csv for `import parties|items`"
    private val parties by option("--parties").int().default(50_000)
    private val items by option("--items").int().default(20_000)
    private val seed by option("--seed").long().default(42)
    private val out by option("--out").file(canBeFile = false).required()

    override fun run() {
        val rnd = Random(seed)
        out.mkdirs()
        File(out, "parties.csv").bufferedWriter().use { w ->
            w.write("name,type,phone,email,gstin,state_code\r\n")
            for (i in 1..parties) {
                val supplier = i % 5 == 0
                val state = STATES[rnd.nextInt(STATES.size)]
                val gstin = if (rnd.nextInt(2) == 0) "%s%s%04dF1Z%d".format(state, pan(rnd), i % 10_000, rnd.nextInt(10)) else ""
                w.write("${if (supplier) "Supplier" else "Customer"} ${word(rnd)} $i,${if (supplier) "supplier" else "customer"},9${"%09d".format(rnd.nextInt(1_000_000_000))},,$gstin,$state\r\n")
            }
        }
        val rates = listOf("0", "5", "12", "18", "18", "28")
        File(out, "items.csv").bufferedWriter().use { w ->
            w.write("name,sku,unit,hsn_sac,gst_rate,sale_price,purchase_price,is_service\r\n")
            for (i in 1..items) {
                val service = i % 20 == 0
                val buy = 10 + rnd.nextInt(5_000)
                w.write("${word(rnd)} ${word(rnd)} $i,SKU$i,pcs,${if (service) "9987" else "%04d".format(1000 + rnd.nextInt(8000))},${rates[rnd.nextInt(rates.size)]},${buy * 13 / 10}.00,$buy.00,${if (service) "yes" else "no"}\r\n")
            }
        }
    }

    private fun pan(rnd: Random) = (1..5).map { 'A' + rnd.nextInt(26) }.joinToString("")
    private fun word(rnd: Random) = WORDS[rnd.nextInt(WORDS.size)]
}

private val WORDS = listOf("Steel", "Copper", "Blue", "Royal", "Sai", "Ganesh", "Prime", "Metro", "Shree", "Om", "Laxmi", "National", "Star", "Bharat", "Global", "Delta", "Pune", "Nashik", "Kolhapur", "Modern")

/**
 * Posts [sales] sale invoices (2-5 lines), [purchases] purchase bills and [payments] payments
 * (80% in, 20% out, each settling the party's oldest bill) spread over FY 2024-25 and 2025-26.
 */
class DevVouchers(private val cli: CliState) : CliktCommand("vouchers") {
    override fun help(context: Context) = "Post generated sales, purchases and payments through the repositories"
    private val sales by option("--sales").int().default(1_000_000)
    private val purchases by option("--purchases").int().default(200_000)
    private val payments by option("--payments").int().default(300_000)
    private val seed by option("--seed").long().default(42)
    private val batch by option("--batch").int().default(5_000)
    private val max by option("--max", help = "Stop after posting this many in this run (resume later)").int()

    override fun run() = cli.withData(write = true) {
        val f = firm()
        val settings = repos.firms.findSettings(f.id) ?: FirmSettings(f.id)
        val years = repos.firms.observeFinancialYears(f.id).first()
        val godowns = repos.catalog.observeGodowns(f.id).first().map { it.id }
        val allParties = parties(f.id)
        val customers = allParties.filter { it.type != PartyType.SUPPLIER }
        val suppliers = allParties.filter { it.type != PartyType.CUSTOMER }
        val goods = items(f.id)
        require(customers.isNotEmpty() && suppliers.isNotEmpty() && goods.isNotEmpty()) { "Import parties (customers and suppliers) and items first" }
        val cash = repos.ledger.ensureSystemAccounts(f.id, now()).cash.id
        val post = PostVoucher(repos.vouchers)
        val money = MoneyVouchers(repos.vouchers, repos.payments, repos.ledger, post)

        val start = years.minOf { it.startDate }
        val days = (years.maxOf { it.endDate }.toEpochDays() - start.toEpochDays() + 1).toInt()
        val total = sales + purchases + payments
        // Resumable: the committed vouchers are the checkpoint (each batch is one transaction).
        suspend fun count(vararg t: VoucherType) =
            repos.vouchers.observeVoucherStats(f.id, t.toSet(), LocalDate(1900, 1, 1), LocalDate(9999, 12, 31)).first().values.sumOf { it.count }
        var s = count(VoucherType.SALE)
        var p = count(VoucherType.PURCHASE)
        var m = count(VoucherType.PAYMENT_IN, VoucherType.PAYMENT_OUT)
        var done = s + p + m
        val stopAt = minOf(total, done + (max ?: total))
        // shortcut: a resumed run reseeds with seed + done, so only an uninterrupted run is byte-reproducible.
        val rnd = Random(seed + done)
        if (done > 0) cli.info("resuming at $done/$total")
        val t0 = System.nanoTime()
        while (done < stopAt) {
            val n = minOf(batch, stopAt - done)
            database.useWriterConnection { t ->
                t.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    repeat(n) {
                        val i = done + it
                        val date = LocalDate.fromEpochDays(start.toEpochDays() + (i.toLong() * days / total).toInt())
                        val fy = years.first { y -> date >= y.startDate && date <= y.endDate }.id
                        val at = Instant.fromEpochMilliseconds(date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 36_000_000L + it)
                        // Weighted pick that keeps each kind's share constant over time.
                        val kind = when {
                            p * total.toLong() < purchases.toLong() * i -> 1
                            m * total.toLong() < payments.toLong() * i && s > 0 -> 2
                            s < sales -> 0
                            p < purchases -> 1
                            else -> 2
                        }
                        when (kind) {
                            0 -> { s++; post(saveTrade(f, settings, fy, VoucherType.SALE, date, at, customers[rnd.nextInt(customers.size)], goods, godowns, rnd), at) }
                            1 -> { p++; post(saveTrade(f, settings, fy, VoucherType.PURCHASE, date, at, suppliers[rnd.nextInt(suppliers.size)], goods, godowns, rnd), at) }
                            else -> {
                                m++
                                val type = if (rnd.nextInt(5) == 0) VoucherType.PAYMENT_OUT else VoucherType.PAYMENT_IN
                                val party = (if (type == VoucherType.PAYMENT_IN) customers else suppliers).let { l -> l[rnd.nextInt(l.size)] }
                                val open = repos.payments.outstandingInvoices(f.id, party.id, MoneyVouchers.invoiceTypeFor(type))
                                val amount = open.minByOrNull { o -> o.date }?.outstanding?.takeIf { a -> a > Money.ZERO } ?: Money.of(100_000L + rnd.nextLong(1_000_000L))
                                money.record(
                                    MoneyVoucherInput(
                                        type = type, id = cli.env.newId(), firmId = f.id, financialYearId = fy, stateCode = f.stateCode, date = date,
                                        amount = amount, accountId = cash, method = PaymentMethod.CASH, partyId = party.id, allocations = MoneyVouchers.fifo(open, amount),
                                    ),
                                    at,
                                )
                            }
                        }
                    }
                }
            }
            done += n
            val secs = (System.nanoTime() - t0) / 1e9
            cli.info("posted $done/$total (sales $s, purchases $p, payments $m) in ${"%.0f".format(secs)}s, ${"%.0f".format(done / secs)}/s")
        }
        cli.out.println("sales $s purchases $p payments $m")
    }

    private suspend fun Data.saveTrade(f: Firm, settings: FirmSettings, fy: String, type: VoucherType, date: LocalDate, at: Instant, party: Party, goods: List<Item>, godowns: List<String>, rnd: Random): String {
        val sale = type == VoucherType.SALE
        val godown = if (sale) settings.defaultGodownId else godowns[rnd.nextInt(godowns.size)]
        val place = if (sale) party.stateCode ?: f.stateCode else f.stateCode
        val picked = List(if (sale) 2 + rnd.nextInt(4) else 3) { goods[rnd.nextInt(goods.size)] }
        val drafts = picked.mapIndexed { idx, item ->
            val qty = Quantity.of(if (sale) 1L + rnd.nextInt(10) else 50L + rnd.nextInt(150))
            val rate = if (sale) item.salePrice else item.purchasePrice
            val gross = Money.of(Rounding.multiplyDivide(rate.paise, qty.scaledUnits, 10_000L, RoundingMode.HALF_UP))
            VoucherLine(
                id = cli.env.newId(), lineNumber = idx + 1, itemId = item.id, description = item.name, hsnSac = item.hsnSac,
                quantity = qty, rate = rate, taxRate = item.taxRate, godownId = if (item.trackInventory) godown else null,
            ) to gross
        }
        val tax = InvoiceTaxCalculator.calculate(
            InvoiceTaxInput(lines = drafts.map { (l, g) -> TaxLineInput(grossAmount = g, discount = l.discount, gstRate = l.taxRate, mode = TaxMode.EXCLUSIVE) }, roundToRupee = settings.roundToRupee),
            TaxContext(f.stateCode, place),
            settings.roundingMode,
        )
        val lines = drafts.mapIndexed { i, (l, _) -> tax.lines[i].let { r -> l.copy(taxable = r.taxableValue, tax = r.totalTax, total = r.totalAmount) } }
        val id = cli.env.newId()
        repos.vouchers.saveDraft(
            Voucher(
                id = id, firmId = f.id, financialYearId = fy, type = type, date = date, partyId = party.id, status = VoucherStatus.DRAFT,
                placeOfSupplyStateCode = place, lines = lines, subtotal = drafts.fold(Money.ZERO) { a, d -> a + d.second },
                taxable = tax.totalTaxable, tax = tax.totalTax, roundOff = tax.roundOff, total = tax.grandTotal, createdAt = at, updatedAt = at,
            ),
        )
        return id
    }
}
