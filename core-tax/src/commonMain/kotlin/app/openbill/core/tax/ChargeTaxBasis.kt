package app.openbill.core.tax

/**
 * How GST is determined for an invoice additional charge (freight, packing, insurance, ...).
 *
 * See docs/09-tax-assumptions.md, "Additional charges".
 */
enum class ChargeTaxBasis {
    /**
     * Outside the value of supply (for example a pure-agent reimbursement). No GST; the
     * charge is reported with [TaxTreatment.NON_GST].
     */
    NON_TAXABLE,

    /** Taxed at the GST and cess rates supplied on the charge. */
    EXPLICIT_RATE,

    /**
     * Part of a composite supply: the charge takes the treatment and rates of the invoice's
     * principal line, chosen by [PrincipalRatePolicy].
     */
    PRINCIPAL_SUPPLY,
}
