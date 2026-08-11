#!/usr/bin/env python3
"""Fail closed unless Trivy 0.69.3 proves analysis of real runtime JARs."""

from __future__ import annotations

import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
from typing import NoReturn


EXPECTED_TRIVY_VERSION = "0.69.3"
EXPECTED_SCAN_ROOT = "app-bot/build/install/app-bot"
CANONICAL_JAVA_RESULT = {
    "Target": "Java",
    "Class": "lang-pkgs",
    "Type": "jar",
}


def fail(message: str) -> NoReturn:
    print(f"trivy-java-inventory: {message}", file=sys.stderr)
    raise SystemExit(1)


def same_inode(left: os.stat_result, right: os.stat_result) -> bool:
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino


def parse_relative_posix_path(value: str, label: str) -> tuple[str, ...]:
    if not value or "\x00" in value or "\\" in value:
        fail(f"{label} must be a non-empty canonical POSIX relative path")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or value != path.as_posix()
        or any(part in ("", ".", "..") for part in path.parts)
    ):
        fail(f"{label} must be a canonical relative path without '.' or '..'")
    return path.parts


def open_physical_directory(raw_path: str, label: str) -> int:
    if not raw_path or "\x00" in raw_path:
        fail(f"{label} must be a non-empty physical absolute directory")
    path = Path(raw_path)
    if not path.is_absolute() or os.path.normpath(raw_path) != raw_path:
        fail(f"{label} must be a canonical physical absolute directory")

    current = Path(path.anchor)
    try:
        root_status = os.lstat(current)
    except OSError:
        fail(f"{label} root is unreadable")
    if stat.S_ISLNK(root_status.st_mode) or not stat.S_ISDIR(root_status.st_mode):
        fail(f"{label} root is not a physical directory")

    for part in path.parts[1:]:
        current /= part
        try:
            component_status = os.lstat(current)
        except OSError:
            fail(f"{label} has a missing or unreadable component")
        if stat.S_ISLNK(component_status.st_mode):
            fail(f"{label} must not contain symlink components")
        if not stat.S_ISDIR(component_status.st_mode):
            fail(f"{label} contains a non-directory component")

    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        directory_fd = os.open(path, flags)
        opened_status = os.fstat(directory_fd)
        final_status = os.lstat(path)
    except OSError:
        fail(f"{label} cannot be opened safely")
    if (
        not stat.S_ISDIR(opened_status.st_mode)
        or stat.S_ISLNK(final_status.st_mode)
        or not same_inode(opened_status, final_status)
    ):
        os.close(directory_fd)
        fail(f"{label} changed while it was being opened")
    return directory_fd


def open_relative_directory(parent_fd: int, parts: tuple[str, ...], label: str) -> int:
    current_fd = os.dup(parent_fd)
    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        for part in parts:
            try:
                before = os.stat(part, dir_fd=current_fd, follow_symlinks=False)
            except OSError:
                fail(f"{label} has a missing or unreadable component")
            if stat.S_ISLNK(before.st_mode) or not stat.S_ISDIR(before.st_mode):
                fail(f"{label} must contain physical directories only")
            try:
                next_fd = os.open(part, flags, dir_fd=current_fd)
                opened = os.fstat(next_fd)
                after = os.stat(part, dir_fd=current_fd, follow_symlinks=False)
            except OSError:
                fail(f"{label} cannot be traversed safely")
            if (
                not stat.S_ISDIR(opened.st_mode)
                or not same_inode(before, opened)
                or not same_inode(opened, after)
            ):
                os.close(next_fd)
                fail(f"{label} changed while it was being traversed")
            os.close(current_fd)
            current_fd = next_fd
        return current_fd
    except BaseException:
        os.close(current_fd)
        raise


def open_regular_file(parent_fd: int, parts: tuple[str, ...], label: str) -> int:
    if not parts:
        fail(f"{label} must name a file")
    directory_fd = open_relative_directory(parent_fd, parts[:-1], label)
    name = parts[-1]
    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    flags |= getattr(os, "O_NONBLOCK", 0)
    try:
        try:
            before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        except OSError:
            fail(f"{label} is missing or unreadable")
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
            fail(f"{label} must be a regular non-symlink file")
        try:
            file_fd = os.open(name, flags, dir_fd=directory_fd)
            opened = os.fstat(file_fd)
            after = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        except OSError:
            fail(f"{label} cannot be opened safely")
        if (
            not stat.S_ISREG(opened.st_mode)
            or not same_inode(before, opened)
            or not same_inode(opened, after)
        ):
            os.close(file_fd)
            fail(f"{label} changed while it was being opened")
        return file_fd
    finally:
        os.close(directory_fd)


