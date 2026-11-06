package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.ChargeTaxBasis
import app.openbill.core.tax.ChargeTaxInput
import app.openbill.core.tax.ChargeTaxResult
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.TaxTreatment
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Number of vouchers and the sum of their totals. */
data class VoucherTally(val count: Int, val total: Money)

data class VoucherLine(
    val id: String,
    val lineNumber: Int,
    val itemId: String? = null,
    val description: String,
    val hsnSac: String? = null,
    val quantity: Quantity,
    val rate: Money,
    val discount: Money = Money.ZERO,
    val taxRate: Rate = Rate.ofBasisPoints(0),
    val cessRate: Rate = Rate.ofBasisPoints(0),
    val taxMode: TaxMode = TaxMode.EXCLUSIVE,
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    val taxable: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val godownId: String? = null,
    val batchId: String? = null,
    val stockDirection: StockDirection? = null,
    /** Batch number for batch-tracked items; resolved to a batch row of [godownId] on posting. */
    val batchNumber: String? = null,
    val expiryDate: LocalDate? = null,
    /** Serial/IMEI numbers for serial-tracked items; one per unit of [quantity]. */
    val serialNumbers: List<String> = emptyList(),
    /** Destination godown of a stock-transfer line ([godownId] is the source). */
    val toGodownId: String? = null,
    /** Bonus quantity given or received free: moves stock, carries no value or tax. */
    val freeQuantity: Quantity = Quantity.of(0),
    /** Purchase side: false when the GST on this line cannot be claimed as input credit. */
    val itcEligible: Boolean = true,
    /** The sales/purchase order line this invoice or bill line fulfils. */
    val sourceLineId: String? = null,
) {
    /** Quantity that moves stock: billed plus free. */
    val stockQuantity: Quantity get() = Quantity.ofScaledUnits(quantity.scaledUnits + freeQuantity.scaledUnits)

    init {
        require(freeQuantity >= Quantity.of(0)) { "VoucherLine free quantity cannot be negative" }
        require(id.isNotBlank()) { "VoucherLine id cannot be blank" }
        require(lineNumber > 0) { "VoucherLine lineNumber must be positive" }
        require(description.isNotBlank()) { "VoucherLine description cannot be blank" }
        require(quantity >= Quantity.of(0)) { "VoucherLine quantity cannot be negative" }
        require(rate >= Money.ZERO) { "VoucherLine rate cannot be negative" }
        require(discount >= Money.ZERO) { "VoucherLine discount cannot be negative" }
        require(taxable >= Money.ZERO) { "VoucherLine taxable cannot be negative" }
        require(tax >= Money.ZERO) { "VoucherLine tax cannot be negative" }
        require(total >= Money.ZERO) { "VoucherLine total cannot be negative" }
        require(serialNumbers.none { it.isBlank() }) { "VoucherLine serial numbers cannot be blank" }
        require(serialNumbers.distinct().size == serialNumbers.size) {
            "VoucherLine serial numbers must be unique"
        }
    }
}

/**
 * An invoice additional charge (freight, packing, insurance, ...).
 *
 * [amount] is what the user entered: tax-exclusive for [TaxMode.EXCLUSIVE], tax-inclusive for
 * [TaxMode.INCLUSIVE]. [taxBasis] says how GST is determined (see [ChargeTaxBasis]);
 * [treatment], [taxRate] and [cessRate] are the values actually applied, resolved from the
 * principal line for [ChargeTaxBasis.PRINCIPAL_SUPPLY], so a posted document never depends on
 * re-running the rule. [taxAmount] and [total] are computed by the tax engine; see
 * [withTaxResult]. [hsnSac] is the optional SAC used for HSN summaries.
 */
/**
 * One rate portion of a charge that was apportioned across several rate groups. [taxableValue]
 * plus tax equals [total]; portions sum to the parent charge.
 */
