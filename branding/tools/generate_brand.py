"""Generates every OpenBill brand asset from one geometry definition.
Run: python3 -I branding/tools/generate_brand.py   (needs Pillow for PNG/ICO)"""
import math, os
R = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PEACOCK, MARIGOLD, WHITE, INK = "#00677A", "#E8A317", "#FFFFFF", "#181C1E"
CX, CY, RO, RI = 32, 27, 12, 7
GAP_A, GAP_B = 295, 335  # open gap (degrees, clockwise from +x), top right

def pt(r, a): return (CX + r*math.cos(math.radians(a)), CY + r*math.sin(math.radians(a)))
# Geometry as absolute commands: ("M",x,y) ("L",x,y) ("A",r,x,y,large,sweep) ("Z",)
RECEIPT = [("M",12,4),("L",40,4),("L",52,16),("L",52,54),("L",42,60),("L",32,54),("L",22,60),("L",12,54),("Z",)]
FOLD = [("M",40,4),("L",40,16),("L",52,16),("Z",)]
o1, o2, i2, i1 = pt(RO,GAP_B), pt(RO,GAP_A), pt(RI,GAP_A), pt(RI,GAP_B)
RING = [("M",*o1),("A",RO,*o2,1,1),("L",*i2),("A",RI,*i1,1,0),("Z",)]
BAR = [("M",20,45),("L",44,45),("L",44,49),("L",20,49),("Z",)]

def xf(cmds, s, tx, ty):
    out = []
    for c in cmds:
        if c[0] in "ML": out.append((c[0], c[1]*s+tx, c[2]*s+ty))
        elif c[0] == "A": out.append(("A", c[1]*s, c[2]*s+tx, c[3]*s+ty, c[4], c[5]))
        else: out.append(c)
    return out
def d(cmds):
    f = lambda v: ("%.2f" % v).rstrip("0").rstrip(".")
    s = []
    for c in cmds:
        if c[0] in "ML": s.append(f"{c[0]}{f(c[1])} {f(c[2])}")
        elif c[0] == "A": s.append(f"A{f(c[1])} {f(c[1])} 0 {c[4]} {c[5]} {f(c[2])} {f(c[3])}")
        else: s.append("Z")
    return "".join(s)
def centered(s, size):  # mark content centre (32,33) -> canvas centre
    return s, size/2 - s*32, size/2 - s*33
def write(rel, text):
    p = os.path.join(R, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)

# ---- SVG
def mark_group(s, tx, ty, body, ring, fold):
    T = lambda c: d(xf(c, s, tx, ty))
    return (f'<path d="{T(RECEIPT)}" fill="{body}"/><path d="{T(FOLD)}" fill="{fold}"/>'
            f'<path d="{T(RING)}" fill="{ring}"/><path d="{T(BAR)}" fill="{ring}"/>')
s0 = (1, 0, 0)
write("branding/logo-mark.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="8 0 48 64" width="192" height="256">'
      f'<title>OpenBill mark</title>{mark_group(*s0, PEACOCK, WHITE, MARIGOLD)}</svg>\n')