def load_report(trusted_report_fd: int, report_parts: tuple[str, ...]) -> dict[str, object]:
    report_fd = open_regular_file(trusted_report_fd, report_parts, "inventory report")
    try:
        with os.fdopen(report_fd, "rb", closefd=False) as report_file:
            raw_payload = report_file.read()
    except OSError:
        fail("inventory report is unreadable")
    finally:
        os.close(report_fd)
    if not raw_payload:
        fail("inventory report is empty")
    try:
        payload = json.loads(raw_payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"inventory report is malformed JSON at line {getattr(error, 'lineno', '?')}")
    if not isinstance(payload, dict):
        fail("report root must be a JSON object")
    return payload


def is_canonical_java_result(result: dict[str, object]) -> bool:
    return all(result.get(key) == value for key, value in CANONICAL_JAVA_RESULT.items())


def is_conflicting_java_result(result: dict[str, object]) -> bool:
    return (
        result.get("Target") == "Java"
        or (
            result.get("Class") == "lang-pkgs"
            and result.get("Type") == "jar"
        )
    ) and not is_canonical_java_result(result)


def verify(
    repository_root: str,
    trusted_report_root: str,
    report_relative_path: str,
    expected_scan_root: str,
) -> None:
    report_parts = parse_relative_posix_path(report_relative_path, "report path")
    scan_root_parts = parse_relative_posix_path(expected_scan_root, "expected scan root")
    if expected_scan_root != EXPECTED_SCAN_ROOT:
        fail(f"expected scan root must be exactly {EXPECTED_SCAN_ROOT!r}")

    repository_fd = open_physical_directory(repository_root, "repository root")
    trusted_report_fd = open_physical_directory(trusted_report_root, "trusted report root")
    try:
        scan_root_fd = open_relative_directory(
            repository_fd, scan_root_parts, "expected scan root"
        )
        try:
            report = load_report(trusted_report_fd, report_parts)
            if report.get("SchemaVersion") != 2:
                fail("expected Trivy JSON SchemaVersion 2")
            trivy = report.get("Trivy")
            if not isinstance(trivy, dict) or trivy.get("Version") != EXPECTED_TRIVY_VERSION:
                fail(f"expected Trivy version {EXPECTED_TRIVY_VERSION}")
            if report.get("ArtifactType") != "filesystem":
                fail("expected Trivy rootfs JSON ArtifactType 'filesystem'")
            if report.get("ArtifactName") != expected_scan_root:
                fail("inventory ArtifactName does not match the exact runtime scan root")

            results = report.get("Results")
            if not isinstance(results, list) or not results:
                fail("report has no scan results")

            canonical_results: list[dict[str, object]] = []
            for result in results:
                if not isinstance(result, dict):
                    fail("report contains a malformed result")
                if is_canonical_java_result(result):
                    canonical_results.append(result)
                elif is_conflicting_java_result(result):
                    fail("report contains a conflicting Java/JAR result")
            if len(canonical_results) != 1:
                fail(
                    "expected exactly one canonical Java/JAR result; "
                    f"found {len(canonical_results)}"
                )

            packages = canonical_results[0].get("Packages")
            if not isinstance(packages, list) or not packages:
                fail("canonical Java/JAR result has no runtime packages")

            archive_paths: set[str] = set()
            for index, package in enumerate(packages, start=1):
                label = f"runtime package {index}"
                if not isinstance(package, dict):
                    fail(f"{label} is malformed")
                for field in ("Name", "Version"):
                    value = package.get(field)
                    if not isinstance(value, str) or not value:
                        fail(f"{label} has no {field}")
                identifier = package.get("Identifier")
                if not isinstance(identifier, dict):
                    fail(f"{label} has no Identifier")
                purl = identifier.get("PURL")
                if not isinstance(purl, str) or not purl:
                    fail(f"{label} has no package PURL")
                if package.get("AnalyzedBy") != "jar":
                    fail(f"{label} was not analyzed by the jar analyzer")

                file_path = package.get("FilePath")
                if not isinstance(file_path, str):
                    fail(f"{label} has no archive path")
                archive_parts = parse_relative_posix_path(
                    file_path, f"{label} archive path"
                )
                if PurePosixPath(file_path).suffix != ".jar":
                    fail(f"{label} archive path must have the canonical '.jar' suffix")
                archive_fd = open_regular_file(
                    scan_root_fd, archive_parts, f"{label} archive"
                )
                os.close(archive_fd)
                archive_paths.add(file_path)

            if not archive_paths:
                fail("canonical Java/JAR result has no verified runtime archives")

            print(
                "trivy-java-inventory: verified "
                f"root={expected_scan_root} java_targets=1 "
                f"runtime_packages={len(packages)} "
                f"coupled_jar_packages={len(packages)} "
                f"runtime_archives={len(archive_paths)}"
            )
        finally:
            os.close(scan_root_fd)
    finally:
        os.close(trusted_report_fd)
        os.close(repository_fd)


def main() -> None:
    if len(sys.argv) != 5:
        print(
            "usage: verify-trivy-java-inventory.py "
            "REPOSITORY_ROOT TRUSTED_REPORT_ROOT REPORT_RELATIVE EXPECTED_SCAN_ROOT",
            file=sys.stderr,
        )
        raise SystemExit(2)
    verify(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])


if __name__ == "__main__":
    main()