data class VoucherChargePortion(
    val treatment: TaxTreatment,
    val taxRate: Rate,
    val cessRate: Rate,
    val taxableValue: Money,
    val taxAmount: Money,
    val total: Money,
) {
    init {
        require(taxableValue >= Money.ZERO && taxAmount >= Money.ZERO) {
            "VoucherChargePortion amounts cannot be negative"
        }
        require(total == taxableValue + taxAmount) {
            "VoucherChargePortion total must equal taxable value plus tax"
        }
        if (treatment != TaxTreatment.TAXABLE) {
            require(taxRate.units == 0L && cessRate.units == 0L && taxAmount == Money.ZERO) {
                "Non-taxable VoucherChargePortion cannot carry GST or cess"
            }
        }
    }
}

data class VoucherCharge(
    val id: String,
    val name: String,
    val amount: Money,
    val taxRate: Rate = Rate.ofBasisPoints(0),
    val cessRate: Rate = Rate.ofBasisPoints(0),
    val taxAmount: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val sortOrder: Int = 0,
    val taxBasis: ChargeTaxBasis = ChargeTaxBasis.PRINCIPAL_SUPPLY,
    val taxMode: TaxMode = TaxMode.EXCLUSIVE,
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    val hsnSac: String? = null,
    /**
     * Per-rate portions when the charge was apportioned across more than one rate group;
     * empty for a single-rate charge. When present, [taxRate], [cessRate] and [treatment] are
     * the highest-ranked portion's (a representative) and ledgers/summaries use the portions.
     */
    val portions: List<VoucherChargePortion> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "VoucherCharge id cannot be blank" }
        require(name.isNotBlank()) { "VoucherCharge name cannot be blank" }
        require(amount >= Money.ZERO) { "VoucherCharge amount cannot be negative" }
        require(taxAmount >= Money.ZERO) { "VoucherCharge tax cannot be negative" }
        require(total >= Money.ZERO) { "VoucherCharge total cannot be negative" }
        require(total >= taxAmount) { "VoucherCharge tax cannot exceed its total" }
        require(sortOrder >= 0) { "VoucherCharge sortOrder cannot be negative" }
        if (treatment != TaxTreatment.TAXABLE) {
            require(taxRate.units == 0L && cessRate.units == 0L && taxAmount == Money.ZERO) {
                "Non-taxable VoucherCharge cannot carry GST or cess"
            }
        }
        if (portions.isNotEmpty()) {
            require(portions.size > 1) { "VoucherCharge portions must be empty or have 2 or more entries" }
            require(
                portions.fold(Money.ZERO) { t, p -> t + p.taxAmount } == taxAmount &&
                    portions.fold(Money.ZERO) { t, p -> t + p.total } == total,
            ) { "VoucherCharge portions must sum to the charge tax and total" }
        }
        if (taxBasis == ChargeTaxBasis.NON_TAXABLE) {
            require(treatment == TaxTreatment.NON_GST) {
                "NON_TAXABLE VoucherCharge must use the NON_GST treatment"
            }
        }
    }

    /** Taxable value of the charge: [total] less [taxAmount]. */
    val taxableValue: Money
        get() = total - taxAmount

    /** Tax-engine input for this charge. Only explicit-rate charges pass their rates. */
    fun toTaxInput(): ChargeTaxInput = ChargeTaxInput(
        amount = amount,
        basis = taxBasis,
        gstRate = if (taxBasis == ChargeTaxBasis.EXPLICIT_RATE) taxRate else Rate.ofBasisPoints(0),
        cessRate = if (taxBasis == ChargeTaxBasis.EXPLICIT_RATE) cessRate else Rate.ofBasisPoints(0),
        mode = taxMode,
        hsnSac = hsnSac,
    )

    /** Copies the applied treatment, rates and computed amounts from the tax engine. */
    fun withTaxResult(result: ChargeTaxResult): VoucherCharge {
        require(result.basis == taxBasis) { "Charge tax result basis does not match" }
        return copy(
            treatment = result.treatment,
            taxRate = result.gstRate,
            cessRate = result.cessRate,
            taxAmount = result.result.totalTax,
            total = result.result.totalAmount,
            portions = if (result.portions.size > 1) {
                result.portions.map {
                    VoucherChargePortion(
                        treatment = it.treatment,
                        taxRate = it.gstRate,
                        cessRate = it.cessRate,
                        taxableValue = it.result.taxableValue,
                        taxAmount = it.result.totalTax,
                        total = it.result.totalAmount,
                    )
                }
            } else {
                emptyList()
            },
        )
    }

    companion object {
        /** A charge outside the value of supply (no GST). */
        fun nonTaxable(id: String, name: String, amount: Money, sortOrder: Int = 0): VoucherCharge =
            VoucherCharge(
                id = id,
                name = name,
                amount = amount,
                total = amount,
                sortOrder = sortOrder,
                taxBasis = ChargeTaxBasis.NON_TAXABLE,
                treatment = TaxTreatment.NON_GST,
            )
    }
}

