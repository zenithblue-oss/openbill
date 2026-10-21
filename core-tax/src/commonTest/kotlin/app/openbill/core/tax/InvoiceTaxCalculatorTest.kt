package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import app.openbill.core.model.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InvoiceTaxCalculatorTest {
    @Test
    fun allocatesInvoiceDiscountProportionallyToLineNetAmounts() {
        val input = InvoiceTaxInput(
            lines = listOf(
                exclusiveLine(money("100.00"), money("10.00"), 18),
                exclusiveLine(money("200.00"), gstPercent = 18),
                exclusiveLine(money("300.00"), gstPercent = 18),
            ),
            invoiceDiscount = money("59.00"),
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(
            listOf(money("9.00"), money("20.00"), money("30.00")),
            result.allocatedInvoiceDiscounts,
        )
        assertEquals(
            listOf(money("81.00"), money("180.00"), money("270.00")),
            result.lines.map { it.taxableValue },
        )
        assertEquals(money("531.00"), result.totalTaxable)
        assertEquals(money("95.58"), result.totalTax)
        assertEquals(money("626.58"), beforeRounding(result))
        assertEquals(money("59.00"), result.invoiceDiscount)
        assertEquals(money("0.42"), result.roundOff)
        assertEquals(money("627.00"), result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun breaksEqualRemainderTiesTowardEarlierLines() {
        val input = InvoiceTaxInput(
            lines = List(3) { exclusiveLine(money("1.00")) },
            invoiceDiscount = money("1.00"),
            roundToRupee = false,
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(
            listOf(money("0.34"), money("0.33"), money("0.33")),
            result.allocatedInvoiceDiscounts,
        )
        assertEquals(
            listOf(money("0.66"), money("0.67"), money("0.67")),
            result.lines.map { it.taxableValue },
        )
        assertEquals(money("2.00"), result.totalTaxable)
        assertEquals(money("2.00"), result.grandTotal)
        assertEquals(Money.ZERO, result.roundOff)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun allowsInvoiceDiscountEqualToLineSubtotal() {
        val input = InvoiceTaxInput(
            lines = List(2) { exclusiveLine(money("1.00")) },
            invoiceDiscount = money("2.00"),
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(
            listOf(money("1.00"), money("1.00")),
            result.allocatedInvoiceDiscounts,
        )
        assertTrue(result.lines.all { it.taxableValue == Money.ZERO })
        assertTrue(result.lines.all { it.totalTax == Money.ZERO })
        assertEquals(Money.ZERO, result.totalTaxable)
        assertEquals(Money.ZERO, result.totalTax)
        assertEquals(Money.ZERO, result.roundOff)
        assertEquals(Money.ZERO, result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun rejectsInvoiceDiscountAboveLineSubtotal() {
        val input = InvoiceTaxInput(
            lines = listOf(exclusiveLine(money("1.00"))),
            invoiceDiscount = money("1.01"),
        )

        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)
        }
    }

    @Test
    fun rejectsNegativeInvoiceDiscount() {
        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxInput(
                lines = listOf(exclusiveLine(money("1.00"))),
                invoiceDiscount = Money.of(-1L),
            )
        }
    }

    @Test
    fun zeroInvoiceDiscountLeavesLinesUnchanged() {
        val input = InvoiceTaxInput(
            lines = listOf(
                inclusiveLine(money("118.00"), gstPercent = 18),
                inclusiveLine(money("236.00"), gstPercent = 18),
            ),
            roundToRupee = false,
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(listOf(Money.ZERO, Money.ZERO), result.allocatedInvoiceDiscounts)
        assertEquals(money("300.00"), result.totalTaxable)
        assertEquals(money("54.00"), result.totalTax)
        assertEquals(Money.ZERO, result.roundOff)
        assertEquals(money("354.00"), result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun inclusiveLinesApplyAllocatedDiscountBeforeExtractingTax() {
        val input = InvoiceTaxInput(
            lines = listOf(
                inclusiveLine(money("118.00"), gstPercent = 18),
                inclusiveLine(money("236.00"), gstPercent = 18),
            ),
            invoiceDiscount = money("35.40"),
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(
            listOf(money("11.80"), money("23.60")),
            result.allocatedInvoiceDiscounts,
        )
        assertEquals(
            listOf(money("90.00"), money("180.00")),
            result.lines.map { it.taxableValue },
        )
        assertEquals(
            listOf(money("16.20"), money("32.40")),
            result.lines.map { it.totalTax },
        )
        assertEquals(money("270.00"), result.totalTaxable)
        assertEquals(money("48.60"), result.totalTax)
        assertEquals(money("318.60"), beforeRounding(result))
        assertEquals(money("0.40"), result.roundOff)
        assertEquals(money("319.00"), result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun positiveRoundOffRoundsGrandTotalUp() {
        val input = InvoiceTaxInput(lines = listOf(exclusiveLine(money("100.76"))))

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.76"), beforeRounding(result))
        assertEquals(money("0.24"), result.roundOff)
        assertEquals(money("101.00"), result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun negativeRoundOffRoundsGrandTotalDown() {
        val input = InvoiceTaxInput(lines = listOf(exclusiveLine(money("100.24"))))

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.24"), beforeRounding(result))
        assertEquals(money("-0.24"), result.roundOff)
        assertEquals(money("100.00"), result.grandTotal)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun roundToRupeeFalseLeavesGrandTotalUnrounded() {
        val input = InvoiceTaxInput(
            lines = listOf(exclusiveLine(money("100.76"))),
            roundToRupee = false,
        )

        val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

        assertEquals(money("100.76"), result.grandTotal)
        assertEquals(Money.ZERO, result.roundOff)
        assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
    }

    @Test
    fun emptyInvoiceWithZeroDiscountIsValid() {
        for (roundToRupee in listOf(true, false)) {
            val input = InvoiceTaxInput(emptyList(), roundToRupee = roundToRupee)
            val result = InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)

            assertTrue(result.lines.isEmpty())
            assertTrue(result.allocatedInvoiceDiscounts.isEmpty())
            assertEquals(Money.ZERO, result.totalTaxable)
            assertEquals(Money.ZERO, result.totalTax)
            assertEquals(Money.ZERO, result.invoiceDiscount)
            assertEquals(Money.ZERO, result.roundOff)
            assertEquals(Money.ZERO, result.grandTotal)
            assertInvoiceInvariants(input, intraState, RoundingMode.HALF_UP, result)
        }
    }

    @Test
    fun emptyInvoiceWithPositiveDiscountIsRejected() {
        val input = InvoiceTaxInput(emptyList(), invoiceDiscount = money("0.01"))

        assertFailsWith<IllegalArgumentException> {
            InvoiceTaxCalculator.calculate(input, intraState, RoundingMode.HALF_UP)
        }
    }

    @Test
    fun randomizedInvoicesSatisfySumInvariants() {
        val random = Random(20261026)
        val gstUnits = longArrayOf(0L, 500L, 1_200L, 1_800L, 2_500L, 5_000L, 12_000L, 18_000L)
        val cessUnits = longArrayOf(0L, 100L, 1_000L)
        val treatments = listOf(
            TaxTreatment.TAXABLE,
            TaxTreatment.ZERO_RATED,
            TaxTreatment.EXEMPT,
            TaxTreatment.NIL_RATED,
            TaxTreatment.NON_GST,
        )

        repeat(1_000) {
            val lineCount = random.nextInt(1, 8)
            val lines = List(lineCount) {
                val gross = random.nextLong(0L, 1_000_001L)
                val discount = if (gross == 0L) 0L else random.nextLong(0L, gross + 1L)
                val treatment = treatments[random.nextInt(treatments.size)]
                val taxable = treatment == TaxTreatment.TAXABLE
                TaxLineInput(
                    grossAmount = Money.of(gross),
                    discount = Money.of(discount),
                    gstRate = if (taxable) {
                        Rate.ofBasisPointsHundredths(gstUnits[random.nextInt(gstUnits.size)])
                    } else {
                        zeroRate()
                    },
                    cessRate = if (taxable) {
                        Rate.ofBasisPointsHundredths(cessUnits[random.nextInt(cessUnits.size)])
                    } else {
                        zeroRate()
                    },
                    mode = TaxMode.entries[random.nextInt(TaxMode.entries.size)],
                    treatment = treatment,
                )
            }
            val subtotal = lines.fold(0L) { total, line ->
                total + (line.grossAmount - line.discount).paise
            }
            val invoiceDiscount = if (subtotal == 0L) {
                0L
            } else {
                random.nextLong(0L, subtotal + 1L)
            }
            val input = InvoiceTaxInput(
                lines = lines,
                invoiceDiscount = Money.of(invoiceDiscount),
                roundToRupee = random.nextBoolean(),
            )
            val context = if (random.nextBoolean()) intraState else interState
            val rounding = RoundingMode.entries[random.nextInt(RoundingMode.entries.size)]

            val result = InvoiceTaxCalculator.calculate(input, context, rounding)

            assertInvoiceInvariants(input, context, rounding, result)
        }
    }

    private fun exclusiveLine(
        gross: Money,
        discount: Money = Money.ZERO,
        gstPercent: Int = 0,
    ): TaxLineInput = TaxLineInput(
        grossAmount = gross,
        discount = discount,
        gstRate = Rate.ofPercent(gstPercent.toLong()),
        mode = TaxMode.EXCLUSIVE,
    )

    private fun inclusiveLine(
        gross: Money,
        discount: Money = Money.ZERO,
        gstPercent: Int = 0,
    ): TaxLineInput = TaxLineInput(
        grossAmount = gross,
        discount = discount,
        gstRate = Rate.ofPercent(gstPercent.toLong()),
        mode = TaxMode.INCLUSIVE,
    )

    private fun beforeRounding(result: InvoiceTaxResult): Money =
        result.lines.fold(Money.ZERO) { total, line -> total + line.totalAmount }

    private fun assertInvoiceInvariants(
        input: InvoiceTaxInput,
        context: TaxContext,
        rounding: RoundingMode,
        result: InvoiceTaxResult,
    ) {
        assertEquals(input.lines.size, result.lines.size)
        assertEquals(input.lines.size, result.allocatedInvoiceDiscounts.size)
        assertEquals(
            input.invoiceDiscount,
            result.allocatedInvoiceDiscounts.fold(Money.ZERO) { total, share -> total + share },
        )
        assertEquals(input.invoiceDiscount, result.invoiceDiscount)
        assertTrue(result.allocatedInvoiceDiscounts.all { it >= Money.ZERO })

        for (index in input.lines.indices) {
            val line = input.lines[index]
            val share = result.allocatedInvoiceDiscounts[index]
            val lineResult = result.lines[index]
            val adjustedDiscount = line.discount + share
            val adjustedNet = line.grossAmount - adjustedDiscount
            val componentSum = lineResult.components.fold(Money.ZERO) { total, component ->
                total + component.amount
            }

            assertTrue(adjustedDiscount <= line.grossAmount)
            assertEquals(lineResult.totalTax, componentSum)
            when (line.mode) {
                TaxMode.EXCLUSIVE ->
                    assertEquals(lineResult.totalAmount, lineResult.taxableValue + lineResult.totalTax)
                TaxMode.INCLUSIVE -> assertEquals(adjustedNet, lineResult.totalAmount)
            }
            assertTrue(lineResult.taxableValue >= Money.ZERO)
            assertTrue(lineResult.totalTax >= Money.ZERO)
            assertTrue(lineResult.totalAmount >= Money.ZERO)
        }

        val before = beforeRounding(result)
        assertEquals(
            result.totalTaxable,
            result.lines.fold(Money.ZERO) { total, line -> total + line.taxableValue },
        )
        assertEquals(
            result.totalTax,
            result.lines.fold(Money.ZERO) { total, line -> total + line.totalTax },
        )
        assertEquals(result.grandTotal, before + result.roundOff)
        if (input.roundToRupee) {
            assertEquals(result.grandTotal - before, result.roundOff)
            assertEquals(0L, result.grandTotal.paise % 100L)
        } else {
            assertEquals(Money.ZERO, result.roundOff)
            assertEquals(before, result.grandTotal)
        }
        assertEquals(
            result,
            InvoiceTaxCalculator.calculate(input, context, rounding),
        )
    }
}
