# Feature Inventory

This inventory combines public product documentation, the Play Store listing, the
competitor help centers. It is used to scope
OpenBill, not to clone every behavior.

**Source note (2026-10-22):** updated after a review of the vendor's public website
(plan pages and feature landing pages), a behavioral review of its Windows desktop
client, and a further feature sweep of the Android app. Everything here is restated as
requirements in original wording; no code, strings, assets, or endpoints are reproduced
(see [06-clean-room-policy.md](06-clean-room-policy.md)). Items seen only on the vendor's
paid tiers are recorded for completeness; OpenBill has no paid tiers.

**Parity principle:** All features that the competitor gates behind paid plans are free in
OpenBill. Every competitor feature is planned (P0, P1, or P2) unless it is inherently
anti-FOSS or harmful (`No`: advertising, attribution and marketing SDKs, vendor referral
programs, paywalls and trials, vendor-sold credit packs, data brokerage, lending or money
transfer, mandatory cloud accounts, paid theme stores, competitor branding). Features that
depend on a third party (messaging, AI, OCR, e-invoice, payments) use bring-your-own
provider or credentials, are opt-in, and never block the offline core. The phase for each
group is in the [parity tracker](../ROADMAP.md#parity-tracker).

**Scope of record:** MVP means 1.0. The authoritative phase-by-phase scope is in
[ROADMAP.md](../ROADMAP.md#release-scope-of-record). P0 is required for 1.0; P1 follows
1.0; P2 is later or optional.

Priority legend:

- `P0` - required for 1.0 (MVP).
- `P1` - after 1.0.
- `P2` - later or optional.
- `No` - explicitly out of scope.

Status legend (checked against the code on 2026-11-06): `Done` = usable end to end in the
app; `Partial` = only part of the row ships (for example a data field without UI);
`Not started` = nothing usable yet; `Not planned` = a `No` row.

Platform legend: `A` = Android, `W` = Windows, `L` = Linux, `S` = self-hosted server,
`C` = command-line tool. Core features are expected on A, W, and L unless noted; S and C
are described in [10-server.md](10-server.md) and [11-cli.md](11-cli.md).

## 1. Onboarding and firm setup

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Phone or email sign-in | Account creation with OTP and social sign-in options | P1 | Not started | Optional after 1.0; local-only use requires no account |
| Business creation | Name, type, address, GSTIN, state, financial year | P0 | Done | GSTIN checksum validation locally |
| GSTIN autofill | Fetch legal name and address from GSTIN | P1 | Not started | Requires a licensed API or manual entry fallback |
| Multiple firms | Switch between businesses | P0 | Done | Local firm switcher; no artificial limit |
| Business logo and signature | Upload, crop, and place on documents | P0 | Done | Image picker; PDF-safe storage |
| Language selection | English and Indian languages | P1 | Not started | Start with English; externalize strings from day one |
| Guided setup | Sample party, item, and invoice | P1 | Not started | 1.0 first-run flow is a simple manual firm form; guided setup is post-1.0 |
| Industry type selection | Firm picks an industry (retail, pharmacy, restaurant, jewellery, etc.) to tailor defaults | P2 | Not started | Optional preset bundles of units, tax rates, and fields; never required |
| Import from other billing software | Guided import of items and parties from competitor files and spreadsheets | P1 | Partial | Generic CSV mapping first; competitor-specific formats only from public documentation |
| Shared product library | Pick items from a common catalogue | P2 | Not started | Community-maintained, offline item packs (name, HSN, unit, tax rate) imported from a file or a self-hosted server; no vendor-hosted dataset or upload of user data |
| Credits for scans and messages | Prepaid credits for OCR scans and messaging | No | Not planned | Vendor-sold credit packs are not offered; OCR and messaging are unmetered and use local models or the user's own provider account |
| Demo or trial mode | Time-limited premium access | No | Not planned | OpenBill is free and open source; no trial gates |
| Referral and rewards | Invite codes and credits | No | Not planned | Not part of the product |
| Plan upgrade and paywall | Subscription purchase through Razorpay | No | Not planned | OpenBill has no paid tiers |
| Push onboarding campaigns | CleverTap-style in-app messages | No | Not planned | No marketing SDKs |

First-run acceptance criteria:

- A new user can create a firm with only a name and state, then add GST details later.
- A user with a GSTIN can validate it offline and see a clear error for a bad checksum.
- The app works without sign-in, network, contacts access, or location.

## 2. Dashboard and navigation

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Sales summary | Today, week, month, and custom range | P0 | Done | Cached aggregates; no blocking query on launch |
| Quick actions | New invoice, payment, expense, item, party | P0 | Done | Android FAB; desktop toolbar and shortcuts |
| Recent invoices | Paged list with status chips | P0 | Done | Shared paging layer |
| Search bar | Debounced search over invoices | P0 | Done | 300 ms debounce observed; see search analysis |
| Invoice type filters | GST, non-GST, credit note, etc. | P0 | Done | Chips below search |
| Outstanding reminders | Receivables and payables summary | P0 | Done | Tap opens filtered ledger |
| Low-stock card | Items below threshold | P0 | Done | Phase 4 |
| Business switcher | Change active firm | P0 | Done | Preserve per-firm UI state |
| Notifications inbox | Transactional and marketing messages | P1 | Not started | Local notifications only; no marketing |
| Command palette | Not observed on Android; desktop apps commonly use one | P0 | Done | Ctrl/Cmd+K on desktop, search icon on Android |
| Home-screen widgets | Sales and quick actions | P2 | Not started | Android only |
| Deep links | Branch links to invoices and campaigns | P1 | Not started | Standard Android App Links; no Branch dependency |
| Tools and feature-discovery tab | Grid of optional modules and promotional banners | P1 | Not started | A modules screen to enable or disable optional modules (POS, restaurant, payroll, and so on); no promotional banners |
| In-app calculator | Calculator that can attach to a party or invoice, with optional floating widget | P2 | Not started | Simple calculator; sound and haptics optional |
| Business-wide tools | GST calculator, late-fee calculator | P2 | Not started | Offline, public-formula calculators |

## 3. Parties

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Create and edit party | Name, phone, email, GSTIN, type | P0 | Done | Customer and supplier flags |
| Billing and shipping addresses | Multiple shipping addresses | P0 | Done | Address book per party |
| Opening balance | Receivable or payable | P0 | Done | Posted as an opening entry |
| Credit limit and terms | Optional | P1 | Partial | Warn, do not block, unless configured |
| Party ledger | Running balance and documents | P0 | Done | Drill-down to voucher |
| Statement export | PDF and CSV | P0 | Done | Date-range filters |
| Party search | Name and phone | P0 | Done | Local index |
| Contact import | Pick a phone contact | P1 | Not started | One contact at a time, explicit consent |
| GSTIN autofill | Legal name and address | P1 | Not started | Manual fallback |
| Birthday and appointment reminders | CRM-lite features | P2 | Not started | Local notifications only |
| Party-wise item price | Special pricing per party | P1 | Not started | Phase 7 |
| Party categories | Group parties for filtering and reports | P1 | Not started | Flat list of user-defined groups |
| Ship-to GSTIN and delivery site | Separate GSTIN per shipping address, with an unregistered option | P0 | Done | Required for compliant invoices |
| Shared ledger | Party sees their own ledger entries through a link; party can hide or re-enable sharing | P2 | Not started | Needs the server (S); read-only link with expiry |
| Service reminders | Per-item service period; reminder to owner and party shortly before due date | P2 | Not started | Local notifications; party message via share intent |
| Smart greetings | Templated festival or birthday messages sent to parties | P2 | Not started | Share-intent only; no bulk sending service |
| Loyalty and reward points | Points earned per paid bill, redeemable on later invoices; redemption cannot exceed balance | P2 | Not started | Phase 7; per-firm rules |
| CRM lite | Follow-up notes and simple customer history beyond the ledger | P2 | Not started | Notes, tags, next-contact date |
| Price history for a party | Shows the last few sale or purchase prices for the item and party while billing | P1 | Not started | Read-only hint panel |
| Notes and attachments | Free text and files | P0 | Done | Files included in backup; expense receipt attachments are post-1.0 |
| Delete and restore | Recycle bin | P0 | Done | Soft delete with audit event |

## 4. Items and inventory

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Item master | Name, SKU, category, unit, HSN/SAC, tax rate | P0 | Done | Goods and services |
| Multiple prices | Sale, purchase, MRP, wholesale | P0 | Done | Party-specific price later |
| Units | Primary and alternate units with conversion | P0 | Done | Decimal quantities |
| Item images | Upload and show on invoice | P1 | Not started | Optional |
| Categories | Grouping and filtering | P0 | Done | Tree or flat list |
| HSN/SAC search | Search by code or description | P0 | Done | Offline dataset |
| Stock tracking | On/off per item | P0 | Done | Service items do not affect stock |
| Godowns | Multiple locations and transfers | P0 | Done | Phase 4 |
| Batch and expiry | Batch number and expiry per movement | P0 | Done | Phase 4 |
| Serial / IMEI | Track individual units | P0 | Done | Phase 4 |
| Stock adjustment | Increase or decrease with reason | P0 | Done | Audit event required |
| Low-stock alerts | Threshold per item or global | P0 | Done | Local notification and dashboard card |
| Barcode generation | Per item and per variant | P0 | Done | Code 128 and QR |
| Barcode label printing | A4 sheets and thermal labels | P0 | Done | Desktop and Android thermal |
| Negative stock | Allow or block per firm | P0 | Done | Explicit setting |
| Stock valuation | Costing method | P0 | Done | Document the method; start with weighted average |
| Item timeline | History of movements | P1 | Done | Read-only audit view |
| Bulk item upload | CSV or Excel | P0 | Done | CSV import is 1.0; Excel upload is post-1.0; dry-run validation required |
| Bulk edit items | Change tax rate, category, or godown for a selection | P1 | Not started | Preview and confirm; audit event per item |
| Bulk categorisation | Assign categories to many items at once | P1 | Not started | Part of bulk edit |
| Opening stock on upload | Quantity, godown, and batch columns in the upload file | P1 | Not started | Posts opening movements |
| Wholesale price | Optional second selling price used for bulk sales | P0 | Done | Covered by multiple prices; explicit toggle |
| Custom item fields | User-defined extra fields (MRP, manufacturing date, part number) shown on documents | P1 | Not started | Typed fields; optional on print |
| Renamable serial label | Serial field relabelled by the user (model number, part number) | P2 | Not started | Label setting per item |
| Batch and serial exclusivity | An item uses either batches or serials, not both | P0 | Done | Validation rule; document in the data model |
| Duplicate and sold serial checks | Block reuse of a serial already in stock or already sold | P0 | Done | Part of serial invariants |
| FEFO batch suggestion | Suggest the earliest-expiring batch when selling | P1 | Done | Suggestion only; user can override |
| Near-expiry alerts | Notify before batches expire | P1 | Not started | Local notification and dashboard card; configurable window |
| Reorder level | Reorder threshold and suggested reorder quantity | P1 | Partial | Extends low-stock alerts |
| Batch and expiry report | Stock by batch with expiry dates | P1 | Done | Report; see section 7 |
| Godown transfer | Single and bulk transfers between locations; enabling godowns moves existing stock to a default location | P0 | Done | Reversing movements on edit; warn when editing old documents changes opening stock |
| Multi-godown stock view | Per-item quantity by location | P0 | Done | Limit of locations in a picker is a UI choice, not a data limit |
| Alternate unit on invoice | Show secondary unit quantity on printed document | P1 | Done | Template option |
| Barcode scanning in billing | Scan to add a line; clear message when code is unknown | P0 | Done | Camera on Android; HID scanner on desktop |
| Weighing-scale input | Read weight from a connected scale for loose goods | P2 | Not started | Desktop serial or USB; optional |

Inventory invariants:

- No stock quantity is edited directly; every change is a movement.
- Deleting a posted voucher creates reversing movements, not silent deletions.
- Batch and serial quantities must match the voucher line quantity.
- Stock cannot go negative unless the firm enables it.
- Every movement records source document, timestamp, user, and reason.

## 5. Billing and vouchers

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Sales invoice | GST and non-GST | P0 | Done | Primary workflow |
| Quotation | Convert to invoice | P0 | Done | Preserve source link |
| Proforma invoice | Non-posting document | P0 | Done | Clearly labeled |
| Credit note | Sales return or adjustment | P0 | Done | Link to original invoice |
| Debit note | Purchase return or adjustment | P0 | Done | Link to original bill |
| Purchase bill | Record supplier invoice and stock inward | P0 | Done | Phase 4 |
| Purchase return | Reverse stock | P0 | Done | Phase 4 |
| Payment voucher | Money in and money out | P0 | Done | Phase 5 |
| Expense voucher | Category, amount, receipt | P0 | Done | Phase 5; receipt attachments after 1.0 |
| POS billing | Fast counter workflow and barcode scan | P1 | Done | Counter screen (scan or search, Cash sale default, tablet two-pane layout, 58/80 mm receipt); posts through the normal invoice engine |
| Recurring invoice | Schedule and generate | P1 | Done | Daily/weekly/monthly/yearly; drafts or auto-post; missed periods made on app open, once each; no OS background job |
| Invoice numbering | Prefix, suffix, sequence, FY reset | P0 | Done | Per document type |
| Due date and payment terms | Optional | P0 | Done | Drives aging |
| Item-level discount | Percentage or amount | P0 | Done | Tax computed after discount |
| Invoice-level discount | Percentage or amount | P0 | Done | Allocation across lines |
| Additional charges | Freight, packing, etc. | P0 | Done | Taxable or non-taxable flag |
| Round-off | Nearest rupee | P0 | Done | Ledger rounding account |
| Multi-currency | Exchange rate and currency | P1 | Done | Export and SEZ invoices: foreign rates, GST, ledger and returns in rupees (Rule 34); no party default currency or exchange gain/loss ledger |
| Reverse charge | GST reverse charge flag | P1 | Done | Sales and purchases; GSTR-1 rchrg and GSTR-3B 3.1(c)/3.1(d)/4(A)(3) |
| TDS and TCS | Threshold-based | P2 | Not started | After 1.0 |
| Attachments | Receipt or reference image | P0 | Done | Files included in backup; expense receipt attachments are post-1.0 |
| Duplicate invoice | Copy lines into a new draft | P0 | Done | New number required |
| Cancel invoice | Reason and audit trail | P0 | Done | Stock reversed if posted |
| Restore invoice | Recycle bin | P0 | Done | Restore with audit |
| E-invoice | IRN generation via portal or GSP | P1 | Not started | Bring-your-own credentials |
| E-way bill | Generate and share | P1 | Not started | Bring-your-own credentials |
| Delivery challan | Non-posting movement document | P1 | Done | After 1.0 |
| Delivery challan with e-way bill | Challan carries transport details and can drive e-way generation | P1 | Partial | Transport details and e-way bill number print on challans; generation not started |
| Purchase order | Order to a supplier; convert to purchase bill | P1 | Done | Non-posting; preserves source link; partial receipt |
| Sales order | Customer order that can be converted to an invoice | P1 | Done | Non-posting; preserves source link; partial fulfilment; quotation converts to it |
| Sales return e-invoice | E-invoice for credit notes | P1 | Not started | With e-invoice support |
| Bulk e-invoice and e-way generation | Select many invoices and generate in one run | P2 | Not started | Rate-limited queue with per-document results |
| E-way expiry alerts | Notify before an e-way bill expires | P2 | Not started | Local notification |
| Transport details | Transporter ID, document number and date, mode, vehicle, distance | P1 | Done | Needed for e-way bill and challan |
| Export and shipping details | Shipping bill number and date, port, export type, LUT or bond reference | P1 | Done | Exports (WPAY/WOPAY) and SEZ (SEZWP/SEZWOP) in GSTR-1; currency is a printed note only |
| E-commerce operator GSTIN | Marketplace GSTIN recorded on the invoice | P1 | Done | Optional header field; the ship-to GSTIN itself is P0 (section 3) |
| ITC applicable flag | Mark purchase lines eligible or ineligible for input credit | P1 | Done | Blocked ITC goes to cost and GSTR-3B 4(D)(2) |
| Tax-rate change advisory | After a scheduled rate change, prompt the user to review items still on the old rate | P1 | Done | Driven by the versioned rate schedule; never rewrites posted documents |
| Price-change prompt | When a line price differs from the item master, offer to update the master | P1 | Done | Explicit confirmation; audit event on change |
| Filing-period warning | Warn before cancelling or deleting a document inside a period already exported for GSTR-1 | P1 | Done | Cutoff is the GSTR-1 due date (11th of next month); warn, do not block; audit event records the acknowledgement |
| Discount before or after tax | Per-firm choice on whether invoice discount is applied before tax (and spread across lines) or after | P0 | Done | Explicit setting; documented in the tax engine |
| Free quantity | Bonus quantity on a line that does not change price | P1 | Done | Stock moves, revenue does not |
| Item description on line | Free text under each line | P0 | Done | Printed under item name |
| Additional document fields | Custom header fields (PO number, vehicle number) | P1 | Done | PO, e-way bill number and custom labelled fields per business |
| Split payment on invoice | Several tenders recorded as separate payments against one invoice | P0 | Done | Creates multiple payment-in entries |
| Party-level discount on payment | Single discount settled against all outstanding invoices of a party | P1 | Done | Settlement discount on a receipt or payment, posted to Discount allowed / Discount received; app only, no CLI flag |
| TDS and TCS on payments | Pre-defined and custom rates applied when linking payments to invoices | P2 | Not started | After 1.0; enable per firm |
| Staff loan and salary advance | Payments to staff tracked as loan or advance and offset later | P2 | Not started | Part of payroll |
| Unlimited recurring schedules | Vendor limits the count of active schedules by plan | P1 | Done | Recurring invoices are unlimited in OpenBill; part of the recurring-invoice feature |
| POS held bills | Several open bills at once, switch between them | P1 | Done | Held drafts survive restarts and are in backups (schema v14); discard asks first and goes to the recycle bin |
| POS multi-tender | Cash, UPI, card, wallet on a single sale | P1 | Done | Cash, UPI, card and bank with change due; paying short needs credit to a named party; no wallet tender |
| POS item-first billing | Item grid or barcode first, party optional | P1 | Done | Party defaults to Cash sale |
| POS keyboard commands | Quick keys for add, discount, tender, hold | P1 | Done | F1 help, F2 search, F3 qty, F4 discount, F6 held, F8 hold, F9 new, F12 pay; desktop and tablet keyboards |
| Cash drawer | Open drawer on sale via printer kick | P2 | Not started | Desktop and thermal printers |
| Restaurant table orders | Table-wise open orders and kitchen slip | P2 | Not started | Optional module |
| OCR purchase-bill capture | Photograph a supplier bill and prefill a purchase draft | P2 | Not started | Opt-in; local on-device OCR first, optional bring-your-own API key for a cloud model; user must review before posting |
| Gallery scan for invoices | Find bill images in the photo library | P2 | Not started | Opt-in; the user picks a folder or photos through the system picker (no blanket media permission); on-device recognition; results are suggestions to review |

Tax engine requirements:

- Integer paise arithmetic; no floating-point accumulation.
- Line-level and invoice-level rounding strategies are explicit.
- CGST/SGST split for intra-state; IGST for inter-state.
- Inclusive and exclusive tax modes per line or invoice.
- Cess and additional tax types.
- HSN summary generated from posted lines.
- Negative lines and credit notes handled without sign errors.
- A versioned tax-rule dataset so rate changes do not rewrite history.

## 6. Payments and accounting

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Payment-in | Record customer payment | P0 | Done | Cash, bank, UPI, cheque |
| Payment-out | Record supplier payment | P0 | Done | Same modes |
| Partial payment | Allocate part of an invoice | P0 | Done | Multiple allocations per payment |
| Advance payment | Unallocated credit | P0 | Done | Apply later |
| Payment allocation UI | Choose invoices and amounts | P0 | Done | Prevent overallocation |
| Party ledger | Running balance | P0 | Done | Drill-down |
| Receivables aging | 0-30, 31-60, 61-90, 90+ | P0 | Done | Configurable buckets |
| Payables aging | Same for suppliers | P0 | Done | Phase 5 |
| Cash and bank accounts | Multiple accounts | P0 | Done | Opening balances |
| Bank transfer | Between accounts | P1 | Done | Two-sided entry |
| UPI QR | Static QR on invoice | P0 | Done | Generate locally; no payment processing |
| Payment reminders | Share text or PDF | P0 | Done | Deep link to WhatsApp; no API required |
| Bank statement import | CSV | P0 | Done | Rule-based matching |
| Bank reconciliation | Match statement lines to vouchers | P0 | Done | Manual confirmation |
| Cheque tracking | Post-dated cheques | P2 | Not started | After 1.0 |
| Bad debt write-off | Adjustment entry | P2 | Not started | Audit trail |
| Share payment link | Link or QR sent with a reminder | P1 | Not started | UPI intent or static QR only; no gateway |
| Multiple bank accounts with deactivation | Retire an account but keep history | P0 | Done | Soft state |
| Other ledger accounts | User-defined ledgers such as loans given or taken, capital, and other income or expense | P2 | Not started | Needs the full accounting model; see balance sheet in section 7 |
| Staff payroll postings | Salary, bonus, advance as expense or loan postings | P2 | Not started | Phase 7 payroll |
| AI payment-reminder calls | Automated voice reminders | P2 | Not started | Opt-in; bring-your-own telephony or voice provider through a server plugin, user-approved script and schedule, consent and do-not-call list honoured; no vendor-hosted calling |
| AI-assisted bank reconciliation | Suggested matches from statement lines | P2 | Not started | Rule-based suggestions first; manual confirm remains |

## 7. Reports and GST

| Report | Priority | Status | Notes |
| --- | --- | --- | --- |
| Sales register | P0 | Done | Date, party, status, type filters |
| Purchase register | P0 | Done | Phase 4 |
| Day book | P0 | Done | All vouchers by date |
| Cash and bank book | P0 | Done | Per account |
| Party statement | P0 | Done | PDF and CSV |
| Outstanding receivables | P0 | Done | Aging |
| Outstanding payables | P0 | Done | Aging |
| Stock summary | P0 | Done | Per godown, category, item |
| Item-wise profit | P0 | Done | Requires cost basis |
| Expense report | P0 | Done | Category breakdown |
| GST summary | P0 | Done | Output and input tax |
| HSN summary | P0 | Done | Quantity and taxable value |
| GSTR-1 export | P0 | Done | CSV or JSON as supported |
| GSTR-3B working | P1 | Done | Internal computation only; the CLI `gst gstr3b` command ships with it, after 1.0 |
| Tally XML export | P0 | Done | Document mapping |
| Day summary | P1 | Done | Cash in, cash out, sales per day; ties to the sales register and cash and bank book |
| Sales summary by user | P1 | Done | Who posted each voucher, from the activity log; old vouchers count as Owner |
| Day book by user | P1 | Done | Day book with a User column, sorted by user |
| GSTR-2 working (purchases) | P1 | Done | Internal computation only: per bill and rate, B2B/unregistered, ITC eligible/ineligible, reverse charge; CSV/JSON; eligible ITC equals GSTR-3B 4(C); imports not tracked |
| GST sales and purchase with HSN | P0 | Done | Included in GST summary and HSN summary |
| GSTR-1 JSON for portal upload | P0 | Done | Same data as CSV export, portal-compatible layout |
| Low-stock report | P0 | Done | Companion to the dashboard card |
| Batch and expiry report | P1 | Done | Batches with stock as of the period end, days left and status; see section 4 |
| Cash and bank (all payments) | P0 | Done | Combined view across accounts |
| Daily balance-sheet snapshot | P2 | Not started | Computed on demand, not cached remotely |
| Select several reports and share together | P1 | Done | Reports hub: choose reports, share the CSVs together or as one ZIP |
| Email a report | P1 | Done | Through the user's mail client: Android mail-only share sheet with attachments; desktop opens a mail draft and the folder with the files (mailto cannot attach); no SMTP |
| Bulk download of reports as ZIP | P1 | Done | Desktop and Android save dialog; CLI `report ca-pack`; built in memory (text reports are small) |
| User activity tracker | P1 | Done | The activity log (filter by user, CSV) and the by-user reports; see section 10 |
| CA report pack | P1 | Done | One ZIP: registers, day book, GSTR-1 (CSV and monthly JSON), GSTR-2 (CSV and JSON), GSTR-3B, HSN, outstanding, party ledgers, Tally XML; tested identical to the single reports |
| Large stock-summary export | P0 | Done | Never silently capped; stream the full list |
| Balance sheet | P2 | Not started | Requires complete accounting model |
| Profit and loss | P2 | Done | After expense and opening balance maturity |
| Custom report builder | P2 | Not started | Not in 1.0 |
| Scheduled report email | P2 | Not started | User-provided SMTP or manual export |

Report correctness rules:

- Every report reconciles to the ledger for the same date range.
- Filters are visible in the exported file.
- Exports never silently truncate; large exports stream or page.
- Negative and cancelled documents are handled explicitly, not hidden.

## 8. Printing, themes, and output

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| A4 invoice | Standard GST invoice layout | P0 | Done | At least three themes |
| A5 invoice | Compact layout | P0 | Done | Phase 2 |
| Thermal 58 mm | Receipt-style output | P0 | Done | Android Bluetooth and desktop USB/CUPS |
| Thermal 80 mm | Wider receipt | P0 | Done | Same |
| Custom logo | Header placement | P0 | Done | Per firm |
| Signature image | Authorized signatory | P0 | Done | Per firm |
| Terms and conditions | Per firm and per document type | P0 | Done | Rich text limited to safe formatting |
| QR code | UPI or document link | P0 | Done | Locally generated |
| Barcode on invoice | Item barcode | P2 | Not started | Optional |
| Custom theme editor | Colors, fonts, layout | P2 | Not started | Start with built-in themes |
| Multi-language invoice | Hindi and regional labels | P1 | Not started | Template-level locale |
| Print preview | WYSIWYG before printing | P0 | Done | Shared renderer |
| Bulk print | Print a selection of invoices in order | P0 | Done | Desktop (W, L): a sequential loop over silent print with progress, cancellation, and per-invoice failures; Android prints one at a time through the system print service |
| Export notification | OS notification after an export, backup, or download, with a show-in-folder action | P0 | Done | Desktop; Android uses the system download notification |
| Silent print to default printer | Print without a dialog to the default or a chosen printer | P0 | Done | Desktop (W, L); a setting beside the system dialog, off until the user enables it |
| A3 paper size | Large-format output | P2 | Not started | Template page-size option |
| Theme families | Several named layout families with colour variants | P0 | Done | Original designs only; no vendor themes |
| Theme gallery | Downloadable invoice themes | P2 | Not started | Free community theme packs as files (import from file or a repository); no paid store and no vendor assets |
| Custom theme creator | User-defined header, columns, footer | P2 | Not started | Same as theme editor |
| Show bank details, phone, timestamp, alternate unit, discount-on-MRP on document | Per-template toggles | P0 | Done | Template options |
| Label-printer barcode sheets | Dedicated label printers | P1 | Not started | Desktop and thermal |
| PDF password | Protect shared PDF | P2 | Not started | Optional |
| Share as image | PNG/JPG | P0 | Done | Android share sheet; desktop save |

Rendering strategy:

- One shared document model feeds Android and desktop renderers.
- PDF output is deterministic for the same data and template version.
- Fonts are bundled or system-safe; missing glyphs must not produce blank text.
- Thermal layouts avoid fonts and spacing that fail on 203 dpi printers.

## 9. Data, import, and export

| Feature | Priority | Status | Notes |
| --- | --- | --- | --- |
| Full backup | P0 | Done | Zip with manifest, SQLite database, attachments |
| Restore | P0 | Done | Validate version and checksum before replacing data |
| Automatic pre-migration backup | P0 | Done | Safety net for schema upgrades |
| CSV export of parties and items | P0 | Done | Round-trip friendly |
| CSV import of parties and items | P0 | Done | Dry-run validation and error report |
| Invoice CSV export | P0 | Done | For accountants |
| Tally XML export | P0 | Done | Phase 6 |
| GSTR-1 export | P0 | Done | Phase 6 |
| Attachment export | P1 | Not started | Preserve folder structure |
| Bulk PDF/ZIP export of invoices | P0 | Done | Selected invoices or a date range as PDFs or one zip, generated locally; desktop at 1.0, Android follows in Phase 7 |
| Competitor file import | P2 | Not started | Only if a public format spec exists |
| Auto online backup | P2 | Not started | Replaced by optional encrypted user-owned storage |
| Portable desktop mode | P0 | Done | Database beside executable on removable media |
| Cloud backup | P2 | Not started | Optional, encrypted, user-owned storage |
| Sync between devices | P2 | Not started | Requires conflict design; not in 1.0 |

Backup format requirements:

- Human-readable manifest with schema version, app version, firm IDs, and checksums.
- Database is a consistent snapshot, not a raw copy of a live file.
- Attachments are content-addressed or stored under stable IDs.
- Restore is atomic: failure leaves the existing database untouched.
- Encryption is optional in 1.0 and mandatory before any cloud backup feature.

## 10. Collaboration and access

| Feature | Observed behavior | Priority | Status | Notes |
| --- | --- | --- | --- | --- |
| Multiple users per firm | Invite staff | P1 | Done | Local users on one install (schema v16), each with a name and PIN; user picker on the lock screen; needs app lock; owner signs in with the app lock PIN |
| Roles and permissions | Owner, accountant, operator | P1 | Done | Owner, Manager, Salesperson/cashier, Accountant; checked in the repositories and use cases, menus hidden to match; cashiers bill only at list prices with no discount |
| User activity log | Who changed what | P1 | Done | Audit events carry the user; sign-ins, masters and settings changes logged; filter by user in the app, user column in CSV and `audit list` |
| CA access | Share reports or ledger | P1 | Done | Accountant role: read-only reports, exports and activity log; CSV/Tally/GSTR exports unchanged |
| Device management | Active devices | P2 | Not started | Tied to optional sync |
| Approval workflow | Draft and approve vouchers | P2 | Not started | After 1.0 |
| Shared ledger portal | Online view for accountants | P2 | Not started | Not in 1.0 |
| Staff attendance | Daily attendance, weekly off, shift hours, leaves, self check-in | P2 | Not started | Separate module; local or server |
| Payroll | Salary calculated from attendance, overtime, advances; payslip sharing | P2 | Not started | Separate module; optional |
| Geo-tagged attendance and live location | Location with check-in | P2 | Not started | Opt-in per firm and per staff member with explicit consent; location stored only on the firm's own devices or server; check-in only, no continuous tracking |
| Staff companion app | Separate app for staff check-in | P2 | Not started | A staff mode of the Android app, plus the thin web client on the self-hosted server; no separate vendor app |
| CA seat | Accountant gets a read-only or limited login at no extra user count | P1 | Done | Accountant role; no user limit locally |
| Staff deactivation | Disable a user while preserving history and balances | P1 | Done | Inactive users cannot sign in; their vouchers and log entries stay |
| Suspicious-activity alerts | Notify owner of unusual edits or deletions | P2 | Not started | Rule-based on audit log |

Post-1.0 local role model (design draft):

Roles are not part of 1.0. This draft describes the intended model after 1.0.

- Owner: everything, including backup, restore, firm deletion, and user management.
- Accountant: vouchers, reports, exports; no firm deletion or user management.
- Operator: create invoices and payments; no reports export or settings.
- Read-only: view and print only.

Permissions must be checked in the domain layer, with UI affordances as a secondary
signal.

## 11. Growth, marketing, and commerce

| Feature | Priority | Status | Notes |
| --- | --- | --- | --- |
| WhatsApp invoice sharing | P0 | Done | Android intent; desktop opens wa.me link |
| SMS or email sharing | P1 | Partial | User's own app or mail client |
| Payment reminder templates | P0 | Done | Editable, localized |
| Online catalogue | P2 | Not started | Static export first |
| Hosted storefront | P2 | Not started | Self-hosted server module (Phase 9); not core app |
| UPI and COD on store | P2 | Not started | UPI intent or static QR and COD need no gateway; card gateways only through the user's own provider account |
| WhatsApp campaigns | P2 | Not started | Bring-your-own provider |
| Loyalty points | P2 | Not started | Phase 7 |
| WhatsApp marketing campaigns with templates | P2 | Not started | Bring-your-own provider; no hosted sending |
| SMS or WhatsApp credit packs | No | Not planned | No metered messaging |
| Catalogue share | P2 | Not started | Static export or PDF; no item-count cap |
| Online store with custom domain | P2 | Not started | Self-hosted server module; the user brings the domain and TLS |
| Smart greeting templates | P2 | Not started | See section 3 |
| Referral program | No | Not planned | No growth gimmicks |
| In-app ads | No | Not planned | Never |
| Data brokerage | No | Not planned | Never |

## 12. Settings, security, and support

| Feature | Priority | Status | Notes |
| --- | --- | --- | --- |
| Firm profile and settings | P0 | Done | Tax, invoice, stock, and print defaults |
| Invoice numbering settings | P0 | Done | Preview next number |
| Financial year close | P0 | Done | Reversible until first new-year posting |
| Tax rate management | P0 | Done | Versioned rates with effective dates |
| Backup schedule | P0 | Done | Local, user-selected folder |
| App lock | P1 | Done | Biometric on Android; OS keyring on desktop |
| Secure storage | P0 | Done | Android Keystore, Windows DPAPI, Linux libsecret |
| Audit log | P0 | Done | Append-only; exportable |
| Crash reporting | No | Not planned | No tracking SDK; the opt-in diagnostics export (P1) is the supported path |
| Help center | P1 | Not started | Offline FAQ plus links |
| Contact support | P1 | Not started | Email or issue tracker; no chat SDK in 1.0 |
| Diagnostics export | P1 | Not started | Redacted logs and schema info |
| Data deletion | P0 | Done | Export first, then delete firm or all data |
| Legal and licenses | P0 | Done | Apache-2.0, third-party notices |
| Additional-settings toggles | P0 | Done | Party balance on document and item description; the free-quantity and other-currency toggles arrive with those P1 features |
| TDS/TCS enable switch | P2 | Not started | Per firm |
| Reminder and notification preferences | P1 | Not started | Local notifications; per category |
| E-invoice and e-way settings | P1 | Not started | Credentials stored in secure storage |
| Recycle bin | P0 | Done | Deleted documents previewable and restorable |
| Desktop access gating | No | Not planned | OpenBill desktop is free |

## 13. Platform-specific inventory

Windows and Linux share a single-window, keyboard-first shell with native file dialogs and
portable mode. A single-instance lock (a second launch focuses the existing window) is P0 on
both, because it protects the single-writer database; see [07-desktop.md](07-desktop.md).

### Server (S)

Optional self-hosted service for sync, shared ledger links, and multi-user access. It is
never required for 1.0. See [10-server.md](10-server.md).

### CLI (C)

Command-line tool for scripting, imports, exports, and backups against a local database
or a server. See [11-cli.md](11-cli.md).

### Android

- Share sheet for PDF, image, and text.
- System photo picker and camera capture.
- Contact picker with one-contact-at-a-time import.
- Bluetooth thermal printing.
- Biometric app lock (post-1.0).
- Home-screen widgets (P2).
- App Links for invoice and payment links.
- WorkManager for backups and reminders.
- Play Store distribution; F-Droid is a possible later channel.

### Windows

- MSI installer with per-user and per-machine options.
- Native file dialogs and Windows printing.
- Single-window layout with menu bar, sidebar, and preview pane.
- Keyboard shortcuts and context menus.
- CSV and Excel-friendly exports.
- Portable mode on removable media.
- Windows DPAPI for secrets.
- High-DPI and multi-monitor support.
- Optional Windows Hello app lock (P2).

### Linux

- DEB package for Debian/Ubuntu and AppImage for other distributions.
- CUPS printing and PDF export.
- libsecret for secrets when available; fall back to an encrypted local store with a
  clear warning.
- Single-window layout with menu bar, sidebar, and preview pane.
- Keyboard shortcuts and desktop file integration.
- Portable mode.
- AppImage smoke test on one non-Ubuntu distribution; best effort, not a release gate.

## 14. Deliberate omissions

OpenBill will not include:

- Advertising, attribution, marketing, or contact-harvesting SDKs.
- A mandatory cloud account.
- Paid feature gates of any kind: plans, trials, upgrade prompts, or desktop access.
- Vendor-sold message, scan, or call credit packs.
- Vendor referral or rewards programs, and data brokerage.
- A paid theme store; proprietary invoice themes or illustrations.
- A clone of another product's navigation, wording, or visual identity.
- Lending, payment processing, or money transfer by the project.
- Automated portal filing without the user's credentials and consent.

Everything else the competitor offers is planned. Third-party-dependent features (AI, OCR,
messaging, e-invoice, stores, payment links) are opt-in, use the user's own provider or
credentials or a local model, and never block the offline core.
