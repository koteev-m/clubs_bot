#!/usr/bin/env python3
"""Validate and deterministically merge expected Detekt task SARIF reports."""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
from typing import Any


SARIF_VERSION = "2.1.0"
MANIFEST_SCHEMA = "clubs-bot/detekt-sarif-manifest"
MANIFEST_VERSION = 2
EXPECTED_MANIFEST = "build/reports/detekt/expected-sarif.json"
EXPECTED_OUTPUT = "build/reports/detekt/combined/detekt.sarif"
MANIFEST_ENTRY_KEYS = {
    "taskPath",
    "projectPath",
    "taskName",
    "reportPath",
    "category",
    "sourceCount",
}
class SarifContractError(RuntimeError):
    """Raised when a path, manifest, or SARIF report violates the contract."""


@dataclass(frozen=True)
class ManifestEntry:
    task_path: str
    project_path: str
    task_name: str
    report_path: str
    category: str
    source_count: int


@dataclass(frozen=True)
class SourceFile:
    logical_path: str
    path: Path
    physical_identity: tuple[int, int]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        default=".",
        help="Repository root used to resolve managed paths.",
    )
    parser.add_argument(
        "--manifest",
        required=True,
        help=f"Must be exactly {EXPECTED_MANIFEST}.",
    )
    parser.add_argument(
        "--output",
        default=EXPECTED_OUTPUT,
        help=f"Must be exactly {EXPECTED_OUTPUT}.",
    )
    return parser.parse_args()


def normalized_relative_path(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise SarifContractError(f"{field} must be a non-empty string")
    if "\\" in value:
        raise SarifContractError(f"{field} must use '/' separators: {value}")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or path.as_posix() != value
        or "." in path.parts
        or ".." in path.parts
    ):
        raise SarifContractError(
            f"{field} must be a normalized repository-relative path: {value}"
        )
    return value


def exact_cli_path(value: str, expected: str, field: str) -> str:
    normalized_relative_path(value, field)
    if value != expected:
        raise SarifContractError(f"{field} must be exactly {expected}")
    return value


def safe_task_name(value: Any, index: int) -> str:
    if not isinstance(value, str) or not value:
        raise SarifContractError(
            f"manifest entry {index} taskName must be a non-empty string"
        )
    if (
        value in {".", ".."}
        or ".." in value
        or any(separator in value for separator in ("/", "\\", ":"))
    ):
        raise SarifContractError(
            f"manifest entry {index} taskName is unsafe: {value}"
        )
    if value == "detekt":
        raise SarifContractError(
            f"manifest entry {index} must not describe the aggregate Detekt task"
        )
    return value


def project_parts(value: Any, index: int) -> tuple[str, list[str]]:
    if not isinstance(value, str) or not value.startswith(":"):
        raise SarifContractError(
            f"manifest entry {index} projectPath is invalid: {value}"
        )
    if value == ":":
        return value, []
    parts = value.removeprefix(":").split(":")
    if any(
        not part
        or part in {".", ".."}
        or ".." in part
        or any(separator in part for separator in ("/", "\\"))
        for part in parts
    ):
        raise SarifContractError(
            f"manifest entry {index} projectPath is invalid: {value}"
        )
    return value, parts


def task_identity(task_name: str) -> str:
    prefix = "detekt"
    suffix = task_name[len(prefix) :] if task_name.startswith(prefix) else ""
    if suffix:
        return suffix[0].lower() + suffix[1:]
    return task_name


def expected_identity(
    project_path: str,
    parts: list[str],
    task_name: str,
) -> tuple[str, str, str]:
    task_path = f":{task_name}" if project_path == ":" else f"{project_path}:{task_name}"
    module_path = "/".join(parts)
    module_prefix = f"{module_path}/" if module_path else ""
    report_path = (
        f"{module_prefix}build/reports/detekt/{task_name}/detekt.sarif"
    )
    category_module = module_path or "root"
    category = f"detekt/{category_module}/{task_identity(task_name)}"
    return task_path, report_path, category


