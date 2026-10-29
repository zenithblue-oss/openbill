# OpenBill Roadmap

This roadmap covers the Android app, the Windows/Linux desktop apps, the `openbill` CLI,
and the optional self-hosted OpenBill Server. Dates are deliberately omitted until the
scaffold is running; scope and exit criteria are more useful than optimistic estimates.

## Product principles

1. **Offline first.** Every core workflow must work with no network. Sync is an optional
   enhancement, never a dependency for billing.
2. **The ledger is the source of truth.** Stock and balances are derived from posted
   vouchers. Edits create audit events; they do not silently rewrite history.
3. **Correctness beats parity.** A smaller feature set with correct GST, rounding, and
   stock behavior is better than a broad feature list that produces wrong books.
4. **Data portability.** Backup, export, and restore are available in every edition, not
   locked behind a paywall.
5. **One core, five surfaces.** Android, Windows, and Linux share the domain, database,
   tax, search, reporting, and most UI. The CLI and the optional server reuse the same
   core on the JVM without UI. Platform code stays at the edges.
6. **No dark patterns.** No ads, no contact scraping, no mandatory account, no artificial
   device limits, no telemetry without explicit consent.

## Release scope of record

MVP means required for 1.0. Version 1.0 is Phases 1 through 6 of this roadmap; every feature
listed in those phases is P0 and required for 1.0. P1 work follows 1.0, and P2 work is later
or optional. Phases 7, 8, and 9 are post-1.0. README.md, docs/01-feature-inventory.md, and
docs/04-implementation-plan.md defer to this section.

Surfaces in 1.0: Android, Windows, Linux, and the `openbill` CLI in direct (local) mode,
limited to milestones C0-C3 ([docs/11-cli.md](docs/11-cli.md)). The OpenBill Server, sync,
and CLI remote mode are post-1.0 ([docs/10-server.md](docs/10-server.md)).

## Phase 0 - Research and specification (complete)

Deliverables:

- Public repository with Apache-2.0 license.
- Clean-room policy and trademark notice.
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
- CLI C0: `openbill` skeleton with `db info/check/migrate` and `backup
  create/verify/inspect/restore`, plus the golden-output test harness. Used by CI for the
  cross-platform backup check.
- Sync readiness: sync metadata fields and number-series-scoped document numbering in the
  schema (docs/03 section 8), so post-1.0 sync needs no migration of posted documents.

Exit criteria:

- `./gradlew :androidApp:assembleDebug` succeeds.
- `./gradlew :desktopApp:run` opens a window on Linux.
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
- CLI C1: firm, party, item, and voucher create/post/cancel/restore/print commands,
  `search`, `--json` output, and stable exit codes.

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
- Silent print to the default (or a chosen) printer as an opt-in setting beside the dialog.
- Bulk print: a sequential loop over silent print with progress, cancellation, and
  per-invoice failures.
- Bulk PDF/ZIP export of selected invoices or a date range, generated locally.
- Export notification with a show-in-folder action after exports and backups.
- CSV import for parties and items.
- Crash-safe local database with a documented data directory.
- Single-instance lock: a second launch focuses the existing window and protects the
  single-writer database.
- Optional portable mode: keep the database next to the executable on a USB drive.
- Packaging pipeline scaffolding: CI compile and `jpackage` smoke builds, not release
  installers.

Exit criteria:

- A desktop development build can create a firm, issue an invoice, print it (dialog and
  silent), bulk-print and bulk-export a selection, and restore a backup on Windows and Linux.
- After an export, the notification reveals the file in its folder.
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
- Barcode scan to add an invoice line (camera on Android, HID scanner on desktop), with a
  clear message for unknown codes.
- Item-wise valuation using a documented costing method.
- Negative-stock policy per firm.
- CLI C2 (with Phase 5): `stock` commands and `import csv --dry-run`; the randomized
  10,000-voucher test is driven through the CLI.

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
- CLI C2 (continued): `payment` commands and allocation, `export csv`.

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
- CLI C3: `report`, `gst gstr1/hsn`, `tally export`, and `audit` commands; signed CLI
  archives for Linux and Windows. `gst gstr3b` follows the P1 GSTR-3B working report.

Exit criteria:

- GSTR-1 export validates against the published schema for supported document types.
- Report totals reconcile with the ledger and invoice totals.
- Financial-year close is reversible until the first voucher of the new year is posted.
- A full month-end workflow succeeds offline on Android and desktop.
- Signed Android, Windows, and Linux artifacts pass clean-install smoke tests.
- 1.0 release checklist is complete, with no release-blocking or high-severity defects open.
- Backup and restore have been tested on real devices and clean VMs.
- CLI golden-output tests pass on Ubuntu and Windows; `--json` fields and exit codes are
  frozen as a public contract.

