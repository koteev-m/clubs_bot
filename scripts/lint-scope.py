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
# These formats are handled by the current Mini App source, test and asset pipelines.
UI_FILES = (b".ts", b".tsx", b".js", b".mjs", b".css", b".html", b".geojson", b".txt")

# These source families are also inputs to the quality gate's mutation fixtures.
# Running them does not require the simulated corrected-stage and recovery runners.
QUALITY_SOURCE = re.compile(
    rb"/(?:tools|logging|payments|db|migration)/|booking(?:finalize|templateservice|service)|"
    rb"refund|payment|migration|flyway|jsonerrorpages|telegramguestfallbackhandler|rbacplugin|webhooksecurityplugin",
    re.IGNORECASE,
)
# Client credentials and payment contracts span Mini App and backend.
CLIENT_API = re.compile(rb"auth|secur|rbac|permission|initdata|payment|refund|credential", re.IGNORECASE)
JVM_SOURCE = re.compile(
    rb"(?:app-bot|core-data|core-domain|core-security|core-testing|core-telemetry)"
    rb"/src/(?:main|test)/kotlin/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.kt"
)
# Restrict the fast path to Flyway's existing SQL layout. Other resources,
# launchers, build logic and migration mechanisms keep the full checks.
MIGRATION_SQL = re.compile(
    rb"core-data/src/main/resources/db/migration/(?:h2|postgresql|common)/V[0-9]+__[A-Za-z0-9_]+\.sql"
)


def areas_for(path):
    name = path.rsplit(b"/", 1)[-1]
    if path in CONTROL_DOCS or name in (b"AGENTS.md", b"AGENTS.override.md"):
        return {"full"}
    if path.endswith(b".md"):
        if b"/" not in path or path.startswith((b"docs/", b"miniapp/docs/")) or path == b"miniapp/README.md":
            return {"docs"}
    if path.startswith((b"miniapp/src/", b"miniapp/dist/", b"miniapp/public/", b"miniapp/tests/")):
        packaged_doc = path.startswith(b"miniapp/dist/") and path.endswith(b".md")
        if not path.endswith(UI_FILES) and not packaged_doc:
            return {"full"}
        # Ktor packages legacy UI directly. Payment and authentication also cross the backend boundary.
        if path.startswith((b"miniapp/src/main/resources/miniapp/", b"miniapp/src/modules/auth/",
                            b"miniapp/src/modules/payments/")) or path in AUTH_TRANSPORT:
            return {"ui", "backend"}
        return {"ui"}
    if path == b"miniapp/index.html":
        return {"ui"}
    # Every JVM change still runs the entire unit/PG profile (including Flyway
    # and deployment bootstrap compatibility), package smoke and payment/log gates.
    if JVM_SOURCE.fullmatch(path):
        areas = {"backend"}
        if path.startswith(b"core-security/") or (path.startswith(b"app-bot/") and CLIENT_API.search(path)):
            areas.add("ui")
        if QUALITY_SOURCE.search(path):
            areas.add("quality")
        return areas
    if MIGRATION_SQL.fullmatch(path):
        return {"backend", "quality"} if name == b"V056__atomic_booking_refunds.sql" else {"backend"}
    return {"full"}


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
            return "full", True, True
        ranges.append((bases[0], candidate))
    elif event != "push" or candidate != revision:
        raise ValueError("unsupported lint event")
    elif base == "0" * 40:  # First push has no previous tree.
        return "full", True, True

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
                return "full", True, True
            areas.update(areas_for(path))
            dist_changed |= path.startswith(b"miniapp/dist/")
    if not areas or "full" in areas:
        return "full", True, True
    git("diff", "--check", base, revision, "--")
    if "backend" in areas:
        mode = "ui-backend" if "ui" in areas else "backend"
        return mode, True, "quality" in areas
    if "ui" in areas:
        return "ui", dist_changed, False
    return "docs", False, False


if __name__ == "__main__":
    mode, image, quality = lint_scope(
        os.environ["GITHUB_EVENT_NAME"], os.environ["LINT_BASE"], os.environ["GITHUB_SHA"], os.environ["LINT_CANDIDATE"]
    )
    print("mode=" + mode)
    print("image=" + str(image).lower())
    print("quality_controls=" + str(quality).lower())
