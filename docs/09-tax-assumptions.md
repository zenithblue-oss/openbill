# Tax Assumptions (1.0)

Status: E1.7, implemented 1.0 tax engine. This document records what the code does, the
assumptions behind it, and what it deliberately does not do, for accountants, reviewers,
and maintainers.

This is not tax advice, a legal opinion, or an official determination of liability.
Examples use illustrative rates. A valid GSTIN checksum does not prove that a registration
exists or is active.

## 1. Scope

Scope is India, INR (paise), and regular GST dealers only. The engine computes
document-level taxable value, GST components, cess, discount allocation, additional
charges (freight, packing, insurance, and similar), round-off, and grand total for invoice
lines. It does not do accounting, ITC, or returns filing; its output is an input to those
activities, not the activities themselves. It is not tax advice or an official
determination.

## 2. Implemented modules and locations

Sources: `core-model/src/commonMain/kotlin/app/openbill/core/model/` and
`core-tax/src/commonMain/kotlin/app/openbill/core/tax/`. Both are pure Kotlin with no
database, Android, or desktop dependency.

- `core-model`: `Money` (paise), `Rate` (hundredths of a basis point), `Rounding`
  (integer divide/multiply-divide), `LargestRemainder` (whole-paise allocation).
- `core-tax`: `GstCalculator` (line tax), `InvoiceTaxCalculator` (discounts, additional
  charges, round-off, and the HSN/rate summary), `ChargeTaxInput`/`ChargeTaxBasis`/
  `PrincipalRatePolicy` (charges), `Gstin`, `IndianState`,
  `TaxRateSchedule`/`GstRateSchedules` (versioned rates).

## 3. Money and precision

`Money` stores a signed 64-bit count of paise, so INR 19.99 is `1999`; `Money.parse`
accepts at most two fractional digits. `Rate` stores hundredths of a basis point, where
one basis point is 0.01 percentage point. For example, 0.25% is 2,500 units and 18% is
180,000; 100% is 1,000,000. Rates are non-negative. Money and rates are never binary
floating point, calculations use checked integer arithmetic, and allocation uses whole
paise, never a fraction of a paisa.

## 4. Rounding

Rounding is per tax component, not once for combined tax. CGST and SGST are each rounded
to paise, as are IGST and cess. There is no line-level rounding to rupees: taxable value,
tax, and line total remain in paise.

`HALF_UP` is OpenBill's default policy and rounds a tie away from zero. `HALF_EVEN` is
selectable and rounds a tie to the even result. The calculator API takes the mode
explicitly, so every caller must pass its configured policy.

Invoice round-off is separate. When `roundToRupee` is true (the default on
`InvoiceTaxInput`), the pre-round total is rounded to the nearest rupee using the same
mode. `roundOff` is the rounded total minus the unrounded total; it may be zero, positive,
or negative. When false, `roundOff` is zero and the grand total keeps paise precision.

| Case | Before rounding | Mode | Round-off | Grand total |
| --- | --- | --- | --- | --- |
| No tax, INR 100.24 | 100.24 | `HALF_UP` | -0.24 | 100.00 |
| No tax, INR 100.50 | 100.50 | `HALF_UP` | +0.50 | 101.00 |
| No tax, INR 100.50 | 100.50 | `HALF_EVEN` | -0.50 | 100.00 |
| No tax, INR 101.50 | 101.50 | `HALF_EVEN` | +0.50 | 102.00 |

Per-component example: INR 24.50 at 18% intra-state gives CGST and SGST of 2.21 each
(tax 4.42) under `HALF_UP`, or 2.20 each (tax 4.40) under `HALF_EVEN`; both round to 29.00.

## 5. Tax formulas

Exclusive: `taxable = gross - discount`. Intra-state CGST and SGST are each
`round(taxable * gstUnits / 2 / 1,000,000)`; inter-state IGST is
`round(taxable * gstUnits / 1,000,000)`; CESS is `round(taxable * cessUnits / 1,000,000)`.
`totalTax` is the sum and `total = taxable + totalTax`. A zero GST rate produces no GST
component, and a zero cess rate produces no cess component.
Example: INR 1,000.00 at 18% inter-state gives IGST INR 180.00 and total INR 1,180.00.

