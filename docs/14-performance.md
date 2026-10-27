# 14 · Performance at scale

Measured on 2026-10-27 on an ARM64 phone running Linux under PRoot (about 11 GB RAM, thermally throttled). CLI runs used `-Xmx1g`. Wall time and peak RSS come from `/usr/bin/time`.

## Dataset

Generated with the hidden `openbill dev` commands (`cli/.../DevCommands.kt`). The generator is seeded, posts through the real repositories and `PostVoucher` / `MoneyVouchers` in 5k-voucher transactions, and can resume (`--max` per run).

| Firm | Parties | Items | Sales invoices | Purchase bills | Payments |
|---|---|---|---|---|---|
| Scale Traders | 50,000 (CSV import) | 20,000 (CSV import) | 1,000,000 posted, 2–5 lines | 200,000 | 300,000 |
| Small Shop | 300 | 100 | 2,000 | 400 | 600 |

Database size: 7.27 GB, about 4.8 KB per voucher including lines, journal, stock movements and indexes.

## Results

| Operation | Wall | Peak RSS | Notes |
|---|---|---|---|
| CSV import, 50k parties | 19.0 s | 231 MB | |
| CSV import, 20k items | 10.1 s | 206 MB | |
| Generate 1.5M vouchers | 92.5 min | 464 MB | 30 chunks, steady about 330 vouchers/s |
| `db check` | 535 s | 66 MB | all checks ok |
| `backup create` | 730 s | 90 MB | 1.62 GB zip |
| `backup verify` | 317 s | 67 MB | |
| `backup restore` (empty dir) | 635 s | 80 MB | |
| `backup create --encrypt` | 821 s | 313 MB | |
| `backup verify` (encrypted) | 325 s | 344 MB | |
| Day book, one month | 4.3 s | 502 MB | see report table below |
| GSTR-1, one month | 4.9 s | 586 MB | see report table below |
| `invoice list --search` by party | 4.9 s | 81 MB | paged SQL |
| `invoice list --search` by number | 3.7 s | 81 MB | |
| `invoice list` newest 50 | 1.3 s | 60 MB | |
| `invoice create --post` at 1M (next number) | 2.0 s | 146 MB | whole CLI process, INV-500001 |

JVM startup accounts for about 1 s of each CLI time.

### Reports at 1M invoices, `-Xmx512m`

Before = 0.3.0 schema v10 code; after = streamed registers and Tally, SQL ageing, covering stock index (schema v11). Times vary with the page cache on this device (the same build measured 12 to 40 s for stock summary before the index), so after shows the cold and the warm run.

| Report | Before | After (cold / warm) | Peak RSS after |
|---|---|---|---|
| Sales register, full year (CSV) | out of memory | 55.4 s / 23.2 s | 537 MB |
| Profit & loss, full year | 70.2 s | 18.9 s / 4.9 s | 416 MB |
| Outstanding / ageing | out of memory | 26.1 s / 5.5 s | 282 MB |
| Stock summary, full year | 35.6 s (14 to 41 s) | 3.8 s / 3.6 s | 228 MB |
| `export tally`, one month | out of memory | 9.7 s / 9.0 s | 540 MB |
| Day book, one month | 3.7 s (3.7 to 5.2 s) | 4.2 s / 4.2 s | 499 MB |
| GSTR-1, one month | 4.9 s (4.9 to 5.7 s) | 4.7 s / 4.9 s | 582 MB |

`db migrate` 10 to 11 on the 7.3 GB database: 813 s, almost all of it the automatic pre-migration backup; the index build itself takes about 35 s. Peak RSS above the 512 MB heap is native SQLite and JVM overhead.

Old and new CLI output is byte-identical on a small book (3,000 sales, 600 purchases, 900 payments): 21 files covering every register, outstanding, stock, P&L, day book, low stock, cash book, GSTR-3B, GSTR-1 JSON and Tally XML for a full year and a mid-month range.

Desktop: the uber jar opens on the 7 GB database (invoice list, stock card, status counts). Long reports and exports show a progress overlay: a bar with percent and "x of y" when the total is known (stock walk rows, weeks of vouchers, bytes for backups), otherwise an indeterminate bar with the stage. Reports and exports can be cancelled; backup, restore and the start-up migration cannot.

## Index audit (EXPLAIN QUERY PLAN)

Version 10 (`MIGRATION_9_10`, tested in `DatabaseSchemaTest.schemaV9MigratesToV10WithScaleIndexes`) changes two indexes:

- `vouchers(firmId, partyId, type, status, date)` replaces `(firmId, partyId)`. Allocating a payment and loading a party's open invoices used to scan every posted sale for the firm: the generator slowed from 260 vouchers/s and kept degrading. With the new index it holds steady at about 330/s.
- `journal_entries(accountId, date, createdAt)` replaces `(accountId)`, so account ledgers page newest-first without a sort.

Query changes: the party ledger joins `vouchers CROSS JOIN journal_entries` so SQLite starts from the party's vouchers. Voucher pages use `+v.type IN (...)` so the planner keeps the `(firmId, date)` index.

## Fixes

- Lists are paged with a growing `LIMIT` (`core-ui/.../components/Paging.kt`, 200 rows per step): invoices, parties, items, account ledger, money. Totals and status chips come from `GROUP BY` aggregates (`observeVoucherStats`, `observeAccountNet`), not from loading every row.
- `RoomVoucherRepository` loads lines, charges and portions in batches (`IN` lists) instead of one query per voucher.
- Stock positions and period totals are computed by a streaming cursor walk (`StockValuation.Walker`), not by loading every movement.
- `loadReportData(kind)` loads only what a report needs. Stock summary, low stock and P&L use aggregates.
- Party balances use one grouped query (`observePartyBalances`).
- Registers and Tally XML stream a week of vouchers at a time and write straight to the output (`StreamedReports.kt`); Tally makes two passes because ledger masters come first.
- Outstanding ages balances from per-bucket SQL sums (`partyAgeingSums`). FIFO settlement always leaves the newest amounts of the balance's sign open, so the bucket sums give the same result as the old walk; `PartyAgeingTest` checks this against the walk on 2,000 random ledgers.
- Schema v11 replaces `stock_movements(firmId, itemId, godownId, date)` with a covering `(firmId, itemId, date, createdAt, id, godownId, quantity, unitCost)`, so the costing walk reads the index in order with no sort and no table lookups (3.1 s instead of 10.6 s for the SQL alone).

## Extrapolation

Generation, `db check` and backup times scale linearly with database size. Expect about 5 minutes per 100k vouchers on this device, and about 1/10 of that on a desktop with an SSD. Paged list and search times depend on page size, not on data size.

## Blockers for 1.0

1. The desktop report table keeps every row in memory, so a full-year sales register (500k rows) on screen needs more than the app's heap. CSV export streams; the screen needs paging.
2. The item-profit report, the Outstanding and ageing screens, and the invoice editor's party and item pickers still load full tables.
3. `db check` and backup take 5 to 14 minutes at 7 GB, and every schema upgrade first makes a full backup (13.5 minutes here). They now show progress; an incremental check would still help.
4. Android start-up still runs the pre-migration backup and migration without the progress screen (desktop has it).
