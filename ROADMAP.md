# OpenBill Roadmap

This roadmap covers the Android app and the Windows/Linux desktop apps. Dates are
deliberately omitted until the scaffold is running; scope and exit criteria are more
useful than optimistic estimates.

## Product principles

1. **Offline first.** Every core workflow must work with no network. Sync is an optional
   enhancement, never a dependency for billing.
2. **The ledger is the source of truth.** Stock and balances are derived from posted
   vouchers. Edits create audit events; they do not silently rewrite history.
3. **Correctness beats parity.** A smaller feature set with correct GST, rounding, and
   stock behavior is better than a broad feature list that produces wrong books.
4. **Data portability.** Backup, export, and restore are available in every edition, not
   locked behind a paywall.
5. **One core, three shells.** Android, Windows, and Linux share the domain, database,
   tax, search, reporting, and most UI. Platform code stays at the edges.
6. **No dark patterns.** No ads, no contact scraping, no mandatory account, no artificial
   device limits, no telemetry without explicit consent.

## Release scope of record

MVP means required for 1.0. Version 1.0 is Phases 1 through 6 of this roadmap; every feature
listed in those phases is P0 and required for 1.0. P1 work follows 1.0, and P2 work is later
or optional. Phases 7 and 8 are post-1.0. README.md, docs/01-feature-inventory.md, and
docs/04-implementation-plan.md defer to this section.

## Phase 0 - Research and specification (complete)

Deliverables:

- Public repository with Apache-2.0 license.
- Clean-room policy and trademark notice.
- Reverse-engineering report covering the target app's architecture, modules, permissions,
  SDKs, and API surface.
- Feature inventory from public product pages, help center articles, store listing, and
  static analysis.
- Search analysis with implementation evidence.
- Cross-platform architecture and desktop strategy.
- Test plan and implementation plan.

Exit criteria:

- Documents reviewed for legal and technical accuracy.
- No proprietary code, assets, or APK artifacts committed.
- Publication gate: no private endpoints, secrets, tokens, or unnecessary decompiled
  identifiers in public docs; external claims are dated and sourced; artifact metadata is
  reproducible.
- Roadmap accepted.

## Phase 1 - Foundation (Android + desktop)

Goals: one Gradle build that produces an Android app and a desktop JVM app, with a shared
database and a tested tax engine.

Workstreams:

- Kotlin Multiplatform scaffold with `androidTarget` and `jvm("desktop")`.
- Compose Multiplatform design system: color tokens, typography, spacing, components,
  dark mode, and large-text support.
- Room KMP database with bundled SQLite, schema export, migration tests, and FTS5 search
  tables.
- Core entities: firm, party, item, unit, godown, voucher, voucher line, payment
  allocation, stock movement, audit event.
- GST tax engine: inclusive/exclusive pricing, intra-state/inter-state split, cess,
  discounts, additional charges, round-off, and invariant tests.
- Money type based on integer paise; no floating-point arithmetic in the ledger.
- Repository and use-case layers with coroutine-friendly interfaces.
- Koin dependency injection shared across targets.
- Navigation and app shell for Android and desktop.
- Backup archive format (zip with manifest, SQLite database, attachments) and restore.
- CI matrix: lint, unit tests, Android assemble, desktop package smoke build.
- Contribution guide, issue templates, and pull-request checklist.

Exit criteria:

- `./gradlew :app-android:assembleDebug` succeeds.
- `./gradlew :app-desktop:run` opens a window on Linux.
- Tax engine passes a table-driven test suite covering Indian GST scenarios.
- Database migration test passes from an empty schema to the current version.
- Backup created on Android restores on desktop and vice versa.
- CI is green on Ubuntu and Windows runners.

## Phase 2 - Billing alpha (Android first)

Goals: a business can issue a correct GST invoice and share a PDF.

Scope:

- Onboarding: firm creation, GSTIN validation, state selection, financial year, logo and
  signature import.
- Parties: create, edit, search, opening balance, billing and shipping addresses.
- Items: create, edit, search, categories, units, HSN/SAC, tax rate, and prices.
- Invoice editor: party selection, item lines, quantity, rate, discount, tax, additional
  charges, round-off, notes, terms, and due date.
