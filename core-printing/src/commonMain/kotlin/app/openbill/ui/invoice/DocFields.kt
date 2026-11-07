package app.openbill.ui.invoice

import app.openbill.core.domain.model.DocumentField
import app.openbill.core.domain.model.ExportDetails
import app.openbill.core.domain.model.ExportType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.TransportDetails
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Quantity
import app.openbill.core.tax.Gstin
import app.openbill.ui.format.Parse
import kotlinx.datetime.LocalDate

// Shared by the app's document editor (core-ui) and the CLI, so both apply the same rules.

/** Optional header fields of a document as the user types them. */
data class DocFieldsForm(
    val reverseCharge: Boolean = false,
    val transporterName: String = "",
    val transporterId: String = "",
    val transportMode: String = "",
    val vehicleNumber: String = "",
    val lrNumber: String = "",
    val lrDate: LocalDate? = null,
    val distance: String = "",
    val exportType: ExportType? = null,
    val shippingBillNumber: String = "",
    val shippingBillDate: LocalDate? = null,
    val portCode: String = "",
    val lutReference: String = "",
    val currencyNote: String = "",
    /** Foreign-currency invoice: ISO code (blank for rupees) and rupees per unit. */
    val currency: String = "",
    val exchangeRate: String = "",
    val ecommerceGstin: String = "",
    val poNumber: String = "",
    val poDate: LocalDate? = null,
    val ewayBillNumber: String = "",
    /** Firm-defined labels in order, with what was typed for each (blank values are not saved). */
    val custom: List<Pair<String, String>> = emptyList(),
)

object DocFieldRules {
    const val MAX = 60
    val MODES = listOf("Road", "Rail", "Air", "Ship")

    fun supportsReverseCharge(type: VoucherType) = type in setOf(VoucherType.SALE, VoucherType.PURCHASE, VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE)
    fun supportsExport(type: VoucherType) = type in setOf(VoucherType.SALE, VoucherType.CREDIT_NOTE, VoucherType.QUOTATION, VoucherType.PROFORMA, VoucherType.SALES_ORDER)
    fun supportsEcommerce(type: VoucherType) = type == VoucherType.SALE || type == VoucherType.CREDIT_NOTE

    /** Single line, trimmed, capped: these fields print in one PDF row. */
    fun clean(text: String, max: Int = MAX): String = text.replace('\n', ' ').replace('\t', ' ').trim().take(max)

    fun from(v: Voucher, labels: List<String>, keepShipping: Boolean = true): DocFieldsForm {
        val t = v.transport.takeIf { keepShipping }
        val values = v.customFields.associate { it.label to it.value }
        return DocFieldsForm(
            reverseCharge = v.reverseCharge,
            transporterName = t?.transporterName.orEmpty(),
            transporterId = t?.transporterId.orEmpty(),
            transportMode = t?.mode.orEmpty(),
            vehicleNumber = t?.vehicleNumber.orEmpty(),
            lrNumber = t?.docNumber.orEmpty(),
            lrDate = t?.docDate,
            distance = t?.distanceKm?.toString().orEmpty(),
            exportType = v.export?.type,
            shippingBillNumber = v.export?.shippingBillNumber.orEmpty(),
            shippingBillDate = v.export?.shippingBillDate,
            portCode = v.export?.portCode.orEmpty(),
            lutReference = v.export?.lutReference.orEmpty(),
            currencyNote = v.export?.currencyNote.orEmpty(),
            currency = v.export?.currency.orEmpty(),
            exchangeRate = v.export?.exchangeRate?.format().orEmpty(),
            ecommerceGstin = v.ecommerceGstin.orEmpty(),
            poNumber = v.poNumber.takeIf { keepShipping }.orEmpty(),
            poDate = v.poDate.takeIf { keepShipping },
            ewayBillNumber = v.ewayBillNumber.takeIf { keepShipping }.orEmpty(),
            custom = ((labels + values.keys).distinct()).map { it to (values[it].takeIf { keepShipping }.orEmpty()) },
        )
    }

