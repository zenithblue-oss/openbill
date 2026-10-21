# Reverse-Engineering Report

This document records the research that informs OpenBill's scope and architecture. It is a
behavioral and architectural study, not a code-reuse project.

## 1. Scope and method

### Target

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

### Allowed methods

- Static inspection of the manifest, resources, and decompiled bytecode.
- Public product pages, Play Store listing, help-center articles, and pricing pages.
- Manual reasoning about user-visible workflows from public documentation.

### Methods not used

- No interception or decryption of production network traffic.
- No authentication bypass, credential extraction, or account access.
- No dynamic instrumentation of another party's app.
- No scraping of private APIs or bulk downloading of user data.
- No reuse of proprietary source, assets, themes, strings, or database schema.

### Toolchain

| Tool | Purpose |
| --- | --- |
| `aapt2` | Manifest, permissions, and package metadata |
| `apktool` 3.0.3 | Resource and manifest decoding |
| `jadx` 1.5.6 | Java decompilation with deobfuscation |
| Python and shell tooling | HTML text extraction, inventory, and search |

The full APK and decompiled output are kept in a private research workspace and are not
distributed with this repository.

### Limitations

- The application is obfuscated; many classes appear as `p000.*` and names are partially
  recovered. Decompilation produced hundreds of recoverable errors, so control flow in a
  few methods is approximate.
- Resource decoding succeeded; full smali decoding was incomplete. Conclusions therefore
  rely on the manifest, resources, and decompiled Java sources rather than smali.
- No runtime profiling, database inspection, or network capture was performed. Statements
  about server behavior are limited to client-visible interface categories, not observed
  runtime behavior.
- The analysis captures one version. Later releases may change behavior.

## 2. Application fingerprint

### Platform and UI

- Kotlin application with Jetpack Compose and a Material 3 design system.
- AndroidX Activity, Fragment, Navigation, Lifecycle, and Data Binding are all present.
- Compose is used for newer screens; XML layouts and View Binding remain for older ones.
- Single-activity shell plus onboarding and splash activities.
- Server-driven UI components are present under a Pulse-style module, suggesting some
  screens or campaigns are configured remotely.

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
| SDUI | Pulse server-driven UI resources |
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
The most relevant groups are:

| Package | Observed responsibility |
| --- | --- |
| `core` | Shared entities, database, networking, utilities, design primitives |
| `core_compose_ui` | Compose components and shared UI |
| `onboarding`, `home`, `dashboard` | First-run flow and main shell |
| `party`, `parties`, `partyshared` | Party master, ledgers, shared party views |
| `item`, `items` | Item master, categories, units, HSN/SAC, stock |
| `vouchers`, `voucher` | Invoice and voucher editing, listing, and viewing |
| `reports` | Report screens and exports |
| `expense` | Expense vouchers |
| `account` | Accounts, bank accounts, and recycle bin |
| `einvoice`, `ewaybill` | Compliance flows |
| `sharedledger` | Accountant or shared ledger portal |
| `wamarketing` | WhatsApp marketing surfaces |
| `themes`, `thermalPrinter`, `print` | Invoice themes and output |
| `bulkUpload`, `tallyexport`, `caexport` | Import and export |
| `multiuser`, `settings`, `pricing` | Access control, configuration, plans |
| `syncing`, `workers`, `notifications` | Background sync and messaging |
| `sam`, `experiment`, `pricing` | Server-driven configuration and plans |
| `crm`, `referral`, `bulkUpload` | Growth and retention features |
| `foreignCurrency` | Multi-currency groundwork |
| `cab`, `cabshared` | Internal shared module (naming unclear from static analysis) |

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

## 4. Product and market notes

At research time the product used tiered subscriptions (Plus, Pro, and Pro Max) with annual
discounts. Exact prices and plan names change frequently and are not authoritative here. See
the [myBillBook pricing page](https://mybillbook.in/pricing/) (accessed 2026-10-21).

The Play Store listing reported approximately 4.7 stars and 1.49 lakh reviews as of 2026-10-21
([listing](https://play.google.com/store/apps/details?id=com.valorem.flobooks)). These
numbers are market context, not targets.

A Windows desktop application exists; its native-versus-wrapper implementation was not
assessed.

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

## 5. Implications for OpenBill

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

## 6. Evidence summary

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

## 7. Legal and ethical position

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
