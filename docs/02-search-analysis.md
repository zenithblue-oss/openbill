# Search Analysis

Search is where billing apps usually fail. A user remembers a party name, half an invoice
number, a phone number, or an item's last four digits, not a database ID. This document
records how the analyzed app handles search and defines a better, shared search layer for
OpenBill on Android, Windows, and Linux.

## 1. Summary of findings

The analyzed app implements search separately in each domain rather than as a shared
service:

| Surface | Implementation observed | Strength | Weakness |
| --- | --- | --- | --- |
| Invoice list | Server query with text plus type, status, date, and mode filters | Rich filters | Implemented via a server endpoint in the analyzed build; offline behavior was not verified; no global entry |
| Item list | Server search with paging and 300 ms debounce | Scales to large catalogs | Implemented via a server endpoint in the analyzed build; offline behavior was not verified |
| Party/contact picker | Paged local substring match over display name and phone number, ordered by name | Works offline and fast for small data | No ranking, no fuzzy match, full scan |
| HSN/SAC | Dedicated server lookup by search text | Large taxonomy handled remotely | Implemented via a server endpoint in the analyzed build; offline behavior was not verified; no offline fallback observed |
| Dashboard | 300 ms debounce on a dashboard search field | Responsive | Limited to dashboard context |
| Command palette | Not observed | - | No single search surface across domains |

OpenBill should keep the good parts (debounce, paging, filters) and replace the fragmented
backends with one local-first search service.

## 2. Evidence and method

Evidence comes from static analysis of version 8.17.7 and the public help center. No
production traffic was intercepted. Only behavioral observations are summarized here; raw
evidence, including the APK and decompiled output, remains private and is not distributed.

### Observed behaviors

- Invoice search combines text with type, status, date, and mode filters.
- Item search is paged and debounced, with SKU, category, stock, batch, and serial filters.
- Party lookup is a paged local substring match over name and phone.
- HSN/SAC lookup uses a dedicated search path and a selection bottom sheet.
- Dashboard search uses a 300 ms debounce.
- Search analytics record the query, result count, page, and source.
- No unified command palette was observed.

### Invoice search

The public help article "How to Search for an Invoice" describes:

- Search from the dashboard invoice list.
- Optional invoice-type filters below the search bar.
- Search by party name or invoice number.
- Results open the invoice for viewing, reminders, or download.
- Date-range filtering is not part of the search bar; it lives in reports.

Client code shows a voucher list request with paging, voucher type, e-invoice status,
search text, date range, and transaction mode fields. The client request exposes filters
that suggest the backend supports more than the search bar shows. The app also has a
separate CRM voucher list interface with sorting, date range, loyalty, and
conversion-status filters. Search is therefore a set of screens over a shared voucher
query, not a single global feature.

### Item search

The item list uses Paging 3 and a paged search request. The request carries paging, search
text, SKU, category, sort, batch/serial enablement, stock state, and item type fields. It is
implemented via a server endpoint in the analyzed build, so offline behavior was not
verified. Results are mapped into an item list response and wrapped in a paging source. A
search analytics event records the query, result count, page, and source. Batch and serial
settings are part of the request, so results can reflect whether batch or serial selection
is required.

The dashboard applies a 300 ms debounce before running its search. The code creates a
debounced flow from the text field and launches collection in the screen's lifecycle. A
shared inline search bar component exists for Compose screens.

### Party and contact search

The cached contact directory uses local paged queries:

- All contacts, ordered by display name.
- Contacts matching a search term, ordered by display name.

The lookup is a paged local substring match over display name and phone number, ordered by
name, with no ranking or full-text index. It is simple and predictable but has three
problems:

1. A leading substring match cannot use an ordinary index efficiently.
2. There is no ranking; a prefix match and a middle-of-string match score the same.
3. Phone numbers are not normalized, so formatting differences cause misses.

### HSN/SAC search

HSN/SAC lookup is implemented via a server endpoint in the analyzed build. The client sends
search text and displays results in a bottom sheet; offline behavior was not verified. The
client request suggests the taxonomy is handled remotely. OpenBill should ship a versioned
offline dataset and allow updates, because HSN selection is part of core invoicing.

