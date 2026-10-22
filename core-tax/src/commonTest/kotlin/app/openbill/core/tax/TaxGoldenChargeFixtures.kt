package app.openbill.core.tax

import app.openbill.core.model.RoundingMode

internal data class GoldenCharge(
    val amount: String,
    val basis: ChargeTaxBasis,
    val gstPercent: String = "0",
    val cessPercent: String = "0",
    val mode: TaxMode = TaxMode.EXCLUSIVE,
)

internal fun explicitCharge(
    amount: String,
    gst: String,
    cess: String = "0",
    mode: TaxMode = TaxMode.EXCLUSIVE,
): GoldenCharge = GoldenCharge(amount, ChargeTaxBasis.EXPLICIT_RATE, gst, cess, mode)

internal fun principalCharge(amount: String, mode: TaxMode = TaxMode.EXCLUSIVE): GoldenCharge =
    GoldenCharge(amount, ChargeTaxBasis.PRINCIPAL_SUPPLY, mode = mode)

internal fun nonTaxableCharge(amount: String): GoldenCharge =
    GoldenCharge(amount, ChargeTaxBasis.NON_TAXABLE)

internal data class GoldenChargeExpectation(
    val treatment: TaxTreatment,
    val gstPercent: String,
    val cessPercent: String,
    val principalLine: Int?,
    val line: GoldenLineExpectation,
)

/** [principalLine] is zero-based. */
internal fun expectCharge(
    treatment: TaxTreatment,
    gst: String,
    principalLine: Int?,
    line: GoldenLineExpectation,
    cess: String = "0",
): GoldenChargeExpectation = GoldenChargeExpectation(treatment, gst, cess, principalLine, line)

internal data class GoldenChargeCase(
    val base: GoldenCase,
    val charges: List<GoldenCharge>,
    val policy: PrincipalRatePolicy,
    val expectedCharges: List<GoldenChargeExpectation>,
) {
    init {
        require(charges.size == expectedCharges.size) { "${base.id} must expect every charge" }
    }
}

@Suppress("LongParameterList")
internal fun goldenWithCharges(
    id: String,
    name: String,
    context: TaxContext,
    rounding: RoundingMode = RoundingMode.HALF_UP,
    lines: List<GoldenLine>,
    invoiceDiscount: String = "0.00",
    roundToRupee: Boolean = true,
    allocatedDiscounts: List<String>? = null,
    expectedLines: List<GoldenLineExpectation>,
    charges: List<GoldenCharge>,
    policy: PrincipalRatePolicy = PrincipalRatePolicy.HIGHEST_RATE,
    expectedCharges: List<GoldenChargeExpectation>,
    totalTaxable: String,
    totalTax: String,
    roundOff: String,
    grandTotal: String,
): GoldenChargeCase = GoldenChargeCase(
    base = golden(
        id = id,
        name = name,
        context = context,
        rounding = rounding,
        lines = lines,
        invoiceDiscount = invoiceDiscount,
        roundToRupee = roundToRupee,
        allocatedDiscounts = allocatedDiscounts,
        expectedLines = expectedLines,
        totalTaxable = totalTaxable,
        totalTax = totalTax,
        roundOff = roundOff,
        grandTotal = grandTotal,
    ),
    charges = charges,
    policy = policy,
    expectedCharges = expectedCharges,
)

