"""PROTOTYPE, throwaway: answers wayfinder ticket #8 ("How do we get a wedding border with a
clean window at a usable resolution?"). Not production code; lives on branch
prototype/wedding-border only.

Usage:
    python tools/borders/prototype_wedding_border.py [--upscaled <4x-upscaled-original.png>]

Needs Pillow, numpy and scipy. Writes into tmp/wedding-border-prototype/ (gitignored):
    a-mask.png      (a) original artwork upscaled, leaves masked out of a clean window, label moved
    a-mat.png       (a) as a-mask, plus an ivory mat and gold keyline that make the cut deliberate
    b-redraw.png    (b) autumn-leaf border redrawn from scratch, no leaf crosses the window
    index.html      viewer: switch variant / gap colour / photo / zoom with the bottom bar
    compare.jpg     all three over one photo, for a quick look

Without --upscaled, (a) uses a plain Lanczos resize as a STAND-IN for the AI upscale.
"""
import argparse
import io
import json
import math
import random
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "tmp" / "wedding-border-prototype"
PHOTOS = REPO / "tmp" / "WeddingPhotobooth"
W, H = 3840, 2400
LABEL = "Nicola & Ryan 2026"
FONT = "C:/Windows/Fonts/pala.ttf"
TEXT = (96, 22, 18, 255)
PLATE = (249, 246, 239, 225)

# The window: 16:9 like the camera, so a 1x crop is the whole photo. Same rectangle for every
# variant so they compare like for like.
WIN_W = 3080
WIN_H = WIN_W * 9 // 16  # 1732
WIN_X = (W - WIN_W) // 2  # 380
WIN_Y = (H - WIN_H) // 2  # 334
RADIUS = 48

SAMPLE_PHOTOS = ["2026-09-12-12-00-11.png", "2026-09-12-11-52-25.png", "2026-08-03-19-56-56.png",
                 "2026-09-12-12-02-00.png", "2026-09-12-11-49-16.png"]
GAPS = {"white": "#ffffff", "ivory": "#fbf6ec", "parchment": "#f1e4cc", "sage": "#dfe5d3",
        "forest": "#2f3b2a"}


def window_mask(grow=0, scale=1):
    m = Image.new("L", (W * scale, H * scale), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        ((WIN_X - grow) * scale, (WIN_Y - grow) * scale,
         (WIN_X + WIN_W + grow) * scale - 1, (WIN_Y + WIN_H + grow) * scale - 1),
        radius=(RADIUS + grow) * scale, fill=255)
    return m


def defringe(img):
    """The artwork was cut from white, so edges carry a white halo. Recolour every
    semi-transparent pixel from its nearest opaque neighbour, then tighten alpha slightly."""
    a = np.array(img).astype(np.float32)
    alpha = a[..., 3]
    solid = alpha >= 250
    _, (iy, ix) = ndimage.distance_transform_edt(~solid, return_indices=True)
    rgb = a[..., :3][iy, ix]
    edge = ~solid
    a[..., :3][edge] = rgb[edge]
    a[..., 3] = np.clip((alpha - 40) * 255 / 215, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def label(img, cx, cy, size=96):
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, size)
    b = d.textbbox((0, 0), LABEL, font=font)
    tw, th = b[2] - b[0], b[3] - b[1]
    px, py = 54, 26
    plate = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(plate).rounded_rectangle(
        (cx - tw / 2 - px, cy - th / 2 - py, cx + tw / 2 + px, cy + th / 2 + py), radius=30, fill=PLATE)
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (cx - tw / 2 - px, cy - th / 2 - py + 8, cx + tw / 2 + px, cy + th / 2 + py + 8), radius=30,
        fill=(60, 30, 10, 70))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
    img.alpha_composite(plate)
    ImageDraw.Draw(img).text((cx - tw / 2 - b[0], cy - th / 2 - b[1]), LABEL, font=font, fill=TEXT)


# ---------- (a) upscale + mask ----------

