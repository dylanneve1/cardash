#!/usr/bin/env python3
"""
Render CarDash screens at the head unit's real resolution.

This is a mockup renderer, not a device capture — but it is not
eyeballed either. Every dp/sp value below is copied from the layout
code, the density matches the unit (160dpi base with a 186 override),
and the colours come from running the app's own M3 class on the JVM.
So layout, proportion and palette are faithful; only the rasteriser and
the app icons are stand-ins.
"""
import math
import subprocess
import sys
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

W, H = 1280, 720
DENSITY = 186 / 160.0          # matches `wm density` on the unit
# Same faces the APK bundles, so the mockups render in the real type.
REG = os.path.join(ROOT, "assets/fonts/GoogleSansText-Regular.ttf")
MED = os.path.join(ROOT, "assets/fonts/GoogleSansText-Medium.ttf")

# Shapes.FAMILY, verbatim.
# HomeActivity.SPANS, verbatim: {column, row, colSpan, rowSpan}
SPANS = [
    (0, 0, 2, 2),
    (2, 0, 2, 1), (4, 0, 2, 1),
    (2, 1, 1, 1), (3, 1, 1, 1), (4, 1, 2, 1),
    (0, 2, 1, 1), (1, 2, 1, 1), (2, 2, 1, 1),
    (3, 2, 1, 1), (4, 2, 1, 1), (5, 2, 1, 1),
]

FAMILY = [
    (28, 28, 28, 28),
    (36, 12, 36, 12),
    (12, 36, 12, 36),
    (44, 44, 16, 16),
    (16, 16, 44, 44),
    (40, 20, 40, 20),
]


def dp(v):
    return int(round(v * DENSITY))


def sp(v):
    return int(round(v * DENSITY))


_COMPILED = False


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
    _ensure_compiled()
    out = subprocess.run(
        ["java", "-cp", os.path.join(ROOT, "build/tools"), "DumpPalette", seed],
        capture_output=True, text=True, check=True).stdout
    p = {}
    for line in out.strip().splitlines():
        k, v = line.split("=")
        p[k] = v
    return p


def font(path, size):
    return ImageFont.truetype(path, size)


def rounded(draw, box, radii, fill):
    """Per-corner rounded rect: radii clockwise from top-left."""
    x0, y0, x1, y1 = box
    tl, tr, br, bl = [dp(r) for r in radii]
    # Clamp so a big radius on a small tile can't invert the geometry.
    m = min(x1 - x0, y1 - y0) // 2
    tl, tr, br, bl = [max(0, min(r, m)) for r in (tl, tr, br, bl)]
    # Interior as a polygon between the arc endpoints, then the arcs.
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
    x0, y0, x1, y1 = box
    r = (y1 - y0) // 2
    draw.rectangle([x0 + r, y0, x1 - r, y1], fill=fill)
    draw.ellipse([x0, y0, x0 + 2 * r, y1], fill=fill)
    draw.ellipse([x1 - 2 * r, y0, x1, y1], fill=fill)


def wallpaper(seed_hex):
    """Stand-in for the user's wallpaper: a soft gradient off the seed."""
    seed = tuple(int(seed_hex[i:i + 2], 16) for i in (0, 2, 4))
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(
            int(seed[i] * (0.55 - 0.42 * t)) for i in range(3)))
    return img


def text_w(draw, s, f):
    return draw.textbbox((0, 0), s, font=f)[2]


# MaterialShape.CYCLE, verbatim.
SHAPES = [("squircle", 0, 0.0), ("clover", 4, 0.14), ("flower", 6, 0.11),
          ("squircle", 0, 0.0), ("burst", 8, 0.09), ("scallop", 12, 0.055)]


def blend(hex_a, hex_b, t):
    """hex_a over hex_b at opacity t."""
    a = [int(hex_a[1:][i:i + 2], 16) for i in (0, 2, 4)]
    b = [int(hex_b[1:][i:i + 2], 16) for i in (0, 2, 4)]
    return "#%02X%02X%02X" % tuple(int(a[i] * t + b[i] * (1 - t)) for i in range(3))


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


def icon(draw, box, fill, letter, on, index=0):
    size = box[3] - box[1]
    draw.polygon(shape_points(box, index), fill=fill)
    f = font(MED, int(size * 0.42))
    bb = draw.textbbox((0, 0), letter, font=f)
    draw.text((box[0] + (size - (bb[2] - bb[0])) / 2 - bb[0],
               box[1] + (size - (bb[3] - bb[1])) / 2 - bb[1]),
              letter, font=f, fill=on)


MINIMAL = [(0, 0, 2, 3), (2, 0, 2, 3), (4, 0, 2, 3)]


