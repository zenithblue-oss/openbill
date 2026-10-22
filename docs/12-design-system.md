# Design System

Status: adopted for the billing alpha. Applies to every shared screen in `core-ui`, on Android
phones and tablets and on the Windows and Linux desktop app.

OpenBill uses **stock Material 3** components from Compose Multiplatform with a small set of
OpenBill tokens on top. We do not build custom look-alikes of Material components, and we do
not imitate any other billing product's layout, colours, wording or illustrations (see
[06-clean-room-policy.md](06-clean-room-policy.md)). Everything here is original.

Code lives in `core-ui/src/commonMain/kotlin/app/openbill/ui/theme/` (tokens and theme) and
`.../ui/components/` (shared components). Platform hooks are `expect`/`actual` in
`theme/Platform.kt`.

## 1. Principles

1. **Numbers first.** A billing app is read for amounts. Figures are tabular, right-aligned,
   never wrapped and never truncated.
2. **Stock Material 3.** Use the M3 component that fits; adapt with tokens, not forks.
3. **Calm, short motion.** Motion explains where things came from. Nothing exceeds 300 ms and
   nothing blocks input. Reduced motion turns it off.
4. **Accessible by default.** Contrast, touch targets, labels and large text are part of done.
5. **One layout, three widths.** Compact phones, medium tablets/landscape, expanded desktop.

## 2. Colour

### 2.1 Brand seed

| Token | Value | Notes |
| --- | --- | --- |
| Seed ("Peacock") | `#00677A` | HCT hue 218, chroma 36, tone 40. A deep peacock blue-teal. |
| Tertiary accent ("Marigold") | `#E8A317` source | HCT hue ~75. Highlights only, never status. |

The seed is deliberately away from plain blue and from the success green, so brand colour and
"posted" status never look alike. Primary on surface measures 6.4:1 in light mode
(`#00687B` on white) and 10.9:1 in dark mode (`#56D6F5` on `#101415`).

### 2.2 Schemes

Tonal palettes were generated with the Material colour utilities and then **hand-tuned**:

- Primary keeps chroma 48 (stronger than tonal-spot) so the brand reads on paper-like surfaces.
- Secondary is the seed hue at chroma 16 for chips, selection and avatars.
- Neutrals carry chroma 5 of the seed hue: slightly cool paper rather than flat grey.
- "On container" roles use tone 10 (light) / 90 (dark) for legible small text.

| Role | Light | Dark |
| --- | --- | --- |
| primary / onPrimary | `#00687B` / `#FFFFFF` | `#56D6F5` / `#003641` |
| primaryContainer / on | `#AEECFF` / `#001F26` | `#004E5D` / `#AEECFF` |
| secondary / onSecondary | `#4B6269` / `#FFFFFF` | `#B2CBD3` / `#1D343A` |
| secondaryContainer / on | `#CEE7EF` / `#061F25` | `#344A51` / `#CEE7EF` |
| tertiary / onTertiary | `#7F5700` / `#FFFFFF` | `#FCBB4A` / `#432C00` |
| tertiaryContainer / on | `#FFDEAE` / `#281900` | `#604100` / `#FFDEAE` |
| surface / onSurface | `#F6FAFC` / `#181C1E` | `#101415` / `#DFE3E5` |
| surfaceContainerLow…Highest | `#F1F4F6` … `#DFE3E5` | `#181C1E` … `#313537` |
| onSurfaceVariant / outline | `#3D494C` / `#6D797D` | `#BCC9CD` / `#869397` |
| error / errorContainer | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` |

The full set is in `theme/Color.kt` (`OpenBillLightColors`, `OpenBillDarkColors`).

### 2.3 Semantic (extended) colours

Material 3 has no success or warning roles, so OpenBill adds them in `ExtendedColors`
(`LocalExtendedColors`). Hues were harmonised toward the seed.

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| success / successContainer / on | `#006D43` / `#92F7BC` / `#002111` | `#76DAA1` / `#005231` / `#92F7BC` | Posted |
| warning / warningContainer / on | `#7D5800` / `#FFDEA9` / `#271900` | `#F6BD51` / `#5E4100` / `#FFDEA9` | Overdue, attention (reserved) |
| error / errorContainer (M3) | as above | as above | Cancelled, destructive |
| secondaryContainer (M3) | as above | as above | Draft |

Semantic colours **do not follow dynamic colour**: a posted invoice reads as "done" whatever
the wallpaper.

### 2.4 Dynamic colour and theme setting

More → Appearance offers:

- **Theme:** System (default), Light, Dark — a segmented button.
- **Use device colors:** off by default. On Android 12+ it swaps the OpenBill scheme for the
  wallpaper scheme (`dynamicLight/DarkColorScheme`). Disabled with an explanation elsewhere.

Settings are per device, stored outside the business database: Android uses a private
`SharedPreferences` file (`openbill_ui`), desktop uses `java.util.prefs` (registry on Windows,
`~/.java` on Linux). Unknown or unreadable values fall back to defaults. Desktop never uses
dynamic colour; it follows the OS theme unless overridden, as required by
[07-desktop.md §4](07-desktop.md).

