#!/usr/bin/env python3
"""Select CI checks from both sides of the change, including the actual PR merge tree."""

import os
import re
import subprocess


# The structural payment/release checks read these runbooks and invariants.
CONTROL_DOCS = {
    b"docs/dr.md", b"docs/invariants.md", b"docs/runtime-db-resiliency.md",
    b"docs/security.md", b"docs/runtime-security.md", b"docs/WEBHOOK_SECURITY.md",
    b"docs/RBAC.md", b"docs/SEC02_LOG_AUDIT.md", b"docs/supply-chain.md",
    b"docs/ops/release-rollback.md", b"docs/ops/secrets-rotation.md",
    b"docs/ops/owner-team-contract.md", b"docs/ops/owner-manager-contract.md",
}
# These entry points install credentials or send the signed header outside the shared interceptor.
AUTH_TRANSPORT = {
    b"miniapp/src/shared/api/http.ts", b"miniapp/src/app/App.tsx", b"miniapp/src/app/CabinetEntry.tsx",
    b"miniapp/src/modules/platform-owner/PlatformOwnerAccess.tsx",
    b"miniapp/src/modules/client/layout.api.ts", b"miniapp/src/modules/client/onboarding.api.ts",
}
SECURITY_CODE = re.compile(
    rb"auth|secur|payment|refund|credential|rbac|permission|initdata|migration|/db/|/database/|"
    rb"/(?:di|wiring|plugins|tools|logging|tokens|ott)/|bookingfinalize|bookingtemplateservice",
    re.IGNORECASE,
)


def area(path):
    name = path.rsplit(b"/", 1)[-1]
    if path in CONTROL_DOCS or name in (b"AGENTS.md", b"AGENTS.override.md"):
        return "full"
    if path.endswith(b".md"):
        if b"/" not in path or path.startswith((b"docs/", b"miniapp/docs/")) or path == b"miniapp/README.md":
            return "docs"
    # Ktor packages legacy UI directly. Payment and authentication also cross the backend boundary.
    if path.startswith((b"miniapp/src/main/resources/miniapp/", b"miniapp/src/modules/auth/",
                        b"miniapp/src/modules/payments/")) or path in AUTH_TRANSPORT:
        return "ui-backend"
    if path.startswith((b"miniapp/src/", b"miniapp/dist/", b"miniapp/public/", b"miniapp/tests/")):
        return "ui"
    if path == b"miniapp/index.html":
        return "ui"
    # JVM code always runs unit/DB tests, access/logging checks and the payment gate.
    # Critical code, build/dependency inputs, CI, infrastructure and unknown paths
    # retain the full suites. Mini App dependency/config changes also take this path.
    if re.fullmatch(rb"(?:app-bot|core-domain|core-testing|core-telemetry)/src/(?:main|test)/kotlin/.+\.kt", path):
        return "full" if SECURITY_CODE.search(path) else "backend"
    return "full"


def git(*args):
    return subprocess.check_output(["git", *args])


def lint_scope(event, base, revision, candidate):
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
            return "full", True
        ranges.append((bases[0], candidate))
    elif event != "push" or candidate != revision:
        raise ValueError("unsupported lint event")
    elif base == "0" * 40:  # First push has no previous tree.
        return "full", True

    areas = set()
    dist_changed = False
    for start, end in ranges:
        raw = git("diff", "--raw", "-z", "--no-renames", "--no-ext-diff", start, end, "--")
        # Disabling rename detection gives us both the removed and the added path.
        fields = raw.split(b"\0")
        for index in range(0, len(fields) - 1, 2):
            metadata, path = fields[index:index + 2]
            old_mode, new_mode = metadata[1:].split()[:2]
            if {old_mode, new_mode} - {b"000000", b"100644"}:
                return "full", True
            areas.add(area(path))
            dist_changed |= path.startswith(b"miniapp/dist/")
    if not areas or "full" in areas:
        return "full", True
    git("diff", "--check", base, revision, "--")
    if "ui-backend" in areas or {"backend", "ui"} <= areas:
        return "ui-backend", True
    if "backend" in areas:
        return "backend", True
    if "ui" in areas:
        return "ui", dist_changed
    return "docs", False


if __name__ == "__main__":
    mode, image = lint_scope(os.environ["GITHUB_EVENT_NAME"], os.environ["LINT_BASE"],
                             os.environ["GITHUB_SHA"], os.environ["LINT_CANDIDATE"])
    print("mode=" + mode)
    print("image=" + str(image).lower())
