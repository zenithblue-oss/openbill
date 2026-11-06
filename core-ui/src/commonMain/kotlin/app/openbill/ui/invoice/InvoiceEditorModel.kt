package app.openbill.ui.invoice

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.isOrder
import app.openbill.core.domain.model.orderTarget
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.TaxMode
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse
import app.openbill.ui.forms.Validation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** The line dialog's text inputs. */
data class LineForm(
    val key: String? = null,
    val itemId: String? = null,
    val description: String = "",
    val hsnSac: String? = null,
    val unit: String? = null,
    val quantity: String = "1",
    val rate: String = "",
    val discount: String = "",
    val gstRate: Rate = Rate.ofPercent(0),
    val inclusive: Boolean = false,
    val trackBatch: Boolean = false,
    val trackSerial: Boolean = false,
    val batchNumber: String = "",
    val expiryDate: LocalDate? = null,
    val serials: String = "",
    /** The item's alternate unit and primary units per one of it, for entering a quantity in it. */
    val altUnit: String? = null,
    val altFactor: Quantity? = null,
    /** Bonus units given or received free, on top of [quantity]. */
    val freeQuantity: String = "",
    val itcEligible: Boolean = true,
)

object LineRules {
    fun fromItem(item: Item, unitSymbol: String?, purchase: Boolean = false, altUnitSymbol: String? = null): LineForm {
        val price = if (purchase) item.purchasePrice else item.salePrice
        val stock = item.trackInventory && !item.isService
        return LineForm(
            itemId = item.id,
            description = item.name,
            hsnSac = item.hsnSac,
            unit = unitSymbol,
            quantity = "1",
            rate = if (price.paise == 0L) "" else price.format(),
            gstRate = item.taxRate,
            trackBatch = stock && item.trackBatch,
            trackSerial = stock && item.trackSerial,
            altUnit = altUnitSymbol?.takeIf { item.alternateUnitFactor != null },
            altFactor = item.alternateUnitFactor.takeIf { altUnitSymbol != null },
        )
    }

    /** [text] read as a quantity of the alternate unit, converted to the primary unit; null if not a quantity. */
    fun fromAltUnits(text: String, factor: Quantity): String? {
        val q = Parse.quantity(text) ?: return null
        return Quantity.ofScaledUnits(app.openbill.core.model.Rounding.multiplyDivide(q.scaledUnits, factor.scaledUnits, 10_000L, app.openbill.core.model.RoundingMode.HALF_UP)).format()
    }

    fun fromDraft(line: DraftLine, item: Item? = null, altUnitSymbol: String? = null): LineForm = LineForm(
        altUnit = altUnitSymbol?.takeIf { item?.alternateUnitFactor != null },
        altFactor = item?.alternateUnitFactor.takeIf { altUnitSymbol != null },
        trackBatch = item?.let { it.trackInventory && it.trackBatch } ?: (line.batchNumber != null),
        trackSerial = item?.let { it.trackInventory && it.trackSerial } ?: line.serialNumbers.isNotEmpty(),
        batchNumber = line.batchNumber.orEmpty(),
        expiryDate = line.expiryDate,
        serials = line.serialNumbers.joinToString("\n"),
        freeQuantity = if (line.freeQuantity.scaledUnits == 0L) "" else line.freeQuantity.format(),
        itcEligible = line.itcEligible,
        key = line.key,
        itemId = line.itemId,
        description = line.description,
        hsnSac = line.hsnSac,
        unit = line.unit,
        quantity = line.quantity.format(),
        rate = line.rate.format(),
        discount = when {
            line.discountPercent != null -> Fmt.percent(line.discountPercent!!) + "%"
            line.discount.paise == 0L -> ""
            else -> line.discount.format()
        },
        gstRate = line.gstRate,
        inclusive = line.taxMode == TaxMode.INCLUSIVE,
    )

    /** Discount accepts an amount ("25") or a percentage of the line value ("10%"). */
    fun discountAmount(text: String, gross: Money): Money? {
        val t = text.trim()
        if (t.isEmpty()) return Money.ZERO
        return if (t.endsWith("%")) {
            val pct = Parse.percent(t) ?: return null
            InvoiceMath.percentOf(gross, pct)
        } else {
            Parse.money(t)
        }
    }

