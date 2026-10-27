"""Tiny pixel-art helper library shared by all generators.

Artwork is held as numpy int8 arrays of palette indices:
    -1 = transparent, 0..3 = palette index (0 lightest, 3 darkest).
Text grids use '.' (or ' ') for transparent and '0'..'3' for indices. Other
characters can be mapped with a dict (e.g. semantic sprite templates).
"""
import json
import os
import struct
import zlib

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "assets")
GFX = os.path.join(ASSETS, "gfx")
PREVIEWS = os.path.join(ROOT, "art", "previews")

T = -1  # transparent


def grid(text, mapping=None, width=None):
    """Parse a multi-line text grid into an int8 array."""
    lines = [ln.strip() for ln in text.strip("\n").splitlines()]
    lines = [ln for ln in lines if ln]
    w = width or max(len(ln) for ln in lines)
    arr = np.full((len(lines), w), T, dtype=np.int8)
    for y, ln in enumerate(lines):
        assert len(ln) == w, f"row {y} has width {len(ln)} != {w}: {ln!r}"
        for x, ch in enumerate(ln):
            if mapping and ch in mapping:
                arr[y, x] = mapping[ch]
            elif ch in "0123":
                arr[y, x] = int(ch)
            elif ch in ". ":
                arr[y, x] = T
            else:
                raise ValueError(f"unknown pixel char {ch!r}")
    return arr


def chargrid(text):
    """Parse a text grid into a 2D numpy array of single characters."""
    lines = [ln.strip() for ln in text.strip("\n").splitlines()]
    lines = [ln for ln in lines if ln]
    w = max(len(ln) for ln in lines)
    for y, ln in enumerate(lines):
        assert len(ln) == w, f"row {y} width {len(ln)} != {w}: {ln!r}"
    return np.array([list(ln) for ln in lines])


def blank(h, w, fill=T):
    return np.full((h, w), fill, dtype=np.int8)


def paste(dst, src, x, y, transparent=True):
    """Paste src onto dst at (x, y) clipping; transparent pixels skipped."""
    h, w = src.shape
    H, W = dst.shape
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(W, x + w), min(H, y + h)
    if x0 >= x1 or y0 >= y1:
        return dst
    s = src[y0 - y:y1 - y, x0 - x:x1 - x]
    d = dst[y0:y1, x0:x1]
    if transparent:
        m = s != T
        d[m] = s[m]
    else:
        d[:] = s
    return dst


def fliph(a):
    return a[:, ::-1].copy()


def flipv(a):
    return a[::-1, :].copy()


def rot90(a, k=1):
    """Rotate counter-clockwise k times."""
    return np.rot90(a, k).copy()


def recolor(a, mapping):
    out = a.copy()
    for s, d in mapping.items():
        out[a == s] = d
    return out


def outline(a, color=3, diagonal=False):
    """Add an outline of `color` around opaque pixels (grows the shape)."""
    m = a != T
    g = np.zeros_like(m)
    g[1:, :] |= m[:-1, :]
    g[:-1, :] |= m[1:, :]
    g[:, 1:] |= m[:, :-1]
    g[:, :-1] |= m[:, 1:]
    if diagonal:
        g[1:, 1:] |= m[:-1, :-1]
        g[1:, :-1] |= m[:-1, 1:]
        g[:-1, 1:] |= m[1:, :-1]
        g[:-1, :-1] |= m[1:, 1:]
    out = a.copy()
    out[g & ~m] = color
    return out


def to_rgba(a, palette, alpha=255):
    """Render an index array to an RGBA Image using a 4-colour palette."""
    h, w = a.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    for i, c in enumerate(palette):
        m = a == i
        rgba[m, 0], rgba[m, 1], rgba[m, 2] = c
        rgba[m, 3] = alpha
    return Image.fromarray(rgba, "RGBA")


def to_rgba16(a, palette16, alpha=255):
    """Render an array of palette slots (1..15) to an RGBA Image using a palettes.Palette16."""
    h, w = a.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    for i, c in enumerate(palette16.slots):
        if c is None:
            assert not (a == i).any(), f"{palette16.name}: slot {i} used but undefined"
            continue
        m = a == i
        rgba[m, 0], rgba[m, 1], rgba[m, 2] = c
        rgba[m, 3] = alpha
    return Image.fromarray(rgba, "RGBA")


def _chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def save_png(img, path):
    """Write RGBA PNG with stored (uncompressed) deflate blocks so the bytes never depend on a zlib build."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img = img.convert("RGBA")
    w, h = img.size
    raw = img.tobytes()
    stride = w * 4
    scan = b"".join(b"\x00" + raw[y * stride:(y + 1) * stride] for y in range(h))
    blocks = [b"\x78\x01"]
    for i in range(0, max(len(scan), 1), 65535):
        part = scan[i:i + 65535]
        final = 1 if i + 65535 >= len(scan) else 0
        blocks.append(struct.pack("<BHH", final, len(part), len(part) ^ 0xFFFF) + part)
    blocks.append(struct.pack(">I", zlib.adler32(scan) & 0xFFFFFFFF))
    png = (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + _chunk(b"IDAT", b"".join(blocks)) + _chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def save_json(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False, sort_keys=False)
        f.write("\n")


def scale(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def colours_used(img):
    """Number of distinct opaque colours in an RGBA image."""
    a = np.asarray(img.convert("RGBA")).reshape(-1, 4)
    a = a[a[:, 3] > 0]
    return len({tuple(p[:3]) for p in a})


# ---------------------------------------------------------------- drawing
def rect(a, x, y, w, h, c):
    a[max(0, y):y + h, max(0, x):x + w] = c
    return a


def hline(a, x0, x1, y, c):
    a[y, x0:x1 + 1] = c


def vline(a, x, y0, y1, c):
    a[y0:y1 + 1, x] = c


def disc_mask(h, w, cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    Y, X = np.mgrid[0:h, 0:w]
    return ((X + 0.5 - cx) / rx) ** 2 + ((Y + 0.5 - cy) / ry) ** 2 <= 1.0
