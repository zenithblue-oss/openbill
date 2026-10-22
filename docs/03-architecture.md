# Architecture

OpenBill is a Kotlin Multiplatform application with three app targets (Android, Windows, and
Linux) and two JVM surfaces that reuse the same core: the `openbill` CLI and the optional,
self-hosted OpenBill Server. The architecture optimizes for offline correctness, a single
accounting core, and platform-native output.

## 1. Goals and constraints

Goals:

- One implementation of accounting, tax, inventory, and search rules.
- Offline-first operation on all platforms.
- Deterministic, testable invoice totals.
- Fast search over hundreds of thousands of records.
- Native installation and printing on Windows and Linux.
- Data portability through an open backup format.
- A codebase that a small team can maintain.

Constraints:

- No proprietary backend dependency for core workflows.
- No advertising, attribution, or contact-upload SDKs.
- Android support floor: API 26 (Android 8.0) minimum, so coverage includes devices used by
  small businesses, not only the latest flagship phones. Compile and target API 36 at the
  time of writing; bump the target to the latest stable API before release.
- Desktop packaging must work from CI, including on Windows runners.
- The tax engine must support historical rates without rewriting posted documents.

## 2. Technology decisions

| Concern | Decision | Rationale |
| --- | --- | --- |
| Language | Kotlin | Shared code across Android and JVM desktop |
| UI | Compose Multiplatform | Shared screens, one design system |
| Architecture | Feature modules, MVVM/UDF | Testable state and clear boundaries |
| DI | Koin | Works in common code; Hilt is Android-only |
| Database | Room KMP with bundled SQLite | Familiar API, migrations, FTS5 consistency |
| Search | SQLite FTS5 | Offline, fast, ranked, no extra service |
| Paging | Shared paging contract; Paging 3 on Android | Avoids Android-only APIs in common code |
| Async | Coroutines and Flow | Structured concurrency and cancellation |
| Serialization | kotlinx.serialization | KMP-native JSON |
| Networking | Ktor client | KMP HTTP client for optional sync |
| Server | Ktor server; SQLite default, PostgreSQL optional | Same Kotlin core and serialization on the JVM; see [10-server.md](10-server.md) |
| CLI | Clikt on the JVM; GraalVM native image after a spike | Kotlin-first subcommands and test API; see [11-cli.md](11-cli.md) |
| Preferences | DataStore KMP | Typed, asynchronous settings |
| Background work | WorkManager on Android; coroutine scheduler on desktop | Platform-appropriate scheduling |
| PDF | Platform renderer behind `PdfRenderer` interface | Android `PdfDocument`; desktop OpenPDF or PDFBox |
| Printing | Android PrintManager; desktop Java printing/CUPS | Native print dialogs |
| Barcode | ZXing | Mature JVM implementation |
| Secure storage | Android Keystore; Windows DPAPI; Linux libsecret | Platform key management |
| Packaging | Android Gradle Plugin; Compose Desktop `jpackage` | Standard toolchains |
| CI | GitHub Actions matrix | Ubuntu and Windows builds |

Exact library versions will be pinned in `gradle/libs.versions.toml` when the scaffold
lands. Avoid mixing Compose Android and Compose Multiplatform artifacts in the same
module.

## 3. Module graph

The graph is logical. `app-android` is the Gradle project `:androidApp`, `app-desktop` is
`:desktopApp`, and `shared:` is a grouping prefix only: the Gradle projects are flat (see the
note after the rules).