## Phase 7 - Post-1.0 growth and parity

Goals: features that make the product competitive without compromising the core.

Scope:

- Recurring invoices and automated reminders.
- Consolidated reporting across multiple firms.
- Staff users and role-based permissions with a local audit log.
- Online catalogue export as static HTML/PDF; optional hosted store later.
- Loyalty points and party-wise pricing.
- POS billing for counter sales with barcode scanning, held bills, and multi-tender payment.
- Other P1 items from docs/01 not tied to a feature above: purchase orders, delivery
  challans, sales orders, reverse charge, and GSTR-3B and GSTR-2 working reports. Each is
  scheduled into a milestone before work starts; none is required for 1.0. (Silent and bulk
  print, bulk PDF/ZIP export of invoices, and export notifications are P0 and ship in
  Phase 3.) Android bulk PDF/ZIP export of invoices follows here.
- Every other P1 row in docs/01 not named above (for example the modules screen, unlimited
  recurring schedules, party categories, bulk item edit, CA report pack, and custom item
  fields) is scheduled in this phase; the parity tracker below gives the group-level map.
- Multi-currency groundwork.
- e-invoice and e-way bill integration using the user's own credentials, behind a
  provider interface.
- WhatsApp/SMS deep-link sharing and bring-your-own provider integration.
- Optional self-hosted OpenBill Server, milestones S0-S3 (docs/10): sync protocol and
  simulation suite, single-user multi-device sync with QR pairing, multi-user roles with a
  CA seat, and REST API v1. Conflict rules are fixed in docs/10 before coding.
- CLI C4: remote mode against the server and `server` admin commands.
- AI-assisted data entry as an optional, opt-in module.

Exit criteria:

- Each feature has a documented data model, permission model, and offline behavior.
- Sync, if shipped, passes conflict and offline-divergence test suites.
- Every app workflow still works with no server; unpairing leaves a complete local
  database.
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
- Server S4-S6: PostgreSQL storage, end-to-end encrypted workspaces, integration plugins
  with bring-your-own credentials, and a thin read-only web client.
- CLI C5: native-image binaries if the spike passes.

Exit criteria:

- Each hardening item has an owner, milestone, and test coverage.
- No regression against the 1.0 release criteria.
- The next release checklist is complete.

## Phase 9 - Full parity and integrations

Goals: close every remaining gap against the competitor's paid tiers, free for everyone.
All integrations are opt-in, use the user's own provider or credentials (or a local model),
run through the server's plugin interface or a device-local adapter, and never block the
offline core. Every P2 row in docs/01 belongs to one of these workstreams.

Workstreams (each is sized and sequenced after 1.0 feedback; order is not fixed):

1. **POS and retail hardware:** cash drawer kick, weighing-scale input, label-printer
   sheets, customer-facing display, barcode-scanner profiles, A3 and large-format output.
2. **Restaurant:** table-wise open orders, kitchen slips, split bills, optional module.
3. **Staff, payroll, and attendance:** attendance with weekly off, shifts, and leaves;
   payroll with overtime, advances, and payslips; opt-in geo check-in; staff mode in the
   Android app and the thin web client.
4. **CRM and loyalty:** loyalty points, CRM notes and tags, service reminders, birthday and
   appointment reminders, smart greeting templates, shared ledger links (server).
5. **Marketing through bring-your-own providers:** WhatsApp and SMS campaigns with
   templates, scheduled report email through the user's SMTP, AI payment-reminder calls
   through the user's voice provider. No vendor-hosted sending and no credit packs.
6. **Online store via the server:** catalogue share, hosted storefront with custom domain,
   UPI and COD checkout, optional card gateway through the user's own account.
7. **AI and OCR, opt-in:** purchase-bill OCR, gallery scan, assisted bank reconciliation,
   assisted data entry; local or on-device models first, optional bring-your-own API key.
8. **Compliance integrations:** e-invoice and e-way bill (including bulk generation and
   expiry alerts) through bring-your-own GSP or portal credentials, TDS and TCS, tax
   reports beyond GSTR-3B, and approval workflows.
9. **Accounting depth and community content:** balance sheet, profit and loss, other ledger
   accounts, cheque tracking, bad-debt write-off, custom report builder, industry presets,
   shared item packs, and a free community theme gallery.

Exit criteria:

- Every P1 and P2 row in docs/01 is shipped, scheduled, or explicitly moved to `No` with a
  reason that matches the exclusion list in docs/01 section 14.
- Each integration has a documented provider interface, consent flow, and offline behavior.
- No workstream adds an account, SDK, or network call that is on by default.

