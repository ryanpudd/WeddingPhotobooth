"""Generates candidate Halloween border overlays (1920x1200 RGBA, transparent centre).

Usage: python tools/borders/generate_borders.py <out-dir>   (needs Pillow, and Windows' Chiller font)

Writes each design as <name>.png, a <name>-mockup.jpg over a stand-in camera frame, and a
contact-sheet.jpg. The booth shows app/src/main/res/drawable-nodpi/border_overlay.png, which
is currently 1-haunted-night.png; copy a new design over it to change the border. Edit LABEL
to change the bottom-right text.
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
W, H = 1920, 1200
S = 2  # supersample factor
SW, SH = W * S, H * S
LABEL = "Halloween 2026"
CHILLER = "C:/Windows/Fonts/CHILLER.TTF"


def new_layer():
    return Image.new("RGBA", (SW, SH), (0, 0, 0, 0))


def finish(img):
    return img.resize((W, H), Image.LANCZOS)


def edge_vignette(color, alpha, inset, blur):
    """Coloured band fading from the edges toward a transparent centre."""
    mask = Image.new("L", (SW, SH), alpha)
    ImageDraw.Draw(mask).rounded_rectangle(
        (inset * S, inset * S, SW - inset * S, SH - inset * S), radius=120 * S, fill=0)
    mask = mask.filter(ImageFilter.GaussianBlur(blur * S))
    layer = Image.new("RGBA", (SW, SH), color + (0,))
    layer.putalpha(mask)
    return layer


def rot(points, angle, cx, cy):
    c, s = math.cos(angle), math.sin(angle)
    return [(cx + x * c - y * s, cy + x * s + y * c) for x, y in points]


# ---------- shapes ----------

BAT_RIGHT = [(0, -0.22), (0.05, -0.36), (0.08, -0.2), (0.15, -0.16), (0.35, -0.34), (0.6, -0.42),
             (0.85, -0.32), (1.0, -0.08), (0.84, -0.02), (0.74, 0.16), (0.6, 0.02), (0.46, 0.2),
             (0.32, 0.04), (0.16, 0.18), (0.06, 0.2), (0, 0.3)]


def bat(d, cx, cy, size, angle=0.0, fill=(18, 8, 24, 255)):
    right = BAT_RIGHT
    left = [(-x, y) for x, y in reversed(right)]
    pts = [(x * size, y * size) for x, y in right + left]
    d.polygon(rot(pts, angle, cx, cy), fill=fill)


def spiderweb(d, ox, oy, sx, sy, radius, color, spokes=7, rings=7, width=2):
    """Web anchored at corner (ox, oy); sx/sy are +1/-1 for direction into the image."""
    angles = [math.radians(90 * i / (spokes - 1)) for i in range(spokes)]
    for a in angles:
        d.line([(ox, oy), (ox + sx * radius * math.cos(a), oy + sy * radius * math.sin(a))],
               fill=color, width=width * S)
    for r_i in range(1, rings + 1):
        r = radius * r_i / (rings + 0.4)
        for a0, a1 in zip(angles, angles[1:]):
            pts = []
            for t in [i / 10 for i in range(11)]:
                a = a0 + (a1 - a0) * t
                sag = 1 - 0.12 * math.sin(math.pi * t)
                pts.append((ox + sx * r * sag * math.cos(a), oy + sy * r * sag * math.sin(a)))
            d.line(pts, fill=color, width=max(1, width * S - 1))


def spider(d, x, top, length, size, color=(15, 10, 15, 255), thread=(220, 220, 220, 200)):
    y = top + length
    d.line([(x, top), (x, y)], fill=thread, width=2 * S)
    for side in (-1, 1):
        for i in range(4):
            a = math.radians(-50 + i * 30)
            kx = x + side * size * 1.1 * math.cos(a)
            ky = y + size * 0.3 + size * 0.9 * math.sin(a) - size * 0.5
            fx = x + side * size * 1.9 * math.cos(a)
            fy = ky + size * 0.9
            d.line([(x, y + size * 0.3), (kx, ky), (fx, fy)], fill=color, width=int(size * 0.14))
    d.ellipse((x - size * 0.45, y - size * 0.35, x + size * 0.45, y + size * 0.4), fill=color)
    d.ellipse((x - size * 0.7, y + size * 0.2, x + size * 0.7, y + size * 1.6), fill=color)
    for ex in (-1, 1):
        d.ellipse((x + ex * size * 0.18 - size * 0.08, y - size * 0.05,
                   x + ex * size * 0.18 + size * 0.08, y + size * 0.11), fill=(200, 30, 30, 255))


def moon(img, cx, cy, r):
    glow = new_layer()
    gd = ImageDraw.Draw(glow)
    gd.ellipse((cx - r * 1.6, cy - r * 1.6, cx + r * 1.6, cy + r * 1.6), fill=(255, 240, 180, 110))
    glow = glow.filter(ImageFilter.GaussianBlur(r * 0.4))
    img.alpha_composite(glow)
    d = ImageDraw.Draw(img)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(250, 238, 190, 255))
    rng = random.Random(3)
    for _ in range(7):
        a, dist, cr = rng.uniform(0, 6.28), rng.uniform(0, r * 0.65), rng.uniform(r * 0.07, r * 0.2)
        x, y = cx + dist * math.cos(a), cy + dist * math.sin(a)
        d.ellipse((x - cr, y - cr, x + cr, y + cr), fill=(225, 210, 160, 255))


def tombstone(d, x, base, w, h, fill, kind):
    if kind == "round":
        d.rectangle((x - w / 2, base - h + w / 2, x + w / 2, base), fill=fill)
        d.ellipse((x - w / 2, base - h, x + w / 2, base - h + w), fill=fill)
    elif kind == "cross":
        d.rectangle((x - w * 0.14, base - h, x + w * 0.14, base), fill=fill)
        d.rectangle((x - w * 0.45, base - h * 0.78, x + w * 0.45, base - h * 0.6), fill=fill)
    else:
        d.polygon([(x - w / 2, base), (x - w / 2, base - h * 0.75), (x, base - h),
                   (x + w / 2, base - h * 0.75), (x + w / 2, base)], fill=fill)


def tree(d, x, y, length, angle, width, depth, fill, rng):
    if depth == 0 or length < 6 * S:
        return
    x2 = x + length * math.cos(angle)
    y2 = y + length * math.sin(angle)
    d.line([(x, y), (x2, y2)], fill=fill, width=max(S, int(width)))
    d.ellipse((x2 - width / 2, y2 - width / 2, x2 + width / 2, y2 + width / 2), fill=fill)
    for spread in (-0.5, 0.45) if depth > 1 else (-0.4,):
        tree(d, x2, y2, length * rng.uniform(0.62, 0.78), angle + spread + rng.uniform(-0.25, 0.25),
             width * 0.68, depth - 1, fill, rng)


def drip_band(d, edge_points_y, rng, color, xs_range, max_len, min_w, max_w, count, highlight):
    """Top band of liquid with drips; edge_points_y(x) gives the band's lower edge."""
    poly = [(0, 0)]
    for x in range(0, SW + 1, 8 * S):
        poly.append((x, edge_points_y(x)))
    poly.append((SW, 0))
    d.polygon(poly, fill=color)
    for _ in range(count):
        x = rng.uniform(*xs_range)
        w = rng.uniform(min_w, max_w) * S
        top = edge_points_y(x) - 10 * S
        length = rng.uniform(0.15, 1.0) ** 1.6 * max_len * S
        bottom = top + length
        d.rounded_rectangle((x - w / 2, top, x + w / 2, bottom), radius=w / 2, fill=color)
        bulb = w * 0.75
        d.ellipse((x - bulb, bottom - bulb * 1.5, x + bulb, bottom + bulb * 0.4), fill=color)
        # fillet where the drip leaves the band
        d.ellipse((x - w * 1.3, top - w * 0.6, x + w * 1.3, top + w * 0.9), fill=color)
        if highlight:
            d.ellipse((x - bulb * 0.45, bottom - bulb * 1.05, x - bulb * 0.05, bottom - bulb * 0.45),
                      fill=highlight)


