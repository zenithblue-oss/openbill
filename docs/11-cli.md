# openbill CLI

`openbill` is a command-line client for OpenBill. It shares the accounting core with the
apps and works either directly on a local database or backup file, or remotely against an
OpenBill Server ([10-server.md](10-server.md)). Scope and milestones follow
[ROADMAP.md](../ROADMAP.md); modules follow [03-architecture.md](03-architecture.md).

## 1. Purpose

- Automation: nightly backups, scheduled exports, and bulk imports without a GUI.
- Accountant workflows: run reports and GST exports on a backup file received from a
  client, with no app installed and no data imported.
- Testing: drive the real domain layer from scripts for cross-platform backup tests,
  large-fixture generation, and golden-output regression tests.
- Administration of an OpenBill Server (users, devices, tokens).

The CLI is cheap because it is a thin shell over `core-domain` use cases that the apps need
anyway. It has no business logic of its own.

Non-goals:

- Raw SQL writes or any command that bypasses domain validation. Read-only inspection of a
  backup with `sqlite3` remains possible and is documented.
- Editing posted vouchers. The CLI offers the same cancel, restore, and correcting-note
  paths as the apps.
- An interactive text UI. Prompts exist only for confirmation on a terminal.
- Opening an Android app's private database directly. Android data reaches the CLI through
  a backup archive or the server.

## 2. Modes

| Mode | Selected by | Data source | Notes |
| --- | --- | --- | --- |
| Direct (database) | `--data-dir PATH`, or the desktop app's default data directory | Local SQLite database via `core-data` | Same single-writer lock as the desktop app |
| Direct (backup, read-only) | `--backup FILE.openbill.zip` | Archive verified and extracted to a temporary directory | Writes are rejected; temporary copy deleted on exit |
| Remote | `--server URL` or a named context | OpenBill Server REST API with an API token | Server enforces roles; requires milestone S3 |

Direct mode is part of 1.0. Remote mode ships with server milestone S3.

Locking in direct mode:

- Write commands take the same database lock as the desktop app. If the app is running,
  they fail with exit code 6 and a message naming the lock holder.
- Read commands open the database read-only and work while the app is running (SQLite WAL).
- Commands that change the schema (`db migrate`, `backup restore`) require exclusive access.

Contexts and credentials:

- `~/.config/openbill/cli.toml` (Linux) or `%APPDATA%\OpenBill\cli.toml` (Windows) stores
  named contexts: data directory or server URL, default firm, and output preferences.
- API tokens are read from the platform secure store, `OPENBILL_TOKEN`, or
  `--token-file`/`--token-stdin`. There is no `--token VALUE` flag, to keep tokens out of
  shell history and process lists.
- Firm selection: `--firm <id|gstin|name>` or the context default. Commands that touch firm
  data fail with a usage error if the firm is ambiguous.

## 3. Command tree

```text
openbill
  context    list | show | use <name> | set | remove
  firm       list | show | create | update | use
  party      list | show | create | update | archive | ledger | statement
  item       list | show | create | update | archive
  stock      summary | ledger | adjust | transfer
  voucher    list | show | create | update | post | cancel | restore
             | delete | recycle-bin | purge | duplicate | print
  invoice    alias of `voucher --type sales-invoice`
  payment    in | out | allocate | list | show | cancel | restore
  report     list | run <name>
  gst        gstr1 | gstr3b | hsn
  tally      export
  search     <query>
  import     csv parties | items | opening-stock
  export     csv <entity>
  backup     create | verify | inspect | restore | list
  db         info | check | migrate | reindex
  audit      list | export | verify
  server     user (add | list | disable | role)
             | device (list | pair | revoke)
             | token (create | list | revoke)
             | workspace (list | create)
             | member (grant | revoke)
  completion bash | zsh | fish | powershell
  version
```

`server ...` commands use the API and require an owner or instance-admin token. Bootstrap
and recovery when the API is down use the separate `openbill-server admin` commands on the
server host.

### 3.1 Key commands

