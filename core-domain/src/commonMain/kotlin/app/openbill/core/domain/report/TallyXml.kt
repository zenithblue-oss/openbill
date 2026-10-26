package app.openbill.core.domain.report

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.model.Money

/**
 * Tally "Import Data" XML (vouchers with the ledger masters they use) from the posted
 * journal of the period. Every voucher's entries are its own journal lines netted per
 * account, so the export ties to the ledger by construction. Party control accounts map to
 * the party's own ledger (Sundry Debtors/Creditors) and GST accounts are split into
 * CGST/SGST/IGST/Cess duty ledgers. Mapping notes: docs/09-tax-assumptions.md section 13.
 */
object TallyXml {
    private data class Ledger(val name: String, val parent: String, val gstin: String? = null, val dutyHead: String? = null, val state: String? = null)

    private data class Line(val ledger: Ledger, val amount: Money) // positive = debit

    fun export(data: ReportData): String {
        val byVoucher = data.journal.groupBy { it.entry.voucherId }
        val masters = LinkedHashMap<String, Ledger>()
        val vouchers = data.posted.filter { it.type.postsLedger() }.mapNotNull { v ->
            val lines = linesOf(v, data, byVoucher[v.id].orEmpty().map { it.entry.accountId to (it.entry.debit - it.entry.credit) })
            if (lines.isEmpty()) null else v to lines.also { l -> l.forEach { masters.getOrPut(it.ledger.name) { it.ledger } } }
        }
        return buildString {
            append("<ENVELOPE>\n")
            append(" <HEADER>\n  <TALLYREQUEST>Import Data</TALLYREQUEST>\n </HEADER>\n")
            append(" <BODY>\n  <IMPORTDATA>\n   <REQUESTDESC>\n    <REPORTNAME>Vouchers</REPORTNAME>\n")
            append("    <STATICVARIABLES>\n     <SVCURRENTCOMPANY>").append(esc(data.firm.legalName?.takeIf { it.isNotBlank() } ?: data.firm.name)).append("</SVCURRENTCOMPANY>\n    </STATICVARIABLES>\n")
            append("   </REQUESTDESC>\n   <REQUESTDATA>\n")
            masters.values.filter { it.name !in TALLY_PREDEFINED }.forEach { l ->
                append("    <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n")
                append("     <LEDGER NAME=\"").append(esc(l.name)).append("\" ACTION=\"Create\">\n")
                append("      <NAME.LIST>\n       <NAME>").append(esc(l.name)).append("</NAME>\n      </NAME.LIST>\n")
                append("      <PARENT>").append(esc(l.parent)).append("</PARENT>\n")
                if (l.parent == SUNDRY_DEBTORS || l.parent == SUNDRY_CREDITORS) append("      <ISBILLWISEON>Yes</ISBILLWISEON>\n")
                l.gstin?.let { append("      <PARTYGSTIN>").append(esc(it)).append("</PARTYGSTIN>\n") }
                l.state?.let { append("      <LEDSTATENAME>").append(esc(it)).append("</LEDSTATENAME>\n") }
                l.dutyHead?.let { append("      <TAXTYPE>GST</TAXTYPE>\n      <GSTDUTYHEAD>").append(it).append("</GSTDUTYHEAD>\n") }
                append("     </LEDGER>\n    </TALLYMESSAGE>\n")
            }
            vouchers.forEach { (v, lines) ->
                val type = voucherType(v.type)
                append("    <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n")
                append("     <VOUCHER VCHTYPE=\"").append(type).append("\" ACTION=\"Create\">\n")
                append("      <DATE>").append(v.date.toString().replace("-", "")).append("</DATE>\n")
                append("      <VOUCHERTYPENAME>").append(type).append("</VOUCHERTYPENAME>\n")
                append("      <VOUCHERNUMBER>").append(esc(v.number.orEmpty())).append("</VOUCHERNUMBER>\n")
                v.referenceNumber?.takeIf { it.isNotBlank() }?.let { append("      <REFERENCE>").append(esc(it)).append("</REFERENCE>\n") }
                v.partyId?.let { data.partyById[it] }?.let { append("      <PARTYLEDGERNAME>").append(esc(it.name)).append("</PARTYLEDGERNAME>\n") }
                v.notes?.takeIf { it.isNotBlank() }?.let { append("      <NARRATION>").append(esc(it)).append("</NARRATION>\n") }
                lines.forEach { l ->
                    val debit = l.amount > Money.ZERO
                    append("      <ALLLEDGERENTRIES.LIST>\n")
                    append("       <LEDGERNAME>").append(esc(l.ledger.name)).append("</LEDGERNAME>\n")
                    append("       <ISDEEMEDPOSITIVE>").append(if (debit) "Yes" else "No").append("</ISDEEMEDPOSITIVE>\n")
                    // Tally convention: debits are negative, credits positive.
                    append("       <AMOUNT>").append((-l.amount).format()).append("</AMOUNT>\n")
                    append("      </ALLLEDGERENTRIES.LIST>\n")
                }
                append("     </VOUCHER>\n    </TALLYMESSAGE>\n")
            }
            append("   </REQUESTDATA>\n  </IMPORTDATA>\n </BODY>\n</ENVELOPE>\n")
        }
    }