def load_original():
    blob = subprocess.run(["git", "-C", str(REPO), "show",
                           "077dc99^:app/src/main/res/drawable/border_overlay.png"],
                          capture_output=True, check=True).stdout
    return Image.open(io.BytesIO(blob)).convert("RGBA")


def upscaled(path):
    src = Image.open(path).convert("RGBA") if path else load_original()
    return src.resize((W, H), Image.LANCZOS)  # a 4x AI result is 5248x3264: only downsampled here


def remove_old_label(img):
    """The old label plate sits mostly inside the window; clear the strip of it left outside."""
    a = np.array(img)
    sx, sy = W / 1312, H / 816
    x0, y0, x1, y1 = int(846 * sx), int(612 * sy), int(1222 * sx), int(709 * sy)
    box = a[y0:y1, x0:x1].astype(int)
    r, g, b, al = box[..., 0], box[..., 1], box[..., 2], box[..., 3]
    whitish = (r > 225) & (g > 215) & (b > 200) & (np.max(box[..., :3], -1) - np.min(box[..., :3], -1) < 40)
    box[..., 3][whitish] = 0
    # leaves under the semi-transparent plate are washed out beyond recovery: clear them too, the
    # gap colour shows through and the surrounding leaves read as unbroken
    washed = (al > 0) & (box[..., :3].min(-1) > 140)
    box[..., 3][washed] = 0
    a[y0:y1, x0:x1] = box.astype(np.uint8)
    return Image.fromarray(a, "RGBA")


