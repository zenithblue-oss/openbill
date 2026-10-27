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
| Day book, one month | 4.3 s | 502 MB | |
| GSTR-1, one month | 4.9 s | 586 MB | |
| Stock summary, full year | 54.1 s | 246 MB | streamed weighted-average walk |
| Profit & loss, full year | 86.3 s | 427 MB | before: out of memory |
| Outstanding / party ageing | 36.8 s | 1143 MB | just below the heap limit |
| Sales register, full year | — | out of memory | **blocker** |
| `export tally`, one month | — | out of memory | **blocker**, loads the whole journal |
| `invoice list --search` by party | 4.9 s | 81 MB | paged SQL |
| `invoice list --search` by number | 3.7 s | 81 MB | |
| `invoice list` newest 50 | 1.3 s | 60 MB | |
| `invoice create --post` at 1M (next number) | 2.0 s | 146 MB | whole CLI process, INV-500001 |

JVM startup accounts for about 1 s of each CLI time.

Desktop: the uber jar opened on the 7 GB database and reached its PIN lock screen. Scrolling and search were not checked because the test profile was locked. The paged queries behind those screens are the same ones the CLI timings above use.

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

## Extrapolation

Generation, `db check` and backup times scale linearly with database size. Expect about 5 minutes per 100k vouchers on this device, and about 1/10 of that on a desktop with an SSD. Paged list and search times depend on page size, not on data size.

## Blockers for 1.0

1. The full-year sales register and `export tally` hold every voucher (or the whole journal) in memory and run out of memory at 1M invoices. They need to stream rows to the writer.
2. Outstanding and party ageing peak at 1.1 GB: they still load all AR/AP rows.
3. The item-profit report, the Outstanding and ageing screens, and the invoice editor's party and item pickers still load full tables.
4. `db check` and backup take 5 to 14 minutes at 7 GB. They need progress reporting, and maybe an incremental check.