    /** Net per account, party first, then debits before credits; GST lines split by duty head. */
    private fun linesOf(v: Voucher, data: ReportData, entries: List<Pair<String, Money>>): List<Line> {
        val net = LinkedHashMap<String, Money>()
        entries.forEach { (acc, amt) -> net[acc] = (net[acc] ?: Money.ZERO) + amt }
        val out = ArrayList<Line>()
        for ((accountId, amount) in net) {
            if (amount == Money.ZERO) continue
            val account = data.accountById[accountId] ?: continue
            when (account.code) {
                AccountCode.OUTPUT_GST, AccountCode.INPUT_GST -> out += gstLines(v, data, account.code == AccountCode.OUTPUT_GST, amount)
                else -> out += Line(ledgerFor(account, v, data), amount)
            }
        }
        val partyName = v.partyId?.let { data.partyById[it]?.name }
        return out.sortedWith(compareBy({ it.ledger.name != partyName }, { it.amount < Money.ZERO }))
    }

    private fun gstLines(v: Voucher, data: ReportData, output: Boolean, amount: Money): List<Line> {
        val heads = TaxAmounts.sum(v.taxRows(data.firm.stateCode, data.rounding).map { it.amounts })
        val prefix = if (output) "Output" else "Input"
        val sign = if (amount > Money.ZERO) 1 else -1
        val magnitude = if (amount > Money.ZERO) amount else -amount
        if (heads.tax != magnitude) return listOf(Line(Ledger("$prefix GST", DUTIES), amount))
        return listOf(
            "CGST" to heads.cgst to "Central Tax", "SGST" to heads.sgst to "State Tax",
            "IGST" to heads.igst to "Integrated Tax", "Cess" to heads.cess to "Cess",
        ).filter { it.first.second != Money.ZERO }.map { (p, head) -> Line(Ledger("$prefix ${p.first}", DUTIES, dutyHead = head), p.second * sign) }
    }

    private fun ledgerFor(a: Account, v: Voucher, data: ReportData): Ledger {
        if (a.code == AccountCode.AR || a.code == AccountCode.AP) {
            val party = v.partyId?.let { data.partyById[it] }
            if (party != null) {
                val parent = if (party.type == PartyType.SUPPLIER || (party.type == PartyType.BOTH && a.code == AccountCode.AP)) SUNDRY_CREDITORS else SUNDRY_DEBTORS
                return Ledger(party.name, parent, party.gstin?.takeIf { it.isNotBlank() })
            }
            return Ledger(a.name, if (a.code == AccountCode.AR) SUNDRY_DEBTORS else SUNDRY_CREDITORS)
        }
        return when (a.code) {
            AccountCode.SALES -> Ledger("Sales", "Sales Accounts")
            AccountCode.PURCHASES -> Ledger("Purchase", "Purchase Accounts")
            AccountCode.ROUND_OFF -> Ledger("Round Off", "Indirect Expenses")
            AccountCode.CASH -> Ledger("Cash", "Cash-in-Hand")
            else -> Ledger(
                a.name,
                when (a.type) {
                    AccountType.CASH -> "Cash-in-Hand"
                    AccountType.BANK -> "Bank Accounts"
                    AccountType.EXPENSE -> "Indirect Expenses"
                    AccountType.INCOME -> "Indirect Incomes"
                    AccountType.ASSET -> "Current Assets"
                    AccountType.LIABILITY -> "Current Liabilities"
                    AccountType.EQUITY -> "Capital Account"
                },
            )
        }
    }

    private fun voucherType(t: VoucherType) = when (t) {
        VoucherType.SALE -> "Sales"
        VoucherType.PURCHASE -> "Purchase"
        VoucherType.CREDIT_NOTE -> "Credit Note"
        VoucherType.DEBIT_NOTE -> "Debit Note"
        VoucherType.PAYMENT_IN -> "Receipt"
        VoucherType.PAYMENT_OUT, VoucherType.EXPENSE -> "Payment"
        VoucherType.CONTRA -> "Contra"
        else -> "Journal"
    }

    private const val SUNDRY_DEBTORS = "Sundry Debtors"
    private const val SUNDRY_CREDITORS = "Sundry Creditors"
    private const val DUTIES = "Duties & Taxes"

    /** Ledgers every Tally company already has; creating them again is an import error. */
    private val TALLY_PREDEFINED = setOf("Cash")

    private fun esc(s: String) = buildString {
        s.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (it < ' ' && it != '\n' && it != '\t') append(' ') else append(it)
            }
        }
    }
}
