# OpenBill

Open-source billing, invoicing, inventory, and payments software for Indian MSMEs.

**Android, Windows, and Linux from one Kotlin codebase.**

> Status: Phase 2 data layer in progress. The Kotlin Multiplatform scaffold, Android
> and desktop shells, fixed-point money types, GST engine, GSTIN validation, versioned
> rate schedule, Room schema, DAOs, transaction runner, migration harness, repository
> ports, in-memory fakes, Room-backed repositories, and voucher posting/cancellation
> use cases are in place. Stock movements and payment allocation are next. The
> specifications remain authoritative for scope and behavior.

OpenBill is a clean-room project. It is not affiliated with, endorsed by, or derived from
the source code of myBillBook or any other commercial billing product. See
[NOTICE](NOTICE) and [docs/06-clean-room-policy.md](docs/06-clean-room-policy.md).

## Why this project exists

Indian small businesses are increasingly dependent on closed billing platforms. When a
vendor changes pricing, removes a feature, loses data, or stops supporting a device,
businesses have little recourse. OpenBill aims to provide a transparent, local-first
alternative:

- Your data stays in an open SQLite database that you can back up and move.
- Core billing and inventory work without a network connection.
- No advertising SDKs, contact harvesting, or hidden telemetry.
- Import and export are first-class features, not paid add-ons.
- The tax engine is testable and auditable.
- The same data model works on a phone, a Windows laptop, and a Linux desktop.

This is not a get-rich-quick clone. Correctness, data durability, and print quality matter
more than shipping every competitor feature in the first release.

## Planned platforms

| Platform | Form factor | Status | Packaging |
| --- | --- | --- | --- |
| Android | Phone and tablet | First target | APK, AAB |
| Windows | Desktop and laptop | Planned | MSI |
| Linux | Desktop and laptop | Planned | DEB, AppImage |
| iOS | Phone and tablet | Not planned for 1.0 | - |
| Web | Browser | Not planned; optional later | - |

Windows and Linux are first-class targets, not afterthoughts. The domain, database, tax,
search, reporting, and import/export layers are shared through Kotlin Multiplatform. The
UI is shared with Compose Multiplatform. Platform-specific code is limited to printing,
file pickers, share sheets, secure storage, notifications, and packaging.

The repository keeps the name `openbill-android` for now because the Android app is the
first deliverable. The project name is **OpenBill**.

## MVP scope

### Business and master data

- Business profile with GSTIN, state, address, logo, signature, and bank details.
- Multiple firms per installation, with a clear active-firm switch.
- Parties: customers and suppliers, billing and shipping addresses, opening balances,
  GSTIN, and contact details.
- Items: goods and services, SKU, category, unit, HSN/SAC, tax rate, MRP, sale price,
  purchase price, and wholesale price.
- Units, categories, godowns, and payment modes.

### Billing

- GST and non-GST sales invoices.
- Quotations, proforma invoices, credit notes, debit notes, and purchase bills.
- Inclusive and exclusive tax, CGST/SGST/IGST, cess, discounts, additional charges,
  and round-off.
- Configurable invoice numbering and financial year handling.
- A4, A5, and thermal (58 mm / 80 mm) PDF output.
- Share PDF or image through the Android share sheet or the desktop save dialog.
- Duplicate, cancel, and restore invoices with an audit trail.

### Inventory

- Stock ledger derived from vouchers, not silently overwritten quantities.
- Multiple godowns and stock transfer.
- Batch and expiry tracking.
- Serial number / IMEI tracking.
- Stock adjustment with a reason.
- Low-stock alerts.
- Barcode generation and label printing.

### Money

- Payment-in and payment-out vouchers.
- Partial payments and allocation against invoices.
- Expense vouchers with categories.
- Party ledger, outstanding receivables, and payables.
- Cash and multiple bank accounts.
- UPI QR on invoices (static, generated locally).
- Manual bank reconciliation and CSV statement import.

### Reports and compliance

- Sales, purchase, day book, party statement, stock summary, item-wise profit, and
  expense reports.
- GST summary, HSN summary, and GSTR-1 CSV export.
- Financial-year close with opening balances.
- Tally-compatible XML export.
- Full backup and restore in an open, documented archive format.

### Search

Search is a first-class workflow, not a filter bolted onto one screen.

- Global search (Ctrl/Cmd+K on desktop, search icon on Android) across invoices, parties,
  items, payments, and expenses.
