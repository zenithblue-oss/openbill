# Test Plan

OpenBill handles money and stock records that small businesses rely on. Testing is
organized around invariants, not around screens. This plan covers the shared core, Android,
Windows, Linux, and the `openbill` CLI, which ships with 1.0. Sections 13 to 15 cover the
optional server, sync, and API ([10-server.md](10-server.md)); they are post-1.0 and must be
green before any release that includes those features.

## 1. Test pyramid

| Layer | Scope | Speed | Runs on |
| --- | --- | --- | --- |
| Unit | Money, tax, domain rules, parsers | Fast | Every commit |
| Property-based | Accounting and rounding invariants | Fast | Every commit |
| Integration | Database, transactions, migrations, search | Medium | Every commit |
| UI component | Compose components and state holders | Medium | Every pull request |
| CLI golden output | Command stdout, stderr, and exit codes against seeded fixtures | Medium | Every pull request, Ubuntu and Windows |
| Sync simulation | N simulated devices against an in-memory server (post-1.0) | Medium | Every commit touching `core-sync` |
| API contract | OpenAPI conformance and authorization matrix (post-1.0) | Medium | Every pull request touching the server |
| End-to-end | Core workflows on device and desktop | Slow | Nightly and release |
| Packaging | Install, launch, print, backup on clean OS | Slow | Release candidates |

The majority of tests should be unit and integration tests. End-to-end tests protect the
critical paths but are too slow to cover every rule.

## 2. Test environments

### Android

- Low-end device profile: 2 GB to 4 GB RAM, Android 8.0 (API 26) or newer, 720p display.
- Mid-range device: Android 14 or newer, 1080p display.
- Tablet or large-screen emulator.
- Emulator for CI: API 26 (the minimum), plus API 28, API 34, and API 36 as appropriate;
  substitute the latest stable API when it changes.
- Thermal printer: at least one 58 mm Bluetooth printer and one 80 mm printer.
- Airplane mode and poor-network simulations.

### Windows

- Windows 11 x64 clean VM.
- Windows 10 x64 if still supported by the toolchain.
- High-DPI display at 125 percent and 150 percent scaling.
- Standard user account without administrator rights; a separate administrator account for
  per-machine MSI testing.
- Printer: Microsoft Print to PDF and one physical printer if available.

### Linux

- Ubuntu LTS as the release gate, plus an AppImage smoke test on one non-Ubuntu glibc
  distribution; best effort, not a release gate.
- Wayland and X11 sessions.
- Fractional scaling at 125 percent.
- CUPS with Print to File and a physical printer if available.
- AppImage on a distribution without the build machine's libraries.

### Cross-platform

- Same backup file restored on all three platforms.
- Same invoice data produces equivalent totals and PDF content.
- Shared database fixtures loaded on Android and desktop.
- CLI archives on a clean Ubuntu VM and a clean Windows VM with no system Java.
- Server (post-1.0): a Raspberry Pi 4 class arm64 host and an amd64 container, with an
  Android, a Windows, and a Linux client paired to it.

## 3. Money and tax tests

Golden tests (1.0 covers regular GST dealers only):

- Intra-state GST with CGST and SGST.
- Inter-state GST with IGST.
- Union territory transactions.
- Inclusive and exclusive pricing.
- Line-level and invoice-level discounts.
- Additional charges that are taxable and non-taxable.
- Round-off at line and invoice level.
- Cess and multiple tax components.
- Zero-rated, exempt, nil-rated, and non-GST supplies.
- Credit notes and debit notes with negative lines.
- Partial payments and advance adjustments for ledger tests.
- Historical tax rates applied to back-dated documents.

Post-1.0 golden tests: composition-dealer and reverse-charge settings, once supported.

Property-based tests:

- Sum of line taxable values equals invoice taxable value after allocation.
- Sum of tax components equals total tax.
- Grand total equals taxable value plus tax plus charges minus discounts plus round-off.
- Rounding never changes the sign of a non-zero amount.
- Allocation of a discount never produces a line total below zero.
- Reordering invoice lines does not change the invoice total.
- Splitting a line into two lines with the same rate preserves the total, within the
  documented rounding rule.

Reference data:

- Hand-calculated spreadsheets for at least 50 scenarios, checked into the repository as
  CSV fixtures with expected outputs.
- An accountant review sign-off recorded in the release checklist for any tax-rate change.

## 4. Voucher and ledger tests