### Units and categories

Selection bottom sheets have their own local search filters. These are small lists, so a
simple case-insensitive contains filter is adequate.

## 3. What the analyzed app gets right

- **Debounce.** 300 ms prevents a request per keystroke.
- **Paging.** Large invoice and item lists do not load everything at once.
- **Domain filters.** Invoice type, stock state, and category filters are visible and
  composable with the text query.
- **Contextual search.** Searching inside the item picker or party picker keeps the scope
  obvious.

## 4. What OpenBill should do differently

1. **One search service, many surfaces.** The same query engine powers global search,
   invoice search, item search, party search, and pickers.
2. **Offline first.** Search must work with no network. A server may improve ranking later,
   but it cannot be required for billing.
3. **Ranked results.** Exact invoice number and phone number matches rank above substring
   matches. Prefix matches rank above middle matches.
4. **Normalized input.** Phone numbers are normalized to digits; GSTINs are uppercased;
   invoice numbers ignore separators where safe; Unicode is normalized.
5. **Explainable matches.** Each result shows which field matched and a highlighted
   snippet.
6. **Keyboard-first desktop.** Ctrl/Cmd+K opens a command palette with arrow-key
   navigation, Enter to open, and shortcuts for actions.
7. **No dead ends.** Zero results offer spelling suggestions, filter removal, and a path to
   create the missing party or item.

## 5. Proposed search architecture

### Domain model

```text
SearchRequest
  rawQuery: String
  normalizedQuery: String
  tokens: List<String>
  scope: ALL | INVOICES | PARTIES | ITEMS | PAYMENTS | EXPENSES | REPORTS
  filters: SearchFilters
  limit: Int
  offset: Int

SearchFilters
  dateRange
  voucherTypes
  invoiceStatus
  stockState
  partyId
  itemCategoryId
  amountRange
  includeDeleted

SearchHit
  type
  id
  title
  subtitle
  matchedFields
  score
  highlights
  deepLink
```

### Storage

Use SQLite FTS5 through Room KMP with a bundled SQLite build so Android and desktop behave
identically.

Virtual tables:

- `invoice_fts`: invoice number, party name, party phone, item names, notes.
- `party_fts`: display name, phone, GSTIN, email, address.
- `item_fts`: name, SKU, HSN/SAC, barcode, category, description.
- `payment_fts`: party name, reference, mode, notes.
- `expense_fts`: category, vendor, notes.
- `hsn_fts`: code, description, chapter.

Design rules:

- Keep the canonical relational tables as the source of truth.
- Update FTS rows in the same transaction as the source row.
- Store normalized search columns separately: digits-only phone, uppercase GSTIN, folded
  item name.
- Use `bm25()` with field weights: exact identifiers highest, then prefixes, then body
  text.
- Maintain a prefix index for type-ahead; do not run leading-wildcard queries on large
  tables.
- Support `external content` FTS tables to avoid duplicating full documents when practical.
- Add triggers or repository-level synchronization, backed by a consistency test that
  compares FTS counts with source counts.

### Query behavior

- Trim and normalize input; collapse whitespace.
- Minimum length: 1 character for barcode and exact-ID scopes, 2 characters for global
  text search. Barcode scanners often emit Enter, so exact lookup runs immediately.
- Debounce 250-300 ms. Cancel the previous query when a new one arrives.
- Return the first page quickly, then page in the background.
- Group results by type in global search, with per-group limits and a "see all" action.
- Empty query shows recent searches and suggested actions, never a full table scan.
- Deleted records are excluded by default and available through an explicit filter.
- Amount search accepts `1,250`, `1250`, and `1250.00`.
- Date shorthand such as `today`, `this month`, or `fy 25-26` may be added later; it is
  not required for 1.0.

### Ranking

Suggested weights:

| Signal | Weight |
| --- | --- |
| Exact invoice number or barcode | 100 |
| Exact normalized phone | 90 |
| Exact party or item name | 80 |
| Prefix match on name or number | 60 |
| Token match in title | 40 |
| Body or notes match | 20 |
| Recency boost | Up to 10 |
| Frequent-open boost | Up to 5 |

