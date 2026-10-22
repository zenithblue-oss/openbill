# Feature Inventory

This inventory combines public product documentation, the Play Store listing, the
myBillBook help center, and static inspection of version 8.17.7. It is used to scope
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

Platform legend: `A` = Android, `W` = Windows, `L` = Linux, `S` = self-hosted server,
`C` = command-line tool. Core features are expected on A, W, and L unless noted; S and C
are described in [10-server.md](10-server.md) and [11-cli.md](11-cli.md).

## 1. Onboarding and firm setup

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Phone or email sign-in | Account creation with OTP and social sign-in options | P1 | Optional after 1.0; local-only use requires no account |
| Business creation | Name, type, address, GSTIN, state, financial year | P0 | GSTIN checksum validation locally |
| GSTIN autofill | Fetch legal name and address from GSTIN | P1 | Requires a licensed API or manual entry fallback |
| Multiple firms | Switch between businesses | P0 | Local firm switcher; no artificial limit |
| Business logo and signature | Upload, crop, and place on documents | P0 | Image picker; PDF-safe storage |
| Language selection | English and Indian languages | P1 | Start with English; externalize strings from day one |
| Guided setup | Sample party, item, and invoice | P1 | 1.0 first-run flow is a simple manual firm form; guided setup is post-1.0 |
| Industry type selection | Firm picks an industry (retail, pharmacy, restaurant, jewellery, etc.) to tailor defaults | P2 | Optional preset bundles of units, tax rates, and fields; never required |
| Import from other billing software | Guided import of items and parties from competitor files and spreadsheets | P1 | Generic CSV mapping first; competitor-specific formats only from public documentation |
| Shared product library | Pick items from a common catalogue | P2 | Community-maintained, offline item packs (name, HSN, unit, tax rate) imported from a file or a self-hosted server; no vendor-hosted dataset or upload of user data |
| Credits for scans and messages | Prepaid credits for OCR scans and messaging | No | Vendor-sold credit packs are not offered; OCR and messaging are unmetered and use local models or the user's own provider account |
| Demo or trial mode | Time-limited premium access | No | OpenBill is free and open source; no trial gates |
| Referral and rewards | Invite codes and credits | No | Not part of the product |
| Plan upgrade and paywall | Subscription purchase through Razorpay | No | OpenBill has no paid tiers |
| Push onboarding campaigns | CleverTap-style in-app messages | No | No marketing SDKs |

First-run acceptance criteria:

- A new user can create a firm with only a name and state, then add GST details later.
- A user with a GSTIN can validate it offline and see a clear error for a bad checksum.
- The app works without sign-in, network, contacts access, or location.

## 2. Dashboard and navigation

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Sales summary | Today, week, month, and custom range | P0 | Cached aggregates; no blocking query on launch |
| Quick actions | New invoice, payment, expense, item, party | P0 | Android FAB; desktop toolbar and shortcuts |
| Recent invoices | Paged list with status chips | P0 | Shared paging layer |
| Search bar | Debounced search over invoices | P0 | 300 ms debounce observed; see search analysis |
| Invoice type filters | GST, non-GST, credit note, etc. | P0 | Chips below search |
| Outstanding reminders | Receivables and payables summary | P0 | Tap opens filtered ledger |
| Low-stock card | Items below threshold | P0 | Phase 4 |
| Business switcher | Change active firm | P0 | Preserve per-firm UI state |
| Notifications inbox | Transactional and marketing messages | P1 | Local notifications only; no marketing |
| Command palette | Not observed on Android; desktop apps commonly use one | P0 | Ctrl/Cmd+K on desktop, search icon on Android |
| Home-screen widgets | Sales and quick actions | P2 | Android only |
| Deep links | Branch links to invoices and campaigns | P1 | Standard Android App Links; no Branch dependency |
| Tools and feature-discovery tab | Grid of optional modules and promotional banners | P1 | A modules screen to enable or disable optional modules (POS, restaurant, payroll, and so on); no promotional banners |
| In-app calculator | Calculator that can attach to a party or invoice, with optional floating widget | P2 | Simple calculator; sound and haptics optional |
| Business-wide tools | GST calculator, late-fee calculator | P2 | Offline, public-formula calculators |