    /** Errors keyed like the editor's other fields; [post] adds the checks only a posted document needs. */
    fun validate(f: DocFieldsForm, type: VoucherType, party: Party?, post: Boolean): Map<String, String> = buildMap {
        val gstin = f.ecommerceGstin.trim().uppercase()
        if (gstin.isNotEmpty() && !Gstin.isValid(gstin)) put("eco", "Enter a valid 15-character GSTIN")
        val tid = f.transporterId.trim().uppercase()
        if (tid.isNotEmpty() && (tid.length != 15 || !tid.all { it.isLetterOrDigit() })) put("transporterId", "Transporter ID is 15 letters and digits")
        val vehicle = f.vehicleNumber.filterNot { it == ' ' || it == '-' }
        if (vehicle.isNotEmpty() && (vehicle.length !in 4..15 || !vehicle.all { it.isLetterOrDigit() })) put("vehicle", "Use letters and digits, e.g. MH12AB1234")
        if (f.distance.isNotBlank() && f.distance.trim().toIntOrNull()?.takeIf { it in 0..9_999 } == null) put("distance", "Whole kilometres, 0 to 9999")
        val eway = f.ewayBillNumber.filterNot { it == ' ' }
        if (eway.isNotEmpty() && (eway.length != 12 || !eway.all(Char::isDigit))) put("eway", "E-way bill numbers are 12 digits")
        val port = f.portCode.trim()
        if (f.exportType != null && port.isNotEmpty() && (port.length != 6 || !port.all { it.isLetterOrDigit() })) put("port", "Port codes are 6 letters and digits, e.g. INBOM1")
        if (f.exportType != null && supportsExport(type)) {
            val code = f.currency.trim().uppercase()
            if (code.isNotEmpty() && (!ExportDetails.isCurrencyCode(code) || code == "INR")) put("currency", "Use a 3-letter code such as USD, or leave blank for rupees")
            if (code.isNotEmpty() && Parse.quantity(f.exchangeRate.trim())?.takeIf { it > Quantity.of(0) } == null) put("exchangeRate", "Enter the rupees one $code buys, e.g. 83.1050")
        }
        if (post && f.reverseCharge && type == VoucherType.SALE && party?.gstin?.trim()?.uppercase()?.let(Gstin::isValid) != true) {
            put("reverseCharge", "Reverse charge needs a registered customer (with a GSTIN)")
        }
        if (post && f.exportType?.sez == true && party?.gstin?.trim()?.uppercase()?.let(Gstin::isValid) != true) {
            put("export", "SEZ supplies need the SEZ unit's GSTIN on the party")
        }
    }

    fun transport(f: DocFieldsForm): TransportDetails? = TransportDetails(
        transporterName = clean(f.transporterName).ifEmpty { null },
        transporterId = clean(f.transporterId).uppercase().ifEmpty { null },
        mode = f.transportMode.ifEmpty { null },
        vehicleNumber = f.vehicleNumber.filterNot { it == ' ' || it == '-' }.uppercase().ifEmpty { null },
        docNumber = clean(f.lrNumber).ifEmpty { null },
        docDate = f.lrDate,
        distanceKm = f.distance.trim().toIntOrNull(),
    ).takeUnless { it.isEmpty() }

    fun export(f: DocFieldsForm): ExportDetails? = f.exportType?.let {
        val fx = exchange(f)
        ExportDetails(
            type = it,
            shippingBillNumber = clean(f.shippingBillNumber, 20).ifEmpty { null },
            shippingBillDate = f.shippingBillDate,
            portCode = clean(f.portCode, 6).uppercase().ifEmpty { null },
            lutReference = clean(f.lutReference).ifEmpty { null },
            currencyNote = clean(f.currencyNote, 80).ifEmpty { null },
            currency = fx?.first,
            exchangeRate = fx?.second,
        )
    }

    /** The foreign currency and rupees per unit of an export, or null for a rupee invoice (or while invalid). */
    fun exchange(f: DocFieldsForm): Pair<String, Quantity>? {
        if (f.exportType == null) return null
        val code = f.currency.trim().uppercase().takeIf { ExportDetails.isCurrencyCode(it) && it != "INR" } ?: return null
        val rate = Parse.quantity(f.exchangeRate.trim())?.takeIf { it > Quantity.of(0) } ?: return null
        return code to rate
    }

    fun custom(f: DocFieldsForm): List<DocumentField> = f.custom.mapNotNull { (label, value) ->
        val l = clean(label, 30)
        val v = clean(value)
        if (l.isEmpty() || v.isEmpty()) null else DocumentField(l, v)
    }

    fun ecommerceGstin(f: DocFieldsForm): String? = f.ecommerceGstin.trim().uppercase().ifEmpty { null }
}
