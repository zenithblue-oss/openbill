# Tax Assumptions (1.0)

Status: E1.7, implemented 1.0 tax engine. This document records what the code does, the
assumptions behind it, and what it deliberately does not do, for accountants, reviewers,
and maintainers.

This is not tax advice, a legal opinion, or an official determination of liability.
Examples use illustrative rates. A valid GSTIN checksum does not prove that a registration
exists or is active.

## 1. Scope

Scope is India, INR (paise), and regular GST dealers only. The engine computes
document-level taxable value, GST components, cess, discount allocation, round-off, and
grand total for invoice lines. It does not do accounting, ITC, or returns filing; its
output is an input to those activities, not the activities themselves. It is not tax
advice or an official determination. The 1.0 tax API has no separate additional-charge
input, so callers must not assume it computes GST on freight, packing, or other charges.

## 2. Implemented modules and locations

Sources: `core-model/src/commonMain/kotlin/app/openbill/core/model/` and
`core-tax/src/commonMain/kotlin/app/openbill/core/tax/`. Both are pure Kotlin with no
database, Android, or desktop dependency.

- `core-model`: `Money` (paise), `Rate` (hundredths of a basis point), `Rounding`
  (integer divide/multiply-divide), `LargestRemainder` (whole-paise allocation).
- `core-tax`: `GstCalculator` (line tax), `InvoiceTaxCalculator` (discounts and
  round-off), `Gstin`, `IndianState`, `TaxRateSchedule`/`GstRateSchedules` (versioned rates).

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
The tax API also has no separate additional-charge input. Credit notes are modeled as their
own positive-amount document, not as negative lines; their report and return sign
convention is an open question below.

## 13. Validation and failure behavior

The engine fails fast; it does not clamp, guess, or repair bad input. Invalid GSTIN text
returns `Invalid` with a typed `GstinError` from `validate` and throws
`IllegalArgumentException` from `parse`. `IllegalArgumentException` also signals negative
gross or line discount, line discount above gross, a non-taxable treatment with non-zero
GST or cess, an intra-state GST rate with an odd number of units, a negative invoice
discount or one above the after-line-discount subtotal, a negative rate, a state code that
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
- **Legacy state codes.** 25, 28, and 99 are accepted and documents keep their stored
  codes. How long should each remain valid, and how should old documents present them?

Resolve these questions before claiming a filing-ready or legally reviewed calculation.
This document describes the implemented 1.0 behavior only.