## 3. Parties

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Create and edit party | Name, phone, email, GSTIN, type | P0 | Customer and supplier flags |
| Billing and shipping addresses | Multiple shipping addresses | P0 | Address book per party |
| Opening balance | Receivable or payable | P0 | Posted as an opening entry |
| Credit limit and terms | Optional | P1 | Warn, do not block, unless configured |
| Party ledger | Running balance and documents | P0 | Drill-down to voucher |
| Statement export | PDF and CSV | P0 | Date-range filters |
| Party search | Name and phone | P0 | Local index |
| Contact import | Pick a phone contact | P1 | One contact at a time, explicit consent |
| GSTIN autofill | Legal name and address | P1 | Manual fallback |
| Birthday and appointment reminders | CRM-lite features | P2 | Local notifications only |
| Party-wise item price | Special pricing per party | P1 | Phase 7 |
| Party categories | Group parties for filtering and reports | P1 | Flat list of user-defined groups |
| Ship-to GSTIN and delivery site | Separate GSTIN per shipping address, with an unregistered option | P0 | Required for compliant invoices |
| Shared ledger | Party sees their own ledger entries through a link; party can hide or re-enable sharing | P2 | Needs the server (S); read-only link with expiry |
| Service reminders | Per-item service period; reminder to owner and party shortly before due date | P2 | Local notifications; party message via share intent |
| Smart greetings | Templated festival or birthday messages sent to parties | P2 | Share-intent only; no bulk sending service |
| Loyalty and reward points | Points earned per paid bill, redeemable on later invoices; redemption cannot exceed balance | P2 | Phase 7; per-firm rules |
| CRM lite | Follow-up notes and simple customer history beyond the ledger | P2 | Notes, tags, next-contact date |
| Price history for a party | Shows the last few sale or purchase prices for the item and party while billing | P1 | Read-only hint panel |
| Notes and attachments | Free text and files | P0 | Files included in backup; expense receipt attachments are post-1.0 |
| Delete and restore | Recycle bin | P0 | Soft delete with audit event |

## 4. Items and inventory

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Item master | Name, SKU, category, unit, HSN/SAC, tax rate | P0 | Goods and services |
| Multiple prices | Sale, purchase, MRP, wholesale | P0 | Party-specific price later |
| Units | Primary and alternate units with conversion | P0 | Decimal quantities |
| Item images | Upload and show on invoice | P1 | Optional |
| Categories | Grouping and filtering | P0 | Tree or flat list |
| HSN/SAC search | Search by code or description | P0 | Offline dataset |
| Stock tracking | On/off per item | P0 | Service items do not affect stock |
| Godowns | Multiple locations and transfers | P0 | Phase 4 |
| Batch and expiry | Batch number and expiry per movement | P0 | Phase 4 |
| Serial / IMEI | Track individual units | P0 | Phase 4 |
| Stock adjustment | Increase or decrease with reason | P0 | Audit event required |
| Low-stock alerts | Threshold per item or global | P0 | Local notification and dashboard card |
| Barcode generation | Per item and per variant | P0 | Code 128 and QR |
| Barcode label printing | A4 sheets and thermal labels | P0 | Desktop and Android thermal |
| Negative stock | Allow or block per firm | P0 | Explicit setting |
| Stock valuation | Costing method | P0 | Document the method; start with weighted average |
| Item timeline | History of movements | P1 | Read-only audit view |
| Bulk item upload | CSV or Excel | P0 | CSV import is 1.0; Excel upload is post-1.0; dry-run validation required |
| Bulk edit items | Change tax rate, category, or godown for a selection | P1 | Preview and confirm; audit event per item |
| Bulk categorisation | Assign categories to many items at once | P1 | Part of bulk edit |
| Opening stock on upload | Quantity, godown, and batch columns in the upload file | P1 | Posts opening movements |
| Wholesale price | Optional second selling price used for bulk sales | P0 | Covered by multiple prices; explicit toggle |
| Custom item fields | User-defined extra fields (MRP, manufacturing date, part number) shown on documents | P1 | Typed fields; optional on print |
| Renamable serial label | Serial field relabelled by the user (model number, part number) | P2 | Label setting per item |
| Batch and serial exclusivity | An item uses either batches or serials, not both | P0 | Validation rule; document in the data model |
| Duplicate and sold serial checks | Block reuse of a serial already in stock or already sold | P0 | Part of serial invariants |
| FEFO batch suggestion | Suggest the earliest-expiring batch when selling | P1 | Suggestion only; user can override |
| Near-expiry alerts | Notify before batches expire | P1 | Local notification and dashboard card; configurable window |
| Reorder level | Reorder threshold and suggested reorder quantity | P1 | Extends low-stock alerts |
| Batch and expiry report | Stock by batch with expiry dates | P1 | Report; see section 7 |
| Godown transfer | Single and bulk transfers between locations; enabling godowns moves existing stock to a default location | P0 | Reversing movements on edit; warn when editing old documents changes opening stock |
| Multi-godown stock view | Per-item quantity by location | P0 | Limit of locations in a picker is a UI choice, not a data limit |
| Alternate unit on invoice | Show secondary unit quantity on printed document | P1 | Template option |
| Barcode scanning in billing | Scan to add a line; clear message when code is unknown | P0 | Camera on Android; HID scanner on desktop |
| Weighing-scale input | Read weight from a connected scale for loose goods | P2 | Desktop serial or USB; optional |

