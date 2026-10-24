"""Checks the style-sheet palettes (docs/ART_STYLE.md "Visual style sheet") and renders a swatch preview.

    python3 -I art/style_check.py              # validate, exit 1 on any violation
    python3 -I art/style_check.py --markdown   # also print the tables used in the doc
    python3 -I art/style_check.py --preview    # also write art/previews/style_palettes.png
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import palettes as P  # noqa: E402

SAFE_MIN = 0.12    # indices 0 and 1: contrast >= 3:1 against the 181010 outline
DETAIL_MIN = 0.06  # index 2: texture only behind a sprite
EFFECT_MIN = 2.0   # best of (highlight, dark) vs any sprite-safe backdrop colour


def lum(c):
    def f(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])


def hx(c):
    return "%02X%02X%02X" % c


def contrast(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def check_env(errors):
    for env, slots in P.BATTLE_ENV.items():
        for variant, fn in (("day", lambda p: p), ("night", P.night_variant)):
            for slot, pal in slots.items():
                ls = [lum(c) for c in fn(pal)]
                tag = f"{env}/{variant}/{slot}"
                if not all(ls[i] > ls[i + 1] for i in range(3)):
                    errors.append(f"{tag}: luminance must fall from index 0 to 3 {[round(x, 3) for x in ls]}")
                for i, lo in ((0, SAFE_MIN), (1, SAFE_MIN), (2, DETAIL_MIN)):
                    if ls[i] < lo:
                        errors.append(f"{tag}: index {i} luminance {ls[i]:.3f} < {lo}")


def check_types(errors):
    for t, h in P.TYPE_COLOURS.items():
        r = P.type_ramp(h)
        ls = [lum(c) for c in r]
        if not all(ls[i] > ls[i + 1] for i in range(3)):
            errors.append(f"type ramp {t}: luminance not falling {[round(x, 3) for x in ls]}")
        if len(set(r)) != 4:
            errors.append(f"type ramp {t}: colours not distinct")
        if contrast(r[0], r[3]) < 4.5:
            errors.append(f"type ramp {t}: highlight/dark contrast {contrast(r[0], r[3]):.1f} < 4.5")


def check_effect_contrast(errors):
    """Every type ramp must stay visible on every sprite-safe backdrop colour: its lightest (core) or darkest (edge) colour wins."""
    worst = (99.0, "")
    for t, h in P.TYPE_COLOURS.items():
        r = P.type_ramp(h)
        for env, slots in P.BATTLE_ENV.items():
            for variant, fn in (("day", lambda p: p), ("night", P.night_variant)):
                for slot, pal in slots.items():
                    for i in (0, 1):
                        best = max(contrast(r[0], fn(pal)[i]), contrast(r[3], fn(pal)[i]))
                        worst = min(worst, (best, f"{t} on {env}/{variant}/{slot}[{i}]"))
    if worst[0] < EFFECT_MIN:
        errors.append(f"effect visibility {worst[0]:.2f} < {EFFECT_MIN} ({worst[1]})")
    return worst


def check_doc(root, errors):
    """The day-palette table in docs/ART_STYLE.md must match BATTLE_ENV."""
    text = open(os.path.join(root, "docs", "ART_STYLE.md"), encoding="utf-8").read()
    row = re.compile(r"^\| `(\w+)` \| ((?:[0-9A-F]{6} ){3}[0-9A-F]{6}) \| ((?:[0-9A-F]{6} ){3}[0-9A-F]{6}) \| ((?:[0-9A-F]{6} ){3}[0-9A-F]{6}) \|", re.M)
    found = {m.group(1): (m.group(2), m.group(3), m.group(4)) for m in row.finditer(text)}
    for env, slots in P.BATTLE_ENV.items():
        want = tuple(" ".join(hx(c) for c in slots[k]) for k in ("sky", "ground", "platform"))
        if found.get(env) != want:
            errors.append(f"docs/ART_STYLE.md table row for {env} missing or differs from palettes.py: {want}")
    for env in found:
        if env not in P.BATTLE_ENV:
            errors.append(f"docs/ART_STYLE.md lists unknown environment {env}")


def kotlin_type_drift(root):
    """Compare Palette.TYPE in Renderer.kt with art/palettes.py TYPE_COLOURS."""
    path = os.path.join(root, "core/src/main/kotlin/pkmn/game/gfx/Renderer.kt")
    text = open(path, encoding="utf-8").read()
    block = text[text.index("val TYPE"):]
    block = block[:block.index("mapValues")]
    kt = {k.lower(): v.upper() for k, v in re.findall(r'"([A-Za-z?]+)" to "([0-9a-fA-F]{6})"', block)}
    return {t: (P.TYPE_COLOURS[t], kt[t]) for t in P.TYPE_COLOURS if t in kt and kt[t] != P.TYPE_COLOURS[t]}


def markdown():
    print("| Environment | Sky | Ground | Platform |")
    print("|---|---|---|---|")
    for env, slots in P.BATTLE_ENV.items():
        print(f"| `{env}` | " + " | ".join(" ".join(hx(c) for c in slots[k]) for k in ("sky", "ground", "platform")) + " |")
    print()
    print("| Environment | Slot | Day (idx 0..3) | Night (idx 0..3) |")
    print("|---|---|---|---|")
    for env, slots in P.BATTLE_ENV.items():
        for slot, pal in slots.items():
            print(f"| `{env}` | {slot} | {' '.join(hx(c) for c in pal)} | {' '.join(hx(c) for c in P.night_variant(pal))} |")
    print()
    print("| Type | Highlight | Light | Main | Dark |")
    print("|---|---|---|---|---|")
    for t, h in P.TYPE_COLOURS.items():
        print(f"| {t} | " + " | ".join(hx(c) for c in P.type_ramp(h)) + " |")


def preview(path):
    from PIL import Image, ImageDraw
    cell, pad = 12, 4
    slots = ["sky", "ground", "platform"]
    w = 90 + 2 * len(slots) * (4 * cell + pad) + 2 * pad
    h = pad + len(P.BATTLE_ENV) * (cell + 2) + 2 * pad + (len(P.TYPE_COLOURS) // 2 + 1) * (cell + 2)
    img = Image.new("RGB", (w, h), (112, 112, 120))
    d = ImageDraw.Draw(img)
    y = pad
    for env, s in P.BATTLE_ENV.items():
        d.text((pad, y), env, fill=(248, 248, 248))
        x = 90
        for fn in (lambda p: p, P.night_variant):
            for slot in slots:
                for c in fn(s[slot]):
                    d.rectangle([x, y, x + cell - 1, y + cell - 1], fill=c)
                    x += cell
                x += pad
        y += cell + 2
    y += 2 * pad
    for i, (t, hexv) in enumerate(P.TYPE_COLOURS.items()):
        x0 = pad + (i % 2) * (w // 2)
        yy = y + (i // 2) * (cell + 2)
        d.text((x0, yy), t, fill=(248, 248, 248))
        for j, c in enumerate(P.type_ramp(hexv)):
            d.rectangle([x0 + 70 + j * cell, yy, x0 + 70 + (j + 1) * cell - 1, yy + cell - 1], fill=c)
    img = img.resize((w * 3, h * 3), Image.NEAREST)
    img.info = {}
    img.save(path, format="PNG", optimize=True)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    errors = []
    check_env(errors)
    check_types(errors)
    worst = check_effect_contrast(errors)
    check_doc(root, errors)
    drift = kotlin_type_drift(root)
    if "--markdown" in sys.argv:
        markdown()
    if "--preview" in sys.argv:
        preview(os.path.join(root, "art", "previews", "style_palettes.png"))
    print(f"{len(P.BATTLE_ENV)} battle environments x day/night x 3 slots, {len(P.TYPE_COLOURS)} type ramps checked")
    print(f"worst effect visibility {worst[0]:.2f}:1 ({worst[1]})")
    print(f"Kotlin Palette.TYPE differs from palettes.py TYPE_COLOURS for {len(drift)} types: "
          + ", ".join(f"{t} (py {a}, kt {b})" for t, (a, b) in drift.items()))
    for e in errors:
        print("FAIL", e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
