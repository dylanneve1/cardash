#!/usr/bin/env python3
"""
Render CarDash screens at the head unit's real resolution.

This is a mockup renderer, not a device capture — but it is not
eyeballed either. Every dp/sp value below is copied from the layout
code, the density matches the unit (160dpi base with a 186 override),
text boxes use the bundled fonts' real vertical metrics the way
TextView does, and the colours come from running the app's own M3
class on the JVM. The backgrounds are the app's bundled wallpaper
designs, drawn with the same maths as Wallpapers.java. So layout,
proportion and palette are faithful; only the rasteriser and the app
icons are stand-ins.

    python3 tools/render.py            # -> screenshots/
"""
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

W, H = 1280, 720
DENSITY = 186 / 160.0          # matches `wm density` on the unit

# Same cuts the APK bundles, so the mockups render in the real type.
FONTS = {
    "body": os.path.join(ROOT, "assets/fonts/GoogleSansFlex-Regular.ttf"),
    "display": os.path.join(ROOT, "assets/fonts/GoogleSansFlex-Medium.ttf"),
    "hero": os.path.join(ROOT, "assets/fonts/GoogleSansFlex-Display.ttf"),
}

# Shapes.FAMILY, verbatim.
FAMILY = [
    (28, 28, 28, 28),
    (36, 12, 36, 12),
    (12, 36, 12, 36),
    (44, 44, 16, 16),
    (16, 16, 44, 44),
    (40, 20, 40, 20),
]

# MaterialShape.CYCLE, verbatim.
SHAPES = [("squircle", 0, 0.0), ("clover", 4, 0.14), ("flower", 6, 0.11),
          ("squircle", 0, 0.0), ("burst", 8, 0.09), ("scallop", 12, 0.055)]

# Wallpapers.ALL, verbatim.
DUSK, AURORA, ORBIT = 0, 1, 2
WALLPAPERS = [
    ("Harbour", "4F7BD5", DUSK), ("Lagoon", "3AA7A3", AURORA), ("Moss", "2E8B57", ORBIT),
    ("Ember", "C96A1E", DUSK), ("Plum", "8E4FB8", AURORA), ("Blush", "C4506A", ORBIT),
]
# Prefs.scrimAlpha, verbatim.
GLASS, BALANCED, SOLID = 0x8C, 0xD8, 0xFF
# Prefs.SEEDS, verbatim.
SEEDS = ["4F7BD5", "3AA7A3", "2E8B57", "C96A1E", "C4506A", "8E4FB8"]


def dp(v):
    return int(round(v * DENSITY))


def sp(v):
    return v * DENSITY


# ---- palette -----------------------------------------------------------------

_COMPILED = False
_PALETTES = {}


def _ensure_compiled():
    """build.sh wipes build/, so make sure DumpPalette is there."""
    global _COMPILED
    if _COMPILED:
        return
    cp = os.path.join(ROOT, "build/tools")
    os.makedirs(cp, exist_ok=True)
    subprocess.run(
        ["javac", "-d", cp,
         os.path.join(ROOT, "src/ie/claudius/cardash/M3.java"),
         os.path.join(HERE, "DumpPalette.java")], check=True)
    _COMPILED = True


def palette(seed):
    if seed in _PALETTES:
        return _PALETTES[seed]
    _ensure_compiled()
    out = subprocess.run(
        ["java", "-cp", os.path.join(ROOT, "build/tools"), "DumpPalette", seed],
        capture_output=True, text=True, check=True).stdout
    p = {}
    for line in out.strip().splitlines():
        k, v = line.split("=")
        p[k] = v
    _PALETTES[seed] = p
    return p


def rgb(hex_):
    h = hex_.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def blend(hex_a, hex_b, t):
    """hex_a over hex_b at opacity t."""
    a, b = rgb(hex_a), rgb(hex_b)
    return "#%02X%02X%02X" % tuple(int(a[i] * t + b[i] * (1 - t)) for i in range(3))


# ---- type ----------------------------------------------------------------------

class Face:
    """A font at a size, with TextView's notion of its line box.

    Android's TextView box is fm.top..fm.bottom (the font bbox, head
    yMax/yMin) with includeFontPadding, and fm.ascent..fm.descent (hhea)
    without. PIL only knows ascent/descent, so read the bbox ourselves.
    """
    _metrics = {}

    def __init__(self, role, size_sp, padding=True):
        path = FONTS[role]
        px = sp(size_sp)
        self.font = ImageFont.truetype(path, int(round(px)))
        if path not in Face._metrics:
            t = TTFont(path)
            Face._metrics[path] = (t["head"].unitsPerEm, t["head"].yMax, t["head"].yMin,
                                   t["hhea"].ascent, t["hhea"].descent)
        upem, ymax, ymin, asc, desc = Face._metrics[path]
        top, bottom = (ymax, ymin) if padding else (asc, desc)
        self.ascent = px * top / upem          # box top -> baseline
        self.height = px * (top - bottom) / upem

    def width(self, draw, s):
        return draw.textlength(s, font=self.font)

    def draw(self, draw, xy, s, fill, align="left"):
        """Draw with the box's top-left at xy, like a TextView."""
        x, y = xy
        if align == "center":
            x -= self.width(draw, s) / 2
        elif align == "right":
            x -= self.width(draw, s)
        draw.text((x, y + self.ascent), s, font=self.font, fill=fill, anchor="ls")
        return self.height


# ---- shapes --------------------------------------------------------------------