| Command | Behavior |
| --- | --- |
| `voucher create --type T --input FILE.json` | Creates a draft from a `core-api-contract` JSON document (same schema as the API). Prints the draft ID. |
| `voucher create --type T --party P --line "SKU:QTY@RATE" ...` | Shorthand for simple vouchers; tax comes from item masters and firm settings. |
| `voucher post ID` | Validates and posts in one transaction; prints the assigned number. Irreversible except by cancel or a correcting note. |
| `voucher cancel ID --reason TEXT` | Reverses stock and ledger effects; reason required. |
| `voucher restore ID --reason TEXT` | Restores a cancelled voucher; reason required. |
| `voucher print ID --theme NAME --size a4\|a5\|58mm\|80mm --out FILE.pdf` | Renders through the shared document model; writes a file. Sending to a printer is out of scope for the first CLI release. |
| `payment in --party P --amount 1500.00 --account Cash --allocate INV-12:1000.00` | Records a payment and optional allocations as one group. |
| `report run NAME --from DATE --to DATE --format table\|csv\|json\|pdf [--out FILE]` | Runs any report listed by `report list`. |
| `gst gstr1 --period 2026-09 --format json\|csv --out DIR` | GSTR-1 export in the published schema for supported document types. |
| `gst hsn --from --to` and `gst gstr3b --period 2026-09` | Summary data for return preparation; filing is out of scope. `gstr3b` follows the GSTR-3B working report (P1 in [01-feature-inventory.md](01-feature-inventory.md)) and is not in the 1.0 C3 set. |
| `tally export --from --to --out FILE.xml` | Tally-compatible XML export. |
| `report NAME [--from] [--to] [--format table\|csv\|json] [--out] [--party]` | Shorthand for `report run NAME`; dates default to the current financial year. Names: `sales-register`, `purchase-register`, `day-book`, `cash-bank-book`, `expenses`, `outstanding`, `party-statement`, `stock-summary`, `low-stock`, `item-profit`, `profit-loss`, `gstr1`, `gstr3b`, `hsn-summary`, `day-summary`, `batch-expiry`, `gstr2`, plus the by-user reports. |
| `report ca-pack [--from] [--to] --out FILE.zip` | The app's CA pack: registers, day book, GSTR-1 (CSV and monthly JSON), GSTR-2 (CSV and JSON), GSTR-3B, HSN, outstanding, party ledgers and Tally XML, each identical to the single export. |
| `export tally --from --to [--out]` | Same as `tally export`. |
| `audit list [--limit N]` | Newest audit events (post, cancel, restore, lock, year close). |
| `import csv items --file F.csv [--mapping M.toml] --dry-run` | Validates every row and reports errors with row numbers; no writes in dry-run. Without `--dry-run`, imports in one transaction or not at all. |
| `backup create [--out DIR]` | Snapshot via SQLite backup API, verified before the final rename. Prints the archive path. With `OPENBILL_BACKUP_PASSWORD` set, the archive is encrypted (AES-256-GCM); `backup verify`/`restore` and `--backup` read the same variable. |
| `backup verify FILE` | Manifest, checksums, schema version, and `integrity_check` on a temporary copy. |
| `backup restore FILE --yes` | Validates, keeps a pre-restore safety copy, then replaces local data. |
| `db check` | Integrity check, foreign keys, ledger invariants (journal balance, allocation bounds, stock sums, FTS counts), and audit trigger presence. |
| `db migrate [--dry-run]` | Takes an automatic backup, then migrates to the current schema. |
| `audit verify` | Checks audit append-only guarantees locally, or the server hash chain in remote mode. |

### 3.2 Commands for app features added since 1.0.1