t = centered(0.8, 64)
tile = f'<rect width="64" height="64" rx="14" fill="{PEACOCK}"/>' + mark_group(*t, WHITE, PEACOCK, MARIGOLD)
write("branding/logo-app-icon.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="512" height="512"><title>OpenBill app icon</title>{tile}</svg>\n')
mg = lambda body: mark_group(0.9, 0, 0, body, WHITE, MARIGOLD)
write("branding/logo-full.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 280 64" width="560" height="128">'
      f'<title>OpenBill</title><g transform="translate(-4 2)">{mark_group(0.9,0,0,PEACOCK,WHITE,MARIGOLD)}</g>'
      f'<text x="62" y="43" font-family="Inter, Roboto, \'Segoe UI\', \'Helvetica Neue\', Arial, sans-serif" font-size="34" font-weight="700" letter-spacing="-0.5" fill="{PEACOCK}">Open<tspan fill="{INK}">Bill</tspan></text></svg>\n')
# monochrome: receipt with ring/bar punched out (evenodd)
mono = d(RECEIPT) + d(RING) + d(BAR)
write("branding/logo-mono.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="8 0 48 64" width="192" height="256"><title>OpenBill mark, monochrome</title>'
      f'<path fill-rule="evenodd" d="{mono}" fill="#000"/></svg>\n')

# ---- Android vector drawables (108dp canvas, mark 1.1x centred => 70x64 content, safe zone 66)
a = centered(1.1, 108)
T = lambda c: d(xf(c, *a))
def vd(paths): return ('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + paths + '</vector>\n')
def p(dd, color, extra=""): return f'    <path\n        android:fillColor="{color}"{extra}\n        android:pathData="{dd}" />\n'
D = "res/drawable/"
base = "androidApp/src/main/res/"
write(base+"drawable/ic_launcher_foreground.xml", vd(p(T(RECEIPT),"#FFFFFF")+p(T(FOLD),MARIGOLD)+p(T(RING),PEACOCK)+p(T(BAR),PEACOCK)))
write(base+"drawable/ic_launcher_monochrome.xml", vd(p(T(RECEIPT)+T(RING)+T(BAR),"#000000",'\n        android:fillType="evenOdd"')))
write(base+"values/colors.xml", f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{PEACOCK}</color>\n</resources>\n')
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
write(base+"mipmap-anydpi-v26/ic_launcher.xml", adaptive)
write(base+"mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)

# ---- Compose ImageVector (tile, 64 viewport)
kt = f'''package app.openbill.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * OpenBill logo (receipt with folded corner and an open "O"), on a Peacock tile. Generated from
 * branding/tools/generate_brand.py; see docs/13-brand.md. Brand colours are fixed, not themed.
 */
object BrandLogo {{
    private val Peacock = Color(0xFF00677A)
    private val Marigold = Color(0xFFE8A317)

    val Mark: ImageVector by lazy {{
        ImageVector.Builder(
            name = "OpenBillLogo",
            defaultWidth = 64.dp,
            defaultHeight = 64.dp,
            viewportWidth = 64f,
            viewportHeight = 64f,
        ).apply {{
            addPath(addPathNodes("M14 0H50A14 14 0 0 1 64 14V50A14 14 0 0 1 50 64H14A14 14 0 0 1 0 50V14A14 14 0 0 1 14 0Z"), fill = SolidColor(Peacock))
            addPath(addPathNodes("{T0 if False else ''}"), fill = SolidColor(Color.White))
        }}.build()
    }}
}}
'''
tt = lambda c: d(xf(c, *t))
kt = kt.replace('addPath(addPathNodes("'+''+'"), fill = SolidColor(Color.White))',
  f'addPath(addPathNodes("{tt(RECEIPT)}"), fill = SolidColor(Color.White))\n'
  f'            addPath(addPathNodes("{tt(FOLD)}"), fill = SolidColor(Marigold))\n'
  f'            addPath(addPathNodes("{tt(RING)}"), fill = SolidColor(Peacock))\n'
  f'            addPath(addPathNodes("{tt(BAR)}"), fill = SolidColor(Peacock))')
kt = kt.replace("import androidx.compose.ui.graphics.vector.PathParser\n", "")
write("core-ui/src/commonMain/kotlin/app/openbill/ui/theme/BrandLogo.kt", kt)

# ---- PNG / ICO
from PIL import Image, ImageDraw
def poly(cmds, S):
    pts = []; cur = None
    for c in cmds:
        if c[0] in "ML": pts.append((c[1]*S, c[2]*S)); cur = c
        elif c[0] == "A":
            # sample arc about (CX,CY) scaled by caller via global centre
            pass
    return pts
def ring_poly(s, tx, ty, S):
    def P(r, ang): x, y = pt(r, ang); return ((x*s+tx)*S, (y*s+ty)*S)
    n = 48; sweep = (GAP_A + 360) - GAP_B
    outer = [P(RO, GAP_B + sweep*i/n) for i in range(n+1)]
    inner = [P(RI, GAP_A + 360 - sweep*i/n) for i in range(n+1)]
    return outer + inner
def render(px):
    S = px/64*8; img = Image.new("RGBA", (int(64*S), int(64*S)), (0,0,0,0)); g = ImageDraw.Draw(img)
    g.rounded_rectangle([0,0,64*S-1,64*S-1], radius=14*S, fill=PEACOCK)
    s, tx, ty = t
    sc = lambda cm: [(x*s+tx, y*s+ty) for _, *rest in cm if _ in "ML" for x, y in [rest]]
    f = lambda pts: [(x*S, y*S) for x, y in pts]
    g.polygon(f(sc(RECEIPT)), fill=WHITE); g.polygon(f(sc(FOLD)), fill=MARIGOLD)
    g.polygon(ring_poly(s, tx, ty, S), fill=PEACOCK); g.polygon(f(sc(BAR)), fill=PEACOCK)
    return img.resize((px, px), Image.LANCZOS)
sizes = [16,24,32,48,64,128,256,512]
for sz in sizes: render(sz).save(os.path.join(R, f"branding/icons/openbill-{sz}.png"))
render(512).save(os.path.join(R, "branding/icons/openbill.png"))
render(256).save(os.path.join(R, "branding/icons/openbill.ico"), sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print("ok")