    /**
     * Live total for the line editor (taxable value plus GST, no invoice-level rounding), or
     * null while the inputs are incomplete or invalid.
     */
    fun previewTotal(
        form: LineForm,
        firmStateCode: String,
        placeOfSupplyStateCode: String,
        rounding: app.openbill.core.model.RoundingMode,
        export: app.openbill.core.domain.model.ExportType? = null,
    ): Money? {
        val line = validate(form) { "preview" }.first ?: return null
        return runCatching {
            InvoiceMath.summarize(listOf(line), firmStateCode, placeOfSupplyStateCode, false, rounding, export = export).grandTotal
        }.getOrNull()
    }

    fun validate(form: LineForm, newKey: () -> String): Pair<DraftLine?, Map<String, String>> {
        val errors = mutableMapOf<String, String>()
        if (form.description.isBlank()) errors["description"] = "Description is required"
        val qty = Parse.quantity(form.quantity)
        if (qty == null || qty <= Quantity.of(0)) errors["quantity"] = "Enter a quantity above zero"
        val rate = Parse.money(form.rate)
        if (rate == null || rate.paise < 0) errors["rate"] = "Enter a valid rate"
        Validation.hsnError(form.hsnSac.orEmpty())?.let { errors["hsn"] = it }
        if (form.trackBatch && form.batchNumber.isBlank()) errors["batch"] = "Enter the batch number"
        val free = if (form.freeQuantity.isBlank()) Quantity.of(0) else Parse.quantity(form.freeQuantity)
        if (free == null || free < Quantity.of(0)) errors["free"] = "Enter a quantity, or leave it empty"
        val serials = serialList(form.serials)
        if (form.trackSerial && qty != null && free != null) {
            val units = Quantity.ofScaledUnits(qty.scaledUnits + free.scaledUnits)
            when {
                units.scaledUnits % 10_000L != 0L -> errors[if (free.scaledUnits % 10_000L != 0L) "free" else "quantity"] = "Serial-tracked items need a whole quantity"
                serials.size.toLong() != units.scaledUnits / 10_000L -> errors["serials"] = "Enter ${units.format()} serial numbers (${serials.size} entered)"
                serials.distinct().size != serials.size -> errors["serials"] = "A serial number is repeated"
            }
        }
        if (errors.isNotEmpty()) return null to errors
        val gross = InvoiceMath.gross(qty!!, rate!!)
        val discount = discountAmount(form.discount, gross)
        if (discount == null || discount.paise < 0) {
            errors["discount"] = "Enter an amount or a percentage like 10%"
        } else if (discount > gross) {
            errors["discount"] = "Discount cannot exceed the line value"
        }
        if (errors.isNotEmpty()) return null to errors
        return DraftLine(
            key = form.key ?: newKey(),
            itemId = form.itemId,
            description = form.description.trim(),
            hsnSac = form.hsnSac?.trim()?.ifBlank { null },
            unit = form.unit,
            quantity = qty,
            rate = rate,
            discount = discount!!,
            gstRate = form.gstRate,
            taxMode = if (form.inclusive) TaxMode.INCLUSIVE else TaxMode.EXCLUSIVE,
            discountPercent = form.discount.trim().takeIf { it.endsWith("%") }?.let { Parse.percent(it) },
            batchNumber = form.batchNumber.trim().takeIf { form.trackBatch && it.isNotEmpty() },
            expiryDate = form.expiryDate.takeIf { form.trackBatch },
            serialNumbers = if (form.trackSerial) serials else emptyList(),
            freeQuantity = free!!,
            itcEligible = form.itcEligible,
        ) to emptyMap()
    }