def label(img, text, fill, plate, font_size=92, font_path=CHILLER, pad=(36, 14), margin=(70, 70),
          stroke=None):
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(font_path, font_size * S)
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x1 = SW - margin[0] * S
    y1 = SH - margin[1] * S
    x0 = x1 - tw - 2 * pad[0] * S
    y0 = y1 - th - 2 * pad[1] * S
    if plate:
        d.rounded_rectangle((x0, y0, x1, y1), radius=18 * S, fill=plate)
    kw = {}
    if stroke:
        kw = dict(stroke_width=stroke[0] * S, stroke_fill=stroke[1])
    d.text((x0 + pad[0] * S - bbox[0], y0 + pad[1] * S - bbox[1]), text, font=font, fill=fill, **kw)


# ---------- designs ----------

def haunted_night():
    img = new_layer()
    img.alpha_composite(edge_vignette((26, 10, 40), 235, 150, 70))
    moon(img, 230 * S, 190 * S, 95 * S)
    d = ImageDraw.Draw(img)
    rng = random.Random(7)
    sil = (8, 4, 12, 255)
    # graveyard silhouette along the bottom
    ground = []
    for x in range(0, SW + 1, 10 * S):
        ground.append((x, SH - (70 + 22 * math.sin(x / (210 * S)) + 12 * math.sin(x / (63 * S))) * S))
    d.polygon([(0, SH)] + ground + [(SW, SH)], fill=sil)
    kinds = ["round", "cross", "point"]
    for gx in [120, 260, 420, 610, 840, 1060, 1290, 1480]:
        x = (gx + rng.uniform(-25, 25)) * S
        base = SH - 55 * S
        tombstone(d, x, base, rng.uniform(55, 85) * S, rng.uniform(85, 140) * S, sil, rng.choice(kinds))
    # iron fence along part of the bottom
    for fx in range(1540, 1900, 26):
        x = fx * S
        d.rectangle((x - 3 * S, SH - 175 * S, x + 3 * S, SH - 40 * S), fill=sil)
        d.polygon([(x - 9 * S, SH - 175 * S), (x, SH - 200 * S), (x + 9 * S, SH - 175 * S)], fill=sil)
    d.rectangle((1530 * S, SH - 150 * S, 1910 * S, SH - 142 * S), fill=sil)
    d.rectangle((1530 * S, SH - 80 * S, 1910 * S, SH - 72 * S), fill=sil)
    # crooked tree on the left edge
    tree(d, 40 * S, SH - 60 * S, 260 * S, -1.35, 34 * S, 7, sil, random.Random(11))
    # webs in the top-right corner and a spider dangling from it
    spiderweb(d, SW, 0, -1, 1, 330 * S, (230, 230, 240, 190))
    spider(d, SW - 250 * S, 0, 300 * S, 20 * S)
    # bats across the top
    for bx, by, bs, ba in [(520, 110, 70, -0.2), (680, 70, 45, 0.15), (820, 140, 55, -0.05),
                           (1010, 60, 38, 0.25), (1180, 120, 60, -0.15), (1340, 75, 42, 0.1),
                           (400, 230, 40, 0.3), (1600, 420, 50, -0.25), (90, 520, 45, 0.2)]:
        bat(d, bx * S, by * S, bs * S, ba)
    label(img, LABEL, (255, 140, 30, 255), (8, 4, 12, 210), margin=(170, 225))
    return finish(img)