def rounded(draw, box, radii, fill):
    """Per-corner rounded rect: radii clockwise from top-left, in dp."""
    x0, y0, x1, y1 = [int(v) for v in box]
    tl, tr, br, bl = [dp(r) for r in radii]
    m = min(x1 - x0, y1 - y0) // 2
    tl, tr, br, bl = [max(0, min(r, m)) for r in (tl, tr, br, bl)]
    draw.polygon([
        (x0 + tl, y0), (x1 - tr, y0),
        (x1, y0 + tr), (x1, y1 - br),
        (x1 - br, y1), (x0 + bl, y1),
        (x0, y1 - bl), (x0, y0 + tl),
    ], fill=fill)
    if tl: draw.pieslice([x0, y0, x0 + 2 * tl, y0 + 2 * tl], 180, 270, fill=fill)
    if tr: draw.pieslice([x1 - 2 * tr, y0, x1, y0 + 2 * tr], 270, 360, fill=fill)
    if br: draw.pieslice([x1 - 2 * br, y1 - 2 * br, x1, y1], 0, 90, fill=fill)
    if bl: draw.pieslice([x0, y1 - 2 * bl, x0 + 2 * bl, y1], 90, 180, fill=fill)


def pill(draw, box, fill):
    x0, y0, x1, y1 = [int(v) for v in box]
    r = (y1 - y0) // 2
    draw.rectangle([x0 + r, y0, x1 - r, y1], fill=fill)
    draw.ellipse([x0, y0, x0 + 2 * r, y1], fill=fill)
    draw.ellipse([x1 - 2 * r, y0, x1, y1], fill=fill)


def shape_points(box, index, samples=240):
    """Mirrors MaterialShape: superellipse or lobed polar curve."""
    _, lobes, amp = SHAPES[index % len(SHAPES)]
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    rx, ry = (x1 - x0) / 2, (y1 - y0) / 2
    phase = math.radians(index * 11.0)
    pts = []
    if lobes == 0:
        n = 4.0
        for i in range(samples):
            t = 2 * math.pi * i / samples
            ct, st = math.cos(t), math.sin(t)
            pts.append((
                cx + rx * (1 if ct >= 0 else -1) * abs(ct) ** (2.0 / n),
                cy + ry * (1 if st >= 0 else -1) * abs(st) ** (2.0 / n)))
    else:
        sx, sy = rx / (1 + amp), ry / (1 + amp)
        for i in range(samples):
            t = 2 * math.pi * i / samples
            r = 1 + amp * math.cos(lobes * (t + phase))
            pts.append((cx + sx * r * math.cos(t), cy + sy * r * math.sin(t)))
    return pts


def app_icon(draw, box, fill, letter, on, index=0):
    """Icon holder in the M3 shape, with a letter standing in for the app icon."""
    size = box[3] - box[1]
    draw.polygon(shape_points(box, index), fill=fill)
    f = ImageFont.truetype(FONTS["display"], int(size * 0.42))
    draw.text(((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), letter,
              font=f, fill=on, anchor="mm")


def glyph(draw, kind, cx, cy, s, on, hub=None):
    """Glyph.java's paths: s is the half-size (0.46 * button/2)."""
    if kind == "play":
        draw.polygon([(cx - s * 0.42, cy - s), (cx + s * 0.78, cy),
                      (cx - s * 0.42, cy + s)], fill=on)
    elif kind == "pause":
        bw = s * 0.30
        draw.rectangle([cx - s * 0.62, cy - s, cx - s * 0.62 + bw, cy + s], fill=on)
        draw.rectangle([cx + s * 0.62 - bw, cy - s, cx + s * 0.62, cy + s], fill=on)
    elif kind == "prev":
        draw.polygon([(cx + s * 0.9, cy - s), (cx - s * 0.1, cy), (cx + s * 0.9, cy + s)], fill=on)
        draw.polygon([(cx + s * 0.05, cy - s), (cx - s * 0.95, cy), (cx + s * 0.05, cy + s)], fill=on)
        draw.rectangle([cx - s * 0.95, cy - s, cx - s * 0.72, cy + s], fill=on)
    elif kind == "next":
        draw.polygon([(cx - s * 0.9, cy - s), (cx + s * 0.1, cy), (cx - s * 0.9, cy + s)], fill=on)
        draw.polygon([(cx - s * 0.05, cy - s), (cx + s * 0.95, cy), (cx - s * 0.05, cy + s)], fill=on)
        draw.rectangle([cx + s * 0.72, cy - s, cx + s * 0.95, cy + s], fill=on)
    elif kind == "gear":
        # Disc + 8 teeth; the hub is "punched out" by painting the
        # button's own fill back over it, which is what it looks like.
        draw.ellipse([cx - s * 0.68, cy - s * 0.68, cx + s * 0.68, cy + s * 0.68], fill=on)
        for i in range(8):
            a = math.radians(i * 45)
            pts = []
            for (px, py) in [(-0.18, -1), (0.18, -1), (0.18, -0.5), (-0.18, -0.5)]:
                x, y = px * s, py * s
                pts.append((cx + x * math.cos(a) - y * math.sin(a),
                            cy + x * math.sin(a) + y * math.cos(a)))
            draw.polygon(pts, fill=on)
        draw.ellipse([cx - s * 0.28, cy - s * 0.28, cx + s * 0.28, cy + s * 0.28], fill=hub)


def button(draw, box, fill, on, kind):
    x0, y0, x1, y1 = box
    pill(draw, box, fill)
    s = min(x1 - x0, y1 - y0) * 0.46 * 0.5
    glyph(draw, kind, (x0 + x1) / 2, (y0 + y1) / 2, s, on, hub=fill)


# ---- wallpapers ---------------------------------------------------------------

def _stops(t, stops):
    """Piecewise-linear RGBA interpolation over (pos, (r,g,b,a)) stops."""
    out = np.zeros(t.shape + (4,), dtype=np.float32)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (t >= p0) & (t <= p1)
        f = np.where(p1 > p0, (t - p0) / max(p1 - p0, 1e-6), 0.0)
        for i in range(4):
            out[..., i] = np.where(m, c0[i] + (c1[i] - c0[i]) * f, out[..., i])
    return out


def _over(base, layer):
    """Source-over composite of float RGBA arrays (0..255, straight alpha)."""
    a = layer[..., 3:4] / 255.0
    base[..., :3] = layer[..., :3] * a + base[..., :3] * (1 - a)
    return base


def _radial(w, h, fx, fy, fr, stops):
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy, r = w * fx, h * fy, h * fr
    t = np.clip(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2) / r, 0, 1)
    return _stops(t, stops)