- Invoice search by invoice number, party name, and amount.
- Item search by name, SKU, HSN, and barcode, with stock-state filters.
- Party search by name and phone number.
- Offline HSN/SAC lookup.
- Local full-text index with ranked results and highlighted matches.
- 300 ms debounce, cancellation of stale queries, and paging for large result sets.

The evidence behind these decisions is in
[docs/02-search-analysis.md](docs/02-search-analysis.md).

### Scope of record

MVP means required for 1.0. The authoritative phase-by-phase scope is in
[ROADMAP.md](ROADMAP.md); [docs/01](docs/01-feature-inventory.md) uses P0/P1/P2 priorities
that map to it. Features outside Phases 1-6 of the roadmap are after 1.0.

## Explicit non-goals for the MVP

- Reimplementing a proprietary backend or scraping a competitor service.
- Copying myBillBook assets, themes, illustrations, strings, or database schema.
- Payment processing or lending. OpenBill records payments; it does not move money.
- Automated GST portal filing. The MVP exports data in portal-compatible formats.
- e-invoice and e-way bill registration without the user's own credentials and a
  compliant integration.
- AI OCR, WhatsApp Business API messaging, and an online storefront. These are later
  integrations with bring-your-own provider credentials.
- A hosted multi-tenant SaaS in the MVP. The first release is local-first.

## Planned technology stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin |
| UI | Compose Multiplatform (Android and desktop) |
| Architecture | Feature modules, MVVM with unidirectional state |
| Dependency injection | Koin (KMP-friendly); Hilt is Android-only |
| Local database | Room KMP with bundled SQLite, including FTS5 |
| Paging | Paging 3 on Android; shared paging abstraction for desktop |
| Async | Coroutines and Flow |
| Networking | Ktor client; optional sync backend is not part of MVP |
| Serialization | kotlinx.serialization |
| Preferences | DataStore KMP |
| Background work | WorkManager on Android; coroutine scheduler on desktop |
| PDF | Platform renderer behind a shared interface |
| Printing | Android PrintManager; desktop Java printing / CUPS |
| Barcode | ZXing |
| Testing | JUnit, Kotlin test, Turbine, Room in-memory tests, Compose UI tests |
| Build | Gradle with version catalogs, KSP where required |
| CI | GitHub Actions matrix for Android, Linux desktop, Windows desktop |

Versions are pinned in `gradle/libs.versions.toml` (AGP 8.13.2, Kotlin 2.3.10,
Compose Multiplatform 1.11.1). Android 8.0 (API 26) is the minimum; the project compiles
and targets API 36 at the time of writing and will move to the latest stable target before
release.

## Repository layout (target)

```text
openbill/
  app-android/              Android application shell
  app-desktop/              Windows and Linux desktop shell
  shared/
    core-model/             Domain entities and value objects
    core-database/          Room KMP database, DAOs, migrations, FTS
    core-data/              Repositories and sync boundary
    core-domain/            Use cases
    core-tax/               GST tax and rounding engine
    core-search/            Query parsing, ranking, highlights
    core-ui/                Design system and shared screens
    core-reporting/         Report models and exports
    core-printing/          Print layout contracts
    platform/               expect/actual boundaries
  feature/
    onboarding/  parties/  items/  inventory/  billing/  payments/
    reports/     gst/      settings/ expenses/  search/   backup/
  docs/
  gradle/
```

A single Gradle build owns all targets. Android and desktop applications share the same
feature screens unless a platform-specific interaction genuinely requires a different UI.

## Documentation

- [Roadmap](ROADMAP.md)
- [Reverse-engineering report](docs/00-reverse-engineering.md)
- [Feature inventory](docs/01-feature-inventory.md)
- [Search analysis](docs/02-search-analysis.md)
- [Architecture](docs/03-architecture.md)
- [Implementation plan](docs/04-implementation-plan.md)
- [Test plan](docs/05-test-plan.md)
- [Clean-room policy](docs/06-clean-room-policy.md)
- [Windows and Linux desktop strategy](docs/07-desktop.md)
- [Glossary](docs/08-glossary.md)
- [Tax assumptions](docs/09-tax-assumptions.md)

## Contributing

Contributions are welcome now, including code and specifications. See
[CONTRIBUTING.md](CONTRIBUTING.md) for build and test commands, clean-room rules, and
other ways to help.

Read [docs/06-clean-room-policy.md](docs/06-clean-room-policy.md) before contributing.
Do not submit code, assets, strings, or database schemas copied from a proprietary
application. Do not submit APKs or decompiled output.

## License

Apache License 2.0. See [LICENSE](LICENSE).

"myBillBook" is a trademark of its respective owner and is used here only for nominative
comparison. OpenBill is an independent project.