Posting:

- Draft does not affect stock or balances.
- Posting assigns the next number and is atomic.
- A failure during posting leaves no partial rows.
- Concurrent posting attempts do not duplicate numbers.
- Cancellation reverses stock and ledger effects exactly once.
- Cancelling twice is rejected.
- Editing a posted voucher creates a correction path, not a silent mutation.
- Restoring a cancelled voucher reapplies stock and ledger effects exactly once.
- Restore keeps the original voucher number.
- Restore writes an audit event and requires the recorded cancellation reason and a restore
  reason.
- Restoring an already restored voucher is rejected.
- A voucher restore and a backup restore are separate operations; a backup restore does not
  create a voucher restore audit event or duplicate stock and ledger effects.

Recycle bin:

- Soft-deleting a draft removes it from active voucher lists but keeps it in the firm's
  recycle bin with its lines and charges.
- Restoring a soft-deleted draft clears its deletion timestamp and restores it to draft
  state; restoring an active draft is rejected.
- Purging requires a soft-deleted draft and removes its lines, charges, and payment details.
- Posted and cancelled vouchers cannot be soft-deleted or restored through the draft recycle
  path.
- Recycle-bin reads are scoped to the firm and ordered by deletion time, newest first.
- Soft-delete, restore, and purge each write an audit event in the same transaction.

Stock:

- Sale decreases stock; purchase increases stock.
- Sales return increases stock; purchase return decreases stock.
- Stock transfer moves quantity between godowns without changing total.
- Adjustment requires a reason and writes an audit event.
- Negative stock is blocked by default and allowed only when configured.
- Batch quantity and serial count match line quantity.
- Serial numbers cannot be sold twice.
- Expired batch warnings are shown but do not silently block unless configured.

Payments:

- Partial payment reduces the invoice balance correctly.
- Multiple allocations cannot exceed the payment amount.
- Allocation cannot exceed the invoice outstanding amount.
- Advance payments remain unallocated until applied.
- Cancelling a payment appends reversal entries and makes allocations dormant without
  deleting them.
- Cancelling an invoice does not reallocate its payments automatically.
- Credit notes do not net against invoice outstanding in this phase.
- Party closing balance equals opening plus all posted movements.
- Aging buckets sum to the total outstanding.

Ledger:

- Chosen accounting equation holds for every posted transaction.
- Cash and bank balances match their journal entries.
- Opening balances come from party master data and are included in the party ledger and
  control reconciliation.
- Financial-year close preserves balances and prevents back-dated posting into a closed
  year unless the user reopens it.

## 5. Database and migration tests

Voucher posting tests cover number assignment, financial-year sequence isolation, duplicate
post prevention, cancellation state and reasons, audit rows, and rollback of number and
audit writes when the voucher update fails.

Audit guards:

- Fresh and migrated databases install the `audit_events` update/delete rejection triggers.
- Direct `UPDATE` and `DELETE` attempts against `audit_events` fail, while inserts succeed.

- Create the schema from empty and compare with the exported schema.
- Migrate from every released schema version to the current version.
- Verify row counts and checksums for critical tables after migration.
- Verify FTS tables are rebuilt or migrated correctly.
- Test a migration interrupted mid-way; the database must remain usable or restore from
  the automatic pre-migration backup.
- Test large databases: 50,000 invoices, 500,000 line items, 10,000 parties.
- Test low disk space during migration and backup.
- Test a corrupted database file; the app must fail safely and offer restore.
- Test foreign-key enforcement and cascading behavior.
- Test WAL checkpointing and graceful shutdown on desktop.

Schema files live in `core-database/schemas`; `DatabaseMigrations` validates that the
registered chain has no gaps, and `DatabaseSchemaTest` covers empty-to-current creation
and schema validation. Migration fixtures are immutable. Never edit an old fixture to
make a new migration pass.

## 6. Search tests

Normalization:

- Phone numbers with spaces, hyphens, country code, and leading zero.
- GSTIN lowercase and with separators.
- Invoice numbers with slashes, hyphens, and prefixes.
- Unicode names with combining characters.
- Devanagari and other Indian scripts once localization lands.

Ranking:

- Exact invoice number beats a party name containing the same digits.
- Exact phone beats a partial phone.
- Prefix match beats a middle substring.
- Recent documents win ties.
- Deleted documents are excluded by default.
- Filters compose with text queries.

Consistency:

- Create, update, cancel, restore, and delete keep FTS in sync.
- Rebuilding the index yields the same results as incremental updates.
- Counts match between canonical and FTS tables.

Performance:

- p95 under 100 ms at 50,000 invoices on a low-end Android profile.
- p95 under 150 ms at 500,000 invoices on desktop.
- Exact barcode lookup under 30 ms.
- No leading-wildcard query plan in hot paths.
- Memory overhead under 100 MB at 500,000 invoices.

UI behavior:

- Debounce prevents one query per keystroke.
- Stale results never overwrite newer results.
- Empty query does not run a full scan.
- Keyboard navigation works in the desktop palette.
- TalkBack announces result count and match context.

## 7. UI and accessibility tests

For every screen:

- Empty state with a clear next action.
- Loading state that does not block navigation.
- Error state with retry and a readable message.
- Permission-denied state with an explanation.
- Offline state where relevant.
- Large text at 200 percent without clipping critical values.
- Dark mode contrast.
- Landscape and portrait on Android.
- Window resizing on desktop.
- Keyboard-only operation on desktop.
- TalkBack traversal order on Android.
- Screen-reader labels for icon-only buttons.
- Color is never the only signal for status.

Critical flows for Compose UI tests:

- Create a firm.
- Add a party and an item.
- Create and post an invoice.
- Search for the invoice and open it.
- Record a payment.
- Cancel and restore an invoice.
- Back up and restore.
- Print preview.

Screenshot tests are useful for the design system but must not be the only guard for
layout correctness.

## 8. End-to-end scenarios

### Scenario A - First invoice

1. Install on a clean device.
2. Create a firm without an account.
3. Add a party and an item.
4. Create a GST invoice with a discount and round-off.
5. Verify totals manually.
6. Share the PDF.
7. Search for the invoice by number, party, and amount.

Pass criteria: correct totals, legible PDF, all three searches find the invoice, no network
required.

### Scenario B - Month end

1. Create sales, purchases, payments, and a credit note.
2. Run sales register, GST summary, and HSN summary.
3. Export GSTR-1 and reconcile totals with the sales register.
4. Close the financial year.
5. Verify opening balances in the new year.

Pass criteria: reports reconcile, export validates, close is reversible until the first new
posting.

### Scenario C - Inventory stress

1. Enable two godowns, batch tracking, and serial tracking.
2. Post 1,000 mixed invoices and purchases.
3. Transfer stock between godowns.
4. Adjust stock with a reason.
5. Run stock summary and trace three random quantities to source documents.

Pass criteria: every quantity is explainable; no negative stock unless configured; serial
duplicates are rejected.

### Scenario D - Device migration

1. Back up on Android.
2. Restore on Windows.
3. Verify parties, items, invoices, attachments, and search results.
4. Create an invoice on Windows.
5. Back up and restore on Linux.

Pass criteria: no data loss, totals unchanged, attachments open, search rebuilt or restored
correctly.

### Scenario E - Print matrix

1. Print an A4 invoice from Android, Windows, and Linux.
2. Print a 58 mm receipt from Android Bluetooth and desktop USB/CUPS.
3. Print an 80 mm receipt.
4. Print a barcode label sheet.

Pass criteria: no clipped text, correct page size, scannable barcodes, legible totals.

## 9. Desktop packaging and installation tests

- Install the per-user MSI as a standard user (the default mode).
- Install the per-machine MSI as an administrator (the optional mode).
- Uninstall leaves user data unless the user opts to delete it.
- Install DEB on a clean Ubuntu VM.
- Run AppImage on a distribution without the build machine's libraries.
- Launch from a path containing spaces and non-ASCII characters.
- Run with a read-only installation directory and a writable data directory.
- Verify file associations and desktop entry on Linux.
- Verify the bundled JRE is used, not a system Java.
- Verify the app starts with no network.
- Verify no automatic update check runs at startup or in the background.
- Verify the manual update check from Help is user-initiated, does not block startup, and
  fails gracefully when offline.
- Verify checksums for all release artifacts.

## 10. Security tests

- Confirm no secrets, signing keys, or developer paths in release artifacts.
- Confirm cleartext traffic is disabled in release builds.
- Confirm exported Android components are minimal and protected.
- Confirm backup files validate checksums before restore.
- Confirm a tampered backup is rejected.
- After 1.0, if app lock is enabled, confirm it cannot be bypassed by killing and
  relaunching into a protected screen.
