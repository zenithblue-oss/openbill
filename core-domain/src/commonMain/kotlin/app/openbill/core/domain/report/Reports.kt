package app.openbill.core.domain.report

import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.isOrder
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.service.AgeingSums
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.domain.service.StockValuation
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.tax.TaxTreatment
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus

enum class ReportGroup(val label: String) { TRANSACTIONS("Transactions"), PARTIES("Parties and money"), STOCK("Stock and profit"), GST("GST") }

/** Every report, by its stable CLI name ([key]). */
enum class ReportKind(val key: String, val title: String, val group: ReportGroup, val needsParty: Boolean = false) {
    SALES_REGISTER("sales-register", "Sales register", ReportGroup.TRANSACTIONS),
    PURCHASE_REGISTER("purchase-register", "Purchase register", ReportGroup.TRANSACTIONS),
    DAY_BOOK("day-book", "Day book", ReportGroup.TRANSACTIONS),
    SALES_BY_USER("sales-by-user", "Sales summary by user", ReportGroup.TRANSACTIONS),
    DAY_BOOK_BY_USER("day-book-by-user", "Day book by user", ReportGroup.TRANSACTIONS),
    DAY_SUMMARY("day-summary", "Day summary", ReportGroup.TRANSACTIONS),
    CASH_BANK_BOOK("cash-bank-book", "Cash and bank book", ReportGroup.PARTIES),
    EXPENSES("expenses", "Expense report", ReportGroup.PARTIES),
    OUTSTANDING("outstanding", "Party-wise outstanding", ReportGroup.PARTIES),
    PARTY_STATEMENT("party-statement", "Party statement", ReportGroup.PARTIES, needsParty = true),
    STOCK_SUMMARY("stock-summary", "Stock summary", ReportGroup.STOCK),
    LOW_STOCK("low-stock", "Low-stock report", ReportGroup.STOCK),
    BATCH_EXPIRY("batch-expiry", "Batch and expiry report", ReportGroup.STOCK),
    ITEM_PROFIT("item-profit", "Item-wise profit", ReportGroup.STOCK),
    PROFIT_LOSS("profit-loss", "Profit and loss", ReportGroup.STOCK),
    GSTR1("gstr1", "GSTR-1 summary", ReportGroup.GST),
    GSTR3B("gstr3b", "GSTR-3B summary", ReportGroup.GST),
    GSTR2("gstr2", "GSTR-2 working (purchases)", ReportGroup.GST),
    HSN_SUMMARY("hsn-summary", "HSN-wise summary", ReportGroup.GST),
    ;

    companion object {
        fun of(key: String): ReportKind? = entries.firstOrNull { it.key == key }
    }
}

private fun col(key: String, label: String, kind: ColumnKind = ColumnKind.TEXT) = ReportColumn(key, label, kind)
private fun money(key: String, label: String) = col(key, label, ColumnKind.MONEY)

private val TAX_COLUMNS = listOf(money("taxable", "Taxable"), money("cgst", "CGST"), money("sgst", "SGST"), money("igst", "IGST"), money("cess", "Cess"))
private fun TaxAmounts.cells() = listOf(taxable.cell(), cgst.cell(), sgst.cell(), igst.cell(), cess.cell())

object Reports {
    fun run(kind: ReportKind, data: ReportData, partyId: String? = null): ReportTable = when (kind) {
        ReportKind.SALES_REGISTER -> register(data, sales = true)
        ReportKind.PURCHASE_REGISTER -> register(data, sales = false)
        ReportKind.DAY_BOOK -> dayBook(data)
        ReportKind.SALES_BY_USER -> salesByUser(data)
        ReportKind.DAY_BOOK_BY_USER -> dayBookByUser(data)
        ReportKind.DAY_SUMMARY -> daySummary(data)
        ReportKind.BATCH_EXPIRY -> batchExpiry(data)
        ReportKind.GSTR2 -> gstr2(data)
        ReportKind.CASH_BANK_BOOK -> cashBankBook(data)
        ReportKind.EXPENSES -> expenses(data)
        ReportKind.OUTSTANDING -> outstanding(data)
        ReportKind.PARTY_STATEMENT -> partyStatement(data, partyId ?: throw IllegalArgumentException("Party statement needs a party"))
        ReportKind.STOCK_SUMMARY -> stockSummary(data)
        ReportKind.LOW_STOCK -> lowStock(data)
        ReportKind.ITEM_PROFIT -> itemProfit(data)
        ReportKind.PROFIT_LOSS -> profitLoss(data)
        ReportKind.GSTR1 -> Gstr1.build(data).summaryTable()
        ReportKind.GSTR3B -> gstr3b(data)
        ReportKind.HSN_SUMMARY -> Gstr1.hsnTable(data)
    }

