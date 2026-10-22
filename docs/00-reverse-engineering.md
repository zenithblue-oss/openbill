# Reverse-Engineering Report

This document records the research that informs OpenBill's scope and architecture. It is a
behavioral and architectural study, not a code-reuse project.

## 1. Scope and method

### Targets

Two artifacts were studied: the Android application (primary) and the Windows desktop
installer (secondary, added 2026-10-22).

#### Android application

| Property | Value |
| --- | --- |
| Application | myBillBook (com.valorem.flobooks) |
| Version analyzed | 8.17.7 |
| Version code | 427 |
| Package format | XAPK (base APK plus ARM64 and xxhdpi splits) |
| `minSdkVersion` | 32 |
| `targetSdkVersion` | 37 |
| `compileSdkVersion` | 37 |
| Artifact source | User-supplied XAPK, analyzed locally |

### Artifact record

The analyzed artifact is recorded so the study can be reproduced against the same build:

- SHA-256: `028b151cfd463d819264c141ffcd80f5284c6174f576f2035e60b18fb28a082b`
- Size: 36,017,336 bytes
- Verified with Android SDK build-tools 36.1.0 `aapt2 dump badging` on 2026-10-21.
- Package metadata first line:

```text
package: name='com.valorem.flobooks' versionCode='427' versionName='8.17.7' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
```

- SDK levels from the artifact: minSdk 32, target 37, compile 37.

Public listing sites may state different minimum Android versions. The artifact metadata above is
authoritative for this study.

#### Windows desktop installer

| Property | Value |
| --- | --- |
| Application | myBillBook desktop (Windows) |
| Shell version analyzed | 0.0.77-beta.1 |
| Package format | PE32 (i386) Nullsoft NSIS 3.04 one-click per-user installer, built with electron-builder |
| Code signing | Authenticode, signer organization "Metaloop Innovation Labs Pte. Ltd" |
| Artifact source | Installer obtained from the vendor's public download page, analyzed locally |

- SHA-256: `95c0e558ec9e74b0eb4aedc9f53efae17d9d0b066cf7cd1a8d10e2e53c91fab1`
- Size: 86,729,720 bytes
- Analyzed on 2026-10-22. The study covers the installer and the shell it carries; it does
  not cover the web application that the shell loads (see the limitations below).

### Allowed methods

- Static inspection of the manifest, resources, and decompiled bytecode.
- Public product pages, Play Store listing, help-center articles, and pricing pages.
- Unpacking an installer downloaded from the vendor's public download page and reading its
  application resources and compiled shell code (Windows target).
- Manual reasoning about user-visible workflows from public documentation.

### Methods not used

- No interception or decryption of production network traffic.
- No authentication bypass, credential extraction, or account access.
- No dynamic instrumentation of another party's app.
- No scraping of private APIs or bulk downloading of user data.
- No fetching of the desktop shell's over-the-air web frontend bundle, and no requests to
  the vendor's version documents, update feeds, or content delivery hosts.
- No launching of the desktop shell against the vendor's services.
- No reuse of proprietary source, assets, themes, strings, or database schema.

### Toolchain

| Tool | Purpose |
| --- | --- |
| `aapt2` | Manifest, permissions, and package metadata |
| `apktool` 3.0.3 | Resource and manifest decoding |
| `jadx` 1.5.6 | Java decompilation with deobfuscation |
| Python and shell tooling | HTML text extraction, inventory, and search |
| `py7zr` | Carving and reading the 7z payload embedded in the NSIS installer (Windows target) |
| `@electron/asar` 3.2.17 | Unpacking the Electron application archive (Windows target) |

The full APK and decompiled output are kept in a private research workspace and are not
distributed with this repository.

### Limitations

- The application is obfuscated; many classes have short machine-generated names and names
  are partially recovered. Decompilation produced hundreds of recoverable errors, so control flow in a
  few methods is approximate.
