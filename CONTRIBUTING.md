# Contributing to OpenBill

OpenBill is an Apache-2.0, clean-room billing, invoicing, inventory, and payments
project for Indian MSMEs. Android is the first target, with Windows and Linux
following from the same Kotlin codebase.

This guide explains how to contribute as the application scaffold and shared
modules evolve.

## Project status

The Kotlin Multiplatform scaffold, the Android app, the desktop app, and the
shared modules exist. The test harness and feature work are still early. The
specifications remain authoritative for scope and behavior.

## Build and test

Prerequisites: JDK 21, Android SDK platform 36 and build-tools 36.1.0, and a
`local.properties` file at the repository root with `sdk.dir` pointing to the
Android SDK.

Run these commands from the repository root:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:lintDebug
./gradlew :desktopApp:compileKotlin
```

The debug APK is written to
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`. To run the desktop
app, use `./gradlew :desktopApp:run`.

## Working on the CLI and server modules

The `openbill` CLI ([docs/11-cli.md](docs/11-cli.md)) and the optional OpenBill Server
([docs/10-server.md](docs/10-server.md)) are specified but not scaffolded yet. The
CLI direct mode ships with 1.0; the server, sync, and CLI remote mode are post-1.0.
Planned Gradle projects follow the flat naming in `settings.gradle.kts`: `:cli`,
`:server`, `:core-api-contract`, `:core-sync`, and `:platform-jvm`.

- Both are JVM surfaces over the shared core. They must not depend on `core-ui`, any
  `feature-*` module, Compose, or AWT/Swing (docs/03, section 3). A new CLI command
  calls an existing `core-domain` use case; it does not hold business logic or write SQL.
- Request and response shapes live in `core-api-contract`: DTOs only, with money as a
  decimal string and never a JSON float. CLI `--json` output, API payloads, and sync
  payloads share that schema.
- The CLI `--json` field names and exit codes become a public contract at 1.0. Treat
  changes to them as breaking, and get maintainer review.
- CLI changes need golden-output tests. Once `:cli` exists, run `./gradlew :cli:test`
  and, to regenerate goldens for review, `./gradlew :cli:test -PupdateGoldens`. Goldens
  must pass on Linux and Windows.
- Sync and server changes need a simulation-suite or API contract test, and an updated
  OpenAPI document with a reviewed diff. Conflict rules are fixed in docs/10 before any
  code that depends on them; propose rule changes in an issue first.
- Never log tokens, invoice contents, party names, phone numbers, or GSTINs. Use fake
  credentials and synthetic data in tests; never commit a real server key or token.
- Integrations (e-invoice, e-way bill, messaging) use the business's own credentials and
  are disabled by default.

## Ways to contribute now

- Review the specifications. Read README.md, ROADMAP.md, and the documents in
  `docs/`, then point out contradictions, missing states, or unclear acceptance
  criteria.
- Report ambiguities. Open an issue that quotes the relevant section and
  proposes a resolution.
- Contribute GST edge cases. Cover place of supply, inclusive and exclusive tax,
  cess, discounts, rounding, credit notes, and financial-year boundaries. Cite
  public law, official schedules, or published guidance, and state the expected
  result. Do not include data from a real business.
- Give accessibility feedback. Describe the platform, assistive technology, and
  the workflow that failed. Feedback on TalkBack, keyboard navigation, contrast,
  and large text is useful before UI code exists.
- Share original print-layout samples. A4, A5, and thermal layouts are welcome
  when you own the rights to them. Use fictitious businesses and synthetic
  amounts. Do not submit invoices or screenshots from a commercial product.

## Clean-room rules

OpenBill is developed from behavioral specifications and public standards, not
from another product's implementation. Read
[docs/06-clean-room-policy.md](docs/06-clean-room-policy.md) before contributing.

Do not submit:

- Decompiled or reconstructed code, including Java, Kotlin, or smali output.
- APK, AAB, XAPK, DEX, JAR, or native library artifacts from another product.
- Competitor assets, icons, themes, fonts, layouts, strings, or database schemas.
- Scraped data, private API responses, credentials, or customer records.
- Code or assets that you do not have the right to license under Apache-2.0.

If you are unsure whether something is allowed, ask in an issue before opening a
pull request. The same rules apply to AI-assisted work.

Every pull request must include this provenance statement, copied from the
clean-room policy:

```text
Provenance:
- [ ] This contribution is original work.
- [ ] It contains no code, assets, strings, or data copied from a proprietary product.
- [ ] Any third-party code is compatible with Apache-2.0 and is attributed.
- [ ] Any test data is synthetic or properly licensed.
```

Mark each box only after you have checked it. Reviewers may ask for details
about how a contribution was produced.

## Issues and pull requests

1. Search existing issues and discussions before opening a new one.
2. Open a focused issue for bugs, specification gaps, or non-trivial changes.
   Describe the problem, the proposed scope, and alternatives you considered.
3. Keep pull requests small and limited to one concern.
4. Link the pull request to its issue, for example with `Closes #123`.
5. Include tests and documentation with the change, or explain why neither is
   needed.
6. Add the provenance statement to the pull request description.
7. Describe how you verified the change, including the platforms you tested.

Draft pull requests are welcome for early feedback. Maintainers may close a
change that cannot demonstrate provenance or that expands scope without
discussion.

## Commit style

- Write an imperative subject line, ideally 72 characters or fewer, with no
  trailing period.
- Use the body to explain why the change is needed, not just what changed.
- Keep one logical change per commit and avoid generated noise.
- Conventional Commits are optional but preferred: `feat:`, `fix:`, `docs:`,
  `test:`, or `chore:`.

Example:

```text
fix: reject negative quantities on invoice lines

Posting a negative quantity produced a credit-like line without an
associated credit note. Validate quantities at the domain boundary and
cover the case with a table-driven test.
```

## Code style

- Follow the official Kotlin coding conventions and the existing project style.
- Android lint is available now via `./gradlew :androidApp:lintDebug`. Shared
  formatting and static-analysis checks are planned under E0.2.
- Prefer the standard library and existing dependencies.
- Do not add a dependency without justification. Explain its purpose, license,
  size, maintenance status, and privacy or security impact in the pull request.
- Keep domain and tax logic independent of Android-only APIs.

## Testing expectations

- Add unit tests for domain logic, especially tax calculation, rounding, money,
  and ledger behavior.
- Add migration tests for every schema change, including upgrades from the
  previous schema version.
- Cover UI states such as loading, empty, error, and success.
- Verify behavior on Android and desktop when a change affects shared code.
- Add CLI golden-output tests for command changes, and sync or API contract tests for
  server changes (docs/05, sections 12 to 15).
- Keep tests deterministic and offline. State any platform-specific verification
  in the pull request.

## Documentation

Update README.md, the relevant files in `docs/`, or user-facing help text when a
change alters behavior, data formats, migrations, or backup compatibility. If a
change affects scope or sequencing, update ROADMAP.md as part of the same pull
request.

## Licensing

Contributions are accepted under the Apache License, Version 2.0. By submitting a
pull request, you confirm that you have the right to license the work under
Apache-2.0 and that any third-party material is compatible and attributed.

## Data and secrets

Do not commit secrets, signing keys, tokens, personal data, or real customer
data. Use synthetic fixtures and fictitious business names. Remove local paths,
account identifiers, and other sensitive details from logs and screenshots.

## Code of conduct

Be respectful, assume good faith, and keep discussion focused on the work.
Harassment, discrimination, and personal attacks are not acceptable. Report
conduct concerns privately to the maintainers through the repository.

## Security disclosure

Do not open a public issue for a vulnerability. Use the repository's private
security advisory feature to contact the maintainers. Include the affected
version or commit, reproduction steps, and the expected impact. Allow time for a
fix before any public disclosure.
