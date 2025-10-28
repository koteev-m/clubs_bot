#!/usr/bin/env python3
"""Generate a consolidated smoke report.

This script collates JUnit summaries, lint outputs, and auxiliary scan
information into a Markdown report. It is intentionally defensive: missing
files, parse errors, or failing commands should not abort the report.
"""

from __future__ import annotations

import subprocess
import textwrap
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]
SMOKE_DIR = REPO_ROOT / "build" / "reports" / "smoke"
DEFAULT_RESULTS_DIR = SMOKE_DIR / "test-default"
NOTIFY_RESULTS_DIR = SMOKE_DIR / "test-notify"
ADMIN_RESULTS_DIR = SMOKE_DIR / "test-admin"
KTLINT_REPORT_DIR = REPO_ROOT / "app-bot" / "build" / "reports" / "ktlint"
DETEKT_REPORT_DIR = REPO_ROOT / "app-bot" / "build" / "reports" / "detekt"
WIREUP_REPORT = REPO_ROOT / "build" / "reports" / "wireup" / "README.md"
UNUSED_REPORT = REPO_ROOT / "build" / "reports" / "unused" / "README.md"
REPORT_PATH = SMOKE_DIR / "REPORT.md"


@dataclass
class TestTotals:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    files: int = 0

    def as_markdown(self, title: str) -> str:
        return textwrap.dedent(
            f"""
            ### {title}
            * Files: {self.files}
            * Tests: {self.tests}
            * Failures: {self.failures}
            * Errors: {self.errors}
            * Skipped: {self.skipped}
            """
        ).strip()


@dataclass
class LintSummary:
    name: str
    issue_count: int = 0
    files: int = 0
    unparsed: int = 0
    report_paths: List[Path] = None

    def __post_init__(self) -> None:
        if self.report_paths is None:
            self.report_paths = []

    def as_markdown(self) -> str:
        if not self.report_paths:
            return f"### {self.name}\n_No reports found._"

        details = "\n".join(f"  - {path.relative_to(REPO_ROOT)}" for path in self.report_paths)
        summary = (
            f"### {self.name}\n"
            f"* Reports: {len(self.report_paths)}\n"
            f"* Parsed issues: {self.issue_count}\n"
        )
        if self.unparsed:
            summary += f"* Reports not parsed: {self.unparsed}\n"
        summary += "\n" + details
        return summary.strip()