Inventory invariants:

- No stock quantity is edited directly; every change is a movement.
- Deleting a posted voucher creates reversing movements, not silent deletions.
- Batch and serial quantities must match the voucher line quantity.
- Stock cannot go negative unless the firm enables it.
- Every movement records source document, timestamp, user, and reason.

## 5. Billing and vouchers

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Sales invoice | GST and non-GST | P0 | Primary workflow |
| Quotation | Convert to invoice | P0 | Preserve source link |
| Proforma invoice | Non-posting document | P0 | Clearly labeled |
| Credit note | Sales return or adjustment | P0 | Link to original invoice |
| Debit note | Purchase return or adjustment | P0 | Link to original bill |
| Purchase bill | Record supplier invoice and stock inward | P0 | Phase 4 |
| Purchase return | Reverse stock | P0 | Phase 4 |
| Payment voucher | Money in and money out | P0 | Phase 5 |
| Expense voucher | Category, amount, receipt | P0 | Phase 5; receipt attachments after 1.0 |
| POS billing | Fast counter workflow and barcode scan | P1 | Phase 7 |
| Recurring invoice | Schedule and generate | P1 | Phase 7 |
| Invoice numbering | Prefix, suffix, sequence, FY reset | P0 | Per document type |
| Due date and payment terms | Optional | P0 | Drives aging |
| Item-level discount | Percentage or amount | P0 | Tax computed after discount |
| Invoice-level discount | Percentage or amount | P0 | Allocation across lines |
| Additional charges | Freight, packing, etc. | P0 | Taxable or non-taxable flag |
| Round-off | Nearest rupee | P0 | Ledger rounding account |
| Multi-currency | Exchange rate and currency | P1 | Phase 7 groundwork |
| Reverse charge | GST reverse charge flag | P1 | After 1.0 |
| TDS and TCS | Threshold-based | P2 | After 1.0 |
| Attachments | Receipt or reference image | P0 | Files included in backup; expense receipt attachments are post-1.0 |
| Duplicate invoice | Copy lines into a new draft | P0 | New number required |
| Cancel invoice | Reason and audit trail | P0 | Stock reversed if posted |
| Restore invoice | Recycle bin | P0 | Restore with audit |
| E-invoice | IRN generation via portal or GSP | P1 | Bring-your-own credentials |
| E-way bill | Generate and share | P1 | Bring-your-own credentials |
| Delivery challan | Non-posting movement document | P1 | After 1.0 |
| Delivery challan with e-way bill | Challan carries transport details and can drive e-way generation | P1 | Bring-your-own credentials |
| Purchase order | Order to a supplier; convert to purchase bill | P1 | Non-posting; preserves source link; partial receipt |
| Sales order | Customer order that can be converted to an invoice | P1 | Non-posting; preserves source link; partial fulfilment; mirrors purchase order |
| Sales return e-invoice | E-invoice for credit notes | P1 | With e-invoice support |
| Bulk e-invoice and e-way generation | Select many invoices and generate in one run | P2 | Rate-limited queue with per-document results |
| E-way expiry alerts | Notify before an e-way bill expires | P2 | Local notification |
| Transport details | Transporter ID, document number and date, mode, vehicle, distance | P1 | Needed for e-way bill and challan |
| Export and shipping details | Shipping bill number and date, port, export type, LUT or bond reference | P1 | Part of foreign-currency billing |
| E-commerce operator GSTIN | Marketplace GSTIN recorded on the invoice | P1 | Optional header field; the ship-to GSTIN itself is P0 (section 3) |
| ITC applicable flag | Mark purchase lines eligible or ineligible for input credit | P1 | Feeds GST working |
| Tax-rate change advisory | After a scheduled rate change, prompt the user to review items still on the old rate | P1 | Driven by the versioned rate schedule; never rewrites posted documents |
| Price-change prompt | When a line price differs from the item master, offer to update the master | P1 | Explicit confirmation; audit event on change |
| Filing-period warning | Warn before cancelling or deleting a document inside a period already exported for GSTR-1 | P1 | Warn, do not block; audit event records the acknowledgement |
| Discount before or after tax | Per-firm choice on whether invoice discount is applied before tax (and spread across lines) or after | P0 | Explicit setting; documented in the tax engine |
| Free quantity | Bonus quantity on a line that does not change price | P1 | Stock moves, revenue does not |
| Item description on line | Free text under each line | P0 | Printed under item name |
| Additional document fields | Custom header fields (PO number, vehicle number) | P1 | Typed, optional on print |
| Split payment on invoice | Several tenders recorded as separate payments against one invoice | P0 | Creates multiple payment-in entries |
| Party-level discount on payment | Single discount settled against all outstanding invoices of a party | P1 | Posted as an adjustment |
| TDS and TCS on payments | Pre-defined and custom rates applied when linking payments to invoices | P2 | After 1.0; enable per firm |
| Staff loan and salary advance | Payments to staff tracked as loan or advance and offset later | P2 | Part of payroll |
| Unlimited recurring schedules | Vendor limits the count of active schedules by plan | P1 | Recurring invoices are unlimited in OpenBill; part of the recurring-invoice feature |
| POS held bills | Several open bills at once, switch between them | P1 | Phase 7 POS |
| POS multi-tender | Cash, UPI, card, wallet on a single sale | P1 | Phase 7 POS |
| POS item-first billing | Item grid or barcode first, party optional | P1 | Phase 7 POS |
| POS keyboard commands | Quick keys for add, discount, tender, hold | P1 | Desktop |
| Cash drawer | Open drawer on sale via printer kick | P2 | Desktop and thermal printers |
| Restaurant table orders | Table-wise open orders and kitchen slip | P2 | Optional module |
| OCR purchase-bill capture | Photograph a supplier bill and prefill a purchase draft | P2 | Opt-in; local on-device OCR first, optional bring-your-own API key for a cloud model; user must review before posting |
| Gallery scan for invoices | Find bill images in the photo library | P2 | Opt-in; the user picks a folder or photos through the system picker (no blanket media permission); on-device recognition; results are suggestions to review |

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

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Payment-in | Record customer payment | P0 | Cash, bank, UPI, cheque |
| Payment-out | Record supplier payment | P0 | Same modes |
| Partial payment | Allocate part of an invoice | P0 | Multiple allocations per payment |
| Advance payment | Unallocated credit | P0 | Apply later |
| Payment allocation UI | Choose invoices and amounts | P0 | Prevent overallocation |
| Party ledger | Running balance | P0 | Drill-down |
| Receivables aging | 0-30, 31-60, 61-90, 90+ | P0 | Configurable buckets |
| Payables aging | Same for suppliers | P0 | Phase 5 |
| Cash and bank accounts | Multiple accounts | P0 | Opening balances |
| Bank transfer | Between accounts | P1 | Two-sided entry |
| UPI QR | Static QR on invoice | P0 | Generate locally; no payment processing |
| Payment reminders | Share text or PDF | P0 | Deep link to WhatsApp; no API required |
| Bank statement import | CSV | P0 | Rule-based matching |
| Bank reconciliation | Match statement lines to vouchers | P0 | Manual confirmation |
| Cheque tracking | Post-dated cheques | P2 | After 1.0 |
| Bad debt write-off | Adjustment entry | P2 | Audit trail |
| Share payment link | Link or QR sent with a reminder | P1 | UPI intent or static QR only; no gateway |
| Multiple bank accounts with deactivation | Retire an account but keep history | P0 | Soft state |
| Other ledger accounts | User-defined ledgers such as loans given or taken, capital, and other income or expense | P2 | Needs the full accounting model; see balance sheet in section 7 |
| Staff payroll postings | Salary, bonus, advance as expense or loan postings | P2 | Phase 7 payroll |
| AI payment-reminder calls | Automated voice reminders | P2 | Opt-in; bring-your-own telephony or voice provider through a server plugin, user-approved script and schedule, consent and do-not-call list honoured; no vendor-hosted calling |
| AI-assisted bank reconciliation | Suggested matches from statement lines | P2 | Rule-based suggestions first; manual confirm remains |