def blood_drip():
    img = new_layer()
    img.alpha_composite(edge_vignette((20, 0, 0), 200, 120, 60))
    d = ImageDraw.Draw(img)
    rng = random.Random(21)
    blood = (122, 4, 8, 255)
    hl = (210, 60, 60, 200)
    edge = lambda x: (48 + 14 * math.sin(x / (97 * S)) + 9 * math.sin(x / (31 * S))) * S
    drip_band(d, edge, rng, blood, (0, SW), 330, 12, 34, 46, hl)
    # runs down the sides
    for side in (0, 1):
        pts = []
        for y in range(0, SH + 1, 10 * S):
            w = (26 + 16 * math.sin(y / (120 * S) + side) + 8 * math.sin(y / (37 * S))) * S
            pts.append((w if side == 0 else SW - w, y))
        base_x = 0 if side == 0 else SW
        d.polygon([(base_x, 0)] + pts + [(base_x, SH)], fill=blood)
    # pool along the bottom
    pool = [(x, SH - (40 + 22 * math.sin(x / (150 * S)) + 10 * math.sin(x / (45 * S))) * S)
            for x in range(0, SW + 1, 10 * S)]
    d.polygon([(0, SH)] + pool + [(SW, SH)], fill=blood)
    # splatters
    for cx, cy, spread in [(190, 330, 120), (1720, 300, 110), (260, 1000, 130), (1000, 1080, 90), (1730, 760, 100), (150, 650, 80)]:
        cx, cy = cx * S, cy * S
        r0 = rng.uniform(22, 36) * S
        d.ellipse((cx - r0, cy - r0, cx + r0, cy + r0), fill=blood)
        for _ in range(26):
            a = rng.uniform(0, 6.28)
            dist = rng.uniform(0.4, 1.0) ** 0.7 * spread * S
            r = rng.uniform(3, 14) * S * (1.1 - dist / (spread * S))
            x, y = cx + dist * math.cos(a), cy + dist * math.sin(a)
            d.ellipse((x - r, y - r, x + r, y + r), fill=blood)
    label(img, LABEL, (235, 225, 210, 255), (60, 0, 0, 220), margin=(90, 110))
    return finish(img)