def wallpaper(design):
    """Wallpapers.render(), in numpy."""
    name, seed, style = design
    p = palette(seed)
    c = lambda key, a=255: rgb(p[key]) + (a,)

    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    # LinearGradient (0,0)->(0.35w, h): project onto that vector.
    vx, vy = W * 0.35, H
    t = np.clip((xs * vx + ys * vy) / (vx * vx + vy * vy), 0, 1)
    img = _stops(t, [(0.0, c("p24")), (0.55, c("n10")), (1.0, c("n5"))])

    def bloom(fx, fy, fr, key, alpha):
        col = rgb(p[key])
        _over(img, _radial(W, H, fx, fy, fr, [
            (0.0, col + (alpha * 255,)), (0.45, col + (alpha * 90,)), (1.0, col + (0,))]))

    def ring(fx, fy, fr, key, alpha):
        col = rgb(p[key])
        _over(img, _radial(W, H, fx, fy, fr, [
            (0.0, col + (0,)), (0.30, col + (0,)), (0.48, col + (alpha * 40,)),
            (0.64, col + (alpha * 255,)), (0.82, col + (alpha * 70,)), (1.0, col + (0,))]))

    if style == AURORA:
        bloom(0.80, 0.15, 0.70, "p50", 0.75)
        bloom(0.30, 0.05, 0.55, "t45", 0.55)
        bloom(0.55, 0.95, 0.65, "p40", 0.55)
    elif style == ORBIT:
        ring(0.72, 0.55, 0.70, "p50", 0.42)
        bloom(0.72, 0.55, 0.28, "p35", 0.35)
        bloom(0.10, 0.10, 0.45, "t40", 0.45)
    else:
        bloom(0.85, 0.85, 0.85, "p45", 0.80)
        bloom(0.15, 0.20, 0.50, "t45", 0.50)
        bloom(0.60, 0.40, 0.35, "p60", 0.30)

    img[..., 3] = 255
    return Image.fromarray(img.astype(np.uint8))


def backdrop(design, scrim_alpha):
    """Wallpaper under the tonal scrim — HomeActivity's page background."""
    seed = design[1]
    p = palette(seed)
    img = wallpaper(design)
    layer = Image.new("RGBA", (W, H), rgb(p["surface"]) + (scrim_alpha,))
    return Image.alpha_composite(img, layer), p


# ---- home ---------------------------------------------------------------------

