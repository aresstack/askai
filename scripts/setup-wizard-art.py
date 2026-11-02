#!/usr/bin/env python3
"""Derives the setup wizard's resource copies from the unedited originals (see docs/images/setup-wizard).

The originals are never changed: each copy is scaled to 480 x 640 px and gets transparent rounded corners.
"""
import glob
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "docs", "images", "setup-wizard", "originals")
TARGET = os.path.join(ROOT, "askai-app", "src", "main", "resources", "com", "aresstack", "askai", "java8",
                      "ui", "setup", "wizard")
WIDTH, HEIGHT, RADIUS, SUPERSAMPLE = 480, 640, 36, 4


def derive(path):
    image = Image.open(path).convert("RGBA").resize((WIDTH, HEIGHT), Image.LANCZOS)
    mask = Image.new("L", (WIDTH * SUPERSAMPLE, HEIGHT * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, WIDTH * SUPERSAMPLE - 1, HEIGHT * SUPERSAMPLE - 1),
                                           radius=RADIUS * SUPERSAMPLE, fill=255)
    image.putalpha(mask.resize((WIDTH, HEIGHT), Image.LANCZOS))
    image.save(os.path.join(TARGET, os.path.basename(path)), optimize=True)


if __name__ == "__main__":
    os.makedirs(TARGET, exist_ok=True)
    for original in sorted(glob.glob(os.path.join(SOURCE, "*.png"))):
        derive(original)
        print("derived", os.path.basename(original))