def load_json_object(path: Path, description: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SarifContractError(f"invalid {description} JSON: {path}") from error
    if not isinstance(value, dict):
        raise SarifContractError(f"{description} root must be an object: {path}")
    return value


def manifest_entry(value: Any, index: int) -> ManifestEntry:
    if not isinstance(value, dict):
        raise SarifContractError(f"manifest entry {index} must be an object")
    if set(value) != MANIFEST_ENTRY_KEYS:
        missing = sorted(MANIFEST_ENTRY_KEYS - set(value))
        unexpected = sorted(set(value) - MANIFEST_ENTRY_KEYS)
        raise SarifContractError(
            f"manifest entry {index} fields changed; "
            f"missing={missing}, unexpected={unexpected}"
        )

    project_path, parts = project_parts(value["projectPath"], index)
    task_name = safe_task_name(value["taskName"], index)
    expected_task_path, expected_report_path, expected_category = expected_identity(
        project_path,
        parts,
        task_name,
    )

    task_path = value["taskPath"]
    if task_path != expected_task_path:
        raise SarifContractError(
            f"manifest entry {index} taskPath must be {expected_task_path}"
        )
    report_path = normalized_relative_path(
        value["reportPath"],
        f"manifest entry {index} reportPath",
    )
    if report_path != expected_report_path:
        raise SarifContractError(
            f"manifest entry {index} reportPath must be {expected_report_path}"
        )
    category = value["category"]
    if category != expected_category:
        raise SarifContractError(
            f"manifest entry {index} category must be {expected_category}"
        )
    source_count = value["sourceCount"]
    if (
        not isinstance(source_count, int)
        or isinstance(source_count, bool)
        or source_count <= 0
    ):
        raise SarifContractError(
            f"manifest entry {index} sourceCount must be a positive integer"
        )

    return ManifestEntry(
        task_path=task_path,
        project_path=project_path,
        task_name=task_name,
        report_path=report_path,
        category=category,
        source_count=source_count,
    )


def load_manifest(path: Path) -> list[ManifestEntry]:
    manifest = load_json_object(path, "Detekt SARIF manifest")
    if set(manifest) != {"schema", "version", "entries"}:
        raise SarifContractError("Detekt SARIF manifest root fields changed")
    if manifest.get("schema") != MANIFEST_SCHEMA:
        raise SarifContractError(
            f"Detekt SARIF manifest schema must be {MANIFEST_SCHEMA}"
        )
    version = manifest.get("version")
    if (
        not isinstance(version, int)
        or isinstance(version, bool)
        or version != MANIFEST_VERSION
    ):
        raise SarifContractError(
            f"Detekt SARIF manifest version must be {MANIFEST_VERSION}"
        )
    raw_entries = manifest.get("entries")
    if not isinstance(raw_entries, list) or not raw_entries:
        raise SarifContractError("Detekt SARIF manifest entries must be non-empty")

    entries = [manifest_entry(value, index) for index, value in enumerate(raw_entries)]
    task_paths = [entry.task_path for entry in entries]
    if task_paths != sorted(task_paths):
        raise SarifContractError("Detekt SARIF manifest entries are not taskPath-sorted")
    for description, values in (
        ("taskPath", task_paths),
        ("reportPath", [entry.report_path for entry in entries]),
        ("category", [entry.category for entry in entries]),
    ):
        if len(values) != len(set(values)):
            raise SarifContractError(
                f"Detekt SARIF manifest contains duplicate {description} values"
            )
    return entries


def lstat_path(path: Path, description: str) -> os.stat_result:
    try:
        return path.lstat()
    except OSError as error:
        raise SarifContractError(f"{description} is unavailable: {path}") from error


def validate_existing_components(
    root: Path,
    relative: str,
    description: str,
    *,
    allow_missing: bool,
) -> Path:
    relative = normalized_relative_path(relative, description)
    current = root
    parts = PurePosixPath(relative).parts
    for position, part in enumerate(parts):
        current = current / part
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            if allow_missing:
                return root.joinpath(*parts)
            raise SarifContractError(f"{description} is missing: {relative}")
        except OSError as error:
            raise SarifContractError(f"{description} is unavailable: {relative}") from error
        if stat.S_ISLNK(metadata.st_mode):
            raise SarifContractError(
                f"{description} contains a symbolic link: {current.relative_to(root)}"
            )
        if position < len(parts) - 1 and not stat.S_ISDIR(metadata.st_mode):
            raise SarifContractError(
                f"{description} parent is not a directory: {current.relative_to(root)}"
            )
    return root.joinpath(*parts)


def validate_regular_file(root: Path, relative: str, description: str) -> SourceFile:
    path = validate_existing_components(
        root,
        relative,
        description,
        allow_missing=False,
    )
    metadata = lstat_path(path, description)
    if not stat.S_ISREG(metadata.st_mode):
        raise SarifContractError(f"{description} is not a regular file: {relative}")
    if not os.access(path, os.R_OK):
        raise SarifContractError(f"{description} is not readable: {relative}")

    expected_directory = root / PurePosixPath(relative).parent
    try:
        resolved_file = path.resolve(strict=True)
        resolved_directory = expected_directory.resolve(strict=True)
    except OSError as error:
        raise SarifContractError(f"{description} cannot be resolved: {relative}") from error
    if resolved_file.parent != resolved_directory:
        raise SarifContractError(
            f"{description} does not resolve inside its exact managed task directory: {relative}"
        )
    return SourceFile(
        logical_path=relative,
        path=path,
        physical_identity=(metadata.st_dev, metadata.st_ino),
    )


def find_managed_report_roots(root: Path) -> list[Path]:
    roots: list[Path] = []
    ignored = {".git", ".gradle", ".idea", "node_modules"}
    for directory, child_directories, _ in os.walk(root, followlinks=False):
        base = Path(directory)
        child_directories[:] = sorted(
            name
            for name in child_directories
            if name not in ignored and name != "build"
        )
        build = base / "build"
        if not os.path.lexists(build):
            continue
        build_metadata = lstat_path(build, "managed build directory")
        if stat.S_ISLNK(build_metadata.st_mode):
            raise SarifContractError(
                f"managed build path is a symbolic link: {build.relative_to(root)}"
            )
        if not stat.S_ISDIR(build_metadata.st_mode):
            continue
        reports = build / "reports"
        if not os.path.lexists(reports):
            continue
        reports_metadata = lstat_path(reports, "managed reports directory")
        if stat.S_ISLNK(reports_metadata.st_mode):
            raise SarifContractError(
                f"managed reports path is a symbolic link: {reports.relative_to(root)}"
            )
        if not stat.S_ISDIR(reports_metadata.st_mode):
            continue
        detekt = reports / "detekt"
        if not os.path.lexists(detekt):
            continue
        detekt_metadata = lstat_path(detekt, "managed Detekt directory")
        if stat.S_ISLNK(detekt_metadata.st_mode):
            raise SarifContractError(
                f"managed Detekt path is a symbolic link: {detekt.relative_to(root)}"
            )
        if not stat.S_ISDIR(detekt_metadata.st_mode):
            raise SarifContractError(
                f"managed Detekt path is not a directory: {detekt.relative_to(root)}"
            )
        roots.append(detekt)
    return roots


def discover_managed_reports(root: Path) -> set[str]:
    """Inventory every source-like SARIF under managed Detekt report roots."""

    discovered: set[str] = set()

    def inspect_directory(directory: Path) -> None:
        relative_directory = directory.relative_to(root).as_posix()
        try:
            with os.scandir(directory) as iterator:
                entries = sorted(iterator, key=lambda entry: entry.name)
        except OSError as error:
            raise SarifContractError(
                f"cannot inspect managed Detekt directory: {relative_directory}"
            ) from error

        for entry in entries:
            path = Path(entry.path)
            relative = path.relative_to(root).as_posix()
            try:
                metadata = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise SarifContractError(
                    f"managed Detekt entry is unavailable: {relative}"
                ) from error

            if stat.S_ISLNK(metadata.st_mode):
                raise SarifContractError(
                    f"managed Detekt entry is a symbolic link: {relative}"
                )
            if stat.S_ISDIR(metadata.st_mode):
                inspect_directory(path)
                continue
            if path.suffix != ".sarif":
                continue
            if not stat.S_ISREG(metadata.st_mode):
                raise SarifContractError(
                    f"managed Detekt SARIF is not a regular file: {relative}"
                )
            if relative == EXPECTED_OUTPUT:
                continue
            discovered.add(relative)

    for report_root in find_managed_report_roots(root):
        inspect_directory(report_root)
    return discovered


def resolve_sources(root: Path, entries: list[ManifestEntry]) -> list[SourceFile]:
    sources = [
        validate_regular_file(root, entry.report_path, "expected source report")
        for entry in entries
    ]
    physical_identities = [source.physical_identity for source in sources]
    if len(physical_identities) != len(set(physical_identities)):
        raise SarifContractError(
            "multiple logical manifest entries refer to the same physical source report"
        )

    expected = {source.logical_path for source in sources}
    managed = discover_managed_reports(root)
    extra = sorted(managed - expected)
    missing = sorted(expected - managed)
    if extra:
        raise SarifContractError(
            "unexpected managed Detekt source reports found: " + ", ".join(extra)
        )
    if missing:
        raise SarifContractError(
            "expected managed Detekt source reports were not discovered: "
            + ", ".join(missing)
        )
    return sources


def load_source(source: SourceFile) -> dict[str, Any]:
    report = load_json_object(source.path, "SARIF")
    if report.get("version") != SARIF_VERSION:
        raise SarifContractError(
            f"SARIF version must be {SARIF_VERSION}: {source.logical_path}"
        )
    unexpected_properties = set(report) - {"$schema", "version", "runs"}
    if unexpected_properties:
        names = ", ".join(sorted(unexpected_properties))
        raise SarifContractError(
            f"unsupported SARIF root properties in {source.logical_path}: {names}"
        )

    runs = report.get("runs")
    if not isinstance(runs, list) or not runs:
        raise SarifContractError(
            f"SARIF runs must be a non-empty array: {source.logical_path}"
        )
    for run in runs:
        if not isinstance(run, dict):
            raise SarifContractError(
                f"each SARIF run must be an object: {source.logical_path}"
            )
        tool = run.get("tool")
        driver = tool.get("driver") if isinstance(tool, dict) else None
        if (
            not isinstance(driver, dict)
            or not isinstance(driver.get("name"), str)
            or not driver["name"]
        ):
            raise SarifContractError(
                f"SARIF run is missing tool.driver.name: {source.logical_path}"
            )
        results = run.get("results", [])
        if not isinstance(results, list):
            raise SarifContractError(
                f"SARIF run results must be an array: {source.logical_path}"
            )
        if any(not isinstance(result, dict) for result in results):
            raise SarifContractError(
                f"each SARIF result must be an object: {source.logical_path}"
            )
        automation_details = run.get("automationDetails")
        if "automationDetails" in run and not isinstance(automation_details, dict):
            raise SarifContractError(
                f"SARIF run automationDetails must be an object: {source.logical_path}"
            )
    return report


def effective_github_category(automation_id: str) -> str:
    """Return the category GitHub derives from SARIF automationDetails.id."""
    if "/" not in automation_id:
        return ""
    return automation_id.rsplit("/", 1)[0]


def validate_automation_ids(automation_ids: list[str]) -> None:
    categories: dict[str, str] = {}
    for automation_id in automation_ids:
        if not isinstance(automation_id, str) or not automation_id:
            raise SarifContractError("SARIF automation id must be a non-empty string")
        category = effective_github_category(automation_id)
        previous_id = categories.get(category)
        if previous_id is not None:
            raise SarifContractError(
                "duplicate effective GitHub automation category "
                f"{category}: {previous_id}, {automation_id}"
            )
        categories[category] = automation_id
    for automation_id in automation_ids:
        category = effective_github_category(automation_id)
        if not automation_id.endswith("/"):
            raise SarifContractError(
                f"SARIF automation id must end with '/': {automation_id}"
            )
        if not category:
            raise SarifContractError("SARIF automation category must be non-empty")


def merge_reports(
    entries: list[ManifestEntry],
    sources: list[SourceFile],
) -> tuple[dict[str, Any], int]:
    merged_runs: list[dict[str, Any]] = []
    automation_ids: list[str] = []
    schema: str | None = None
    result_count = 0

    for entry, source in zip(entries, sources, strict=True):
        report = load_source(source)
        source_schema = report.get("$schema")
        if source_schema is not None:
            if not isinstance(source_schema, str):
                raise SarifContractError(
                    f"SARIF $schema must be a string: {source.logical_path}"
                )
            if schema is None:
                schema = source_schema
            elif schema != source_schema:
                raise SarifContractError("source SARIF reports use different schemas")

        source_runs = report["runs"]
        for index, run in enumerate(source_runs, start=1):
            category = (
                entry.category
                if len(source_runs) == 1
                else f"{entry.category}/run-{index}"
            )
            automation_id = f"{category}/"
            automation_ids.append(automation_id)

            merged_run = deepcopy(run)
            automation_details = dict(merged_run.get("automationDetails") or {})
            automation_details["id"] = automation_id
            merged_run["automationDetails"] = automation_details
            merged_runs.append(merged_run)
            result_count += len(merged_run.get("results", []))

    validate_automation_ids(automation_ids)
    combined: dict[str, Any] = {"version": SARIF_VERSION, "runs": merged_runs}
    if schema is not None:
        combined["$schema"] = schema
    return combined, result_count


def prepare_output(root: Path, raw_output: str) -> Path:
    relative = exact_cli_path(raw_output, EXPECTED_OUTPUT, "output")
    output = validate_existing_components(
        root,
        relative,
        "combined SARIF output",
        allow_missing=True,
    )
    if os.path.lexists(output):
        metadata = lstat_path(output, "combined SARIF output")
        if stat.S_ISLNK(metadata.st_mode):
            raise SarifContractError("combined SARIF output must not be a symbolic link")
        if not stat.S_ISREG(metadata.st_mode):
            raise SarifContractError("combined SARIF output must be a regular file")
        try:
            output.unlink()
        except OSError as error:
            raise SarifContractError("cannot remove stale combined SARIF output") from error
    return output


def atomic_write(report: dict[str, Any], root: Path, output: Path) -> None:
    relative_parent = PurePosixPath(EXPECTED_OUTPUT).parent.as_posix()
    validate_existing_components(
        root,
        relative_parent,
        "combined SARIF parent",
        allow_missing=True,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    validate_existing_components(
        root,
        relative_parent,
        "combined SARIF parent",
        allow_missing=False,
    )

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output.parent,
            prefix=f".{output.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            json.dump(
                report,
                temporary,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass


def main() -> int:
    args = parse_args()
    try:
        root = Path(args.root).resolve(strict=True)
    except OSError as error:
        raise SarifContractError(f"repository root is unavailable: {args.root}") from error
    if not root.is_dir():
        raise SarifContractError(f"repository root is not a directory: {args.root}")

    output = prepare_output(root, args.output)
    manifest_relative = exact_cli_path(args.manifest, EXPECTED_MANIFEST, "manifest")
    manifest_source = validate_regular_file(
        root,
        manifest_relative,
        "Detekt SARIF manifest",
    )
    entries = load_manifest(manifest_source.path)
    sources = resolve_sources(root, entries)
    combined, result_count = merge_reports(entries, sources)
    try:
        atomic_write(combined, root, output)
    except OSError as error:
        raise SarifContractError("cannot write combined Detekt SARIF") from error
    print(
        "detekt-sarif: "
        f"sources={len(sources)} "
        f"runs={len(combined['runs'])} "
        f"results={result_count} "
        f"output={EXPECTED_OUTPUT}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SarifContractError as error:
        print(f"detekt-sarif: {error}", file=sys.stderr)
        raise SystemExit(1)
