#!/usr/bin/env python3
"""Copies the shared libre-apps themes into the app (assets/themes/).

The app never reads ../libre-apps-themes at build time: run this after the themes change and
commit the result. Portrait backgrounds become WebP (full size plus a small settings preview).
Needs Pillow.
"""
import json
import pathlib
import shutil
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / "libre-apps-themes"
OUT = ROOT / "app/src/main/assets/themes"

(OUT / "backgrounds").mkdir(parents=True, exist_ok=True)
shutil.copy(SRC / "themes.json", OUT / "themes.json")
for theme in json.loads((SRC / "themes.json").read_text())["themes"]:
    tid = theme["id"]
    png = SRC / "backgrounds/png" / f"{tid}-portrait.png"
    if not png.exists():
        sys.exit(f"missing {png}")
    im = Image.open(png).convert("RGB")
    im.save(OUT / "backgrounds" / f"{tid}-portrait.webp", quality=80, method=6)
    im.resize((108, 240), Image.LANCZOS).save(OUT / "backgrounds" / f"{tid}-thumb.webp", quality=80)
    print("ok", tid)