Inclusive: `taxable = round(net * 1,000,000 / (1,000,000 + gst units + cess units))`
where `net = gross - discount`; `totalTax = net - taxable`; `total = net`. The divisor
uses 1,000,000 because rates are a fraction of 100%. Tax is the remainder, not a fresh
rate calculation on rounded taxable value.

`totalTax` is allocated by largest remainder using rate units as weights: half the GST
rate each for CGST and SGST, the full rate for IGST, and the cess rate for cess.
Components sum exactly to `totalTax`, with ties to the earlier component. INR 1,180.00
inclusive at 18% intra-state gives taxable 1,000.00 and CGST/SGST 90.00 each; at
INR 101.00, taxable is 85.59 and tax 15.41, split 7.71 and 7.70.

## 6. CGST/SGST split and place of supply

`TaxContext` compares `firmStateCode` with `placeOfSupplyStateCode` by exact string
equality. Equal means intra-state and a CGST/SGST split; otherwise GST is charged as IGST.

The split is an equal division. A rate whose units cannot be halved exactly is rejected
intra-state: 18% (180,000 units) splits 90,000 each, 0.25% (2,500) splits 1,250 each; an
odd unit count is accepted for inter-state IGST but rejected intra-state.

There are no special place-of-supply rules in 1.0 for services, immovable property, SEZ,
exports, imports, bill-to/ship-to, or overrides; only code equality applies. Both codes
must be two ASCII digits, which does not prove they are known states: validity is enforced
by GSTIN validation, so a raw `TaxContext` with `00` cannot come from a valid GSTIN.

## 7. Discounts

Line discount cannot be negative or exceed gross, which must itself be non-negative. It
reduces the amount before GST in exclusive mode, or the tax-inclusive net before tax is
extracted in inclusive mode: INR 1,180.00 at 18% less INR 118.00 yields taxable 900.00.

Invoice discount is allocated before line tax is calculated:

- Each line's weight is `gross - line discount` (tax-inclusive for inclusive lines).
- Weights include non-taxable and zero-net lines; treatment and mode do not change them.
- Allocation is largest remainder in whole paise; shares sum exactly and ties go to the
  earlier line. Three INR 1.00 lines with INR 0.01 discount get 0.01, 0.00, 0.00.
- Each share is added to the line discount before calculation. A discount equal to the
  after-line-discount subtotal yields zero net and zero tax; a larger one is rejected.
- Because allocation is by net weight, a non-taxable line can absorb part of an invoice
  discount. It is a document-level reduction, not a GST-only adjustment.

`InvoiceTaxResult.allocatedInvoiceDiscounts` exposes the shares for review.

### 7.1 Additional charges

An invoice may carry any number of additional charges (`InvoiceTaxInput.charges`, default
empty). Each `ChargeTaxInput` has an amount in paise, a tax mode (exclusive by default, or
inclusive), an optional HSN/SAC code, and one of three bases (`ChargeTaxBasis`):

| Basis | Treatment applied | Rates applied |
| --- | --- | --- |
| `NON_TAXABLE` | `NON_GST` | none |
| `EXPLICIT_RATE` | `TAXABLE` | GST and cess rates supplied on the charge |
| `PRINCIPAL_SUPPLY` (default) | that of the principal line | that line's GST and cess rates |

**Rule.** A charge billed together with goods or services (freight, packing, insurance,
handling) is part of the supply it is billed with: section 15(2)(c) of the CGST Act puts
incidental expenses, including packing, in the value of supply, and section 2(30) with its
illustration (goods packed, transported and insured) makes such a bundle a composite supply
taxed as the principal supply under section 8(a). `PRINCIPAL_SUPPLY` is therefore the
default basis. `EXPLICIT_RATE` is for a charge the user has determined to be a separate
supply with its own rate. `NON_TAXABLE` is for an amount outside the value of supply, such
as a pure-agent reimbursement; it is the user's determination, not the engine's.