```text
app-android
  -> feature-* (Android-specific navigation and permissions)
  -> core-ui
  -> shared:core-data
  -> shared:platform-android

app-desktop
  -> feature-* (desktop navigation, menus, shortcuts)
  -> core-ui
  -> shared:core-data
  -> shared:core-sync (post-1.0)
  -> shared:platform-desktop

cli                        openbill command-line client (JVM)
  -> shared:core-domain
  -> shared:core-data
  -> shared:core-reporting
  -> shared:core-printing
  -> shared:core-api-contract
  -> shared:core-sync (remote mode, post-1.0)
  -> shared:platform-jvm

server                     OpenBill Server (JVM, post-1.0)
  -> shared:core-domain
  -> shared:core-data (SQLite projections)
  -> shared:core-reporting
  -> shared:core-printing
  -> shared:core-api-contract
  -> shared:core-sync (op log apply and validation)
  -> server-store-postgres (optional)
  -> server-integrations (optional providers)
  -> shared:platform-jvm

shared:core-model          Pure Kotlin domain types and value objects
shared:core-tax            GST and rounding engine
shared:core-database       Room KMP entities, DAOs, migrations, FTS
shared:core-domain         Domain models, repository ports, validation, use cases
shared:core-data           Room-backed repository adapters, transactions, backup, import/export
shared:core-search         Query parsing, ranking, highlights
shared:core-reporting      Report models, CSV, GSTR-1, Tally XML
shared:core-printing       Document models and layout contracts
shared:core-ui             Design system, shared screens, navigation contracts
shared:core-api-contract   Serializable DTOs, error codes, and OpenAPI source for API, CLI, sync
shared:core-sync           Operation log, outbox, HLC, conflict rules, Ktor sync/API client
shared:platform            expect declarations for platform services
shared:platform-android    Android actuals
shared:platform-jvm        Headless JVM actuals (paths, PDF, secure store) for CLI and server
shared:platform-desktop    Desktop actuals (AWT/Swing dialogs, printing); builds on platform-jvm

feature-onboarding
feature-dashboard
feature-parties
feature-items
feature-inventory
feature-billing
feature-payments
feature-expenses
feature-reports
feature-gst
feature-settings
feature-search
feature-backup
```

Rules:

- `core-model` and `core-tax` have no Android or desktop dependencies.
- `core-database` owns SQL and migrations; no feature writes SQL directly.
- Features depend on domain interfaces, not concrete platform implementations.
- Platform modules are the only place that touch Android SDK or AWT/Swing APIs.
- Shared UI is in `core-ui` and feature modules with `commonMain`; platform-specific UI is
  allowed only for interactions that cannot be shared.
- A feature may expose a route contract consumed by both app shells.
- `cli` and `server` must not depend on `core-ui`, `feature-*`, Compose, or AWT/Swing.
- `core-api-contract` holds DTOs only: no repositories, no Room entities, no Ktor.
- `core-sync` depends on `core-domain` and `core-api-contract`, never on `core-database`
  directly.

Gradle project names in `settings.gradle.kts` are flat (`:core-model`, `:androidApp`,
`:desktopApp`); new modules follow the same convention (`:core-api-contract`,
`:core-sync`, `:platform-jvm`, `:cli`, `:server`).

## 4. Source sets

```text
commonMain    Domain, tax, database, repositories, use cases, shared UI
androidMain   Android drivers, permissions, share, Bluetooth, biometrics
desktopMain   JVM drivers, file dialogs, printing, desktop notifications
commonTest    Tax, domain, search, repository, migration tests
androidTest   Device integration and Compose UI tests
desktopTest   JVM integration and Compose UI tests
```

Do not put Android imports in `commonMain`. Enforce this with a dependency-analysis check
or a simple CI grep for forbidden packages.

## 5. Domain model

### Identifiers and values

- IDs are ULIDs stored as strings. They are sortable and safe to generate offline.
- Money is stored as signed 64-bit paise, never floating point.
- Quantities use a fixed decimal scale (up to four decimal places).
- Rates are stored as fixed-point basis-point units, with hundredth-basis-point precision
  where needed, not as a binary float.
- Timestamps are UTC instants; display uses the firm's time zone.
- Dates that represent business dates (invoice date, due date) are local dates without a
  time component.

### Core entities

| Entity | Purpose |
| --- | --- |
| `Firm` | Legal business entity and branding |
| `FirmSettings` | Tax, stock, numbering, print, and feature settings |
| `FinancialYear` | Date range and close state |
| `Party` | Customer or supplier |
| `PartyAddress` | Billing or shipping address |
| `Item` | Good or service |
| `ItemCategory` | Optional grouping |
| `Unit` | Unit of measure and conversion |
| `Godown` | Stock location |
| `Batch` | Batch number and expiry for an item |
| `SerialNumber` | Individual tracked unit |
| `Voucher` | Header for any document |
| `VoucherLine` | Item, quantity, rate, discount, tax |
| `VoucherCharge` | Additional charges and their tax treatment |
| `PaymentDetails` | Account and instrument details for a payment voucher |
| `PaymentAllocation` | Links a payment to a document |
| `StockMovement` | Signed quantity movement from a posted document |
| `Account` | Cash, bank, or ledger account |
| `JournalEntry` | Double-entry movement for money accounts |
| `AuditEvent` | Append-only record of a state change |
| `Attachment` | File linked to a party, item, or voucher |
| `SearchDocument` | FTS projection rebuilt from canonical data |