data class Voucher(
    val id: String,
    val firmId: String,
    val financialYearId: String,
    val type: VoucherType,
    val number: String? = null,
    val date: LocalDate,
    val dueDate: LocalDate? = null,
    val partyId: String? = null,
    val referenceVoucherId: String? = null,
    /** The supplier's own bill number on a purchase bill or the party's document number. */
    val referenceNumber: String? = null,
    /** Expense category account (EXPENSE) or contra destination account (CONTRA). */
    val ledgerAccountId: String? = null,
    val status: VoucherStatus = VoucherStatus.DRAFT,
    val placeOfSupplyStateCode: String,
    val lines: List<VoucherLine> = emptyList(),
    val charges: List<VoucherCharge> = emptyList(),
    val subtotal: Money = Money.ZERO,
    val discount: Money = Money.ZERO,
    val taxable: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val roundOff: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val notes: String? = null,
    val cancellationReason: String? = null,
    val postedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val restoredAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    /**
     * GST on this supply is paid by the recipient (reverse charge). [tax] is still computed and
     * printed, but [total] (what the party owes) excludes it.
     */
    val reverseCharge: Boolean = false,
    val transport: TransportDetails? = null,
    val export: ExportDetails? = null,
    /** GSTIN of the e-commerce operator the supply was made through. */
    val ecommerceGstin: String? = null,
    val poNumber: String? = null,
    val poDate: LocalDate? = null,
    /** E-way bill number generated elsewhere, printed as plain text. */
    val ewayBillNumber: String? = null,
    /** Firm-defined extra header fields with their values. */
    val customFields: List<DocumentField> = emptyList(),
    /** Orders only: how much has been invoiced or billed, kept in step by the repository. */
    val fulfilment: OrderFulfilment = OrderFulfilment.NONE,
    /** POS: when this draft was put on hold at the counter; held drafts are listed for resuming. */
    val heldAt: Instant? = null,
) {
    /** Supplies to SEZ and exports are inter-state whatever the place of supply. */
    fun isIntraState(firmStateCode: String): Boolean = export == null && firmStateCode == placeOfSupplyStateCode

    /** Printable document details (transport, export, PO, e-way bill, custom fields), label to value. */
    fun detailRows(date: (LocalDate) -> String = { it.toString() }): List<Pair<String, String>> = buildList {
        ecommerceGstin?.let { add("E-commerce operator GSTIN" to it) }
        poNumber?.let { add("PO no." to it) }
        poDate?.let { add("PO date" to date(it)) }
        ewayBillNumber?.let { add("E-way bill no." to it) }
        transport?.let { addAll(it.rows(date)) }
        export?.let { addAll(it.rows(date)) }
        customFields.forEach { add(it.label to it.value) }
    }

    init {
        require(id.isNotBlank()) { "Voucher id cannot be blank" }
        require(firmId.isNotBlank()) { "Voucher firmId cannot be blank" }
        require(financialYearId.isNotBlank()) { "Voucher financialYearId cannot be blank" }
        require(placeOfSupplyStateCode.length == 2) {
            "Voucher place of supply state code must have two characters"
        }
        require(lines.map { it.lineNumber }.distinct().size == lines.size) {
            "Voucher line numbers must be unique"
        }
        require(lines.map { it.id }.distinct().size == lines.size) {
            "Voucher line ids must be unique"
        }
        require(charges.map { it.sortOrder }.distinct().size == charges.size) {
            "Voucher charge sort orders must be unique"
        }
        require(charges.map { it.id }.distinct().size == charges.size) {
            "Voucher charge ids must be unique"
        }
        require(dueDate == null || dueDate >= date) {
            "Voucher due date cannot precede the voucher date"
        }
        if (status == VoucherStatus.POSTED) {
            require(number != null) { "Posted voucher must have a number" }
            require(postedAt != null) { "Posted voucher must have postedAt" }
        }
        if (status == VoucherStatus.CANCELLED) {
            require(cancelledAt != null) { "Cancelled voucher must have cancelledAt" }
            require(!cancellationReason.isNullOrBlank()) {
                "Cancelled voucher must have a cancellation reason"
            }
        }
        require(updatedAt >= createdAt) { "Voucher updatedAt cannot precede createdAt" }
    }
}

