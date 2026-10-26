package app.openbill.core.domain.report

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.domain.service.LedgerJournalDerivation
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Fixture firm (Maharashtra, GSTIN 27ABCDE1234F1Z0) for October 2026: a B2B sale, a B2C large
 * inter-state sale, a B2C small service sale, a credit note, an inter-state purchase and a
 * cancelled sale. Journal lines come from the real [LedgerJournalDerivation].
 */
object ReportFixture {
    val at: Instant = Instant.parse("2026-10-01T00:00:00Z")
    const val F = "F1"
    val firm = Firm(id = F, name = "Sharma Traders", gstin = "27ABCDE1234F1Z0", stateCode = "27", addressLine1 = "1 Market Road", city = "Pune", createdAt = at, updatedAt = at)
    val units = listOf(UnitOfMeasure("U1", F, "Piece", "pcs", createdAt = at, updatedAt = at))
    val items = listOf(
        Item(id = "I1", firmId = F, name = "Steel Bolt", unitId = "U1", hsnSac = "7318", taxRate = Rate.ofPercent(18), createdAt = at, updatedAt = at),
        Item(id = "I2", firmId = F, name = "Water Pump", unitId = "U1", hsnSac = "8413", taxRate = Rate.ofPercent(18), trackInventory = false, createdAt = at, updatedAt = at),
        Item(id = "I3", firmId = F, name = "Fitting Service", unitId = "U1", hsnSac = "9987", isService = true, trackInventory = false, taxRate = Rate.ofPercent(12), createdAt = at, updatedAt = at),
    )
    val parties = listOf(
        Party(id = "P1", firmId = F, name = "Acme Stores", type = PartyType.CUSTOMER, gstin = "27AAAPL1234C1ZE", stateCode = "27", createdAt = at, updatedAt = at),
        Party(id = "P2", firmId = F, name = "Ravi Kumar", type = PartyType.CUSTOMER, stateCode = "29", createdAt = at, updatedAt = at),
        Party(id = "P3", firmId = F, name = "Walk-in", type = PartyType.CUSTOMER, stateCode = "27", createdAt = at, updatedAt = at),
        Party(id = "P4", firmId = F, name = "Bharat Supplies", type = PartyType.SUPPLIER, gstin = "29AAAPL1234C1ZA", stateCode = "29", createdAt = at, updatedAt = at),
    )

    private fun account(code: AccountCode) = Account("A-${code.name}", F, code.accountName, code.accountType, code, createdAt = at, updatedAt = at)
    val ledger = FirmLedgerAccounts(
        account(AccountCode.AR), account(AccountCode.AP), account(AccountCode.OUTPUT_GST), account(AccountCode.INPUT_GST),
        account(AccountCode.ROUND_OFF), account(AccountCode.SALES), account(AccountCode.PURCHASES), account(AccountCode.CASH),
    )
    val accounts = listOf(ledger.accountsReceivable, ledger.accountsPayable, ledger.outputGst, ledger.inputGst, ledger.roundOff, ledger.sales, ledger.purchases, ledger.cash)

    private fun line(n: Int, itemId: String, qty: Long, rate: String, gst: Int, intra: Boolean): VoucherLine {
        val item = items.first { it.id == itemId }
        val taxable = Money.parse(rate) * qty
        val tax = Money.of(taxable.paise * gst / 100)
        return VoucherLine(
            id = "L-$n-$itemId", lineNumber = n, itemId = itemId, description = item.name, hsnSac = item.hsnSac,
            quantity = Quantity.of(qty), rate = Money.parse(rate), taxRate = Rate.ofPercent(gst),
            taxable = taxable, tax = tax, total = taxable + tax, godownId = "G1",
        )
    }

    private fun voucher(id: String, type: VoucherType, number: String, day: Int, party: String, pos: String, lines: List<VoucherLine>, status: VoucherStatus = VoucherStatus.POSTED): Voucher {
        val taxable = lines.fold(Money.ZERO) { t, l -> t + l.taxable }
        val tax = lines.fold(Money.ZERO) { t, l -> t + l.tax }
        val date = LocalDate(2026, 10, day)
        return Voucher(
            id = id, firmId = F, financialYearId = "FY1", type = type, number = number, date = date, partyId = party,
            status = status, placeOfSupplyStateCode = pos, lines = lines, subtotal = taxable, taxable = taxable, tax = tax,
            total = taxable + tax, postedAt = at, createdAt = at, updatedAt = at,
            cancelledAt = if (status == VoucherStatus.CANCELLED) at else null,
            cancellationReason = if (status == VoucherStatus.CANCELLED) "Duplicate" else null,
            referenceNumber = if (type == VoucherType.PURCHASE) "BS/77" else null,
        )
    }

    val vouchers = listOf(
        voucher("V1", VoucherType.SALE, "INV-0001", 2, "P1", "27", listOf(line(1, "I1", 10, "100.00", 18, true))),
        voucher("V2", VoucherType.SALE, "INV-0002", 5, "P2", "29", listOf(line(1, "I2", 2, "60000.00", 18, false))),
        voucher("V3", VoucherType.SALE, "INV-0003", 7, "P3", "27", listOf(line(1, "I3", 1, "500.00", 12, true))),
        voucher("V4", VoucherType.SALE, "INV-0004", 8, "P3", "27", listOf(line(1, "I3", 1, "500.00", 12, true)), VoucherStatus.CANCELLED),
        voucher("V5", VoucherType.CREDIT_NOTE, "CN-0001", 10, "P1", "27", listOf(line(1, "I1", 1, "100.00", 18, true))),
        voucher("V6", VoucherType.PURCHASE, "PUR-0001", 1, "P4", "29", listOf(line(1, "I1", 20, "50.00", 18, false))),
    )

    val journal: List<AccountTransaction> = vouchers.filter { it.status == VoucherStatus.POSTED && it.type.postsLedger() }.flatMap { v ->
        var n = 0
        LedgerJournalDerivation({ "J-${v.id}-${n++}" }).derivePosted(v, ledger, null, at).map { AccountTransaction(it, v.type, v.number, v.partyId) }
    }

    val stock = listOf(
        StockMovement("M1", F, "I1", "G1", voucherId = "V6", date = LocalDate(2026, 10, 1), quantity = Quantity.of(20), createdAt = at, unitCost = Money.parse("50.00")),
        StockMovement("M2", F, "I1", "G1", voucherId = "V1", date = LocalDate(2026, 10, 2), quantity = Quantity.of(-10), createdAt = at),
        StockMovement("M3", F, "I1", "G1", voucherId = "V5", date = LocalDate(2026, 10, 10), quantity = Quantity.of(1), createdAt = at),
    )

    val partyMovements = journal.filter { it.entry.accountId == ledger.accountsReceivable.id || it.entry.accountId == ledger.accountsPayable.id }
        .map { PartyMovement(it.partyId!!, it.entry.voucherId, it.voucherType, it.voucherNumber, it.entry.date, it.entry.debit - it.entry.credit) }

    fun data(period: ReportPeriod = ReportPeriod.month("2026-10")) = ReportData(
        firm, FirmSettings(F), period, vouchers.filter { it.date >= period.from && it.date <= period.to }, parties, items, units, accounts,
        journal, stock, partyMovements,
    )
}