**Mixed-rate invoices: the decision.** When goods at different rates share one invoice there
is no single "principal supply" for the invoice as a whole, and no CBIC circular or notification
located during research prescribes a method (see the research notes below). The default is
therefore `PROPORTIONAL`: each portion of the charge follows the goods it travels with, in
proportion to taxable value. The reasoning is that the invoice is several supplies of goods,
each naturally bundled with its share of freight or packing, and section 8(a) taxes each
bundle at its principal supply's rate. This is the same result as the "pro-rata" treatment
that practitioners describe; practitioner reports (not vendor documentation) say some
accounting software, such as Tally, can apportion additional ledgers by value. Its weakness, which is raised in
practitioner commentary, is that freight cost really depends on weight or volume rather than
value, so a department could contest the value basis; the highest-rate alternative is
the conservative way to avoid that dispute.

**Choosing a policy** (`InvoiceTaxInput.principalRatePolicy`, a firm-level setting; only lines
with a positive taxable value after line and invoice discounts are candidates):

- `PROPORTIONAL` (default): candidate lines are grouped by (treatment, GST rate, cess rate).
  The charge amount, which is tax-exclusive or tax-inclusive per its mode, is allocated over
  the groups by taxable value using largest-remainder allocation (ties to the earlier group),
  so the portions sum exactly to the charge. Each portion is then taxed like a line with no
  discount, CGST/SGST or IGST as for lines. Zero-amount portions are dropped. Exempt, nil-rated
  and non-GST goods carry a tax-free portion. With one group the result is identical to the
  other policies.
- `HIGHEST_RATE`: the whole charge takes the rate of one line: taxable lines rank above
  non-taxable ones, then higher GST rate, higher cess rate, larger taxable value, earlier line.
  Never charges less GST than any line, the conservative mixed-supply (section 8(b)) approach.
- `LARGEST_VALUE`: the whole charge takes the treatment and rates of the line with the largest
  taxable value, the "predominant element" reading (ties: taxable first, then higher rates,
  then earlier line).

On an invoice whose only valued lines are exempt, nil-rated, zero-rated, or non-GST, a
`PRINCIPAL_SUPPLY` charge takes that treatment and has no tax. A `PRINCIPAL_SUPPLY` charge on
an invoice with no positive-value line is rejected.

**Research notes (accessed 2026-10-22).** This is a summary, not tax advice; confirm with
your CA.

- CGST Act 2017 section 2(30) (composite supply, with the illustration of goods packed,
  transported and insured), section 2(74) (mixed supply), section 8(a)/(b) (composite taxed as
  the principal supply; mixed taxed at the highest-rate supply), and section 15(2)(c)
  (incidental expenses such as packing in the value of supply). CBIC/GST Council flyer on
  composite and mixed supply: https://gstcouncil.gov.in/sites/default/files/e-version-gst-flyers/Composite%20supply%20&%20Mixed%20Supply-050819.pdf
  and the West Bengal note https://comtax.wb.gov.in/GST/GST_FAQ/A_Note_on_Composite_&_Mixed_Supply.pdf.
- No CBIC circular specific to apportioning freight across multi-rate goods was found. Forum
  and practitioner views: freight billed with goods takes the goods' rate (composite supply);
  on mixed rates either pro-rata by value or the highest rate (to avoid litigation), with a
  caution that value-based bifurcation can be disputed because freight varies by weight or
  volume. Examples: https://www.taxtmi.com/forum/issue?id=119545 (Jan 2025 thread),
  https://ask.taxninja.in/query/gst-on-freight-in-invoice-on-sale-of-cement-bags,
  https://www.knowyourgst.com/question/gst-on-freight/532.
