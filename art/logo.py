"""Ausence logo: seven sparkles riding a sound wave.

Seven four-pointed sparkles sit on one cycle of a sine wave; their heights follow it too, so the
mark reads as sound drawn in points of light. Run to write SVGs:

    python logo.py art/
"""
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw


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


def wave7():
    """The mark: seven sparkles on a sine wave, heights following it."""
    n = 7
    ys = [0.45 * math.sin(i / (n - 1) * 2 * math.pi) for i in range(n)]
    hs = [0.55 + 0.45 * abs(math.cos(i / (n - 1) * math.pi)) for i in range(n)]
    return [sparkle((i - (n - 1) / 2) * 1.0, ys[i], 0.36, h * 1.7, pinch=0.26)
            for i, h in enumerate(hs)]


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



if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    d = path_data(wave7(), 512, 440)
    for name, colour in [("ausence-logo.svg", "#000"), ("ausence-logo-white.svg", "#fff")]:
        with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">\n'
                f'  <title>Ausence</title>\n  <path fill="{colour}" d="{d}"/>\n</svg>\n')
    print("wrote SVGs to", out)