## 7. Reports and GST

| Report | Priority | Notes |
| --- | --- | --- |
| Sales register | P0 | Date, party, status, type filters |
| Purchase register | P0 | Phase 4 |
| Day book | P0 | All vouchers by date |
| Cash and bank book | P0 | Per account |
| Party statement | P0 | PDF and CSV |
| Outstanding receivables | P0 | Aging |
| Outstanding payables | P0 | Aging |
| Stock summary | P0 | Per godown, category, item |
| Item-wise profit | P0 | Requires cost basis |
| Expense report | P0 | Category breakdown |
| GST summary | P0 | Output and input tax |
| HSN summary | P0 | Quantity and taxable value |
| GSTR-1 export | P0 | CSV or JSON as supported |
| GSTR-3B working | P1 | Internal computation only; the CLI `gst gstr3b` command ships with it, after 1.0 |
| Tally XML export | P0 | Document mapping |
| Day summary | P1 | Cash in, cash out, sales |
| Sales summary by user | P1 | Needs local users |
| Day book by user | P1 | Needs local users |
| GSTR-2 working (purchases) | P1 | Internal computation only |
| GST sales and purchase with HSN | P0 | Included in GST summary and HSN summary |
| GSTR-1 JSON for portal upload | P0 | Same data as CSV export, portal-compatible layout |
| Low-stock report | P0 | Companion to the dashboard card |
| Batch and expiry report | P1 | See section 4 |
| Cash and bank (all payments) | P0 | Combined view across accounts |
| Daily balance-sheet snapshot | P2 | Computed on demand, not cached remotely |
| Select several reports and share together | P1 | One bundle or ZIP |
| Email a report | P1 | Through the user's mail client |
| Bulk download of reports as ZIP | P1 | Desktop and Android; streamed (invoice export is P0, section 9) |
| User activity tracker | P1 | Needs local users; see section 10 |
| CA report pack | P1 | Preconfigured set of reports for an accountant |
| Large stock-summary export | P0 | Never silently capped; stream the full list |
| Balance sheet | P2 | Requires complete accounting model |
| Profit and loss | P2 | After expense and opening balance maturity |
| Custom report builder | P2 | Not in 1.0 |
| Scheduled report email | P2 | User-provided SMTP or manual export |

