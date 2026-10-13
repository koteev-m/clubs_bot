#!/usr/bin/env python3
"""Local deterministic source bundling; never executes a helper or accepts a host."""
import ast
import hashlib
import json
from pathlib import Path
import sys

BEGIN = '# BEGIN CLB91 FIXED EXECUTOR SOURCES\n'
END = '# END CLB91 FIXED EXECUTOR SOURCES\n'

class Compact(ast.NodeTransformer):
    def generic_visit(self, node):
        node = super().generic_visit(node)
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and type(first.value.value) is str:
                node.body.pop(0)
                if not node.body: node.body.append(ast.Pass())
        return node

def selected(path, names, omitted=None):
    tree = ast.parse(path.read_bytes())
    result, found = [], set()
    for node in tree.body:
        name = getattr(node, 'name', None)
        if isinstance(node, ast.Assign):
            name = node.targets[0].id if isinstance(node.targets[0], ast.Name) else None
        if name in names:
            # Omit only unused standalone reporting/writer-boundary methods.
            # Every executable prerequisite, identity and cleanup method remains.
            if omitted and name in omitted:
                node.body = [child for child in node.body if not isinstance(child, ast.FunctionDef) or child.name not in omitted[name]]
            result.append(node); found.add(name)
    assert found == set(names), 'source inventory mismatch'
    return ast.unparse(Compact().visit(ast.Module(body=result, type_ignores=[]))) + '\n'

def generated(root):
    deploy = root / 'scripts/deploy'
    planner = deploy / 'stage-compose-env-file-plan.py'
    p_tree = ast.parse(planner.read_bytes())
    names = ast.literal_eval(next(n.value for n in p_tree.body if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == 'HELPER_SOURCE_NODES' for t in n.targets)))
    p = 'import json, math, re\n' + selected(planner, names)
    d_path = deploy / 'stage-compose-diagnostic-operation.py'
    d = 'import os, selectors, signal, subprocess, time\n' + selected(d_path,
        ('identity', 'CaptureResult', 'CaptureInterrupted', 'CaptureCleanupError',
         '_capture_cancellation', 'interrupted', 'capture', 'capture_result', '_capture_owned'))
    e = 'import hashlib, json, os, platform, re, stat, sys\nfrom pathlib import Path\n' + selected(
        deploy / 'stage-compose-env-semantic-operation.py',
        ('PREREQUISITES', 'PHASES', 'STATUSES', 'Evidence', 'RuntimeMismatch', 'Runtime'),
        omitted={'Evidence': {'statuses', 'refused'}, 'Runtime': {'private_root'}})
    r_path = deploy / 'stage-docker-compose-runtime.py'
    r = ast.unparse(Compact().visit(ast.parse(r_path.read_bytes()))) + '\n'
    profile = json.loads((deploy / 'stage-docker-compose-profile.json').read_bytes())
    # The complete executor/closure profile is inside the approved helper Git
    # blob. Private review v2 already authenticates that blob and source revision.
    grouped = {}
    files = profile['runtime'].pop('files')
    for path, digest in sorted(files.items()):
        prefix, leaf = path.rsplit('/', 1)
        grouped.setdefault(prefix + '/', []).append((leaf, digest))
    profile['runtime']['files'] = {}
    lines = ['import types\n', 'CLB91_PLAN = {}\n',
        'exec(compile('+repr(p)+", '<clb91-planner>', 'exec'), CLB91_PLAN)\n",
        'CLB91_CAPTURE = {}\n',
        'exec(compile('+repr(d)+", '<clb91-capture>', 'exec'), CLB91_CAPTURE)\n",
        'CLB91_SEMANTIC = {}\n',
        'exec(compile('+repr(e)+", '<clb91-runtime>', 'exec'), CLB91_SEMANTIC)\n",
        'CLB91_EXECUTOR = {}\n',
        'exec(compile('+repr(r)+", '<clb91-pipeline>', 'exec'), CLB91_EXECUTOR)\n",
        'CLB91_PROFILE = '+repr(profile)+'\n',
        "CLB91_PROFILE['runtime']['files'] = {prefix + leaf: digest for prefix, entries in "+repr(sorted(grouped.items()))+" for leaf, digest in entries}\n",
        'CLB91_CAPTURE_OBJECT = types.SimpleNamespace(**CLB91_CAPTURE)\n',
        'CLB91_PLAN_OBJECT = types.SimpleNamespace(**CLB91_PLAN)\n',
        "CLB91_SEMANTIC.update(D=CLB91_CAPTURE_OBJECT, P=CLB91_PLAN_OBJECT, S=None, MANIFEST=CLB91_PROFILE['runtime'], COMPOSE='/usr/libexec/docker/cli-plugins/docker-compose')\n",
        "CLB91_EXECUTOR.update(D=CLB91_CAPTURE_OBJECT, E=types.SimpleNamespace(**CLB91_SEMANTIC), P=CLB91_PLAN_OBJECT, S=None, PROFILE=CLB91_PROFILE)\n"]
    return BEGIN + ''.join(lines) + END

def main():
    assert len(sys.argv) == 3 and sys.argv[1] in ('--check', '--write')
    root = Path(sys.argv[2]).resolve()
    path = root / 'scripts/deploy/remote-compose-release.sh'
    helper = path.read_text()
    block = generated(root)
    if BEGIN in helper:
        before, tail = helper.split(BEGIN, 1)
        _, after = tail.split(END, 1)
        candidate = before + block + after
    else:
        needle = '\n\nclass BoundContext:\n'
        assert helper.count(needle) == 1
        candidate = helper.replace(needle, '\n\n'+block+needle, 1)
    assert len(candidate.encode()) < 262144, 'fixed helper exceeds existing transport cap'
    body = candidate.split("<<'CLB82_BOUND_PYTHON' || true\n", 1)[1].split('\nCLB82_BOUND_PYTHON', 1)[0]
    assert len(body.encode()) < 131072, 'fixed Python argument exceeds Linux MAX_ARG_STRLEN'
    if sys.argv[1] == '--check':
        assert candidate == helper, 'embedded source drift'
    else:
        path.write_text(candidate)
    # Public source identity only, not a private input/configuration digest.
    print('corrected-compose-embedding: OK')

if __name__ == '__main__':
    main()
