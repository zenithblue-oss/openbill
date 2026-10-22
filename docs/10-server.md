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
[00-reverse-engineering.md](00-reverse-engineering.md) and
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
| HTTP server | Ktor server (Netty or CIO engine) | Kotlin and coroutines end to end; same kotlinx.serialization models and Ktor client already chosen for app sync; small footprint; no reflection-heavy container |
| Serialization | kotlinx.serialization via `core-api-contract` | One DTO set for server, apps, and CLI |
| SQLite storage | Room KMP (`core-database`) on JVM for projections; plain JDBC for the op log | Projection is byte-compatible with the app schema and backup format |
| PostgreSQL storage | JDBC adapters implementing `core-domain` repository ports | Room does not target PostgreSQL; both adapters pass one repository contract test suite |
| Migrations | Room migrations for SQLite projection; versioned SQL migrations for PostgreSQL and instance tables | Explicit and tested, like the app |
| Password hashing | Argon2id | Memory-hard, current recommendation |
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

### 6.3 Exchange

| Step | Endpoint | Behavior |
| --- | --- | --- |
| Push | `POST /api/v1/sync/firms/{firmId}/ops` | Batch of up to 500 ops in author order. Each op is accepted, deduplicated, or rejected with a reason. Response carries assigned `seq` values. |
| Pull | `GET /api/v1/sync/firms/{firmId}/ops?after={cursor}` | Ops after the device's cursor, paginated, including the device's own ops. |
| Ack | Implicit | The cursor is the last `seq` the device has applied. The server tracks it per device for diagnostics and retention only. |
| Snapshot | `GET /api/v1/sync/firms/{firmId}/snapshot` | Backup-format archive at a known `seq` for first sync, long-offline devices, and op-log compaction. |

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
  series per device.
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
| Health | `GET /health/live`, `GET /health/ready`, `GET /metrics` (optional) |

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
| S0 | Sync protocol spec, `core-sync` with outbox and in-memory server, simulation suite green | 1.0 |
| S1 | Single-user multi-device sync: Ktor server, SQLite, local accounts, QR pairing, push/pull, snapshots, per-device series, scheduled backups | S0 |
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

- Whether drafts should sync at all in S1, or stay device-local until S2.
- Block-lease numbering defaults: block size and whether it is on by default.
- Whether the server should expose mDNS for LAN discovery or rely on QR pairing only.
- Attachment sync policy on low-storage phones: eager, on demand, or per type.
- Web client technology choice (section 15).