def jack_o_lantern(img, cx, cy, r, rng, lit=True):
    d = ImageDraw.Draw(img)
    if lit:
        glow = new_layer()
        ImageDraw.Draw(glow).ellipse((cx - r * 1.5, cy - r * 1.3, cx + r * 1.5, cy + r * 1.3),
                                     fill=(255, 150, 20, 90))
        img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(r * 0.35)))
        d = ImageDraw.Draw(img)
    # stem
    d.polygon([(cx - r * 0.1, cy - r * 0.75), (cx - r * 0.02, cy - r * 1.15), (cx + r * 0.2, cy - r * 1.1),
               (cx + r * 0.12, cy - r * 0.75)], fill=(70, 80, 30, 255))
    # ribs
    body = (232, 110, 20, 255)
    rib = (190, 80, 10, 255)
    for k, frac in enumerate([0.95, 0.62, 0.3]):
        for side in (-1, 1):
            ox = side * r * (0.95 - frac) * 0.55
            d.ellipse((cx + ox - r * frac, cy - r * 0.8, cx + ox + r * frac, cy + r * 0.8),
                      fill=body, outline=rib, width=max(2, int(r * 0.05)))
    # face
    face = (255, 214, 70, 255) if lit else (40, 20, 5, 255)
    ey = cy - r * 0.18
    for side in (-1, 1):
        ex = cx + side * r * 0.36
        d.polygon([(ex - r * 0.17, ey + r * 0.12), (ex + r * 0.17, ey + r * 0.12), (ex, ey - r * 0.2)], fill=face)
    d.polygon([(cx - r * 0.08, cy + r * 0.04), (cx + r * 0.08, cy + r * 0.04), (cx, cy - r * 0.1)], fill=face)
    mouth = []
    n = 9
    for i in range(n + 1):
        t = i / n
        x = cx - r * 0.55 + r * 1.1 * t
        y = cy + r * 0.22 + r * 0.18 * math.sin(math.pi * t)
        mouth.append((x, y - (r * 0.1 if i % 2 else 0)))
    lower = [(cx + r * 0.55 - r * 1.1 * t, cy + r * 0.3 + r * 0.32 * math.sin(math.pi * t))
             for t in [i / 12 for i in range(13)]]
    d.polygon(mouth + lower, fill=face)


def candy_corn(d, x, y, size, angle):
    pts = [(0, -size), (size * 0.55, size * 0.6), (-size * 0.55, size * 0.6)]
    tri = rot(pts, angle, x, y)
    d.polygon(tri, fill=(255, 250, 235, 255))
    mid = rot([(0, -size * 0.45), (size * 0.36, size * 0.25), (-size * 0.36, size * 0.25)], angle, x, y)
    d.polygon(rot([(-size * 0.55, size * 0.6), (size * 0.55, size * 0.6), (size * 0.36, size * 0.25),
                   (-size * 0.36, size * 0.25)], angle, x, y), fill=(250, 200, 30, 255))
    d.polygon(rot([(-size * 0.36, size * 0.25), (size * 0.36, size * 0.25), (size * 0.16, -size * 0.2),
                   (-size * 0.16, -size * 0.2)], angle, x, y), fill=(240, 120, 20, 255))