### Voucher lifecycle

```text
draft -> posted -> cancelled -> restored (posted)
             \-> corrected by credit/debit note
```

Rules:

- Only posted vouchers affect stock, tax, and balances.
- Posted vouchers are immutable. Edits create a new version or a correcting document.
- Cancellation reverses stock and ledger effects in one transaction and records a reason.
- A cancelled voucher can be restored to the posted state in one transaction. Restore
  reapplies stock and ledger effects exactly once, keeps the original document number,
  writes an `AuditEvent`, and rejects a duplicate restore. It requires the recorded
  cancellation reason and a restore reason.
- Soft-deleted drafts can be restored from the recycle bin separately from the
  cancelled-voucher transition.
- Backup restore is a separate operation that replaces local data from an archive; see
  section 14. It is not a voucher transition.
- Drafts can be deleted physically or soft-deleted into the recycle bin. Soft-deleted
  drafts can be restored or purged. Posted and cancelled vouchers are not eligible for the
  draft recycle path; they remain in the document history.
- Document numbers are assigned at posting, inside the same transaction. The format is
  the sequence prefix plus a separator when needed, then the zero-padded number, scoped by
  firm, document type, and financial year.
- Every transition writes an `AuditEvent`.

### Database invariants

- Debits equal credits for every journal group.
- Sum of allocations never exceeds a payment or an invoice balance.
- Stock movements for a cancelled document sum to zero while it remains cancelled.
- Batch and serial quantities match line quantities.
- FTS row counts match canonical row counts for indexed types.
- Foreign keys are enforced; SQLite `foreign_keys` is on for every connection.
- `audit_events` is append-only. Triggers reject every `UPDATE` and `DELETE`, on fresh and
  migrated databases alike.

### Payments, control accounts, and party ledger

- Each firm owns system accounts for accounts receivable, accounts payable, output GST,
  input GST, and round-off. Account codes are unique per firm and are created idempotently
  at first posting. A same-named account without the expected code is a conflict, not a
  silent match.
- A payment voucher stores its cash/bank account, method, and instrument data in
  `payment_details`, one row per payment. The voucher itself carries no payment account
  columns.
- Posted vouchers post balanced journal entries. Sales and credit notes move the receivable
  control account; purchases and debit notes move the payable control account. Payment-in
  debits cash/bank and credits receivables; payment-out debits payables and credits
  cash/bank. Charge-inclusive voucher totals split charges into the sales or purchases
  account and charge tax into the matching GST account. Inconsistent totals fail posting
  instead of producing an unbalanced journal.
- Payment allocation does not create journal entries. It reduces the outstanding amount of
  the invoice, which is derived as invoice total minus effective posted allocations.
  Allocation rows are append-only. Allocation is validated against the payment balance,
  the invoice outstanding balance, firm, party, and document type.
- Cancelling a payment appends reversal journal entries and leaves its allocations in
  place. Effective allocation queries ignore cancelled or deleted payments, so invoice
  outstanding amounts and party balances recover without mutating history. Credit notes do
  not net against invoice outstanding in this phase.
- The party ledger is derived from receivable/payable journal entries joined to their
  posted, non-deleted vouchers. It is not a separate mutable balance table. The opening
  balance comes from the party master's signed opening balance; the closing balance is the
  opening balance plus posted movements.
- Expense vouchers (E8.10) and allocation editing are not yet implemented in the data layer;
  expense vouchers are in 1.0 scope.

## 6. Tax engine

The tax engine is a pure Kotlin library with no database or UI dependency.

Inputs:

- Line amount, quantity, rate, discount, and tax rate.
- Tax mode: inclusive or exclusive.
- Place of supply and firm state.
- Tax types: CGST, SGST, IGST, cess.
- Rounding policy at line and invoice level.
- Currency and minor-unit precision (INR uses two decimals).