Report correctness rules:

- Every report reconciles to the ledger for the same date range.
- Filters are visible in the exported file.
- Exports never silently truncate; large exports stream or page.
- Negative and cancelled documents are handled explicitly, not hidden.

## 8. Printing, themes, and output

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| A4 invoice | Standard GST invoice layout | P0 | At least three themes |
| A5 invoice | Compact layout | P0 | Phase 2 |
| Thermal 58 mm | Receipt-style output | P0 | Android Bluetooth and desktop USB/CUPS |
| Thermal 80 mm | Wider receipt | P0 | Same |
| Custom logo | Header placement | P0 | Per firm |
| Signature image | Authorized signatory | P0 | Per firm |
| Terms and conditions | Per firm and per document type | P0 | Rich text limited to safe formatting |
| QR code | UPI or document link | P0 | Locally generated |
| Barcode on invoice | Item barcode | P2 | Optional |
| Custom theme editor | Colors, fonts, layout | P2 | Start with built-in themes |
| Multi-language invoice | Hindi and regional labels | P1 | Template-level locale |
| Print preview | WYSIWYG before printing | P0 | Shared renderer |
| Bulk print | Print a selection of invoices in order | P0 | Desktop (W, L): a sequential loop over silent print with progress, cancellation, and per-invoice failures; Android prints one at a time through the system print service |
| Export notification | OS notification after an export, backup, or download, with a show-in-folder action | P0 | Desktop; Android uses the system download notification |
| Silent print to default printer | Print without a dialog to the default or a chosen printer | P0 | Desktop (W, L); a setting beside the system dialog, off until the user enables it |
| A3 paper size | Large-format output | P2 | Template page-size option |
| Theme families | Several named layout families with colour variants | P0 | Original designs only; no vendor themes |
| Theme gallery | Downloadable invoice themes | P2 | Free community theme packs as files (import from file or a repository); no paid store and no vendor assets |
| Custom theme creator | User-defined header, columns, footer | P2 | Same as theme editor |
| Show bank details, phone, timestamp, alternate unit, discount-on-MRP on document | Per-template toggles | P0 | Template options |
| Label-printer barcode sheets | Dedicated label printers | P1 | Desktop and thermal |
| PDF password | Protect shared PDF | P2 | Optional |
| Share as image | PNG/JPG | P0 | Android share sheet; desktop save |