- Resource decoding succeeded; full smali decoding was incomplete. Conclusions therefore
  rely on the manifest, resources, and decompiled Java sources rather than smali.
- No runtime profiling, database inspection, or network capture was performed. Statements
  about server behavior are limited to client-visible interface categories, not observed
  runtime behavior.
- The analysis captures one version. Later releases may change behavior.
- Windows target: native binaries inside the installer payload use BCJ2-compressed 7z
  folders and were not extracted; only the application resources were read. The Electron
  runtime itself is a stock component and is out of scope.
- Windows target: the shell downloads its web frontend bundle at run time. That bundle was
  deliberately not fetched, in line with [06-clean-room-policy.md](06-clean-room-policy.md),
  so nothing is known about its code beyond what the shell reveals about how it is loaded.
- Windows target: no runtime execution, network capture, or account use. Behavior is inferred
  from the shell's compiled main-process code and package metadata.

## 2. Application fingerprint

### Platform and UI

- Kotlin application with Jetpack Compose and a Material 3 design system.
- AndroidX Activity, Fragment, Navigation, Lifecycle, and Data Binding are all present.
- Compose is used for newer screens; XML layouts and View Binding remain for older ones.
- Single-activity shell plus onboarding and splash activities.
- A server-driven UI module is present, suggesting some screens or campaigns are
  configured remotely.

### Libraries and services observed

| Area | Components |
| --- | --- |
| UI | Jetpack Compose, Material 3, View Binding, ConstraintLayout, Lottie |
| DI | Hilt/Dagger generated code |
| Local data | Room, Paging 3, DataStore or preference storage, WorkManager |
| Networking | OkHttp, Retrofit-style interfaces, Moshi JSON models |
| Images | Coil, Glide, or both; image CDN assets |
| Auth and messaging | Firebase Authentication components, Firebase Cloud Messaging |
| Analytics and growth | CleverTap, Singular, Google Ads/attribution components |
| Experimentation | GrowthBook-style feature flags |
| Deep links | Branch deep links |
| Payments | Razorpay checkout |
| Maps | MapLibre-style map components |
| SDUI | Server-driven UI resources |
| Debugging | Chucker, LeakCanary initializer, Timber, StrictMode initializer |

The presence of debug tooling and a cleartext-traffic flag in the analyzed build is a
quality signal, not something OpenBill should imitate. OpenBill will ship release builds
with debugging tools excluded and cleartext traffic disabled by default.

### Permissions

The manifest requests a broad set of permissions:

- Network and Wi-Fi state, internet access.
- Contacts read and write.
- Camera, microphone, and audio settings.
- Bluetooth scan, advertise, and connect.
- Fine and coarse location.
- Notifications, exact alarms, wake lock, boot completed, foreground service.
- NFC, biometric and fingerprint authentication.
- Media images and legacy external storage.
- Advertising ID and ad-services attribution.
- Install-referrer and license-check permissions.
- A Truecaller SDK permission.

OpenBill's policy is to request permissions lazily and narrowly:

- Contacts access is optional and used only to import a party the user selects.
- Camera is used only for receipts or logos, with a system photo picker preferred.
- Bluetooth is used only for thermal printing, never for background scanning.
- Location is not required for core billing.
- No advertising ID or attribution SDK in the default build.
- No contact upload to a server.

## 3. Architecture observations

### Module map

The decompiled package tree contains roughly 57 top-level feature and platform packages.
The most relevant groups are summarized below by area; internal package names are
intentionally not reproduced:

| Area | Observed responsibility |
| --- | --- |
| Core and shared UI | Shared entities, database, networking, utilities, design primitives, Compose components |
| Onboarding, home, dashboard | First-run flow and main shell |
| Parties | Party master, ledgers, shared party views |
| Items | Item master, categories, units, HSN/SAC, stock |
| Vouchers | Invoice and voucher editing, listing, and viewing |
| Reports | Report screens and exports |
| Expenses | Expense vouchers |
| Accounts | Accounts, bank accounts, and recycle bin |
| E-invoice and e-way bill | Compliance flows |
| Shared ledger | Accountant or shared ledger portal |
| WhatsApp marketing | Marketing surfaces |
| Themes and printing | Invoice themes, thermal and standard output |
| Bulk upload and exports | Import, Tally export, and accountant export |
| Multi-user, settings, pricing | Access control, configuration, plans |
| Syncing, workers, notifications | Background sync and messaging |
| Staff attendance and payroll | Attendance, payroll, and a companion app |
| Experiments | Server-driven configuration and feature flags |
| CRM, referral | Growth and retention features |
| Foreign currency | Multi-currency groundwork |
| Cash and bank accounts | Shared module for cash and bank ledgers |

The breadth confirms that billing is only one part of the product. The defensible core for
OpenBill is the ledger plus tax correctness plus reliable output, not the marketing stack.

### Data model concepts observed

The app models at least the following domain concepts. These are generic accounting
concepts, not a copied schema:

- Firm/company, company settings, financial year.
- Party/contact with billing and shipping addresses, GSTIN, and balances.
- Item, item category, unit, HSN/SAC code, godown, batch, serial number.
- Voucher with type (invoice, quotation, credit note, debit note, purchase, payment,
  expense), lines, taxes, discounts, charges, and attachments.
- Payment allocation against vouchers.
- Stock movement derived from posted vouchers.
- Audit and recycle-bin records.
- User, role, and device/session metadata.

Room is used for local relational data, including a cached contact directory. Paging 3 is
used for large lists. The exact proprietary schema is not reproduced here and must not be
copied into OpenBill.

### API surface observed

At a category level, client interfaces cover item master, inventory search, HSN/SAC lookup,
voucher listing and editing, invoice payments, company reporting, and recurring invoices. No
private endpoint paths or hostnames are reproduced in this public report.

OpenBill will define its own backend contract if and when sync is implemented. It will not
depend on, proxy, or impersonate the observed service.

### Search-related components

The app contains dedicated classes for item search, HSN/SAC search, contact search, and
invoice search, plus a shared debounce pattern. The detailed evidence and behavior are in
[02-search-analysis.md](02-search-analysis.md).

## 4. Windows desktop application

This section describes behavior and architecture only. Private endpoint addresses, service
identifiers, keys, and internal message names are intentionally omitted; they are described
generically ("a cloud-hosted version document", "a vendor CDN").

### Conclusion

The Windows application is a thin Electron shell around the vendor's web application. It
contains no local business logic and no local database. Invoices, parties, items, and
reports all live on the vendor's servers and are reached through the same web frontend that
runs in a browser. It is a cached copy of the website with a native window and a handful of
OS integrations, not a native or offline-capable desktop product.

### Startup and over-the-air frontend

1. A splash window opens first. The web renderer reports whether the machine is online.
2. The shell reads a version string from a cloud-hosted version document.
3. If it differs from the version recorded in a local file, the shell downloads a zip of the
   web frontend bundle from a vendor CDN and extracts it into the per-user application data
   folder. Downloads retry up to five times.
4. The shell loads the extracted `index.html` from local disk.

Consequences: the frontend can be updated without reinstalling the shell; the first run
requires internet access; every data operation needs the server, so the application is
online-only in practice. The bundle is trusted on the strength of its version string
and transport alone; no signature check on the downloaded bundle was observed.

### Shell auto-update

The shell itself updates through a generic-provider update feed on a beta channel, with a
separate feed per 32-bit and 64-bit build. Downloads are not automatic: the shell notifies the
user, the user chooses to update now or be reminded later, and the shell re-checks
periodically. A DNS lookup of the vendor domain serves as the connectivity check.

### Capabilities the shell adds over the website

The web frontend reaches the host only through a narrow bridge. The capabilities are:

| Capability | Behavior |
| --- | --- |
| Direct PDF print | Downloads an invoice PDF and prints it silently to the default printer (A4, portrait, fit to page), with an optional print dialog |
| HTML print | Renders supplied HTML in a hidden window and prints it with caller-supplied options |
| Bulk invoice download | Sends a list of voucher identifiers to the server and saves the returned zip of invoices to the Downloads folder, de-duplicating names such as "name(1).zip" |
| Download notifications | Saves a file to Downloads, raises an OS notification, and reveals the file in its folder when the notification is clicked |
| External links | Opens links in the default browser rather than inside the app window |
| Small file save and read | Writes and reads small files inside the application data folder |
| Connectivity and progress | Reports online status to the shell and surfaces download progress and errors to the page |

Silent printing is implemented with a bundled Windows PDF-printing helper, which is why the
feature is Windows-specific. Bulk download and bulk print are the features the vendor gates
behind its Pro plan, alongside the desktop application itself.

### Window and resilience behavior

- Single-instance lock; a second launch focuses the existing window.
- Maximized to the work area, no menu bar, not resizable in the production build.
- A keyboard shortcut opens developer tools even in production builds.
- If the renderer crashes, the shell reloads it up to five times, then shows an error
  telling the user to use the website instead.
- Offline: the cached bundle can start the interface, but data access needs the network.

### Telemetry and logging

Production builds send crash reports to a third-party error-reporting service. Local file
logging is capped at a few megabytes.

### Packaging

- One-click, per-user NSIS installer (no admin rights, no install-location choice), signed
  with Authenticode, in 32-bit and 64-bit variants.
- The build configuration also lists macOS disk-image and Linux AppImage targets, but only
  Windows is shipped and marketed. Linux is never mentioned publicly.
- Installer size is about 87 MB, dominated by the bundled Electron runtime.

### Security observations

Phrased generically, as an improvement list for OpenBill rather than a vulnerability report:

- Developer tools are reachable in production builds.
- Code that is downloaded at run time and loaded from local disk is verified only by a version
  comparison; signed bundles would be stronger.
- Relaxed web-security settings are limited to development, which is the correct posture.
- A shell that is a pure web wrapper makes every privacy property depend on the vendor's
  servers; OpenBill's local-first design avoids that class of exposure.

## 5. Product and market notes

