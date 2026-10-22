package app.openbill.ui.more

import app.openbill.core.domain.model.AddressKind
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyAddress
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.Gstin
import app.openbill.core.tax.TaxMode
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.invoice.DraftLine
import app.openbill.ui.invoice.InvoiceMath
import kotlinx.coroutines.flow.first

/** Fictional demo data for development and screenshots. Debug builds only. */
object SampleData {
    /** Builds a checksum-valid GSTIN for a made-up PAN, so demo parties pass validation. */
    fun demoGstin(stateCode: String, pan: String): String {
        val first14 = stateCode + pan + "1Z"
        return first14 + Gstin.checksum(first14)
    }

    suspend fun load(container: AppContainer, session: Session): String {
        val repos = container.repositories
        val firmId = session.firm.id
        val home = session.firm.stateCode
        val away = if (home == "29") "27" else "29"
        if (repos.parties.observeParties(firmId).first().any { it.name == "Western Coast Distributors" }) {
            return "Sample data is already loaded"
        }
        val now = container.now()
        val units = repos.catalog.observeUnits(firmId).first().associateBy { it.symbol }
        fun unit(symbol: String) = (units[symbol] ?: units.values.first()).id

        val parties = listOf(
            Triple("Lotus Provision Store", home, "AAKFL4821M"),
            Triple("Neelkanth Builders", home, "AAGFN7315Q"),
            Triple("Western Coast Distributors", away, "AABCW2290K"),
            Triple("Asha Tailoring Works", home, null),
            Triple("Greenleaf Cafe", away, null),
        ).mapIndexed { i, (name, state, pan) ->
            Party(
                id = container.newId(),
                firmId = firmId,
                name = name,
                type = PartyType.CUSTOMER,
                phone = "98${(45000000 + i * 1234567).toString().padStart(8, '0')}",
                gstin = pan?.let { demoGstin(state, it) },
                stateCode = state,
                openingBalance = if (i == 1) Money.of(1_250_000) else Money.ZERO,
                openingBalanceType = OpeningBalanceType.DEBIT,
                addresses = listOf(
                    PartyAddress(
                        id = container.newId(),
                        kind = AddressKind.BILLING,
                        line1 = "${12 + i * 7}, Demo Market Road",
                        city = if (state == home) session.firm.city else "Demo City",
                        stateCode = state,
                        pincode = "56000${i + 1}",
                        isDefault = true,
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            )
        }
        parties.forEach { repos.parties.saveParty(it) }

        data class Spec(val name: String, val hsn: String, val unit: String, val rate: Rate, val price: Long, val service: Boolean = false)
        val specs = listOf(
            Spec("Basmati rice 5 kg bag", "1006", "PCS", Rate.ofPercent(5), 62_500),
            Spec("Sunflower oil 1 L", "1512", "PCS", Rate.ofPercent(5), 16_500),
            Spec("Steel almirah, 2 door", "9403", "NOS", Rate.ofPercent(18), 1_249_900),
            Spec("LED bulb 9 W", "8539", "NOS", Rate.ofPercent(18), 9_900),
            Spec("Cotton shirt fabric", "5208", "MTR", Rate.ofPercent(5), 18_000),
            Spec("Wall putty 20 kg", "3214", "BOX", Rate.ofPercent(18), 74_000),
            Spec("Installation service", "995461", "HRS", Rate.ofPercent(18), 50_000, service = true),
            Spec("Notebook, 200 pages", "4820", "PCS", Rate.ofPercent(5), 6_000),
        )
        val items = specs.map { s ->
            Item(
                id = container.newId(),
                firmId = firmId,
                name = s.name,
                unitId = unit(s.unit),
                hsnSac = s.hsn,
                isService = s.service,
                taxRate = s.rate,
                salePrice = Money.of(s.price),
                trackInventory = false,
                createdAt = now,
                updatedAt = now,
            )
        }
        items.forEach { repos.catalog.saveItem(it) }

        val today = container.today()
        val fy = session.financialYearFor(today) ?: return "No financial year covers today"
        val plans = listOf(
            Triple(0, listOf(0 to "4", 1 to "6", 7 to "10"), true),
            Triple(2, listOf(2 to "2", 3 to "24"), true),
            Triple(1, listOf(5 to "3", 6 to "2.5"), true),
            Triple(4, listOf(3 to "12"), false),
        )
        var posted = 0
        for ((partyIndex, lines, post) in plans) {
            val party = parties[partyIndex]
            val draftLines = lines.map { (itemIndex, qty) ->
                val item = items[itemIndex]
                DraftLine(
                    key = container.newId(),
                    itemId = item.id,
                    description = item.name,
                    hsnSac = item.hsnSac,
                    unit = specs[itemIndex].unit,
                    quantity = Quantity.parse(qty),
                    rate = item.salePrice,
                    discount = if (itemIndex == 3) Money.of(5_000) else Money.ZERO,
                    gstRate = item.taxRate,
                    taxMode = TaxMode.EXCLUSIVE,
                )
            }
            val summary = InvoiceMath.summarize(
                draftLines,
                home,
                party.stateCode!!,
                session.settings.roundToRupee,
                session.settings.roundingMode,
            )
            val id = container.newId()
            repos.vouchers.saveDraft(
                Voucher(
                    id = id,
                    firmId = firmId,
                    financialYearId = fy.id,
                    type = VoucherType.SALE,
                    date = today,
                    partyId = party.id,
                    placeOfSupplyStateCode = party.stateCode!!,
                    lines = InvoiceMath.toVoucherLines(draftLines, summary) { it.key },
                    subtotal = summary.gross,
                    discount = summary.discount,
                    taxable = summary.taxable,
                    tax = summary.totalTax,
                    roundOff = summary.roundOff,
                    total = summary.grandTotal,
                    notes = "Thank you for your business.",
                    createdAt = container.now(),
                    updatedAt = container.now(),
                ),
            )
            if (post) {
                container.postVoucher(id, container.now())
                posted++
            }
        }
        return "Added ${parties.size} parties, ${items.size} items, $posted posted invoices and 1 draft"
    }
}