    /** Signed tax breakdown of a voucher: returns (credit/debit notes) count negative. */
    fun amounts(v: Voucher, data: ReportData): TaxAmounts =
        TaxAmounts.sum(v.taxRows(data.firm.stateCode, data.rounding).map { it.amounts }).negateIf(v.type.isReturn())

    private fun VoucherType.isReturn() = this == VoucherType.CREDIT_NOTE || this == VoucherType.DEBIT_NOTE

    private fun typeLabel(t: VoucherType) = when (t) {
        VoucherType.SALE -> "Sale"
        VoucherType.PURCHASE -> "Purchase"
        VoucherType.CREDIT_NOTE -> "Credit note"
        VoucherType.DEBIT_NOTE -> "Debit note"
        VoucherType.PAYMENT_IN -> "Receipt"
        VoucherType.PAYMENT_OUT -> "Payment"
        VoucherType.DELIVERY_CHALLAN -> "Delivery challan"
        else -> t.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Sales (with credit notes negative) or purchases (with debit notes negative). */
    fun register(data: ReportData, sales: Boolean): ReportTable {
        val r = Register(data, sales)
        return ReportTable(r.title, r.columns, data.posted.filter { it.type in r.types }.map(r::row), r.totals())
    }

    /** Register rows one voucher at a time, so a streamed register ([writeRegisterCsv]) and [register] print the same. */
    class Register(private val data: ReportData, private val sales: Boolean) {
        val types = if (sales) setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE) else setOf(VoucherType.PURCHASE, VoucherType.DEBIT_NOTE)
        val title = if (sales) "Sales register" else "Purchase register"
        val columns = listOf(col("date", "Date", ColumnKind.DATE), col("type", "Type"), col("number", "Number")) +
            (if (sales) emptyList() else listOf(col("bill_no", "Supplier bill"))) +
            listOf(col("party", "Party"), col("gstin", "GSTIN"), col("pos", "POS")) + TAX_COLUMNS +
            listOf(money("round_off", "Round off"), money("total", "Total"))
        private var sum = TaxAmounts()
        private var roundOff = Money.ZERO
        private var total = Money.ZERO

        /** Row of [v]; adds it to the totals. */
        fun row(v: Voucher): List<String> {
            val a = amounts(v, data)
            val sign = if (v.type.isReturn()) -1 else 1
            sum += a
            roundOff += v.roundOff * sign
            total += v.total * sign
            val party = v.partyId?.let { data.partyById[it] }
            return listOf(v.date.toString(), typeLabel(v.type), v.number.orEmpty()) +
                (if (sales) emptyList() else listOf(v.referenceNumber.orEmpty())) +
                listOf(party?.name.orEmpty(), party?.gstin.orEmpty(), v.placeOfSupplyStateCode) +
                a.cells() + listOf((v.roundOff * sign).cell(), (v.total * sign).cell())
        }

        fun totals(): List<String> = listOf("Total") + List((if (sales) 6 else 7) - 1) { "" } + sum.cells() + listOf(roundOff.cell(), total.cell())
    }

    /** Every non-draft voucher of the period by date; orders are commitments, not transactions, and stay out. */
    fun dayBook(data: ReportData): ReportTable {
        val list = data.vouchers.filter { it.status != VoucherStatus.DRAFT && !it.type.isOrder() }
            .sortedWith(compareBy<Voucher>({ it.date }, { it.number ?: "" }, { it.id }))
        return ReportTable(
            "Day book",
            listOf(col("date", "Date", ColumnKind.DATE), col("type", "Type"), col("number", "Number"), col("particulars", "Particulars"), col("status", "Status"), money("amount", "Amount")),
            list.map { v ->
                val particulars = v.partyId?.let { data.partyName(it) }
                    ?: v.ledgerAccountId?.let { data.accountById[it]?.name } ?: v.notes.orEmpty()
                listOf(v.date.toString(), typeLabel(v.type), v.number.orEmpty(), particulars, v.status.name.lowercase(), v.total.cell())
            },
        )
    }

    /** [dayBook] with who posted each voucher, grouped by user. */
    fun dayBookByUser(data: ReportData): ReportTable {
        val book = dayBook(data)
        val list = data.vouchers.filter { it.status != VoucherStatus.DRAFT && !it.type.isOrder() }
            .sortedWith(compareBy<Voucher>({ it.date }, { it.number ?: "" }, { it.id }))
        val rows = list.zip(book.rows) { v, row -> listOf(data.userOf(v)) + row }.sortedBy { it[0].lowercase() }
        return ReportTable("Day book by user", listOf(col("user", "User")) + book.columns, rows)
    }