| Command | Behavior |
| --- | --- |
| `db info`, `backup inspect FILE` | Path, size, schema and firms; an archive's manifest, encryption, record counts and whether it needs migration. |
| `search QUERY [--type invoices\|parties\|items\|payments\|...]` | The app's list searches in one go. |
| `invoice restore REF [--reason]`, `invoice delete`, `invoice recycle-bin [--purge]` | Restore a cancelled invoice (reason required) or a deleted draft. |
| `invoice create ... --reverse-charge --export wpay\|wopay\|sezwp\|sezwop --currency USD --exchange-rate 83.50 --transporter ... --po-number ... --eway-bill ... --field LABEL=VALUE --line SKU:QTY@RATE+FREE` | The app's document fields; totals use the app's invoice math. Party special rates and credit terms apply. |
| `order list\|show\|create\|post\|convert [--qty LINE=QTY]\|short-close\|cancel --type sales\|purchase` | Sales and purchase orders; converting bills the pending quantity. |
| `recurring list\|show\|pause\|resume\|delete\|run [--today]` | Recurring invoice schedules; `run` catches up missed periods and is safe to repeat (cron). |
| `pos held list\|discard` | Bills on hold at the counter. |
| `payment in\|out ... --discount AMOUNT`, `--settle-exchange INVOICE` | Settlement discount; exchange gain or loss on a foreign-currency invoice. |
| `party update REF --credit-limit --credit-days --group --currency`, `party price list\|set\|remove` | Credit terms (owner or manager), group, default currency, special rates. `none` clears a value. |
| `item update REF --low-stock --reorder-qty --category --field LABEL=VALUE`, `item category list\|add` | Stock levels, categories (created when missing) and the custom item fields set up in the app. |
| `export attachments --out FILE.zip` | Every attachment, one folder per record, plus `index.csv`; the same as the app's export. |
| `users list\|add\|activate\|deactivate\|role`, `audit list --user NAME` | Staff users (owner PIN required), and the activity log for one user. |

Sign-in: when the data has staff users, every command needs `--user NAME` (or
`OPENBILL_USER`) and that user's PIN. The owner's PIN is the desktop app-lock PIN. The CLI
reads the PIN from `OPENBILL_PIN`, from the first stdin line with `--pin-stdin`, or from a
hidden prompt on a terminal. It never accepts the PIN as an argument. The CLI uses the
app's wrong-PIN back-off and enforces the app's roles:

- A cashier gets exit 8 on reports, exports, recurring, users and the activity log.
- Writes are checked by the same repository rules as the app.

`users add` reads the new PIN from `--new-pin-stdin`, `OPENBILL_NEW_PIN`, or a hidden
prompt, which asks twice.

Dates are `YYYY-MM-DD`; periods are `YYYY-MM`. Amounts on the command line are decimal
strings with up to two places; the CLI never parses money as floating point.

## 4. Output conventions

| Concern | Convention |
| --- | --- |
| Streams | Data to stdout; progress, warnings, and errors to stderr |
| Default format | Human-readable table when stdout is a terminal; `--json` otherwise recommended for scripts |
| `--json` | One JSON document per command, using `core-api-contract` DTOs; lists are arrays |
| `--ndjson` | One JSON object per line for large lists and streaming exports |
| `--format csv` | RFC 4180, UTF-8, header row, `.` decimal separator, no thousands grouping |
| Money | JSON and CSV: decimal string with two places (`"1234.50"`). Tables: Indian digit grouping (`12,34,567.00`) |
| Color | Only on a terminal; disabled by `NO_COLOR` or `--no-color` |
| Locale | Machine formats are locale-independent; table formats follow the firm locale |
| Quiet | `-q` prints only the essential value (an ID, number, or path) |
| Stability | `--json` field names and exit codes are a public contract from 1.0; additive changes only within a major version |

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | Unexpected internal error |
| 2 | Usage error (unknown flag, missing argument, ambiguous firm) |
| 3 | Domain validation failed (e.g., over-allocation, invalid GSTIN, posting rule) |
| 4 | Not found |
| 5 | Conflict (state transition not allowed, revision mismatch, duplicate idempotency key with different input) |
| 6 | Database locked or busy |
| 7 | Integrity check failed (`backup verify`, `db check`, `audit verify`) |
| 8 | Authentication or permission denied |
| 9 | Network or server unavailable |
| 10 | Schema version incompatible (needs `db migrate`, or archive is from a newer version) |
| 130 | Interrupted |

