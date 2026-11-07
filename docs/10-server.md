# OpenBill Server

OpenBill Server is an optional, self-hosted service that adds multi-device sync, multi-user
access, and an HTTP API to the OpenBill apps. It is a design for post-1.0 work. Scope and
milestones follow [ROADMAP.md](../ROADMAP.md); modules follow
[03-architecture.md](03-architecture.md). The command-line client is specified in
[11-cli.md](11-cli.md).

## 1. Purpose and principles

The apps are complete without a server. The server exists for businesses that want:

- The same firm on several devices (counter phone, back-office PC, owner's laptop).
- Staff and accountant access with roles and an audit trail.
- A scriptable API and a CLI against shared data.
- An always-on backup target that the business controls.
- Optional integrations (e-invoice, e-way bill, messaging, email) configured once for a
  firm instead of on every device.

Principles:

1. **Optional, never required.** Every 1.0 workflow keeps working with no server, no
   account, and no network. Pairing a device with a server is reversible; unpairing leaves
   a complete local database.
2. **The device is authoritative for what it authored.** A device posts vouchers offline.
   The server orders, relays, validates, and stores; it does not hold the only copy.
3. **Self-hosted by default.** No OpenBill-operated service is assumed. A hosted offering,
   if one ever exists, runs the same open-source binary.
4. **Same core, same rules.** The server runs `core-model`, `core-tax`, and `core-domain`
   on the JVM. Tax, posting, and permission rules are not reimplemented.
5. **No telemetry.** The server never phones home.

### Competitive baseline

The market leader's Windows client (studied 2026-10-22; see
[07-desktop.md](07-desktop.md#competitive-baseline)) is a shell around the vendor's web app.
It has no local database and needs the vendor's servers for all data. OpenBill inverts that
model: the local database is the product, and the server is an optional relay and
collaboration point that the business can host on its own hardware or not use at all.

## 2. Non-goals

- Required accounts, licensing checks, or device limits.
- A multi-tenant SaaS control plane (billing, signup funnels, plan enforcement).
- Server-side business logic that differs from the apps.
- Automatic merging of posted financial documents.
- Real-time collaborative editing of the same draft.
- Acting as a GST Suvidha Provider, payment processor, or messaging provider.
- Third-party analytics, crash reporting, or advertising SDKs.
- Dynamically loaded third-party plugin binaries in the first server releases.
- Horizontal scale-out or high-availability clustering. One process per installation.

## 3. Deployment

| Concern | Decision |
| --- | --- |
| Artifact | `openbill-server` fat JAR plus a `jlink` runtime tarball per OS/arch; OCI image for linux/amd64 and linux/arm64 |
| Minimum host | Raspberry Pi 4/5 (arm64, 2 GB RAM) or a 1 vCPU / 1 GB VPS |
| Memory budget | Under 256 MB heap for a workspace with 50,000 vouchers and 5 devices |
| Default storage | SQLite (WAL mode), one database file per workspace |
| Optional storage | PostgreSQL, one schema per workspace (milestone S4) |
| Attachments | Content-addressed files under the data directory; optional S3-compatible store |
| Config | One TOML file plus environment overrides; secrets via file paths, not inline values |
| TLS | Built-in TLS with a user-supplied certificate, or plain HTTP bound to localhost behind a reverse proxy |
| LAN mode | Self-signed certificate; the pairing QR carries its fingerprint for pinning |
| Service management | Example systemd unit and Compose file; Windows service wrapper is best effort |
| Upgrades | Schema migrations run at start after an automatic pre-migration backup; downgrade refused |

The server refuses to start on a non-localhost HTTP listener unless an explicit
`allow_insecure_http` flag is set, and logs a warning on every start when it is.

Data directory layout:

```text
<data-dir>/
  server.toml
  instance.sqlite            Users, devices, tokens, workspaces, server audit
  workspaces/<workspace-id>/
    projection.sqlite        Same schema as the app database (core-database)
    oplog.sqlite             Ordered operation log
    attachments/<sha256>
  backups/<workspace-id>/<timestamp>.openbill.zip
  keys/                      Instance master key and TLS material, mode 0600
```

## 4. Technology

| Concern | Decision | Rationale |
| --- | --- | --- |
| HTTP server | Ktor server, Netty engine (S1a; CIO has no built-in TLS) | Kotlin and coroutines end to end; same kotlinx.serialization models and Ktor client already chosen for app sync; small footprint; no reflection-heavy container |
| Serialization | kotlinx.serialization via `core-api-contract` (S1a: the sync bodies use one hand-written codec, `SyncWire`, shared by server and devices) | One DTO set for server, apps, and CLI |
| SQLite storage | Room KMP (`core-database`) on JVM for projections; the op log through the same bundled SQLite driver (S1a), not JDBC | Projection is byte-compatible with the app schema and backup format |
| PostgreSQL storage | JDBC adapters implementing `core-domain` repository ports | Room does not target PostgreSQL; both adapters pass one repository contract test suite |
| Migrations | Room migrations for SQLite projection; versioned SQL migrations for PostgreSQL and instance tables | Explicit and tested, like the app |
| Password hashing | PBKDF2-HMAC-SHA256, 600,000 iterations (S1a decision; Argon2id was the plan) | In the JDK, so no native or extra crypto library on a Raspberry Pi; OWASP's current PBKDF2 setting; the iteration count is stored with each hash, so it can be raised or moved to Argon2id on next sign-in |
| API description | OpenAPI 3.1 generated from `core-api-contract` and checked in | Reviewable diffs, client generation by third parties |
| DI | Koin | Same as the apps |

Spring Boot was considered and rejected: heavier memory footprint on a Raspberry Pi, a second
serialization and DI stack, and little benefit for a single-process service. http4k is a
reasonable alternative if Ktor causes friction; the API contract module keeps that swap cheap.

## 5. Tenancy model

```text
Instance (one server process)
  Workspace          Isolation boundary; one SQLite file set or one PostgreSQL schema
    Firm             Legal business entity, as in the app (multi-firm per workspace)
      Membership     User x firm x role
    Device           Paired app or CLI installation
  User               Local account; may belong to several workspaces
```

- A small business runs one workspace with one or more firms.
- An accountant or IT provider may host several clients as separate workspaces on one
  instance. Workspace data never shares a database file or schema.
- Instance administrators manage workspaces and users but have no firm data access unless
  they also hold a membership. Every cross-workspace admin action is audited.
- Consolidated multi-firm reports run inside a workspace only.

## 6. Sync protocol

### 6.1 Model

Sync replicates an append-only operation log per firm. Each device keeps its own full local
database plus an outbox of operations it authored. The server assigns a total order per firm
and relays operations to other devices.

Why an operation log rather than row diffs:

- Posted vouchers are immutable, so most financial data is insert-only facts. These never
  conflict; they only need ordering and deduplication.
- Lifecycle transitions (post, cancel, restore) are explicit domain operations with
  validation, which row diffs would lose.
- Conflicts are confined to mutable records: masters, settings, and drafts.

### 6.2 Operation envelope

| Field | Meaning |
| --- | --- |
| `opId` | ULID generated on the authoring device; global idempotency key |
| `firmId` | Firm the operation belongs to |
| `deviceId`, `userId` | Author device and signed-in user |
| `hlc` | Hybrid logical clock (wall time, counter, device id) for causal ordering of master edits |
| `entity`, `entityId` | Target type and ULID |
| `type` | Domain operation, e.g. `party.upsertFields`, `voucher.post`, `voucher.cancel`, `allocation.add` |
| `baseRevision` | Revision the author saw, for mutable records |
| `payload` | Operation body; for `voucher.post` the full voucher snapshot with computed totals and tax trace |
| `schemaVersion` | App schema version that produced it |
| `seq` | Assigned by the server on acceptance; per-firm, gap-free, monotonic |

Operations are recorded locally in the same transaction as the change they describe.
Existing 1.0 databases are bootstrapped with a snapshot (section 6.6) instead of backfilled
history.

#### 6.2.1 Local operation log (S0a, schema 19)

Implemented in `core-database` (tables, triggers, migration) and `core-sync`
(`SyncOperation` envelope, `SyncOutbox`). Decisions:

| Concern | Decision |
| --- | --- |
| Entity ids | Already globally unique: every synced row has a client-generated ULID text key (composite keys for `voucher_number_sequences`, `payment_details` and `firm_settings` are made of such ids). No new id column and no backfill. `entityId` is the primary key, composite keys joined with `\|`. |
| Hook point | One SQLite `AFTER INSERT/UPDATE/DELETE` trigger per synced table, generated on every open from the live columns (`SyncTriggers`). No write path (repositories, CLI import, raw SQL, cascades) can skip it, a rollback removes the op with the change, and new columns are picked up without code. Every migration step first drops the triggers so a step may drop or rename columns. |
| Op content | `upsert` carries the full row as a JSON object with keys in name order; `delete` carries the key only. `type` is `upsert` or `delete`; `action` names the domain operation (`voucher.post`, `voucher.cancel`, `allocation.add`, `party.save`, ...) from the repository write that made it, so lifecycle transitions stay explicit. `baseRevision` is the row's previous `revision` where the table has one. |
| Groups | `DatabaseTransactionRunner` stamps one `groupId` per write; a `Repositories.transaction` block (a payment with its details, post and allocations; POS checkout) shares one group across its writes. The server accepts or rejects a group as a unit (section 6.3). |
| Device and user | `sync_state` holds one row: a ULID `deviceId` seeded on open, the `enabled` flag, and the HLC. Each op stores `deviceId`, the signed-in user (`Access.actorId`), and `hlc` (wall millis, never decreasing on the device); the device `seq` (AUTOINCREMENT, never reused) orders a device's ops and breaks HLC ties with `deviceId`. S1 raises the HLC to the newest pulled op. |
| `opId` | 128-bit random hex from SQLite `randomblob`, the idempotency key only. Order comes from the device `seq` and the server `seq`, so it need not be a ULID. |
| Firm | Tables with `firmId` use it; `firms` uses its id; children (`party_addresses`, `batches`, `serial_numbers`, `voucher_lines`, `voucher_charges`, `voucher_charge_portions`) look up their parent. A child whose parent is already gone (foreign-key cascade) records nothing: the parent's delete cascades the same way on every replica. `users` ops have no firm (workspace-wide). |
| Synced tables | Every table except `sync_state`, `sync_outbox`, `room_master_table` (and SQLite internals); S0b adds `sync_inbox` and `voucher_number_sequences`. A new table syncs by default; one without a firm fails loudly at open until it is mapped. Device preferences (theme, printer, last firm) are not in the database. Drafts and held bills stay local (6.2.2). |
| Never in ops | `users.pinHash`: a 4-digit PIN hash is brute-forced offline in seconds, so PINs stay per device (server accounts use their own passwords, section 7.1). `vouchers.fulfilment` (a derived cache, 6.2.2). Local file paths travel as content references (6.2.2). |
| Activation and growth | Off by default: a standalone install records nothing, so the outbox cannot grow for users who never pair. Pairing (S1c) sets `enabled` in the same transaction that takes the bootstrap snapshot, so the snapshot plus the outbox is complete. Accepted ops are deleted (`acknowledge(seq)`). Unpairing (`disable`) stops recording and drops unsent ops; re-pairing starts from a new snapshot (section 6.6). |
| Backups | Backups are byte copies and contain the sync tables, but restore wipes `sync_state` and `sync_outbox` in the staged copy before the swap: a restored database is standalone with a fresh device id on open, so two installs never share an identity and old unsent ops are not replayed. Pre-19 backups restore unchanged and migrate. |
| Firm erasure | Deletes the firm's ops from the outbox in the same transaction: erasure is local and leaves no copy of the firm's rows. |

#### 6.2.2 Sync engine (S0b, schema 20)

Implemented in `core-sync`: `SyncTransport` (push, pull after a cursor, snapshot),
`SyncClient` (device side), `SyncRules` (server validation), `SyncRows` (the apply path shared
by client and server) and, on the JVM, the in-process server (renamed `SyncServer` in S1a,
section 6.2.3): one database file per workspace with the app's own schema and code.
Decisions on the S0a open issues:

| Issue | Decision |
| --- | --- |
| (a) Derived rows and counters | Journal entries, stock movements and allocations are synced as written (they are the posting's facts, validated on the server). Caches each replica can recompute stay out of ops: `vouchers.fulfilment` is a private column, recomputed after every apply, and an update that changes no synced column records nothing. Number counters (`voucher_number_sequences`) are device-local: every device numbers in its own series (section 6.5). |
| (b) Drafts and held bills | Device-local until S2. Trigger conditions skip draft vouchers, their lines, charges, portions, payment details, and audit events about drafts (and the draft lifecycle events themselves). Posting touches the children, so the post group carries the complete document. |
| (c) System and natural-key rows | Deterministic ids: `Ulid.derived(parts)` (FNV-1a of the natural key, timestamp 0) for system accounts (firm + code), batches (item + godown + number), serials, party prices, allocations (payment + invoice), next financial years and the POS cash-sale party. Two offline devices create the same row id, so the second insert is an upsert of the same row. |
| (d) File paths | Ops carry `blob:sha256:<hex>` content references for `logoPath`, `signaturePath` and `storagePath` (`BlobRefs`, `FileBlobRefs` content store); a device maps a reference back to its own path when it holds the blob, else keeps the reference. Blob transfer is S1. |
| (e) Copied data folder | The server keeps (device id, device seq) to op id; a push reusing a pair with another op id is refused (`SyncRefusedException`). The client records the refusal and stops syncing until it is paired again. Backup restore already gives a restored database a new device id (6.2.1). |
| (f) Startup seeding | Triggers are dropped before the owner and settings are seeded on open and recreated afterwards, so seeding is never recorded. Any write outside the transaction runner is recorded with action `unattributed`. |

Engine:

- **Push** sends the outbox in device order, whole groups only. The server applies each group
  in one transaction after `SyncRules`; a failed group is rolled back, its ops are rejected,
  and the server returns its current rows for every touched entity as corrections. The
  corrections are also appended to the log as one server-authored group with a
  `sync.rejected` audit event (the review task). Over-allocation drops only the allocation ops;
  the posted payment stands.
- **Validation** (6.7, row level for now): posted vouchers and their lines never change except
  lifecycle fields; every write to an existing posted or cancelled voucher must carry the
  server's current revision (two offline cancels or restores: the second is refused, even when
  equal clocks make the rows equal); journal and stock entries of an existing voucher change
  only with such a lifecycle step in the same group (bank-clearing dates excepted); every
  touched journal balances; allocations fit the payment and the invoice and both are posted.
- **Pull** applies pages in server order, one transaction per page with recording off (no
  echo), cursor advanced in the same transaction, whole groups only (a page ending inside a
  group is cut back). Rebase rule: a pulled op is skipped while the device still has an op for
  that row in its outbox; own ops leave the outbox when pulled back, not when acknowledged.
  When nothing is pending every replica equals the server (row-level last writer by server
  order, instead of the field-level HLC merge of 6.4, which stays a later refinement).
- **Retry**: transport failures back off exponentially with jitter (6 attempts per call); a
  sync that makes no progress stops and resumes on the next sync.
- **Server restore (epochs)**: each device keeps pulled ops and received corrections in
  `sync_inbox` until the server reports them durable (in a server backup). On a new epoch it
  re-pushes them in their original order (idempotent by op id), applies the corrections for any
  that are now rejected, and pulls again from the last durable point. Copies stay retained until
  pulled again, so back-to-back restores lose nothing.

Simulation (section 16): `SyncSimulationTest` runs three devices on real SQLite files with the
real repositories (sales, purchases, receipts with allocations, cancel and restore, master
edits, drafts, logos) against the in-process server, with lost requests and responses,
duplicate calls, repeated, reordered and short pages, ±2 h clock skew, offline spells, server
restarts, backups and restores. Invariants: every replica's canonical dump equals the
server's; outbox empty and cursor at head; integrity and foreign keys; every posted voucher
present with its number, total, journal and stock (empty once cancelled); journals balance;
no over-allocation; no duplicate numbers. CI default 12 seeds x 150 steps;
`-Popenbill.sim.seeds=300 -Popenbill.sim.steps=400` (120,000 steps, about 555,000 server ops)
passed with no failures; `-Popenbill.sim.seed=<n>` replays one seed and
`-Popenbill.sim.keep=<dir>` keeps a realistic day's databases for the CLI.

#### 6.2.3 Server process (S1a)

Done in S1a: the `server` module (Ktor on Netty) around the same sync core the simulation
uses. How to run it: [server-setup.md](server-setup.md).

- **Sync core** (`core-sync`, JVM): `SyncServer` keeps ordering, dedupe and validation and is
  storage-agnostic over `SyncLog`; `SqliteSyncLog` stores each workspace's op log in
  `workspaces/<id>/oplog.sqlite` (WAL, `synchronous = FULL`) beside `projection.sqlite` (the
  app schema). An accepted group is appended to the log inside the projection's transaction,
  which also records the applied seq in the projection's `sync_state.cursor`; if the
  projection commit is lost, opening the workspace replays the log tail. A restore replaces
  the projection first and truncates the log second, so a crash in between replays as if no
  restore happened.
- **Rejection vs failure**: an op is rejected (corrections, review task) only for a sync rule,
  a malformed op or an SQLite constraint, type or SQL error; disk, lock and other server errors
  fail the call (HTTP 500) so the device retries and never loses the op.
- **Wire**: `SyncWire` (JSON bodies) and `HttpSyncTransport` (Ktor client, OkHttp on Android
  and the JVM) implement `SyncTransport`. 5xx, 408, 429 and link failures are retryable
  `SyncTransportException`; 4xx refusals, 501 and a rejected sign-in are `SyncRefusedException`.
  Not wired into the apps until pairing (S1b).
- **Accounts** (`instance.sqlite`, schema version checked, downgrade refused): local users with
  PBKDF2 hashes; a one-time setup token (`obst_`) printed on start while no admin exists, or
  `openbill-server admin create`; sign-in gives a refresh token (`obrt_`, 90 days, not rotated,
  so a lost response cannot lock a device out) and 15-minute access tokens (`obat_`); tokens
  are stored as SHA-256 hashes only; sign-out ends the session. Unknown users cost the same
  hashing time as known ones. Workspaces have members; an admin creates them; a non-member
  gets 404 for a workspace, the same as for a missing one.
- **Audit** (section 10): setup, admin creation, sign-in and failures, sign-out, workspace
  creation and seeding go to a hash-chained table; `openbill-server audit verify` checks it.
- **Limits** (section 11): 5 failed sign-ins per minute per account and per address lock it
  for 1, 2, 4 ... 64 minutes (429 with `Retry-After`); 500 ops and 10 MB per push, 64 KB for
  JSON forms, 512 MB per seed upload; all configurable.
- **Seeding**: `POST /sync/workspaces/{w}/seed` takes the first device's database (integrity
  and schema checked, device-local tables cleared) once. Snapshot download answers 501 until
  S1c; a second device can start from a prepared copy of the seed (as the tests do).
- **Server-authored ops** (corrections) are relayed back by devices when they refill a
  restored server, so the server accepts them from devices; S2 signs them before non-owner
  roles can push.
- **Tests**: server API, TLS and configuration tests; `HttpContractTest` runs the simulation
  with every push and pull over HTTP against the real server on a random port (3 seeds by
  default); `PracticalHttpDayTest` drives two file-backed counters against a running server
  in phases around a restart.

Practical check (Linux PC, runtime tarball): two counters synced a day of 240 steps (1,347
server ops, 143 posted vouchers) with the server stopped and restarted mid-way; replicas and
the server's database converged, `openbill db check` passed on all three, and eight reports
(registers, day book, outstanding, stock, P&L, GSTR-1, HSN) were byte-identical. Server RSS
about 205 MB after the day and 288 MB peak under 16 parallel pullers (`-Xmx256m`, serial GC;
p95 0.11 s). A tablet on the same Wi-Fi reached the LAN listener.

### 6.3 Exchange

| Step | Endpoint | Behavior |
| --- | --- | --- |
| Push | `POST /api/v1/sync/workspaces/{w}/ops` | Batch of up to 500 ops in author order. Each op is accepted, deduplicated, or rejected with a reason. Response carries assigned `seq` values. |
| Pull | `GET /api/v1/sync/workspaces/{w}/ops?after={cursor}&limit={n}` | Ops after the device's cursor, pages of up to 500, including the device's own ops. |
| Ack | Implicit | The cursor is the last `seq` the device has applied. The server tracks it per device for diagnostics and retention only. |
| Seed | `POST /api/v1/sync/workspaces/{w}/seed` | The first device's database (section 6.6), once per workspace. |
| Snapshot | `GET /api/v1/sync/workspaces/{w}/snapshot` | Backup-format archive at a known `seq` for first sync, long-offline devices, and op-log compaction (S1c; 501 until then). |

The log is per workspace (one order across its firms), as implemented in S0b; the paths
say so since S1a.

Rules:

- **Idempotency.** `opId` is unique per firm. Re-pushing an accepted op returns the original
  result. Retries use exponential backoff with jitter.
- **Ordering.** A device pushes its outbox in authoring order and never reorders. The server
  applies ops in the order received per device; cross-device order is the server `seq`.
- **Cursors.** One cursor per (device, firm). A device syncs firms independently, so a large
  firm does not block a small one.
- **Rebase.** A device applies pulled ops it did not author, then re-validates its pending
  outbox. Its local view is "server state at cursor plus pending local ops".
- **Atomic batches.** Ops that must travel together (voucher post plus its allocations)
  share a `groupId` and are accepted or rejected as one unit.
- **Retention.** The server keeps the full op log by default. Compaction to a snapshot is
  an explicit admin action and forces devices behind the snapshot to resnapshot.
- **Epochs.** Restoring the server from a backup starts a new sync epoch. Devices that see a
  new epoch re-push any local ops the server no longer has; idempotency makes this safe.
  Devices are therefore also recovery copies of the server.

### 6.4 Conflict rules

| Data | Rule | Conflict outcome |
| --- | --- | --- |
| Posted voucher (insert) | Always accepted; an authored fact | None; may trigger derived warnings below |
| Voucher cancel | Valid if voucher is posted at apply time; duplicate cancels are no-ops with both reasons audited | Cancel of a cancelled voucher: no-op |
| Voucher restore | Valid if voucher is cancelled at apply time | Rejected op returned to author; author rolls back locally and gets a review task |
| Document number | Per-device number series (section 6.5); collisions are impossible by construction | If a legacy collision is detected, both stand and a review task is raised |
| Stock going negative after merge | Accepted; evaluated against the firm's negative-stock policy | Review task listing the vouchers involved |
| Payment allocation | Validated at apply time against effective allocations | Over-allocation: later op (by `seq`) is stored as suspended (no effect) plus a review task |
| Masters (party, item, unit, godown) | Field-level last-writer-wins by HLC for descriptive fields | None |
| Tax-relevant master fields (GSTIN, state, HSN/SAC, tax rate, cess) | Concurrent edits from different bases are not auto-merged | Earlier value kept; conflict record shows both; owner or accountant resolves |
| Master delete vs edit | Masters are archived, not deleted, once referenced; archive is a field | Last-writer-wins on the archive flag |
| Drafts | Single owner: the authoring device and user | An edit from another device creates a "conflicted copy" draft; never merged |
| Firm settings, numbering, tax settings | Owner-only ops; HLC last-writer-wins | Concurrent tax-setting edits raise a review task |
| Audit events | Append-only, never conflict | None |

Posted vouchers carry snapshots of party, item, and tax data, so master edits and their
conflicts never change a posted document. This matches the immutability rules in
[03-architecture.md](03-architecture.md#voucher-lifecycle).

Review tasks are synced records visible in the app and the API. Resolving one emits a normal
operation (for example, a correcting credit note or a master edit), never a silent rewrite.

### 6.5 Document numbering across devices

GST invoice numbers must be unique per financial year within a series. Offline devices cannot
coordinate a single counter, so:

- Each paired device gets its own number series per document type, for example a prefix
  such as `A/`, `B/`, assigned by the owner at pairing time. Numbers remain gap-free per
  series per device. Implemented in S0b: `sync_state.series` (1-3 letters or digits) is
  appended to the document prefix (`INV-A-0001`), and the counters are device-local. A
  standalone install never has a series (`INV-0001` unchanged); a device that enables sync
  keeps counters it already had for the running financial year. GST caps a number at 16
  characters: numbering refuses a longer number with a clear error, setup refuses a prefix
  that cannot fit, and enabling sync refuses a series that would push the invoice number past
  16 (use a shorter prefix or a one-letter series).
- An optional block-lease mode lets online devices reserve blocks from a shared series. A
  device that exhausts its block offline falls back to its own series.
- The 1.0 schema therefore scopes numbering by a series identifier, not only by firm,
  document type, and financial year, so per-device series need no migration of posted
  documents.

### 6.6 Bootstrap and unpairing

- Pairing an existing standalone database: the device uploads a snapshot; the server
  creates the firm at `seq` 0 from it. Only one device may seed a firm; others must pull.
- Joining a firm: the device downloads a snapshot and pulls from its `seq`.
- Unpairing keeps the local database and marks it standalone. Re-pairing requires a fresh
  snapshot exchange, so diverged copies are never silently merged.

### 6.7 Server-side validation

On apply, the server re-runs the same `core-domain` validation the device ran, including a
`core-tax` recomputation of posted totals. A mismatch caused by tax-engine version skew does
not reject the voucher (it is already a legal document); it records a review task with both
traces. Malformed ops, unknown schema versions, and permission failures are rejected.

### 6.8 End-to-end encryption option

Per-workspace opt-in, chosen at workspace creation.

- The owner's device generates a firm data key. Paired devices receive it wrapped with their
  own X25519 public key during QR pairing; the server never sees it.
- Op payloads, snapshots, and attachments are encrypted with an AEAD cipher before upload.
  Envelope fields needed for routing (`opId`, `firmId`, `deviceId`, `seq`, sizes,
  timestamps) remain visible to the server.
- In this mode the server is a blind relay and backup store. Server-side validation, REST
  data endpoints, reports, the web client, and server-run integrations are unavailable.
  Integrations run from devices instead.
- Key loss means data loss on the server side; devices keep their plaintext local copies.
  Key rotation re-wraps the data key for remaining devices after a device is revoked.

## 7. Authentication and authorization

### 7.1 Identities

| Identity | Credential | Lifetime |
| --- | --- | --- |
| User | Username and password (Argon2id); optional TOTP | Until disabled |
| Web session | Opaque session cookie, `HttpOnly`, `Secure`, `SameSite=Strict` | Idle and absolute timeouts |
| Device | Device key pair plus refresh token bound to the device | Until revoked |
| Access token | Opaque, short-lived bearer token issued from a refresh token | Minutes |
| API token | Opaque, scoped (workspace, firms, role ceiling, read/write), optional expiry | Until expiry or revocation |

Tokens are random, prefixed by type for secret scanning, and stored only as hashes.
No external identity provider is required. OIDC login is a later optional plugin.

### 7.2 Device pairing

1. An owner (in the app, CLI, or admin UI) creates a pairing invitation for a firm, role,
   and optional number-series prefix. It is single-use and expires in 10 minutes.
2. The invitation is shown as a QR code containing the server base URL, the invitation
   secret, the TLS certificate fingerprint, and the workspace public key (E2EE mode only).
3. The new device scans it, pins the certificate fingerprint, generates its key pair, and
   redeems the invitation with the user's password or an owner approval.
4. The server issues a device-bound refresh token and records the device in the audit log.
5. Devices without a camera, and the CLI, can paste the invitation as text.

Revoking a device invalidates its tokens immediately. Ops it pushes afterwards are
quarantined for owner review, not applied and not discarded, because they may be legitimate
offline sales.

### 7.3 Roles

Roles follow the draft in [01-feature-inventory.md](01-feature-inventory.md#10-collaboration-and-access).
They are checked in `core-domain` use cases on the device and again on the server when ops
are applied or API calls are made.

| Capability | Owner | Accountant | Operator | Read-only | CA seat |
| --- | --- | --- | --- | --- | --- |
| Create and post sales vouchers and payments | Yes | Yes | Yes | No | No |
| Cancel and restore vouchers | Yes | Yes | No | No | No |
| Purchases, expenses, stock adjustments | Yes | Yes | Configurable | No | No |
| Masters (parties, items) | Yes | Yes | Create only | No | No |
| Reports, GST returns, Tally export | Yes | Yes | No | View only | View and export |
| Firm settings, numbering, tax settings | Yes | No | No | No | No |
| Users, devices, tokens, integrations | Yes | No | No | No | No |
| Backup and restore | Yes | Backup only | No | No | Download backup if granted |
| Delete firm | Yes | No | No | No | No |

The CA seat is a read-only membership with export rights, scoped to selected firms and an
optional expiry date. It does not count against any limit, because there are none.

A role check uses the role the user held when the server applies the op. An op authored
offline before a role downgrade is quarantined for review rather than rejected.

## 8. HTTP API

### 8.1 Conventions

- Base path `/api/v1`. Breaking changes require `/api/v2`; additive fields and endpoints do
  not. Clients ignore unknown fields.
- JSON via `core-api-contract` DTOs. Money is a decimal string with exactly two places
  (`"1234.50"`); quantities and rates are decimal strings; never JSON floats. Dates are ISO
  8601 local dates; instants are UTC.
- IDs are ULIDs. Clients may supply the ID on create for offline-safe retries.
- Every mutating request accepts an `Idempotency-Key` header; replays return the first
  response.
- Mutable resources (masters, drafts, settings) return an `ETag` revision and require
  `If-Match` on update. A mismatch returns `412`.
- Lists use opaque cursor pagination and explicit filters; no offset paging.
- Errors use `application/problem+json` (RFC 9457) with a stable `code` field.
- The OpenAPI 3.1 document is served at `/api/v1/openapi.json` and checked into the
  repository. CI fails if it changes without a reviewed diff.
- API writes are converted to operations authored by a server-side device identity, so
  devices receive them through normal sync.

### 8.2 Resource outline

| Area | Key endpoints |
| --- | --- |
| Auth | `POST /auth/login`, `POST /auth/logout`, `POST /auth/token` (refresh), `GET /me` |
| Pairing and devices | `POST /workspaces/{w}/pairing-invitations`, `POST /pairing/redeem`, `GET /devices`, `DELETE /devices/{id}` |
| Users and memberships | `GET/POST /users`, `PATCH /users/{id}`, `PUT /firms/{f}/members/{userId}` |
| API tokens | `GET/POST /tokens`, `DELETE /tokens/{id}` |
| Workspaces and firms | `GET/POST /workspaces`, `GET/POST /workspaces/{w}/firms`, `GET/PATCH /firms/{f}` |
| Masters | `/firms/{f}/parties`, `/items`, `/units`, `/godowns`, `/accounts`, each with list, get, create, patch, archive |
| Vouchers | `GET/POST /firms/{f}/vouchers`, `PATCH /vouchers/{id}` (draft only), `POST /vouchers/{id}/post`, `/cancel`, `/restore`, `GET /vouchers/{id}/pdf?theme=` |
| Payments | `POST /firms/{f}/payments`, `POST /payments/{id}/allocations` |
| Stock | `GET /firms/{f}/stock/summary`, `GET /stock/ledger?item=`, `POST /stock/adjustments`, `POST /stock/transfers` |
| Reports | `GET /firms/{f}/reports`, `GET /reports/{name}?from=&to=&format=json\|csv\|pdf` |
| GST | `GET /firms/{f}/gst/gstr1?period=`, `/gstr3b?period=`, `/hsn-summary?from=&to=` |
| Exports | `POST /firms/{f}/exports/tally`, `GET /exports/{id}` (async job) |
| Search | `GET /firms/{f}/search?q=&scope=` |
| Sync | `/sync/firms/{f}/ops`, `/sync/firms/{f}/snapshot` (section 6.3) |
| Review tasks | `GET /firms/{f}/review-tasks`, `POST /review-tasks/{id}/resolve` |
| Backups | `GET/POST /workspaces/{w}/backups`, `GET /backups/{id}/download`, `POST /backups/{id}/verify` |
| Audit | `GET /workspaces/{w}/audit-events`, `GET /firms/{f}/audit-events` |
| Integrations | `GET /firms/{f}/integrations`, `PUT /integrations/{provider}`, `POST /vouchers/{id}/e-invoice` |
| Health | `GET /healthz`, `GET /readyz` (S1a, outside `/api/v1`), `GET /api/v1/version`, `GET /metrics` (optional) |

Posted vouchers have no `PATCH` or `DELETE`. Long-running work (PDF batches, Tally export,
large reports) returns `202` with a job resource.

## 9. Backups

- Scheduled per-workspace backups in the app backup format
  ([03-architecture.md](03-architecture.md#14-backup-format)), so any server backup restores
  into a standalone app and vice versa.
- Default schedule daily with 7 daily, 4 weekly, and 12 monthly copies; configurable.
- Optional off-site copy to an S3-compatible bucket or an rsync/SFTP target using the
  user's own credentials. Encrypted before upload when a backup passphrase is configured.
- Every backup is verified (checksums, manifest, `PRAGMA integrity_check` on a restored
  temporary copy) before older copies are pruned.
- Restore is an offline admin operation (`openbill-server admin restore`) that starts a new
  sync epoch (section 6.3).
- In E2EE mode the server stores encrypted snapshots it cannot verify beyond checksums.

## 10. Audit log

Two layers, both append-only:

| Layer | Contents |
| --- | --- |
| Domain audit events | Synced `AuditEvent` records from devices and API writes: posting, cancel, restore, deletes, exports |
| Server audit events | Logins and failures, pairing, device revocation, token creation and use, role changes, admin actions, backup and restore, integration calls, quarantined ops |

- Server audit rows form a hash chain (each row stores the hash of the previous row) so
  truncation or edits are detectable. `openbill-server admin audit verify` checks it.
- Audit events never contain passwords, tokens, or full integration payloads.
- Owners and CA seats can export audit events as CSV or JSON.

## 11. Rate limits and abuse controls

| Surface | Default |
| --- | --- |
| Login and pairing redemption | 5 attempts per minute per account and per IP; exponential lockout |
| API token requests | 600 requests per minute per token; burst 100 |
| Sync push | 500 ops per batch; 10 MB per request |
| Attachments | 25 MB per file; per-workspace quota configurable |
| Report and export jobs | 2 concurrent per workspace |

Limits return `429` with `Retry-After`. All values are configurable; a LAN-only install
may relax them.

## 12. Observability

- Structured JSON logs to stdout or a rotating file. Logs carry request IDs, workspace IDs,
  and timings, never invoice contents, party names, phone numbers, GSTINs, or tokens.
- `GET /health/live` and `/health/ready` for process supervisors and containers.
- Optional Prometheus-format `/metrics`, disabled by default and bindable to localhost.
- Optional OpenTelemetry export to a collector the operator runs. No default endpoint.
- No crash reporting, usage analytics, or update pings. An update check, if added, is a
  manual admin command.

## 13. Threat model

| Threat | Controls |
| --- | --- |
| Stolen or lost device | Per-device revocation; short-lived access tokens; quarantine of later ops |
| Malicious or buggy device | Server re-validation of every op; role checks at apply; groups applied atomically; audit trail; review tasks |
| Compromised server or host | E2EE option; devices keep full copies; hash-chained audit; minimal secrets on server |
| Network attacker | TLS required; certificate pinning from the pairing QR; no cleartext except explicit localhost |
| Credential stuffing and brute force | Argon2id; rate limits; lockout; optional TOTP |
| Token leakage | Hashed storage; type-prefixed tokens for secret scanning; scopes and expiry; never logged |
| Cross-workspace data access | Separate database file or schema per workspace; authorization tests per endpoint |
| Malicious backup or snapshot upload | Manifest, checksum, schema, and size validation; restore into a temporary copy first |
| SSRF through integrations or webhooks | Outbound calls only to hosts declared by the enabled provider; no user-supplied URLs except explicit storage targets |
| Integration secret theft | Encrypted at rest with the instance master key; key file mode 0600 outside the database |
| Replay of sync or API writes | `opId` and `Idempotency-Key` deduplication; token binding to device |
| Clock skew | Server `seq` for ordering; HLC only for master field merges |
| Supply-chain compromise | Dependency locking, vulnerability scanning, signed releases, reproducible builds where feasible |
| Accidental exposure via logs | Redaction rules with tests; no request bodies in logs |

## 14. Integrations as plugins

Integrations implement provider interfaces in a `server-integrations` module and are compiled
into the server. They are disabled by default and use the business's own credentials.

| Interface | Example providers | Notes |
| --- | --- | --- |
| `EInvoiceProvider` | IRP access through the user's GSP or direct API access where eligible | IRN, acknowledgement, and signed QR stored as a linked e-document record; the posted voucher is not modified |
| `EWayBillProvider` | E-way bill system through the user's GSP | Generate, cancel, extend; expiry review tasks |
| `MessageProvider` | WhatsApp Business provider, SMS gateway | Opt-in per party; templates stored locally; rate limited |
| `EmailProvider` | SMTP | Invoice and statement delivery |
| `BackupTarget` | S3-compatible storage, SFTP | Off-site copies |

Rules:

- Each provider declares its outbound hosts, required credentials, and data fields sent.
  The admin UI and CLI show these before enabling.
- Calls go through an outbox with retries, idempotency keys, and audit events.
- Results are synced as operations so devices see IRNs and delivery status.
- The same interfaces are usable from devices without a server; the server only centralizes
  credentials and retries.
- Credentials are workspace-scoped, encrypted at rest, and never returned by the API.

## 15. Optional web client

A thin web client is a later milestone, not part of the first server releases.

- First scope: read-only dashboards, reports, GST exports, and a CA portal, served by the
  server under `/app`.
- Write workflows come later and must use the same API and operations as other clients.
- Technology is open: Compose Multiplatform for web (reusing `core-ui`) or server-rendered
  HTML. Decide with a spike once the API is stable.
- Not available in E2EE workspaces.

## 16. Testing

- `core-sync` simulation suite: in-memory server and N simulated devices with random
  offline periods, duplicate delivery, reordering across devices, clock skew, partial
  batches, and server restore. Invariants: all devices converge to the same projection; no
  posted voucher is lost or altered; allocations never exceed balances on any device.
- Repository contract tests run against SQLite and PostgreSQL adapters.
- API contract tests from the OpenAPI document; authorization matrix tests for every role
  and endpoint.
- Upgrade tests: server version N-1 data directory migrates to N.
- Load test on a Raspberry Pi 4: 5 devices, 50,000 vouchers, p95 push/pull under 500 ms.

## 17. Milestones

| Milestone | Deliverable | Depends on |
| --- | --- | --- |
| S0 | Done. Sync protocol spec, `core-sync` with outbox and in-memory server, simulation suite green. S0a: local operation log, device identity, schema 19 (section 6.2.1). S0b: engine, server validation, per-device series, schema 20 (section 6.2.2) | 1.0 |
| S1 | Single-user multi-device sync: Ktor server, SQLite, local accounts, QR pairing, push/pull, snapshots, per-device series, scheduled backups. S1a done: server process, SQLite op log per workspace, accounts and tokens, HTTP sync and seeding, TLS (files or self-signed), JAR, runtime tarball, image, Compose, systemd, install script (section 6.2.3). Next: S1b pairing, S1c snapshots and backups | S0 |
| S2 | Multi-user: memberships, roles, CA seat, device management, review tasks, hash-chained audit | S1 |
| S3 | REST API v1 with OpenAPI, API tokens, CLI remote mode ([11-cli.md](11-cli.md)) | S2 |
| S4 | PostgreSQL adapter, E2EE workspaces, metrics and OpenTelemetry, OCI image | S3 |
| S5 | Integration plugins: SMTP, e-invoice, e-way bill, messaging, off-site backup targets | S3 |
| S6 | Thin web client, read-only first | S3 |

Exit criteria for S1 release: a firm syncs between Android, Windows, and Linux devices
through a Raspberry Pi server; every device works offline for a week and converges on
reconnect; unpairing leaves a complete standalone database; a server backup restores into
a standalone app.

## 18. Open questions

- Drafts: decided in S0b, device-local until S2 (section 6.2.2); S2 decides whether they sync.
- Block-lease numbering defaults: block size and whether it is on by default.
- Whether the server should expose mDNS for LAN discovery or rely on QR pairing only.
- Attachment sync policy on low-storage phones: eager, on demand, or per type.
- Web client technology choice (section 15).
