# Changelog

All notable changes to OpenBill. Versions follow the git tags; release notes live in
[docs/releases](docs/releases).

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