### 2.5 System bars

Android draws edge-to-edge (`enableEdgeToEdge`). `SystemBarsAppearance` sets light or dark
status- and navigation-bar icons from the **resolved app theme**, not the OS theme, so a forced
Light theme on a dark phone still has legible bar icons.

## 3. Typography

The M3 baseline type scale with the platform font (Roboto on Android, the OS UI font on
desktop), so Indic scripts in party and item names render with system fallbacks.

Every style enables **tabular figures** (`fontFeatureSettings = "tnum"`): amounts, quantities,
dates and invoice numbers align in columns and do not jitter while they animate.

| Use | Style |
| --- | --- |
| Screen title (collapsing lists) | M3 medium top app bar (headlineSmall → titleLarge) |
| Card title, party name in detail | titleLarge / titleMedium |
| List headline | bodyLarge (ListItem default) |
| Section header | titleSmall in `primary`, marked as a heading for screen readers |
| Field and table labels | bodyMedium in `onSurfaceVariant` |
| Hero amount (month sales) | headlineMedium |
| Editor grand total | headlineSmall |
| Line amount | titleSmall |
| Status chip | labelMedium |

## 4. Shape, spacing and elevation

**Shape** (`OpenBillShapes`): extraSmall 4, small 8 (status chips), medium 12 (cards, banners),
large 16, extraLarge 28 (sheets, search, empty-state badge).

**Spacing** (`Spacing`, 4 dp grid): xxs 2, xs 4, sm 8, md 12, lg 16, xl 24, xxl 32.

| Token | Value | Rule |
| --- | --- | --- |
| `gutter` | 16 dp | Page side padding, compact |
| `gutterWide` | 24 dp | Page side padding, medium/expanded |
| `FormSpacing` | 8 dp | Gap between form fields, so supporting text never touches the next field |
| `contentMaxWidth` | 720 dp | Forms, detail and lists are centred and capped on wide windows |
| `fabClearance` | 96 dp | Bottom list padding under an extended FAB |

**Elevation** (`Elevation`): tonal levels 0, 1, 3, 6, 8, 12 dp. Surfaces are separated by
tonal colour; shadows only on FABs, menus and the editor's sticky totals bar (level 2).

## 5. Motion

Tokens in `Motion` (`theme/Tokens.kt`).

| Token | ms | | Easing | Curve |
| --- | --- | --- | --- | --- |
| SHORT1–4 | 50 / 100 / 150 / 200 | | Emphasized | (0.2, 0, 0, 1) |
| MEDIUM1–2 | 250 / 300 | | EmphasizedDecelerate | (0.05, 0.7, 0.1, 1) — entering |
| (cap) | 300 | | EmphasizedAccelerate | (0.3, 0, 0.8, 0.15) — leaving |
| | | | Standard | (0.2, 0, 0, 1) |

| Pattern | Spec |
| --- | --- |
| Push / pop between screens | Shared axis X: 30 dp slide, 300 ms (decelerate in / accelerate out), outgoing fades in 100 ms, incoming fades 200 ms after 100 ms |
| Tab switch, replace (editor → detail) | Fade through: out 100 ms, in 200 ms after 100 ms with scale 0.94 → 1 |
| Bottom bar show/hide | Expand 250 ms / shrink 200 ms with the route change |
| Full-screen editor (line, quick add) | Slide up a quarter height + fade, 300 ms in / 200 ms out |
| List insert / remove / reorder | `animateItem()`: fade 200 / 150 ms, placement 250 ms |
| Amounts | Roll: old figure slides out, new slides in (up when larger, down when smaller), 200 ms |
| Expandable totals | `animateContentSize` 250 ms; chevron rotates 200 ms |
| Extended FAB | Collapses to icon when scrolling down, expands when scrolling up (M3 built-in) |
| Filter chip | Check mark fades and grows in, 150–200 ms |
| Primary button press | Ripple plus scale to 0.96, 100 ms |

Amounts roll rather than count through intermediate values: an invoice should never show a
figure that was not real.

**Reduced motion.** When Android's animator duration scale is 0 (Developer options or
Accessibility "Remove animations"), `LocalReducedMotion` is true and every spec above becomes
`snap()` / `EnterTransition.None`. The value is observed live. Compose also honours the
system scale for built-in component animations. Desktop has no system signal yet.

No skeletons or shimmer: local reads finish in well under a frame budget, so a spinner is
shown only for the rare slow load.

## 6. Components and usage rules