def speedo(draw, box, p, kph, has_fix, mph=False, scale=None, digits=False):
    """Speedo.onDraw, in PIL."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    size = min(w, h)
    stroke = size * 0.075
    inset = stroke * 0.7 + size * 0.06
    cx, cy = x0 + w / 2, y0 + h / 2
    r = size / 2 - inset
    arc = [cx - r, cy - r, cx + r, cy + r]
    start, sweep = 140, 260
    display = kph / 1.609344 if mph else kph
    mx = scale or (120 if mph else 180)
    unit_s = ("mph" if mph else "km/h") if has_fix else "no fix"
    num_s = str(round(display)) if has_fix else "--"

    if digits:
        num = ImageFont.truetype(FONTS["hero"], int(size * 0.40))
        unit = ImageFont.truetype(FONTS["body"], int(size * 0.085))
        draw.text((cx, cy + size * 0.13), num_s, font=num, fill=p["onSurface"], anchor="ms")
        draw.text((cx, cy + size * 0.27), unit_s, font=unit, fill=p["onSurfaceVariant"], anchor="ms")
        return

    def capped_arc(a0, a1, color):
        draw.arc(arc, a0, a1, fill=color, width=int(stroke))
        for a in (a0, a1):  # round caps
            ex, ey = cx + (r - stroke / 2) * math.cos(math.radians(a)), \
                     cy + (r - stroke / 2) * math.sin(math.radians(a))
            draw.ellipse([ex - stroke / 2, ey - stroke / 2, ex + stroke / 2, ey + stroke / 2],
                         fill=color)

    track = blend(p["onSurface"], p["surfaceContainer"], 0x1F / 255)
    capped_arc(start, start + sweep, track)

    frac = min(1.0, display / mx)
    if has_fix and frac > 0.001:
        capped_arc(start, start + sweep * frac, p["primary"])

    tick_w = max(2, int(size * 0.008))
    for k in range(0, mx + 1, 20):
        a = math.radians(start + sweep * k / mx)
        inner, outer = r - stroke * 0.85, r - stroke * 1.35
        draw.line([(cx + math.cos(a) * outer, cy + math.sin(a) * outer),
                   (cx + math.cos(a) * inner, cy + math.sin(a) * inner)],
                  fill=p["onSurfaceVariant"], width=tick_w)

    num = ImageFont.truetype(FONTS["hero"], int(size * 0.28))
    unit = ImageFont.truetype(FONTS["body"], int(size * 0.072))
    draw.text((cx, cy + size * 0.07), num_s, font=num, fill=p["onSurface"], anchor="ms")
    draw.text((cx, cy + size * 0.20), unit_s, font=unit, fill=p["onSurfaceVariant"], anchor="ms")
    headroom = (cy - y0) - r - stroke
    if headroom > size * 0.12:
        cap = ImageFont.truetype(FONTS["body"], int(size * 0.06))
        draw.text((cx, y0 + headroom / 2), "GPS", font=cap, fill=p["onSurfaceVariant"], anchor="mm")


def ellipsize(draw, face, s, max_w):
    if face.width(draw, s) <= max_w:
        return s
    while s and face.width(draw, s + "…") > max_w:
        s = s[:-1]
    return s.rstrip() + "…"


def trip_text(metres, moving_ms, miles):
    """Trip.distance / duration / average, verbatim."""
    v = metres / (1609.344 if miles else 1000.0)
    dist = ("%.1f" % v) if v < 100 else str(round(v))
    mins = moving_ms // 60000
    dur = f"{mins} min" if mins < 60 else "%d h %02d" % (mins // 60, mins % 60)
    detail = dur
    if moving_ms >= 30000:
        avg = round(metres / (moving_ms / 3600000.0) / (1609.344 if miles else 1000.0))
        detail += f" · {avg} {'mph' if miles else 'km/h'} avg"
    return dist + (" mi" if miles else " km"), detail


DEFAULTS = dict(
    hero=True, scrim=BALANCED, hint=True, weather=(14, "Partly cloudy"),
    track=("Everything In Its Right Place", "Radiohead"), playing=True,
    kph=52, has_fix=True, mph=False, clock="18:42", fahrenheit=False,
    dash=("speedo", "weather"), media="full", date="full", digits=False, scale=None,
    vehicle=True, trip=(12_400, 38 * 60_000),
)


def home(design, apps, chips, path, **over):
    c = dict(DEFAULTS, **over)
    img, p = backdrop(design, c["scrim"])
    d = ImageDraw.Draw(img)

    pad = dp(20)
    inner_w = W - 2 * pad
    has_dash = bool(c["dash"])
    # LinearLayout weights: .30/.30/.40 with the dash, .32/.68 without.
    left_w = int(inner_w * (0.30 if has_dash else 0.32))
    dash_w = int(inner_w * 0.30) if has_dash else 0

    # ---- left: clock panel -------------------------------------------
    lx0, ly0 = pad, pad
    lx1, ly1 = pad + left_w - dp(16), H - pad
    rounded(d, (lx0, ly0, lx1, ly1), (36, 36, 36, 36), p["surfaceContainer"])
    cx = lx0 + dp(24)
    right = lx1 - dp(24)

    f_clock = Face("hero", 72, padding=False)
    f_date = Face("body", 18, padding=False)
    f_btn = Face("display", 18)
    f_chip = Face("body", 15)
    f_hint = Face("body", 12)
    compact = c["media"] == "compact"
    f_title = Face("display", 15 if compact else 17)
    f_artist = Face("body", 13 if compact else 14)

    btn_h = f_btn.height + 2 * dp(14)
    actions_h = max(btn_h, dp(50))
    show_date = c["date"] != "hide"
    if c["media"] == "hide":
        np_h = 0
    elif compact:
        np_top_h = max(dp(48), dp(44), f_title.height + f_artist.height)
        np_h = dp(10) + np_top_h + dp(10)
    else:
        np_top_h = max(dp(64), f_title.height + f_artist.height)
        np_h = dp(14) + np_top_h + dp(12) + dp(60) + dp(14)
    chip_h = f_chip.height + 2 * dp(8)
    show_chips = c["vehicle"] and chips
    block = (f_clock.height
             + (dp(10) + f_date.height + dp(20) if show_date else dp(20))
             + actions_h
             + (dp(18) + np_h if np_h else 0)
             + (dp(16) + chip_h if show_chips else 0)
             + (dp(14) + f_hint.height if c["hint"] else 0))
    y = ly0 + dp(24) + ((ly1 - ly0 - 2 * dp(24)) - block) // 2

    # setLetterSpacing(-0.03f) on the clock: nudge glyphs together.
    x = cx
    for ch in c["clock"]:
        f_clock.draw(d, (x, y), ch, p["primary"])
        x += f_clock.width(d, ch) - 0.03 * sp(72)
    y += f_clock.height
    if show_date:
        y += dp(10)
        f_date.draw(d, (cx, y), "Tuesday 15 September" if c["date"] == "full" else "Tue 15 Sep",
                    p["onSurfaceVariant"])
        y += f_date.height
    y += dp(20)

    # All apps + gear
    label = "All apps"
    tw = f_btn.width(d, label)
    by = y + (actions_h - btn_h) // 2
    pill(d, (cx, by, cx + tw + 2 * dp(28), by + btn_h), p["primaryContainer"])
    f_btn.draw(d, (cx + dp(28), by + dp(14)), label, p["onPrimaryContainer"])
    gx = cx + tw + 2 * dp(28) + dp(10)
    gy = y + (actions_h - dp(50)) // 2
    button(d, (gx, gy, gx + dp(50), gy + dp(50)), p["surfaceContainerHigh"],
           p["onSurfaceVariant"], "gear")
    y += actions_h

    # Now playing card
    if np_h:
        y += dp(18)
        rounded(d, (cx, y, right, y + np_h), (28, 28, 28, 28), p["surfaceContainerHigh"])
        cpad = dp(10) if compact else dp(14)
        art_sz = dp(48) if compact else dp(64)
        ay = y + cpad + (np_top_h - art_sz) // 2
        art = (cx + cpad, ay, cx + cpad + art_sz, ay + art_sz)
        d.polygon(shape_points(art, 0),
                  fill=blend(p["onSurface"], p["surfaceContainerHigh"], 0x26 / 255))
        track = c["track"]
        if track:
            d.polygon(shape_points(art, 0), fill=p["primaryContainer"])
            f_note = ImageFont.truetype(FONTS["display"], int(art_sz * 0.4))
            d.text(((art[0] + art[2]) / 2, (art[1] + art[3]) / 2), "♫",
                   font=f_note, fill=p["onPrimaryContainer"], anchor="mm")
        tx = art[2] + dp(12)
        play_kind = "pause" if c["playing"] else "play"
        if compact:
            bsz = dp(44)
            bx1 = right - cpad - dp(6)
            btop = y + cpad + (np_top_h - bsz) // 2
            button(d, (bx1 - bsz, btop, bx1, btop + bsz), p["primary"], p["onPrimary"], play_kind)
            tmax = (bx1 - bsz - dp(6)) - tx
        else:
            tmax = (right - cpad) - tx
        title, artist = track if track else ("Nothing playing", "")
        text_h = f_title.height + (f_artist.height if artist else 0)
        ty = y + cpad + (np_top_h - text_h) // 2
        f_title.draw(d, (tx, ty), ellipsize(d, f_title, title, tmax), p["onSurface"])
        if artist:
            f_artist.draw(d, (tx, ty + f_title.height), ellipsize(d, f_artist, artist, tmax),
                          p["onSurfaceVariant"])
        if not compact:
            ry = y + cpad + np_top_h + dp(12)
            total = dp(48) + dp(60) + dp(48) + 4 * dp(6)
            bx = cx + (right - cx - total) // 2 + dp(6)
            for kind, big in (("prev", False), (play_kind, True), ("next", False)):
                sz = dp(60) if big else dp(48)
                top = ry + (dp(60) - sz) // 2
                button(d, (bx, top, bx + sz, top + sz),
                       p["primary"] if big else p["surfaceContainer"],
                       p["onPrimary"] if big else p["onSurface"], kind)
                bx += sz + 2 * dp(6)
        y += np_h

    # Vehicle chips (a HorizontalScrollView: no wrapping, overflow scrolls)
    if show_chips:
        y += dp(16)
        x = cx
        for text, kind in chips:
            fill, on = p["surfaceContainerHigh"], p["onSurface"]
            if kind == "warn":
                fill, on = p["tertiaryContainer"], p["onTertiaryContainer"]
            elif kind == "alert":
                fill, on = p["primaryContainer"], p["onPrimaryContainer"]
            w_ = f_chip.width(d, text) + 2 * dp(14)
            if x + w_ > right:
                break  # off the end of the scroller
            pill(d, (x, y, x + w_, y + chip_h), fill)
            f_chip.draw(d, (x + dp(14), y + dp(8)), text, on)
            x += w_ + dp(8)
        y += chip_h

    if c["hint"]:
        y += dp(14)
        f_hint.draw(d, (cx, y), "Long-press a tile to change it",
                    blend(p["onSurfaceVariant"], p["surfaceContainer"], 0x99 / 255))

    # ---- middle: dash widgets ----------------------------------------
    if has_dash:
        mx0 = pad + left_w + dp(8)
        mx1 = pad + left_w + dash_w - dp(8)
        mmid = (mx0 + mx1) / 2
        widgets = [w for w in ("speedo", "trip", "weather") if w in c["dash"]]
        miles = c["mph"]

        # Small-card heights (WRAP_CONTENT); the first widget is tall.
        f_temp, f_desc = Face("display", 30, padding=False), Face("body", 14)
        f_ttitle, f_tdist, f_tdet = Face("display", 13), Face("display", 30, padding=False), Face("body", 14)
        small_h = {
            "weather": dp(14) + f_temp.height + dp(4) + f_desc.height + dp(14),
            "trip": dp(14) + f_ttitle.height + dp(2) + f_tdist.height + dp(4) + f_tdet.height + dp(14),
        }
        small_total = sum(small_h[w] + dp(12) for w in widgets[1:])
        tall_box = (mx0, pad, mx1, H - pad - small_total)

        def weather_card(box, tall):
            rounded(d, box, (36,) * 4 if tall else (28,) * 4, p["secondaryContainer"])
            ft = Face("hero" if tall else "display", 64 if tall else 30, padding=False)
            fd = Face("body", 18 if tall else 14)
            gap = dp(8 if tall else 4)
            on = p["onSecondaryContainer"]
            dim = blend(on, p["secondaryContainer"], 0xCC / 255)
            if c["weather"]:
                t, desc = c["weather"]
                if c["fahrenheit"]:
                    t = round(t * 9 / 5 + 32)
                temp, desc = f"{t}°", desc
            else:
                temp, desc = "--", "Weather unavailable"
            bh = ft.height + gap + fd.height
            ty = box[1] + dp(14) if not tall else box[1] + ((box[3] - box[1]) - bh) / 2
            ft.draw(d, (mmid, ty), temp, on, "center")
            fd.draw(d, (mmid, ty + ft.height + gap), desc, dim, "center")

        def trip_card(box, tall):
            rounded(d, box, FAMILY[0], p["tertiaryContainer"])
            on = p["onTertiaryContainer"]
            f1 = Face("display", 16 if tall else 13)
            f2 = Face("hero" if tall else "display", 64 if tall else 30, padding=False)
            f3 = Face("body", 18 if tall else 14)
            f4 = Face("body", 12)
            g1, g2 = dp(8 if tall else 2), dp(8 if tall else 4)
            dist, detail = trip_text(c["trip"][0], c["trip"][1], miles)
            bh = f1.height + g1 + f2.height + g2 + f3.height + (dp(18) + f4.height if tall else 0)
            ty = box[1] + dp(14) if not tall else box[1] + ((box[3] - box[1]) - bh) / 2
            f1.draw(d, (mmid, ty), "Trip", blend(on, p["tertiaryContainer"], 0xB3 / 255), "center")
            ty += f1.height + g1
            f2.draw(d, (mmid, ty), dist, on, "center")
            ty += f2.height + g2
            f3.draw(d, (mmid, ty), detail, blend(on, p["tertiaryContainer"], 0xCC / 255), "center")
            if tall:
                ty += f3.height + dp(18)
                f4.draw(d, (mmid, ty), "Hold to reset", blend(on, p["tertiaryContainer"], 0x80 / 255),
                        "center")

        for i, w in enumerate(widgets):
            tall = i == 0
            if tall:
                box = tall_box
                y_next = tall_box[3] + dp(12)
            else:
                box = (mx0, y_next, mx1, y_next + small_h[w])
                y_next = box[3] + dp(12)
            if w == "speedo":
                rounded(d, box, (36,) * 4, p["surfaceContainer"])
                speedo(d, box, p, c["kph"], c["has_fix"], miles, c["scale"], c["digits"])
            elif w == "trip":
                trip_card(box, tall)
            else:
                weather_card(box, tall)

    # ---- right: tiles ---------------------------------------------------
    gx0 = pad + left_w + dash_w
    gw = W - pad - gx0
    gh = H - 2 * pad
    cols, rows = (2 if has_dash else 3), 4
    cw_, chh = gw / cols, gh / rows

    roles = [("primaryContainer", "onPrimaryContainer"),
             ("secondaryContainer", "onSecondaryContainer"),
             ("tertiaryContainer", "onTertiaryContainer"),
             ("surfaceContainerHigh", "onSurface")]

    # HomeActivity.spans(), verbatim.
    if c["hero"]:
        layout = [(0, 0, cols, 1)] + [(cc, r, 1, 1) for r in range(1, rows) for cc in range(cols)]
    else:
        layout = [(cc, r, 1, 1) for r in range(rows) for cc in range(cols)]

    for i, sp_ in enumerate(layout):
        col, row, cspan, rspan = sp_
        is_hero = cspan > 1
        g = dp(7)
        x0 = int(gx0 + col * cw_) + g
        y0 = int(pad + row * chh) + g
        x1 = int(gx0 + (col + cspan) * cw_) - g
        y1 = int(pad + (row + rspan) * chh) - g

        fillk, onk = roles[i % 4]
        rounded(d, (x0, y0, x1, y1), FAMILY[i % len(FAMILY)], p[fillk])

        name = apps[i] if i < len(apps) else None
        isz = dp(54) if is_hero else dp(64)
        f_lab = Face("display", 18 if is_hero else 15)
        block = isz + dp(8) + f_lab.height
        iy = y0 + ((y1 - y0) - block) // 2
        ix = x0 + ((x1 - x0) - isz) // 2
        if name:
            app_icon(d, (ix, iy, ix + isz, iy + isz),
                     blend(p[onk], p[fillk], 0x30 / 255), name[0].upper(), p[onk], i)
        else:
            d.polygon(shape_points((ix, iy, ix + isz, iy + isz), i),
                      fill=blend(p[onk], p[fillk], 0x30 / 255))
            f_plus = ImageFont.truetype(FONTS["body"], int(isz * 0.5))
            d.text((ix + isz / 2, iy + isz / 2), "+", font=f_plus, fill=p[onk], anchor="mm")
        lab = ellipsize(d, f_lab, name or "Add", x1 - x0 - 2 * dp(8))
        f_lab.draw(d, ((x0 + x1) / 2, iy + isz + dp(8)), lab, p[onk], "center")

    img.convert("RGB").save(path)
    print("wrote", path)


# ---- settings -----------------------------------------------------------------

def settings(design, path, scrim=BALANCED, chosen=None, seed_choice="wallpaper",
             values=None, dash=("Speed", "Weather"), preset="Classic", scroll=0):
    """SettingsActivity. `scroll` is how far down the page the mockup is."""
    values = values or {}
    img, p = backdrop(design, scrim)
    # Draw the page on a tall layer, then show the window at `scroll`.
    page = Image.new("RGBA", (W, H * 3), (0, 0, 0, 0))
    d = ImageDraw.Draw(page)
    pad = dp(20)

    f_title = Face("display", 26)
    f_pill = Face("display", 15)
    f_row = Face("display", 18)
    f_cap = Face("body", 13)
    pill_h = f_pill.height + 2 * dp(10)

    def seg(x_right, y_mid, options, selected):
        """Right-aligned pills; `selected` is a value or a set of values."""
        sel = selected if isinstance(selected, (set, tuple, list)) else {selected}
        widths = [f_pill.width(d, o) + 2 * dp(18) for o in options]
        total = sum(widths) + 2 * dp(3) * len(options)
        x = x_right - total
        for o, w_ in zip(options, widths):
            x += dp(3)
            on = o in sel
            fill = p["primaryContainer"] if on else p["surfaceContainerHigh"]
            col = p["onPrimaryContainer"] if on else p["onSurfaceVariant"]
            pill(d, (x, y_mid - pill_h / 2, x + w_, y_mid + pill_h / 2), fill)
            f_pill.draw(d, (x + dp(18), y_mid - pill_h / 2 + dp(10)), o, col)
            x += w_ + dp(3)
        return x_right - total

    # header
    y = pad
    hx0, hx1 = pad + dp(8), W - pad - dp(8)
    head_h = max(f_title.height, pill_h)
    f_title.draw(d, (hx0, y + (head_h - f_title.height) / 2), "Settings", p["onSurface"])
    seg(hx1, y + head_h / 2, ["Done"], "Done")
    y += head_h + dp(16)

    def card(rows):
        nonlocal y
        cx0, cx1 = pad, W - pad
        ix0, ix1 = cx0 + dp(24), cx1 - dp(24)
        heights = []
        for r in rows:
            content_h = r.get("h", pill_h)
            text_h = f_row.height + dp(2) + f_cap.height
            heights.append(dp(12) + max(text_h, content_h) + dp(12))
        total = dp(8) * 2 + sum(heights) + max(1, dp(1)) * (len(rows) - 1)
        rounded(d, (cx0, y, cx1, y + total), (28, 28, 28, 28), p["surfaceContainer"])
        ry = y + dp(8)
        for i, (r, h) in enumerate(zip(rows, heights)):
            mid = ry + h / 2
            text_h = f_row.height + dp(2) + f_cap.height
            f_row.draw(d, (ix0, mid - text_h / 2), r["title"], p["onSurface"])
            f_cap.draw(d, (ix0, mid - text_h / 2 + f_row.height + dp(2)), r["caption"],
                       p["onSurfaceVariant"])
            r["draw"](ix1, mid)
            ry += h
            if i < len(rows) - 1:
                d.rectangle([ix0, ry, ix1, ry + max(1, dp(1)) - 1],
                            fill=blend(p["outline"], p["surfaceContainer"], 0x33 / 255))
                ry += max(1, dp(1))
        y += total + dp(16)

    def wallpapers(x_right, mid):
        tw, th, ring = dp(88), dp(50), dp(3)
        step = tw + 2 * ring + 2 * dp(4)
        x = x_right - step * len(WALLPAPERS)
        for name, seed, style in WALLPAPERS:
            x += dp(4)
            if name == chosen:
                rounded(d, (x, mid - th / 2 - ring, x + tw + 2 * ring, mid + th / 2 + ring),
                        (15, 15, 15, 15), p["onSurface"])
            thumb = wallpaper((name, seed, style)).resize((tw, th), Image.LANCZOS)
            mask = Image.new("L", (tw, th), 0)
            rounded(ImageDraw.Draw(mask), (0, 0, tw, th), (12, 12, 12, 12), 255)
            page.paste(thumb, (int(x + ring), int(mid - th / 2)), mask)
            x += tw + 2 * ring + dp(4)

    def swatches(x_right, mid):
        sz = dp(40)
        n = len(SEEDS)
        wlab = f_pill.width(d, "Wallpaper") + 2 * dp(18)
        total = wlab + 2 * dp(3) + n * (sz + 2 * dp(6))
        x = x_right - total
        on = seed_choice == "wallpaper"
        pill(d, (x + dp(3), mid - pill_h / 2, x + dp(3) + wlab, mid + pill_h / 2),
             p["primaryContainer"] if on else p["surfaceContainerHigh"])
        f_pill.draw(d, (x + dp(3) + dp(18), mid - pill_h / 2 + dp(10)), "Wallpaper",
                    p["onPrimaryContainer"] if on else p["onSurfaceVariant"])
        x += wlab + 2 * dp(3)
        for s in SEEDS:
            x += dp(6)
            d.ellipse([x, mid - sz / 2, x + sz, mid + sz / 2], fill=palette(s)["primary"])
            if s == seed_choice:
                d.ellipse([x, mid - sz / 2, x + sz, mid + sz / 2],
                          outline=p["onSurface"], width=dp(3))
            x += sz + dp(6)

    v = lambda k, default: values.get(k, default)
    card([
        dict(title="Wallpaper", caption="Bundled designs, drawn to fit this screen",
             h=dp(50) + 2 * dp(3), draw=wallpapers),
        dict(title="Colour", caption="Theme from the wallpaper, or pick a seed",
             h=dp(40), draw=swatches),
        dict(title="Background", caption="How much wallpaper shows through",
             draw=lambda xr, m: seg(xr, m, ["Glass", "Balanced", "Solid"], v("scrim", "Balanced"))),
        dict(title="Tiles", caption="One wide tile on top, or eight equal",
             draw=lambda xr, m: seg(xr, m, ["Hero", "Grid"], v("layout", "Hero"))),
        dict(title="Motion", caption="Springs and staggers, or short fades",
             draw=lambda xr, m: seg(xr, m, ["Expressive", "Calm"], v("motion", "Expressive"))),
    ])
    card([
        dict(title="Layout", caption="Curated combinations of the widgets below",
             draw=lambda xr, m: seg(xr, m, ["Classic", "Driver", "Launcher", "Minimal"], preset)),
        dict(title="Dash widgets", caption="The middle column; turn them all off for wider tiles",
             draw=lambda xr, m: seg(xr, m, ["Speed", "Trip", "Weather"], set(dash))),
        dict(title="Speed gauge", caption="Arc with ticks, or just the number",
             draw=lambda xr, m: seg(xr, m, ["Arc", "Digits"], v("gauge", "Arc"))),
        dict(title="Gauge scale", caption="Where the arc tops out",
             draw=lambda xr, m: seg(xr, m, ["City", "Normal", "Fast"], v("scale", "Normal"))),
        dict(title="Date", caption="Under the clock",
             draw=lambda xr, m: seg(xr, m, ["Full", "Short", "Hidden"], v("date", "Full"))),
        dict(title="Now playing", caption="Track and transport controls under the clock",
             draw=lambda xr, m: seg(xr, m, ["Full", "Compact", "Hidden"], v("media", "Full"))),
        dict(title="Car data", caption="Fuel, coolant, doors — when a source reports",
             draw=lambda xr, m: seg(xr, m, ["Show", "Hide"], v("vehicle", "Show"))),
        dict(title="Trip meter", caption="Distance and moving time since the last reset",
             draw=lambda xr, m: seg(xr, m, ["Reset"], None)),
    ])
    card([
        dict(title="Speed", caption="Unit on the GPS gauge",
             draw=lambda xr, m: seg(xr, m, ["km/h", "mph"], v("speed", "km/h"))),
        dict(title="Temperature", caption="Unit on the weather card",
             draw=lambda xr, m: seg(xr, m, ["°C", "°F"], v("temp", "°C"))),
        dict(title="Clock", caption="Follow the system, or force a format",
             draw=lambda xr, m: seg(xr, m, ["System", "12h", "24h"], v("clock", "System"))),
    ])
    card([
        dict(title="Tips", caption="The long-press reminder under the clock",
             draw=lambda xr, m: seg(xr, m, ["Show", "Hide"], v("hint", "Show"))),
        dict(title="Home tiles", caption="Put the default apps back on every tile",
             draw=lambda xr, m: seg(xr, m, ["Reset"], None)),
    ])

    img.alpha_composite(page.crop((0, scroll, W, scroll + H)))
    img.convert("RGB").save(path)
    print("wrote", path)


# ---- all apps -----------------------------------------------------------------

def all_apps(design, names, path):
    _, p = backdrop(design, 0xF2)
    img = Image.new("RGB", (W, H), p["surface"])
    d = ImageDraw.Draw(img)
    pad = dp(20)

    f_title = Face("display", 26)
    f_title.draw(d, (pad + dp(8), pad), "All apps", p["onSurface"])

    top = pad + f_title.height + dp(16)
    cols = 5
    gw = W - 2 * pad
    cw = gw / cols
    f_lab = Face("body", 14)
    cell_h = dp(16) + dp(48) + dp(8) + f_lab.height + dp(16) + 2 * dp(6)

    for i, name in enumerate(names):
        c, r = i % cols, i // cols
        g = dp(6)
        x0 = int(pad + c * cw) + g
        y0 = int(top + r * cell_h) + g
        x1 = int(pad + (c + 1) * cw) - g
        y1 = int(top + (r + 1) * cell_h) - g
        if y0 > H:
            break
        rounded(d, (x0, y0, x1, y1), FAMILY[i % len(FAMILY)], p["surfaceContainer"])

        isz = dp(48)
        ix = x0 + ((x1 - x0) - isz) // 2
        iy = y0 + dp(16)
        app_icon(d, (ix, iy, ix + isz, iy + isz),
                 blend(p["onSurface"], p["surfaceContainer"], 0.19),
                 name[0].upper(), p["onSurface"], i)
        f_lab.draw(d, ((x0 + x1) / 2, iy + isz + dp(8)), name, p["onSurface"], "center")

    img.save(path)
    print("wrote", path)


def wallpaper_sheet(path):
    """Contact sheet of the six bundled designs."""
    tw, th, g = 400, 225, 20
    sheet = Image.new("RGB", (3 * tw + 4 * g, 2 * th + 3 * g), "#0B0E12")
    d = ImageDraw.Draw(sheet)
    f = Face("display", 12)
    for i, design in enumerate(WALLPAPERS):
        c, r = i % 3, i // 3
        x, y = g + c * (tw + g), g + r * (th + g)
        thumb = wallpaper(design).resize((tw, th), Image.LANCZOS)
        mask = Image.new("L", (tw, th), 0)
        rounded(ImageDraw.Draw(mask), (0, 0, tw, th), (16, 16, 16, 16), 255)
        sheet.paste(thumb, (x, y), mask)
        f.draw(d, (x + 14, y + th - 14 - f.height), design[0], "#FFFFFF")
    sheet.save(path)
    print("wrote", path)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "screenshots")
    os.makedirs(out, exist_ok=True)
    for old in os.listdir(out):
        if old.endswith(".png"):
            os.remove(os.path.join(out, old))

    tiles = ["Android Auto", "CarPlay", "Music", "Bluetooth", "Maps", "Gallery",
             "Equaliser", "FM Radio", "YouTube", "Files", "Video", "Browser"]
    obd = [("Fuel 34%", "ok"), ("89°C", "ok"), ("1726 rpm", "ok")]

    harbour, lagoon, moss, ember, plum, blush = WALLPAPERS

    # One screenshot per preset, then the corners of the option space.
    home(harbour, tiles, [], os.path.join(out, "01-home-classic.png"))
    home(ember, tiles, obd, os.path.join(out, "02-home-driver.png"),
         dash=("speedo", "trip"), media="compact", date="short", hint=False,
         kph=88, mph=True, trip=(37_200, 41 * 60_000), track=("Bad Guy", "Billie Eilish"))
    home(lagoon, tiles, [], os.path.join(out, "03-home-launcher.png"),
         dash=(), media="compact", hero=False, hint=False,
         track=("Motion Sickness", "Phoebe Bridgers"))
    home(plum, tiles, [], os.path.join(out, "04-home-minimal.png"),
         dash=("speedo",), digits=True, media="hide", date="short", hint=False,
         vehicle=False, scrim=GLASS, kph=112)
    home(moss, tiles, [("Fuel 11%", "warn"), ("Door open", "alert")],
         os.path.join(out, "05-home-warnings.png"), kph=0, playing=False,
         track=None, weather=(9, "Rain"))
    home(blush, tiles, [], os.path.join(out, "06-home-trip-weather.png"),
         dash=("trip", "weather"), hero=False, scrim=SOLID, fahrenheit=True,
         weather=(22, "Clear"), trip=(128_900, 2 * 3_600_000 + 5 * 60_000), clock="7:05")

    settings(harbour, os.path.join(out, "07-settings.png"), chosen="Harbour")
    settings(ember, os.path.join(out, "08-settings-widgets.png"), chosen="Ember",
             preset="Driver", dash=("Speed", "Trip"),
             values={"date": "Short", "media": "Compact", "speed": "mph", "hint": "Hide"},
             scroll=dp(20) + int(Face("display", 26).height) + dp(16) + dp(370))

    all_apps(harbour, [
        "Android Auto", "Bluetooth", "CarPlay", "Equaliser", "FM Radio",
        "Gallery", "Maps", "Music", "Settings", "USB", "Video", "YouTube",
        "Browser", "Files", "Weather",
    ], os.path.join(out, "09-all-apps.png"))

    wallpaper_sheet(os.path.join(out, "10-wallpapers.png"))