def vine(d, pts, rng, color=(40, 70, 25, 255)):
    d.line(pts, fill=color, width=7 * S, joint="curve")
    for i in range(6, len(pts) - 6, 14):
        x, y = pts[i]
        # curl
        r0 = rng.uniform(14, 24) * S
        curl = [(x + r0 * (1 - t) * math.cos(t * 9 + 1), y + r0 * (1 - t) * math.sin(t * 9 + 1))
                for t in [k / 30 for k in range(31)]]
        d.line(curl, fill=color, width=3 * S)
        # leaf
        a = rng.uniform(0, 6.28)
        L = rng.uniform(26, 40) * S
        leaf = [(0, 0), (L * 0.5, -L * 0.35), (L, 0), (L * 0.5, L * 0.35)]
        d.polygon(rot(leaf, a, x, y), fill=(55, 95, 30, 255))


def pumpkin_patch():
    img = new_layer()
    img.alpha_composite(edge_vignette((30, 14, 4), 170, 140, 60))
    d = ImageDraw.Draw(img)
    rng = random.Random(5)
    # vine running round the frame
    loop = []
    inset = 45
    for t in [i / 600 for i in range(601)]:
        per = 2 * (W + H - 4 * inset)
        p = t * per
        w, h = W - 2 * inset, H - 2 * inset
        if p < w:
            x, y = inset + p, inset
        elif p < w + h:
            x, y = W - inset, inset + (p - w)
        elif p < 2 * w + h:
            x, y = W - inset - (p - w - h), H - inset
        else:
            x, y = inset, H - inset - (p - 2 * w - h)
        wob = 18 * math.sin(t * 60)
        if inset == y or y == H - inset:
            y += wob
        else:
            x += wob
        loop.append((x * S, y * S))
    vine(d, loop, rng)
    for i in range(55):
        side = rng.choice("tblr")
        if side == "t":
            x, y = rng.uniform(80, 1840), rng.uniform(20, 120)
        elif side == "b":
            x, y = rng.uniform(80, 1840), rng.uniform(1080, 1180)
        elif side == "l":
            x, y = rng.uniform(20, 120), rng.uniform(80, 1120)
        else:
            x, y = rng.uniform(1800, 1900), rng.uniform(80, 1120)
        candy_corn(d, x * S, y * S, rng.uniform(16, 24) * S, rng.uniform(-0.8, 0.8))
    for cx, cy, r, lit in [(150, 1040, 135, True), (360, 1110, 80, False), (1770, 1030, 120, True),
                           (110, 150, 95, True), (1810, 140, 85, False), (960, 1130, 70, True),
                           (640, 70, 55, False), (1300, 75, 60, True), (60, 600, 60, False), (1865, 600, 62, True)]:
        jack_o_lantern(img, cx * S, cy * S, r * S, rng, lit)
    d = ImageDraw.Draw(img)
    for bx, by, bs, ba in [(330, 210, 40, 0.2), (1560, 230, 46, -0.2), (1500, 140, 30, 0.1)]:
        bat(d, bx * S, by * S, bs * S, ba)
    label(img, LABEL, (255, 150, 30, 255), (30, 14, 4, 220), margin=(330, 55))
    return finish(img)


def eyeball(d, x, y, r, rng, look):
    d.ellipse((x - r, y - r, x + r, y + r), fill=(245, 242, 230, 255), outline=(120, 20, 20, 255), width=max(2, int(r * 0.04)))
    for _ in range(6):
        a = rng.uniform(0, 6.28)
        pts = []
        px, py = x + r * 0.97 * math.cos(a), y + r * 0.97 * math.sin(a)
        for k in range(5):
            pts.append((px, py))
            a2 = a + math.pi + rng.uniform(-0.6, 0.6)
            px += r * 0.12 * math.cos(a2)
            py += r * 0.12 * math.sin(a2)
        d.line(pts, fill=(200, 30, 30, 255), width=max(1, int(r * 0.03)))
    ix, iy = x + look[0] * r * 0.3, y + look[1] * r * 0.3
    ir = r * 0.48
    d.ellipse((ix - ir, iy - ir, ix + ir, iy + ir), fill=rng.choice([(60, 160, 60, 255), (190, 120, 20, 255), (130, 30, 160, 255)]))
    pr = ir * 0.5
    d.ellipse((ix - pr, iy - pr, ix + pr, iy + pr), fill=(5, 5, 5, 255))
    d.ellipse((ix - pr * 0.9, iy - pr * 1.1, ix - pr * 0.3, iy - pr * 0.5), fill=(255, 255, 255, 230))


