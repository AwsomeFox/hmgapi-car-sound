#!/usr/bin/env python3
"""Car Sound's launcher icons (99x99, transparent): five rounded equalizer bars in a green
gradient, a deeper one for night, and grey for the disabled tiles (as other Gen5W mod tiles).
    python3 make-icons.py <out dir>
"""
import sys
from PIL import Image, ImageDraw

S, K = 99, 4                      # size, supersampling
HEIGHTS = [0.46, 0.78, 0.58, 0.36, 0.52]   # an EQ curve: bass up, a dip, presence


def bars(mask_draw):
    n, gap, w = len(HEIGHTS), 7 * K, 9 * K
    total = n * w + (n - 1) * gap
    x0, base, top = (S * K - total) // 2, 76 * K, 22 * K
    for i, h in enumerate(HEIGHTS):
        x = x0 + i * (w + gap)
        y = base - int((base - top) * h)
        mask_draw.rounded_rectangle([x, y, x + w, base], radius=w // 2, fill=255)


def gradient(c1, c2):
    g = Image.new("RGBA", (S * K, S * K))
    px = g.load()
    for y in range(S * K):
        for x in range(S * K):
            t = (x + (S * K - y)) / (2 * S * K)
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)
    return g


def icon(fill):
    mask = Image.new("L", (S * K, S * K), 0)
    bars(ImageDraw.Draw(mask))
    out = Image.new("RGBA", (S * K, S * K), (0, 0, 0, 0))
    out.paste(fill, (0, 0), mask)
    return out.resize((S, S), Image.LANCZOS)


def flat(rgb, alpha):
    return Image.new("RGBA", (S * K, S * K), rgb + (alpha,))


d = sys.argv[1] if len(sys.argv) > 1 else "."
icon(gradient((52, 211, 153), (20, 160, 120))).save(d + "/icon.png")
icon(gradient((38, 170, 125), (16, 120, 92))).save(d + "/icon_night.png")
icon(flat((98, 121, 127), 51)).save(d + "/icon_disabled.png")
icon(flat((98, 121, 127), 102)).save(d + "/icon_disabled_night.png")
print("icons in", d)