- Advance rulings exist on freight and insurance billed with supply (for example Aditya Birla
  Nuvo Ltd., Gujarat AAR, https://busy.in/caselaw/aditya-birla-nuvo-limited-grasim-industries-limited-authority-for-advance-ruling-gujrat);
  the outcome was not verified here and AARs bind only the applicant.
- Software: Tally can apportion an additional ledger by value across stock items; vendor
  documentation on this was not located, so behavior is stated from practitioner reports.
  Other billing tools vary (highest rate, proportional, or a single user-chosen rate).
- Out of scope: freight separately contracted (for example ex-works terms with a distinct
  transport service, SAC 9965/9967, goods transport agency rules) is a separate supply and
  should use `EXPLICIT_RATE`.

**Calculation order.**

1. Line discounts, then invoice-discount allocation across lines only (section 7). Charges
   never receive a share of the invoice discount, and the invoice discount still may not
   exceed the lines' after-discount subtotal.
2. Line tax (sections 5 and 6).
3. Principal-line selection from the discounted line results, then each charge's tax using
   the same formulas as a line with no discount: exclusive charges add tax to the amount,
   inclusive charges extract it with largest-remainder component split. Intra-state charges
   split CGST/SGST and inter-state charges use IGST, as for lines; an explicit odd-unit rate
   is rejected intra-state.
4. Round-off on the total of all lines and charges (section 4).

**Results.** `InvoiceTaxResult.charges` holds one `ChargeTaxResult` per charge with the
applied treatment, rates, the zero-based principal line index (null for non-principal and
proportional charges), a line-style aggregate result with a trace, and `portions`, the
per-rate parts of a proportional charge (`effectivePortions` always yields at least one). For a
multi-rate charge the top-level treatment and rates are those of the highest-ranked portion and
are only a representative. `totalTaxable` and `totalTax` include charges; `lines` alone sum to the
line-only values, and `grandTotal` is lines plus charges plus `roundOff`. As for non-taxable
lines, a non-taxable charge reports its amount as taxable value with zero tax, so reports
must use the treatment to separate taxable from non-taxable value.

**Summaries.** `InvoiceTaxResult.summary` groups lines and charges by HSN/SAC code,
treatment, GST rate, and cess rate, with taxable value and CGST, SGST, IGST, and cess per
row; rows sum to the invoice totals. Each portion of a charge gets its own row. A charge is summarized under its own SAC when one is
given (for example 9965 or 9967 for transport), otherwise under the `CHARGES` bucket. The
engine does not reclassify a composite-supply charge under the principal line's HSN; a
caller that reports it that way can pass the principal line's code as the charge code.

**Persistence and ledger.** A voucher charge stores its basis, mode, applied treatment,
applied GST and cess rates, tax, total, and optional SAC; schema version 4 adds the basis,
mode, treatment, and SAC columns, and charges saved earlier migrate as explicit-rate,
exclusive, taxable. A charge apportioned over more than one rate also stores one row per
portion in `voucher_charge_portions` (schema version 5; treatment, rates, taxable value, tax,
total; cascade-deleted with the charge). A charge without portion rows is a single portion
described by its own columns, so earlier data needs no backfill. Portions must sum to the
charge tax and total. The charge's taxable value is its total less its tax. On posting, a
charge's taxable value is credited (sales) or debited (purchases) to the same sales or
purchases account as the lines, and its GST to the output or input GST account; there is no
separate charges ledger, since the charge is part of the value of the supply. Credit and
debit notes post the mirror image, with charges as positive amounts on their own document.

## 8. GSTIN validation

A normalized GSTIN has 15 characters:

| Position | Content |
| --- | --- |
| 1-2 | State or union-territory code |
| 3-7 | Five uppercase letters (PAN) |
| 8-11 | Four digits (PAN) |
| 12 | One uppercase letter (PAN) |
| 13 | Entity code: `1`-`9` or `A`-`Z` |
| 14 | `Z` |
| 15 | Base-36 checksum |

Normalization trims, uppercases, and removes ASCII spaces and hyphens only. The checksum
covers the first 14 characters: map each to 0-35 (`0`-`9`, then `A`-`Z`), weight even
positions by 1 and odd positions by 2 (zero-based), sum the quotient and remainder of
division by 36, and take alphabet entry `(36 - sum mod 36) mod 36`.

The state table includes legacy codes 25 (Daman and Diu), 28 (Andhra Pradesh legacy), and
99 (Centre Jurisdiction), alongside current codes. A GSTIN with a state code outside
the table is rejected. Validation is structural and checksum-based only; it does not call
the GST portal and does not establish registration, filing status, or identity.
`Gstin.validate` returns `Valid` or `Invalid` with a typed reason: `LENGTH`, `CHARACTERS`,
`STATE_CODE`, `PAN_FORMAT`, `ENTITY_CODE`, `DEFAULT_Z`, or `CHECKSUM`. `Gstin.parse`
throws `IllegalArgumentException` with the reason and normalized input.

## 9. Tax treatments

A line is `TAXABLE`, `ZERO_RATED`, `EXEMPT`, `NIL_RATED`, or `NON_GST`. Non-taxable
treatments require zero GST and cess, produce no components, and return net as taxable
value and `totalAmount` for reporting, even for an inclusive line.

Zero-rated and exempt are not the same classification: the engine produces no tax for
either but keeps distinct enum values, and a `TAXABLE` zero-rate line is also distinct.
The caller selects the treatment; the engine does not decide which applies.

## 10. Cess

Cess is a single percentage rate per line, supplied by the caller, applied to taxable value
and returned as a `CESS` component. For inclusive pricing, cess units are in the extraction
divisor and cess is an allocation weight. There is no compensation-cess schedule,
HSN-to-cess mapping, or automatic lookup: when a supply attracts cess, the caller must pass
the rate explicitly, and the constructor default of zero is only for cases determined to
have none. Only a percentage rate is supported; per-unit specific cess is outside 1.0.

## 11. Rate schedule

`TaxRateSchedule` stores versions ordered by effective date. `versionOn(date)` returns the
most recent version starting on or before the date, or null before the first;
`ratesOn(date)` returns empty before the first; `nextChangeAfter(date)` returns the first
version strictly after the date. Duplicate dates, empty schedules, empty rate lists, and
duplicate rates are rejected.

| Effective from | Slabs |
| --- | --- |
| 2017-07-01 | 0%, 0.25%, 3%, 5%, 12%, 18%, 28% |
| 2025-09-22 | 0%, 0.25%, 3%, 5%, 18%, 40% |

The schedule is selectable guidance, not an HSN-level legal determination, and the
calculators do not enforce it: a caller may pass any non-negative `Rate`. Posted documents
must store the actual rate values used. There is no automatic HSN rate lookup, so a
historical document does not change when the schedule is updated.

## 12. Exclusions from 1.0

Not supported: reverse charge; composition dealers (regular dealers only); SEZ, export,
and import special rules beyond selecting a treatment; TDS and TCS; e-invoice and e-way
bill generation or validation; GSTIN autofill or online verification (checksum only); ITC
ledgers; GSTR filing; negative lines or credit-note lines; and multi-currency (INR only).
Additional charges are supported (section 7.1), including proportional apportionment of one
charge across several rates. Credit notes are modeled as their
own positive-amount document, not as negative lines; their report and return sign
convention is an open question below.

### 12.1 Assumptions for the excluded areas

For these items, the 1.0 assumption is what OpenBill does not do, so that nobody mistakes a
printed invoice for a compliant one. Thresholds, rates, and due dates for each area change and
are not recorded here.

- **E-invoice (IRN).** OpenBill 1.0 does not register invoices with the Invoice Registration
  Portal, request an IRN, or produce a signed QR code. A business that must issue e-invoices
  must do so outside OpenBill. Integration is post-1.0, uses the user's own credentials, and
  is described in [10-server.md](10-server.md).
- **E-way bill.** 1.0 neither generates nor validates e-way bills. Structured transport
  details are post-1.0 together with the integration.
- **Reverse charge (RCM).** 1.0 treats every supply as forward charge. A purchase subject to
  reverse charge is recorded as an ordinary purchase; the self-assessed liability and its
  credit are not computed.
- **TCS and TDS.** 1.0 computes neither and creates no withholding ledger entries. Treatment of
  amounts deducted or collected is left to the accountant.
- **Export and SEZ.** 1.0 supports only the `ZERO_RATED` treatment selected by the user (see
  section 9). It does not model LUT or bond status, shipping bill details, refund claims,
  foreign currency, or the SEZ place-of-supply rules, and an export invoice is not
  identified as such in GSTR-1 output.
- **Composition scheme.** Regular dealers only. A composition dealer would need a bill of
  supply with no tax shown and different returns; neither is supported, and a composition
  dealer must not use the 1.0 tax engine as is.
- **Returns and filing.** GSTR-1 export is a data export for the supported document types;
  GSTR-3B and GSTR-2 working reports, ITC reconciliation, and portal filing are not in 1.0.
- **Other registrations.** Unregistered-person rules, job work, input service distributors,
  and non-resident taxpayers are not modeled.

The scope of record for when each area may arrive is [ROADMAP.md](../ROADMAP.md) and the P1
and P2 rows of [01-feature-inventory.md](01-feature-inventory.md).

## 13. Validation and failure behavior

The engine fails fast; it does not clamp, guess, or repair bad input. Invalid GSTIN text
returns `Invalid` with a typed `GstinError` from `validate` and throws
`IllegalArgumentException` from `parse`. `IllegalArgumentException` also signals negative
gross or line discount, line discount above gross, a non-taxable treatment with non-zero
GST or cess, an intra-state GST rate with an odd number of units, a negative invoice
discount or one above the after-line-discount subtotal, a negative charge amount, GST or
cess rates on a charge that is not `EXPLICIT_RATE`, a `PRINCIPAL_SUPPLY` charge on an invoice
with no positive-value line, a negative rate, a state code that
is not exactly two ASCII digits, an empty rate schedule or empty rate version, or a
duplicate effective date or rate. Integer overflow in money, rate, or allocation arithmetic
throws `ArithmeticException`.

These exceptions signal programmer or data errors. Callers should validate user input
before constructing these types and present typed GSTIN results rather than exceptions.

## 14. Test evidence

- 83 golden invoice cases in `TaxGoldenTest` (groups A-H) cover intra-state and
  inter-state supply, exclusive and inclusive pricing, standard rates, cess, treatments,
  discounts, largest-remainder splits, round-off, and large amounts. Each checks line and
  invoice values against hand-calculated expectations.
- 12 golden additional-charge cases in `TaxGoldenChargeTest` (group K) and 6 proportional
  apportionment cases in `ChargeProportionalTest` (K13-K17 plus edge cases) cover explicit,
  non-taxable, and principal-supply charges; all three policies, remainder paise; inclusive charges;
  intra-state and inter-state supply; cess; an exempt principal; a fully discounted line
  skipped as principal; invoice discount plus charge plus round-off; and a credit note with
  charges. `ChargeTaxCalculatorTest` covers validation, tie-breaks, and the summary.
- Seeded property/invariant tests cover 2,000 taxable lines, 500 non-taxable lines, and
  1,000 multi-line invoices. Assertions include component sums equal total tax, line
  taxable sums equal invoice taxable, discount shares sum exactly, grand total equals
  pre-round total plus round-off, and non-negative outputs for non-negative inputs.
- Rounding tests include 1,000 randomized divisions, tie behavior, negative values, and
  extreme 64-bit values.
- GSTIN tests include reference checksum vectors, 50 seeded round trips, checksum-flip
  rejection, normalization cases, every typed error, and 1,000 random 15-character strings
  that must be rejected.
- Rate schedule boundary tests cover the day before and day of a change, dates before the
  first version, future dates, leap day, year boundary, and the 2017 and 2025 slab sets.
- Money and rate tests cover decimal parsing, formatting, overflow, and fixed-point
  behavior. Fixtures use decimal strings or integer paise; no binary floating point is used.

## 15. Open questions for accountant review

- **Cess for specific goods.** 1.0 uses one explicit percentage rate per line, with no
  schedule or HSN mapping. Is a user-entered rate sufficient, or is a compensation-cess
  schedule expected?
- **Place of supply for services.** 1.0 applies only equality of firm state and
  place-of-supply codes. Which service or immovable-property rules must be added?
- **Credit-note sign convention.** Credit notes are separate positive-amount documents and
  negative lines are rejected. Should reports and GSTR-1 export show them as negative
  totals, and how?
- **Rounding policy.** The engine rounds per component, rounds the invoice to the rupee,
  defaults to `HALF_UP`, and allows `HALF_EVEN`. Which default is expected for
  filing-oriented output, and is per-line rupee rounding ever expected?
- **Additional charges.** Charges follow the supply they are billed with; on a mixed-rate
  invoice they are apportioned by taxable value by default (`PROPORTIONAL`), with
  `HIGHEST_RATE` and `LARGEST_VALUE` as firm settings (section 7.1). No CBIC circular settles
  the method. Remaining for review: which policy a given business should use, whether a
  weight/volume basis is ever needed, and whether HSN summaries should report composite-supply
  charges under the goods' HSN rather than the charge's SAC.
- **Legacy state codes.** 25, 28, and 99 are accepted and documents keep their stored
  codes. How long should each remain valid, and how should old documents present them?

Resolve these questions before claiming a filing-ready or legally reviewed calculation.
This document describes the implemented 1.0 behavior only.