def toxic_slime():
    img = new_layer()
    img.alpha_composite(edge_vignette((10, 25, 5), 190, 120, 60))
    d = ImageDraw.Draw(img)
    rng = random.Random(42)
    slime = (110, 210, 40, 255)
    edge = lambda x: (60 + 16 * math.sin(x / (110 * S)) + 10 * math.sin(x / (37 * S))) * S
    drip_band(d, edge, rng, slime, (0, SW), 300, 16, 42, 40, (210, 255, 160, 220))
    # dark rim on the band for depth
    d.line([(x, edge(x)) for x in range(0, SW + 1, 8 * S)], fill=(60, 140, 20, 255), width=3 * S)
    # oozing sides and bottom
    for side in (0, 1):
        pts = []
        for y in range(0, SH + 1, 10 * S):
            w = (22 + 14 * math.sin(y / (140 * S) + side * 2) + 7 * math.sin(y / (41 * S))) * S
            pts.append((w if side == 0 else SW - w, y))
        base_x = 0 if side == 0 else SW
        d.polygon([(base_x, 0)] + pts + [(base_x, SH)], fill=slime)
    pool = [(x, SH - (55 + 25 * math.sin(x / (170 * S)) + 12 * math.sin(x / (50 * S))) * S)
            for x in range(0, SW + 1, 10 * S)]
    d.polygon([(0, SH)] + pool + [(SW, SH)], fill=slime)
    # bubbles
    for _ in range(60):
        x = rng.choice([rng.uniform(0, 70), rng.uniform(1850, 1920), rng.uniform(0, 1920)])
        y = rng.uniform(1110, 1190) if rng.random() < 0.5 else rng.uniform(0, 1200)
        if 90 < x < 1830 and 120 < y < 1110:
            continue
        r = rng.uniform(5, 16) * S
        d.ellipse((x * S - r, y * S - r, x * S + r, y * S + r), outline=(220, 255, 180, 230), width=2 * S)
    # eyeballs peeking from the ooze
    for ex, ey, er in [(140, 1080, 70), (330, 1140, 45), (1730, 1060, 60), (1600, 1150, 38),
                       (60, 300, 45), (1860, 380, 50), (900, 1150, 42), (1210, 1130, 52)]:
        look = (rng.uniform(-1, 1), rng.uniform(-1, 0.2))
        eyeball(d, ex * S, ey * S, er * S, rng, look)
    label(img, LABEL, (190, 255, 90, 255), (10, 25, 5, 225), margin=(330, 160),
          stroke=(2, (20, 50, 5, 255)))
    return finish(img)


def mockup(border, name):
    """Border over a stand-in camera frame, for judging how it reads on the tablet."""
    bg = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):
        c = int(70 + 60 * y / H)
        d.line([(0, y), (W, y)], fill=(c, c - 8, c - 16, 255))
    d.ellipse((760, 330, 1160, 800), fill=(150, 120, 100, 255))
    d.rounded_rectangle((640, 760, 1280, 1200), radius=120, fill=(60, 70, 110, 255))
    bg.alpha_composite(border)
    return bg


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    designs = {"1-haunted-night": haunted_night, "2-blood-drip": blood_drip,
               "3-pumpkin-patch": pumpkin_patch, "4-toxic-slime": toxic_slime}
    thumbs = []
    for name, fn in designs.items():
        border = fn()
        border.save(OUT / f"{name}.png", optimize=True)
        m = mockup(border, name)
        m.convert("RGB").save(OUT / f"{name}-mockup.jpg", quality=90)
        thumbs.append((name, m.resize((W // 2, H // 2), Image.LANCZOS)))
        print("wrote", name)
    sheet = Image.new("RGB", (W, H + 80), (25, 25, 25))
    sd = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 34)
    for i, (name, t) in enumerate(thumbs):
        x, y = (i % 2) * (W // 2), (i // 2) * (H // 2 + 40)
        sheet.paste(t.convert("RGB"), (x, y + 40))
        sd.text((x + 16, y + 2), name, font=font, fill=(240, 240, 240))
    sheet.save(OUT / "contact-sheet.jpg", quality=88)
    print("wrote contact sheet")
