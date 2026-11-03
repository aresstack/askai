#!/usr/bin/env python3
"""Derives the setup wizard's resource copies from the unedited originals (see docs/images/setup-wizard).

The originals are never changed: each copy is scaled to 480 x 640 px and gets transparent rounded corners.
"""
import glob
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "docs", "images", "setup-wizard", "originals")
TARGET = os.path.join(ROOT, "askai-app", "src", "main", "resources", "com", "aresstack", "askai",
                      "ui", "setup", "wizard")
WIDTH, HEIGHT, RADIUS, SUPERSAMPLE = 480, 640, 36, 4


CUTOUT_HEIGHT = 900


def derive_cutout(path):
    """Merlin cut-outs (transparent background): only scaled down, no corners - they stand free in the step."""
    image = Image.open(path).convert("RGBA")
    width = round(image.width * CUTOUT_HEIGHT / image.height)
    image.resize((width, CUTOUT_HEIGHT), Image.LANCZOS).save(os.path.join(TARGET, os.path.basename(path)),
                                                             optimize=True)


def derive(path):
    if os.path.basename(path).startswith("merlin-"):
        derive_cutout(path)
        return
    image = Image.open(path).convert("RGBA").resize((WIDTH, HEIGHT), Image.LANCZOS)
    mask = Image.new("L", (WIDTH * SUPERSAMPLE, HEIGHT * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, WIDTH * SUPERSAMPLE - 1, HEIGHT * SUPERSAMPLE - 1),
                                           radius=RADIUS * SUPERSAMPLE, fill=255)
    image.putalpha(mask.resize((WIDTH, HEIGHT), Image.LANCZOS))
    image.save(os.path.join(TARGET, os.path.basename(path)), optimize=True)


def derive_v3():
    """Third set (docs/images/setup-wizard/originals/v3/wizard): cut-outs, only scaled down when taller than 900 px."""
    for path in sorted(glob.glob(os.path.join(SOURCE, "v3", "wizard", "*.png"))):
        image = Image.open(path).convert("RGBA")
        if image.height > CUTOUT_HEIGHT:
            image = image.resize((round(image.width * CUTOUT_HEIGHT / image.height), CUTOUT_HEIGHT), Image.LANCZOS)
        image.save(os.path.join(TARGET, "v3-" + os.path.basename(path)), optimize=True)
        print("derived v3-" + os.path.basename(path))


if __name__ == "__main__":
    os.makedirs(TARGET, exist_ok=True)
    for original in sorted(glob.glob(os.path.join(SOURCE, "*.png"))):
        derive(original)
        print("derived", os.path.basename(original))
    derive_v3()