/** How goods travel; optional, all fields free text except the date and distance. */
data class TransportDetails(
    val transporterName: String? = null,
    /** Transporter GSTIN or the 15-character transporter ID. */
    val transporterId: String? = null,
    /** Road, Rail, Air or Ship. */
    val mode: String? = null,
    val vehicleNumber: String? = null,
    /** LR, RR or airway bill number. */
    val docNumber: String? = null,
    val docDate: LocalDate? = null,
    val distanceKm: Int? = null,
) {
    init {
        require(distanceKm == null || distanceKm in 0..9_999) { "Transport distance must be 0 to 9999 km" }
    }

    fun isEmpty(): Boolean = this == TransportDetails()

    fun rows(date: (LocalDate) -> String = { it.toString() }): List<Pair<String, String>> = listOfNotNull(
        transporterName?.let { "Transporter" to it },
        transporterId?.let { "Transporter ID" to it },
        mode?.let { "Transport mode" to it },
        vehicleNumber?.let { "Vehicle no." to it },
        docNumber?.let { "LR/doc no." to it },
        docDate?.let { "LR/doc date" to date(it) },
        distanceKm?.let { "Distance" to "$it km" },
    )
}

/** Export or SEZ supply; [code] is the GSTR-1 export type or SEZ invoice type. */
enum class ExportType(val code: String, val label: String, val withTax: Boolean, val sez: Boolean) {
    EXPORT_WITH_TAX("WPAY", "Export with IGST paid", true, false),
    EXPORT_UNDER_LUT("WOPAY", "Export under LUT/bond (no IGST)", false, false),
    SEZ_WITH_TAX("SEZWP", "SEZ supply with IGST paid", true, true),
    SEZ_UNDER_LUT("SEZWOP", "SEZ supply under LUT/bond (no IGST)", false, true),
    ;

    companion object {
        fun fromCode(code: String): ExportType? = entries.firstOrNull { it.code == code }
    }
}

data class ExportDetails(
    val type: ExportType,
    val shippingBillNumber: String? = null,
    val shippingBillDate: LocalDate? = null,
    /** Six-character port code, e.g. INBOM1. */
    val portCode: String? = null,
    /** LUT or bond reference for supplies without tax. */
    val lutReference: String? = null,
    /** Currency and amount in it, e.g. "USD 1,200.00 at 83.10"; text only, totals stay in rupees. */
    val currencyNote: String? = null,
) {
    fun rows(date: (LocalDate) -> String = { it.toString() }): List<Pair<String, String>> = listOfNotNull(
        "Supply type" to type.label,
        shippingBillNumber?.let { "Shipping bill no." to it },
        shippingBillDate?.let { "Shipping bill date" to date(it) },
        portCode?.let { "Port code" to it },
        lutReference?.let { "LUT/bond ref." to it },
        currencyNote?.let { "Currency" to it },
    )
}

/** One firm-defined header field on a document. */
data class DocumentField(val label: String, val value: String) {
    init {
        require(label.isNotBlank() && value.isNotBlank()) { "Document field label and value are required" }
        require('\t' !in label && '\n' !in label && '\t' !in value && '\n' !in value) { "Document fields are single-line" }
    }

    companion object {
        /** Stored as one "label<TAB>value" pair per line. */
        fun encode(fields: List<DocumentField>): String? = fields.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.label}\t${it.value}" }

        fun decode(text: String?): List<DocumentField> = text.orEmpty().split('\n').mapNotNull { row ->
            val i = row.indexOf('\t')
            if (i <= 0 || i == row.length - 1) null else DocumentField(row.substring(0, i), row.substring(i + 1))
        }
    }
}
