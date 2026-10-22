package app.openbill.core.tax

/**
 * How a [ChargeTaxBasis.PRINCIPAL_SUPPLY] charge takes its tax treatment and rates.
 *
 * Only lines with a positive taxable value are candidates. Ties go to the earlier line.
 */
enum class PrincipalRatePolicy {
    /**
     * Default. The charge is split across the distinct (treatment, GST rate, cess rate)
     * groups of the candidate lines in proportion to each group's taxable value, with
     * largest-remainder allocation so portions sum exactly to the charge. Each portion is
     * taxed at its group's treatment and rates, so freight on a mixed invoice follows the
     * goods it travels with. A single-group invoice behaves exactly like the other policies.
     * See docs/09-tax-assumptions.md section 7.1.
     */
    PROPORTIONAL,

    /**
     * The whole charge takes the rate of a single line. Taxable lines rank above non-taxable lines, then by higher GST rate, then
     * higher cess rate, then larger taxable value. Never under-charges tax on a mixed-rate
     * invoice.
     */
    HIGHEST_RATE,

    /**
     * The line with the largest taxable value is the principal supply, whatever its rate or
     * treatment. Ties rank taxable lines first, then higher GST and cess rates.
     */
    LARGEST_VALUE,
}