Tie-breakers: most recent document date, then type priority (invoice before quotation),
then stable ID.

### Android experience

- Search icon in the top bar and a search field on list screens.
- Global search opens a full-screen surface with scope tabs or chips.
- Filter chips show active filters and can be removed individually.
- Result rows show type icon, title, party, date, amount, and status.
- Search state survives rotation and process death where practical.
- TalkBack announces result count and the matched field.
- No search history is uploaded; history can be cleared in settings.

### Windows and Linux experience

- Ctrl/Cmd+K opens a command palette overlay.
- Type to search; arrow keys move; Enter opens; Ctrl/Cmd+Enter opens in a new window later.
- Tab cycles scopes: All, Invoices, Parties, Items, Payments.
- Typing `>` enters command mode for actions such as "new invoice" or "backup now".
- Filters appear in a right-side panel for advanced searches.
- Results can be opened in a preview pane without losing the query.
- Escape closes the palette and restores focus to the previous control.
- Search works in the invoice editor for party and item pickers with the same ranking.

## 6. Performance budgets

| Scenario | Target |
| --- | --- |
| Keystroke to first result, 50k invoices, mid-range phone | p95 under 100 ms |
| Keystroke to first result, 500k invoices, desktop | p95 under 150 ms |
| Exact barcode lookup | Under 30 ms |
| FTS index update per posted invoice | Under 20 ms |
| Search memory overhead at 500k invoices | Under 100 MB |

Measurement method:

- Generate deterministic fixtures at 1k, 50k, and 500k invoices.
- Run benchmarks on a low-end Android device profile and a 2-core desktop VM.
- Track query plans with `EXPLAIN QUERY PLAN`; fail CI if a leading wildcard appears in a
  hot path.
- Record frame timing for the search UI, not just database time.

## 7. Test strategy

Unit tests:

- Normalization: phone formats, GSTIN case, Unicode, punctuation, whitespace.
- Tokenization and query parsing for quotes, prefixes, and filters.
- Ranking order for curated fixtures.
- Highlight offsets for overlapping tokens.
- Empty, one-character, and very long queries.

Integration tests:

- FTS rows stay in sync after create, update, delete, cancel, and restore.
- Search results exclude deleted records by default.
- Paging returns stable, non-duplicated results.
- Filters compose correctly with text queries.
- Barcode exact lookup wins over fuzzy name matches.

UI tests:

- Debounce does not fire a query per keystroke.
- Stale results never replace newer results.
- Keyboard navigation works on desktop.
- TalkBack reads result summaries on Android.
- Zero-state offers useful actions.

## 8. Delivery plan

| Phase | Work |
| --- | --- |
| Foundation | FTS schema, normalization, ranking, fixtures, benchmark harness |
| Billing alpha | Invoice, party, and item search; Android search surface; desktop palette |
| Inventory beta | Stock filters, barcode, batch, and serial search |
| Money beta | Payment and expense search; saved filters |
| Reports | Report search and export-aware filters |
| Hardening | Large-database benchmarks, accessibility, query-plan checks |

## 9. Privacy and security

- Search runs locally by default; queries are not sent to a server.
- Search history is stored locally and can be disabled or cleared.
- No contact data is uploaded for indexing.
- Logs redact query text and phone numbers.
- If optional sync is added, search indexes remain local unless the user opts in.
- Backup includes the search index only if it can be rebuilt; rebuilding is preferred to
  reduce archive size.

## 10. Acceptance criteria for 1.0

- Invoice search by number, party name, and amount works offline.
- Item search by name, SKU, HSN, and barcode works offline with stock filters.
- Party search by name and phone handles common Indian phone formats.
- HSN/SAC search works offline after the dataset is installed.
- Global search returns grouped, ranked, highlighted results on all three platforms.
- Ctrl/Cmd+K opens search on desktop; the search icon opens it on Android.
- p95 latency targets in section 6 are met.
- Stale-query cancellation and debounce are covered by UI tests.
- No search query leaves the device by default.
