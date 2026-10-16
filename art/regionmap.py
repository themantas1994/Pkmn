"""Region map of Tessera -> assets/gfx/ui/region_map.png (256x224)
plus assets/gfx/ui/region_map.json (town names, cell + pixel coordinates).

The map is a 32x28 grid of 8x8 cells (GSC style). Terrain is laid out with
simple shapes in cell coordinates, then routes (straight segments between
towns) and towns are painted on top. Change TOWNS / ROUTES to edit the map;
docs/ART_STYLE.md describes the layout for the world team.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from palettes import _c  # noqa: E402

CW, CH = 32, 28
SEA, LAND, MOUNT, FOREST, LAKE, SAND, ROUTE, SEAROUTE = range(8)

# id: (name, cell x, cell y, kind)  kind: town | city | league
TOWNS = {
    "lumen": ("Lumen Town", 12, 23, "town"),          # start
    "quartzford": ("Quartzford", 12, 19, "town"),
    "mica": ("Mica City", 5, 19, "city"),
    "feldspar": ("Feldspar Town", 12, 16, "town"),
    "prism": ("Prism City", 12, 11, "city"),
    "basalt": ("Basalt Town", 4, 11, "town"),
    "garnet": ("Garnet Port", 5, 15, "city"),
    "opaline": ("Opaline City", 26, 14, "city"),
    "jasper": ("Jasper City", 25, 9, "city"),
    "selenite": ("Selenite Village", 19, 9, "town"),
    "cobalt": ("Cobalt City", 16, 6, "city"),
    "obsidian": ("Obsidian Town", 12, 6, "town"),
    "beryl": ("Beryl Isle", 26, 24, "town"),
    "zenith": ("Zenith Plateau", 16, 2, "league"),
}

# routes as polylines of cell coordinates (land routes) ; sea routes separate
ROUTES = [
    ("route_1", ["lumen", "quartzford"]),
    ("route_2", ["quartzford", "mica"]),
    ("route_3", ["quartzford", "feldspar"]),
    ("route_4", ["mica", (5, 16), "garnet"]),
    ("route_5", ["garnet", (4, 15), "basalt"]),
    ("route_6", ["feldspar", "prism"]),
    ("route_7", ["basalt", "prism"]),
    ("route_8", ["feldspar", (25, 16), (26, 16), "opaline"]),
    ("route_9", ["opaline", (26, 9), "jasper"]),
    ("route_10", ["jasper", "selenite"]),
    ("route_11", ["selenite", (19, 6), "cobalt"]),
    ("route_12", ["prism", "obsidian"]),
    ("route_13", ["obsidian", "cobalt"]),
    ("victory_road", ["cobalt", "zenith"]),
]
SEA_ROUTES = [("route_14", ["lumen", (12, 24), "beryl"])]


def cell_point(p):
    if isinstance(p, str):
        _, x, y, _ = TOWNS[p]
        return x, y
    return p


def poly_mask(pts):
    Y, X = np.mgrid[0:CH, 0:CW]
    XC, YC = X + 0.5, Y + 0.5
    inside = np.zeros((CH, CW), bool)
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        cond = (y1 > YC) != (y2 > YC)
        xint = (x2 - x1) * (YC - y1) / (y2 - y1 + 1e-9) + x1
        inside ^= cond & (XC < xint)
    return inside


def ell(cx, cy, rx, ry):
    Y, X = np.mgrid[0:CH, 0:CW]
    return ((X + 0.5 - cx) / rx) ** 2 + ((Y + 0.5 - cy) / ry) ** 2 <= 1


def terrain():
    t = np.full((CH, CW), SEA, np.int8)
    land = poly_mask([(15, 0.6), (21, 2.5), (27, 7), (29.5, 12), (29, 18), (26.5, 20.5),
                      (21, 21), (15, 25.5), (10, 25.5), (5.5, 22.5), (2, 17), (2.5, 10),
                      (6, 5), (10, 2.5)])
    t[land] = LAND
    t[ell(26, 24.5, 2.6, 1.8)] = LAND             # Beryl Isle
    t[ell(2.5, 25, 1.5, 1.0)] = LAND              # islet
    mount = poly_mask([(9, 4), (15, 0.6), (22, 3), (24.5, 7.5), (21, 8.2), (14, 8.4), (10, 7.6)])
    t[mount & land] = MOUNT
    t[ell(8.5, 17.5, 3.2, 2.2) & land] = FOREST
    t[ell(6.5, 7.5, 2.6, 2.0) & land] = FOREST
    t[ell(22, 18.5, 2.5, 1.4) & land] = FOREST
    t[ell(19, 13, 3.6, 2.3)] = LAKE
    # sandy south-east coast
    coast = np.zeros_like(land)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        coast |= np.roll(t == SEA, (dy, dx), (0, 1))
    Y, X = np.mgrid[0:CH, 0:CW]
    t[(t == LAND) & coast & ((Y + X) > 38)] = SAND
    return t


def paint_routes(t):
    for routes, val in ((ROUTES, ROUTE), (SEA_ROUTES, SEAROUTE)):
        for _, pts in routes:
            pts = [cell_point(p) for p in pts]
            for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
                assert x1 == x2 or y1 == y2, f"routes must be straight: {(x1, y1)}->{(x2, y2)}"
                for x in range(min(x1, x2), max(x1, x2) + 1):
                    for y in range(min(y1, y2), max(y1, y2) + 1):
                        if val == SEAROUTE and t[y, x] != SEA:
                            continue
                        t[y, x] = val
    return t


COL = {
    "sea": _c("4880D8"), "sea_l": _c("70A8F0"), "land": _c("98D068"), "land_d": _c("68A848"),
    "mount": _c("C89858"), "mount_d": _c("805028"), "snow": _c("F8F8F8"), "forest": _c("387838"),
    "forest_l": _c("58A048"), "sand": _c("F0D898"), "route": _c("F8E8B0"), "edge": _c("183048"),
    "town": _c("E03828"), "city": _c("F8A020"), "league": _c("8850D8"), "white": _c("F8F8F8"),
    "lake": _c("4890E8"),
}


def render():
    t = paint_routes(terrain())
    img = np.zeros((CH * 8, CW * 8, 3), np.uint8)
    rng = np.random.RandomState(7)
    for cy in range(CH):
        for cx in range(CW):
            v = t[cy, cx]
            blk = img[cy * 8:cy * 8 + 8, cx * 8:cx * 8 + 8]
            if v in (SEA, SEAROUTE):
                blk[:] = COL["sea"]
                if (cx * 3 + cy * 5) % 7 == 0:
                    blk[3, 2:5] = COL["sea_l"]
                if v == SEAROUTE:
                    blk[3:5, 3:5] = COL["white"]
            elif v == LAKE:
                blk[:] = COL["lake"]
                if (cx + cy) % 3 == 0:
                    blk[4, 2:5] = COL["sea_l"]
            else:
                blk[:] = COL["sand"] if v == SAND else COL["land"]
                if v in (LAND,) and (cx * 7 + cy * 3) % 5 == 0:
                    blk[5, 2] = blk[5, 4] = blk[6, 3] = COL["land_d"]
                if v == MOUNT:
                    for y in range(2, 8):
                        half = (y - 1) // 1
                        lo, hi = max(0, 4 - half), min(8, 4 + half)
                        blk[y, lo:4] = COL["mount"]
                        blk[y, 4:hi] = COL["mount_d"]
                    blk[1:3, 3:5] = COL["snow"] if cy < 5 else COL["mount"]
                if v == FOREST:
                    for (ox, oy) in ((1, 1), (4, 3)):
                        blk[oy:oy + 4, ox:ox + 3] = COL["forest"]
                        blk[oy, ox + 1] = COL["forest_l"]
                        blk[oy + 1, ox] = COL["forest_l"]
                if v == ROUTE:
                    def isr(x, y):
                        return 0 <= x < CW and 0 <= y < CH and (t[y, x] == ROUTE or _town_at(x, y))
                    blk[3:5, 3:5] = COL["route"]
                    if isr(cx - 1, cy):
                        blk[3:5, 0:4] = COL["route"]
                    if isr(cx + 1, cy):
                        blk[3:5, 4:8] = COL["route"]
                    if isr(cx, cy - 1):
                        blk[0:4, 3:5] = COL["route"]
                    if isr(cx, cy + 1):
                        blk[4:8, 3:5] = COL["route"]
    # coastline: dark pixel on land next to sea
    land = np.kron((t != SEA) & (t != SEAROUTE), np.ones((8, 8), bool))
    edge = land & ~(np.roll(land, 1, 0) & np.roll(land, -1, 0) & np.roll(land, 1, 1) & np.roll(land, -1, 1))
    img[edge] = COL["edge"]
    lake = np.kron(t == LAKE, np.ones((8, 8), bool))
    ledge = ~lake & (np.roll(lake, 1, 0) | np.roll(lake, -1, 0) | np.roll(lake, 1, 1) | np.roll(lake, -1, 1))
    img[ledge] = COL["edge"]
    # towns
    for tid, (name, x, y, kind) in TOWNS.items():
        px, py = x * 8, y * 8
        img[py + 1:py + 7, px + 1:px + 7] = COL["edge"]
        img[py + 2:py + 6, px + 2:px + 6] = COL[kind]
        img[py + 2, px + 2] = COL["white"]
    # map frame
    img[0, :] = img[-1, :] = COL["edge"]
    img[:, 0] = img[:, -1] = COL["edge"]
    # compass in the north-west sea
    cxp, cyp = 3 * 8 + 4, 3 * 8 + 4
    for d in range(-6, 7):
        img[cyp + d, cxp] = COL["white"]
        img[cyp, cxp + d] = COL["white"] if abs(d) < 4 else COL["sea_l"]
    img[cyp - 9:cyp - 7, cxp - 1:cxp + 2] = COL["white"]   # N marker
    return Image.fromarray(img, "RGB").convert("RGBA"), t


def _town_at(x, y):
    return any(tx == x and ty == y for (_, tx, ty, _) in TOWNS.values())


def build():
    img, t = render()
    assert img.size == (256, 224)
    pixlib.save_png(img, os.path.join(pixlib.GFX, "ui", "region_map.png"))
    meta = {
        "cellSize": 8, "cols": CW, "rows": CH,
        "towns": [{"id": k, "name": v[0], "kind": v[3], "cell": [v[1], v[2]],
                   "pixel": [v[1] * 8 + 4, v[2] * 8 + 4]} for k, v in TOWNS.items()],
        "routes": [{"id": rid, "path": [list(cell_point(p)) for p in pts], "sea": False}
                   for rid, pts in ROUTES]
        + [{"id": rid, "path": [list(cell_point(p)) for p in pts], "sea": True} for rid, pts in SEA_ROUTES],
        "legend": {"town": "red square", "city": "orange square", "league": "purple square"},
    }
    pixlib.save_json(meta, os.path.join(pixlib.GFX, "ui", "region_map.json"))


if __name__ == "__main__":
    build()
