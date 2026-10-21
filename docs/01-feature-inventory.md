# Feature Inventory

This inventory combines public product documentation, the Play Store listing, the
myBillBook help center, and static inspection of version 8.17.7. It is used to scope
OpenBill, not to clone every behavior.

**Scope of record:** MVP means 1.0. The authoritative phase-by-phase scope is in
[ROADMAP.md](../ROADMAP.md#release-scope-of-record). P0 is required for 1.0; P1 follows
1.0; P2 is later or optional.

Priority legend:

- `P0` - required for 1.0 (MVP).
- `P1` - after 1.0.
- `P2` - later or optional.
- `No` - explicitly out of scope.

Platform legend: `A` = Android, `W` = Windows, `L` = Linux. Core features are expected on
all three unless noted.

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
| GSTR-3B working | P1 | Internal computation only |
| Tally XML export | P0 | Document mapping |
| Day summary | P1 | Cash in, cash out, sales |
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
| Batch print | Multiple invoices | P1 | Desktop especially |
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
| Hosted storefront | P2 | Separate service, not core app |
| UPI and COD on store | P2 | Requires payment integration |
| WhatsApp campaigns | P2 | Bring-your-own provider |
| Loyalty points | P2 | Phase 7 |
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
| Crash reporting | No | Opt-in only if ever added; no default SDK |
| Help center | P1 | Offline FAQ plus links |
| Contact support | P1 | Email or issue tracker; no chat SDK in 1.0 |
| Diagnostics export | P1 | Redacted logs and schema info |
| Data deletion | P0 | Export first, then delete firm or all data |
| Legal and licenses | P0 | Apache-2.0, third-party notices |

## 13. Platform-specific inventory

Windows and Linux share a single-window, keyboard-first shell with native file dialogs and
portable mode.

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

- Advertising, attribution, or contact-harvesting SDKs.
- A mandatory cloud account.
- Paid feature gates for exports, desktop access, or backups.
- Proprietary invoice themes or illustrations.
- A clone of another product's navigation, wording, or visual identity.
- Lending, payment processing, or money transfer.
- Automated portal filing without the user's credentials and consent.
