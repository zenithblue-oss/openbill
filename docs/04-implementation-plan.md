# Implementation Plan

This plan turns the roadmap into an ordered backlog. It assumes a small team of two to
four engineers and a strong preference for vertical slices over layer-by-layer delivery.
Estimates are relative and should be re-sized after the foundation sprint.

The scope of record is [ROADMAP.md](../ROADMAP.md#release-scope-of-record): 1.0 is Phases
1-6, and every P0 item is required for 1.0 and cannot be cut.

## 1. Delivery strategy

1. **Prove the cross-platform core first.** If Kotlin Multiplatform, Room KMP, or Compose
   Desktop has a blocker, discover it in the first two weeks.
2. **Ship an Android invoice before shipping a settings screen.** The invoice editor is
   the product.
3. **Bring desktop up as soon as the shared core is stable.** Do not wait for feature
   parity to start the desktop shell.
4. **Keep a working build at all times.** Every merge must produce an installable Android
   debug APK and a runnable desktop app.
5. **Test money and stock at the domain layer.** UI tests are not a substitute for
   accounting invariants.
6. **Cut scope, not correctness.** If time is short, remove themes, not tax tests.

## 2. Assumptions

- Two Android/Compose engineers, one platform/JVM engineer, and part-time design and QA.
- The team can test on at least one low-end Android device, one Windows machine, and one
  Linux machine or VM.
- CI minutes are available for Windows and Ubuntu runners.
- No proprietary code or assets are used.
- The first release is local-only; sync and server work are out of scope.

## 3. Work breakdown

### Epic E0 - Repository and engineering hygiene

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E0.1 | Create Gradle KMP scaffold with Android and desktop targets | - | Version catalog, wrapper, convention plugins |
| E0.2 | Configure formatting, lint, and static analysis | E0.1 | ktlint or detekt; fail CI on new violations |
| E0.3 | Add CI matrix for Ubuntu and Windows | E0.1 | Cache Gradle and Android SDK |
| E0.4 | Add issue templates, PR template, CODEOWNERS, and contribution guide | - | Include clean-room checklist |
| E0.5 | Add dependency locking and vulnerability scanning | E0.1 | Renovate or Dependabot |
| E0.6 | Create app icons and a minimal design token set | - | Original artwork only |

Exit: a pull request runs lint, common tests, Android debug build, and desktop compile on
both operating systems.

### Epic E1 - Core model, money, and tax

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E1.1 | Implement `Money`, `Quantity`, `Rate`, and rounding primitives | E0.1 | Integer paise; property tests |
| E1.2 | Implement GST tax engine with inclusive/exclusive modes | E1.1 | CGST/SGST/IGST, cess |
| E1.3 | Implement discount allocation and invoice round-off | E1.2 | Largest-remainder allocation |
| E1.4 | Build golden-test fixtures for 50+ invoice scenarios | E1.2 | Hand-calculated references |
| E1.5 | Implement GSTIN validation and state-code mapping | E1.1 | Checksum and state table |
| E1.6 | Implement versioned tax-rate schedule | E1.2 | Effective dates; historical rates |
| E1.7 | Document tax assumptions and edge cases | E1.2 | Required for accountant review |

Exit: tax engine passes golden and property tests; no floating-point arithmetic in money
paths.

### Epic E2 - Database and domain

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E2.1 | Define Room KMP entities for firms, parties, items, vouchers, lines, stock, payments | E0.1 | ULIDs, timestamps, soft delete |
| E2.2 | Add DAOs and transaction boundaries | E2.1 | Single-writer dispatcher |
| E2.3 | Add schema export and migration test harness | E2.1 | Empty-to-current and version-to-version |
| E2.4 | Implement repository interfaces and fakes | E2.2 | Domain does not depend on Room |
| E2.5 | Implement voucher posting, numbering, and cancellation | E2.2, E1.2 | One transaction per operation |
| E2.6 | Implement stock movement derivation | E2.5 | Purchase, sale, return, adjustment |
| E2.7 | Implement payment allocation and party ledger | E2.5, E1.1 | No overallocation; control accounts and charge-inclusive journals; payment restore covered by E2.8 |
| E2.8 | Implement audit events and recycle bin | E2.5 | Append-only triggers; draft soft-delete/restore/purge |
| E2.9 | Add deterministic seed data generator | E2.4 | 1k/50k/500k fixtures |

Exit: domain tests cover posting, cancellation, stock, allocations, and audit behavior.

### Epic E3 - Search

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E3.1 | Add FTS5 tables and synchronization | E2.1 | Transactional updates |
| E3.2 | Implement normalization for phone, GSTIN, invoice number, text | E3.1 | Unit tests |
| E3.3 | Implement query parser and ranking | E3.1 | Exact, prefix, token, recency |
| E3.4 | Implement highlights and result models | E3.3 | Shared across platforms |
| E3.5 | Build search benchmarks and query-plan checks | E3.3 | 1k/50k/500k fixtures |
| E3.6 | Implement invoice, party, and item search use cases | E3.3, E2.4 | Scope and filters |
| E3.7 | Implement HSN/SAC offline dataset and lookup | E3.1 | Versioned dataset |
| E3.8 | Implement global search aggregation | E3.6 | Grouped results |

Exit: search works offline, meets latency budgets, and passes consistency tests.

### Epic E4 - Design system and app shell

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E4.1 | Implement color, typography, spacing, and shape tokens | E0.6 | Light and dark |
| E4.2 | Implement buttons, fields, chips, dialogs, bottom sheets, and tables | E4.1 | Compose Multiplatform |
| E4.3 | Implement adaptive scaffold for phone, tablet, and desktop | E4.2 | Compact, medium, expanded |
| E4.4 | Implement navigation contracts and deep-link routes | E4.3 | Shared route model |
| E4.5 | Implement empty, loading, error, and permission states | E4.2 | Reusable components |
| E4.6 | Implement accessibility defaults and keyboard focus order | E4.3 | Definition of done |
| E4.7 | Implement Android app shell and permissions flow | E4.3 | Lazy permission requests |
| E4.8 | Implement desktop app shell, menu bar, and shortcuts | E4.3 | Ctrl/Cmd+K, N, S, P |

Exit: both shells run with navigation and a shared design system.

### Epic E5 - Master data screens

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E5.1 | Firm onboarding and settings | E4.7, E2.4 | Local-only path |
| E5.2 | Party list, editor, ledger, and statement | E5.1, E3.6 | Search and filters |
| E5.3 | Item list, editor, categories, units, HSN | E5.1, E3.7 | Item images after 1.0 |
| E5.4 | CSV import for parties and items | E5.2, E5.3 | Dry-run validation |
| E5.5 | Contact picker import on Android | E5.2 | One contact at a time; after 1.0 |
| E5.6 | Bulk actions and recycle bin | E5.2, E5.3, E2.8 | Restore and purge |
| E5.7 | Desktop data tables with sorting and resizing | E5.2, E5.3 | Desktop only |

Exit: a user can create a firm, add parties and items, and search them on all platforms.

### Epic E6 - Invoice editor and output

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E6.1 | Invoice draft state and validation | E2.5, E4.4 | Autosave drafts |
| E6.2 | Party and item pickers with search | E3.6, E4.2 | Keyboard and touch |
| E6.3 | Line editing, discounts, charges, and tax preview | E6.1, E1.2 | Instant totals |
| E6.4 | Invoice numbering and posting | E2.5, E6.3 | Assign number at posting |
| E6.5 | Quotation, proforma, credit note, debit note | E6.4 | Shared voucher model |
| E6.6 | PDF document model and A4/A5 renderers | E6.3 | Deterministic output |
| E6.7 | Android PDF/image and share integration | E6.6 | Share sheet |
| E6.8 | Desktop PDF/image and save dialog | E6.6 | Native file chooser |
| E6.9 | Thermal 58/80 mm layout | E6.6 | Test on real printers |
| E6.10 | Invoice list, filters, and search | E3.6, E6.4 | Status and type filters |
| E6.11 | Duplicate, cancel, and restore | E6.4, E2.8 | Audit trail |
| E6.12 | Print preview | E6.8, E6.9 | Desktop first; batch print after 1.0 |
| E6.13 | Attachment storage and picker for party and voucher files | E5.2, E6.4 | Images/PDF; content-addressed under the data directory; included in backup; expense receipt attachments after 1.0 |

Exit: an invoice can be created, posted, printed, shared, cancelled, and restored offline
on Android and desktop.

### Epic E7 - Inventory

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E7.1 | Stock ledger and summary screens | E2.6 | Drill-down to source |
| E7.2 | Godowns and transfers | E2.6 | Multi-location |
| E7.3 | Batch and expiry | E2.6 | Validation at posting |
| E7.4 | Serial/IMEI tracking | E2.6 | Selection UI |
| E7.5 | Stock adjustment with reasons | E2.6 | Audit event |
| E7.6 | Low-stock thresholds and alerts | E7.1 | Local notifications |
| E7.7 | Barcode generation and labels | E5.3 | ZXing; A4 and thermal |
| E7.8 | Purchase bills and returns | E6.4, E2.6 | Stock inward |
| E7.9 | Stock valuation | E7.1 | Weighted average first |
| E7.10 | Negative-stock policy | E7.1 | Per-firm setting |

Exit: stock is explainable, batch/serial rules hold, and labels scan correctly.

### Epic E8 - Payments and ledgers

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E8.1 | Payment-in and payment-out editors | E2.7, E6.4 | Cash, bank, UPI, cheque |
| E8.2 | Allocation UI across invoices | E8.1 | Prevent overallocation |
| E8.3 | Party ledger and statement export | E8.2 | PDF and CSV |
| E8.4 | Receivables and payables aging | E8.2 | Configurable buckets |
| E8.5 | Cash and bank accounts | E2.7 | Opening balances |
| E8.6 | UPI QR generation | E5.1 | Static only |
| E8.7 | Reminder templates and sharing | E8.3 | Deep links |
| E8.8 | Bank CSV import and matching | E8.5 | Manual confirmation |
| E8.9 | Reconciliation UI | E8.8 | Undo support |
| E8.10 | Expense vouchers and categories | E6.4, E2.5 | Receipt attachments after 1.0 |

Exit: party balances reconcile with the ledger, expense vouchers post correctly, and
reminders can be shared offline.

### Epic E9 - Reports and GST

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E9.1 | Report query layer and shared filters | E2.4, E3.6 | Streaming exports |
| E9.2 | Sales, purchase, and day book | E9.1 | CSV and PDF |
| E9.3 | Stock summary and item profit | E9.1, E7.1 | Cost basis documented |
| E9.4 | GST summary and HSN summary | E9.1, E1.2 | Reconcile with invoices |
| E9.5 | GSTR-1 export | E9.4 | Schema validation |
| E9.6 | Tally XML export | E9.2 | Mapping document |
| E9.7 | Financial-year close | E2.5 | Reversible until first posting |
| E9.8 | Report print layouts | E9.2 | A4 first |
| E9.9 | Expense report and category breakdown | E9.1, E8.10 | Reconciles to expense vouchers |

Exit: month-end reports, including the expense report, reconcile and GSTR-1 export validates.

### Epic E10 - Backup, settings, and security

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E10.1 | Backup archive writer and reader | E2.2 | Manifest and checksums |
| E10.2 | Restore flow with validation and safety copy | E10.1 | Atomic replace |
| E10.3 | Scheduled local backups | E10.1 | WorkManager and desktop scheduler |
| E10.4 | Secure storage | E4.7, E4.8 | Keystore, DPAPI, libsecret; app lock after 1.0 |
| E10.5 | Audit log viewer and export | E2.8 | Redacted diagnostics |
| E10.6 | Data deletion flows | E2.8 | Export first |
| E10.7 | Settings screens | E5.1 | Firm, tax, stock, print, backup |
| E10.8 | Diagnostics export | E10.5 | No sensitive content; after 1.0 |

Exit: backup on one platform restores on another; secure storage works; deletion is safe.

### Epic E11 - Desktop delivery

| ID | Task | Depends on | Notes |
| --- | --- | --- | --- |
| E11.1 | Desktop navigation, menus, and command palette | E4.8, E3.8 | Shell/data milestone; no installer packaging |
| E11.2 | Desktop invoice editor and data tables | E6.1, E5.7 | Larger layouts; installer packaging starts at E11.6 |
| E11.3 | File pickers and export locations | E11.1 | Native dialogs |
| E11.4 | PDF generation and printing | E6.8 | CUPS and Windows spooler |
| E11.5 | Portable mode and data directory rules | E11.1 | Documented paths |
| E11.6 | MSI packaging | E11.2 | Windows runner, WiX |
| E11.7 | DEB and AppImage packaging | E11.2 | Ubuntu runner |
| E11.8 | Clean-VM smoke tests | E11.6, E11.7 | Windows 11 and Ubuntu LTS |
| E11.9 | Signed release artifacts and checksums | E11.6, E11.7 | GitHub Releases |

Exit: installable Windows and Linux builds complete the core billing workflow.

E11.1-E11.3 run in parallel at sprints 7-8. Packaging, signing, and clean-VM work
(E11.6-E11.9) are release-phase activities at sprint 14. D1 is a shell/data milestone;
D4 is the first installable-package milestone.

## 4. Sprint sequence

Two-week sprints, adjustable after the foundation.

| Sprint | Focus | Demo |
| --- | --- | --- |
| 0 | E0 scaffold, CI, design tokens | Android and desktop windows open |
| 1 | E1 money and tax, E2 schema | Golden invoice totals in tests |
| 2 | E2 posting and stock, E3 FTS | Post an invoice and search it |
| 3 | E4 design system and shells | Navigation on Android and desktop |
| 4 | E5 parties and items | Create and search master data |
| 5 | E6 invoice draft and editor | Create a draft invoice |
| 6 | E6 posting, PDF, share | Issue and share an invoice |
| 7 | E6 list, filters, cancel, restore; E11.1 desktop shell (parallel) | Full invoice lifecycle; desktop shell with navigation |
| 8 | E7 inventory core; E11.2 desktop editor and E11.3 file pickers (parallel) | Stock ledger and adjustments; desktop invoice editing and native dialogs |
| 9 | E7 batch, serial, labels; E11.4-E11.5 desktop printing and portable mode (parallel) | Batch/serial invoice and label print; desktop print and portable data |
| 10 | E8 payments, ledger, and expense vouchers | Record payment and expense, and view ledger |
| 11 | E8 aging, reminders, reconciliation | Receivables workflow |
| 12 | E9 reports and GST | Month-end reports, expense report, and GSTR-1 |
| 13 | E10 backup and security | Cross-platform restore |
| 14 | E11.6-E11.9 desktop packaging, signing, and clean-VM tests | Signed MSI, DEB/AppImage, and checksums |
| 15 | Hardening, accessibility, performance | Release candidate |

This sequence is a guide. The critical path is E1 -> E2 -> E6 -> E9. Search and design can
run in parallel once E2 lands. The desktop shell starts after E6 core screens; E11.1-E11.3
run in parallel during sprints 7-8. Installer packaging and signing are release-phase
work at sprint 14, not part of D1; the D1 package smoke build remains allowed.

## 5. First pull requests

1. Gradle KMP scaffold with empty Android and desktop apps.
2. CI workflow for Ubuntu and Windows.
3. `Money` and rounding primitives with tests.
4. Room entity definitions and schema export.
5. Tax engine line calculation with golden tests.
6. Invoice posting transaction with stock movements.
7. FTS5 table and invoice search.
8. Design tokens and button/text field components.
9. Android shell with navigation.
10. Desktop shell with a window and menu bar.

Keep each pull request small enough to review in under an hour.

## 6. Definition of ready

A ticket is ready when:

- The user-visible behavior is described.
- The data model impact is known.
- Offline and error behavior are defined.
- Android and desktop differences are noted.
- Acceptance criteria are testable.
- Required design assets are original or licensed.

## 7. Definition of done

- Code reviewed by at least one other engineer.
- Unit tests for domain logic.
- Migration test if the schema changed.
- UI states implemented.
- Accessibility check completed.
- Strings externalized.
- Android and desktop builds pass in CI.
- Documentation updated.
- No new lint or vulnerability warnings.

## 8. Quality gates

| Gate | Requirement |
| --- | --- |
| Tax gate | Golden tests pass; accountant review for rate changes |
| Data gate | Migration and backup/restore tests pass |
| Search gate | Latency and query-plan budgets pass |
| UI gate | Empty, loading, error, and accessibility states present |
| Platform gate | Android and desktop builds install and launch |
| Security gate | No secrets, no cleartext, no unnecessary permissions |
| Release gate | Signed artifacts, checksums, release notes, rollback plan |

## 9. Cut list if time is short

Cut in this order:

1. Custom theme editor.
2. Loyalty and party-wise pricing.
3. Online catalogue.
4. Desktop multi-window.
5. Automated bank feeds.
6. AI OCR and assisted data entry.
7. Desktop batch printing.
8. Additional invoice theme packs.

Do not cut any P0 item from the scope of record. That includes Tally export, batch and serial
tracking, multiple godowns, low-stock alerts, barcode labels, UPI QR, bank CSV import and
reconciliation, and A5 output.

Do not cut:

- Tax correctness.
- Invoice PDF quality.
- Backup and restore.
- Search.
- Audit trail.
- Payment allocation integrity.

## 10. Staffing and parallelization

- Engineer A: tax, posting, inventory, reports.
- Engineer B: Compose UI, invoice editor, Android shell.
- Engineer C: desktop shell, packaging, printing, platform services.
- Engineer D or part-time: test automation, fixtures, accessibility, documentation.

Work that can run in parallel:

- Design system and search once the schema stabilizes.
- Desktop packaging during the release phase, after the shell and core screens exist.
- Documentation and fixtures throughout.

Work that should not be parallelized:

- Voucher posting and stock movement design.
- Tax rounding decisions.
- Backup format and restore semantics.

These need one owner and explicit review.