- Confirm logs redact phone numbers, GSTINs, and invoice contents.
- Confirm secure storage uses Keystore, DPAPI, or libsecret as appropriate.
- Run dependency vulnerability scans and review high-severity findings.
- Test backup encryption if the feature is enabled.

## 11. Performance tests

Budgets:

| Metric | Target |
| --- | --- |
| Android cold start | Under 2 seconds on mid-range, under 4 seconds on low-end |
| Desktop cold start | p95 under 4 seconds on a 4-core, 8 GB, SSD machine ([07-desktop.md](07-desktop.md#12-performance-expectations)) |
| Invoice editor keystroke to total update | Under 50 ms |
| Search p95 | Under 100 ms at 50k invoices on Android |
| PDF generation, one-page invoice | Under 500 ms |
| PDF generation, 100 invoices | Under 30 seconds, streamed |
| Database migration, 50k invoices | Under 60 seconds with progress |
| Backup, 50k invoices | Under 60 seconds |
| Memory during invoice editing | Under 200 MB on Android |

Use Macrobenchmark and Baseline Profiles on Android. Use JMH or simple repeatable benchmarks
on the JVM. Store benchmark results as CI artifacts and fail on significant regressions.

## 12. CLI tests

The CLI ([11-cli.md](11-cli.md)) is a 1.0 surface; its public contract is frozen at 1.0.

- Golden-output tests run each command in-process against a seeded fixture database and
  compare stdout, stderr, and exit code with checked-in golden files. The clock and ULID
  generator are injected through DI, never through a public flag. Line endings are normalized
  so Ubuntu and Windows share the same goldens.
- Every documented exit code (0 to 10 and 130) has at least one test that triggers it.
- `--json` output validates against the JSON Schema generated from `core-api-contract`;
  removing or renaming a field fails the build, adding one does not.
- Non-interactive behavior: with stdin not a terminal, a prompting command fails with exit
  code 2 unless `--yes` is given; an idempotency key replays the first result and never
  creates a duplicate.
- Safety: `voucher cancel`, `voucher restore`, `backup restore`, and `import csv` honor
  `--reason`, `--yes`, and `--dry-run`; there is no flag that bypasses a domain invariant.
- Lock behavior: a write command fails with exit code 6 while the desktop app holds the
  database lock; read commands and `--backup` mode still work.
- Direct-versus-app parity: a voucher posted through the CLI and the same voucher posted
  through the use case layer produce identical journal entries, stock movements, and audit
  events (with the CLI recorded as source).
- Cross-platform backup: a backup created by the CLI on Linux is verified and reported on
  Windows, and report output is compared byte for byte; Android-created backups feed the
  same check.
- Large fixtures: the randomized 10,000-voucher stock test and the 50,000-invoice search
  fixtures are generated through CLI commands.
- Packaging: the signed CLI archives run on a clean VM with no system Java, and checksums
  verify.

## 13. Sync and conflict tests (post-1.0)

Rules and the protocol are in [10-server.md](10-server.md#6-sync-protocol). The `core-sync`
simulation suite runs an in-memory server with N simulated devices, each with a seeded random
schedule of offline periods, and checks invariants after every run.

Invariants:

- All devices converge to the same projection once every outbox is drained.
- No posted voucher is lost or altered; posted vouchers are insert-only facts.
- Allocations never exceed payment or invoice balances on any device.
- Server `seq` is gap-free and monotonic per firm.

Fault injection: duplicate delivery, reordering across devices, dropped acknowledgements,
partial batches, clock skew of hours in either direction, a server restore from backup (new
epoch), a device offline for weeks, and a device that restores an old backup.

Conflict cases, each with an expected outcome from the conflict table:

- Concurrent cancel of one voucher; cancel racing a restore; restore of a voucher that is not
  cancelled.
- Two devices editing different fields of one party (merged) and the same field (last writer
  by HLC).
- Concurrent edits to GSTIN, state, HSN/SAC, tax rate, or cess (kept earlier value, conflict
  record, review task, no auto-merge).
- Master edit on one device while another posts a voucher using it (the posted snapshot is
  unchanged).
- Two devices allocating one payment to different invoices past its balance (later op
  suspended, review task).
- Negative stock after merge under each firm policy.
- Draft edited on two devices (conflicted copy, never merged).
- Document numbers across per-device series, block-lease exhaustion offline, and a legacy
  collision.
- Op group atomicity: a voucher post with its allocations is accepted or rejected as one unit.
- Tax-engine version skew produces a review task with both traces, not a rejection.

Product guarantees: every workflow still passes with no server; unpairing leaves a complete
local database; re-pairing requires a fresh snapshot exchange; a server backup restores into a
standalone app.

## 14. API contract tests (post-1.0)

- The OpenAPI 3.1 document is generated from `core-api-contract` and checked in; CI fails if
  it changes without a reviewed diff.
- Every endpoint is exercised from the OpenAPI document: request and response schemas, status
  codes, and `application/problem+json` error bodies with stable `code` values.
- Conventions: money, quantities, and rates are decimal strings and never JSON floats;
  `Idempotency-Key` replays return the first response; stale `If-Match` returns 412; cursor
  pagination has no offset; unknown response fields are ignored by clients.
- Authorization matrix: every role (owner, accountant, operator, read-only, CA seat) against
  every endpoint, plus cross-workspace access attempts, expired and revoked tokens, and
  scopes narrower than the call.
- Posted vouchers have no `PATCH` or `DELETE`; long-running work returns 202 with a job.
- Repository contract tests run against the SQLite and PostgreSQL adapters.
- Rate limits return 429 with `Retry-After` and are configurable.
- Upgrade tests: a version N-1 data directory migrates to N after an automatic backup;
  downgrade is refused.
- Load: Raspberry Pi 4 class host, 5 devices, 50,000 vouchers, p95 push and pull under
  500 ms and heap under 256 MB.
- Remote-mode CLI goldens match direct-mode goldens.

## 15. Device pairing and server security tests (post-1.0)

- Pairing invitations are single-use and expire after 10 minutes; reuse and expiry are
  rejected; invitation secrets are never logged.
- The pairing QR carries the certificate fingerprint; a device refuses a server presenting a
  different certificate.
- Redeeming an invitation requires the user's password or owner approval; brute-force
  attempts hit the rate limit and lockout.
- Revoking a device invalidates its access and refresh tokens immediately; ops it pushes
  afterwards are quarantined for review, not applied and not discarded.
- A role downgrade quarantines later-applied offline ops authored before the downgrade.
- Tokens are random, type-prefixed, stored only as hashes, and never appear in logs, audit
  rows, or error bodies; API token scopes and expiry are enforced.
- The server refuses a non-localhost HTTP listener unless the insecure flag is set, and warns
  on every start when it is.
- Cross-workspace isolation: no endpoint or sync path returns another workspace's data.
- Malicious uploads: a snapshot or backup with a bad manifest, checksum, schema version,
  oversized entry, path traversal, or symlink is rejected before any restore.
- Outbound calls from integrations go only to hosts declared by the enabled provider; no
  user-supplied URL is fetched except explicit storage targets.
- The hash-chained server audit log detects truncation or edits (`audit verify`).
- End-to-end encrypted workspaces: the server cannot read payloads, key rotation re-wraps
  keys after revocation, and server-only features are unavailable.
- Logs contain no invoice contents, party names, phone numbers, GSTINs, or tokens.

## 16. Release checklist

- All release-blocking tests for 1.0 pass; optional post-1.0 tests may be deferred.
- Tax golden tests pass and rate changes are reviewed.
- Migration tests pass from every supported schema.
- Backup restore tested on Android, Windows, and Linux.
- Clean-VM install tests pass for MSI, DEB, and AppImage on Ubuntu LTS.
- The non-Ubuntu AppImage smoke test is best effort and does not block release.
- Accessibility spot check passes on all platforms.
- Performance budgets met or deviations documented.
- Security scan has no unresolved high-severity findings.
- Release artifacts signed and checksums published.
- Release notes include schema changes, known issues, and rollback steps.
- A pre-release backup of a real test firm is restored successfully.
- CLI golden, JSON contract, and exit-code tests pass on Ubuntu and Windows.
- For any release that includes sync or the server: simulation, API contract, and pairing
  security suites pass.

## 17. Test data policy

- Use synthetic firms, parties, and items. Never commit real customer data.
- Generate deterministic fixtures with a seeded random generator.
- Keep golden invoice fixtures small and reviewable.
- Include Indian names, phone formats, GSTINs, and addresses in fixtures, but use
  fictitious values.
- Do not include copyrighted invoice themes, logos, or scanned documents from third
  parties.
