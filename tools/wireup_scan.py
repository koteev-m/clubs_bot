#!/usr/bin/env python3
from __future__ import annotations
import re, json, os
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[1]
OUTDIR = ROOT / "build" / "reports" / "wireup"
OUTDIR.mkdir(parents=True, exist_ok=True)

MAIN_SUBPATH = "src/main/kotlin"

# ---- utils
def list_kotlin_main():
    files = []
    for dp, dn, fn in os.walk(ROOT):
        if "build" in dp.split(os.sep): 
            continue
        if "node_modules" in dp.split(os.sep):
            continue
        if not dp.endswith(MAIN_SUBPATH):
            continue
        for f in fn:
            if f.endswith(".kt"):
                files.append(Path(dp)/f)
    # relax: also allow nested dirs under src/main/kotlin
    # (walk again for subdirs)
    files2=[]
    for base in ROOT.glob("**/src/main/kotlin"):
        for p in base.rglob("*.kt"):
            files2.append(p)
    uniq = {p.resolve() for p in (files+files2)}
    return sorted(uniq)

def read(p: Path)->str:
    try:
        return p.read_text(encoding="utf-8")
    except Exception:
        return ""

PKG_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.M|re.S)
CLS_RE = re.compile(
    r"(?m)^\s*(?:@[^\n]*\s+)*"
    r"(?:(?:public|private|protected|internal|open|final|abstract|data|sealed|enum|annotation|value)\s+)*"
    r"(class|object|interface)(?:\s+)?\s+([A-Za-z_][A-Za-z0-9_]*)"
)

def package_of(text:str)->str:
    m=PKG_RE.search(text)
    return m.group(1) if m else ""

def find_decls(text:str):
    res=[]
    for m in CLS_RE.finditer(text):
        kind=m.group(1)
        name=m.group(2)
        # line number
        start_idx = m.start()
        line = text.count("\n", 0, start_idx) + 1
        res.append((kind, name, line))
    return res

def module_of(path:Path)->str:
    # first path segment (e.g. app-bot)
    rel = path.relative_to(ROOT)
    return rel.parts[0] if len(rel.parts)>0 else ""

def classify_kind(path:Path, decl_kind:str, name:str)->str:
    s = path.as_posix().lower()
    fn = path.name.lower()
    if "/workers/" in s or "worker" in fn:
        return "worker"
    if "/telegram/" in s:
        return "bot-handler"
    if "/routes/" in s:
        return "route"
    if "/repo/" in s or "repository" in s:
        return "repo"
    if "/security/" in s and ("validator" in fn or "/auth/" in s):
        return "validator"
    if "metrics" in s or "observability" in s or "telemetry" in s:
        return "telemetry"
    if "/data/" in s and "table" in name.lower():
        return "table"
    return decl_kind

def scan():
    files = list_kotlin_main()
    texts = {p: read(p) for p in files}

    # build a simple usage index (plain text search by simple name)
    all_text = {p: texts[p] for p in files}

    results = []
    for p, txt in all_text.items():
        pkg = package_of(txt)
        mod = module_of(p)
        for decl_kind, name, line in find_decls(txt):
            # search for simple name in other files
            found = False
            name_rx = re.compile(rf"\b{name}\b")
            for q, qt in all_text.items():
                if q == p: 
                    continue
                if name_rx.search(qt):
                    found = True
                    break
            # keep_by heuristics: reflection (::class)
            kept_by = []
            class_rx = re.compile(rf"\b{name}::class\b")
            for q, qt in all_text.items():
                if q == p: 
                    continue
                if class_rx.search(qt):
                    kept_by.append("reflection")
                    break
            if not found:
                k = classify_kind(p, decl_kind, name)
                results.append({
                    "symbol": (pkg+"."+name) if pkg else name,
                    "kind": k,
                    "module": mod,
                    "file": p.relative_to(ROOT).as_posix(),
                    "line": line,
                    "reason": "no inbound route/di/worker ref",
                    "kept_by": kept_by,
                    "suggested_wireup": {
                        "ktor_route": "",
                        "koin_module": "",
                        "worker_start": "",
                        "caller_example": ""
                    },
                    "risk": "low",
                    "notes": "",
                    "tests_to_add": []
                })
    return results

def write_readme(items):
    by_mod = defaultdict(list)
    for it in items:
        by_mod[it["module"]].append(it)
    lines=[]
    lines.append("# Wire-up Scan\n\n")
    lines.append("| Module | Unwired Symbols | Highlights |\n| --- | --- | --- |\n")
    for mod in sorted(by_mod):
        arr = by_mod[mod]
        hilites = []
        for it in arr[:3]:
            hilites.append(f"`{it['symbol'].split('.')[-1]}` ({it['kind']}) — {it['reason']}")
        lines.append(f"| `{mod}` | {len(arr)} | " + ("<br/>".join(hilites) if hilites else "") + " |\n")
    (OUTDIR/"README.md").write_text("".join(lines), encoding="utf-8")

def main():
    items = scan()
    (OUTDIR/"wireup.json").write_text(json.dumps(items, indent=2, ensure_ascii=False), encoding="utf-8")
    write_readme(items)
    print(f"wireup.json written: {len(items)} items")
    print((OUTDIR/"wireup.json").as_posix())
    print((OUTDIR/"README.md").as_posix())

if __name__ == "__main__":
    main()