def home(seed, apps, chips, path, spans=None):
    p = palette(seed)
    img = wallpaper(seed).convert("RGBA")
    layer = Image.new("RGBA", (W, H), p["surface"] + "D8")
    img = Image.alpha_composite(img, layer)
    d = ImageDraw.Draw(img)

    pad = dp(20)
    inner_w = W - 2 * pad
    left_w = int(inner_w * 0.34)

    # ---- left panel -------------------------------------------------
    lx0, ly0 = pad, pad
    lx1, ly1 = pad + left_w - dp(16), H - pad
    rounded(d, (lx0, ly0, lx1, ly1), (36, 36, 36, 36), p["surfaceContainer"])

    cx = lx0 + dp(24)
    # The panel uses Gravity.CENTER_VERTICAL, so measure first.
    block = (int(sp(72) * 1.18) + dp(4) + int(sp(18) * 1.3) + dp(20)
             + int(sp(18) * 1.3) + 2 * dp(14)
             + dp(18) + dp(64)
             + (dp(16) + int(sp(15) * 1.3) + 2 * dp(8) if chips else 0)
             + dp(14) + int(sp(12) * 1.3))
    y = ly0 + ((ly1 - ly0) - block) // 2

    f_clock = font(MED, sp(72))
    d.text((cx, y), "18:42", font=f_clock, fill=p["primary"])
    y += int(sp(72) * 1.18)

    f_date = font(REG, sp(18))
    y += dp(4)
    d.text((cx, y), "Tuesday 15 September", font=f_date, fill=p["onSurfaceVariant"])
    y += int(sp(18) * 1.3) + dp(20)

    # All apps pill
    f_pill = font(REG, sp(18))
    label = "All apps"
    tw = text_w(d, label, f_pill)
    ph = int(sp(18) * 1.3) + 2 * dp(14)
    pill(d, (cx, y, cx + tw + 2 * dp(28), y + ph), p["primaryContainer"])
    d.text((cx + dp(28), y + dp(14)), label, font=f_pill, fill=p["onPrimaryContainer"])
    y += ph

    # media transport (paths, matching Glyph.java)
    y += dp(18)
    mx = cx
    for kind, big in (("prev", False), ("play", True), ("next", False)):
        msz = dp(64) if big else dp(52)
        top = y + (dp(64) - msz) // 2
        fill = p["primary"] if big else p["surfaceContainerHigh"]
        on = p["onPrimary"] if big else p["onSurface"]
        pill(d, (mx, top, mx + msz, top + msz), fill)
        ccx, ccy = mx + msz / 2, top + msz / 2
        sz = msz * 0.46 * 0.5
        if kind == "play":
            d.polygon([(ccx - sz * 0.42, ccy - sz), (ccx + sz * 0.78, ccy),
                       (ccx - sz * 0.42, ccy + sz)], fill=on)
        elif kind == "prev":
            d.polygon([(ccx + sz * 0.9, ccy - sz), (ccx - sz * 0.1, ccy),
                       (ccx + sz * 0.9, ccy + sz)], fill=on)
            d.polygon([(ccx + sz * 0.05, ccy - sz), (ccx - sz * 0.95, ccy),
                       (ccx + sz * 0.05, ccy + sz)], fill=on)
            d.rectangle([ccx - sz * 0.95, ccy - sz, ccx - sz * 0.72, ccy + sz], fill=on)
        else:
            d.polygon([(ccx - sz * 0.9, ccy - sz), (ccx + sz * 0.1, ccy),
                       (ccx - sz * 0.9, ccy + sz)], fill=on)
            d.polygon([(ccx - sz * 0.05, ccy - sz), (ccx + sz * 0.95, ccy),
                       (ccx - sz * 0.05, ccy + sz)], fill=on)
            d.rectangle([ccx + sz * 0.72, ccy - sz, ccx + sz * 0.95, ccy + sz], fill=on)
        mx += msz + dp(10)
    y += dp(64)

    # vehicle chips
    if chips:
        y += dp(16)
        f_chip = font(REG, sp(15))
        ch = int(sp(15) * 1.3) + 2 * dp(8)
        x = cx
        for text, kind in chips:
            fill = p["surfaceContainerHigh"]
            on = p["onSurface"]
            if kind == "warn":
                fill, on = p["tertiaryContainer"], p["onTertiaryContainer"]
            elif kind == "alert":
                fill, on = p["primaryContainer"], p["onPrimaryContainer"]
            cw = text_w(d, text, f_chip) + 2 * dp(14)
            if x + cw > lx1 - dp(24):
                x = cx
                y += ch + dp(8)
            pill(d, (x, y, x + cw, y + ch), fill)
            d.text((x + dp(14), y + dp(8)), text, font=f_chip, fill=on)
            x += cw + dp(8)
        y += ch

    f_hint = font(REG, sp(12))
    d.text((cx, y + dp(14)), "Long-press a tile to change it",
           font=f_hint, fill=p["onSurfaceVariant"])

    # ---- tile mosaic ------------------------------------------------
    gx0 = pad + left_w
    gw = inner_w - left_w
    gh = H - 2 * pad
    cols, rows = 6, 3
    cw, chh = gw / cols, gh / rows

    roles = [("primaryContainer", "onPrimaryContainer"),
             ("secondaryContainer", "onSecondaryContainer"),
             ("tertiaryContainer", "onTertiaryContainer"),
             ("surfaceContainerHigh", "onSurface")]

    layout = spans or SPANS
    for i, name in enumerate(apps[:len(layout)]):
        col, row, cspan, rspan = layout[i]
        hero = cspan > 1 and rspan > 1
        g = dp(7)
        x0 = int(gx0 + col * cw) + g
        y0 = int(pad + row * chh) + g
        x1 = int(gx0 + (col + cspan) * cw) - g
        y1 = int(pad + (row + rspan) * chh) - g

        fillk, onk = roles[i % 4]
        rounded(d, (x0, y0, x1, y1), FAMILY[i % len(FAMILY)], p[fillk])

        isz = dp(104) if hero else dp(64)
        lsz = sp(22) if hero else sp(15)
        f_lab = font(REG, lsz)
        gap = dp(8)
        block = isz + gap + int(lsz * 1.3)

        iy = y0 + ((y1 - y0) - block) // 2
        ix = x0 + ((x1 - x0) - isz) // 2
        icon(d, (ix, iy, ix + isz, iy + isz),
             blend(p[onk], p[fillk], 0.19), name[0].upper(), p[onk], i)

        lw = text_w(d, name, f_lab)
        d.text((x0 + ((x1 - x0) - lw) / 2, iy + isz + gap),
               name, font=f_lab, fill=p[onk])

    img.convert("RGB").save(path)
    print("wrote", path)