Rendering strategy:

- One shared document model feeds Android and desktop renderers.
- PDF output is deterministic for the same data and template version.
- Fonts are bundled or system-safe; missing glyphs must not produce blank text.
- Thermal layouts avoid fonts and spacing that fail on 203 dpi printers.

## 9. Data, import, and export

| Feature | Priority | Notes |
| --- | --- | --- |
| Full backup | P0 | Zip with manifest, SQLite database, attachments |
| Restore | P0 | Validate version and checksum before replacing data |
| Automatic pre-migration backup | P0 | Safety net for schema upgrades |
| CSV export of parties and items | P0 | Round-trip friendly |
| CSV import of parties and items | P0 | Dry-run validation and error report |
| Invoice CSV export | P0 | For accountants |
| Tally XML export | P0 | Phase 6 |
| GSTR-1 export | P0 | Phase 6 |
| Attachment export | P1 | Preserve folder structure |
| Bulk PDF/ZIP export of invoices | P0 | Selected invoices or a date range as PDFs or one zip, generated locally; desktop at 1.0, Android follows in Phase 7 |
| Competitor file import | P2 | Only if a public format spec exists |
| Auto online backup | P2 | Replaced by optional encrypted user-owned storage |
| Portable desktop mode | P0 | Database beside executable on removable media |
| Cloud backup | P2 | Optional, encrypted, user-owned storage |
| Sync between devices | P2 | Requires conflict design; not in 1.0 |

Backup format requirements:

- Human-readable manifest with schema version, app version, firm IDs, and checksums.
- Database is a consistent snapshot, not a raw copy of a live file.
- Attachments are content-addressed or stored under stable IDs.
- Restore is atomic: failure leaves the existing database untouched.
- Encryption is optional in 1.0 and mandatory before any cloud backup feature.

## 10. Collaboration and access