At research time the product used tiered subscriptions (Plus, Pro, and Pro Max) with annual
discounts. Exact prices and plan names change frequently and are not authoritative here. See
the [myBillBook pricing page](https://mybillbook.in/pricing-plans) (accessed 2026-10-22).
The earlier `/pricing/` link now returns only a page title and should not be relied on.

Public facts recorded on 2026-10-22 from the pricing and product pages:

| Plan | Price (annual) | Notable inclusions |
| --- | --- | --- |
| Free | None | Mobile only, usage-limited |
| Plus | INR 3,490 per year | One business, one user plus one CA seat, themes, online store, A4 barcodes |
| Pro | INR 3,990 per year | Adds the Desktop app, SMS and WhatsApp marketing (500 messages a year), unlimited godowns, label-printer barcodes, bulk download and bulk print |
| Pro Max | From about INR 570 per month, billed annually | Two businesses, three users plus one CA seat, e-way bill, e-invoice, loyalty, recurring billing, POS billing on desktop and web, Tally export on request, user activity tracker |

- A seven-day money-back guarantee is offered.
- Platforms advertised: Android, iOS, web, and Windows 10/11. macOS is mentioned vaguely;
  Linux is never mentioned. The product requires internet access and makes no offline claim.
- Features on the vendor's public pages that were thin or absent in OpenBill's early
  inventory, now triaged in [01-feature-inventory.md](01-feature-inventory.md): purchase orders, delivery challans with e-way bill, e-invoice for
  sales returns, bulk e-invoice and e-way generation, e-way expiry alerts, FEFO batch
  suggestion, near-expiry and reorder alerts, POS extras (item-first billing, several
  simultaneous bills, keyboard quick commands, cash drawer and weighing scale, restaurant
  tables, multi-tender payment), A3 print size, payroll, loyalty points, GSTR-1/2/3B views,
  day book and balance sheet, industry templates, and migration from other billing software.
  AI-assisted features such as OCR of purchase bills and bank reconciliation are marketing
  surface, not foundation (see section 6).

The Play Store listing reported approximately 4.7 stars and 1.49 lakh reviews as of 2026-10-21
([listing](https://play.google.com/store/apps/details?id=com.valorem.flobooks)). These
numbers are market context, not targets.

The Windows desktop application is a web wrapper, not a native or offline product (section 4),
and it is gated to the Pro plan.

Observed strengths worth matching:

- Fast invoice creation with a small number of taps.
- Offline-first behavior with background sync.
- A broad report catalogue and accountant-friendly exports.
- Thermal printing and barcode labels, which matter more than flashy UI in this market.
- Clear separation between quotation, invoice, payment, and credit note workflows.

Observed friction worth improving:

- Broad permissions and advertising/attribution SDKs in a financial app.
- Paid tiers gate features that are inexpensive to run locally, such as desktop access and
  data export.
- Search is fragmented across screens; there is no single command surface.

## 6. Implications for OpenBill

1. **The ledger is the hard part.** Invest early in a correct, auditable voucher and stock
   model. UI can be iterated; bad books destroy trust.
2. **Build search once, use it everywhere.** A shared FTS layer beats per-screen filters.
3. **Ship desktop from the same core.** A Kotlin Multiplatform core avoids a second
   implementation of tax and accounting rules.
4. **Keep permissions minimal.** Import contacts on demand; never upload the address book.
5. **Treat export and backup as core features.** They are differentiators against closed
   competitors and reduce lock-in.
6. **Do not chase the marketing stack.** WhatsApp campaigns, storefronts, and AI OCR are
   integrations, not the foundation.
7. **A native, offline desktop app is a differentiator.** The competitor's desktop product
   needs the internet for all data and sits behind a paid tier. OpenBill's desktop app runs
   the full ledger locally, works with no account, and also supports Linux.
8. **Desktop parity items to match natively:** silent printing to the default printer, bulk
   PDF/ZIP export of selected invoices, bulk print, download and export notifications with
   show-in-folder, single-instance behavior, and signed auto-update. Exceed them with
   signature-verified updates, no developer tools in release builds, and offline-first
   operation. Details are in [07-desktop.md](07-desktop.md).

## 7. Evidence summary

Behavioral evidence supporting this report:

- Local paged contact lookup with a substring match over display name and phone number,
  ordered by name.
- Paged item search with SKU, category, stock, batch, and serial filters.
- HSN/SAC lookup with a dedicated result path and a selection bottom sheet.
- Voucher search filters for type, status, date range, and transaction mode.
- A 300 ms debounce on the dashboard search field.
- A search analytics event recording query, result count, page, and source.
- Searchable selection bottom sheets for units and categories.

Raw evidence, including the APK and decompiled output, is retained privately and is not
distributed. Unnecessary decompiled identifiers are intentionally omitted from this public
report.

## 8. Legal and ethical position

- This report is an independent analysis for interoperability and product research.
- No proprietary code, resources, or assets are distributed with OpenBill.
- The OpenBill implementation must be written from specifications and public accounting
  knowledge, not from decompiled source.
- "myBillBook" is used only to identify the product being compared. It is not part of the
  OpenBill name, package, logo, or branding.
- Contributors must follow [06-clean-room-policy.md](06-clean-room-policy.md).

### Source and publication gate

- External claims must be dated and sourced.
- No private endpoints, secrets, tokens, or unnecessary decompiled identifiers may be
  published.
- Raw APK and decompiled output stay private and are not distributed.