def safe_run(cmd: List[str]) -> str:
    try:
        completed = subprocess.run(
            cmd,
            cwd=REPO_ROOT,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        return completed.stdout.strip()
    except OSError as exc:
        return f"Failed to execute {' '.join(cmd)}: {exc}"


def parse_junit_directory(directory: Path) -> TestTotals:
    totals = TestTotals()
    if not directory.exists():
        return totals

    for xml_file in sorted(directory.rglob("*.xml")):
        if not xml_file.is_file():
            continue
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()
        except ET.ParseError:
            continue
        totals.files += 1
        # The root may be <testsuite> or <testsuites>
        if root.tag.endswith("testsuite"):
            _merge_suite(totals, root)
        else:
            for child in root:
                if child.tag.endswith("testsuite"):
                    _merge_suite(totals, child)
    return totals


def _merge_suite(totals: TestTotals, suite: ET.Element) -> None:
    totals.tests += int(suite.attrib.get("tests", 0))
    totals.failures += int(suite.attrib.get("failures", 0))
    totals.errors += int(suite.attrib.get("errors", 0))
    totals.skipped += int(suite.attrib.get("skipped", 0) or suite.attrib.get("ignored", 0))


def parse_lint_reports(base_dir: Path, name: str) -> LintSummary:
    summary = LintSummary(name=name)
    if not base_dir.exists():
        return summary

    for path in sorted(base_dir.rglob("*")):
        if not path.is_file():
            continue
        summary.report_paths.append(path)
        if path.suffix.lower() == ".xml":
            issues = _count_xml_issues(path)
            if issues is None:
                summary.unparsed += 1
            else:
                summary.issue_count += issues
                summary.files += 1
        elif path.suffix.lower() in {".txt", ".log"}:
            summary.issue_count += _count_text_issues(path)
            summary.files += 1
        else:
            summary.unparsed += 1
    return summary


def _count_xml_issues(path: Path) -> int | None:
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return None
    root = tree.getroot()
    count = 0
    for elem in root.iter():
        tag = elem.tag.split('}')[-1].lower()
        if tag in {"error", "violation", "issue"}:
            count += 1
        elif tag == "file" and "errors" in elem.attrib:
            try:
                count += int(elem.attrib.get("errors", "0"))
            except ValueError:
                continue
    return count


def _count_text_issues(path: Path) -> int:
    count = 0
    try:
        with path.open("r", encoding="utf-8", errors="ignore") as handle:
            for line in handle:
                stripped = line.strip()
                if not stripped:
                    continue
                if stripped.startswith(("#", "=", "-")):
                    continue
                if stripped.lower().startswith(("summary", "no issues", "lint")):
                    continue
                count += 1
    except OSError:
        return 0
    return count


def extract_tables(report_path: Path) -> str:
    if not report_path.exists():
        return ""
    try:
        lines = report_path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return ""

    table_lines: List[str] = []
    capture = False
    for line in lines:
        if "|" in line:
            table_lines.append(line.rstrip())
            capture = True
        elif capture and not line.strip():
            table_lines.append("")
            capture = False
    if not table_lines:
        # Fallback to first 20 lines if no tables were found
        table_lines = lines[:20]
    content = "\n".join(table_lines).strip()
    return content


def build_report() -> str:
    default_totals = parse_junit_directory(DEFAULT_RESULTS_DIR)
    notify_totals = parse_junit_directory(NOTIFY_RESULTS_DIR)
    admin_totals = parse_junit_directory(ADMIN_RESULTS_DIR)

    ktlint_summary = parse_lint_reports(KTLINT_REPORT_DIR, "ktlint")
    detekt_summary = parse_lint_reports(DETEKT_REPORT_DIR, "detekt")

    gradle_env = safe_run(["./gradlew", "-v"])
    commit = safe_run(["git", "rev-parse", "--short", "HEAD"])

    wireup_tables = extract_tables(WIREUP_REPORT)
    unused_tables = extract_tables(UNUSED_REPORT)

    sections: List[str] = ["# Smoke Report"]

    sections.append(
        textwrap.dedent(
            f"""
            ## Summary
            * Commit: `{commit}`
            * Gradle environment:\n\n```
{gradle_env}
```
            """
        ).strip()
    )

    sections.append("## Test Results")
    sections.append(default_totals.as_markdown(":app-bot:test (default)"))
    sections.append(notify_totals.as_markdown(":app-bot:test (USE_NOTIFY_SENDER=true)"))
    sections.append(admin_totals.as_markdown(":app-bot:test --tests \"*OutboxAdmin*\" (OUTBOX_ADMIN_ENABLED=true)"))

    sections.append("## Admin endpoints")
    sections.append(admin_totals.as_markdown("Outbox admin tests"))

    sections.append("## Lint & Static Analysis")
    sections.append(ktlint_summary.as_markdown())
    sections.append(detekt_summary.as_markdown())

    sections.append("## Observability snapshot")
    sections.append(
        textwrap.dedent(
            """
            ### Payments Metrics
            * payments.finalize.duration
            * payments.cancel.duration
            * payments.refund.duration
            * payments.idempotent.hit
            * payments.outbox.enqueued
            * payments.errors
            """
        ).strip(),
    )

    additional_sections: List[Tuple[str, str]] = []
    if wireup_tables:
        additional_sections.append(("Wireup Scan", wireup_tables))
    if unused_tables:
        additional_sections.append(("Unused Scan", unused_tables))

    if additional_sections:
        sections.append("## Additional Scans")
        for title, body in additional_sections:
            sections.append(f"### {title}\n{body}")

    return "\n\n".join(section.strip() for section in sections if section.strip()) + "\n"


def main() -> None:
    SMOKE_DIR.mkdir(parents=True, exist_ok=True)
    report = build_report()
    REPORT_PATH.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