/** Hand-calculated additional-charge cases (group K). */
internal val chargeGoldenFixtures = listOf(
    goldenWithCharges(
        id = "K01",
        name = "explicitEighteenExclusiveIntra",
        context = intraState,
        lines = listOf(line("1000.00", gst = "18")),
        expectedLines = listOf(expectLine("1000.00", "180.00", "1180.00", cgst("9", "90.00"), sgst("9", "90.00"))),
        charges = listOf(explicitCharge("100.00", gst = "18")),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "18",
                null,
                expectLine("100.00", "18.00", "118.00", cgst("9", "9.00"), sgst("9", "9.00")),
            ),
        ),
        totalTaxable = "1100.00",
        totalTax = "198.00",
        roundOff = "0.00",
        grandTotal = "1298.00",
    ),
    goldenWithCharges(
        id = "K02",
        name = "nonTaxableChargeInterRoundUp",
        context = interState,
        lines = listOf(line("500.00", gst = "12")),
        expectedLines = listOf(expectLine("500.00", "60.00", "560.00", igst("12", "60.00"))),
        charges = listOf(nonTaxableCharge("25.50")),
        expectedCharges = listOf(
            expectCharge(TaxTreatment.NON_GST, "0", null, expectLine("25.50", "0.00", "25.50")),
        ),
        totalTaxable = "525.50",
        totalTax = "60.00",
        roundOff = "0.50",
        grandTotal = "586.00",
    ),
    goldenWithCharges(
        id = "K03",
        name = "principalHighestRateMixedIntra",
        context = intraState,
        lines = listOf(line("1000.00", gst = "5"), line("200.00", gst = "18")),
        expectedLines = listOf(
            expectLine("1000.00", "50.00", "1050.00", cgst("2.5", "25.00"), sgst("2.5", "25.00")),
            expectLine("200.00", "36.00", "236.00", cgst("9", "18.00"), sgst("9", "18.00")),
        ),
        charges = listOf(principalCharge("150.00")),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "18",
                1,
                expectLine("150.00", "27.00", "177.00", cgst("9", "13.50"), sgst("9", "13.50")),
            ),
        ),
        totalTaxable = "1350.00",
        totalTax = "113.00",
        roundOff = "0.00",
        grandTotal = "1463.00",
    ),
    goldenWithCharges(
        id = "K04",
        name = "principalLargestValueMixedIntra",
        context = intraState,
        lines = listOf(line("1000.00", gst = "5"), line("200.00", gst = "18")),
        expectedLines = listOf(
            expectLine("1000.00", "50.00", "1050.00", cgst("2.5", "25.00"), sgst("2.5", "25.00")),
            expectLine("200.00", "36.00", "236.00", cgst("9", "18.00"), sgst("9", "18.00")),
        ),
        charges = listOf(principalCharge("150.00")),
        policy = PrincipalRatePolicy.LARGEST_VALUE,
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "5",
                0,
                expectLine("150.00", "7.50", "157.50", cgst("2.5", "3.75"), sgst("2.5", "3.75")),
            ),
        ),
        totalTaxable = "1350.00",
        totalTax = "93.50",
        roundOff = "0.50",
        grandTotal = "1444.00",
    ),
    goldenWithCharges(
        id = "K05",
        name = "explicitInclusiveChargeInter",
        context = interState,
        lines = listOf(line("1000.00", gst = "18")),
        expectedLines = listOf(expectLine("1000.00", "180.00", "1180.00", igst("18", "180.00"))),
        charges = listOf(explicitCharge("118.00", gst = "18", mode = TaxMode.INCLUSIVE)),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "18",
                null,
                expectLine("100.00", "18.00", "118.00", igst("18", "18.00")),
            ),
        ),
        totalTaxable = "1100.00",
        totalTax = "198.00",
        roundOff = "0.00",
        grandTotal = "1298.00",
    ),
    goldenWithCharges(
        id = "K06",
        name = "invoiceDiscountThenChargeThenRoundOffIntra",
        context = intraState,
        lines = listOf(line("600.00", gst = "18"), line("400.00", gst = "18")),
        invoiceDiscount = "100.00",
        allocatedDiscounts = listOf("60.00", "40.00"),
        expectedLines = listOf(
            expectLine("540.00", "97.20", "637.20", cgst("9", "48.60"), sgst("9", "48.60")),
            expectLine("360.00", "64.80", "424.80", cgst("9", "32.40"), sgst("9", "32.40")),
        ),
        charges = listOf(principalCharge("75.25")),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "18",
                0,
                expectLine("75.25", "13.54", "88.79", cgst("9", "6.77"), sgst("9", "6.77")),
            ),
        ),
        totalTaxable = "975.25",
        totalTax = "175.54",
        roundOff = "0.21",
        grandTotal = "1151.00",
    ),
    goldenWithCharges(
        id = "K07",
        name = "creditNoteWithChargesInter",
        context = interState,
        lines = listOf(line("250.00", gst = "12")),
        expectedLines = listOf(expectLine("250.00", "30.00", "280.00", igst("12", "30.00"))),
        charges = listOf(principalCharge("50.00"), nonTaxableCharge("10.00")),
        expectedCharges = listOf(
            expectCharge(TaxTreatment.TAXABLE, "12", 0, expectLine("50.00", "6.00", "56.00", igst("12", "6.00"))),
            expectCharge(TaxTreatment.NON_GST, "0", null, expectLine("10.00", "0.00", "10.00")),
        ),
        totalTaxable = "310.00",
        totalTax = "36.00",
        roundOff = "0.00",
        grandTotal = "346.00",
    ),
    goldenWithCharges(
        id = "K08",
        name = "principalFollowsExemptInvoice",
        context = intraState,
        lines = listOf(line("500.00", treatment = TaxTreatment.EXEMPT)),
        expectedLines = listOf(expectLine("500.00", "0.00", "500.00")),
        charges = listOf(principalCharge("40.00")),
        expectedCharges = listOf(
            expectCharge(TaxTreatment.EXEMPT, "0", 0, expectLine("40.00", "0.00", "40.00")),
        ),
        totalTaxable = "540.00",
        totalTax = "0.00",
        roundOff = "0.00",
        grandTotal = "540.00",
    ),
    goldenWithCharges(
        id = "K09",
        name = "principalCarriesCessInter",
        context = interState,
        lines = listOf(line("1000.00", gst = "28", cess = "12")),
        expectedLines = listOf(
            expectLine("1000.00", "400.00", "1400.00", igst("28", "280.00"), cess("12", "120.00")),
        ),
        charges = listOf(principalCharge("200.00")),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "28",
                0,
                expectLine("200.00", "80.00", "280.00", igst("28", "56.00"), cess("12", "24.00")),
                cess = "12",
            ),
        ),
        totalTaxable = "1200.00",
        totalTax = "480.00",
        roundOff = "0.00",
        grandTotal = "1680.00",
    ),
    goldenWithCharges(
        id = "K10",
        name = "inclusivePrincipalChargeLargestRemainderIntra",
        context = intraState,
        rounding = RoundingMode.HALF_EVEN,
        lines = listOf(line("100.00", gst = "18")),
        expectedLines = listOf(expectLine("100.00", "18.00", "118.00", cgst("9", "9.00"), sgst("9", "9.00"))),
        charges = listOf(principalCharge("101.00", mode = TaxMode.INCLUSIVE)),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "18",
                0,
                expectLine("85.59", "15.41", "101.00", cgst("9", "7.71"), sgst("9", "7.70")),
            ),
        ),
        totalTaxable = "185.59",
        totalTax = "33.41",
        roundOff = "0.00",
        grandTotal = "219.00",
    ),
    goldenWithCharges(
        id = "K11",
        name = "explicitChargeUnroundedInter",
        context = interState,
        roundToRupee = false,
        lines = listOf(line("333.33", gst = "5")),
        expectedLines = listOf(expectLine("333.33", "16.67", "350.00", igst("5", "16.67"))),
        charges = listOf(explicitCharge("12.34", gst = "5")),
        expectedCharges = listOf(
            expectCharge(TaxTreatment.TAXABLE, "5", null, expectLine("12.34", "0.62", "12.96", igst("5", "0.62"))),
        ),
        totalTaxable = "345.67",
        totalTax = "17.29",
        roundOff = "0.00",
        grandTotal = "362.96",
    ),
    goldenWithCharges(
        id = "K12",
        name = "principalSkipsFullyDiscountedHigherRateLine",
        context = intraState,
        lines = listOf(line("300.00", gst = "12"), line("100.00", gst = "28", discount = "100.00")),
        expectedLines = listOf(
            expectLine("300.00", "36.00", "336.00", cgst("6", "18.00"), sgst("6", "18.00")),
            expectLine("0.00", "0.00", "0.00", cgst("14", "0.00"), sgst("14", "0.00")),
        ),
        charges = listOf(principalCharge("20.00")),
        expectedCharges = listOf(
            expectCharge(
                TaxTreatment.TAXABLE,
                "12",
                0,
                expectLine("20.00", "2.40", "22.40", cgst("6", "1.20"), sgst("6", "1.20")),
            ),
        ),
        totalTaxable = "320.00",
        totalTax = "38.40",
        roundOff = "-0.40",
        grandTotal = "358.00",
    ),
)
