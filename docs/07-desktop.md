# Windows and Linux Desktop Strategy

OpenBill ships native Windows and Linux apps from the same Kotlin Multiplatform codebase as
Android. This is the desktop engineering and release contract. Milestones and modules follow
[ROADMAP.md](../ROADMAP.md), [03-architecture.md](03-architecture.md), and
[04-implementation-plan.md](04-implementation-plan.md).

## 1. Purpose and goals

Desktop billing is back-office work: a large screen, a keyboard, shared printers, and files
from accountants. Goals:

- The same ledger, tax, inventory, reports, and search as Android.
- A native install with keyboard-first entry.
- Predictable A4, A5, 58 mm, and 80 mm output.
- Portable offline data with no account and no telemetry.

"First-class" means desktop is a release gate: MSI, DEB, and AppImage must install and pass
the smoke workflow before release, and desktop defects in billing, printing, or backup block
release. Desktop gets designed menus, shortcuts, tables, and window behavior, not stretched
phone layouts. A package smoke build starts at D1; installable MSI/DEB/AppImage artifacts
are a D4 deliverable.

Non-goals for 1.0: multiple windows, tray, background sync, automatic update checks,
self-update, raw ESC/POS, a local server, macOS, and 32-bit builds.

### Competitive baseline

The competing product's Windows app (studied 2026-10-22) is a thin Electron shell around its
web application. It has no local database, needs the internet for all data, ships on Windows
only, and sits behind a paid tier. What the shell adds over a browser is small: silent
printing of an invoice PDF to the default printer, bulk invoice download as a zip, bulk print,
download notifications that reveal the file in its folder, a single-instance lock, and shell
auto-update. OpenBill desktop must match these natively and exceed them on data ownership.

| Item | Competitor shell | OpenBill target | Timing |
| --- | --- | --- | --- |
| Silent print to default printer | Yes, Windows only | Print directly to the default printer without a dialog, as an option beside the system dialog, on Windows and Linux | P0; D3 |
| Bulk PDF/ZIP export | Yes, server-generated | Export selected invoices as PDFs or one zip, generated locally | P0; D3 |
| Bulk print | Yes | A sequential loop over silent print or the chosen printer, with progress, cancellation, and per-invoice failures | P0; D3, as in section 6 |
| Export notification with show-in-folder | Yes | OS notification after export or backup, with a show-in-folder action | P0; D3 |
| Single instance | Yes | A second launch focuses the existing window; protects the single-writer database | D1 |
| Signed auto-update | Shell updates; downloaded web bundle verified by version only | Signature-verified updates (signed manifest, checksum, rollback); 1.0 ships the manual Help check, automatic checks follow after 1.0 | Post-1.0 |
| Offline-first | No | Full ledger, search, print, and backup with no network | D0 onward |
| Linux | No | DEB and AppImage | D4 |
| Developer tools in release builds | Reachable | Not shipped | D4 |