    /** Per user: sales invoices and credit notes they posted (credit notes negative), with tax and totals. */
    fun salesByUser(data: ReportData): ReportTable {
        val sales = setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE)
        val byUser = data.posted.filter { it.type in sales }.groupBy { data.userOf(it) }.entries.sortedBy { it.key.lowercase() }
        var count = 0
        var returns = 0
        var sum = TaxAmounts()
        var total = Money.ZERO
        val rows = byUser.map { (user, list) ->
            val invoices = list.count { it.type == VoucherType.SALE }
            val notes = list.size - invoices
            val a = list.fold(TaxAmounts()) { acc, v -> acc + amounts(v, data) }
            val t = list.fold(Money.ZERO) { acc, v -> if (v.type.isReturn()) acc - v.total else acc + v.total }
            count += invoices
            returns += notes
            sum += a
            total += t
            listOf(user, invoices.toString(), notes.toString()) + a.cells() + t.cell()
        }
        return ReportTable(
            "Sales summary by user",
            listOf(col("user", "User"), col("invoices", "Invoices", ColumnKind.NUMBER), col("credit_notes", "Credit notes", ColumnKind.NUMBER)) +
                TAX_COLUMNS + money("total", "Total"),
            rows,
            listOf("Total", count.toString(), returns.toString()) + sum.cells() + total.cell(),
        )
    }

    /** Per cash/bank account: opening, each journal line (receipt = debit, payment = credit) with running balance, closing. */
    fun cashBankBook(data: ReportData): ReportTable {
        val accounts = data.accounts.filter { it.type == AccountType.CASH || it.type == AccountType.BANK }.sortedWith(compareBy({ it.type }, { it.name }))
        val byAccount = data.journal.groupBy { it.entry.accountId }
        val rows = ArrayList<List<String>>()
        var inTotal = Money.ZERO
        var outTotal = Money.ZERO
        var closingTotal = Money.ZERO
        for (a in accounts) {
            val txns = byAccount[a.id].orEmpty()
            var balance = txns.filter { it.entry.date < data.from }.fold(a.openingBalance) { b, t -> b + t.entry.debit - t.entry.credit }
            rows += listOf(a.name, data.from.toString(), "Opening balance", "", "", "", "", balance.cell())
            txns.filter { it.entry.date >= data.from && it.entry.date <= data.to }.forEach { t ->
                val e = t.entry
                balance += e.debit - e.credit
                inTotal += e.debit
                outTotal += e.credit
                rows += listOf(
                    a.name, e.date.toString(), typeLabel(t.voucherType), t.voucherNumber.orEmpty(),
                    data.partyName(t.partyId).ifEmpty { e.narration.orEmpty() }, e.debit.cellOrBlank(), e.credit.cellOrBlank(), balance.cell(),
                )
            }
            closingTotal += balance
        }
        return ReportTable(
            "Cash and bank book",
            listOf(col("account", "Account"), col("date", "Date", ColumnKind.DATE), col("type", "Type"), col("number", "Number"), col("particulars", "Particulars"), money("receipt", "Receipt"), money("payment", "Payment"), money("balance", "Balance")),
            rows,
            listOf("Total", "", "", "", "", inTotal.cell(), outTotal.cell(), closingTotal.cell()),
        )
    }

    /** Posted expenses of the period by category. */
    fun expenses(data: ReportData): ReportTable {
        val groups = data.posted.filter { it.type == VoucherType.EXPENSE }.groupBy { it.ledgerAccountId }
        val rows = groups.map { (cat, list) ->
            val name = cat?.let { data.accountById[it]?.name } ?: "Uncategorised"
            Triple(name, list.size, list)
        }.sortedBy { it.first.lowercase() }
        fun sum(list: List<Voucher>, f: (Voucher) -> Money) = list.fold(Money.ZERO) { t, v -> t + f(v) }
        val all = groups.values.flatten()
        return ReportTable(
            "Expense report",
            listOf(col("category", "Category"), col("count", "Vouchers", ColumnKind.NUMBER), money("amount", "Amount"), money("gst", "GST"), money("total", "Total")),
            rows.map { (name, n, list) -> listOf(name, n.toString(), sum(list) { it.taxable }.cell(), sum(list) { it.tax }.cell(), sum(list) { it.total }.cell()) },
            listOf("Total", all.size.toString(), sum(all) { it.taxable }.cell(), sum(all) { it.tax }.cell(), sum(all) { it.total }.cell()),
        )
    }

    /** Balance and ageing of every party as of the period end (positive = receivable). */
    fun outstanding(data: ReportData): ReportTable {
        val byParty = data.partyMovements.groupBy { it.partyId }
        val rows = data.parties.mapNotNull { p ->
            val ageing = data.partyAgeing?.let { PartyAgeing.fromSums(p.signedOpening, it[p.id] ?: AgeingSums()) }
                ?: PartyAgeing.of(p.signedOpening, byParty[p.id].orEmpty(), data.to)
            if (ageing.total == Money.ZERO) null else p to ageing
        }.sortedBy { it.first.name.lowercase() }
        fun pos(m: Money) = if (m > Money.ZERO) m else Money.ZERO
        fun neg(m: Money) = if (m < Money.ZERO) -m else Money.ZERO
        val total = rows.fold(app.openbill.core.domain.service.AgeingBuckets()) { t, (_, a) -> t + a }
        // Past the party's credit terms; only parties with terms have a figure.
        val overdue = rows.associate { (p, _) ->
            p.id to p.creditDays?.let { d -> PartyAgeing.overdue(p.signedOpening, byParty[p.id].orEmpty(), data.to, d) }
        }
        return ReportTable(
            "Party-wise outstanding",
            listOf(
                col("party", "Party"), col("type", "Type"), col("group", "Group"), col("gstin", "GSTIN"), money("receivable", "Receivable"), money("payable", "Payable"),
                col("terms", "Credit days"), money("overdue", "Overdue"),
                money("d0_30", "0-30 days"), money("d31_60", "31-60 days"), money("d61_90", "61-90 days"), money("d90", "90+ days"),
            ),
            rows.map { (p, a) ->
                listOf(
                    p.name, p.type.name.lowercase(), p.category.orEmpty(), p.gstin.orEmpty(), pos(a.total).cell(), neg(a.total).cell(),
                    p.creditDays?.toString().orEmpty(), overdue[p.id]?.cell() ?: "",
                    a.upTo30.cell(), a.days31To60.cell(), a.days61To90.cell(), a.over90.cell(),
                )
            },
            listOf(
                "Total", "", "", "", rows.fold(Money.ZERO) { t, r -> t + pos(r.second.total) }.cell(), rows.fold(Money.ZERO) { t, r -> t + neg(r.second.total) }.cell(),
                "", overdue.values.filterNotNull().fold(Money.ZERO) { t, m -> t + m }.cell(),
                total.upTo30.cell(), total.days31To60.cell(), total.days61To90.cell(), total.over90.cell(),
            ),
            notes = listOf(
                "As of ${data.to}; ageing buckets are signed (negative = payable or advance).",
                "Overdue is the receivable still open (oldest settled first) from sales older than the party's credit days; blank for parties without terms.",
            ),
        )
    }

    /** One party's ledger for the period with opening and running balance. */
    fun partyStatement(data: ReportData, partyId: String): ReportTable {
        val party = data.partyById[partyId] ?: throw IllegalArgumentException("Unknown party: $partyId")
        val moves = data.partyMovements.filter { it.partyId == partyId }.sortedBy { it.date }
        var balance = moves.filter { it.date < data.from }.fold(party.signedOpening) { b, m -> b + m.net }
        val opening = balance
        var dr = Money.ZERO
        var cr = Money.ZERO
        val rows = ArrayList<List<String>>()
        rows += listOf(data.from.toString(), "Opening balance", "", "", "", balance.cell())
        moves.filter { it.date >= data.from && it.date <= data.to }.forEach { m ->
            balance += m.net
            val d = if (m.net > Money.ZERO) m.net else Money.ZERO
            val c = if (m.net < Money.ZERO) -m.net else Money.ZERO
            dr += d
            cr += c
            rows += listOf(m.date.toString(), typeLabel(m.voucherType), m.voucherNumber.orEmpty(), d.cellOrBlank(), c.cellOrBlank(), balance.cell())
        }
        return ReportTable(
            "Party statement: ${party.name}",
            listOf(col("date", "Date", ColumnKind.DATE), col("type", "Type"), col("number", "Number"), money("debit", "Debit"), money("credit", "Credit"), money("balance", "Balance")),
            rows,
            // The label sits in the Type column, like "Opening balance", so the Date column stays dates.
            listOf("", "Closing balance", "", dr.cell(), cr.cell(), balance.cell()),
            notes = listOf("Opening ${opening.cell()}; positive balance = ${party.name} owes ${data.firm.name}."),
        )
    }

    /** Opening, inward, outward and closing quantity per inventory item, valued at weighted average. */
    fun stockSummary(data: ReportData): ReportTable {
        val byItem = data.stock.groupBy { it.itemId }
        var totalValue = Money.ZERO
        val rows = data.items.filter { it.trackInventory && !it.isService }.sortedBy { it.name.lowercase() }.map { item ->
            val t = data.stockTotals?.let { it[item.id] ?: StockValuation.periodTotals(emptyList(), data.from, data.to) }
                ?: StockValuation.periodTotals(byItem[item.id].orEmpty(), data.from, data.to)
            val opening = t.opening
            val inward = t.inward
            val outward = t.outward
            val v = t.closing
            totalValue += v.value
            listOf(item.name, data.unitById[item.unitId]?.symbol.orEmpty(), opening.format(), inward.format(), outward.format(), v.quantity.format(), v.averageCost.cell(), v.value.cell())
        }
        return ReportTable(
            "Stock summary",
            listOf(col("item", "Item"), col("unit", "Unit"), col("opening", "Opening", ColumnKind.QUANTITY), col("inward", "Inward", ColumnKind.QUANTITY), col("outward", "Outward", ColumnKind.QUANTITY), col("closing", "Closing", ColumnKind.QUANTITY), money("avg_cost", "Avg cost"), money("value", "Value")),
            rows,
            listOf("Total", "", "", "", "", "", "", totalValue.cell()),
            notes = listOf("Closing as of ${data.to}; moving weighted-average cost across godowns."),
        )
    }

    /** Items at or below their low-stock level as of the period end, shortest first (same rule as the dashboard card). */
    fun lowStock(data: ReportData): ReportTable {
        val byItem = data.stock.filter { it.date <= data.to }.groupBy { it.itemId }
        val rows = data.items.filter { it.trackInventory && !it.isService && it.lowStockLevel != null }.mapNotNull { item ->
            val level = item.lowStockLevel!!
            val onHand = data.stockTotals?.let { it[item.id]?.closing?.quantity ?: Quantity.of(0) }
                ?: Quantity.ofScaledUnits(byItem[item.id].orEmpty().sumOf { it.quantity.scaledUnits })
            if (onHand > level) null else Triple(item, onHand, level)
        }.sortedWith(compareBy({ it.second.scaledUnits - it.third.scaledUnits }, { it.first.name.lowercase() }))
        return ReportTable(
            "Low-stock report",
            listOf(
                col("item", "Item"), col("unit", "Unit"), col("on_hand", "On hand", ColumnKind.QUANTITY), col("level", "Low-stock level", ColumnKind.QUANTITY),
                col("short", "Short by", ColumnKind.QUANTITY), col("reorder", "Order", ColumnKind.QUANTITY),
            ),
            rows.map { (item, onHand, level) ->
                val short = Quantity.ofScaledUnits(level.scaledUnits - onHand.scaledUnits)
                listOf(item.name, data.unitById[item.unitId]?.symbol.orEmpty(), onHand.format(), level.format(), short.format(), suggestedOrder(item.reorderQuantity, short).format())
            },
            notes = listOf(
                "As of ${data.to}; all godowns. Set an item's low-stock level and reorder quantity in the item form.",
                "Order is the item's reorder quantity, raised to the shortfall when that is larger.",
            ),
        )
    }

    /** What to order for an item short by [short]: its reorder quantity, at least the shortfall; the shortfall when none is set. */
    fun suggestedOrder(reorder: Quantity?, short: Quantity): Quantity =
        if (reorder == null) short else Quantity.ofScaledUnits(maxOf(reorder.scaledUnits, short.scaledUnits))

    /** Value of all inventory at the end of [through] (inclusive). */
    fun stockValue(data: ReportData, through: kotlinx.datetime.LocalDate): Money {
        data.stockTotals?.let { totals ->
            // Only the two P&L dates are precomputed: the day before the period and its end.
            require(through == data.to || through == data.from.minus(DatePeriod(days = 1))) { "No stock totals for $through" }
            return totals.filterKeys { id -> data.itemById[id]?.let { it.trackInventory && !it.isService } ?: true }
                .values.fold(Money.ZERO) { t, p -> t + if (through == data.to) p.closing.value else p.valueBeforeFrom }
        }
        return data.stock.filter { it.date <= through }.groupBy { it.itemId }
            .filterKeys { id -> data.itemById[id]?.let { it.trackInventory && !it.isService } ?: true }
            .values.fold(Money.ZERO) { t, list -> t + StockValuation.value(list).value }
    }

    /**
     * Per item: net quantity sold, net sales value (taxable, credit notes negative), cost of goods
     * at moving weighted average (the value the sale movements took out of stock), profit, margin.
     */
    fun itemProfit(data: ReportData): ReportTable {
        val saleIds = data.posted.filter { it.type == VoucherType.SALE || it.type == VoucherType.CREDIT_NOTE }.associateBy { it.id }
        val revenue = HashMap<String, Money>()
        val sold = HashMap<String, Long>()
        saleIds.values.forEach { v ->
            val sign = if (v.type == VoucherType.CREDIT_NOTE) -1 else 1
            v.lines.filter { it.itemId != null }.forEach { l ->
                revenue[l.itemId!!] = (revenue[l.itemId] ?: Money.ZERO) + l.taxable * sign
                sold[l.itemId] = (sold[l.itemId] ?: 0L) + l.quantity.scaledUnits * sign
            }
        }
        val cogs = HashMap<String, Money>()
        data.stock.groupBy { it.itemId }.forEach { (itemId, moves) ->
            val values = StockValuation.movementValues(moves)
            moves.filter { it.voucherId in saleIds }.forEach { m -> cogs[itemId] = (cogs[itemId] ?: Money.ZERO) - (values[m.id] ?: Money.ZERO) }
        }
        var tRev = Money.ZERO
        var tCost = Money.ZERO
        val rows = revenue.keys.sortedBy { data.itemById[it]?.name?.lowercase() ?: it }.map { id ->
            val rev = revenue.getValue(id)
            val cost = cogs[id] ?: Money.ZERO
            tRev += rev
            tCost += cost
            listOf(data.itemById[id]?.name ?: id, Quantity.ofScaledUnits(sold[id] ?: 0L).format(), rev.cell(), cost.cell(), (rev - cost).cell(), margin(rev, rev - cost))
        }
        return ReportTable(
            "Item-wise profit",
            listOf(col("item", "Item"), col("qty", "Qty sold", ColumnKind.QUANTITY), money("sales", "Sales value"), money("cost", "Cost of goods"), money("profit", "Profit"), col("margin", "Margin %", ColumnKind.NUMBER)),
            rows,
            listOf("Total", "", tRev.cell(), tCost.cell(), (tRev - tCost).cell(), margin(tRev, tRev - tCost)),
            notes = listOf("Sales value excludes GST; cost of goods at moving weighted average; services have no cost."),
        )
    }

    private fun margin(revenue: Money, profit: Money): String =
        if (revenue.paise == 0L) "" else ((profit.paise * 10_000L / revenue.paise).let { Money.of(it).format() })

    /** Ledger-based trading and profit and loss account for the period. */
    fun profitLoss(data: ReportData): ReportTable {
        val inRange = data.journal.filter { it.entry.date >= data.from && it.entry.date <= data.to }
        fun net(accountId: String) = data.accountNets?.let { it[accountId] ?: Money.ZERO }
            ?: inRange.filter { it.entry.accountId == accountId }.fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit }
        val sales = data.accounts.firstOrNull { it.code == AccountCode.SALES }?.let { -net(it.id) } ?: Money.ZERO
        val purchases = data.accounts.firstOrNull { it.code == AccountCode.PURCHASES }?.let { net(it.id) } ?: Money.ZERO
        val openingStock = stockValue(data, data.from.minus(DatePeriod(days = 1)))
        val closingStock = stockValue(data, data.to)
        val cogs = openingStock + purchases - closingStock
        val gross = sales - cogs
        val otherIncome = data.accounts.filter { it.type == AccountType.INCOME && it.code != AccountCode.SALES }
            .map { it.name to -net(it.id) }.filter { it.second != Money.ZERO }.sortedBy { it.first }
        val expenses = data.accounts.filter { it.type == AccountType.EXPENSE && it.code != AccountCode.PURCHASES }
            .map { it.name to net(it.id) }.filter { it.second != Money.ZERO }.sortedBy { it.first }
        val netProfit = gross + otherIncome.fold(Money.ZERO) { t, x -> t + x.second } - expenses.fold(Money.ZERO) { t, x -> t + x.second }
        val rows = buildList {
            add(listOf("Sales (net of returns)", sales.cell()))
            add(listOf("Opening stock", openingStock.cell()))
            add(listOf("Purchases (net of returns)", purchases.cell()))
            add(listOf("Closing stock", closingStock.cell()))
            add(listOf("Cost of goods sold", cogs.cell()))
            add(listOf("Gross profit", gross.cell()))
            otherIncome.forEach { add(listOf("Income: ${it.first}", it.second.cell())) }
            expenses.forEach { add(listOf("Expense: ${it.first}", it.second.cell())) }
        }
        return ReportTable(
            "Profit and loss",
            listOf(col("particulars", "Particulars"), money("amount", "Amount")),
            rows,
            listOf("Net profit", netProfit.cell()),
            notes = listOf("From posted journal entries; stock at moving weighted average. Not a statutory statement."),
        )
    }

    /** GSTR-3B table 3.1 (outward) and 4 (ITC) from posted vouchers of the period. */
    fun gstr3b(data: ReportData): ReportTable {
        fun rows(types: Set<VoucherType>, treatments: Set<TaxTreatment>, keep: (Voucher, TaxRow) -> Boolean = { _, _ -> true }) = TaxAmounts.sum(
            data.posted.filter { it.type in types }.flatMap { v ->
                v.taxRows(data.firm.stateCode, data.rounding).filter { it.treatment in treatments && keep(v, it) }.map { it.amounts.negateIf(v.type.isReturn()) }
            },
        )
        val outward = setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE)
        val inward = setOf(VoucherType.PURCHASE, VoucherType.DEBIT_NOTE, VoucherType.EXPENSE)
        val gst = setOf(TaxTreatment.TAXABLE)
        // Exports and SEZ supplies with IGST are zero-rated supplies (3.1(b)); outward reverse
        // charge supplies carry no tax for the supplier and are reported with nil/exempt (3.1(c)).
        val a = rows(outward, gst) { v, _ -> v.export == null && !v.reverseCharge }
        val b = rows(outward, setOf(TaxTreatment.ZERO_RATED)) + rows(outward, gst) { v, _ -> v.export != null && !v.reverseCharge }
        val c = rows(outward, setOf(TaxTreatment.NIL_RATED, TaxTreatment.EXEMPT)) +
            rows(outward, gst) { v, _ -> v.reverseCharge }.let { TaxAmounts(taxable = it.taxable) }
        val d = rows(inward, gst) { v, _ -> v.reverseCharge }
        val e = rows(outward, setOf(TaxTreatment.NON_GST))
        val itcRcm = rows(inward, gst) { v, t -> v.reverseCharge && t.itcEligible }.copy(taxable = Money.ZERO)
        val itcOther = rows(inward, gst) { v, t -> !v.reverseCharge && t.itcEligible }.copy(taxable = Money.ZERO)
        val blocked = rows(inward, gst) { _, t -> !t.itcEligible }.copy(taxable = Money.ZERO)
        val itc = itcRcm + itcOther
        val out = a + b + d
        fun r(label: String, t: TaxAmounts, withTaxable: Boolean = true) =
            listOf(label, if (withTaxable) t.taxable.cell() else "", t.igst.cell(), t.cgst.cell(), t.sgst.cell(), t.cess.cell())
        val netTax = TaxAmounts(Money.ZERO, out.igst - itc.igst, out.cgst - itc.cgst, out.sgst - itc.sgst, out.cess - itc.cess)
        return ReportTable(
            "GSTR-3B summary",
            listOf(col("table", "Table"), money("taxable", "Taxable"), money("igst", "IGST"), money("cgst", "CGST"), money("sgst", "SGST"), money("cess", "Cess")),
            listOf(
                r("3.1(a) Outward taxable supplies", a),
                r("3.1(b) Outward zero rated", b),
                r("3.1(c) Nil/exempt and RCM outward", c),
                r("3.1(d) Inward reverse charge", d),
                r("3.1(e) Non-GST outward", e),
                r("4(A)(3) ITC on inward reverse charge", itcRcm, withTaxable = false),
                r("4(A)(5) All other ITC", itcOther, withTaxable = false),
                r("4(C) Net ITC available", itc, withTaxable = false),
                r("4(D)(2) Ineligible ITC (not claimed)", blocked, withTaxable = false),
            ),
            r("Tax payable after ITC", netTax, withTaxable = false),
            notes = listOf(
                "Working only: no imports, ITC reversal or cross-head set-off; reverse charge tax (3.1(d)) must be paid in cash. Verify before filing.",
            ),
        )
    }

    /**
     * Per day with activity: sales (invoices less credit notes, with tax, as in the sales register)
     * and money into and out of cash and bank accounts (as in the cash and bank book).
     */
    fun daySummary(data: ReportData): ReportTable {
        val sales = data.posted.filter { it.type == VoucherType.SALE || it.type == VoucherType.CREDIT_NOTE }.groupBy { it.date }
        val cashBank = data.accounts.filter { it.type == AccountType.CASH || it.type == AccountType.BANK }.map { it.id }.toSet()
        val cash = data.journal.filter { it.entry.accountId in cashBank && it.entry.date >= data.from && it.entry.date <= data.to }.groupBy { it.entry.date }
        var invoices = 0
        var tSales = Money.ZERO
        var tIn = Money.ZERO
        var tOut = Money.ZERO
        val rows = (sales.keys + cash.keys).sorted().map { day ->
            val list = sales[day].orEmpty()
            val n = list.count { it.type == VoucherType.SALE }
            val s = list.fold(Money.ZERO) { t, v -> if (v.type.isReturn()) t - v.total else t + v.total }
            val cashIn = cash[day].orEmpty().fold(Money.ZERO) { t, x -> t + x.entry.debit }
            val cashOut = cash[day].orEmpty().fold(Money.ZERO) { t, x -> t + x.entry.credit }
            invoices += n
            tSales += s
            tIn += cashIn
            tOut += cashOut
            listOf(day.toString(), n.toString(), s.cell(), cashIn.cell(), cashOut.cell(), (cashIn - cashOut).cell())
        }
        return ReportTable(
            "Day summary",
            listOf(col("date", "Date", ColumnKind.DATE), col("invoices", "Invoices", ColumnKind.NUMBER), money("sales", "Sales"), money("cash_in", "Cash in"), money("cash_out", "Cash out"), money("net", "Net cash")),
            rows,
            listOf("Total", invoices.toString(), tSales.cell(), tIn.cell(), tOut.cell(), (tIn - tOut).cell()),
            notes = listOf("Sales include GST, less credit notes. Cash in and out cover every cash and bank account, including transfers between them."),
        )
    }

    /**
     * GSTR-2 working: posted purchases, debit notes (negative) and expenses of the period, one row
     * per voucher and rate, with input credit eligibility and reverse charge. Its eligible ITC
     * equals GSTR-3B table 4(C).
     */
    fun gstr2(data: ReportData): ReportTable {
        val inward = setOf(VoucherType.PURCHASE, VoucherType.DEBIT_NOTE, VoucherType.EXPENSE)
        var sum = TaxAmounts()
        var eligible = TaxAmounts()
        var ineligible = TaxAmounts()
        var rcm = TaxAmounts()
        val rows = data.posted.filter { it.type in inward }.flatMap { v ->
            val party = v.partyId?.let { data.partyById[it] }
            val supplier = party?.name ?: v.ledgerAccountId?.let { data.accountById[it]?.name } ?: v.notes.orEmpty()
            val gstin = party?.gstin.orEmpty()
            v.taxRows(data.firm.stateCode, data.rounding).groupBy { Triple(it.rate, it.treatment, it.itcEligible) }.map { (key, list) ->
                val (rate, treatment, itc) = key
                val a = TaxAmounts.sum(list.map { it.amounts }).negateIf(v.type.isReturn())
                sum += a
                if (treatment == TaxTreatment.TAXABLE) {
                    val credit = a.copy(taxable = Money.ZERO)
                    if (itc) eligible += credit else ineligible += credit
                    if (v.reverseCharge) rcm += credit
                }
                listOf(
                    v.date.toString(), typeLabel(v.type), v.number.orEmpty(), v.referenceNumber.orEmpty(), supplier, gstin,
                    if (gstin.isBlank()) "Unregistered" else "B2B", rate.percentText(), treatment.name.lowercase().replace('_', ' '),
                    if (v.reverseCharge) "Yes" else "No", if (itc) "Eligible" else "Ineligible",
                ) + a.cells()
            }
        }
        fun heads(t: TaxAmounts) = "IGST ${t.igst.cell()}, CGST ${t.cgst.cell()}, SGST ${t.sgst.cell()}, cess ${t.cess.cell()}"
        return ReportTable(
            "GSTR-2 working (purchases)",
            listOf(
                col("date", "Date", ColumnKind.DATE), col("type", "Type"), col("number", "Number"), col("bill_no", "Supplier bill"), col("supplier", "Supplier"),
                col("gstin", "GSTIN"), col("section", "Section"), col("rate", "Rate %", ColumnKind.NUMBER), col("treatment", "Treatment"),
                col("rcm", "Reverse charge"), col("itc", "ITC"),
            ) + TAX_COLUMNS,
            rows,
            listOf("Total") + List(10) { "" } + sum.cells(),
            notes = listOf(
                "Eligible ITC (GSTR-3B 4(C)): ${heads(eligible)}.",
                "Ineligible ITC (not claimed): ${heads(ineligible)}.",
                "Reverse charge tax (pay in cash, then claim): ${heads(rcm)}.",
                "Working only: imports and ITC reversals are not tracked; match against GSTR-2B on the portal before claiming.",
            ),
        )
    }

    /** Batches with stock as of the period end, earliest expiry first, with days left and status. */
    fun batchExpiry(data: ReportData): ReportTable {
        val onHand = data.stock.filter { it.batchId != null && it.date <= data.to }.groupBy { it.batchId!! }
            .mapValues { (_, list) -> list.sumOf { it.quantity.scaledUnits } }
        val godowns = data.godowns.associate { it.id to it.name }
        val window = data.settings?.nearExpiryDays?.takeIf { it > 0 } ?: 30
        val rows = data.batches.mapNotNull { b -> onHand[b.id]?.takeIf { it != 0L }?.let { b to it } }
            .sortedWith(compareBy({ it.first.expiryDate == null }, { it.first.expiryDate }, { data.itemById[it.first.itemId]?.name?.lowercase() }, { it.first.batchNumber }))
            .map { (b, qty) ->
                val item = data.itemById[b.itemId]
                val days = b.expiryDate?.let { data.to.daysUntil(it) }
                val status = when {
                    days == null -> "No expiry"
                    days < 0 -> "Expired"
                    days <= window -> "Expiring soon"
                    else -> "OK"
                }
                listOf(
                    item?.name ?: b.itemId, item?.let { data.unitById[it.unitId]?.symbol }.orEmpty(), b.batchNumber, godowns[b.godownId].orEmpty(),
                    b.expiryDate?.toString().orEmpty(), Quantity.ofScaledUnits(qty).format(), days?.toString().orEmpty(), status,
                )
            }
        return ReportTable(
            "Batch and expiry report",
            listOf(
                col("item", "Item"), col("unit", "Unit"), col("batch", "Batch"), col("godown", "Godown"), col("expiry", "Expiry", ColumnKind.DATE),
                col("on_hand", "On hand", ColumnKind.QUANTITY), col("days_left", "Days left", ColumnKind.NUMBER), col("status", "Status"),
            ),
            rows,
            notes = listOf("As of ${data.to}; batches with stock only. Days left count from ${data.to}; expiring soon = within $window days (the near-expiry alert window)."),
        )
    }
}
