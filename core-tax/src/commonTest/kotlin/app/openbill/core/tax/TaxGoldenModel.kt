package app.openbill.core.tax

import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode

internal val utIntraState = TaxContext(firmStateCode = "07", placeOfSupplyStateCode = "07")

internal data class GoldenLine(
    val gross: String,
    val gstPercent: String = "0",
    val cessPercent: String = "0",
    val mode: TaxMode = TaxMode.EXCLUSIVE,
    val discount: String = "0.00",
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
)

internal fun line(
    gross: String,
    gst: String = "0",
    cess: String = "0",
    mode: TaxMode = TaxMode.EXCLUSIVE,
    discount: String = "0.00",
    treatment: TaxTreatment = TaxTreatment.TAXABLE,
): GoldenLine = GoldenLine(
    gross = gross,
    gstPercent = gst,
    cessPercent = cess,
    mode = mode,
    discount = discount,
    treatment = treatment,
)

internal data class GoldenComponent(
    val kind: TaxKind,
    val ratePercent: String,
    val amount: String,
)

internal fun cgst(ratePercent: String, amount: String): GoldenComponent =
    GoldenComponent(TaxKind.CGST, ratePercent, amount)

internal fun sgst(ratePercent: String, amount: String): GoldenComponent =
    GoldenComponent(TaxKind.SGST, ratePercent, amount)

internal fun igst(ratePercent: String, amount: String): GoldenComponent =
    GoldenComponent(TaxKind.IGST, ratePercent, amount)

internal fun cess(ratePercent: String, amount: String): GoldenComponent =
    GoldenComponent(TaxKind.CESS, ratePercent, amount)

internal data class GoldenLineExpectation(
    val taxableValue: String,
    val totalTax: String,
    val totalAmount: String,
    val components: List<GoldenComponent>,
)

internal fun expectLine(
    taxableValue: String,
    totalTax: String,
    totalAmount: String,
    vararg components: GoldenComponent,
): GoldenLineExpectation = GoldenLineExpectation(
    taxableValue = taxableValue,
    totalTax = totalTax,
    totalAmount = totalAmount,
    components = components.toList(),
)

internal data class GoldenCase(
    val id: String,
    val name: String,
    val context: TaxContext,
    val rounding: RoundingMode,
    val lines: List<GoldenLine>,
    val invoiceDiscount: String,
    val roundToRupee: Boolean,
    val allocatedDiscounts: List<String>,
    val expectedLines: List<GoldenLineExpectation>,
    val expectedTotalTaxable: String,
    val expectedTotalTax: String,
    val expectedRoundOff: String,
    val expectedGrandTotal: String,
) {
    init {
        require(expectedLines.size == lines.size) { "$id must expect every line" }
        require(allocatedDiscounts.size == lines.size) { "$id must allocate every line" }
    }
}

internal fun golden(
    id: String,
    name: String,
    context: TaxContext,
    rounding: RoundingMode,
    lines: List<GoldenLine>,
    invoiceDiscount: String = "0.00",
    roundToRupee: Boolean = true,
    allocatedDiscounts: List<String>? = null,
    expectedLines: List<GoldenLineExpectation>,
    totalTaxable: String,
    totalTax: String,
    roundOff: String,
    grandTotal: String,
): GoldenCase = GoldenCase(
    id = id,
    name = name,
    context = context,
    rounding = rounding,
    lines = lines,
    invoiceDiscount = invoiceDiscount,
    roundToRupee = roundToRupee,
    allocatedDiscounts = allocatedDiscounts ?: List(lines.size) { "0.00" },
    expectedLines = expectedLines,
    expectedTotalTaxable = totalTaxable,
    expectedTotalTax = totalTax,
    expectedRoundOff = roundOff,
    expectedGrandTotal = grandTotal,
)

internal fun percentRate(text: String): Rate {
    val parts = text.split('.')
    require(parts.size in 1..2 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) {
        "Invalid percent '$text'"
    }
    val fraction = parts.getOrElse(1) { "" }
    require(fraction.length <= 4) { "Percent '$text' has too many fraction digits" }
    val whole = parts[0].toLong()
    val fractionUnits = (fraction + "0000").take(4).toLong()
    return Rate.ofBasisPointsHundredths(whole * 10_000L + fractionUnits)
}
