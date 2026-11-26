#!/usr/bin/env python3
"""Check every Gradle JUnit report in the PostgreSQL worker for unexpected skips."""
from pathlib import Path
import sys
from xml.etree import ElementTree as ET


# These individual cases were already skipped in the baseline integration profile.
# They may start passing. New cases in the same classes must run.
ALLOWED_SKIPS = {
    ("application", "core-testing", "com.example.bot.GuestFlowStartTest"): {
        "start command sends menu with four buttons",
    },
    ("data-other", "core-data", "com.example.bot.data.support.NativeSupportPostgresLifecycleIT"): {
        "replaced PID file cannot redirect teardown to another running cluster()",
        "cleanup timeout is suppressed on original failure and does not kill a live session()",
        "partial setup cleanup works before pools exist and after the legacy pool opens()",
        "repeated successful runs stop only their child and preserve foreign cluster data()",
        "database name replaced after initial identity verification is never dropped by teardown()",
    },
    ("data-other", "core-data", "com.example.bot.data.support.SupportTicketCreationNativePostgresIT"): {
        "service persists one NEW ticket and its exact initial GUEST message()",
        "initial message failure returns typed error and rolls back the complete transaction()",
        "V057 fails closed when the expected legacy status constraint was renamed()",
        "mapped private club denies creation and reads while leaving stored rows unchanged()",
        "V057 preserves legacy rows and status column semantics()",
    },
}


def verify(shard, started_ns):
    if shard not in {"application", "data-client", "data-other"}:
        raise SystemExit("unknown PostgreSQL shard")
    reports = 0
    skips = 0
    for path in Path(".").glob("**/build/test-results/test/TEST-*.xml"):
        module = path.parents[3].as_posix()
        if (module == "core-data") != (shard != "application"):
            continue
        if path.is_symlink() or not path.is_file() or path.stat().st_mtime_ns <= started_ns:
            raise SystemExit(f"invalid or stale JUnit report: {path}")

        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        skipped = [case for case in cases if case.find("skipped") is not None]
        if (suite.tag != "testsuite" or not cases or int(suite.attrib["tests"]) != len(cases)
                or int(suite.attrib["skipped"]) != len(skipped)):
            raise SystemExit(f"inconsistent JUnit counts: {path}")
        for case in skipped:
            allowed = ALLOWED_SKIPS.get((shard, module, suite.attrib["name"]), ())
            if case.attrib["classname"] != suite.attrib["name"] or case.attrib["name"] not in allowed:
                raise SystemExit(f"unexpected skipped test in {path}: {case.attrib['name']}")
        reports += 1
        skips += len(skipped)
    if not reports:
        raise SystemExit(f"missing JUnit reports for {shard}")
    print(f"{shard}: checked {reports} fresh JUnit reports, {skips} known skips")


if __name__ == "__main__":
    verify(sys.argv[1], int(sys.argv[2]))