    fun serialList(text: String): List<String> = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

data class InvoiceEditorState(
    val loaded: Boolean = false,
    val voucherId: String? = null,
    val type: VoucherType = VoucherType.SALE,
    /** Supplier's bill number (purchases) or the party's document number. */
    val referenceNumber: String = "",
    /** The bill or invoice a return (debit/credit note) reverses. */
    val referenceVoucherId: String? = null,
    val godownId: String? = null,
    val party: Party? = null,
    val placeOfSupply: String,
    val dateText: String,
    val notes: String = "",
    /** Reverse charge, export/SEZ, transport and the other optional header fields. */
    val fields: DocFieldsForm = DocFieldsForm(),
    val lines: List<DraftLine> = emptyList(),
    val charges: List<DraftCharge> = emptyList(),
    /** Totals in the document currency ([currency], or rupees). */
    val summary: TaxSummary? = null,
    /** Foreign-currency invoices: ISO code, and the rupee totals that are saved, posted and filed. */
    val currency: String? = null,
    val inrSummary: TaxSummary? = null,
    val calcError: String? = null,
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val failure: String? = null,
    /** Bumped on each rejected save so the screen can scroll the first error into view. */
    val rejectedSaves: Int = 0,
    /** True once the user has changed anything since the editor opened or last saved. */
    val dirty: Boolean = false,
    /** Shown once when leaving zero-rated export with custom lines still at the stored 0%. */
    val rateNotice: String? = null,
)

class InvoiceEditorModel(
    private val container: AppContainer,
    private val session: Session,
    private val voucherId: String?,
    private val scope: CoroutineScope,
    private val newType: VoucherType = VoucherType.SALE,
    /** A posted purchase bill or sale to prefill a return (debit/credit note) from, or a document to convert. */
    private val returnOf: String? = null,
) {
    private val firm = session.firm
    private val labels get() = CustomFieldLabels.load(container.platform.settingsStore, firm.id)
    private val _state = MutableStateFlow(
        InvoiceEditorState(
            loaded = voucherId == null && returnOf == null,
            type = newType,
            godownId = session.settings.defaultGodownId,
            placeOfSupply = firm.stateCode,
            dateText = container.today().toString(),
            fields = DocFieldsForm(custom = labels.map { it to "" }),
        ).recalculated(),
    )
    val state: StateFlow<InvoiceEditorState> = _state
    private var existing: Voucher? = null
    /** Keys of custom lines loaded from a zero-rated draft (their real rate was not stored). */
    private var zeroRatedCustom: Set<String> = emptySet()

    init {
        if (voucherId != null) scope.launch { load(voucherId) }
        else if (returnOf != null) scope.launch { load(returnOf, asReturn = true) }
    }

    private suspend fun load(id: String, asReturn: Boolean = false) {
        val repos = container.repositories
        val found = repos.vouchers.findVoucher(id)
        val v = if (asReturn && found != null) {
            // A quotation/proforma/challan converts to an invoice (a quotation also to a sales
            // order), an order to its invoice or bill for what is still pending; a bill or
            // invoice to its return.
            val convert = found.type.isIssueOnly()
            if (found.status != VoucherStatus.POSTED) {
                _state.update { it.copy(loaded = true, failure = if (convert) "Issue the document before converting it" else "Only posted documents can be returned") }
                return
            }
            val order = found.type.isOrder()
            val earlier = if (order) repos.vouchers.observeConversions(found.id).first() else emptyList()
            val lines = if (order) {
                Orders.conversionLines(found, earlier, container::newId) ?: run {
                    _state.update { it.copy(loaded = true, failure = "Nothing is pending on this order: it is fully invoiced, or a draft already holds the rest") }
                    return
                }
            } else {
                found.lines.map { it.copy(id = container.newId()) }
            }
            // Charges (freight, packing) are billed once, with the first conversion of an order.
            val chargesOnce = !order || earlier.none { it.status != VoucherStatus.CANCELLED }
            found.copy(
                type = when {
                    order -> found.type.orderTarget()!!
                    convert && found.type == VoucherType.QUOTATION && newType == VoucherType.SALES_ORDER -> VoucherType.SALES_ORDER
                    convert -> VoucherType.SALE
                    found.type == VoucherType.PURCHASE -> VoucherType.DEBIT_NOTE
                    else -> VoucherType.CREDIT_NOTE
                },
                referenceVoucherId = found.id,
                referenceNumber = if (convert) null else found.number,
                status = VoucherStatus.DRAFT,
                number = null,
                date = container.today(),
                lines = lines,
                charges = if (convert && chargesOnce) found.charges.map { it.copy(id = container.newId()) } else emptyList(),
                notes = if (convert) found.notes else null,
                fulfilment = app.openbill.core.domain.model.OrderFulfilment.NONE,
                // A bill made from our purchase order quotes it.
                poNumber = if (found.type == VoucherType.PURCHASE_ORDER) found.number else found.poNumber,
                poDate = if (found.type == VoucherType.PURCHASE_ORDER) found.date else found.poDate,
            )
        } else {
            found
        }
        if (v == null || v.status != VoucherStatus.DRAFT) {
            _state.update { it.copy(loaded = true, failure = "Only drafts can be edited") }
            return
        }
        if (!asReturn) existing = v
        val party = v.partyId?.let { repos.parties.findParty(it) }
        val units = repos.catalog.observeUnits(firm.id).first().associateBy { it.id }
        // Zero-rated exports store 0% lines; reload the item's own rate so switching back to a
        // regular supply taxes them again. Custom lines have no master rate: [setFields] prompts.
        val zeroRated = v.export?.type?.withTax == false
        val lines = v.lines.map { line ->
            val item = line.itemId?.let { repos.catalog.findItem(it) }
            val draft = InvoiceMath.fromVoucherLine(line, item?.let { units[it.unitId]?.symbol })
            if (zeroRated && item != null) draft.copy(gstRate = item.taxRate) else draft
        }
        zeroRatedCustom = if (zeroRated) lines.filter { it.itemId == null }.map { it.key }.toSet() else emptySet()
        _state.value = InvoiceEditorState(
            loaded = true,
            voucherId = if (asReturn) null else v.id,
            type = v.type,
            referenceNumber = v.referenceNumber.orEmpty(),
            referenceVoucherId = v.referenceVoucherId,
            godownId = v.lines.firstNotNullOfOrNull { it.godownId } ?: session.settings.defaultGodownId,
            party = party,
            placeOfSupply = v.placeOfSupplyStateCode,
            dateText = v.date.toString(),
            notes = v.notes.orEmpty(),
            // A return keeps the tax flags of what it reverses, not its transport or PO.
            fields = DocFieldRules.from(v, labels, keepShipping = !asReturn || found?.type?.isIssueOnly() == true),
            lines = lines,
            charges = v.charges.sortedBy { it.sortOrder }.map(InvoiceMath::fromVoucherCharge),
        ).recalculated()
    }

    private fun InvoiceEditorState.recalculated(): InvoiceEditorState = runCatching {
        val export = fields.exportType.takeIf { DocFieldRules.supportsExport(type) }
        val fx = DocFieldRules.exchange(fields).takeIf { export != null }
        // Foreign-currency invoices are not rounded to the rupee: the buyer pays the exact amount.
        fun calc(rate: app.openbill.core.model.Quantity?) = InvoiceMath.summarize(
            lines, firm.stateCode, placeOfSupply, session.settings.roundToRupee && fx == null, session.settings.roundingMode, charges,
            reverseCharge = fields.reverseCharge && DocFieldRules.supportsReverseCharge(type),
            export = export,
            exchangeRate = rate,
        )
        Triple(calc(null), fx?.first, fx?.let { calc(it.second) })
    }.fold(
        onSuccess = { (s, code, inr) -> copy(summary = s, currency = code, inrSummary = inr, calcError = null) },
        onFailure = { copy(summary = null, currency = null, inrSummary = null, calcError = it.message ?: "Cannot compute totals") },
    )

    private fun mutate(transform: (InvoiceEditorState) -> InvoiceEditorState) {
        _state.update { transform(it).copy(dirty = true).recalculated() }
    }

    fun setParty(party: Party) = mutate {
        val pos = if (it.fields.exportType?.sez == false) EXPORT_POS
        else party.shipToStateCode() ?: party.stateCode ?: Validation.stateFromGstin(party.gstin.orEmpty()) ?: firm.stateCode
        it.copy(party = party, placeOfSupply = pos, errors = it.errors - "party" - "reverseCharge" - "export")
    }

    fun setPlaceOfSupply(code: String) = mutate { it.copy(placeOfSupply = code) }

    /** Exports take place of supply 96 (other countries); leaving export goes back to the party's state. */
    fun setFields(f: DocFieldsForm) = mutate { s ->
        val wasExport = s.fields.exportType?.sez == false
        val isExport = f.exportType?.sez == false
        val pos = when {
            isExport -> EXPORT_POS
            wasExport -> s.party?.let { p -> p.shipToStateCode() ?: p.stateCode } ?: firm.stateCode
            else -> s.placeOfSupply
        }
        val stillZero = s.lines.filter { it.key in zeroRatedCustom && it.gstRate.units == 0L }
        val notice = if (f.exportType?.withTax != false && stillZero.isNotEmpty()) {
            zeroRatedCustom = emptySet()
            "These custom lines were saved zero-rated for export and are still at 0% GST: " +
                stillZero.joinToString { it.description } + ". Open each line and set its GST rate."
        } else {
            s.rateNotice
        }
        s.copy(fields = f, placeOfSupply = pos, errors = s.errors - FIELD_ERRORS, rateNotice = notice)
    }

    fun dismissRateNotice() = _state.update { it.copy(rateNotice = null) }

    fun setCustomLabels(labels: List<String>) = CustomFieldLabels.save(container.platform.settingsStore, firm.id, labels)
    fun setDate(text: String) = mutate { it.copy(dateText = text, errors = it.errors - "date") }
    fun setNotes(text: String) = mutate { it.copy(notes = text) }
    fun setReferenceNumber(text: String) = mutate { it.copy(referenceNumber = text) }
    fun setGodown(id: String) = mutate { it.copy(godownId = id) }

    fun upsertLine(line: DraftLine) = mutate { s ->
        val idx = s.lines.indexOfFirst { it.key == line.key }
        // The line form does not carry the order link; keep it across edits.
        val lines = if (idx >= 0) s.lines.toMutableList().also { it[idx] = line.copy(sourceLineId = s.lines[idx].sourceLineId) } else s.lines + line
        s.copy(lines = lines, errors = s.errors - "lines")
    }

    fun removeLine(key: String) = mutate { s -> s.copy(lines = s.lines.filterNot { it.key == key }) }

    fun upsertCharge(charge: DraftCharge) = mutate { s ->
        val idx = s.charges.indexOfFirst { it.key == charge.key }
        s.copy(charges = if (idx >= 0) s.charges.toMutableList().also { it[idx] = charge } else s.charges + charge)
    }

    fun removeCharge(key: String) = mutate { s -> s.copy(charges = s.charges.filterNot { it.key == key }) }

    fun newKey(): String = container.newId()

    fun previewTotal(form: LineForm): Money? = _state.value.let { s ->
        LineRules.previewTotal(form, firm.stateCode, s.placeOfSupply, session.settings.roundingMode, s.fields.exportType.takeIf { DocFieldRules.supportsExport(s.type) })
    }

    fun validateForSave(post: Boolean): Map<String, String> {
        val s = _state.value
        return DocFieldRules.validate(s.fields, s.type, s.party, post) + buildMap {
            val date = Parse.date(s.dateText)
            if (date == null) put("date", "Use YYYY-MM-DD, e.g. ${container.today()}")
            else if (session.financialYearFor(date) == null) put("date", "No financial year covers this date")
            if (post && s.party == null) put("party", "Choose a party")
            if (post && s.lines.isEmpty()) put("lines", "Add at least one item")
            if (s.summary == null) put("lines", s.calcError ?: "Totals could not be computed")
            else if (post && s.summary.grandTotal <= Money.ZERO) put("lines", "Invoice total must be above zero")
        }
    }

    /** False until an existing draft has loaded (or if it could not be), so a save never forks a new voucher. */
    fun canSave(s: InvoiceEditorState = _state.value): Boolean = s.loaded && (voucherId == null || existing != null)

    /**
     * Saves the draft, and posts it (allocating the number) when [post] is true. [hold] parks the
     * draft as a POS held bill; otherwise a held draft stays held until it is posted. [poster]
     * replaces the plain post (POS posts and records payments in one transaction).
     */
    fun save(
        post: Boolean,
        hold: Boolean = false,
        poster: suspend (voucherId: String) -> Unit = { container.postVoucher(it, container.now()) },
        onDone: (voucherId: String, posted: Boolean) -> Unit,
    ) {
        if (!canSave()) return
        val errors = validateForSave(post)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, rejectedSaves = it.rejectedSaves + 1) }
            return
        }
        val s = _state.value
        _state.update { it.copy(saving = true, failure = null) }
        scope.launch {
            val now = container.now()
            val date = Parse.date(s.dateText)!!
            val fy = session.financialYearFor(date)!!
            // Rupee totals are what is stored; a foreign-currency invoice keeps its entered rates beside them.
            val summary = s.inrSummary ?: s.summary!!
            val id = existing?.id ?: container.newId()
            val f = s.fields
            val export = DocFieldRules.export(f).takeIf { DocFieldRules.supportsExport(s.type) }
            val fxRate = export?.exchangeRate
            val voucher = Voucher(
                reverseCharge = f.reverseCharge && DocFieldRules.supportsReverseCharge(s.type),
                transport = DocFieldRules.transport(f),
                export = export,
                ecommerceGstin = DocFieldRules.ecommerceGstin(f).takeIf { DocFieldRules.supportsEcommerce(s.type) },
                poNumber = DocFieldRules.clean(f.poNumber).ifEmpty { null },
                poDate = f.poDate,
                ewayBillNumber = f.ewayBillNumber.filterNot { it == ' ' }.ifEmpty { null },
                customFields = DocFieldRules.custom(f),
                id = id,
                firmId = firm.id,
                financialYearId = fy.id,
                type = s.type,
                date = date,
                referenceVoucherId = s.referenceVoucherId,
                referenceNumber = s.referenceNumber.trim().ifBlank { null },
                partyId = s.party?.id,
                status = VoucherStatus.DRAFT,
                placeOfSupplyStateCode = s.placeOfSupply,
                lines = InvoiceMath.toVoucherLines(s.lines, summary, s.godownId, export?.type, fxRate) { it.key },
                charges = InvoiceMath.toVoucherCharges(s.charges, summary, fxRate) { it.key },
                subtotal = summary.gross,
                discount = summary.discount,
                // Header taxable value and tax include charges, so total = taxable + tax + roundOff.
                taxable = summary.taxable + summary.chargesTaxable,
                tax = summary.totalTax,
                roundOff = summary.roundOff,
                total = summary.grandTotal,
                notes = s.notes.trim().ifBlank { null },
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                // Kept through a failed post so the bill stays in the held list.
                heldAt = if (hold) now else existing?.heldAt,
            )
            val saved = runCatching { container.repositories.vouchers.saveDraft(voucher) }
            if (saved.isFailure) {
                _state.update { it.copy(saving = false, failure = saved.exceptionOrNull()?.message ?: "Could not save") }
                return@launch
            }
            existing = voucher
            _state.update { it.copy(voucherId = id, dirty = false) }
            if (!post) {
                onDone(id, false)
                return@launch
            }
            runCatching { poster(id) }
                .onSuccess { onDone(id, true) }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, failure = "Saved as draft, but posting failed: ${e.message}") }
                }
        }
    }

    fun parsedDate(): LocalDate? = Parse.date(_state.value.dateText)

    /** Records that the user went ahead despite the filing-period warning. */
    fun acknowledgeFilingPeriod(voucherId: String?, action: String) {
        scope.launch {
            runCatching {
                container.repositories.audit.record(
                    app.openbill.core.domain.model.AuditEvent(
                        id = container.newId(), firmId = firm.id, entityType = "voucher", entityId = voucherId ?: "new",
                        voucherId = voucherId, action = "filing_period_warning_acknowledged",
                        detailsJson = "{\"action\":\"$action\",\"date\":\"${_state.value.dateText}\"}", createdAt = container.now(),
                    ),
                )
            }
        }
    }

    private companion object {
        /** GSTR-1 place of supply for exports: other countries. */
        const val EXPORT_POS = "96"
        val FIELD_ERRORS = setOf("eco", "transporterId", "vehicle", "distance", "eway", "port", "reverseCharge", "export", "currency", "exchangeRate")
    }
}