| Feature | Observed behavior | Priority | Notes |
| --- | --- | --- | --- |
| Multiple users per firm | Invite staff | P1 | Post-1.0 local profiles |
| Roles and permissions | Owner, accountant, operator | P1 | Post-1.0; enforced in use cases, not only UI |
| User activity log | Who changed what | P1 | Post-1.0 with local users |
| CA access | Share reports or ledger | P1 | Export or read-only mode |
| Device management | Active devices | P2 | Tied to optional sync |
| Approval workflow | Draft and approve vouchers | P2 | After 1.0 |
| Shared ledger portal | Online view for accountants | P2 | Not in 1.0 |
| Staff attendance | Daily attendance, weekly off, shift hours, leaves, self check-in | P2 | Separate module; local or server |
| Payroll | Salary calculated from attendance, overtime, advances; payslip sharing | P2 | Separate module; optional |
| Geo-tagged attendance and live location | Location with check-in | P2 | Opt-in per firm and per staff member with explicit consent; location stored only on the firm's own devices or server; check-in only, no continuous tracking |
| Staff companion app | Separate app for staff check-in | P2 | A staff mode of the Android app, plus the thin web client on the self-hosted server; no separate vendor app |
| CA seat | Accountant gets a read-only or limited login at no extra user count | P1 | Role in post-1.0 model |
| Staff deactivation | Disable a user while preserving history and balances | P1 | Soft state |
| Suspicious-activity alerts | Notify owner of unusual edits or deletions | P2 | Rule-based on audit log |

Post-1.0 local role model (design draft):

Roles are not part of 1.0. This draft describes the intended model after 1.0.

- Owner: everything, including backup, restore, firm deletion, and user management.
- Accountant: vouchers, reports, exports; no firm deletion or user management.
- Operator: create invoices and payments; no reports export or settings.
- Read-only: view and print only.

Permissions must be checked in the domain layer, with UI affordances as a secondary
signal.

## 11. Growth, marketing, and commerce

| Feature | Priority | Notes |
| --- | --- | --- |
| WhatsApp invoice sharing | P0 | Android intent; desktop opens wa.me link |
| SMS or email sharing | P1 | User's own app or mail client |
| Payment reminder templates | P0 | Editable, localized |
| Online catalogue | P2 | Static export first |
| Hosted storefront | P2 | Self-hosted server module (Phase 9); not core app |
| UPI and COD on store | P2 | UPI intent or static QR and COD need no gateway; card gateways only through the user's own provider account |
| WhatsApp campaigns | P2 | Bring-your-own provider |
| Loyalty points | P2 | Phase 7 |
| WhatsApp marketing campaigns with templates | P2 | Bring-your-own provider; no hosted sending |
| SMS or WhatsApp credit packs | No | No metered messaging |
| Catalogue share | P2 | Static export or PDF; no item-count cap |
| Online store with custom domain | P2 | Self-hosted server module; the user brings the domain and TLS |
| Smart greeting templates | P2 | See section 3 |
| Referral program | No | No growth gimmicks |
| In-app ads | No | Never |
| Data brokerage | No | Never |

## 12. Settings, security, and support

| Feature | Priority | Notes |
| --- | --- | --- |
| Firm profile and settings | P0 | Tax, invoice, stock, and print defaults |
| Invoice numbering settings | P0 | Preview next number |
| Financial year close | P0 | Reversible until first new-year posting |
| Tax rate management | P0 | Versioned rates with effective dates |
| Backup schedule | P0 | Local, user-selected folder |
| App lock | P1 | Biometric on Android; OS keyring on desktop |
| Secure storage | P0 | Android Keystore, Windows DPAPI, Linux libsecret |
| Audit log | P0 | Append-only; exportable |
| Crash reporting | No | No tracking SDK; the opt-in diagnostics export (P1) is the supported path |
| Help center | P1 | Offline FAQ plus links |
| Contact support | P1 | Email or issue tracker; no chat SDK in 1.0 |
| Diagnostics export | P1 | Redacted logs and schema info |
| Data deletion | P0 | Export first, then delete firm or all data |
| Legal and licenses | P0 | Apache-2.0, third-party notices |
| Additional-settings toggles | P0 | Party balance on document and item description; the free-quantity and other-currency toggles arrive with those P1 features |
| TDS/TCS enable switch | P2 | Per firm |
| Reminder and notification preferences | P1 | Local notifications; per category |
| E-invoice and e-way settings | P1 | Credentials stored in secure storage |
| Recycle bin | P0 | Deleted documents previewable and restorable |
| Desktop access gating | No | OpenBill desktop is free |

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
