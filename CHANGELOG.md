# Changelog

All notable changes to OpenBill. Versions follow the git tags; release notes live in
[docs/releases](docs/releases).

## 1.0.1 - 2026-10-27

Update notice and What's new; see [docs/releases/v1.0.1.md](docs/releases/v1.0.1.md).

- Update notice (desktop): once a day OpenBill asks GitHub for the latest release number and shows
  a dismissible banner when a newer version exists. Nothing is downloaded or installed. Turn it
  off under More > About > Check for updates. Android has no network permission, so it never
  checks and hides the setting.
- What's new: after an update, the changes for the running version are shown once. Reopen them
  any time from More > About > What's new. Fresh installs do not show it.

## 1.0.0 - 2026-10-27

First stable release. Scale work on top of 0.3.0-alpha; see
[docs/releases/v1.0.0.md](docs/releases/v1.0.0.md) and [docs/14-performance.md](docs/14-performance.md).

- Scale: tested at 1M sales invoices. Paged lists, scale indexes (schema v10), a covering index
  for the stock costing walk (schema v11).
- Reports: sales/purchase registers and Tally XML are streamed, outstanding/ageing is computed
  in SQL; every report runs under a 512 MB heap. On-screen registers are paged.
- Progress overlay for long reports, exports, backup, import and start-up; reports and exports
  can be cancelled. Counts use Indian grouping.
- Upgrading from schema 10 or earlier makes an automatic backup first, which dominates the
  migration time on very large books.
- Fix: ship-to GSTIN in one-line ship-to rows on PDFs.
- Fix: desktop restore no longer leaves the app running on a closed database.
- CLI: hidden `openbill dev` commands to generate scale fixtures.
- Database schema version 11.

## 0.3.0-alpha - 2026-10-27

New package id `com.zenithblue.openbill`: back up in the old app, install this, restore. See
[docs/releases/v0.3.0-alpha.md](docs/releases/v0.3.0-alpha.md).

All 136 P0 features of the 1.0 scope are in ([ROADMAP.md](ROADMAP.md)).

- Parties: several shipping addresses per party, each with its own ship-to GSTIN (printed in
  the Ship to block); editable party notes; attachments on parties.
- Items: optional alternate unit with conversion (enter bill quantities in it, stock stays in
  the primary unit); an item tracks batches or serial numbers, not both.
- Reports: low-stock report (app and `openbill report low-stock`).
- Security: app lock (PIN, biometric on Android) with persisted exponential backoff on wrong
  PINs; desktop can lock again when idle or minimised (setting). Password-encrypted backups.
- Data: erasing a business deletes its rows, audit log, attachment files, logo and signature.
- Printing: ESC/POS thermal receipts over Bluetooth, print services and serial ports, with an
  optional serial baud rate (Linux, via `stty`); silent print; OS notification after bulk
  export or print.
- GST: place of supply follows the default ship-to state; can still be set by hand.
- Fix: reopened draft lines offer the alternate-unit conversion.
- Database schema version 9 (migrates from every earlier version).

## 0.2.0-alpha - 2026-10-26

Inventory (batches, serials, godowns, transfers, valuation, labels, scanning), all document
types, money vouchers and reconciliation, reports and GST (GSTR-1 JSON, GSTR-3B, HSN, Tally
XML, books lock, audit log), CSV import, verified backups with daily automatic copies, several
businesses, desktop command palette and portable mode, and the `openbill` CLI. See
[docs/releases/v0.2.0-alpha.md](docs/releases/v0.2.0-alpha.md).

## 0.1.3-alpha - 2026-10-23

Branding on the setup screens, sharper PDF zoom, centred navigation rail.

## 0.1.2-alpha - 2026-10-23

Animated onboarding and the Material 3 navigation rail.

## 0.1.1-alpha - 2026-10-22

Invoice PDF templates, business logo and the PDF viewer; desktop packages for x64 and arm64.

## 0.1.0-alpha - 2026-10-22

First alpha: GST tax engine, money and rounding primitives, Room database with migrations,
voucher posting and cancellation, stock derivation, payments and party ledger, audit guards
and recycle bin, and the billing UI on Android and desktop.
