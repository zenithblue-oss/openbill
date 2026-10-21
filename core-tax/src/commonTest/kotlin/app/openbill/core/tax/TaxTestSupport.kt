package app.openbill.core.tax

import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val intraState = TaxContext(firmStateCode = "27", placeOfSupplyStateCode = "27")
internal val interState = TaxContext(firmStateCode = "27", placeOfSupplyStateCode = "29")

internal fun money(text: String): Money = Money.parse(text)

internal fun zeroRate(): Rate = Rate.ofBasisPointsHundredths(0L)

internal fun assertLineInvariants(input: TaxLineInput, result: TaxLineResult) {
    val net = input.grossAmount - input.discount
    val componentSum = result.components.fold(Money.ZERO) { total, component ->
        total + component.amount
    }

    assertEquals(result.totalTax, componentSum, "sum of components must equal totalTax")
    when (input.mode) {
        TaxMode.EXCLUSIVE ->
            assertEquals(result.totalAmount, result.taxableValue + result.totalTax)
        TaxMode.INCLUSIVE -> assertEquals(net, result.totalAmount)
    }
    assertTrue(result.totalTax >= Money.ZERO, "totalTax must be non-negative")
    assertTrue(result.taxableValue >= Money.ZERO, "taxableValue must be non-negative")
    assertTrue(result.totalAmount >= Money.ZERO, "totalAmount must be non-negative")
    for (component in result.components) {
        assertTrue(component.amount >= Money.ZERO, "component tax must be non-negative")
        assertTrue(component.rate.units >= 0L, "component rate must be non-negative")
    }
    assertTrue(result.trace.isNotEmpty(), "trace must explain the calculation")
    assertTrue(result.trace.all { entry -> entry.all { it.code < 128 } }, "trace must be ASCII")
}