| Need | Component | Rules |
| --- | --- | --- |
| Top-level list title | `MediumTopAppBar` + `exitUntilCollapsedScrollBehavior` (`CollapsingTopBar`) | Invoices, Parties, Items, More |
| First run | `LargeTopAppBar` | Setup only |
| Detail and form title | `TopAppBar` (`AppTopBar`) with back | Destructive action as an icon with a label for screen readers |
| Primary navigation | `NavigationBar` (compact) / `NavigationRail` (medium, expanded) | Only on the four home tabs; hidden on editors so unsaved-change guards cannot be bypassed |
| Primary create action | `ExtendedFloatingActionButton` | One per list; collapses on scroll |
| Search | `DockedSearchBar` with `SearchBarDefaults.InputField` (`AppSearchBar`) | Scrolls with the list header so landscape keeps room for rows |
| Filters | `FilterChip` (`SelectableChip`) | Show counts where cheap ("Drafts (1)") |
| Rows | `ListItem` | Monogram avatar (first letter, decorative); trailing amount never wraps |
| Grouped content | `ElevatedCard` (summary header), `OutlinedCard` (lines, totals, chosen party), filled `Card` in primaryContainer (sales summary) | |
| Choose from a list | `ModalBottomSheet` (`PickerSheet`) | Party, item, state, unit, GST rate, financial year. Searchable when long; selected option shows a check |
| Date | `DatePickerDialog` (`DateField`) | Shown as "22 Oct 2026". No typed dates |
| Multi-field sub-form | In-window full-screen dialog (`FullScreenEditor`) | Line editor and quick-add party. Confirm in the top bar so the keyboard never hides it; body pads for the IME |
| Confirmation | `AlertDialog` | Destructive verb in `error` colour; "Keep" as the safe choice |
| Unsaved changes | `DiscardChangesDialog` | Every editor: invoice, party, item, business details. Offers Save, Discard, Keep editing |
| Feedback and undo | `Snackbar` via `LocalAppMessages` | "Draft saved", "Invoice posted", "Removed X — Undo" (party and item) |
| Status | `StatusChip` | Icon + word + colour |
| Empty list | `EmptyState` | material-icons-core outline icon in a secondaryContainer badge, one sentence, one action |
| Theme choice | `SingleChoiceSegmentedButtonRow` | |
| On/off setting | `ListItem` + `Switch`, whole row toggleable | |

Icons: navigation destinations use OpenBill's own line icons (`AppIcons`); all other icons come
from `material-icons-core`. No illustrations.

Wording: short, plain, Indian-English business terms (party, GSTIN, place of supply, HSN/SAC).
Messages say what happened and what to do next.

## 7. Money and number display

- Always format through `Fmt` from a `Money`/`Quantity`/`Rate` value; never format a `Double`.
- Rupee sign directly before the figure, Indian grouping: `₹12,34,567.89`. Always two decimals.
- Deductions use a true minus sign: `−₹50.00` (`Fmt.inrDeduction`).
- Amounts are right-aligned, `maxLines = 1`, `softWrap = false`; the **label** wraps instead.
- Quantities carry their unit: `2.5 KG × ₹740.00`.
- Percentages without trailing zeros: `18%`, `0.25%`, `12.5%`.
- Dates: `22 Oct 2026` on screen; `22-10-2026` on the PDF.
- Grand totals show "incl. GST ₹x" beneath the label; amount in words on the detail screen.
- Money inputs use a `₹` prefix and the decimal keyboard; discount accepts `25` or `10%`.

## 8. Accessibility

- Contrast: text ≥ 4.5:1, large text and icons ≥ 3:1, in both schemes (checked on the tonal
  values above; dynamic schemes are trusted to the platform).
- Touch targets ≥ 48 dp (M3 defaults); picker fields expose a single button with the label
  and current value ("State: Karnataka (29)").
- Colour is never the only signal: status chips carry an icon and a word; errors carry text.
- Icon-only buttons have content descriptions; decorative icons and avatars have none.
- Section headers and empty-state titles are marked as headings.
- Text scales to 200 %: no fixed heights on text containers; amounts keep one line while labels
  wrap; date and place of supply stack on compact widths.
- Toggle rows announce state (`Role.Switch`); the totals bar announces "Breakdown shown/hidden".
- Reduced motion is honoured (section 5).
- Desktop: every action reachable by keyboard; dialogs close with Escape.

## 9. Platform adaptations

| Width class | Breakpoint | Navigation | Layout |
| --- | --- | --- | --- |
| Compact | < 600 dp | Bottom `NavigationBar` | Single column, 16 dp gutters, date and place of supply stacked |
| Medium | 600–839 dp | `NavigationRail` | Content centred, max 720 dp, 24 dp gutters, paired fields side by side |
| Expanded | ≥ 840 dp | `NavigationRail` | As medium. List-detail panes are a later milestone |

The class is computed from the window, not the device, so a phone in landscape (e.g. 915 dp
wide) gets the rail, and a narrow desktop window gets the bottom bar. Phone landscape keeps
list headers (summary, search, filters) inside the scrolling list so rows remain visible.

Desktop specifics (menus, shortcuts, command palette, tables) follow
[07-desktop.md §4](07-desktop.md) and live in the desktop shell; they use these same tokens.

## 10. Definition of done for UI work

- Uses tokens and stock M3 components from this document.
- Light, dark, dynamic on/off, font scale 1.3 and 2.0, landscape and animator scale 0 checked.
- Empty, loading and error states present; unsaved-change guard on every editor.
- New pure logic (formatting, settings, rules) has unit tests in `core-ui/src/commonTest`.
- Screenshots in a pull request are of OpenBill only.
