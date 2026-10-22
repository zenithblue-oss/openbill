# Brand identity

Status: adopted. Everything here is original work released under Apache-2.0 with the code;
see section 9 for the trademark and clean-room note.

## 1. Name

"OpenBill": one word, capital O and capital B. Never "Open Bill", "openbill", "OPENBILL" or
"Openbill" in prose. Package ids and URLs stay lowercase (`app.openbill`). Use "OpenBill" as a
proper noun, not a verb.

## 2. Tagline options

1. "Free GST billing, on your device." (preferred)
2. "Bills that stay yours."
3. "Open, offline, GST-ready."

## 3. Voice and tone

Plain, calm, precise. Short sentences, active voice, numbers before adjectives. Say what
happened and what to do next; no exclamation marks, no jargon beyond standard GST terms, no
blame in errors. Reassure on privacy by stating facts ("stored on this device"), not
slogans. English first; Indian number grouping and rupee symbol in all examples.

## 4. Logo

**Concept.** A paper bill with a folded top-right corner and a zigzag torn bottom edge. In the
middle sits an "O" ring that is left open at the top right: the open in OpenBill, and a hint
of a pen stroke. A single bar below is the total line. The Marigold fold is the only accent.

**Construction.** Master grid is 64 units. Receipt: x 12-52, y 4-60 (40 x 56, 5:7). Fold: 12
unit triangle at the top right. Ring: centre (32,27), outer radius 12, inner 7, open between
295 and 335 degrees. Total bar: x 20-44, y 45-49. App icon tile: 64 units, 14 unit corner
radius, mark at 80 %. Geometry lives in `branding/tools/generate_brand.py`.

**Clear space.** Keep at least the ring's outer radius (12 units, about 1/4 of mark width) free
on all sides. **Minimum size:** mark 16 px (tile) or 12 px (bare mark); full logo 96 px wide.
Below 24 px the bar and fold remain readable; do not add detail.

**Variants.** `logo-mark.svg` (Peacock), `logo-full.svg` (wordmark: "Open" Peacock, "Bill"
ink, bold geometric sans), `logo-mono.svg` (one colour, for themed icons and stamps),
`logo-app-icon.svg` (tile).

## 5. Colour

| Name | Hex | Use | Contrast |
| --- | --- | --- | --- |
| Peacock | `#00677A` (`#00687B` in UI tokens) | primary, tile, mark | 6.4:1 on white; white on Peacock 6.4:1 |
| Peacock light | `#56D6F5` | primary on dark surfaces | 10.9:1 on `#101415` |
| Marigold | `#E8A317` | fold accent only | 2.2:1 on white, so decorative, never text |
| Marigold text | `#7F5700` | accent text | 5.9:1 on white |
| Ink | `#181C1E` | wordmark "Bill", body | 16:1 on white |
| Paper | `#F6FAFC` | light surface | |

Full scheme tables are in [12-design-system.md](12-design-system.md). Marigold never signals
status (green/red are reserved for that).

## 6. Typography

Wordmark: bold geometric sans, tight tracking (-0.015 em); SVGs use the stack
Inter, Roboto, Segoe UI, Helvetica Neue, Arial. App UI uses the Material 3 type scale from the
design system, with tabular figures for amounts.

## 7. Icon specs

| Platform | Spec | Files |
| --- | --- | --- |
| Android | adaptive, 108 dp canvas, mark inside 66 dp safe zone; Peacock background, white receipt; `<monochrome>` layer for Android 13 themed icons; round uses the same layers | `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `values/colors.xml`, `mipmap-anydpi-v26/ic_launcher{,_round}.xml` |
| Windows | multi-size `.ico` 16-256 | `branding/icons/openbill.ico` |
| Linux | 512 px PNG (plus 16-256) | `branding/icons/openbill.png`, `openbill-<size>.png` |
| In-app / window | Compose `ImageVector` | `core-ui/.../theme/BrandLogo.kt` |

## 8. Do and don't

Do: use the supplied files; keep clear space; use the mono mark on one-colour backgrounds;
place the tile on any background.
Don't: recolour the mark, rotate or skew it, add shadows or gradients, change the ring gap,
put Marigold on text, redraw the receipt with a different silhouette, or combine it with
other logos in one lockup.

## 9. Trademark and clean-room note

The mark, palette and wordmark were drawn from scratch for OpenBill from the Peacock seed in
the design system, without reference to any other billing product's logo, colours or
illustrations ([06-clean-room-policy.md](06-clean-room-policy.md)). The code is Apache-2.0; the
OpenBill name and logo identify the official project, so forks that change the product should
use a different name and mark.