def variant_a(up, mat):
    img = defringe(remove_old_label(up))
    hole = window_mask(grow=40 if mat else 0)
    a = np.array(img)
    a[..., 3] = np.minimum(a[..., 3], 255 - np.array(hole))
    img = Image.fromarray(a, "RGBA")
    if mat:
        ring = Image.new("RGBA", (W * 2, H * 2), (0, 0, 0, 0))
        d = ImageDraw.Draw(ring)
        g = 40 * 2
        d.rounded_rectangle(((WIN_X * 2 - g), (WIN_Y * 2 - g), (WIN_X + WIN_W) * 2 + g, (WIN_Y + WIN_H) * 2 + g),
                            radius=(RADIUS + 40) * 2, fill=(251, 247, 238, 255))
        k = 14 * 2
        d.rounded_rectangle(((WIN_X * 2 - k), (WIN_Y * 2 - k), (WIN_X + WIN_W) * 2 + k, (WIN_Y + WIN_H) * 2 + k),
                            radius=(RADIUS + 14) * 2, outline=(186, 140, 62, 255), width=5 * 2)
        d.rounded_rectangle((WIN_X * 2, WIN_Y * 2, (WIN_X + WIN_W) * 2 - 1, (WIN_Y + WIN_H) * 2 - 1),
                            radius=RADIUS * 2, fill=(0, 0, 0, 0))
        ring = ring.resize((W, H), Image.LANCZOS)
        shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle(
            (WIN_X - 40, WIN_Y - 34, WIN_X + WIN_W + 40, WIN_Y + WIN_H + 46), radius=RADIUS + 40,
            fill=(70, 35, 10, 90))
        shadow = shadow.filter(ImageFilter.GaussianBlur(18))
        shadow.putalpha(Image.fromarray(np.minimum(np.array(shadow.getchannel("A")),
                                                   255 - np.array(window_mask(grow=40)))))
        img.alpha_composite(shadow)
        img.alpha_composite(ring)
    label(img, W // 2, WIN_Y + WIN_H + (H - WIN_Y - WIN_H) // 2 + 6)
    return img


# ---------- (b) redraw ----------

S = 2
PALETTE = [(214, 83, 30), (232, 134, 42), (242, 179, 61), (192, 57, 43), (200, 105, 40),
           (226, 160, 70), (176, 74, 32), (238, 118, 34), (205, 140, 55)]


def mix(c, t, k):
    return tuple(int(a + (b - a) * k) for a, b in zip(c, t))


def ovate(n=60, teeth=16, fat=0.32):
    top, bot = [], []
    for i in range(n + 1):
        t = i / n
        w = fat * math.sin(math.pi * t) ** 0.75 * (1 - 0.25 * t)
        w *= 1 + 0.07 * (1 - abs(((t * teeth) % 1) * 2 - 1))
        top.append((t, -w))
        bot.append((t, w))
    return top + bot[::-1], top


def maple():
    pts, half = [], []
    # (angle, height, width): broad lobes with pointed tips, a notch at the stem
    lobes = [(0, 1.0, .62), (1.05, .88, .6), (-1.05, .88, .6), (2.1, .55, .55), (-2.1, .55, .55)]
    for i in range(360):
        th = -math.pi + 2 * math.pi * i / 360
        r = 0.4
        for c, h, s in lobes:
            d = math.atan2(math.sin(th - c), math.cos(th - c))
            r = max(r, h * (math.cos(min(abs(d) / s, 1) * math.pi / 2) ** 0.9) * (1 + 0.22 * math.exp(-(d / .07) ** 2)))
        r *= 1 + 0.05 * abs(math.sin(th * 22))
        p = (0.15 + r * math.cos(th) * 0.85, r * math.sin(th) * 0.85)
        pts.append(p)
        if th <= 0:
            half.append(p)
    return pts, half


def oak():
    top, bot = [], []
    for i in range(81):
        t = i / 80
        w = 0.26 * math.sin(math.pi * t) ** 0.6 * (0.62 + 0.38 * abs(math.sin(t * math.pi * 4.5)))
        top.append((t, -w))
        bot.append((t, w))
    return top + bot[::-1], top


SHAPES = {"ovate": ovate, "maple": maple, "oak": oak, "leaflet": lambda: ovate(fat=0.2)}


def place(pts, x, y, size, ang):
    c, s = math.cos(ang), math.sin(ang)
    return [(x + (px * c - py * s) * size, y + (px * s + py * c) * size) for px, py in pts]


def draw_leaf(d, kind, x, y, size, ang, color, rng):
    """Leaf with its base at (x, y), pointing along ang. Returns the outline for collision."""
    if kind == "compound":
        outs = []
        for k, off in enumerate((-1.25, -0.62, 0, 0.62, 1.25)):
            ls = size * (0.62 + 0.38 * math.cos(off))
            outs += draw_leaf(d, "leaflet", x, y, ls, ang + off, mix(color, (255, 220, 120), 0.08 * k), rng)
        d.ellipse((x - size * .05, y - size * .05, x + size * .05, y + size * .05), fill=(110, 60, 25, 255))
        return outs
    out, half = SHAPES[kind]()
    poly = place(out, x, y, size, ang)
    d.polygon(poly, fill=color + (255,))
    hp = place(half + [(1, 0), (0, 0)], x, y, size, ang)
    d.polygon(hp, fill=mix(color, (90, 25, 5), 0.16) + (255,))
    vein = mix(color, (255, 236, 190), 0.45) + (255,)
    xs, ys = [q[0] for q in poly], [q[1] for q in poly]
    bx0, by0 = int(min(xs)) - 2, int(min(ys)) - 2
    bw, bh = int(max(xs)) - bx0 + 3, int(max(ys)) - by0 + 3
    vl = Image.new("L", (bw, bh), 0)
    vd = ImageDraw.Draw(vl)
    sh = lambda ps: [(px - bx0, py - by0) for px, py in ps]
    base, tip = place([(0.02, 0), (0.95, 0)], x, y, size, ang)
    vd.line(sh([base, tip]), fill=255, width=max(2, int(size * 0.018)))
    for k in range(1, 6):
        t = k / 6.5
        for sgn in (-1, 1):
            if kind == "maple":
                if k > 2:
                    continue
                a0 = sgn * (1.05 * k)
                ln = 0.85 if k == 1 else 0.5
                pp = place([(0.15, 0), (0.15 + ln * math.cos(a0), ln * math.sin(a0))], x, y, size, ang)
            else:
                pp = place([(t, 0), (t + 0.18, sgn * 0.5)], x, y, size, ang)
            vd.line(sh(pp), fill=255, width=max(1, int(size * 0.009)))
    clip = Image.new("L", (bw, bh), 0)
    ImageDraw.Draw(clip).polygon(sh(place([(px * 0.97 + 0.015, py * 0.9) for px, py in out], x, y, size, ang)), fill=255)
    vl = Image.fromarray(np.minimum(np.array(vl), np.array(clip)))
    d._image.paste(Image.new("RGBA", (bw, bh), vein), (bx0, by0), vl)
    if kind == "leaflet":
        return poly
    stem = place([(0, 0), (-0.16, 0.02)], x, y, size, ang)
    d.line(stem, fill=(110, 60, 25, 255), width=max(2, int(size * 0.02)))
    return poly


def variant_b(seed=11):
    rng = random.Random(seed)
    SW, SH = W * S, H * S
    keep_out = np.array(window_mask(grow=34, scale=S)) > 0  # leaves never come within 34px
    occupied = np.zeros((SH // 16, SW // 16), np.int16)
    tiers = [[] for _ in range(3)]

    def edge_point():
        per = 2 * (SW + SH)
        p = rng.uniform(0, per)
        if p < SW:
            x, y = p, 0
        elif p < SW + SH:
            x, y = SW, p - SW
        elif p < 2 * SW + SH:
            x, y = 2 * SW + SH - p, SH
        else:
            x, y = 0, per - p
        return x, y

    kinds = ["maple", "maple", "ovate", "oak", "compound", "maple", "oak"]
    tries = 0
    while tries < 9000 and sum(len(t) for t in tiers) < 230:
        tries += 1
        ex, ey = edge_point()
        corner = min(math.hypot(ex - cx, ey - cy) for cx in (0, SW) for cy in (0, SH)) < 700 * S
        depth = rng.uniform(0, (380 if corner else 250) * S)
        cxm, cym = SW / 2, SH / 2
        dx, dy = cxm - ex, cym - ey
        n = math.hypot(dx, dy)
        x = ex + dx / n * depth * (1.3 if ex in (0, SW) else 0.5) + rng.uniform(-60, 60) * S
        y = ey + dy / n * depth * (0.5 if ex in (0, SW) else 1.3) + rng.uniform(-60, 60) * S
        x = min(max(x, -40 * S), SW + 40 * S)
        y = min(max(y, -40 * S), SH + 40 * S)
        kind = rng.choice(kinds)
        size = rng.uniform(150, 330) * S * (1.2 if corner else 1.0)
        if kind == "compound":
            size *= 0.85
        ang = rng.uniform(0, 2 * math.pi)
        scratch = Image.new("L", (SW // 8, SH // 8), 0)
        sd = ImageDraw.Draw(scratch)
        out = draw_leaf_outline(kind, x, y, size, ang)
        for poly in out:
            sd.polygon([(px / 8, py / 8) for px, py in poly], fill=255)
        m = np.array(scratch) > 0
        if (m & keep_out[::8, ::8][:m.shape[0], :m.shape[1]]).any():
            continue
        cov = np.array(scratch.resize((SW // 16, SH // 16))) > 0
        if cov.sum() and occupied[cov].mean() > 1.6:
            continue
        occupied[cov] += 1
        tiers[rng.choices([0, 1, 2], [3, 4, 3])[0]].append((kind, x, y, size, ang, rng.choice(PALETTE)))

    img = Image.new("RGBA", (SW, SH), (0, 0, 0, 0))
    # twigs behind everything
    d = ImageDraw.Draw(img)
    for _ in range(26):
        ex, ey = edge_point()
        ang = rng.uniform(0, 2 * math.pi)
        pts = [(ex, ey)]
        for _ in range(12):
            ang += rng.uniform(-0.25, 0.25)
            nx, ny = pts[-1][0] + math.cos(ang) * 28 * S, pts[-1][1] + math.sin(ang) * 28 * S
            if keep_out[int(min(max(ny, 0), SH - 1)), int(min(max(nx, 0), SW - 1))]:
                break
            pts.append((nx, ny))
        if len(pts) > 2:
            d.line(pts, fill=(120, 72, 36, 255), width=5 * S, joint="curve")
    for tier in tiers:
        shadow = Image.new("RGBA", (SW, SH), (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow)
        for kind, x, y, size, ang, col in tier:
            for poly in draw_leaf_outline(kind, x + 10 * S, y + 14 * S, size, ang):
                sd.polygon(poly, fill=(70, 30, 5, 80))
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(9 * S)))
        d = ImageDraw.Draw(img)
        for kind, x, y, size, ang, col in tier:
            draw_leaf(d, kind, x, y, size, ang, col, rng)
    # the shadows may drift into the window: keep it perfectly clear
    a = np.array(img)
    a[..., 3] = np.minimum(a[..., 3], 255 - np.array(window_mask(grow=0, scale=S)))
    img = Image.fromarray(a, "RGBA").resize((W, H), Image.LANCZOS)
    label(img, W // 2, WIN_Y + WIN_H + (H - WIN_Y - WIN_H) // 2 + 6)
    return img


def draw_leaf_outline(kind, x, y, size, ang):
    if kind == "compound":
        polys = []
        for off in (-1.25, -0.62, 0, 0.62, 1.25):
            polys += draw_leaf_outline("leaflet", x, y, size * (0.62 + 0.38 * math.cos(off)), ang + off)
        return polys
    return [place(SHAPES[kind]()[0], x, y, size, ang)]


# ---------- viewer ----------

def write_viewer(variants, standin):
    win = {"x": WIN_X, "y": WIN_Y, "w": WIN_W, "h": WIN_H, "radius": RADIUS}
    photos = [f"../WeddingPhotobooth/{p}" for p in SAMPLE_PHOTOS]
    html = (Path(__file__).with_name("prototype_wedding_border.html")).read_text(encoding="utf-8")
    html = html.replace("/*DATA*/", "const DATA = " + json.dumps(
        {"variants": variants, "gaps": GAPS, "photos": photos, "window": win, "canvas": [W, H],
         "standin": standin}) + ";")
    (OUT / "index.html").write_text(html, encoding="utf-8")


def compare_sheet(imgs, photo, gap):
    tw, th = W // 4, H // 4
    sheet = Image.new("RGB", (tw * len(imgs), th + 60), (30, 30, 30))
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 34)
    p = Image.open(photo).convert("RGBA").resize((WIN_W, WIN_H), Image.LANCZOS)
    for i, (name, im) in enumerate(imgs.items()):
        c = Image.new("RGBA", (W, H), gap)
        c.paste(p, (WIN_X, WIN_Y))
        c.alpha_composite(im)
        sheet.paste(c.convert("RGB").resize((tw, th), Image.LANCZOS), (i * tw, 60))
        d.text((i * tw + 16, 10), name, font=font, fill=(240, 240, 240))
    return sheet


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--upscaled", help="4x AI-upscaled copy of the original border PNG")
    ap.add_argument("--only", help="comma list of variants to rebuild")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    only = set(args.only.split(",")) if args.only else None
    up = upscaled(args.upscaled)
    builders = {"a-mask": lambda: variant_a(up, mat=False), "a-mat": lambda: variant_a(up, mat=True),
                "b-redraw": variant_b}
    imgs = {}
    for name, fn in builders.items():
        path = OUT / f"{name}.png"
        if only and name not in only and path.exists():
            imgs[name] = Image.open(path).convert("RGBA")
            continue
        imgs[name] = fn()
        imgs[name].save(path, optimize=False)
        print("wrote", path)
    write_viewer(list(builders), standin=args.upscaled is None)
    compare_sheet(imgs, PHOTOS / SAMPLE_PHOTOS[0], GAPS["ivory"]).save(OUT / "compare.jpg", quality=88)
    print("wrote viewer and compare sheet in", OUT)