## Parity tracker

Maps each feature group to the competitor plan tier that gates it (tier names are generic
here), the OpenBill phase, and the surfaces: A = Android, W = Windows, L = Linux,
S = self-hosted server, C = CLI. In OpenBill every row is free.

| Feature group | Competitor plan that gates it | OpenBill phase | Surfaces | Status |
| --- | --- | --- | --- | --- |
| Core invoicing, parties, items, payments, reports, GSTR-1, backup | Free tier (limited) and paid tiers | Phases 1-6 (1.0) | A W L C | Done (split tender, voucher attachments, daily backups, HSN/SAC search, business switcher; app lock with PIN backoff and idle relock, encrypted backups, business erasure with files; shipping addresses with ship-to GSTIN, party notes and attachments; low-stock report) |
| Multi-godown, batch and serial, barcode labels, low-stock alerts | Paid tiers | Phases 4 and 6 (1.0) | A W L C | Done (FEFO batch picker on sale lines; alternate units with conversion; batch or serial per item, not both) |
| Windows and Linux desktop app | Top paid tier | Phase 3 (1.0) | W L | Alpha builds; packaging and signing pending |
| Silent print, bulk print, bulk PDF/ZIP export, export notification | Top paid tier (desktop) | Phase 3 (1.0) | W L | Done (silent print setting, tray notification, portable mode, Ctrl+K palette; ESC/POS thermal printing on all surfaces) |
| Purchase orders, sales orders, delivery challans, reverse charge | Paid tiers | Phase 7 | A W L C | Delivery challans done; rest planned |
| Recurring invoices (unlimited) and automated reminders | Paid tiers with schedule limits | Phase 7 | A W L | Editable reminder message done; scheduling planned |
| Staff users, roles, CA seat, activity log | Paid tiers | Phase 7 | A W L S C | Planned |
| Multi-currency and export billing | Paid tiers | Phase 7 | A W L | Planned |
| GSTR-3B and GSTR-2 working reports, CA report pack | Paid tiers | Phase 7 | A W L C | GSTR-3B summary done; rest planned |
| POS counter workflow (held bills, multi-tender, keyboard) | Top paid tier | Phase 7 | A W L | Split tender on payments done; held bills planned |
| Sync, self-hosted server, REST API, CLI remote mode | Vendor cloud (paid tiers) | Phase 7 (S0-S3, C4) | A W L S C | Planned |
| E-invoice and e-way bill, bulk generation | Paid tiers | Phase 7 (provider interface), Phase 9 (bulk, alerts) | A W L S C | Planned |
| POS and retail hardware (cash drawer, scale, label sheets) | Top paid tier | Phase 9 | W L A | Planned |
| Restaurant tables and kitchen slips | Add-on module | Phase 9 | A W L | Planned |
| Staff attendance, payroll, geo check-in, staff mode | Separate paid module | Phase 9 | A W L S | Planned |
| CRM, loyalty, service reminders, greetings, shared ledger | Paid tiers | Phase 9 | A W L S | Planned |
| WhatsApp and SMS marketing, AI reminder calls | Paid tiers plus vendor credit packs | Phase 9 (bring-your-own provider) | S A W L | Planned |
| Online store, catalogue, custom domain, UPI and COD checkout | Paid tiers | Phase 9 (server module) | S | Planned |
| AI and OCR: bill capture, gallery scan, assisted reconciliation | Paid tiers plus scan credits | Phase 9 (opt-in, local first) | A W L | Planned |
| TDS and TCS, approval workflows, suspicious-activity alerts | Top paid tier | Phase 9 | A W L S | Planned |
| Balance sheet, P&L, other ledgers, cheque tracking, report builder | Paid tiers | Phase 9 | A W L C | Planned |
| Web client | Vendor web app | Phase 8 (S6, read-only first), Phase 9 (staff mode) | S | Planned |
| Ads, attribution SDKs, referral program, paywalls, trials, credit packs, paid themes, data brokerage, lending | Vendor monetization | Not planned | None | - |

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
| Windows desktop app | Exists; an online-only Electron shell around the web app | Native offline Compose app | MVP |
| Linux desktop app | Not offered | Native Compose app | MVP |
| Multi-firm | Yes | Yes | MVP |
| Staff users and roles | Yes | Post-1.0 local roles | Next |
| Sales orders | Yes | Non-posting, convertible to invoice | Next |
| Unlimited recurring schedules | Limited by plan | No limit | Next |
| Recurring invoices | Yes | Yes | Next |
| Purchase orders and delivery challans | Yes | Yes | Next |
| POS billing | Yes (web and desktop tier) | Counter workflow with held bills | Next |
| Silent print, bulk print, bulk PDF/ZIP export, export notification | Yes (desktop tier) | Desktop W and L; silent print is an explicit opt-in | MVP |
| Staff attendance and payroll | Yes (separate module) | Optional module, Phase 9 | Later |
| Loyalty and party pricing | Yes | Party pricing Phase 7; loyalty Phase 9 | Next |
| Online storefront | Yes | Static catalogue (Phase 7), server-hosted store (Phase 9) | Next |
| WhatsApp/SMS campaigns | Yes | Deep links first (Phase 7); BYO provider campaigns (Phase 9) | Next |
| e-invoice and e-way bill | Yes | BYO credentials | Next |
| Cloud sync | Yes | Optional self-hosted (docs/10) | Next |
| Self-hosted server | Not observed; vendor-hosted | OpenBill Server, SQLite or PostgreSQL | Next |
| Public REST API | Not assessed | Versioned, OpenAPI, token-scoped | Next |
| Command-line interface | Not observed | Direct mode on local data and backups | MVP |
| CLI remote mode and server admin | Not observed | Via server API tokens | Next |
| End-to-end encrypted sync | Not assessed | Per-workspace opt-in | Later |
| Web client | Yes | Thin client served by the self-hosted server | Later |
| AI OCR / assisted data entry | Yes | Opt-in, local first, optional bring-your-own key (Phase 9) | Later |
| Automated bank feeds | Yes | Optional, user-provided aggregator or statement import (Phase 9) | Later |
| Restaurant tables and kitchen slips | Yes | Optional module (Phase 9) | Later |
| POS retail hardware | Yes | Cash drawer, scale, label sheets (Phase 9) | Later |
| TDS/TCS and approval workflows | Yes | Per-firm switch (Phase 9) | Later |
| Balance sheet and profit and loss | Yes | Phase 9 | Later |
| Lending or payment processing | Some | No | No |
| Paywalls, trials, credit packs, referral program, paid themes | Observed | No | No |
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

