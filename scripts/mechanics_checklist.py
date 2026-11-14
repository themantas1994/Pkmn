#!/usr/bin/env python3
"""Renders docs/MECHANICS.md's checklist tables into reports/mechanics-checklist.json and rewrites the "Totals:" line.

The Markdown tables are the source; this keeps the JSON copy and the totals from drifting. Run from the repo root:
    python3 scripts/mechanics_checklist.py          # rewrite both
    python3 scripts/mechanics_checklist.py --check  # exit 1 if either is out of date
"""
import json, re, sys
from collections import Counter

MD, JS = "docs/MECHANICS.md", "reports/mechanics-checklist.json"
STATUSES = ("tested", "implemented", "simplified", "excluded", "open")

md = open(MD, encoding="utf-8").read()
rows, area = [], None
for line in md.splitlines():
    if line.startswith("## "):
        area = line[3:].strip()
    cells = [c.strip() for c in line.strip().strip("|").split("|")] if line.startswith("|") else []
    if len(cells) >= 5 and cells[1] in STATUSES:
        rows.append({"area": area, "mechanic": cells[0], "status": cells[1], "ruleset": cells[2], "evidence": cells[3],
                     "note": cells[4] if len(cells) > 4 else ""})
counts = Counter(r["status"] for r in rows)
old = json.load(open(JS, encoding="utf-8"))
out = {"generatedFrom": old.get("generatedFrom", f"{MD} checklist (one list, two renderings)"),
       "statusMeaning": old["statusMeaning"], "counts": {s: counts.get(s, 0) for s in STATUSES}, "mechanics": rows}
js_text = json.dumps(out, ensure_ascii=False, indent=2) + "\n"
totals = "Totals: " + ", ".join(f"{s} {counts[s]}" for s in sorted(counts)) + "."
md_text = re.sub(r"^Totals: .*$", totals, md, count=1, flags=re.M)
if "--check" in sys.argv:
    stale = [p for p, t in ((JS, js_text), (MD, md_text)) if open(p, encoding="utf-8").read() != t]
    print("out of date: " + ", ".join(stale) if stale else "up to date"); sys.exit(1 if stale else 0)
open(JS, "w", encoding="utf-8").write(js_text)
open(MD, "w", encoding="utf-8").write(md_text)
print(f"{len(rows)} mechanics; {totals}")
