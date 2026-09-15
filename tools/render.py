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
import subprocess
import sys
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

W, H = 1280, 720
DENSITY = 186 / 160.0          # matches `wm density` on the unit
REG = os.path.join(HERE, "Roboto-Regular.ttf")
MED = os.path.join(HERE, "Roboto-Medium.ttf")

# Shapes.FAMILY, verbatim.
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


def icon(draw, box, fill, letter, on):
    rounded(draw, box, (14, 14, 14, 14), fill)
    f = font(MED, int((box[3] - box[1]) * 0.5))
    w = text_w(draw, letter, f)
    draw.text((box[0] + ((box[2] - box[0]) - w) / 2,
               box[1] + (box[3] - box[1]) * 0.22), letter, font=f, fill=on)


def home(seed, apps, chips, path):
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

    # ---- tile grid --------------------------------------------------
    gx0 = pad + left_w
    gw = inner_w - left_w
    gh = H - 2 * pad
    cols, rows = 4, 3
    cw, chh = gw / cols, gh / rows

    roles = [("primaryContainer", "onPrimaryContainer"),
             ("secondaryContainer", "onSecondaryContainer"),
             ("tertiaryContainer", "onTertiaryContainer"),
             ("surfaceContainerHigh", "onSurface")]

    for i, name in enumerate(apps):
        c, r = i % cols, i // cols
        g = dp(8)
        x0 = int(gx0 + c * cw) + g
        y0 = int(pad + r * chh) + g
        x1 = int(gx0 + (c + 1) * cw) - g
        y1 = int(pad + (r + 1) * chh) - g

        fillk, onk = roles[i % 4]
        rounded(d, (x0, y0, x1, y1), FAMILY[i % len(FAMILY)], p[fillk])

        isz = dp(52)
        ix = x0 + ((x1 - x0) - isz) // 2
        iy = y0 + int((y1 - y0) * 0.5) - isz - dp(4)
        icon(d, (ix, iy, ix + isz, iy + isz), p[onk], name[0].upper(), p[fillk])

        f_lab = font(REG, sp(15))
        lw = text_w(d, name, f_lab)
        d.text((x0 + ((x1 - x0) - lw) / 2, iy + isz + dp(8)),
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
        icon(d, (ix, iy, ix + isz, iy + isz), p["primaryContainer"],
             name[0].upper(), p["onPrimaryContainer"])

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
    all_apps("4F7BD5", [
        "Android Auto", "Bluetooth", "CarPlay", "Equaliser", "FM Radio",
        "Gallery", "Maps", "Music", "Settings", "USB", "Video", "YouTube",
        "Browser", "Files", "Weather",
    ], os.path.join(out, "04-all-apps.png"))
