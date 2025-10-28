#!/usr/bin/env python3
import json, sys, os
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[1]
WIREUP = ROOT / "build" / "reports" / "wireup" / "wireup.json"
UNUSED = ROOT / "build" / "reports" / "unused" / "unused.json"
OUTDIR = ROOT / "build" / "reports" / "wireup"

def load_json(path):
    if not path.exists():
        return []
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []

def is_unwired_item(it, module):
    if it.get("module") != module:
        return False
    if it.get("kind") == "test-only":
        return False
    if it.get("reason","").lower() != "no inbound route/di/worker ref":
        return False
    return True

def main():
    module = sys.argv[1] if len(sys.argv) > 1 else "app-bot"
    wireup = load_json(WIREUP)
    unused = load_json(UNUSED)

    # map unused safe_delete by symbol for fast lookup
    safe_delete = {u["symbol"]: u for u in unused if u.get("module")==module and u.get("suggested_action")=="safe_delete"}

    unwired = [w for w in wireup if is_unwired_item(w, module)]
    # classify
    kill, wire, park = [], [], []
    for w in sorted(unwired, key=lambda x: (x.get("kind",""), x.get("symbol",""))):
        sym = w.get("symbol")
        kept_by = w.get("kept_by") or []
        if sym in safe_delete:
            kill.append({**w, "reason_merged":"safe_delete in unused.json"})
        elif len(kept_by)==0:
            wire.append(w)
        else:
            park.append({**w, "reason_merged":"kept_by="+",".join(kept_by)})

    # prepare outputs
    OUTDIR.mkdir(parents=True, exist_ok=True)
    out_json = {
        "module": module,
        "counts": {"KILL": len(kill), "WIRE": len(wire), "PARK": len(park)},
        "kill": kill,
        "wire": wire,
        "park": park,
    }
    (OUTDIR / f"actions_{module}.json").write_text(json.dumps(out_json, indent=2, ensure_ascii=False), encoding="utf-8")

    def row(w):
        return f"| `{w.get('symbol')}` | {w.get('kind','')} | `{w.get('file','')}` | {','.join(w.get('kept_by') or [])} |"

    md = []
    md.append(f"# Next actions for `{module}`\n")
    md.append(f"- **KILL**: {len(kill)}  •  **WIRE**: {len(wire)}  •  **PARK**: {len(park)}\n")
    md.append("\n## KILL (safe_delete)\n")
    md.append("| Symbol | Kind | File | kept_by |\n|---|---|---|---|")
    md += [row(x) for x in kill] or ["_none_"]

    md.append("\n\n## WIRE (connect DI/Routes/Workers)\n")
    md.append("| Symbol | Kind | File | kept_by |\n|---|---|---|---|")
    md += [row(x) for x in wire] or ["_none_"]

    md.append("\n\n## PARK (manual review — reflection/serialization/koin etc.)\n")
    md.append("| Symbol | Kind | File | kept_by |\n|---|---|---|---|")
    md += [row(x) for x in park] or ["_none_"]

    out_md = "\n".join(md) + "\n"
    (OUTDIR / f"actions_{module}.md").write_text(out_md, encoding="utf-8")

    # Also print first 120 lines for the chat
    print("\n".join(out_md.splitlines()[:120]))

if __name__ == "__main__":
    main()
