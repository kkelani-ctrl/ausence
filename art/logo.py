"""Ausence logo options: eight four-pointed sparkles.

A "voice": separate sparkles whose heights follow a speaking rhythm.
B "orbit": sparkles round a ring, growing as they go (time passing, chapters adding up).
Prints/writes paths for a given viewport and PNG previews (rasterised with PIL).
"""
import math
import re
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageChops, ImageFont


def sparkle(cx, cy, w, h, pinch=0.24, angle=0.0):
    """Four-pointed sparkle with concave sides. `pinch` sets how full the body is."""
    p = pinch
    local = [
        [(0, -h), (w * p * 0.3, -h * p * 1.5), (w * p * 1.5, -h * p * 0.3), (w, 0)],
        [(w, 0), (w * p * 1.5, h * p * 0.3), (w * p * 0.3, h * p * 1.5), (0, h)],
        [(0, h), (-w * p * 0.3, h * p * 1.5), (-w * p * 1.5, h * p * 0.3), (-w, 0)],
        [(-w, 0), (-w * p * 1.5, -h * p * 0.3), (-w * p * 0.3, -h * p * 1.5), (0, -h)],
    ]
    ca, sa = math.cos(angle), math.sin(angle)
    rot = lambda x, y: (cx + x * ca - y * sa, cy + x * sa + y * ca)
    return [[rot(*pt) for pt in seg] for seg in local]


def voice():
    heights = [0.36, 0.62, 0.88, 0.50, 1.00, 0.70, 0.92, 0.44]
    n = len(heights)
    return [sparkle((i - (n - 1) / 2) * 1.0, 0, 0.40, h * 2.6, pinch=0.26) for i, h in enumerate(heights)]


def orbit():
    stars = []
    n = 8
    for i in range(n):
        # Start just after 12 o'clock and go clockwise; each star a little larger.
        a = -math.pi / 2 + (i + 0.5) * 2 * math.pi / n
        r = 1.0
        size = 0.12 + 0.20 * (i / (n - 1))
        stars.append(sparkle(r * math.cos(a), r * math.sin(a), size * 0.8, size * 1.25, pinch=0.26))
    return stars


VARIANTS = {"voice": voice, "orbit": orbit}


def path_data(stars, viewport, box):
    xs = [pt[0] for s in stars for seg in s for pt in seg]
    ys = [pt[1] for s in stars for seg in s for pt in seg]
    x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    s = box / max(x1 - x0, y1 - y0)
    dx = (viewport - (x1 - x0) * s) / 2 - x0 * s
    dy = (viewport - (y1 - y0) * s) / 2 - y0 * s
    f = lambda p: f"{p[0] * s + dx:.2f},{p[1] * s + dy:.2f}"
    out = []
    for st in stars:
        out.append("M" + f(st[0][0]))
        for a, c1, c2, b in st:
            out.append("C" + f(c1) + " " + f(c2) + " " + f(b))
        out.append("Z")
    return "".join(out)


def raster(d, viewport, size, fg, bg, circle=True):
    k = size / viewport
    mask = Image.new("L", (size, size), 0)
    for sub in re.findall(r"M[^Z]*Z", d):
        pts, cur = [], None
        for cmd, args in re.findall(r"([MC])([^MCZ]*)", sub):
            q = [tuple(map(float, t.split(","))) for t in re.findall(r"-?[\d.]+,-?[\d.]+", args)]
            if cmd == "M":
                cur = q[0]
                pts.append(cur)
            else:
                p0, (p1, p2, p3) = cur, q
                for t in np.linspace(0, 1, 40)[1:]:
                    pts.append(tuple((1 - t) ** 3 * np.array(p0) + 3 * (1 - t) ** 2 * t * np.array(p1)
                                     + 3 * (1 - t) * t ** 2 * np.array(p2) + t ** 3 * np.array(p3)))
                cur = p3
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).polygon([(x * k, y * k) for x, y in pts], fill=255)
        mask = ImageChops.lighter(mask, m)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if circle:
        c = Image.new("L", (size, size), 0)
        ImageDraw.Draw(c).ellipse((0, 0, size - 1, size - 1), fill=255)
        img.paste(Image.new("RGBA", (size, size), bg), mask=c)
    else:
        img.paste(Image.new("RGBA", (size, size), bg))
    img.paste(Image.new("RGBA", (size, size), fg), mask=mask)
    return img


def sheet(out):
    """Both options: large, app icon light/dark, and small sizes."""
    W = 1500
    img = Image.new("RGBA", (W, 1120), "#e4e4e4")
    draw = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("arial.ttf", 34)
    except OSError:
        font = ImageFont.load_default()
    for row, (name, make) in enumerate(VARIANTS.items()):
        y = 30 + row * 550
        d = path_data(make(), 108, 62)
        draw.text((30, y), ("A  Voice" if name == "voice" else "B  Orbit"), fill="black", font=font)
        big = raster(path_data(make(), 108, 86), 108, 1000, "black", "white", circle=False).resize((470, 470), Image.LANCZOS)
        img.paste(big, (30, y + 50))
        for j, (fg, bg) in enumerate([("black", "white"), ("white", "black"), ("#cfd8ff", "#1b1b22")]):
            ic = raster(d, 108, 1024, fg, bg).resize((260, 260), Image.LANCZOS)
            img.paste(ic, (540 + j * 290, y + 60), ic)
        for j, px in enumerate([96, 48]):
            ic = raster(d, 108, px * 4, "black", "white").resize((px, px), Image.LANCZOS)
            img.paste(ic, (540 + j * 130, y + 370), ic)
        sm = raster(path_data(make(), 24, 21), 24, 96, "white", "#333333").resize((24, 24), Image.LANCZOS)
        img.paste(sm, (820, y + 400), sm)
    img.save(out)


if __name__ == "__main__":
    sheet(sys.argv[1])
    print("ok")