Outputs:

- Taxable value per line.
- Tax components with rates and amounts.
- Discount allocation.
- Round-off amount.
- Grand total.
- A trace object explaining each calculation for debugging and tests.

Design notes:

- Use banker's or half-up rounding explicitly; never rely on platform defaults.
- Allocate invoice-level discounts proportionally with a largest-remainder method so the
  sum of line amounts equals the invoice total.
- Keep tax rates versioned with effective dates. A posted document stores the rate values
  used, not a reference that can change later.
- Validate GSTIN state code against the place of supply.
- Treat exempt, nil-rated, and zero-rated supplies explicitly.
- Support regular GST dealers only in 1.0.
- Post-1.0: reverse charge and composition-dealer support.

The implemented 1.0 behavior, including its assumptions and exclusions, is documented in
[09-tax-assumptions.md](09-tax-assumptions.md).

The tax module should have a golden test suite generated from hand-calculated examples.
Property-based tests should verify invariants such as "line totals sum to invoice total"
and "tax is non-negative for non-negative inputs."

## 7. Data layer and transactions

Repository ports live in `core-domain` and expose suspend functions and flows. They use
domain models, not Room entities, and the in-memory fakes in `core-domain` support use-case
tests and previews. Room-backed adapters belong in `core-data`.

Transaction boundaries:

- Posting a voucher: validate, assign number, insert voucher and lines, insert stock
  movements, insert journal entries, update FTS, write audit event.
- Recording a payment: validate the account and payment details, insert the payment
  journal entries, write audit event.
- Allocating a payment: validate the payment and invoice balances, insert append-only
  allocation rows, write audit event.
- Cancelling: validate state, insert reversing movements and entries, update status, write
  audit event.
- Restoring a cancelled voucher: validate state and reasons, reapply stock movements and
  journal entries, update status, write audit event.
- Soft-deleting a draft: validate draft state, set `deletedAt`, write audit event.
- Restoring a draft: validate it is soft-deleted, clear `deletedAt`, write audit event.
- Purging a draft: require it to be soft-deleted, remove lines, charges, and payment
  details, permanently delete the voucher, write audit event.

Use a single writer per database. SQLite handles concurrency, but the app should serialize
writes through a mutex or a single-threaded dispatcher to avoid `SQLITE_BUSY` and
nondeterministic numbering.

Read models:

- Dashboard aggregates are materialized views or cached queries updated on write.
- Reports query canonical tables, not stale caches, unless a cache is explicitly labeled.
- Search reads FTS tables and joins back to canonical rows for display.

## 8. Offline-first and optional sync

The local database is authoritative for the user's device.

For 1.0:

- No account is required.
- No network call is required for billing, inventory, payments, reports, or search.
- HSN/SAC lookup is offline. Dataset updates may use the network and degrade gracefully.
- GSTIN autofill, e-invoice, and e-way bill are post-1.0 integrations and are not required
  for any 1.0 workflow.

Sync is a post-1.0 feature. Before implementing it:

- Define ownership and conflict rules per entity.
- Add stable IDs, device IDs, logical clocks, and tombstones.
- Decide between server-authoritative sync and merge-based sync per document type.
- Never merge financial documents automatically. Conflicts on posted vouchers should
  create a review task, not silent edits.
- Design an outbox with idempotent operations and retry with backoff.
- Test offline divergence, duplicate delivery, clock skew, and partial sync extensively.

Do not add sync as an afterthought. The schema should include sync metadata fields from
day one, even if unused: `updated_at`, `deleted_at`, `revision`, `origin_device_id`.
Document numbering should also be scoped by a number-series identifier, so per-device
series can be introduced for sync without migrating posted documents.

The sync design is an append-only, device-authored operation log relayed by the optional
OpenBill Server. Posted vouchers are insert-only facts, so conflicts are limited to masters,
settings, drafts, and derived checks (allocations, negative stock). Details, conflict rules,
and milestones are in [10-server.md](10-server.md).

## 9. Search architecture

See [02-search-analysis.md](02-search-analysis.md) for evidence and product requirements.

Implementation summary:

- FTS5 virtual tables maintained transactionally with canonical rows.
- A shared query parser handles scopes, filters, quoting, and normalization.
- Ranking uses `bm25()` with field weights plus exact-match and recency boosts.
- Results include highlights and a deep link.
- Android and desktop use the same search service and result model.
- Desktop adds a command palette; Android adds a full-screen search surface.

## 10. UI architecture

- Compose Multiplatform for shared screens.
- One design system with tokens for color, typography, spacing, elevation, and motion.
- Adaptive layouts: single pane on phones, list-detail on tablets, sidebar plus content on
  desktop.
- State holders expose an immutable `UiState` and accept events.
- Navigation is expressed as route contracts in shared code, with platform shells
  providing the navigator.
- Strings are externalized from day one, even if only English ships in 1.0.
- Accessibility is part of the definition of done: content descriptions, focus order,
  keyboard navigation, and scalable text.
- Desktop-only affordances (menus, shortcuts, context menus, column resizing) live in the
  desktop shell or in `desktopMain` variants of shared screens.

Suggested screen structure:

```text
Screen
  TopBar / Toolbar
  Content
    EmptyState
    LoadingState
    ErrorState
    DataState
  Filters / BottomSheet / Dialog
```

Every list screen must define empty, loading, error, and permission-denied states.

## 11. Platform abstraction boundaries

Define `expect` interfaces in `shared:platform`:

| Interface | Android | Windows/Linux |
| --- | --- | --- |
| `FilePicker` | Storage Access Framework | AWT/Swing file chooser |
| `PdfRenderer` | `PdfDocument` | OpenPDF or PDFBox |
| `Printer` | `PrintManager` and Bluetooth | Java printing and CUPS |
| `ShareService` | Android share sheet | Save dialog and mail client |
| `SecureStore` | Keystore-backed encryption | DPAPI or libsecret |
| `AppLock` | BiometricPrompt | OS credential prompt where available |
| `Notifier` | Notification channels | Desktop notifications |
| `NetworkMonitor` | ConnectivityManager | JVM network state |
| `AppPaths` | Internal and cache directories | Platform data directories |
| `DeviceInfo` | Build and screen metrics | OS and JVM properties |

CLI and server use headless JVM actuals from `shared:platform-jvm`: `AppPaths`,
`PdfRenderer`, `SecureStore` (OS keyring where available, otherwise a mode-0600 key file),
and `DeviceInfo`. `FilePicker`, `Printer`, `ShareService`, `AppLock`, and `Notifier` are not
available headless; commands take explicit paths instead.

Keep actual implementations small and covered by platform tests. If an abstraction needs
more than a few methods, split it.

## 12. Desktop architecture

Detailed strategy: [07-desktop.md](07-desktop.md).

- One Compose Desktop application with a menu bar, sidebar, content area, and command
  palette.
- Single window for 1.0; preview panes instead of multi-window.
- Keyboard shortcuts for search, new invoice, save, print, and navigation.
- Data stored under the OS-appropriate application data directory, with a portable mode.
- PDF generation uses a JVM library; printing uses the OS print dialog.
- File exports go through native file choosers.
- MSI, DEB, and AppImage artifacts are built in CI. A package smoke build starts at D1;
  installable artifacts are a D4 deliverable.
- The MSI defaults to a per-user install; per-machine is an optional installer mode.
- No automatic update checks in 1.0; a manual, user-initiated Help check is allowed.
- Desktop UI tests run on a virtual display in CI where possible; packaging smoke tests
  run on clean Windows and Linux runners.

## 13. Security and privacy

Threat model:

- Lost or stolen device.
- Shared desktop computer.
- Malicious or corrupted backup file.
- Supply-chain compromise of a dependency.
- Accidental data exposure through exports or logs.

Controls:

- App sandbox on Android; per-user data directory with restrictive permissions on desktop.
- App lock using platform authentication is a post-1.0 enhancement; 1.0 relies on the OS
  device lock, the Android app sandbox, and per-user desktop permissions.
- Secrets stored in the platform secure store, never in preferences or logs.
- TLS required for all network calls; cleartext traffic disabled.
- No advertising ID, no contact upload, no analytics SDK by default.
- Export files created with user-selected locations and clear warnings.
- Backup integrity checked with SHA-256 and a manifest before restore.
- Dependency locking, vulnerability scanning, and reproducible builds where feasible.
- Audit log for sensitive actions: delete, restore, export, backup, and configuration
  changes. Role changes are recorded once roles ship after 1.0.