Silent print, bulk print, bulk PDF/ZIP export, and export notifications are P0 in
[01-feature-inventory.md](01-feature-inventory.md) and ship in 1.0 at D3 (ROADMAP Phase 3).
They are free in OpenBill, whereas the competitor gates them behind its top paid tier. Where
this table moves a non-goal (automatic update checks), the scope of record in
[ROADMAP.md](../ROADMAP.md#release-scope-of-record) still governs the 1.0 scope.

## 2. Supported platforms and architectures

| Platform | Architecture | Status for 1.0 | Package |
| --- | --- | --- | --- |
| Windows 11 | x64 | Supported | MSI |
| Windows 10 22H2 | x64 | Supported if the pinned toolchain allows; confirmed at D4 | MSI |
| Linux, Ubuntu LTS baseline | x64 | Supported | DEB, AppImage |
| Other glibc distributions | x64 | Best effort through AppImage | AppImage |
| Windows or Linux | arm64 | Validation target only | None promised |

Windows 7, Windows 8, x86, and musl are out of scope. Ubuntu 24.04 LTS is the reference
environment; 22.04 is a compatibility check. RPM-based distributions are covered by the
best-effort AppImage check, not a release gate. arm64 is built and smoke-tested when the JVM,
SQLite native library, and packaging tools allow it, but it is not a 1.0 release criterion.

MSI builds run on Windows with `jpackage` and WiX; DEB and AppImage builds run on Ubuntu.
Cross-compilation is not promised, and signing happens on the producing runner.

## 3. Shared core and desktop-specific code

Desktop follows the module graph in [03-architecture.md](03-architecture.md). `app-desktop`
(the Gradle project `:desktopApp`) uses the shared feature modules and `shared:platform-desktop`; it never forks domain logic.

| Layer | Shared | Desktop-specific |
| --- | --- | --- |
| Domain, money, tax | `core-model`, `core-domain`, `core-tax` | None |
| Database | `core-database`, Room KMP, bundled SQLite | JVM driver and paths |
| Search and reporting | `core-search`, `core-reporting` | Command palette, save dialogs |
| Document layout | `core-printing` contracts | PDF renderer, printer transport |
| UI | `core-ui`, shared screens, tokens | Menus, shortcuts, tables, window chrome |
| Platform edges | `shared:platform` expects | `shared:platform-desktop` actuals |

Invoice editing, posting, tax, inventory, payments, reports, search, and backup are shared.
Desktop owns navigation wiring, file choosers, printing, secure storage, notifications, and
packaging. No AWT, Swing, or `java.awt.print` appears in `commonMain`; fork a screen only for
a genuinely different interaction, such as a resizable table. PDF layout stays shared behind
`PdfRenderer`.

## 4. Desktop user experience

The app uses one window: menu bar, toolbar, left sidebar, content, and optional right
preview. The sidebar collapses to icons below 1280 logical pixels and overlays below 960.
The minimum window is 1024 by 700, usable at 125 percent on a 1366 by 768 display.

Ctrl/Cmd+K opens a command palette over invoices, parties, items, payments, and expenses,
plus commands such as "new invoice", "back up now", and "print last invoice". Results are
ranked and keyboard-navigable; scopes such as `party:` and `inv:` narrow the search.

| Action | Shortcut | Behavior |
| --- | --- | --- |
| Search | Ctrl/Cmd+K | Opens the palette and keeps the prior query |
| New invoice | Ctrl/Cmd+N | Opens a blank draft without discarding the current one |
| Save | Ctrl/Cmd+S | Saves the draft; posting stays explicit |
| Print | Ctrl/Cmd+P | Opens preview, then the system print dialog |
| Dismiss | Escape | Closes palette or dialog, then clears a transient filter |

Windows and Linux use Ctrl; Cmd exists for future ports. Shortcuts do not fire in text
fields unless global, and every shortcut has a menu equivalent.

Tables are virtualized with sortable columns, persistent widths, sticky headers, checkbox
and range selection, and context menus. Bulk actions use explicit selections and confirm
destructive operations. The preview uses the print renderer with page navigation, zoom,
save, and print; regeneration is debounced and off the UI thread.

Layouts remain legible at 100, 125, 150, and 200 percent, including fractional Wayland
scaling, and per-monitor DPI changes need no restart. Window size, position, maximized state,
pane widths, and column widths persist; off-screen positions are re-centered. Dark mode
follows the OS with a manual override.

## 5. Data locations and portability

| Content | Windows | Linux |
| --- | --- | --- |
| Database, attachments, backups, logs | `%LOCALAPPDATA%\OpenBill` | `${XDG_DATA_HOME:-$HOME/.local/share}/openbill` |
| Configuration and window state | `%APPDATA%\OpenBill` | `${XDG_CONFIG_HOME:-$HOME/.config}/openbill` |
| Cache and render files | `%LOCALAPPDATA%\OpenBill\cache` | `${XDG_CACHE_HOME:-$HOME/.cache}/openbill` |

The data directory holds `openbill.db`, WAL files, `attachments/`, `backups/`, and `logs/`.
Linux uses 0700 directories and 0600 database files; Windows inherits the per-user ACL.

Portable mode uses an `openbill.portable` marker beside the executable, or an explicit flag,
and stores data in `openbill-data/` beside the executable or AppImage. It takes precedence,
appears in About, and never falls back silently. For AppImage, the original path comes from
the environment because the mount is read-only.

Network shares, roaming profiles, and cloud-synced live databases are unsupported. Each OS
user has an independent database; firm-level multi-user access is post-1.0 sync. Backups
follow [03-architecture.md](03-architecture.md); restore validates manifest, checksums,
schema, and space, and keeps a safety copy until confirmed.

## 6. Printing and PDF

`core-printing` owns layout and pagination. The desktop renderer uses OpenPDF or PDFBox,
selected at D2.

| Format | Page model | Use |
| --- | --- | --- |
| A4 | 595 by 842 points, multi-page | Invoices, reports, statements |
| A5 | 420 by 595 points, multi-page | Compact invoices, delivery notes |
| Thermal 58 mm | About 164 points wide, content height | Receipts |
| Thermal 80 mm | About 227 points wide, content height | Receipts and bills |

Thermal layouts use printable width after driver margins. Long receipts continue with
repeated headings. Printing uses `javax.print` and `PrinterJob` to reach the Windows spooler
or CUPS; the system dialog picks printer, paper, orientation, copies, and scaling. Thermal
printers use their OS driver; raw ESC/POS and vendor SDKs are out of scope.

PDF output is deterministic: layout depends only on document data, template version,
bundled fonts, and renderer version. Embedded font subsets replace Arial, Calibri, or
DejaVu; Latin and Devanagari are covered, with other Indic scripts a known gap. Indian digit
grouping is explicit, not host-locale dependent. Metadata timestamps are fixed or omitted;
tests compare text and geometry, not byte identity.

Preview, save, and print share one renderer for a single invoice. The 1.0 matrix covers A4
and A5 on both spoolers, 58/80 mm thermal, virtual PDF printers, 100 to 150 percent scaling,
offline queues, and cancellation. Silent print sends the PDF to the default or a chosen
printer without a dialog; it is a setting that is off until the user enables it. Bulk print
(P0) is a loop over silent print: it takes a selection, order, copies, and printer, runs
sequential jobs, and shows progress, cancellation, and per-invoice failures. Bulk PDF/ZIP
export renders each invoice locally and writes the zip atomically; after an export or backup
an OS notification offers a show-in-folder action.

## 7. File handling

All file access goes through `FilePicker`. Desktop uses the AWT native dialog with a Swing
fallback; Wayland and portal behavior is validated at D3. Paths use `java.nio.file.Path`,
never string concatenation or a shell. Spaces, Unicode, and Windows paths beyond 260
characters are tested. Exports are UTF-8, with an optional BOM for CSV.

Attachments are copied into managed `attachments/` under a generated ID; original filenames
are metadata, and source paths are not retained. Installation directories and the AppImage
mount are read-only. Restore rejects absolute paths, `..` traversal, symlinks, duplicate
entries, and oversized archives.

## 8. Security

The threat model covers a shared office computer, a stolen laptop, a tampered backup, and a
compromised dependency. Full-disk encryption is the primary protection at rest; OpenBill adds
permissions, integrity checks, and an app lock after 1.0.

| Platform | Secret storage | Fallback |
| --- | --- | --- |
| Windows | DPAPI, user scope | Secret-dependent features disabled with a clear message |
| Linux | Secret Service through libsecret | Passphrase-derived key (PBKDF2-HMAC-SHA256, random salt) with a verifier |

Minimal Linux sessions may lack a Secret Service. If the user declines the passphrase,
persisted secrets are disabled while billing, printing, and backup continue. The post-1.0 app
lock is a UI gate, not database encryption. Failed unlocks back off, and lock state survives
restart.

Release builds reject cleartext HTTP and use the system trust store with no custom trust
manager. Windows installers use Authenticode with timestamping; Linux artifacts ship with
detached GPG signatures and SHA-256 checksums. Restore verifies the manifest, entry
checksums, and SQLite integrity, and refuses unknown or newer formats. Logs redact invoice
contents, phone numbers, and attachment names. Dependencies are locked, scanned, and listed
in a bill of materials.

## 9. Packaging and release

Every package bundles a trimmed `jlink` runtime assembled by `jpackage`; no system Java is
required. Installer size is tracked, with a working expectation of 60 to 150 MB.

| Artifact | Tooling | Behavior |
| --- | --- | --- |
| Windows MSI | `jpackage` and WiX on Windows | Per-user by default, per-machine optional; upgrades preserve data |
| Linux DEB | `jpackage` on Ubuntu | Installs under `/opt/openbill` with launcher, entry, icons, license |
| Linux AppImage | `jpackage` and `appimagetool` | Runs without install; XDG data unless portable mode |

The MSI defaults to a per-user install; per-machine is an optional installer mode. Program
files and shortcuts stay in the user profile for per-user, and under `%ProgramFiles%\OpenBill`
for per-machine. User data remains per Windows user in both modes.

| MSI mode | Program files | User data |
| --- | --- | --- |
| Per-user (default) | User profile | `%LOCALAPPDATA%\OpenBill`; configuration in `%APPDATA%\OpenBill` |
| Per-machine (optional) | `%ProgramFiles%\OpenBill` | `%LOCALAPPDATA%\OpenBill`; configuration in `%APPDATA%\OpenBill`, per Windows user |

Upgrades preserve user data in both modes.

Packages include original icons, a desktop entry with `Office` and `Finance` categories, and
the Apache-2.0 license. Uninstall removes app files but not user data; data removal is
separate. No developer paths or machine-specific files may appear in artifacts.

GitHub Releases hosts the packages, `SHA256SUMS`, detached signatures, release notes, and
schema and backup versions. Versioning is semantic. Version 1.0 has no automatic update
checks: Help performs a user-initiated check against a signed manifest or opens Releases,
with no identifiers and no background polling.

A pre-migration backup is created before any schema upgrade. Migrations are forward-only, so
an older build refuses a newer schema and directs the user to that backup; downgrades never
rewrite the database in place.

## 10. CI and build matrix

Desktop CI is separate from Android CI but shares the Gradle build. It uses
`windows-latest` and `ubuntu-latest`; no job pretends to cross-compile.

| Job | Runner | Trigger | Output |
| --- | --- | --- | --- |
| Desktop tests | Ubuntu and Windows | Pull request, push | JVM and database tests; Compose UI on a Linux virtual display; package smoke build from D1 |
| Package Windows | Windows | Tag, manual | MSI with install smoke test |
| Package Linux | Ubuntu | Tag, manual | DEB and AppImage with install or extract smoke tests |
| Release | Ubuntu | Tag | Checksums, signatures, GitHub Release assets |

The JDK and WiX are pinned; upgrading either reruns packaging on both systems. A
`--self-test` mode creates a temporary database, posts an invoice, renders a PDF, checks
totals, and exits, proving a package starts without a developer environment. Pull-request
artifacts are retained 14 days, release candidates 90 days, release assets permanently.
Android jobs still gate release but stay separate, so desktop failures do not block Android
pull-request feedback.

## 11. Testing

| Area | Environment | Checks |
| --- | --- | --- |
| Clean install | Windows 11 x64 VM, standard user | MSI install, first run, invoice, print, backup, restore, uninstall, reinstall |
| Linux | Ubuntu 24.04 and 22.04 LTS; one non-Ubuntu glibc distribution | DEB and AppImage on Ubuntu LTS as the release gate; AppImage smoke test on the other distribution, best effort and not a release gate |
| Display servers | Wayland and X11 | Placement, fractional scaling, file and print dialogs |
| Printing | Physical and virtual printers | A4, A5, 58 mm, 80 mm, offline queue; silent print and bulk-print failure handling |
| Packaging | Clean VMs and CI | Signatures, checksums, no developer paths, read-only install |
| Keyboard and accessibility | All screens; Narrator and Orca | Tab order, focus, shortcuts, 200 percent text, high contrast |

Manual exploratory testing covers printing, installers, and assistive technology. Shared
layers are in [05-test-plan.md](05-test-plan.md); cross-platform checks include
Android-Windows-Linux backup round trips and equivalent PDF text and geometry.

## 12. Performance expectations

No disk, database, PDF, or network work runs on the UI thread. Queries use a database
dispatcher, file work IO, rendering a default dispatcher; long operations are cancellable.
Tables compose visible rows only, sorting and filtering run in SQL with indexes, and bulk
actions use IDs.

| Scenario | Initial budget |
| --- | --- |
| Cold start to interactive window, 4-core/8 GB SSD | p95 at or under 4 seconds |
| First page of a 50,000-invoice list | p95 at or under 500 ms |
| Sort or filter 50,000 invoices | p95 at or under 300 ms |
| Search at 500,000 invoices | p95 at or under 150 ms |
| Single A4 PDF | p95 at or under 500 ms |
| Steady memory at 100,000 invoices | At or under 512 MB |

Budgets are ratified in Phase 6; a regression above 20 percent needs a written explanation.
Bulk-print and bulk-export budgets are set during D3 and ratified in Phase 6.
PDFs write to a temporary file and move atomically, single-invoice printing spools one document
at a time, and migrations show progress with a backup rather than freezing the window.

## 13. Desktop milestones

| Milestone | Scope | Exit criteria | Depends on |
| --- | --- | --- | --- |
| D0 | Shared core on the JVM | Common, tax, and database tests pass on Linux and Windows | Phase 1 |
| D1 | Shell, navigation, single-instance lock, database, party/item lists; first package smoke build | Window opens, data directory is created, master data can be searched, smoke package builds | D0 |
| D2 | Invoice editor and PDF | Posted invoice matches Android; A4/A5 render; preview works | D1, Phase 2 |
| D3 | Printing through the system dialog, file pickers, backup and restore; silent print to the default printer, bulk print, bulk PDF/ZIP export, export notifications | A4/thermal print; silent and bulk print work with per-invoice failures; bulk zip exports; notification reveals the file; native open/save works; cross-platform restore works | D2 |
| D4 | MSI, DEB, and AppImage in CI | Tagged build produces all three; each installs or runs on a clean runner | D3 |
| D5 | Clean-VM tests and signed artifacts | Install, invoice, print, backup, restore, upgrade, and uninstall succeed; artifacts signed and checksummed | D4, Phase 6 |

D5 makes the desktop claim credible: a standard user on a clean machine completes the core
workflow without a developer, admin rights, or a network.

## 14. Desktop risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| JDK and `jpackage` drift, WiX changes | Broken installers near release | Pin both, package on relevant changes, install-smoke-test artifacts |
| Native access through JNA fails | Secure storage or printing unavailable | Small interfaces, documented fallbacks, clean-VM tests, prefer JVM APIs |
| Linux fragmentation | AppImage or dialogs fail on some distributions | Ubuntu LTS baseline, bundled runtime, Wayland and X11 tests, second-distribution check |
| Signing cost and SmartScreen reputation | Users abandon installation | Budget early, sign and timestamp every release, publish hashes and GPG signatures |
| Printer and driver variance | Clipped thermal output or failed batch jobs | Real-renderer preview, bundled fonts, physical printer matrix, per-job failures |
| Forward-only schema and downgrades | Older build cannot open newer data | Pre-migration backup, explicit refusal, rollback runbook, installer retention |

## 15. Open questions for the foundation sprint

| Question | Why it matters | Decide by |
| --- | --- | --- |
| Is Windows 10 supported for 1.0? | Test matrix and pinned toolchain | D4 |
| OpenPDF or PDFBox? | Font shaping, features, licensing | D2 |
| Is the Wayland chooser AWT-native, Swing, or portal-backed? | Usability and sandbox compatibility | D3 |
| Which signing certificate and key custody model are funded? | SmartScreen reputation and cost | D4 |

Answers become decisions in the implementation plan. Until then, the defaults here apply:
single window, no automatic update checks (a manual Help check only), no tray, no
cross-compilation, and no arm64 release promise.
