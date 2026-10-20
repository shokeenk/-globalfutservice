"""Makes the site icons from the brand mark: the red GLOBAL FUT SERVICES ring on white.

    python frontend/scripts/make-icons.py        (needs Pillow: pip install Pillow)

Reads frontend/public/brand/gfs-mark.png (the red mark on transparent) and writes, into
frontend/public:

    favicon.ico          16, 32 and 48 px inside
    favicon-48.png, favicon-96.png, favicon-192.png
    apple-touch-icon.png 180 px
    icon-512.png

Every icon is square, solid white (#FFFFFF), with the mark centred and about 8% padding
on each side, so the ring is not cut off where Google or a phone shows icons in a circle.

The mark is drawn in one red (#E11D2E) using only the source's shape (its alpha). The
source's semi-transparent edge pixels carry dark colour from whatever it was cut from, and
laid on white as they are they would leave a grey fringe; recolouring them means nothing
dark can reach an icon. Each size is drawn at 1024 px and scaled down once, so small
sizes stay smooth.
"""
from pathlib import Path

from PIL import Image

FRONTEND = Path(__file__).resolve().parent.parent
SOURCE = FRONTEND / "public" / "brand" / "gfs-mark.png"
OUT = FRONTEND / "public"

RED = (0xE1, 0x1D, 0x2E)
WHITE = (0xFF, 0xFF, 0xFF)
PADDING = 0.08
MASTER = 1024

PNGS = {
    "favicon-48.png": 48,
    "favicon-96.png": 96,
    "favicon-192.png": 192,
    "apple-touch-icon.png": 180,
    "icon-512.png": 512,
}
ICO_SIZES = [16, 32, 48]


def master() -> Image.Image:
    """The mark in red on white, MASTER px square, centred with PADDING on every side."""
    mark = Image.open(SOURCE).convert("RGBA")
    alpha = mark.getchannel("A")
    box = alpha.getbbox()
    if box is None:
        raise SystemExit(f"{SOURCE} has nothing visible in it")
    shape = alpha.crop(box)

    inner = round(MASTER * (1 - 2 * PADDING))
    scale = inner / max(shape.size)
    size = (max(1, round(shape.width * scale)), max(1, round(shape.height * scale)))
    shape = shape.resize(size, Image.Resampling.LANCZOS)

    canvas = Image.new("RGB", (MASTER, MASTER), WHITE)
    red = Image.new("RGB", size, RED)
    canvas.paste(red, ((MASTER - size[0]) // 2, (MASTER - size[1]) // 2), shape)
    return canvas


def main() -> None:
    big = master()
    for name, px in PNGS.items():
        big.resize((px, px), Image.Resampling.LANCZOS).save(OUT / name, optimize=True)
        print(f"wrote {name} ({px}x{px})")
    big.resize((256, 256), Image.Resampling.LANCZOS).save(
        OUT / "favicon.ico", sizes=[(s, s) for s in ICO_SIZES])
    print(f"wrote favicon.ico ({', '.join(str(s) for s in ICO_SIZES)} px)")


if __name__ == "__main__":
    main()