- Invoice numbering with prefix, suffix, and financial-year reset.
- Quotation and proforma invoice.
- Credit note and debit note.
- PDF themes: at least three built-in A4 themes, an A5 layout, and one thermal layout.
- Android share sheet and desktop save dialog for PDF and image output.
- Invoice list with search, type filters, status filters, and date filters.
- Global search entry point and command palette.
- Audit trail for create, edit, cancel, and restore.
- Sample data generator for development and screenshots.

Exit criteria:

- Invoice totals match a manually calculated reference for 30+ scenarios.
- PDF output is legible at A4, A5, and 58 mm/80 mm thermal widths.
- Search returns correct results for invoice number, party name, and item name.
- Airplane-mode end-to-end invoice creation and PDF sharing works.
- No ANR or jank in the invoice editor on a low-end device profile.

## Phase 3 - Desktop alpha (Windows and Linux)

Goals: the same billing workflows on a larger screen with keyboard-first interaction, ahead
of inventory.

Scope:

- Desktop app shell: menu bar, sidebar, command palette, keyboard shortcuts, and window
  state persistence.
- Responsive invoice editor and data tables sized for desktop.
- File pickers for logo, signature, attachments, backup, and export.
- PDF generation and printing through the platform print dialog.
- CSV import for parties and items.
- Crash-safe local database with a documented data directory.
- Optional portable mode: keep the database next to the executable on a USB drive.
- Packaging pipeline scaffolding: CI compile and `jpackage` smoke builds, not release
  installers.

Exit criteria:

- A desktop development build can create a firm, issue an invoice, print it, and restore a
  backup on Windows and Linux.
- Keyboard-only invoice creation is possible.
- Desktop and Android backups are interchangeable.
- No absolute developer paths or machine-specific files in development artifacts.

Installable MSI, DEB, and AppImage packages, signing, and clean-VM smoke tests are completed
in Phase 6 as release work.

## Phase 4 - Inventory beta

Goals: stock is explainable and survives real-world messiness.

Scope:

- Stock ledger and stock summary.
- Multiple godowns and transfers.
- Batch and expiry tracking.
- Serial number / IMEI tracking.
- Stock adjustment with reason codes.
- Low-stock thresholds and alerts.
- Purchase bills and purchase returns.
- Barcode generation and label printing (A4 sheets and thermal labels).
- Item-wise valuation using a documented costing method.
- Negative-stock policy per firm.

Exit criteria:

- Every stock quantity can be traced to posted vouchers and adjustments.
- Batch and serial constraints are enforced at posting time.
- Closing stock matches the ledger for a randomized 10,000-voucher test.
- Barcode labels scan correctly on common 1D and QR scanners.

## Phase 5 - Money beta

Goals: receivables and payables are accurate and actionable.

Scope:

- Payment-in and payment-out vouchers.
- Partial payments and allocation across invoices.
- Advance payments and adjustments.
- Party ledger and statement export.
- Receivables and payables aging.
- Cash and multiple bank accounts.
- Static UPI QR generation on invoices.
- Payment reminders as shareable text/PDF; no proprietary messaging API required.
- Manual bank reconciliation.
- CSV bank statement import with rule-based matching.
- Expense vouchers with categories.

Exit criteria:

- Allocations never exceed invoice or payment amounts.
- Party closing balance equals opening balance plus posted movements.
- Aging buckets reconcile with the ledger.
- Reconciliation can be undone without corrupting the ledger.

## Phase 6 - Reports, GST, and release candidate

Goals: a business can close a month and hand data to its accountant.

Scope:

- Sales register, purchase register, day book, and cash/bank book.
- Party statement, outstanding report, and aging report.
- Stock summary, item-wise profit, and expense report.
- GST summary, HSN summary, and GSTR-1 CSV export.
- Financial-year close and opening balances.
- Tally-compatible XML export.
- Scheduled local backups and manual restore.
- Report filters, saved views, and export to CSV/PDF.
- Print templates for common report layouts.
- Release hardening: performance budgets, security review, accessibility audit, and release
  documentation.
- Desktop packaging, signing, and clean-VM smoke tests for Windows 11 and Ubuntu LTS.
- Release engineering: signed Android artifacts, signed Windows installer, Linux packages,
  checksums, and release notes.
- Manual, user-initiated update check from Help; no automatic update checks in 1.0.

Exit criteria:

- GSTR-1 export validates against the published schema for supported document types.
- Report totals reconcile with the ledger and invoice totals.
- Financial-year close is reversible until the first voucher of the new year is posted.
- A full month-end workflow succeeds offline on Android and desktop.
- Signed Android, Windows, and Linux artifacts pass clean-install smoke tests.
- 1.0 release checklist is complete, with no release-blocking or high-severity defects open.
- Backup and restore have been tested on real devices and clean VMs.