Non-interactive behavior:

- The CLI never prompts when stdin is not a terminal. A command that would prompt fails with
  exit code 2 unless `--yes` is given.
- Create commands accept `--idempotency-key KEY`. A retry with the same key returns the
  original result instead of creating a duplicate. In remote mode the key becomes the
  `Idempotency-Key` header; in direct mode it is stored locally with the result.

## 5. Safety

| Operation | Safeguard |
| --- | --- |
| `voucher post` | Explicit command; prints number and totals. `--dry-run` shows computed totals and tax trace without posting. |
| `voucher cancel`, `voucher restore`, `payment cancel` | `--reason` required; confirmation on a terminal; `--yes` in scripts |
| `voucher delete` | Drafts only; moves to the recycle bin |
| `voucher purge` | Soft-deleted drafts only; confirmation or `--yes` |
| `backup restore` | Confirmation shows firm name, archive date, and record counts; safety copy kept |
| `db migrate` | Automatic pre-migration backup; `--dry-run` lists pending migrations |
| `import csv` | `--dry-run` recommended; all-or-nothing transaction |
| `server device revoke`, `server user disable`, `server token revoke` | Confirmation or `--yes`; audited |

There is no `--force` that bypasses domain invariants. Every write produces the same audit
events as the equivalent app action, with the CLI identified as the source.

## 6. Scripting examples

Nightly backup on Linux (cron), keeping 30 days:

```sh
# m h dom mon dow
30 1 * * * openbill --data-dir /srv/openbill backup create --out /backups/openbill -q \
  && find /backups/openbill -name '*.openbill.zip' -mtime +30 -delete
```

The same on Windows with Task Scheduler:

```powershell
schtasks /Create /SC DAILY /ST 01:30 /TN "OpenBill backup" `
  /TR "openbill.exe backup create --out D:\Backups\OpenBill -q"
```

Monthly GST export for the accountant:

```sh
openbill gst gstr1 --period 2026-09 --format json --out ./gst/2026-09
openbill report run sales-register --from 2026-09-01 --to 2026-09-30 --format csv \
  --out ./gst/2026-09/sales-register.csv
```

Reports from a client's backup without importing it:

```sh
openbill --backup client.openbill.zip report run outstanding --format table
```

Create and post an invoice from a script, safely retryable:

```sh
id=$(openbill voucher create --type sales-invoice --input inv.json \
       --idempotency-key "order-4812" -q)
