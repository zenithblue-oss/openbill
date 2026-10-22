# OpenBill branding

Original assets (Apache-2.0). See [docs/13-brand.md](../docs/13-brand.md).

- `logo-mark.svg` - receipt mark on transparent (Peacock, Marigold fold, white open "O")
- `logo-full.svg` - mark plus "OpenBill" wordmark (system font stack, not outlined)
- `logo-mono.svg` - single-colour mark with the ring punched out (themed icons, stamps)
- `logo-app-icon.svg` - mark on a rounded Peacock tile (source of the PNG/ICO set)
- `icons/openbill-{16,24,32,48,64,128,256,512}.png`, `icons/openbill.png` (512, Linux), `icons/openbill.ico` (Windows, 16-256)
- `tools/generate_brand.py` - regenerates all of the above plus the Android vector drawables
  and `core-ui/.../theme/BrandLogo.kt` from one geometry definition:
  `python3 -I branding/tools/generate_brand.py` (PNG/ICO need Pillow).
