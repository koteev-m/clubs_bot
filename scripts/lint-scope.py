#!/usr/bin/env python3
"""Select the smaller CI suite only for edits to known documentation and Mini App presentation files."""

import os
import re
import subprocess


# Keep operational docs, access controls, new files and all unlisted paths on the full suite.
ORDINARY = {path.encode() for path in (
    "README.md", "miniapp/README.md", "docs/product/README.md", "docs/product/PRODUCT_ROADMAP.md",
    "miniapp/src/modules/common/components/InlineStat.tsx", "miniapp/src/modules/common/components/CapacityBadge.tsx",
    "miniapp/src/modules/guest/components/HallLegend.tsx", "miniapp/src/widgets/ToastHost.tsx",
    "miniapp/src/index.css", "miniapp/src/modules/client/calendar.css",
    "miniapp/src/modules/client/cabinet.css", "miniapp/src/modules/client/client-theme.css",
    "miniapp/src/modules/client/onboarding.css", "miniapp/src/modules/admin/pages/clubTableCreation.css",
)}


def git(*args):
    return subprocess.check_output(["git", *args])


def lint_mode(event, base, revision, candidate):
    if not all(re.fullmatch("[0-9a-f]{40}", value) for value in (base, revision, candidate)):
        raise ValueError("missing or invalid GitHub revision")
    if git("rev-parse", "HEAD").decode().strip() != revision:
        raise ValueError("checkout differs from event revision")
    ranges = [(base, revision)]
    if event == "pull_request":
        parents = git("show", "-s", "--format=%P", revision).decode().split()
        if parents != [base, candidate]:
            raise ValueError("unexpected pull request merge parents")
        bases = git("merge-base", "--all", base, candidate).decode().split()
        if len(bases) != 1:
            return "full"
        ranges.append((bases[0], candidate))
    elif event != "push" or candidate != revision:
        raise ValueError("unsupported lint event")
    elif base == "0" * 40:  # First push has no previous tree.
        return "full"

    changed = set()
    for start, end in ranges:
        raw = git("diff", "--raw", "-z", "--no-renames", "--no-ext-diff", start, end, "--")
        fields = raw.split(b"\0")
        for index in range(0, len(fields) - 1, 2):
            metadata, path = fields[index:index + 2]
            if not metadata.startswith(b":100644 100644 ") or not metadata.endswith(b" M") or path not in ORDINARY:
                return "full"
            changed.add(path)
    if not changed:
        return "full"
    git("diff", "--check", base, revision, "--")
    return "ui" if any(path.startswith(b"miniapp/src/") for path in changed) else "docs"


if __name__ == "__main__":
    mode = lint_mode(os.environ["GITHUB_EVENT_NAME"], os.environ["LINT_BASE"],
                     os.environ["GITHUB_SHA"], os.environ["LINT_CANDIDATE"])
    print("mode=" + mode)