## Phase 7 - Post-1.0 growth and parity

Goals: features that make the product competitive without compromising the core.

Scope:

- Recurring invoices and automated reminders.
- Consolidated reporting across multiple firms.
- Staff users and role-based permissions with a local audit log.
- Online catalogue export as static HTML/PDF; optional hosted store later.
- Loyalty points and party-wise pricing.
- POS billing for counter sales with barcode scanning.
- Multi-currency groundwork.
- e-invoice and e-way bill integration using the user's own credentials, behind a
  provider interface.
- WhatsApp/SMS deep-link sharing and bring-your-own provider integration.
- Optional sync server for multi-device use; conflict resolution designed before coding.
- AI-assisted data entry as an optional, opt-in module.

Exit criteria:

- Each feature has a documented data model, permission model, and offline behavior.
- Sync, if shipped, passes conflict and offline-divergence test suites.
- No feature requires a third-party account to use the core product.

## Phase 8 - Post-1.0 hardening and scale

Goals: keep the product healthy as data grows and as the platform matrix expands.

Scope:

- Performance work beyond the 1.0 budgets: large databases, long report runs, and many
  attachments.
- Security review follow-up: backup encryption, hardening of secure storage and exported
  file permissions, and dependency audit.
- Accessibility audit remediation: TalkBack, keyboard navigation, contrast, and scalable
  text.
- Localization for Hindi and other Indian languages.
- Documentation updates: user guide, administrator guide, backup/restore guide, and
  migration guide.
- Maintenance release engineering: signed Android artifacts, signed Windows installer,
  Linux packages, checksums, and release notes.
- Incident response and data-loss recovery runbook.

Exit criteria:

- Each hardening item has an owner, milestone, and test coverage.
- No regression against the 1.0 release criteria.
- The next release checklist is complete.

## Feature matrix

Status legend: `MVP` = required for 1.0; `Next` = after 1.0; `Later` = deliberately
deferred; `No` = out of scope.

| Capability | Observed in the market | OpenBill | Target |
| --- | --- | --- | --- |
| GST and non-GST invoicing | Yes | Yes | MVP |
| Quotations and proforma | Yes | Yes | MVP |
| Credit and debit notes | Yes | Yes | MVP |
| Purchase bills and returns | Yes | Yes | MVP |
| Invoice numbering and FY reset | Yes | Yes | MVP |
| A4/A5/thermal PDF | Yes | Yes | MVP |
| Multiple invoice themes | Yes | 3 built-in, theme API later | MVP |
| Custom logo, signature, terms | Yes | Yes | MVP |
| Party master and ledger | Yes | Yes | MVP |
| Item master with HSN/SAC | Yes | Yes | MVP |
| Multiple units and prices | Yes | Yes | MVP |
| Stock ledger | Yes | Yes | MVP |
| Multiple godowns | Yes | Yes | MVP |
| Batch and expiry | Yes | Yes | MVP |
| Serial / IMEI | Yes | Yes | MVP |
| Low-stock alerts | Yes | Yes | MVP |
| Barcode labels | Yes | Yes | MVP |
| Payments in/out and allocation | Yes | Yes | MVP |
| Receivables and payables | Yes | Yes | MVP |
| Bank accounts and reconciliation | Yes | Manual + CSV; rules later | MVP |
| UPI QR on invoice | Yes | Static QR | MVP |
| 20+ reports | Yes | 12 core reports, expand later | MVP |
| Item-wise profit and expense reports | Yes | Yes | MVP |
| GST summary and GSTR-1 export | Yes | Yes | MVP |
| Tally export | Yes | XML | MVP |
| Backup and restore | Yes | Open archive format | MVP |
| Invoice search | Yes | Yes | MVP |
| Item search with filters | Yes | Yes | MVP |
| Party search | Yes | Yes | MVP |
| Offline HSN/SAC lookup | Yes | Yes | MVP |
| Global search / command palette | Partial | Yes | MVP |
| Windows desktop app | Exists; native-versus-wrapper implementation not assessed | Native Compose app | MVP |
| Linux desktop app | Not assessed | Native Compose app | MVP |
| Multi-firm | Yes | Yes | MVP |
| Staff users and roles | Yes | Post-1.0 local roles | Next |
| Recurring invoices | Yes | Yes | Next |
| Loyalty and party pricing | Yes | Yes | Next |
| Online storefront | Yes | Static catalogue first | Next |
| WhatsApp/SMS campaigns | Yes | Deep links / BYO provider | Next |
| e-invoice and e-way bill | Yes | BYO credentials | Next |
| Cloud sync | Yes | Optional self-hosted | Next |
| AI OCR / automated bank feeds | Yes | Optional, opt-in | Later |
| Lending or payment processing | Some | No | No |
| Advertising/attribution SDKs | Observed | No | No |
| Data brokerage | Not assessed | No | No |