## CLI milestones

| Milestone | Deliverable | Depends on |
| --- | --- | --- |
| C0 | Skeleton, `db` and `backup` commands, golden-test harness | Phase 1 core and backup format |
| C1 | Masters, vouchers, search, `--json`, exit codes | Phase 2 |
| C2 | Stock, payments, CSV import/export | Phases 4-5 |
| C3 | Reports, GST, Tally, audit; signed archives (ships with 1.0) | Phase 6 |
| C4 | Remote mode and server admin commands | S3 |
| C5 | Native-image binaries | Spike result |

The CLI starts in Phase 1 because it costs little (a shell over existing use cases) and
gives CI a scriptable driver for backup, posting, and large-fixture tests. Detail:
[docs/11-cli.md](docs/11-cli.md).

## Server milestones

| Milestone | Deliverable | Depends on |
| --- | --- | --- |
| S0 | Sync protocol, `core-sync`, simulation suite | 1.0 |
| S1 | Single-user multi-device sync, QR pairing, SQLite, scheduled backups | S0 |
| S2 | Users, roles, CA seat, device management, audit chain | S1 |
| S3 | REST API v1, OpenAPI, API tokens | S2 |
| S4 | PostgreSQL, end-to-end encryption, metrics | S3 |
| S5 | Integration plugins (SMTP, e-invoice, e-way bill, messaging, backup targets) | S3 |
| S6 | Thin web client, read-only first | S3 |

The server stays post-1.0, aligned with "Sync between devices" (P2) in docs/01. Moving it
earlier would put sync risk on the 1.0 critical path before the ledger is proven, and would
undercut the offline-first position. The 1.0 work that keeps it cheap later is limited to
sync metadata, number-series-scoped numbering, and the CLI. Detail:
[docs/10-server.md](docs/10-server.md).

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
| Sync conflicts corrupt books | Trust damage | Posted vouchers insert-only, per-device number series, review tasks instead of merges, simulation suite before S1 |
| Server becomes a de facto requirement | Breaks offline-first promise | Every release gate includes a no-server workflow; unpairing leaves a full local database |
| CLI output treated as unstable | Broken user scripts | `--json` and exit codes frozen at 1.0; golden tests in CI |

## Success metrics for 1.0

- A new user can create a firm and issue a GST invoice in under five minutes.
- Invoice, stock, and ledger totals reconcile across Android, Windows, and Linux.
- Crash-free session rate above 99.5 percent in beta.
- Backup/restore succeeds on all supported platforms without data loss.
- p95 search latency under 100 ms at 50,000 invoices.
- No release-blocking or high-severity security or data-integrity defects open.
- At least three external contributors have merged documentation or test improvements.