- Redacted diagnostics; never include invoice contents or party phone numbers by default.
- Networked surfaces (server, sync, CLI remote mode) have their own threat model in
  [10-server.md](10-server.md#13-threat-model).

Encryption at rest:

- Android relies on file-based encryption plus app sandbox for 1.0.
- Desktop relies on OS full-disk encryption plus restrictive file permissions.
- Optional database encryption (SQLCipher) is a post-1.0 enhancement; if added, key
  management must use the platform secure store and backup encryption must be designed
  first.

## 14. Backup format

```text
backup-<firm>-<timestamp>.openbill.zip
  manifest.json
    formatVersion
    appVersion
    schemaVersion
    createdAt
    firmId
    firmName
    recordCounts
    sha256
  database.sqlite
  attachments/
    <attachment-id>.<ext>
  checksums.sha256
```

A backup restore replaces local data from an archive; it is not a voucher transition.

Rules:

- Create the database snapshot with SQLite's backup API or a `VACUUM INTO` equivalent,
  not a file copy of a live database.
- Write to a temporary file, verify, then atomically rename.
- Restore validates the manifest, checksums, schema version, and available disk space
  before touching the current database.
- Restore keeps a pre-restore safety copy until the user confirms success.
- Backups are forward-compatible through migrations; older app versions refuse newer
  schemas with a clear message.
- Encryption envelope is versioned and optional in 1.0.

## 15. Build and release engineering

Android:

- Debug and release variants; release minified with R8 and a kept-symbols file.
- Signing keys stored in CI secrets, never in the repository.
- Baseline profiles for startup and list scrolling.
- AAB for Play; APK for direct distribution.
- `minSdk` and `targetSdk` pinned in the version catalog.

Desktop:

- Compose Desktop `application` configuration per OS.
- Windows MSI via `jpackage` and WiX on a Windows runner.
- Linux DEB and AppImage via `jpackage` on an Ubuntu runner.
- Bundle a compatible JRE; do not require a system Java installation.
- Include an application icon, desktop entry, and license file.
- Publish SHA-256 checksums with every release.

CLI and server:

- CLI: `jlink` runtime archives for Linux and Windows, optionally bundled with the desktop
  DEB and MSI; native image only after the spike passes.
- Server (post-1.0): fat JAR, `jlink` runtime tarballs, and a multi-arch OCI image
  (amd64, arm64).

CI pipeline:

1. Static checks and formatting.
2. Common unit tests and tax golden tests.
3. Android debug build and unit tests.
4. Desktop compile, JVM tests, and package smoke build (from D1), plus CLI golden-output
   tests on Ubuntu and Windows (from C0).
5. Database migration tests.
6. Compose UI tests on Android emulator and desktop virtual display.
7. Packaging jobs for MSI, DEB, and AppImage on tagged releases.
8. Artifact signing and checksum generation.

Versioning:

- Semantic versioning for the product.
- Independent database schema version with explicit migrations.
- Backup format version independent of both.
- Release notes list schema changes, migration behavior, and known issues.

## 16. Definition of done for a feature

A feature is not done until:

- Domain rules are covered by unit tests.
- Database changes include a migration and a migration test.
- Offline behavior is defined.
- Empty, loading, error, and permission states exist.
- Accessibility labels and keyboard behavior are verified.
- Strings are externalized.
- Backup and export behavior is defined.
- Android and desktop behavior is verified, or the platform limitation is documented.
- Performance is measured against the relevant budget.
- User-facing documentation is updated.

## 17. CLI and server

- The CLI ([11-cli.md](11-cli.md)) is a thin shell over `core-domain` use cases. Direct mode
  opens the local database under the same single-writer lock as the desktop app, or a
  backup archive read-only. Remote mode calls the server API. Direct mode ships with 1.0.
- The server ([10-server.md](10-server.md)) is optional and post-1.0. It runs the shared
  core on the JVM, relays the sync operation log, enforces roles, and exposes a versioned
  REST API described by OpenAPI. Apps never require it.
- Both share `core-api-contract`, so `--json` CLI output, API payloads, and sync payloads
  use one schema. Money is serialized as a decimal string, never a JSON float.