openbill voucher post "$id" --json | jq -r '.number'
```

Fail a CI job or monitoring check if the books do not reconcile:

```sh
openbill db check --json || exit 1
```

## 7. Technology

| Concern | Decision | Rationale |
| --- | --- | --- |
| Argument parsing | Clikt | Kotlin-first, nested subcommands, typed options, shell completion generation, and an in-process test API; runs on JVM and Kotlin/Native |
| Fallback | picocli | Mature and GraalVM-friendly if native-image work with Clikt proves difficult |
| Runtime | JVM with a `jlink` runtime | Same code paths as the desktop app, including Room and bundled SQLite |
| Native binary | GraalVM native image, spike first | Faster startup for scripts; risk in JNI (bundled SQLite) and reflection metadata. AppCDS is the fallback for startup time |
| Platform services | `shared:platform-jvm` headless actuals | No AWT or Compose dependency; PDF via the desktop PDF library in headless mode |

Module rules: `cli` depends on `core-data`, `core-domain`, `core-reporting`,
`core-printing`, `core-api-contract`, `core-sync` (its Ktor API client, for remote mode),
and `shared:platform-jvm`. It must not depend on `core-ui` or any `feature-*` module.

## 8. Packaging

Built today by the `cli` job in `.github/workflows/release.yml` on every release tag, after
`:cli:test` passes. The job builds one portable JVM build, which needs Java 17 or later on
the machine; the bundled SQLite jar carries the Linux, Windows and macOS natives. It
publishes three artifacts, all listed in `SHA256SUMS`:

- `openbill-cli-<tag>-linux.tar.gz`
- `openbill-cli-<tag>-windows.zip`, which includes `bin\openbill.bat`
- `openbill-cli_<version>_all.deb`, which installs to `/usr/lib/openbill-cli` and links
  `/usr/bin/openbill`. It depends on a Java 17 runtime.

These artifacts are not signed yet. Signing, `jlink` runtimes and the rest of the plan below
are pending.

| Platform | Artifact | Notes |
| --- | --- | --- |
| Linux x64 | `tar.gz` with `jlink` runtime; `openbill` included in the desktop DEB and as a separate DEB | Installs to `/usr/bin/openbill` from the DEB |
| Windows x64 | `zip` with `openbill.exe` launcher; optional "add to PATH" component in the desktop MSI | Per-user install by default |
| Linux/Windows arm64 | Best effort, as for desktop | Useful on a Raspberry Pi next to the server |
| macOS | Optional, community-maintained `tar.gz` | No GUI signing requirements; not a release gate |
| Native image | Separate artifacts once the spike passes | Same tests as the JVM build |

Every artifact ships with SHA-256 checksums and signatures alongside the desktop releases,
plus generated shell completions and a man page.

## 9. Testing

- **Golden output tests.** Each test runs a command in-process against a seeded fixture
  database and compares stdout, stderr, and exit code with checked-in golden files. A fixed
  clock and deterministic ULID generator are injected through DI (never a public flag).
  Line endings are normalized; Windows and Linux CI run the same goldens.
  `./gradlew :cli:test -PupdateGoldens` regenerates them for review.
- **JSON contract tests.** `--json` output is validated against the JSON Schema generated
  from `core-api-contract`.
- **Exit-code tests.** Each documented exit code has at least one test.
- **Cross-platform backup tests.** CI creates a backup on Linux with the CLI, verifies and
  reports on it on Windows, and compares report outputs byte for byte; Android-created
  backups from the device test suite feed the same check.
- **Large-fixture drivers.** The randomized 10,000-voucher stock test and the 50,000-invoice
  search fixtures are generated through CLI commands so they exercise real posting.
- **Remote-mode tests.** The same golden suite runs against an in-process server once S3
  lands; direct and remote outputs must match.

## 10. Milestones

| Milestone | Deliverable | Roadmap phase |
| --- | --- | --- |
| C0 | `openbill` skeleton: `version`, `db info/check/migrate`, `backup create/verify/inspect/restore`; golden-test harness | Phase 1 |
| C1 | `firm`, `party`, `item`, `voucher` create/post/cancel/restore/print, `search`, `--json`, exit codes | Phase 2 |
| C2 | `stock`, `payment`, `import csv --dry-run`, `export csv` | Phases 4-5 |
| C3 | `report`, `gst gstr1/hsn`, `tally export`, `audit`; signed Linux and Windows packages; `gst gstr3b` (added with the P1 GSTR-3B working report) | Phase 6 (ships with 1.0) |
| C4 | Remote mode, contexts, `server` admin commands | Server S3 |
| C5 | Native image binaries, if the spike passes | Post-1.0 |

Status after 1.0.1: C0-C3 are complete in direct mode. Vouchers are handled by `invoice`
(`list`, `show`, `create`, `post`, `cancel`, `restore`, `delete`, `recycle-bin`, `pdf`)
rather than a generic `voucher` group. The CLI also covers the app features added since
1.0.1 (section 3.2). Release tags build unsigned CLI packages (section 8); to run from
source, use `./gradlew :cli:installDist` (`cli/build/install/openbill/bin/openbill`).

Not in the CLI, because they are app-screen features: POS checkout, the invoice editor's
live preview, themes and appearance, the diagnostics screen (use `version`, `db info` and
`db check`), notifications and the inbox, barcode scanning, and app-lock set-up. Contexts,
`--idempotency-key`, `--ndjson`, completions and remote mode remain planned (C4).
