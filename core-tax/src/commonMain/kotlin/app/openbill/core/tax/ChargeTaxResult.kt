package app.openbill.core.tax

import app.openbill.core.model.Rate

/**
 * Result for one additional charge.
 *
 * [treatment], [gstRate] and [cessRate] are the values actually applied (resolved from the
 * principal line for [ChargeTaxBasis.PRINCIPAL_SUPPLY]); posted documents store these.
 * [principalLineIndex] is the zero-based index of the principal line, or null when the basis
 * is not [ChargeTaxBasis.PRINCIPAL_SUPPLY].
 */
data class ChargeTaxResult(
    val basis: ChargeTaxBasis,
    val treatment: TaxTreatment,
    val gstRate: Rate,
    val cessRate: Rate,
    val principalLineIndex: Int?,
    val summaryCode: String,
    val result: TaxLineResult,
    /**
     * Per-rate portions. Always at least one when produced by the calculator; more than one
     * only for a proportional charge on a mixed-rate invoice. [result] is their aggregate
     * and [treatment]/[gstRate]/[cessRate] are those of the highest-ranked portion (a
     * representative only: use [portions] for exact rates). An empty list means the charge is
     * a single portion equal to [result].
     */
    val portions: List<ChargeTaxPortion> = emptyList(),
) {
    /** Portions to report; a single portion built from [result] when none were recorded. */
    val effectivePortions: List<ChargeTaxPortion>
        get() = portions.ifEmpty { listOf(ChargeTaxPortion(treatment, gstRate, cessRate, result)) }
}

/** One rate portion of a charge: [result] is tax computed for that portion's amount. */
data class ChargeTaxPortion(
    val treatment: TaxTreatment,
    val gstRate: Rate,
    val cessRate: Rate,
    val result: TaxLineResult,
)