def all_apps(seed, names, path):
    p = palette(seed)
    img = Image.new("RGB", (W, H), p["surface"])
    d = ImageDraw.Draw(img)
    pad = dp(20)

    f_title = font(MED, sp(26))
    d.text((pad + dp(8), pad), "All apps", font=f_title, fill=p["onSurface"])

    top = pad + int(sp(26) * 1.3) + dp(16)
    cols = 5
    gw = W - 2 * pad
    cw = gw / cols
    chh = dp(118)

    for i, name in enumerate(names):
        c, r = i % cols, i // cols
        g = dp(6)
        x0 = int(pad + c * cw) + g
        y0 = int(top + r * chh) + g
        x1 = int(pad + (c + 1) * cw) - g
        y1 = int(top + (r + 1) * chh) - g
        if y1 > H:
            break
        rounded(d, (x0, y0, x1, y1), FAMILY[i % len(FAMILY)],
                p["surfaceContainer"])

        isz = dp(48)
        ix = x0 + ((x1 - x0) - isz) // 2
        iy = y0 + dp(16)
        icon(d, (ix, iy, ix + isz, iy + isz),
             blend(p["onSurface"], p["surfaceContainer"], 0.19),
             name[0].upper(), p["onSurface"], i)

        f_lab = font(REG, sp(14))
        lw = text_w(d, name, f_lab)
        d.text((x0 + ((x1 - x0) - lw) / 2, iy + isz + dp(8)),
               name, font=f_lab, fill=p["onSurface"])

    img.save(path)
    print("wrote", path)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "screenshots")
    os.makedirs(out, exist_ok=True)

    tiles = ["Android Auto", "CarPlay", "Music", "Bluetooth",
             "Maps", "Gallery", "Equaliser", "FM Radio",
             "Video", "Files", "YouTube", "Settings"]

    home("4F7BD5", tiles, [], os.path.join(out, "01-home-blue.png"))
    home("C96A1E", tiles, [
        ("Fuel 34%", "ok"), ("89°C", "ok"), ("1726 rpm", "ok"),
    ], os.path.join(out, "02-home-amber-obd.png"))
    home("2E8B57", tiles, [
        ("Fuel 11%", "warn"), ("72°C", "ok"), ("Door open", "alert"),
    ], os.path.join(out, "03-home-green-warnings.png"))
    home("4F7BD5", ["CarPlay", "Bluetooth", "Settings"], [],
         os.path.join(out, "05-home-minimal.png"), spans=MINIMAL)

    all_apps("4F7BD5", [
        "Android Auto", "Bluetooth", "CarPlay", "Equaliser", "FM Radio",
        "Gallery", "Maps", "Music", "Settings", "USB", "Video", "YouTube",
        "Browser", "Files", "Weather",
    ], os.path.join(out, "04-all-apps.png"))
