#!/usr/bin/env python3
"""Analyze subtitle label contrast in repro renders (1.7.3.1).

Measures in the FIRST subtitle card (服务器) of each PNG:
- card base luminance (mode color of card area)
- label text core luminance (darkest pixels = dark-label core; near-0xB8C8D8 for light label)
- WCAG contrast ratio between label core and card base
"""
import sys
from pathlib import Path

from PIL import Image


def srgb_to_linear(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rel_luminance(rgb):
    r, g, b = rgb
    return 0.2126 * srgb_to_linear(r) + 0.7152 * srgb_to_linear(g) + 0.0722 * srgb_to_linear(b)


def contrast(a, b):
    la, lb = rel_luminance(a), rel_luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def lum(px):
    return 0.299 * px[0] + 0.587 * px[1] + 0.114 * px[2]


def analyze(path, sub_y, card_x, card_w, label_dx=26, label_top_dy=14, label_bot_dy=44):
    img = Image.open(path).convert("RGB")
    # label text region (inside first card, left-aligned label)
    lx0 = card_x + label_dx - 6
    lx1 = int(card_x + min(label_dx + 130, card_w - 10))
    ly0, ly1 = sub_y + label_top_dy, sub_y + label_bot_dy
    # card base = mode color of card area EXCLUDING label text rows (use right half + bottom strip)
    base_pixels = []
    for y in range(sub_y + 4, sub_y + 80):
        for x in range(int(card_x + card_w * 0.55), int(card_x + card_w - 6)):
            base_pixels.append(img.getpixel((x, y)))
    base = max(set(base_pixels), key=base_pixels.count)
    base_lum = lum(base)
    # label core: darkest 2% of label-region pixels (dark label) OR brightest 2% (light label)
    label_pixels = [img.getpixel((x, y)) for y in range(ly0, ly1) for x in range(lx0, lx1)]
    label_pixels.sort(key=lum)
    n = len(label_pixels)
    darkest = label_pixels[: max(1, n // 50)]
    brightest = label_pixels[-max(1, n // 50):]
    dark_core = tuple(sum(c[i] for c in darkest) // len(darkest) for i in range(3))
    bright_core = tuple(sum(c[i] for c in brightest) // len(brightest) for i in range(3))
    # pick whichever is farther from base luminance
    if abs(lum(dark_core) - base_lum) >= abs(lum(bright_core) - base_lum):
        core, kind = dark_core, "dark-core"
    else:
        core, kind = bright_core, "bright-core"
    return {
        "base": base, "base_lum": base_lum,
        "core": core, "kind": kind,
        "contrast": contrast(core, base),
        "judged_bright": base_lum >= 150,
    }


CASES = [
    # name, sub_y, card_x, card_w  (第一张副标题卡：服务器)
    ("online", 240, 40, (1350 - 80 - 32) / 3),
    ("leader", 240, 40, (1200 - 80 - 32) / 3),
    ("info", 328, 24, (900 - 48 - 28) / 3),
]


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/repro1731")
    print(f"{'file':<38} {'baseL':>6} {'labelL':>7} {'kind':>11} {'contrast':>8}  judge")
    print("-" * 84)
    for f in sorted(root.glob("*.png")):
        kind = next((c for c in CASES if c[0] in f.name), None)
        if not kind:
            continue
        _, sub_y, card_x, card_w = kind
        r = analyze(f, sub_y, card_x, card_w)
        print(f"{f.name:<38} {r['base_lum']:>6.0f} {lum(r['core']):>7.0f} {r['kind']:>11} "
              f"{r['contrast']:>7.2f}:1  {'BRIGHT->dark-label' if r['judged_bright'] else 'dark->light-label'}")


if __name__ == "__main__":
    main()
