#!/usr/bin/env python3
"""Spectra's mark, drawn once and written into every drawable that shows it.

    python3 tools/launcher-icon.py

The mark ("crest and steps", the owner's pick on 2026-10-16): half a period of
a smooth analog sine on top, then the staircase of the NES 2A03's triangle
channel below it. The analog half has round ends; the chip half has square
corners, the way the chip steps. It is drawn on the sites' 24-unit icon grid
with their 2-unit line, then placed into:

  ic_launcher_background.xml   the flat violet plate (Palette.accentFill)
  ic_launcher_foreground.xml   the mark in white, on the 108 x 108 adaptive canvas
  ic_launcher_monochrome.xml   the same mark for themed icons, which Android tints
  ic_stat_spectra.xml          the status-bar icon, 24 x 24

The real chip takes 16 steps per half wave, which blurs at icon size, so the
staircase is drawn with 7. `./gradlew :desktop:appIcon` renders the Windows icon
from these files, and web/nebula3d/ops/vd2svg.py the one on the store's Apps page.
"""
import math
import os

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")

LINE = 2.0                  # the sites' line width on the 24-unit grid
AMPLITUDE = 7.0             # crest from y 12 up to 5, trough down to 19
CREST = (2.0, 12.0)         # x range of the analog half
STAIRS = (12.0, 22.0)       # x range of the chip half
TREADS = 7
INK = (1.0, 4.0, 23.0, 20.0)  # the mark's ink, line included; centred on (12, 12)

PLATE = "#FF6A45FF"
WHITE = "#FFFFFFFF"

# On the launcher the mark's longer side is 14 of the 24 units a 72 dp plate
# spans, the proportion the concept sheet showed: 42 dp across, well inside the
# 66 dp circle every launcher mask leaves visible.
LAUNCHER_SCALE = 14 / 24 * 72 / (INK[2] - INK[0])


def crest_segments(n=4):
    """Half a sine period as cubic Béziers, Hermite-matched at every join."""
    x0, x1 = CREST
    k = math.pi / (x1 - x0)

    def y(x):
        return 12 - AMPLITUDE * math.sin(k * (x - x0))

    def dy(x):
        return -AMPLITUDE * k * math.cos(k * (x - x0))

    segments = []
    for i in range(n):
        a = x0 + (x1 - x0) * i / n
        b = x0 + (x1 - x0) * (i + 1) / n
        h = (b - a) / 3
        segments.append(((a, y(a)), (a + h, y(a) + h * dy(a)), (b - h, y(b) - h * dy(b)), (b, y(b))))
    return segments


def stair_points():
    """Down the staircase and back up: deepest tread in the middle."""
    x0, x1 = STAIRS
    width = (x1 - x0) / TREADS
    levels = TREADS // 2 + 1
    points = [(x0, 12.0)]
    x = x0
    for i in range(TREADS):
        y = 12 + AMPLITUDE * (levels - abs(i - TREADS // 2)) / levels
        points.append((x, y))
        x += width
        points.append((x, y))
    points.append((x1, 12.0))
    return points


def num(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def mark(cx, cy, scale, color):
    """The two paths of the mark, centred on (cx, cy) at `scale` units per grid unit."""
    def at(p):
        return f"{num(cx + (p[0] - 12) * scale)},{num(cy + (p[1] - 12) * scale)}"

    segments = crest_segments()
    crest = f"M{at(segments[0][0])}" + "".join(f" C{at(c1)} {at(c2)} {at(e)}" for _, c1, c2, e in segments)
    points = stair_points()
    stairs = f"M{at(points[0])}" + "".join(f" L{at(p)}" for p in points[1:])
    width = num(LINE * scale)
    return (
        "    <!-- The analog half: round ends. -->\n"
        "    <path\n"
        f'        android:pathData="{crest}"\n'
        f'        android:strokeColor="{color}"\n'
        f'        android:strokeWidth="{width}"\n'
        '        android:strokeLineCap="round"\n'
        '        android:strokeLineJoin="round" />\n'
        "    <!-- The 2A03 half: square corners. -->\n"
        "    <path\n"
        f'        android:pathData="{stairs}"\n'
        f'        android:strokeColor="{color}"\n'
        f'        android:strokeWidth="{width}"\n'
        '        android:strokeLineCap="square"\n'
        '        android:strokeLineJoin="miter"\n'
        '        android:strokeMiterLimit="4" />\n'
    )


def vector(size, viewport, note, body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {note} Written by tools/launcher-icon.py; edit that, not this. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{viewport}"\n'
        f'    android:viewportHeight="{viewport}">\n'
        f"{body}"
        "</vector>\n"
    )


FILES = {
    "ic_launcher_background.xml": vector(
        108, 108, "The plate: one flat violet, the app's accent fill.",
        f'    <path\n        android:pathData="M0,0h108v108h-108z"\n        android:fillColor="{PLATE}" />\n',
    ),
    "ic_launcher_foreground.xml": vector(
        108, 108,
        "Adaptive-icon foregrounds are 108 x 108 with the middle 72 visible; the mark sits in the middle.",
        mark(54, 54, LAUNCHER_SCALE, WHITE),
    ),
    "ic_launcher_monochrome.xml": vector(
        108, 108, "Themed icons tint this one colour, so it is the foreground's mark alone.",
        mark(54, 54, LAUNCHER_SCALE, WHITE),
    ),
    "ic_stat_spectra.xml": vector(
        24, 24, "Status-bar icons are masked to one colour, so shape is all that matters.",
        mark(12, 12, 1.0, WHITE),
    ),
}

if __name__ == "__main__":
    for name, text in FILES.items():
        path = os.path.normpath(os.path.join(RES, name))
        with open(path, "w") as f:
            f.write(text)
        print("wrote", os.path.relpath(path))