SDK presence and permissions do not establish data sale or brokerage. The advertising row
records observed SDKs only; the data-brokerage row is not assessed.

## Desktop milestones

| Milestone | Deliverable | Depends on |
| --- | --- | --- |
| D0 | Shared core compiles and tests on JVM | Phase 1 |
| D1 | Desktop shell with navigation, database, and party/item lists | D0 |
| D2 | Desktop invoice editor and PDF output | D1, Phase 2 |
| D3 | Printing, file pickers, backup/restore | D2 |
| D4 | Windows MSI and Linux DEB/AppImage in CI | D3 |
| D5 | Clean-VM smoke tests and signed release artifacts | D4, Phase 6 |

D1 is a shell and data milestone, not an installer milestone. D4 produces installable
packages, and D5 is the 1.0 desktop release gate. The desktop shell starts after the E6 core
screens (see docs/04); packaging, signing, and clean-VM validation are release-phase work.
Version 1.0 ships with no automatic update checks; the Help check is manual and
user-initiated.

Desktop-specific requirements:

- Keyboard-first workflow, including Ctrl/Cmd+K search, Ctrl/Cmd+N new invoice,
  Ctrl/Cmd+S save, Ctrl/Cmd+P print, and Escape to dismiss dialogs.
- Data tables with column resizing, sorting, and bulk selection.
- Multi-window is not required for 1.0; a preview pane for invoice PDFs is enough.
- Portable mode for accountants who carry data on removable media.
- High-DPI rendering and Linux fractional scaling.
- A documented data directory per operating system.
- No automatic update checks in 1.0. A manual, user-initiated check from Help is allowed;
  automatic checks come later. Publish signed installers and checksums on GitHub Releases.

## Search workstream

Search spans phases because it depends on the data model:

1. Phase 1: schema for FTS5 tables, query normalization, ranking, and snippets.
2. Phase 2: invoice, party, and item search; command palette on desktop; search entry
   point on Android.
3. Phase 4: stock-state filters, barcode lookup, and batch/serial search.
4. Phase 5: payment and expense search; saved filters.
5. Phase 6: report search and export-aware filters.

Acceptance targets:

- p95 query latency under 100 ms for a 50,000-invoice database on a mid-range phone.
- No query executes for an empty search term.
- Latest query wins; stale results are cancelled.
- Results highlight matched fields and explain why they matched.
- Search works offline for all local data.
- HSN/SAC lookup works without a network after the first dataset install.

## Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| GST rules change | Wrong tax output | Versioned tax rules, table-driven tests, release checklist |
| Scope creep from parity lists | Never ships | MVP matrix, explicit non-goals, phase gates |
| Cross-platform UI differences | Inconsistent UX | Shared screens, platform adapters, desktop-specific test pass |
| Room KMP or Compose Desktop limitations | Rework | Phase 1 spike; fallback to SQLDelight or platform UI where needed |
| Desktop packaging complexity | Delayed release | CI packaging scaffold early, release packaging gate, clean-VM smoke tests |
| Data loss during migration | Trust damage | Schema exports, migration tests, automatic pre-migration backup |
| Legal/trademark confusion | Project risk | Clean-room policy, NOTICE, nominative use only, no copied assets |
| Low-end device performance | Poor adoption | Performance budgets, baseline profiles, large-database tests |

## Success metrics for 1.0

- A new user can create a firm and issue a GST invoice in under five minutes.
- Invoice, stock, and ledger totals reconcile across Android, Windows, and Linux.
- Crash-free session rate above 99.5 percent in beta.
- Backup/restore succeeds on all supported platforms without data loss.
- p95 search latency under 100 ms at 50,000 invoices.
- No release-blocking or high-severity security or data-integrity defects open.
- At least three external contributors have merged documentation or test improvements.
