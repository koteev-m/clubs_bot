#!/usr/bin/env python3
"""Fail-closed structural and test-result guard for payment hardening."""

from __future__ import annotations

import re
import os
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


def reject(rule: str, message: str) -> None:
    print(f"payment-hardening-contract: [{rule}] {message}", file=sys.stderr)
    raise SystemExit(1)


@dataclass(frozen=True)
class RequiredRuntimeTest:
    task: str
    filter: str
    xml: str
    suite: str
    class_name: str
    testcase: str
    requires_docker: bool


SENSITIVE_LOGGING_SUITE = "com.example.bot.logging.SensitiveIdempotencyLoggingTest"
SQL_LOGGING_SUITE = "com.example.bot.logging.SqlThrowableLoggingPersistenceTest"
PAYMENTS_SUITE = "com.example.bot.payments.PaymentsPersistenceTest"

REQUIRED_RUNTIME_TESTS = (
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.booking finalize route never serializes raw idempotency key",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "booking finalize route never serializes raw idempotency key",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.booking template service never serializes generated idempotency key",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "booking template service never serializes generated idempotency key",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.rbac audit fingerprint does not expose raw key to json logs",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "rbac audit fingerprint does not expose raw key to json logs",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.webhook keeps business key but never serializes it through mdc",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "webhook keeps business key but never serializes it through mdc",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.payments finalize logs presence only for long and short keys",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "payments finalize logs presence only for long and short keys",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SENSITIVE_LOGGING_SUITE}.db transaction logs never serialize sql exception detail",
        f"app-bot/build/test-results/test/TEST-{SENSITIVE_LOGGING_SUITE}.xml",
        SENSITIVE_LOGGING_SUITE,
        SENSITIVE_LOGGING_SUITE,
        "db transaction logs never serialize sql exception detail",
        False,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{SQL_LOGGING_SUITE}.postgres sql throwable never reaches payment route status pages or json logs",
        f"app-bot/build/test-results/test/TEST-{SQL_LOGGING_SUITE}.xml",
        SQL_LOGGING_SUITE,
        SQL_LOGGING_SUITE,
        "postgres sql throwable never reaches payment route status pages or json logs",
        True,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{PAYMENTS_SUITE}.refund explicit zero persists terminal success without mutation",
        f"app-bot/build/test-results/test/TEST-{PAYMENTS_SUITE}.xml",
        PAYMENTS_SUITE,
        PAYMENTS_SUITE,
        "refund explicit zero persists terminal success without mutation",
        True,
    ),
    RequiredRuntimeTest(
        ":app-bot:test",
        f"{PAYMENTS_SUITE}.refund explicit zero production RBAC route replays stable public result without mutation",
        f"app-bot/build/test-results/test/TEST-{PAYMENTS_SUITE}.xml",
        PAYMENTS_SUITE,
        PAYMENTS_SUITE,
        "refund explicit zero production RBAC route replays stable public result without mutation",
        True,
    ),
)


def physical_repository_root(argument: str) -> Path:
    try:
        root = Path(argument).resolve(strict=True)
        root_stat = root.lstat()
    except OSError as error:
        reject("PH-FILE", f"repository root is unavailable: {error.strerror or error.__class__.__name__}")
    if not stat.S_ISDIR(root_stat.st_mode) or root.is_symlink():
        reject("PH-FILE", "repository root is not a physical directory")
    return root


def safe_regular_file(root: Path, relative: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute() or not candidate.parts or any(part in {"", ".", ".."} for part in candidate.parts):
        reject("PH-FILE", f"protected path is not repository-relative: {relative}")

    current = root
    final_stat: os.stat_result | None = None
    for index, component in enumerate(candidate.parts):
        current = current / component
        try:
            component_stat = current.lstat()
        except OSError as error:
            reject(
                "PH-FILE",
                f"cannot inspect protected input {relative}: {error.strerror or error.__class__.__name__}",
            )
        if stat.S_ISLNK(component_stat.st_mode):
            reject("PH-FILE", f"protected input contains a symlink component: {relative}")
        if index < len(candidate.parts) - 1:
            if not stat.S_ISDIR(component_stat.st_mode):
                reject("PH-FILE", f"protected input parent is not a directory: {relative}")
        else:
            final_stat = component_stat

    if final_stat is None or not stat.S_ISREG(final_stat.st_mode):
        reject("PH-FILE", f"protected input is not a regular file: {relative}")
    if not os.access(current, os.R_OK):
        reject("PH-FILE", f"protected input is unreadable: {relative}")
    return current


def safe_read_text(root: Path, relative: str) -> str:
    path = safe_regular_file(root, relative)
    flags = os.O_RDONLY
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = -1
    try:
        before = path.lstat()
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            reject("PH-FILE", f"protected input changed while opening: {relative}")
        with os.fdopen(descriptor, "r", encoding="utf-8") as handle:
            descriptor = -1
            return handle.read()
    except (OSError, UnicodeError) as error:
        detail = error.strerror if isinstance(error, OSError) else error.__class__.__name__
        reject("PH-FILE", f"cannot read protected input {relative}: {detail}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def expected_runtime_groups() -> dict[str, tuple[RequiredRuntimeTest, ...]]:
    groups: dict[str, list[RequiredRuntimeTest]] = {}
    for entry in REQUIRED_RUNTIME_TESTS:
        groups.setdefault(entry.xml, []).append(entry)
    return {path: tuple(entries) for path, entries in groups.items()}


def verify_junit_xml(arguments: list[str]) -> None:
    if len(arguments) < 4:
        reject("PH-TEST-RESULT", "JUnit verification requires XML, suite, start time, and test names")
    xml_path = Path(arguments[0])
    expected_suite = arguments[1]
    try:
        started_at_ns = int(arguments[2])
    except ValueError:
        reject("PH-TEST-RESULT", "JUnit verification start time must be epoch nanoseconds")
    expected_tests = set(arguments[3:])
    if len(expected_tests) != len(arguments[3:]):
        reject("PH-TEST-RESULT", "required JUnit testcase names must be unique")
    try:
        xml_stat = xml_path.lstat()
        if not stat.S_ISREG(xml_stat.st_mode) or xml_path.is_symlink() or not os.access(xml_path, os.R_OK):
            reject("PH-TEST-RESULT", f"JUnit XML is not a readable regular file: {xml_path}")
        if xml_stat.st_mtime_ns <= started_at_ns:
            reject("PH-TEST-RESULT", f"JUnit XML is stale: {xml_path}")
        root = ET.parse(xml_path).getroot()
    except (OSError, ET.ParseError) as error:
        reject("PH-TEST-RESULT", f"cannot read JUnit XML {xml_path}: {error}")

    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    matching = [suite for suite in suites if suite.attrib.get("name") == expected_suite]
    if len(matching) != 1:
        reject("PH-TEST-RESULT", f"expected exactly one JUnit suite {expected_suite}")
    suite = matching[0]
    try:
        counts = {
            name: int(suite.attrib.get(name, "0"))
            for name in ("tests", "skipped", "failures", "errors")
        }
    except ValueError:
        reject("PH-TEST-RESULT", "JUnit XML contains a non-numeric suite count")
    if counts["tests"] != len(expected_tests) or counts["tests"] <= 0:
        reject(
            "PH-TEST-RESULT",
            f"required JUnit suite test count changed: expected {len(expected_tests)}, got {counts['tests']}",
        )
    if any(counts[name] != 0 for name in ("skipped", "failures", "errors")):
        reject("PH-TEST-RESULT", f"required JUnit suite is not clean: {counts}")

    cases = list(suite.findall("testcase"))
    if len(cases) != len(expected_tests):
        reject("PH-TEST-RESULT", "required JUnit suite contains unexpected testcase count")
    if any(case.attrib.get("classname") != expected_suite for case in cases):
        reject("PH-TEST-RESULT", "required JUnit testcase classname changed")
    if any(case.find(name) is not None for case in cases for name in ("skipped", "failure", "error")):
        reject("PH-TEST-RESULT", "required JUnit testcase contains a skipped or failed result")
    executed_names = [case.attrib.get("name", "").removesuffix("()") for case in cases]
    if len(set(executed_names)) != len(executed_names):
        reject("PH-TEST-RESULT", "required JUnit suite contains duplicate testcase names")
    executed = set(executed_names)
    missing = sorted(expected_tests - executed)
    extra = sorted(executed - expected_tests)
    if missing or extra:
        reject("PH-TEST-RESULT", f"required JUnit tests did not execute: {', '.join(missing)}")
    print("payment-hardening-test-result: OK")


def prepare_runtime_output(root: Path, relative: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute() or not candidate.parts or any(part in {"", ".", ".."} for part in candidate.parts):
        reject("PH-TEST-RESULT", f"unsafe JUnit output path in required manifest: {relative}")

    current = root
    missing_parent = False
    for component in candidate.parts[:-1]:
        current = current / component
        if missing_parent:
            continue
        try:
            component_stat = current.lstat()
        except FileNotFoundError:
            missing_parent = True
            continue
        except OSError as error:
            reject(
                "PH-TEST-RESULT",
                f"cannot inspect JUnit output parent {relative}: {error.strerror or error.__class__.__name__}",
            )
        if stat.S_ISLNK(component_stat.st_mode) or not stat.S_ISDIR(component_stat.st_mode):
            reject("PH-TEST-RESULT", f"JUnit output parent is not a physical directory: {relative}")

    output = root / candidate
    try:
        output_stat = output.lstat()
    except FileNotFoundError:
        return output
    except OSError as error:
        reject(
            "PH-TEST-RESULT",
            f"cannot inspect prior JUnit output {relative}: {error.strerror or error.__class__.__name__}",
        )
    if stat.S_ISLNK(output_stat.st_mode) or not stat.S_ISREG(output_stat.st_mode):
        reject("PH-TEST-RESULT", f"prior JUnit output is not a regular file: {relative}")
    try:
        output.unlink()
    except OSError as error:
        reject(
            "PH-TEST-RESULT",
            f"cannot remove prior JUnit output {relative}: {error.strerror or error.__class__.__name__}",
        )
    return output


def verify_required_runtime_xml(
    root: Path,
    relative: str,
    entries: tuple[RequiredRuntimeTest, ...],
    started_at_ns: int,
) -> None:
    path = safe_regular_file(root, relative)
    try:
        xml_stat = path.lstat()
        if xml_stat.st_mtime_ns <= started_at_ns:
            reject("PH-TEST-RESULT", f"required JUnit XML is stale: {relative}")
        xml_root = ET.fromstring(safe_read_text(root, relative))
    except ET.ParseError as error:
        reject("PH-TEST-RESULT", f"required JUnit XML is malformed: {relative}: {error}")

    suites = [xml_root] if xml_root.tag == "testsuite" else list(xml_root.findall("testsuite"))
    if len(suites) != 1 or suites[0].attrib.get("name") != entries[0].suite:
        reject("PH-TEST-RESULT", f"required JUnit suite identity changed: {relative}")
    suite = suites[0]
    try:
        counts = {
            name: int(suite.attrib.get(name, "0"))
            for name in ("tests", "skipped", "failures", "errors")
        }
    except ValueError:
        reject("PH-TEST-RESULT", f"required JUnit suite has non-numeric counts: {relative}")
    if counts["tests"] <= 0 or counts["tests"] != len(entries):
        reject(
            "PH-TEST-RESULT",
            f"required JUnit suite count changed for {relative}: expected {len(entries)}, got {counts['tests']}",
        )
    if any(counts[name] != 0 for name in ("skipped", "failures", "errors")):
        reject("PH-TEST-RESULT", f"required JUnit suite is not clean for {relative}: {counts}")

    cases = list(suite.findall("testcase"))
    if len(cases) != len(entries):
        reject("PH-TEST-RESULT", f"required JUnit testcase count changed: {relative}")
    expected = {(entry.class_name, entry.testcase) for entry in entries}
    if len(expected) != len(entries):
        reject("PH-RUNTIME-MANIFEST", "required runtime manifest contains duplicate testcase identities")
    executed: list[tuple[str, str]] = []
    for case in cases:
        if any(case.find(name) is not None for name in ("skipped", "failure", "error")):
            reject("PH-TEST-RESULT", f"required JUnit testcase is skipped or failed: {relative}")
        executed.append(
            (
                case.attrib.get("classname", ""),
                case.attrib.get("name", "").removesuffix("()"),
            ),
        )
    if len(set(executed)) != len(executed):
        reject("PH-TEST-RESULT", f"required JUnit suite contains duplicate testcase identities: {relative}")
    missing = sorted(expected - set(executed))
    extra = sorted(set(executed) - expected)
    if missing or extra:
        reject(
            "PH-TEST-RESULT",
            f"required JUnit identities changed for {relative}: missing={missing}, extra={extra}",
        )


def run_required_runtime(argument: str) -> None:
    root = physical_repository_root(argument)
    if len(REQUIRED_RUNTIME_TESTS) != 9:
        reject("PH-RUNTIME-MANIFEST", "required runtime manifest must contain exactly nine tests")
    filters = tuple(entry.filter for entry in REQUIRED_RUNTIME_TESTS)
    if len(filters) != len(set(filters)):
        reject("PH-RUNTIME-MANIFEST", "required Gradle filters must be unique")
    if any(entry.task != ":app-bot:test" for entry in REQUIRED_RUNTIME_TESTS):
        reject("PH-RUNTIME-MANIFEST", "required runtime task contract changed")

    source_paths = {
        SENSITIVE_LOGGING_SUITE: "app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt",
        SQL_LOGGING_SUITE: "app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt",
        PAYMENTS_SUITE: "app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt",
    }
    for source_path in source_paths.values():
        safe_regular_file(root, source_path)

    gradlew = safe_regular_file(root, "gradlew")
    if not gradlew.stat().st_mode & 0o111:
        reject("PH-RUNTIME", "repository Gradle wrapper is not executable")

    groups = expected_runtime_groups()
    for relative in groups:
        prepare_runtime_output(root, relative)
    started_at_ns = time.time_ns()

    command = [
        str(gradlew),
        ":app-bot:test",
        "-PrunIT=true",
    ]
    for required_filter in filters:
        command.extend(("--tests", required_filter))
    command.extend(
        (
            "--rerun-tasks",
            "--no-build-cache",
            "--no-configuration-cache",
            "--console=plain",
        ),
    )
    try:
        result = subprocess.run(command, cwd=root, check=False)
    except OSError as error:
        reject("PH-RUNTIME", f"cannot execute repository Gradle wrapper: {error.strerror or error.__class__.__name__}")
    if result.returncode != 0:
        reject("PH-RUNTIME", f"required Gradle tests failed with exit {result.returncode}")

    for relative, entries in groups.items():
        verify_required_runtime_xml(root, relative, entries, started_at_ns)
    print("payment-hardening-runtime: PASS")


if len(sys.argv) > 1 and sys.argv[1] == "--verify-junit-xml":
    verify_junit_xml(sys.argv[2:])
    raise SystemExit(0)

if len(sys.argv) > 1 and sys.argv[1] == "--run-required-runtime":
    if len(sys.argv) != 3:
        reject("PH-CLI", "usage: validate-payment-hardening.py --run-required-runtime repository-root")
    run_required_runtime(sys.argv[2])
    raise SystemExit(0)

if len(sys.argv) > 1 and sys.argv[1] == "--structural":
    if len(sys.argv) != 3:
        reject("PH-CLI", "usage: validate-payment-hardening.py --structural repository-root")
    structural_root = sys.argv[2]
elif len(sys.argv) <= 2:
    structural_root = sys.argv[1] if len(sys.argv) == 2 else "."
else:
    reject("PH-CLI", "usage: validate-payment-hardening.py [--structural] [repository-root]")

ROOT = physical_repository_root(structural_root)


def read(relative: str) -> str:
    return safe_read_text(ROOT, relative)


def strip_comments(text: str, sql: bool = False) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    if sql:
        return re.sub(r"--[^\r\n]*", " ", text)
    return re.sub(r"//[^\r\n]*", " ", text)


def compact(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().lower()


def require(text: str, needle: str, rule: str, description: str) -> None:
    if compact(needle) not in compact(text):
        reject(rule, description)


def check_body(sql: str, constraint_name: str) -> str:
    match = re.search(
        rf"\b(?:add\s+)?constraint\s+{re.escape(constraint_name)}\s+check\s*\(",
        sql,
        flags=re.IGNORECASE,
    )
    if match is None:
        reject("PH-MIGRATION-CONSTRAINT", f"missing CHECK constraint {constraint_name}")
    start = match.end() - 1
    depth = 0
    quote = False
    index = start
    while index < len(sql):
        char = sql[index]
        if char == "'":
            if quote and index + 1 < len(sql) and sql[index + 1] == "'":
                index += 2
                continue
            quote = not quote
        elif not quote:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return sql[start + 1 : index]
        index += 1
    reject("PH-MIGRATION-CONSTRAINT", f"unterminated CHECK constraint {constraint_name}")
    raise AssertionError("unreachable")


@dataclass(frozen=True)
class KotlinToken:
    kind: str
    text: str
    offset: int


@dataclass(frozen=True)
class KotlinCall:
    name: str
    receiver: str | None
    body: tuple[KotlinToken, ...]
    start_index: int
    end_index: int


@dataclass(frozen=True)
class KotlinFunctionDeclaration:
    name: str
    receiver: str | None
    parameters: tuple[tuple[str, str], ...]
    return_type: str | None
    visibility: str | None
    declaration_index: int
    body_start: int | None
    body_end: int | None
    brace_depth: int


@dataclass(frozen=True)
class ReviewedCallPattern:
    terminal: tuple[str | None, str]
    required: tuple[tuple[str | None, str], ...] = ()


@dataclass(frozen=True)
class ReviewedResultContract:
    owner: str
    patterns: tuple[ReviewedCallPattern, ...]
    projections: tuple[tuple[str, ...], ...]


def lex_kotlin(source: str) -> list[KotlinToken]:
    """Small Kotlin lexer for call/alias analysis; strings and nested comments are opaque tokens."""
    tokens: list[KotlinToken] = []
    index = 0
    length = len(source)
    while index < length:
        char = source[index]
        if char in " \t\r\f":
            index += 1
            continue
        if char == "\n":
            tokens.append(KotlinToken("newline", "\n", index))
            index += 1
            continue
        if source.startswith("//", index):
            end = source.find("\n", index + 2)
            index = length if end < 0 else end
            continue
        if source.startswith("/*", index):
            start = index
            depth = 1
            index += 2
            while index < length and depth:
                if source.startswith("/*", index):
                    depth += 1
                    index += 2
                elif source.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                reject("PH-LOG-SCANNER", f"unterminated Kotlin block comment at offset {start}")
            continue
        if source.startswith('"""', index):
            start = index
            end = source.find('"""', index + 3)
            if end < 0:
                reject("PH-LOG-SCANNER", f"unterminated Kotlin raw string at offset {start}")
            tokens.append(KotlinToken("raw_string", source[index + 3 : end], start))
            index = end + 3
            continue
        if char in ('"', "'"):
            start = index
            quote = char
            index += 1
            value: list[str] = []
            escaped = False
            while index < length:
                current = source[index]
                if escaped:
                    value.append(current)
                    escaped = False
                elif current == "\\":
                    value.append(current)
                    escaped = True
                elif current == quote:
                    break
                else:
                    value.append(current)
                index += 1
            if index >= length:
                reject("PH-LOG-SCANNER", f"unterminated Kotlin quoted literal at offset {start}")
            tokens.append(KotlinToken("string" if quote == '"' else "char", "".join(value), start))
            index += 1
            continue
        if char == "`":
            start = index
            end = source.find("`", index + 1)
            if end < 0:
                reject("PH-LOG-SCANNER", f"unterminated Kotlin escaped identifier at offset {start}")
            tokens.append(KotlinToken("identifier", source[index + 1 : end], start))
            index = end + 1
            continue
        if char == "_" or char.isalpha():
            start = index
            index += 1
            while index < length and (source[index] == "_" or source[index].isalnum()):
                index += 1
            tokens.append(KotlinToken("identifier", source[start:index], start))
            continue
        operator = next(
            (candidate for candidate in ("->", "?.", "?:", "!!", "==", "!=", "<=", ">=", "&&", "||", "::") if source.startswith(candidate, index)),
            None,
        )
        if operator is not None:
            tokens.append(KotlinToken("symbol", operator, index))
            index += len(operator)
        else:
            tokens.append(KotlinToken("symbol", char, index))
            index += 1
    return tokens


def matching_token(tokens: list[KotlinToken], opening_index: int) -> int:
    pairs = {"(": ")", "{": "}", "[": "]", "<": ">"}
    opening = tokens[opening_index].text
    closing = pairs.get(opening)
    if closing is None:
        reject("PH-LOG-SCANNER", f"unsupported Kotlin delimiter {opening}")
    depth = 0
    for index in range(opening_index, len(tokens)):
        token = tokens[index]
        if token.kind in {"string", "raw_string", "char"}:
            continue
        if token.text == opening:
            depth += 1
        elif token.text == closing:
            depth -= 1
            if depth == 0:
                return index
    reject("PH-LOG-SCANNER", f"unterminated Kotlin delimiter {opening}")
    raise AssertionError("unreachable")


def skip_newlines(tokens: list[KotlinToken], index: int) -> int:
    while index < len(tokens) and tokens[index].kind == "newline":
        index += 1
    return index


def matching_type_arguments(tokens: list[KotlinToken], opening_index: int) -> int | None:
    """Return a generic type-argument close only when it is followed by a call body."""
    depth = 0
    for index in range(opening_index, len(tokens)):
        token = tokens[index]
        if token.kind in {"string", "raw_string", "char"}:
            continue
        if token.text == "<":
            depth += 1
        elif token.text == ">":
            depth -= 1
            if depth == 0:
                after = skip_newlines(tokens, index + 1)
                if after < len(tokens) and tokens[after].text in {"(", "{"}:
                    return index
                return None
    return None


def kotlin_calls(tokens: list[KotlinToken]) -> list[KotlinCall]:
    calls: list[KotlinCall] = []
    for index, token in enumerate(tokens):
        if token.kind != "identifier":
            continue
        declaration_cursor = index - 1
        is_function_declaration = False
        while declaration_cursor >= 0 and tokens[declaration_cursor].text not in {";", "{", "}", "="}:
            if tokens[declaration_cursor].kind == "identifier" and tokens[declaration_cursor].text == "fun":
                is_function_declaration = True
                break
            declaration_cursor -= 1
        if is_function_declaration:
            continue
        cursor = skip_newlines(tokens, index + 1)
        if cursor < len(tokens) and tokens[cursor].text == "<":
            type_arguments_end = matching_type_arguments(tokens, cursor)
            if type_arguments_end is None:
                continue
            cursor = skip_newlines(tokens, type_arguments_end + 1)
        if cursor >= len(tokens) or tokens[cursor].text not in ("(", "{"):
            continue
        closing = matching_token(tokens, cursor)
        body = list(tokens[cursor + 1 : closing])
        after = closing + 1
        after = skip_newlines(tokens, after)
        end_index = closing
        if tokens[cursor].text == "(" and after < len(tokens) and tokens[after].text == "{":
            lambda_end = matching_token(tokens, after)
            body.extend(tokens[after + 1 : lambda_end])
            end_index = lambda_end
        receiver: str | None = None
        before = index - 1
        while before >= 0 and tokens[before].kind == "newline":
            before -= 1
        if before >= 0 and tokens[before].text in (".", "?."):
            receiver_index = before - 1
            while receiver_index >= 0 and tokens[receiver_index].kind == "newline":
                receiver_index -= 1
            receiver = (
                tokens[receiver_index].text
                if receiver_index >= 0 and tokens[receiver_index].kind == "identifier"
                else "<expression>"
            )
        calls.append(KotlinCall(token.text, receiver, tuple(body), index, end_index))
    return calls


def token_text(tokens: tuple[KotlinToken, ...] | list[KotlinToken]) -> str:
    return "".join(token.text for token in tokens if token.kind != "newline")


def split_top_level_tokens(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    separator: str = ",",
) -> list[list[KotlinToken]]:
    parts: list[list[KotlinToken]] = [[]]
    stack: list[str] = []
    pairs = {"(": ")", "[": "]", "{": "}", "<": ">"}
    for token in tokens:
        if token.kind not in {"string", "raw_string", "char"}:
            if token.text in pairs:
                stack.append(pairs[token.text])
            elif stack and token.text == stack[-1]:
                stack.pop()
            elif token.text == separator and not stack:
                parts.append([])
                continue
        parts[-1].append(token)
    return [part for part in parts if any(token.kind != "newline" for token in part)]


def kotlin_package(tokens: list[KotlinToken]) -> str | None:
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text != "package":
            continue
        cursor = skip_newlines(tokens, index + 1)
        parts: list[str] = []
        expect_identifier = True
        while cursor < len(tokens) and tokens[cursor].kind != "newline":
            current = tokens[cursor]
            if expect_identifier and current.kind == "identifier":
                parts.append(current.text)
                expect_identifier = False
            elif not expect_identifier and current.text == ".":
                expect_identifier = True
            else:
                break
            cursor += 1
        return ".".join(parts) if parts and not expect_identifier else None
    return None


def kotlin_imports(tokens: list[KotlinToken]) -> set[tuple[str, str | None]]:
    imports: set[tuple[str, str | None]] = set()
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text != "import":
            continue
        cursor = skip_newlines(tokens, index + 1)
        parts: list[str] = []
        alias: str | None = None
        while cursor < len(tokens) and tokens[cursor].kind != "newline":
            current = tokens[cursor]
            if current.kind == "identifier" and current.text == "as":
                alias_cursor = skip_newlines(tokens, cursor + 1)
                if alias_cursor < len(tokens) and tokens[alias_cursor].kind == "identifier":
                    alias = tokens[alias_cursor].text
                break
            if current.kind == "identifier" or current.text in {".", "*"}:
                parts.append(current.text)
            else:
                break
            cursor += 1
        if parts:
            imports.add(("".join(parts), alias))
    return imports


def brace_depths(tokens: list[KotlinToken]) -> list[int]:
    depths: list[int] = []
    depth = 0
    for token in tokens:
        depths.append(depth)
        if token.kind in {"string", "raw_string", "char"}:
            continue
        if token.text == "{":
            depth += 1
        elif token.text == "}":
            depth = max(0, depth - 1)
    return depths


def kotlin_function_declarations(tokens: list[KotlinToken]) -> list[KotlinFunctionDeclaration]:
    declarations: list[KotlinFunctionDeclaration] = []
    depths = brace_depths(tokens)
    visibility_names = {"public", "private", "protected", "internal"}
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text != "fun":
            continue
        after_fun = skip_newlines(tokens, index + 1)
        if after_fun < len(tokens) and tokens[after_fun].kind == "identifier" and tokens[after_fun].text == "interface":
            continue
        parameter_open = skip_newlines(tokens, index + 1)
        while parameter_open < len(tokens) and tokens[parameter_open].text != "(":
            if tokens[parameter_open].text in {"{", "}", ";", "="}:
                parameter_open = len(tokens)
                break
            parameter_open += 1
        if parameter_open >= len(tokens):
            continue
        header = [item for item in tokens[index + 1 : parameter_open] if item.kind != "newline"]
        identifier_positions = [position for position, item in enumerate(header) if item.kind == "identifier"]
        if not identifier_positions:
            continue
        name_position = identifier_positions[-1]
        name = header[name_position].text
        receiver: str | None = None
        if name_position > 0 and header[name_position - 1].text in {".", "?."}:
            receiver = token_text(header[: name_position - 1])

        parameter_close = matching_token(tokens, parameter_open)
        parameters: list[tuple[str, str]] = []
        for part in split_top_level_tokens(tokens[parameter_open + 1 : parameter_close]):
            compact_part = [item for item in part if item.kind != "newline"]
            colon = next((position for position, item in enumerate(compact_part) if item.text == ":"), None)
            if colon is None:
                continue
            parameter_name = next(
                (item.text for item in reversed(compact_part[:colon]) if item.kind == "identifier"),
                None,
            )
            if parameter_name is None:
                continue
            equals = next(
                (position for position, item in enumerate(compact_part[colon + 1 :], start=colon + 1) if item.text == "="),
                len(compact_part),
            )
            parameters.append((parameter_name, token_text(compact_part[colon + 1 : equals])))

        cursor = skip_newlines(tokens, parameter_close + 1)
        return_type: str | None = None
        if cursor < len(tokens) and tokens[cursor].text == ":":
            cursor += 1
            return_tokens: list[KotlinToken] = []
            while cursor < len(tokens) and tokens[cursor].text not in {"{", "=", ";"}:
                return_tokens.append(tokens[cursor])
                cursor += 1
            return_type = token_text(return_tokens) or None
        cursor = skip_newlines(tokens, cursor)
        body_start: int | None = None
        body_end: int | None = None
        if cursor < len(tokens) and tokens[cursor].text == "{":
            body_start = cursor
            body_end = matching_token(tokens, cursor)
        elif cursor < len(tokens) and tokens[cursor].text == "=":
            expression = assignment_expression(tokens, cursor)
            if expression:
                body_start = cursor
                last_offset = expression[-1].offset
                body_end = next(
                    candidate
                    for candidate in range(cursor + 1, len(tokens))
                    if tokens[candidate].offset == last_offset
                )

        visibility: str | None = None
        previous = index - 1
        while previous >= 0 and tokens[previous].kind != "newline" and tokens[previous].text not in {"{", "}", ";"}:
            if tokens[previous].kind == "identifier" and tokens[previous].text in visibility_names:
                visibility = tokens[previous].text
                break
            previous -= 1
        declarations.append(
            KotlinFunctionDeclaration(
                name=name,
                receiver=receiver,
                parameters=tuple(parameters),
                return_type=return_type,
                visibility=visibility,
                declaration_index=index,
                body_start=body_start,
                body_end=body_end,
                brace_depth=depths[index],
            ),
        )
    return declarations


def kotlin_value_bindings(tokens: list[KotlinToken]) -> set[str]:
    names: set[str] = set()
    for index, token in enumerate(tokens):
        if token.kind == "identifier" and token.text in {"val", "var"}:
            cursor = skip_newlines(tokens, index + 1)
            if cursor < len(tokens) and tokens[cursor].kind == "identifier":
                names.add(tokens[cursor].text)
    for declaration in kotlin_function_declarations(tokens):
        names.update(name for name, _ in declaration.parameters)
    return names


def scope_function_name(tokens: list[KotlinToken]) -> str | None:
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text != "fun":
            continue
        cursor = skip_newlines(tokens, index + 1)
        if cursor < len(tokens) and tokens[cursor].kind == "identifier" and tokens[cursor].text == "interface":
            continue
        identifiers: list[str] = []
        while cursor < len(tokens) and tokens[cursor].text != "(":
            if tokens[cursor].kind == "identifier":
                identifiers.append(tokens[cursor].text)
            if tokens[cursor].text in {"{", "}", ";", "="}:
                break
            cursor += 1
        if cursor < len(tokens) and tokens[cursor].text == "(" and identifiers:
            return identifiers[-1]
    return None


def normalized_identifier(value: str) -> str:
    return value.replace("_", "").lower()


RAW_KEY_IDENTIFIERS = {"idempotencykey", "idemkey", "rawkey"}
PRESENCE_METHODS = {"isBlank", "isNotBlank", "isNullOrBlank", "isNullOrEmpty"}
RAW_HEADER_NAMES = {"idempotency-key", "idempotency_key"}
LOGGER_CALLS = {"trace", "debug", "info", "warn", "error"}
TRACING_CALLS = {"setAttribute", "attribute", "tag", "addEvent", "keyValue", "addKeyValue"}
FLUENT_LOGGER_CALLS = {"setCause", "addArgument", "addKeyValue", "log"}
RBAC_PATH = "core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt"
SQL_HELPER_PATH = "app-bot/src/main/kotlin/com/example/bot/logging/SqlThrowableLogging.kt"
RBAC_FINGERPRINT_PACKAGE = "com.example.bot.security.rbac"
SQL_HELPER_PACKAGE = "com.example.bot.logging"
SQL_SAFE_HELPERS = {"warnSqlSafe", "errorSqlSafe"}
CRITICAL_SQL_PATHS = {
    "core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt",
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt",
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt",
    "app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt",
    SQL_HELPER_PATH,
}
RBAC_SYMBOL_CONTRACT_VALIDATED = False
SQL_SAFE_CALLER_VALIDATED: set[str] = set()


def typed_binding_types(tokens: list[KotlinToken]) -> dict[str, list[str]]:
    bindings: dict[str, list[str]] = {}
    for index, token in enumerate(tokens):
        if token.kind != "identifier":
            continue
        cursor = skip_newlines(tokens, index + 1)
        if cursor >= len(tokens) or tokens[cursor].text != ":":
            continue
        cursor = skip_newlines(tokens, cursor + 1)
        type_tokens: list[KotlinToken] = []
        depth = 0
        while cursor < len(tokens):
            current = tokens[cursor]
            if current.text in {"<", "(", "["}:
                depth += 1
            elif current.text in {">", ")", "]"}:
                if depth == 0:
                    break
                depth -= 1
            if depth == 0 and current.text in {",", "=", "->", "{"}:
                break
            if current.kind == "newline" and depth == 0:
                break
            type_tokens.append(current)
            cursor += 1
        type_name = token_text(type_tokens)
        if type_name:
            bindings.setdefault(token.text, []).append(type_name)
    return bindings


def call_identity(call: KotlinCall) -> tuple[str | None, str]:
    return call.receiver, call.name


def exact_assignment_call(
    tokens: list[KotlinToken],
    binding: str,
    expected_receiver: str | None,
    expected_name: str,
) -> bool:
    matching = [expression for name, expression in assignments(tokens) if name == binding]
    if len(matching) != 1:
        return False
    expression = normalized_expression_tokens(matching[0])
    calls = [call for call in kotlin_calls(expression) if call.end_index == len(expression) - 1]
    return len(calls) == 1 and call_identity(calls[0]) == (expected_receiver, expected_name)


def declarations_named(tokens: list[KotlinToken], name: str) -> list[KotlinFunctionDeclaration]:
    return [declaration for declaration in kotlin_function_declarations(tokens) if declaration.name == name]


def validate_rbac_fingerprint_symbol(tokens: list[KotlinToken], source: str) -> None:
    global RBAC_SYMBOL_CONTRACT_VALIDATED
    if kotlin_package(tokens) != RBAC_FINGERPRINT_PACKAGE:
        reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint package changed")
    imports = kotlin_imports(tokens)
    if any(
        imported.endswith(".*")
        or imported.rsplit(".", 1)[-1] == "fingerprint"
        or alias == "fingerprint"
        for imported, alias in imports
    ):
        reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint has an ambiguous imported shadow")
    declarations = declarations_named(tokens, "fingerprint")
    if len(declarations) != 1:
        reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint must have one reviewed declaration")
    declaration = declarations[0]
    expected_parameters = (
        ("idempotencyKey", "String?"),
        ("method", "String"),
        ("path", "String"),
        ("result", "String"),
    )
    if (
        declaration.receiver is not None
        or declaration.parameters != expected_parameters
        or declaration.return_type != "String"
        or declaration.visibility != "private"
        or declaration.brace_depth != 0
    ):
        reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint declaration identity changed")
    shadow_bindings = kotlin_value_bindings(tokens) & {"fingerprint"}
    if shadow_bindings:
        reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint has a value/parameter shadow")
    for needle in (
        'val payload = "rbac|$idempotencyKey|$method|$path|$result"',
        'MessageDigest.getInstance("SHA-256")',
        "Base64.getUrlEncoder().withoutPadding().encodeToString(digest)",
    ):
        if compact(needle) not in compact(source):
            reject("PH-SYMBOL-CONTRACT", "RBAC fingerprint reviewed algorithm changed")
    RBAC_SYMBOL_CONTRACT_VALIDATED = True


REVIEWED_RECEIVER_TYPES: dict[tuple[str, str], str] = {
    ("app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt", "holdRepository"): "BookingHoldRepository",
    ("app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt", "bookingRepository"): "BookingRepository",
    ("app-bot/src/main/kotlin/com/example/bot/di/DefaultPaymentsService.kt", "paymentsRepository"): "PaymentsRepository",
    ("app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt", "bookingState"): "BookingState",
    ("app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt", "bookingService"): "BookingService",
    ("app-bot/src/main/kotlin/com/example/bot/workers/RefundOutboxWorker.kt", "client"): "ProviderRefundClient",
}


def validate_reviewed_projection_symbols(path: str, tokens: list[KotlinToken]) -> None:
    file_contracts = SAFE_RAW_RESULT_PROJECTIONS.get(path)
    if file_contracts is None:
        return
    declarations = kotlin_function_declarations(tokens)
    owners = {contract.owner for contracts in file_contracts.values() for contract in contracts}
    for owner in owners:
        if sum(declaration.name == owner for declaration in declarations) != 1:
            reject("PH-SYMBOL-CONTRACT", f"reviewed result owner is missing or shadowed in {path}: {owner}")

    call_identities = {
        identity
        for contracts in file_contracts.values()
        for contract in contracts
        for pattern in contract.patterns
        for identity in (pattern.terminal, *pattern.required)
    }
    external_callees = {
        name
        for receiver, name in call_identities
        if receiver is not None and receiver != "<expression>"
    }
    if any(declaration.name in external_callees for declaration in declarations):
        reject("PH-SYMBOL-CONTRACT", f"reviewed result callee is locally shadowed in {path}")

    typed = typed_binding_types(tokens)
    for (contract_path, receiver), expected_type in REVIEWED_RECEIVER_TYPES.items():
        if contract_path != path:
            continue
        if typed.get(receiver) != [expected_type]:
            reject("PH-SYMBOL-CONTRACT", f"reviewed result receiver identity changed in {path}: {receiver}")
        if any(name == receiver for name, _ in assignments(tokens)):
            reject("PH-SYMBOL-CONTRACT", f"reviewed result receiver is reassigned in {path}: {receiver}")

    if path == "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt":
        if not exact_assignment_call(tokens, "paymentsService", "koin", "get"):
            reject("PH-SYMBOL-CONTRACT", "payment route service binding is not the reviewed Koin PaymentsService")
        expression = next(expression for name, expression in assignments(tokens) if name == "paymentsService")
        if "PaymentsService" not in token_text(expression):
            reject("PH-SYMBOL-CONTRACT", "payment route service binding lost its exact type argument")
    elif path == "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt":
        normalized = token_text(tokens)
        if "valpaymentsServicebyinject<PaymentsFinalizeService>()" not in normalized:
            reject("PH-SYMBOL-CONTRACT", "finalize route service binding identity changed")
        if any(name == "paymentsService" for name, _ in assignments(tokens)):
            reject("PH-SYMBOL-CONTRACT", "finalize route service binding is shadowed")

    unqualified_contracts = {
        name for receiver, name in call_identities if receiver is None
    }
    expected_member_counts = {
        "tryPersistResult": 1,
        "attemptHold": 1,
        "attemptConfirm": 1,
    }
    imports = kotlin_imports(tokens)
    for name in unqualified_contracts:
        count = sum(declaration.name == name for declaration in declarations)
        if name in expected_member_counts:
            if count != expected_member_counts[name]:
                reject("PH-SYMBOL-CONTRACT", f"reviewed member result call is shadowed in {path}: {name}")
        elif name == "withContext":
            if count != 0 or ("kotlinx.coroutines.withContext", None) not in imports:
                reject("PH-SYMBOL-CONTRACT", "withContext result call is not the reviewed coroutine symbol")
        elif name == "runCatching":
            if count != 0 or name in kotlin_value_bindings(tokens):
                reject("PH-SYMBOL-CONTRACT", "runCatching result call is locally shadowed")

    if any(name == "insertIgnore" for _, name in call_identities):
        if ("org.jetbrains.exposed.sql.insertIgnore", None) not in imports:
            reject("PH-SYMBOL-CONTRACT", "insertIgnore result call lost its reviewed import")
        if declarations_named(tokens, "insertIgnore") or "insertIgnore" in kotlin_value_bindings(tokens):
            reject("PH-SYMBOL-CONTRACT", "insertIgnore result call is locally shadowed")


def is_raw_identifier(token: KotlinToken) -> bool:
    return token.kind == "identifier" and normalized_identifier(token.text) in RAW_KEY_IDENTIFIERS


def string_interpolations(token: KotlinToken) -> list[str]:
    if token.kind not in {"string", "raw_string"}:
        return []
    value = token.text
    expressions: list[str] = []
    index = 0
    while index < len(value):
        if value[index] != "$":
            index += 1
            continue
        if token.kind == "string":
            backslashes = 0
            cursor = index - 1
            while cursor >= 0 and value[cursor] == "\\":
                backslashes += 1
                cursor -= 1
            if backslashes % 2 == 1:
                index += 1
                continue
        cursor = index + 1
        if cursor < len(value) and (value[cursor] == "_" or value[cursor].isalpha()):
            end = cursor + 1
            while end < len(value) and (value[end] == "_" or value[end].isalnum()):
                end += 1
            expressions.append(value[cursor:end])
            index = end
            continue
        if cursor < len(value) and value[cursor] == "{":
            end = value.find("}", cursor + 1)
            if end >= 0:
                expressions.append(value[cursor + 1 : end])
                index = end + 1
                continue
        index += 1
    return expressions


def string_identifiers(token: KotlinToken) -> set[str]:
    return {
        identifier
        for expression in string_interpolations(token)
        for identifier in re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", expression)
    }


def contains_direct_raw_header_read(tokens: tuple[KotlinToken, ...] | list[KotlinToken]) -> bool:
    has_header_name = any(
        token.kind in {"string", "raw_string"} and token.text.lower() in RAW_HEADER_NAMES
        for token in tokens
    )
    has_header_access = any(
        token.kind == "identifier" and token.text.lower() in {"header", "headers", "queryparameters"}
        for token in tokens
    )
    return has_header_name and has_header_access


def tainted_occurrences(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> list[int]:
    occurrences: list[int] = []
    for index, token in enumerate(tokens):
        if token.kind == "identifier" and token.text in tainted:
            occurrences.append(index)
        elif token.kind in {"string", "raw_string"} and string_identifiers(token) & tainted:
            occurrences.append(index)
    return occurrences


# Unknown calls preserve raw-key taint. These path-, lexical-owner-, receiver-,
# and callee-scoped contracts identify reviewed result objects whose listed
# projections cannot contain the request idempotency key. Names alone are never
# sufficient: a fake local refund()/result.idempotent pair does not match.
SAFE_RAW_RESULT_PROJECTIONS: dict[str, dict[str, tuple[ReviewedResultContract, ...]]] = {
    "app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt": {
        "result": (
            ReviewedResultContract(
                "hold",
                (ReviewedCallPattern(("holdRepository", "createHold")),),
                (("error",),),
            ),
        ),
        "booked": (
            ReviewedResultContract(
                "confirm",
                (ReviewedCallPattern(("bookingRepository", "confirmFromHold")),),
                (("error",),),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/di/DefaultPaymentsService.kt": {
        "outcome": (
            ReviewedResultContract(
                "refund",
                (ReviewedCallPattern(("paymentsRepository", "executeRefundIdempotently")),),
                (("reason",), ("toServiceException",)),
            ),
            ReviewedResultContract(
                "cancelIdempotently",
                (ReviewedCallPattern(("paymentsRepository", "executeCancelIdempotently")),),
                (("reason",), ("toServiceException",)),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/payments/finalize/DefaultPaymentsFinalizeService.kt": {
        "stored": (
            ReviewedResultContract(
                "finalize",
                (ReviewedCallPattern((None, "tryPersistResult")),),
                (("status",),),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt": {
        "result": (
            ReviewedResultContract(
                "bookingA3Routes",
                (
                    ReviewedCallPattern(("bookingState", "hold")),
                    ReviewedCallPattern(("bookingState", "confirm")),
                    ReviewedCallPattern(("bookingState", "plusOne")),
                ),
                (
                    ("cached",),
                    ("booking", "id"),
                    ("booking", "status"),
                    ("booking", "tableId"),
                    ("booking", "eventId"),
                    ("booking", "clubId"),
                    ("booking", "guestCount"),
                ),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt": {
        "result": (
            ReviewedResultContract(
                "registerCancelRefundHandlers",
                (
                    ReviewedCallPattern(("paymentsService", "cancel")),
                    ReviewedCallPattern(("paymentsService", "refund")),
                ),
                (("idempotent",),),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt": {
        "result": (
            ReviewedResultContract(
                "paymentsFinalizeRoutes",
                (ReviewedCallPattern(("paymentsService", "finalize")),),
                (("paymentStatus",),),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt": {
        "holdResult": (
            ReviewedResultContract(
                "attemptHold",
                (
                    ReviewedCallPattern(
                        (None, "withContext"),
                        (("bookingService", "hold"),),
                    ),
                ),
                (("class", "simpleName"),),
            ),
            ReviewedResultContract(
                "processBookingFlow",
                (ReviewedCallPattern((None, "attemptHold")),),
                (("class", "simpleName"),),
            ),
        ),
        "confirmResult": (
            ReviewedResultContract(
                "attemptConfirm",
                (
                    ReviewedCallPattern(
                        (None, "withContext"),
                        (("bookingService", "confirm"),),
                    ),
                ),
                (("class", "simpleName"),),
            ),
            ReviewedResultContract(
                "continueBookingFlow",
                (ReviewedCallPattern((None, "attemptConfirm")),),
                (("class", "simpleName"),),
            ),
        ),
    },
    "app-bot/src/main/kotlin/com/example/bot/workers/RefundOutboxWorker.kt": {
        "outcome": (
            ReviewedResultContract(
                "processMessage",
                (
                    ReviewedCallPattern(
                        ("<expression>", "getOrElse"),
                        ((None, "runCatching"), ("client", "send")),
                    ),
                ),
                (("retryAfter",),),
            ),
        ),
    },
    "core-data/src/main/kotlin/com/example/bot/data/repo/PaymentsRepositoryImpl.kt": {
        "inserted": (
            ReviewedResultContract(
                "markPaymentFullyRefunded",
                (ReviewedCallPattern(("PaymentRefundsTable", "insertIgnore")),),
                (("insertedCount",),),
            ),
            ReviewedResultContract(
                "claimAction",
                (ReviewedCallPattern(("PaymentActionsTable", "insertIgnore")),),
                (("insertedCount",),),
            ),
            ReviewedResultContract(
                "insertInternalRefundAction",
                (ReviewedCallPattern(("PaymentActionsTable", "insertIgnore")),),
                (("insertedCount",),),
            ),
        ),
    },
}


def raw_reference_is_reviewed_projection(
    path: str,
    identifier: str,
    projection: tuple[str, ...],
    reviewed_bindings: set[str],
) -> bool:
    if identifier not in reviewed_bindings:
        return False
    contracts = SAFE_RAW_RESULT_PROJECTIONS.get(path, {}).get(identifier)
    if contracts is None:
        return False
    allowed = tuple(prefix for contract in contracts for prefix in contract.projections)
    return any(projection[: len(prefix)] == prefix for prefix in allowed)


def raw_occurrence_is_reviewed_projection(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    index: int,
    tainted: set[str],
    reviewed_bindings: set[str],
) -> bool:
    token = tokens[index]
    if occurrence_is_presence_only(tokens, index):
        return True
    if token.kind in {"string", "raw_string"}:
        found_tainted = False
        for expression in string_interpolations(token):
            for match in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", expression):
                identifier = match.group(1)
                if identifier not in tainted:
                    continue
                found_tainted = True
                suffix = expression[match.end() :]
                projection = tuple(
                    re.findall(r"(?:\?\.|\.|::)\s*([A-Za-z_][A-Za-z0-9_]*)", suffix)
                )
                if not raw_reference_is_reviewed_projection(
                    path,
                    identifier,
                    projection,
                    reviewed_bindings,
                ):
                    return False
        return found_tainted
    if token.kind != "identifier":
        return False
    projection: list[str] = []
    cursor = index + 1
    while cursor < len(tokens):
        if tokens[cursor].kind == "newline":
            cursor += 1
            continue
        if tokens[cursor].text not in {".", "?.", "::"}:
            break
        cursor = skip_newlines(list(tokens), cursor + 1)
        if cursor >= len(tokens) or tokens[cursor].kind != "identifier":
            break
        projection.append(tokens[cursor].text)
        cursor += 1
    return raw_reference_is_reviewed_projection(
        path,
        token.text,
        tuple(projection),
        reviewed_bindings,
    )


def occurrence_is_in_terminating_lambda(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    occurrence: int,
) -> bool:
    stack: list[int] = []
    regions: list[tuple[int, int]] = []
    for index, token in enumerate(tokens):
        if token.kind in {"string", "raw_string", "char"}:
            continue
        if token.text == "{":
            stack.append(index)
        elif token.text == "}" and stack:
            regions.append((stack.pop(), index))
    for opening, closing in regions:
        if not (opening < occurrence < closing):
            continue
        if any(
            token.kind == "identifier" and token.text in {"return", "throw"}
            for token in tokens[opening + 1 : closing]
        ):
            return True
    return False


def propagating_occurrences(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> list[int]:
    return [
        index
        for index in tainted_occurrences(tokens, tainted)
        if not occurrence_is_in_terminating_lambda(tokens, index)
    ]


def occurrence_is_presence_only(tokens: tuple[KotlinToken, ...] | list[KotlinToken], index: int) -> bool:
    token = tokens[index]
    if token.kind in {"string", "raw_string"}:
        return False
    cursor = index + 1
    if cursor < len(tokens) and tokens[cursor].text in (".", "?."):
        cursor += 1
        return cursor < len(tokens) and tokens[cursor].kind == "identifier" and tokens[cursor].text in PRESENCE_METHODS
    if cursor + 1 < len(tokens) and tokens[cursor].text in ("==", "!="):
        return tokens[cursor + 1].kind == "identifier" and tokens[cursor + 1].text == "null"
    return False


def expression_is_presence_only(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> bool:
    occurrences = tainted_occurrences(tokens, tainted)
    return bool(occurrences) and all(occurrence_is_presence_only(tokens, index) for index in occurrences)


def normalized_expression_tokens(tokens: tuple[KotlinToken, ...] | list[KotlinToken]) -> list[KotlinToken]:
    expression = [token for token in tokens if token.kind != "newline"]
    while expression and expression[0].text == "(":
        closing = matching_token(expression, 0)
        if closing != len(expression) - 1:
            break
        expression = expression[1:closing]
    return expression


def expression_is_exact_rbac_fingerprint(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> bool:
    """The only raw-key declassifier is the reviewed one-way RBAC audit fingerprint."""
    if path != RBAC_PATH or not RBAC_SYMBOL_CONTRACT_VALIDATED:
        return False
    expression = normalized_expression_tokens(tokens)
    calls = kotlin_calls(expression)
    for call in calls:
        if (
            call.name != "fingerprint"
            or call.receiver is not None
            or call.start_index != 0
            or call.end_index != len(expression) - 1
        ):
            continue
        arguments = split_top_level_tokens(call.body)
        if len(arguments) != 4:
            continue
        if not tainted_occurrences(arguments[0], tainted):
            continue
        if any(tainted_occurrences(argument, tainted) for argument in arguments[1:]):
            continue
        return True
    return False


def expression_is_raw_tainted(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
    reviewed_bindings: set[str],
) -> bool:
    if contains_direct_raw_header_read(tokens):
        return True
    if expression_is_presence_only(tokens, tainted):
        return False
    occurrences = tainted_occurrences(tokens, tainted)
    return any(
        not raw_occurrence_is_reviewed_projection(
            path,
            tokens,
            index,
            tainted,
            reviewed_bindings,
        )
        for index in occurrences
    )


def expression_propagates_raw_alias(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
    reviewed_bindings: set[str],
) -> bool:
    if contains_direct_raw_header_read(tokens):
        return True
    if expression_is_presence_only(tokens, tainted) or expression_is_exact_rbac_fingerprint(path, tokens, tainted):
        return False
    # Unknown helpers are not sanitizers: an expression depending on raw key material
    # remains tainted unless it is one of the two explicit declassifiers above.
    occurrences = propagating_occurrences(tokens, tainted)
    return any(
        not raw_occurrence_is_reviewed_projection(
            path,
            tokens,
            index,
            tainted,
            reviewed_bindings,
        )
        for index in occurrences
    )


def assignment_expression(tokens: list[KotlinToken], equals: int) -> tuple[KotlinToken, ...]:
    cursor = equals + 1
    expression: list[KotlinToken] = []
    depth = 0
    while cursor < len(tokens):
        current = tokens[cursor]
        if current.text in ("(", "{", "["):
            depth += 1
        elif current.text in (")", "}", "]"):
            if depth == 0:
                break
            depth -= 1
        if current.kind == "newline" and depth == 0:
            previous = next((item for item in reversed(expression) if item.kind != "newline"), None)
            lookahead = skip_newlines(tokens, cursor + 1)
            following = tokens[lookahead] if lookahead < len(tokens) else None
            continuation = {".", "?.", "?:", ",", "+", "-", "*", "/", "%", "&&", "||"}
            if previous is None or previous.text in continuation or (
                following is not None and following.text in continuation | {"[", "("}
            ):
                cursor += 1
                continue
            break
        if current.text == ";" and depth == 0:
            break
        expression.append(current)
        cursor += 1
    return tuple(expression)


def assignments(tokens: list[KotlinToken]) -> list[tuple[str, tuple[KotlinToken, ...]]]:
    found: list[tuple[str, tuple[KotlinToken, ...]]] = []
    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text not in {"val", "var"}:
            continue
        cursor = skip_newlines(tokens, index + 1)
        if cursor >= len(tokens) or tokens[cursor].kind != "identifier":
            continue
        name = tokens[cursor].text
        cursor += 1
        depth = 0
        equals = -1
        while cursor < len(tokens):
            current = tokens[cursor]
            if current.text in ("(", "{", "["):
                depth += 1
            elif current.text in (")", "}", "]"):
                depth = max(0, depth - 1)
            elif current.text == "=" and depth == 0:
                equals = cursor
                break
            elif (current.kind == "newline" or current.text == ";") and depth == 0:
                break
            cursor += 1
        if equals < 0:
            continue
        found.append((name, assignment_expression(tokens, equals)))

    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text in {"val", "var"}:
            continue
        equals = skip_newlines(tokens, index + 1)
        if equals >= len(tokens) or tokens[equals].text != "=":
            continue
        before = index - 1
        newline_before = False
        while before >= 0 and tokens[before].kind == "newline":
            newline_before = True
            before -= 1
        previous = tokens[before] if before >= 0 else None
        if previous is not None and previous.text in {".", "?.", "(", "[", ",", ":", "@"}:
            continue
        if previous is not None and previous.kind == "identifier" and previous.text in {"val", "var"}:
            continue
        if not newline_before and previous is not None and previous.text not in {";", "{", "}", "->", ")", "else", "do"}:
            continue
        found.append((token.text, assignment_expression(tokens, equals)))
    return found


def propagate_raw_taint(
    path: str,
    assignment_edges: list[tuple[str, tuple[KotlinToken, ...]]],
    seeds: set[str],
    reviewed_bindings: set[str],
) -> set[str]:
    tainted = set(seeds)
    changed = True
    while changed:
        changed = False
        for name, expression in assignment_edges:
            if name in tainted:
                continue
            if expression_propagates_raw_alias(
                path,
                expression,
                tainted,
                reviewed_bindings,
            ):
                tainted.add(name)
                changed = True
    return tainted


def reviewed_raw_result_bindings(
    path: str,
    owner: str | None,
    assignment_edges: list[tuple[str, tuple[KotlinToken, ...]]],
    provisional_taint: set[str],
) -> set[str]:
    specs = SAFE_RAW_RESULT_PROJECTIONS.get(path, {})
    reviewed: set[str] = set()
    for name, contracts in specs.items():
        owner_contracts = [contract for contract in contracts if contract.owner == owner]
        if not owner_contracts:
            continue
        tainting_assignments = [
            expression
            for assigned_name, expression in assignment_edges
            if assigned_name == name
            and (
                contains_direct_raw_header_read(expression)
                or bool(tainted_occurrences(expression, provisional_taint))
            )
        ]
        if not tainting_assignments:
            continue
        all_assignments_reviewed = True
        for expression in tainting_assignments:
            normalized = normalized_expression_tokens(expression)
            calls = kotlin_calls(normalized)
            terminal_calls = {
                (call.receiver, call.name)
                for call in calls
                if call.end_index == len(normalized) - 1
            }
            call_identities = {(call.receiver, call.name) for call in calls}
            if not any(
                pattern.terminal in terminal_calls
                and all(required_call in call_identities for required_call in pattern.required)
                for contract in owner_contracts
                for pattern in contract.patterns
            ):
                all_assignments_reviewed = False
                break
        if all_assignments_reviewed:
            reviewed.add(name)
    return reviewed


def raw_taint_state(
    path: str,
    owner: str | None,
    tokens: list[KotlinToken],
) -> tuple[set[str], set[str]]:
    seeds = {token.text for token in tokens if is_raw_identifier(token)}
    assignment_edges = assignments(tokens)
    provisional = propagate_raw_taint(path, assignment_edges, seeds, set())
    reviewed = reviewed_raw_result_bindings(path, owner, assignment_edges, provisional)
    return propagate_raw_taint(path, assignment_edges, seeds, reviewed), reviewed


def typed_throwable_identifiers(tokens: list[KotlinToken]) -> set[str]:
    identifiers: set[str] = set()
    for index, token in enumerate(tokens):
        if token.kind != "identifier":
            continue
        cursor = index + 1
        while cursor < len(tokens) and tokens[cursor].kind == "newline":
            cursor += 1
        if cursor >= len(tokens) or tokens[cursor].text != ":":
            continue
        type_tokens: list[KotlinToken] = []
        cursor += 1
        while cursor < len(tokens) and tokens[cursor].text not in {",", ")", "=", "->", "{"}:
            if tokens[cursor].kind != "newline":
                type_tokens.append(tokens[cursor])
            cursor += 1
        if any(
            type_token.kind == "identifier" and re.search(r"(?:Throwable|Exception)$", type_token.text)
            for type_token in type_tokens
        ):
            identifiers.add(token.text)

    for index, token in enumerate(tokens):
        if token.kind != "identifier" or token.text not in {"exception", "onFailure", "getOrElse"}:
            continue
        cursor = index + 1
        if token.text == "exception":
            while cursor < len(tokens) and tokens[cursor].text != "{":
                cursor += 1
        else:
            while cursor < len(tokens) and tokens[cursor].kind == "newline":
                cursor += 1
        if cursor >= len(tokens) or tokens[cursor].text != "{":
            continue
        end = matching_token(tokens, cursor)
        arrow = next((position for position in range(cursor + 1, end) if tokens[position].text == "->"), None)
        if arrow is None:
            continue
        lambda_parameters = [
            candidate.text
            for candidate in tokens[cursor + 1 : arrow]
            if candidate.kind == "identifier"
        ]
        if lambda_parameters:
            identifiers.add(lambda_parameters[-1])
    return identifiers


def throwable_tainted_identifiers(path: str, tokens: list[KotlinToken]) -> set[str]:
    tainted = typed_throwable_identifiers(tokens)
    assignment_edges = assignments(tokens)
    changed = True
    while changed:
        changed = False
        for name, expression in assignment_edges:
            if name in tainted:
                continue
            if expression_propagates_throwable_alias(path, expression, tainted):
                tainted.add(name)
                changed = True
    return tainted


def expression_is_exact_safe_sql_summary(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> bool:
    """Declassify only the reviewed Throwable -> SafeSqlFailure projection."""
    expression = normalized_expression_tokens(tokens)
    for call in kotlin_calls(expression):
        if call.end_index != len(expression) - 1:
            continue
        if path == SQL_HELPER_PATH and call.name == "safeSqlFailureOrNull":
            prefix = expression[: call.start_index]
            if (
                len(prefix) == 2
                and prefix[0].kind == "identifier"
                and prefix[0].text in tainted
                and prefix[1].text in {".", "?."}
            ):
                return True
        if (
            path == "core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt"
            and call.name == "classify"
            and call.receiver == "DbErrorClassifier"
            and call.start_index == 2
            and expression[0].text == "DbErrorClassifier"
            and expression[1].text == "."
            and bool(tainted_occurrences(call.body, tainted))
        ):
            return True
    return False


def expression_propagates_throwable_alias(
    path: str,
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    tainted: set[str],
) -> bool:
    if expression_is_exact_safe_sql_summary(path, tokens, tainted):
        return False
    occurrences = propagating_occurrences(tokens, tainted)
    if not occurrences:
        return False
    # Unknown wrappers preserve Throwable taint. Only direct class-name projections
    # and the explicit SafeSqlFailure sanitizer above declassify diagnostic data.
    return any(not throwable_occurrence_is_safe_projection(tokens, index) for index in occurrences)


def throwable_occurrence_is_safe_projection(
    tokens: tuple[KotlinToken, ...] | list[KotlinToken],
    index: int,
) -> bool:
    if tokens[index].kind != "identifier":
        return False
    tail = [token.text for token in tokens[index + 1 : index + 6] if token.kind != "newline"]
    return tail[:4] in (
        [".", "javaClass", ".", "simpleName"],
        [".", "javaClass", ".", "name"],
    )


def is_observability_sink(call: KotlinCall) -> bool:
    if call.name in LOGGER_CALLS and call.receiver is not None:
        return True
    if call.receiver == "MDC" and call.name in {"put", "putCloseable"}:
        return True
    return call.name in TRACING_CALLS or (call.receiver is not None and call.name in FLUENT_LOGGER_CALLS)


def is_exception_message_sink(call: KotlinCall) -> bool:
    return (call.receiver is None and call.name in {"require", "check", "requireNotNull", "checkNotNull", "error"}) or (
        call.name[:1].isupper()
        and (
            call.name.endswith("Exception")
            or call.name in {"Error", "AssertionError", "StackOverflowError", "OutOfMemoryError"}
        )
    )


def is_sql_critical_path(path: str, source: str) -> bool:
    if path in CRITICAL_SQL_PATHS:
        return True
    if "/routes/" in path and Path(path).name.startswith("Payments"):
        return True
    sql_aware = any(marker in source for marker in ("SQLException", "PSQLException", "safeSqlFailureOrNull"))
    return sql_aware and ("logger." in source or "log." in source)


def is_sql_logger_sink(path: str, call: KotlinCall) -> bool:
    if call.receiver is not None and call.name in FLUENT_LOGGER_CALLS:
        return True
    if call.name in LOGGER_CALLS and call.receiver is not None:
        return True
    return path == SQL_HELPER_PATH and call.name in LOGGER_CALLS and call.receiver is None


def call_is_in_non_sql_helper_branch(path: str, tokens: list[KotlinToken], call: KotlinCall) -> bool:
    if path != SQL_HELPER_PATH or call.name not in LOGGER_CALLS or call.receiver is not None:
        return False
    for index in range(call.start_index - 1, -1, -1):
        if tokens[index].kind != "identifier" or tokens[index].text != "if":
            continue
        condition_open = skip_newlines(tokens, index + 1)
        if condition_open >= len(tokens) or tokens[condition_open].text != "(":
            continue
        condition_close = matching_token(tokens, condition_open)
        condition = [
            token.text
            for token in tokens[condition_open + 1 : condition_close]
            if token.kind != "newline"
        ]
        if condition not in (["sqlFailure", "==", "null"], ["null", "==", "sqlFailure"]):
            continue
        branch_open = skip_newlines(tokens, condition_close + 1)
        if branch_open >= len(tokens) or tokens[branch_open].text != "{":
            continue
        branch_close = matching_token(tokens, branch_open)
        if branch_open < call.start_index < branch_close:
            return True
    return False


SQL_SAFE_CALLER_BINDINGS: dict[str, tuple[str, str]] = {
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt": ("KotlinLogging", "logger"),
    "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt": ("KotlinLogging", "logger"),
    "app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt": ("LoggerFactory", "getLogger"),
}


def validate_sql_helper_declarations(tokens: list[KotlinToken]) -> None:
    if kotlin_package(tokens) != SQL_HELPER_PACKAGE:
        reject("PH-SYMBOL-CONTRACT", "SQL-safe logging helper package changed")
    declarations = [
        declaration
        for declaration in kotlin_function_declarations(tokens)
        if declaration.name in SQL_SAFE_HELPERS
    ]
    expected = {
        (
            "warnSqlSafe",
            "KLogger",
            (("throwable", "Throwable"), ("message", "()->String")),
        ),
        (
            "errorSqlSafe",
            "KLogger",
            (("throwable", "Throwable"), ("message", "()->String")),
        ),
        (
            "errorSqlSafe",
            "Logger",
            (("message", "String"), ("argument", "Any"), ("throwable", "Throwable")),
        ),
    }
    actual = {
        (declaration.name, declaration.receiver, declaration.parameters)
        for declaration in declarations
        if declaration.visibility == "internal" and declaration.brace_depth == 0
    }
    if actual != expected or len(declarations) != len(expected):
        reject("PH-SYMBOL-CONTRACT", "SQL-safe logging helper declaration identity changed")
    if kotlin_value_bindings(tokens) & SQL_SAFE_HELPERS:
        reject("PH-SYMBOL-CONTRACT", "SQL-safe logging helper has a callable value/parameter shadow")


def validate_sql_safe_caller(path: str, tokens: list[KotlinToken]) -> None:
    calls = [call for call in kotlin_calls(tokens) if call.name in SQL_SAFE_HELPERS]
    if not calls:
        return
    binding_contract = SQL_SAFE_CALLER_BINDINGS.get(path)
    if binding_contract is None:
        reject("PH-SYMBOL-CONTRACT", f"SQL-safe helper used from an unreviewed boundary: {path}")
    imports = kotlin_imports(tokens)
    for helper_name in {call.name for call in calls}:
        expected_import = (f"{SQL_HELPER_PACKAGE}.{helper_name}", None)
        if expected_import not in imports:
            reject("PH-SYMBOL-CONTRACT", f"SQL-safe helper import identity changed in {path}: {helper_name}")
    if any(
        alias in SQL_SAFE_HELPERS
        or (imported.endswith(".*") and imported.startswith(f"{SQL_HELPER_PACKAGE}."))
        for imported, alias in imports
    ):
        reject("PH-SYMBOL-CONTRACT", f"SQL-safe helper import is ambiguous in {path}")
    if any(declaration.name in SQL_SAFE_HELPERS for declaration in kotlin_function_declarations(tokens)):
        reject("PH-SYMBOL-CONTRACT", f"SQL-safe helper is locally shadowed in {path}")
    if kotlin_value_bindings(tokens) & SQL_SAFE_HELPERS:
        reject("PH-SYMBOL-CONTRACT", f"SQL-safe helper callable binding is shadowed in {path}")
    expected_receiver, expected_factory = binding_contract
    if not exact_assignment_call(tokens, "logger", expected_receiver, expected_factory):
        reject("PH-SYMBOL-CONTRACT", f"SQL-safe logger receiver identity changed in {path}")
    SQL_SAFE_CALLER_VALIDATED.add(path)


def is_exact_safe_sql_helper_call(path: str, call: KotlinCall) -> bool:
    return (
        path in SQL_SAFE_CALLER_VALIDATED
        and call.receiver == "logger"
        and call.name in SQL_SAFE_HELPERS
    )


def kotlin_function_scopes(tokens: list[KotlinToken]) -> list[list[KotlinToken]]:
    """Split a file into non-overlapping function scopes plus non-function top-level tokens."""
    regions: list[tuple[int, int]] = []
    index = 0
    while index < len(tokens):
        if tokens[index].kind != "identifier" or tokens[index].text != "fun":
            index += 1
            continue
        after_fun = skip_newlines(tokens, index + 1)
        if after_fun < len(tokens) and tokens[after_fun].kind == "identifier" and tokens[after_fun].text == "interface":
            index += 1
            continue
        parameter_open = index + 1
        while parameter_open < len(tokens) and tokens[parameter_open].text != "(":
            if tokens[parameter_open].text in {";", "{", "}"}:
                break
            parameter_open += 1
        if parameter_open >= len(tokens) or tokens[parameter_open].text != "(":
            index += 1
            continue
        parameter_close = matching_token(tokens, parameter_open)
        cursor = skip_newlines(tokens, parameter_close + 1)
        body_start = -1
        while cursor < len(tokens):
            if tokens[cursor].text in {"=", "{"}:
                body_start = cursor
                break
            if tokens[cursor].text in {";", "}"}:
                break
            if tokens[cursor].kind == "newline":
                following = skip_newlines(tokens, cursor + 1)
                if following < len(tokens) and (
                    tokens[following].text == "}"
                    or (
                        tokens[following].kind == "identifier"
                        and tokens[following].text
                        in {"fun", "class", "object", "interface", "val", "var", "override", "private", "public", "protected", "internal"}
                    )
                ):
                    break
            cursor += 1
        if body_start < 0:
            index += 1
            continue
        if tokens[body_start].text == "{":
            end = matching_token(tokens, body_start)
        else:
            expression = assignment_expression(tokens, body_start)
            if not expression:
                index += 1
                continue
            end_offset = expression[-1].offset
            end = next(
                candidate
                for candidate in range(body_start + 1, len(tokens))
                if tokens[candidate].offset == end_offset
            )
        regions.append((index, end))
        index = end + 1

    scopes = [tokens[start : end + 1] for start, end in regions]
    if regions:
        top_level: list[KotlinToken] = []
        region_index = 0
        for token_index, token in enumerate(tokens):
            while region_index < len(regions) and token_index > regions[region_index][1]:
                region_index += 1
            if region_index < len(regions) and regions[region_index][0] <= token_index <= regions[region_index][1]:
                continue
            top_level.append(token)
        if top_level:
            scopes.append(top_level)
    elif tokens:
        scopes.append(tokens)
    return scopes


def inspect_production_kotlin(path: str, source: str) -> None:
    tokens = lex_kotlin(source)
    if path == RBAC_PATH:
        validate_rbac_fingerprint_symbol(tokens, source)
    if path == SQL_HELPER_PATH:
        validate_sql_helper_declarations(tokens)
    validate_reviewed_projection_symbols(path, tokens)
    validate_sql_safe_caller(path, tokens)
    for scope in kotlin_function_scopes(tokens):
        calls = kotlin_calls(scope)
        raw_tainted, reviewed_raw_bindings = raw_taint_state(path, scope_function_name(scope), scope)
        for call in calls:
            if (is_observability_sink(call) or is_exception_message_sink(call)) and expression_is_raw_tainted(
                path,
                call.body,
                raw_tainted,
                reviewed_raw_bindings,
            ):
                reject("PH-LOG-RAW-SINK", f"raw idempotency material reaches {call.name} in {path}")
        for index, token in enumerate(scope):
            if token.kind == "identifier" and token.text == "throw":
                thrown = assignment_expression(scope, index)
                if expression_is_raw_tainted(
                    path,
                    thrown,
                    raw_tainted,
                    reviewed_raw_bindings,
                ):
                    reject("PH-LOG-RAW-SINK", f"raw idempotency material reaches throw in {path}")

        if not is_sql_critical_path(path, source):
            continue
        throwable_tainted = throwable_tainted_identifiers(path, scope)
        for call in calls:
            unsafe_occurrences = [
                index
                for index in tainted_occurrences(call.body, throwable_tainted)
                if not throwable_occurrence_is_safe_projection(call.body, index)
            ]
            if call.name in SQL_SAFE_HELPERS and unsafe_occurrences:
                if is_exact_safe_sql_helper_call(path, call):
                    continue
                reject("PH-LOG-SQL-BOUNDARY", f"unreviewed SQL-safe helper receives Throwable in {path}")
            if (
                is_sql_logger_sink(path, call)
                and unsafe_occurrences
                and not call_is_in_non_sql_helper_branch(path, scope, call)
            ):
                reject("PH-LOG-SQL-BOUNDARY", f"original Throwable reaches {call.name} in {path}")


def production_kotlin_inventory() -> list[str]:
    command = [
        "git",
        "-C",
        str(ROOT),
        "ls-files",
        "-z",
        "--cached",
        "--others",
        "--exclude-standard",
        "--",
        ":(glob)**/src/main/**/*.kt",
    ]
    try:
        result = subprocess.run(command, check=False, capture_output=True)
    except OSError as error:
        reject("PH-INVENTORY", f"cannot execute git production inventory: {error}")
    if result.returncode != 0:
        reject("PH-INVENTORY", f"git production inventory failed with exit {result.returncode}")
    raw_paths = [entry for entry in result.stdout.split(b"\0") if entry]
    paths = [os.fsdecode(entry) for entry in raw_paths]
    paths = [path for path in paths if not path.startswith("buildSrc/")]
    if not paths:
        reject("PH-INVENTORY", "production Kotlin inventory is empty")
    if len(paths) != len(set(paths)):
        reject("PH-INVENTORY", "production Kotlin inventory contains duplicate paths")
    for path in paths:
        candidate = Path(path)
        if candidate.is_absolute() or ".." in candidate.parts:
            reject("PH-INVENTORY", f"unsafe production Kotlin path: {path}")
        resolved = ROOT / candidate
        if resolved.is_symlink() or not resolved.is_file():
            reject("PH-INVENTORY", f"production Kotlin path is missing or not regular: {path}")
    return sorted(paths)


def parse_kotlin_annotation(tokens: list[KotlinToken], opening_index: int) -> tuple[str | None, int]:
    cursor = skip_newlines(tokens, opening_index + 1)
    names: list[str] = []
    while cursor < len(tokens) and tokens[cursor].kind == "identifier":
        names.append(tokens[cursor].text)
        cursor = skip_newlines(tokens, cursor + 1)
        if cursor >= len(tokens) or tokens[cursor].text != ".":
            break
        cursor = skip_newlines(tokens, cursor + 1)
    if cursor < len(tokens) and tokens[cursor].text == "(":
        cursor = matching_token(tokens, cursor) + 1
    return (".".join(names) if names else None), cursor


@dataclass(frozen=True)
class KotlinTestClassDeclaration:
    name: str
    annotations: frozenset[str]
    declaration_index: int
    body_start: int
    body_end: int
    top_level: bool


@dataclass(frozen=True)
class KotlinTestMethodDeclaration:
    name: str
    annotations: frozenset[str]
    declaration_index: int
    owner_index: int | None
    direct_member: bool


def kotlin_test_declarations(
    source: str,
) -> tuple[
    str | None,
    set[tuple[str, str | None]],
    list[KotlinTestClassDeclaration],
    list[KotlinTestMethodDeclaration],
]:
    """Parse real class/function annotations from the token stream, never from literals/comments."""
    tokens = lex_kotlin(source)
    package_name = kotlin_package(tokens)
    imports = kotlin_imports(tokens)
    raw_classes: list[tuple[int, int, int, str, frozenset[str]]] = []
    method_declarations: list[tuple[int, str, set[str]]] = []
    pending_annotations: list[str] = []
    modifiers = {
        "public",
        "private",
        "protected",
        "internal",
        "final",
        "open",
        "abstract",
        "sealed",
        "data",
        "inner",
        "enum",
        "annotation",
        "value",
        "expect",
        "actual",
        "external",
        "override",
        "lateinit",
        "tailrec",
        "vararg",
        "suspend",
        "inline",
        "noinline",
        "crossinline",
        "reified",
        "operator",
        "infix",
    }
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.kind == "newline":
            index += 1
            continue
        if token.text == "@":
            annotation, index = parse_kotlin_annotation(tokens, index)
            if annotation is not None:
                pending_annotations.append(annotation)
            continue
        if token.kind == "identifier" and token.text in modifiers:
            index += 1
            continue
        if token.kind == "identifier" and token.text in {"class", "object", "interface"}:
            cursor = skip_newlines(tokens, index + 1)
            if cursor < len(tokens) and tokens[cursor].kind == "identifier":
                class_name = tokens[cursor].text
                body_cursor = cursor + 1
            elif token.text == "object" and cursor < len(tokens) and tokens[cursor].text == "{":
                class_name = f"<anonymous-object@{token.offset}>"
                body_cursor = cursor
            else:
                class_name = ""
                body_cursor = cursor
            while body_cursor < len(tokens) and tokens[body_cursor].text not in {"{", ";"}:
                body_cursor += 1
            if class_name and body_cursor < len(tokens) and tokens[body_cursor].text == "{":
                raw_classes.append(
                    (
                        index,
                        body_cursor,
                        matching_token(tokens, body_cursor),
                        class_name,
                        frozenset(pending_annotations),
                    ),
                )
            pending_annotations.clear()
            index = cursor + 1
            continue
        if token.kind == "identifier" and token.text == "fun":
            declaration = next(
                (item for item in kotlin_function_declarations(tokens) if item.declaration_index == index),
                None,
            )
            if declaration is not None:
                method_declarations.append((index, declaration.name, set(pending_annotations)))
            pending_annotations.clear()
            index += 1
            continue
        if pending_annotations:
            pending_annotations.clear()
        index += 1
    function_regions = [
        (declaration.body_start, declaration.body_end, declaration.declaration_index)
        for declaration in kotlin_function_declarations(tokens)
        if declaration.body_start is not None and declaration.body_end is not None
    ]
    classes: list[KotlinTestClassDeclaration] = []
    for declaration_index, body_start, body_end, name, annotations in raw_classes:
        enclosing_class = any(
            other_start < declaration_index < other_end
            for _, other_start, other_end, _, _ in raw_classes
        )
        enclosing_function = any(
            start is not None and end is not None and start < declaration_index < end
            for start, end, _ in function_regions
        )
        classes.append(
            KotlinTestClassDeclaration(
                name=name,
                annotations=annotations,
                declaration_index=declaration_index,
                body_start=body_start,
                body_end=body_end,
                top_level=not enclosing_class and not enclosing_function,
            ),
        )

    methods: list[KotlinTestMethodDeclaration] = []
    for declaration_index, method_name, annotations in method_declarations:
        containing = [
            class_declaration
            for class_declaration in classes
            if class_declaration.body_start < declaration_index < class_declaration.body_end
        ]
        owner = min(containing, key=lambda item: item.body_end - item.body_start) if containing else None
        enclosing_other_function = any(
            start is not None
            and end is not None
            and start < declaration_index < end
            and function_index != declaration_index
            for start, end, function_index in function_regions
        )
        methods.append(
            KotlinTestMethodDeclaration(
                name=method_name,
                annotations=frozenset(annotations),
                declaration_index=declaration_index,
                owner_index=owner.declaration_index if owner is not None else None,
                direct_member=owner is not None and not enclosing_other_function,
            ),
        )
    return package_name, imports, classes, methods


def has_junit_annotation(
    annotations: frozenset[str],
    imports: set[tuple[str, str | None]],
    simple_name: str,
) -> bool:
    qualified = f"org.junit.jupiter.api.{simple_name}"
    return qualified in annotations or (
        simple_name in annotations and (qualified, None) in imports
    )


def require_active_test_declarations(
    path: str,
    expected_fqcn: str,
    required_methods: tuple[str, ...],
) -> None:
    package_name, imports, classes, methods = kotlin_test_declarations(read(path))
    expected_package, _, expected_class_name = expected_fqcn.rpartition(".")
    if package_name != expected_package:
        reject("PH-TEST-MISSING", f"required test package changed: {expected_fqcn}")
    matching_classes = [
        declaration
        for declaration in classes
        if declaration.top_level and declaration.name == expected_class_name
    ]
    if len(matching_classes) != 1:
        reject("PH-TEST-MISSING", f"required top-level test class is missing or ambiguous: {expected_fqcn}")
    expected_class = matching_classes[0]
    if has_junit_annotation(expected_class.annotations, imports, "Disabled"):
        reject("PH-TEST-DISABLED", f"required test class is disabled: {expected_fqcn}")
    for method in required_methods:
        declarations = [
            declaration
            for declaration in methods
            if declaration.name == method
            and declaration.owner_index == expected_class.declaration_index
            and declaration.direct_member
        ]
        if len(declarations) != 1 or not has_junit_annotation(declarations[0].annotations, imports, "Test"):
            reject("PH-TEST-MISSING", f"active required test is missing: {method}")
        if has_junit_annotation(declarations[0].annotations, imports, "Disabled"):
            reject("PH-TEST-DISABLED", f"required test method is disabled: {method}")


def validate_testcontainers_contract() -> None:
    catalog_path = "gradle/libs.versions.toml"
    runtime_path = "scripts/verify-payment-hardening-runtime.sh"
    catalog = read(catalog_path)
    version_match = re.search(
        r'^testcontainers\s*=\s*"([0-9]+)\.([0-9]+)\.([0-9]+)"\s*$',
        catalog,
        flags=re.MULTILINE,
    )
    if version_match is None:
        reject("PH-TESTCONTAINERS", "Testcontainers catalog version is missing or ambiguous")
    version = tuple(int(part) for part in version_match.groups())
    if version < (1, 21, 4) or version >= (2, 0, 0):
        reject("PH-TESTCONTAINERS", f"Testcontainers must remain in the compatible 1.21.4+ 1.x line: {version}")
    for alias in ("testcontainers-junit", "testcontainers-postgresql"):
        pattern = re.compile(
            rf'^{re.escape(alias)}\s*=\s*\{{[^\n]*version\.ref\s*=\s*"testcontainers"[^\n]*\}}\s*$',
            flags=re.MULTILINE,
        )
        if pattern.search(catalog) is None:
            reject("PH-TESTCONTAINERS", f"{alias} is not bound to the unified Testcontainers version")
    if re.search(r'org\.testcontainers:[A-Za-z0-9_.-]+:[0-9]', catalog):
        reject("PH-TESTCONTAINERS", "Testcontainers module has a direct mixed version")

    runtime = read(runtime_path)
    expected_runtime = """#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

exec python3 \\
  "$ROOT_DIR/scripts/validate-payment-hardening.py" \\
  --run-required-runtime \\
  "$ROOT_DIR"
"""
    if runtime != expected_runtime:
        reject("PH-RUNTIME-CONTRACT", "payment hardening helper must remain the exact thin runtime wrapper")
    runtime_mode = safe_regular_file(ROOT, runtime_path).stat().st_mode
    if not runtime_mode & 0o111:
        reject("PH-RUNTIME-CONTRACT", "payment hardening helper is not executable")


validate_testcontainers_contract()


PG_MIGRATION = "core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
H2_MIGRATION = "core-data/src/main/resources/db/migration/h2/V056__atomic_booking_refunds.sql"
LOGGING_TEST = "app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt"
SQL_LOGGING_TEST = "app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt"
PAYMENTS_TEST = "app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt"
MIGRATION_TEST = "app-bot/src/test/kotlin/com/example/bot/tools/QuiescedMigrateMainTest.kt"
MIGRATION_LOG_CONFIG = "app-bot/src/main/resources/quiesced-migration-logback.xml"
MIGRATION_BOUNDARY = "app-bot/src/main/dist/bin/app-bot-migrate"
INVARIANTS_DOC = "docs/invariants.md"

PROTECTED_INPUTS = (
    "Dockerfile",
    "docker-compose.yml",
    ".github/workflows/lint.yml",
    ".github/workflows/deploy-ssh.yml",
    ".github/workflows/db-migrate.yml",
    "scripts/selfcheck-quality-gates.sh",
    "scripts/validate-payment-hardening.py",
    "scripts/verify-payment-hardening-runtime.sh",
    "scripts/validate-workflow-yaml.rb",
    "scripts/validate-quiesced-deployment.sh",
    "scripts/deploy/quiesced-release.sh",
    "scripts/deploy/remote-compose-release.sh",
    "app-bot/build.gradle.kts",
    "app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt",
    MIGRATION_TEST,
    MIGRATION_LOG_CONFIG,
    MIGRATION_BOUNDARY,
    "core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt",
    "core-data/src/test/kotlin/com/example/bot/data/db/FlywayConfigTest.kt",
    "docs/dr.md",
    "docs/runtime-db-resiliency.md",
    "docs/ops/release-rollback.md",
    PG_MIGRATION,
    H2_MIGRATION,
    LOGGING_TEST,
    SQL_LOGGING_TEST,
    PAYMENTS_TEST,
    INVARIANTS_DOC,
)
for protected_input in PROTECTED_INPUTS:
    read(protected_input)


def validate_migration_logging_contract() -> None:
    raw_config = read(MIGRATION_LOG_CONFIG)
    if "${" in raw_config:
        reject("PH-LOG-MIGRATION", "migration logging configuration contains variable substitution")
    try:
        config = ET.fromstring(raw_config)
    except ET.ParseError as error:
        reject("PH-LOG-MIGRATION", f"migration logging configuration is malformed: {error}")
    if config.tag != "configuration" or config.attrib:
        reject("PH-LOG-MIGRATION", "migration logging root contract changed")
    children = list(config)
    if [child.tag for child in children] != [
        "statusListener",
        "contextName",
        "appender",
        "logger",
        "logger",
        "root",
    ]:
        reject("PH-LOG-MIGRATION", "migration logging configuration gained an uncontrolled element")

    status_listener, context_name, appender, flyway_logger, safe_logger, root_logger = children
    if status_listener.attrib != {"class": "ch.qos.logback.core.status.NopStatusListener"}:
        reject("PH-LOG-MIGRATION", "migration Logback status output is not suppressed")
    if (context_name.text or "").strip() != "quiesced-migration" or context_name.attrib:
        reject("PH-LOG-MIGRATION", "migration Logback context identity changed")
    if appender.attrib != {
        "name": "MIGRATION_SAFE_CONSOLE",
        "class": "ch.qos.logback.core.ConsoleAppender",
    }:
        reject("PH-LOG-MIGRATION", "migration logger does not have the sole safe console appender")
    appender_children = list(appender)
    if len(appender_children) != 1 or appender_children[0].tag != "encoder" or appender_children[0].attrib:
        reject("PH-LOG-MIGRATION", "migration console encoder contract changed")
    encoder_children = list(appender_children[0])
    if (
        len(encoder_children) != 1
        or encoder_children[0].tag != "pattern"
        or encoder_children[0].attrib
        or (encoder_children[0].text or "").strip() != "%msg%n%nopex"
    ):
        reject("PH-LOG-MIGRATION", "migration console may render uncontrolled exception data")
    if flyway_logger.attrib != {
        "name": "org.flywaydb",
        "level": "OFF",
        "additivity": "false",
    } or list(flyway_logger):
        reject("PH-LOG-MIGRATION", "Flyway internal logging is not fully disabled for migration")
    if safe_logger.attrib != {
        "name": "QuiescedMigrations",
        "level": "INFO",
        "additivity": "false",
    }:
        reject("PH-LOG-MIGRATION", "safe migration logger identity changed")
    safe_children = list(safe_logger)
    if len(safe_children) != 1 or safe_children[0].tag != "appender-ref" or safe_children[0].attrib != {
        "ref": "MIGRATION_SAFE_CONSOLE",
    }:
        reject("PH-LOG-MIGRATION", "safe migration logger gained an uncontrolled sink")
    if root_logger.attrib != {"level": "OFF"} or list(root_logger):
        reject("PH-LOG-MIGRATION", "migration root logger is not fully disabled")

    migration_main = read("app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt")
    for required in (
        '.loggers("slf4j")',
        "LogManager.getLogManager().reset()",
        'JulLogger.getLogger("org.postgresql")',
        'check(System.getProperty("logback.configurationFile") == MIGRATION_LOG_CONFIG)',
        'check(System.getProperty("logback.statusListenerClass") == MIGRATION_STATUS_LISTENER)',
        'check(encoder.pattern == "%msg%n%nopex")',
        "catch (failure: Exception)",
        "catch (_: Error)",
        "private const val EXIT_FAILURE = 1",
        '"migration-safe:v=1 event=started"',
        '"migration-safe:v=1 event=completed applied=',
        '"migration-safe:v=1 event=failed phase=',
    ):
        if required not in migration_main:
            reject("PH-LOG-MIGRATION", f"migration safe-output boundary lacks: {required}")
    if re.search(
        r"\bthrow\b|\.message\b|printStackTrace|\.(?:trace|debug|info|warn|error)\([^)]*(?:failure|throwable)",
        migration_main,
        flags=re.IGNORECASE,
    ):
        reject("PH-LOG-MIGRATION", "migration failure path exposes raw throwable data")

    app_build = read("app-bot/build.gradle.kts")
    for required in (
        "-Dlogback.configurationFile=$quiescedMigrationLogConfig",
        "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
        "application launcher must not use the migration-only logging configuration",
    ):
        if required not in app_build:
            reject("PH-LOG-MIGRATION", f"migration launcher logging contract lacks: {required}")
    application_start = app_build.find("application {")
    migration_launcher_start = app_build.find("val quiescedMigrationStartScripts")
    if application_start < 0 or migration_launcher_start <= application_start:
        reject("PH-LOG-MIGRATION", "application and migration launcher boundaries are ambiguous")
    if "quiescedMigrationLogConfig" in app_build[application_start:migration_launcher_start]:
        reject("PH-LOG-MIGRATION", "normal application launcher inherits migration-only logging")

    migration_boundary = read(MIGRATION_BOUNDARY)
    for required in (
        "unset JAVA_TOOL_OPTIONS",
        "unset JDK_JAVA_OPTIONS",
        "unset _JAVA_OPTIONS",
        "unset JAVA_OPTS",
        "unset APP_BOT_MIGRATE_OPTS",
        "unset APP_BOT_MIGRATE_JAVA_OPTS",
        "JAVA_HOME=/opt/java/openjdk",
        "private_launcher=/opt/app/bin/app-bot-migrate-java",
        'exec "$private_launcher"',
    ):
        if required not in migration_boundary:
            reject("PH-LOG-MIGRATION", f"fixed migration boundary lacks: {required}")
    if re.search(r"QuiescedMigrateMainKt|EngineMain|ApplicationKt|\$@|\$\*", migration_boundary):
        reject("PH-LOG-MIGRATION", "fixed migration boundary bypasses its private launcher contract")
    boundary_mode = safe_regular_file(ROOT, MIGRATION_BOUNDARY).stat().st_mode
    if not boundary_mode & 0o111:
        reject("PH-LOG-MIGRATION", "fixed migration boundary is not executable")


validate_migration_logging_contract()

for documentation_path, required_text in (
    ("docs/invariants.md", "Public migration-only wrapper удаляет JVM option injection variables"),
    ("docs/dr.md", "никогда не пересылается в GitHub Actions"),
    ("docs/ops/release-rollback.md", "реконструированные canonical `migration-safe:v=1` events"),
    ("docs/runtime-db-resiliency.md", "pgjdbc JUL сброшен"),
):
    if required_text not in read(documentation_path):
        reject("PH-DOC-MIGRATION-LOG", f"migration log redaction contract is missing: {documentation_path}")

pg = strip_comments(read(PG_MIGRATION), sql=True)
h2 = strip_comments(read(H2_MIGRATION), sql=True)

for vendor, sql in (("PostgreSQL", pg), ("H2", h2)):
    require(
        sql,
        "CREATE INDEX IF NOT EXISTS payments_booking_idx" if vendor == "PostgreSQL" else "CREATE INDEX payments_booking_idx",
        "PH-MIGRATION-INDEX",
        f"{vendor} payments_booking_idx is missing",
    )
    require(
        sql,
        "ON payments (booking_id)" if vendor == "H2" else 'ON "${flyway:defaultSchema}".payments (booking_id)',
        "PH-MIGRATION-INDEX",
        f"{vendor} payments booking index target changed",
    )
    require(sql, "CREATE INDEX payment_refunds_booking_idx", "PH-MIGRATION-INDEX", f"{vendor} payment_refunds_booking_idx is missing")
    require(
        sql,
        "ON payment_refunds (booking_id)" if vendor == "H2" else 'ON "${flyway:defaultSchema}".payment_refunds (booking_id)',
        "PH-MIGRATION-INDEX",
        f"{vendor} refund booking index target changed",
    )

    terminal = check_body(sql, "payment_actions_typed_refund_terminal_check")
    require(terminal, "refund_result_amount_minor IS NOT NULL", "PH-MIGRATION-TYPED", f"{vendor} typed OK permits NULL result")
    require(terminal, "refund_result_amount_minor >= 0", "PH-MIGRATION-TYPED", f"{vendor} typed OK permits a negative result")
    require(
        terminal,
        "refund_result_amount_minor = 0 AND refund_source_kind IS NULL",
        "PH-MIGRATION-TYPED",
        f"{vendor} typed zero OK source contract changed",
    )
    require(
        terminal,
        "refund_result_amount_minor > 0 AND refund_source_kind = 'ATOMIC_ACTION'",
        "PH-MIGRATION-TYPED",
        f"{vendor} typed positive OK source contract changed",
    )

    source_shape = check_body(sql, "payment_refunds_source_shape_check")
    for needle, description in (
        ("source_kind IN ('ATOMIC_ACTION', 'LEGACY_ACTION')", "action source kinds changed"),
        ("source_action = 'REFUND'", "non-refund action can source a refund"),
        ("source_status = 'OK'", "non-OK action can source a refund"),
        ("source_kind = 'PAYMENT_STATUS'", "payment source shape is missing"),
        ("source_status = 'REFUNDED'", "non-refunded payment can source a refund"),
    ):
        require(source_shape, needle, "PH-MIGRATION-SOURCE", f"{vendor} {description}")
    for needle, description in (
        ("CONSTRAINT payment_refunds_action_source_fk", "action source FK is missing"),
        ("CONSTRAINT payment_refunds_payment_source_fk", "payment source FK is missing"),
        ("UNIQUE (id, booking_id, refund_source_kind, action, status, refund_result_amount_minor)", "action composite source key changed"),
        ("UNIQUE (id, booking_id, status, amount_minor)", "payment composite source key changed"),
        ("CREATE UNIQUE INDEX payment_refunds_action_idx", "action source uniqueness is missing"),
        ("CREATE UNIQUE INDEX payment_refunds_source_payment_idx", "payment source uniqueness is missing"),
    ):
        require(sql, needle, "PH-MIGRATION-SOURCE", f"{vendor} {description}")

require(pg, "NEW.reason::numeric <= 0", "PH-MIGRATION-LEGACY", "PostgreSQL legacy non-positive trigger guard is missing")
require(pg, "WHEN reason::numeric <= 0 THEN false", "PH-MIGRATION-LEGACY", "PostgreSQL legacy non-positive backfill guard is missing")
require(h2, "WHEN CAST(reason AS DECIMAL(20, 0)) <= 0 THEN FALSE", "PH-MIGRATION-LEGACY", "H2 legacy non-positive backfill guard is missing")
for sql, vendor in ((pg, "PostgreSQL"), (h2, "H2")):
    require(sql, "MALFORMED_LEGACY_REFUND_ACTION", "PH-MIGRATION-LEGACY", f"{vendor} malformed legacy block is missing")
    require(
        sql,
        "refund_result_amount_minor IS NULL AS has_malformed_action" if vendor == "H2" else "refund_result_amount_minor IS NULL",
        "PH-MIGRATION-LEGACY",
        f"{vendor} unresolved legacy action is not blocked",
    )

function_names = (
    "payment_refund_block_booking",
    "payment_refund_prepare_legacy_action",
    "payment_refund_track_source",
    "payment_refund_record_action_source",
    "payment_refund_reject_mutation",
    "payment_refund_record_payment_status",
)
qualified_schema = '"${flyway:defaultSchema}"'
for name in function_names:
    pattern = re.compile(
        rf"create\s+or\s+replace\s+function\s+{re.escape(qualified_schema)}\.{name}\s*\([^$]*?\)\s*(?:returns\s+\w+\s+)?language\s+plpgsql\s+set\s+search_path\s*=\s*pg_catalog\s*,\s*{re.escape(qualified_schema)}\s+as\s+\$\$",
        flags=re.IGNORECASE,
    )
    if pattern.search(pg) is None:
        reject("PH-MIGRATION-SEARCH-PATH", f"PostgreSQL function {name} lacks a pinned trusted search_path")

for relation in ("payment_actions", "payment_refunds", "payments", "booking_refund_reconciliation"):
    if re.search(rf"\b(?:from|into|update|join|on)\s+{relation}\b", pg, flags=re.IGNORECASE):
        reject("PH-MIGRATION-QUALIFICATION", f"PostgreSQL financial relation is caller-resolved: {relation}")
require(
    pg,
    'PERFORM "${flyway:defaultSchema}".payment_refund_block_booking',
    "PH-MIGRATION-QUALIFICATION",
    "PostgreSQL helper call is caller-resolved",
)

production_paths = production_kotlin_inventory()
for production_path in production_paths:
    inspect_production_kotlin(production_path, read(production_path))
print(f"payment-hardening-inventory: {len(production_paths)} production Kotlin files")

critical_files = {
    "rbac": "core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt",
    "webhook": "core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt",
    "booking-finalize": "app-bot/src/main/kotlin/com/example/bot/routes/BookingFinalizeRoutes.kt",
    "booking-template": "app-bot/src/main/kotlin/com/example/bot/promo/BookingTemplateService.kt",
    "payment-finalize-service": "app-bot/src/main/kotlin/com/example/bot/payments/finalize/DefaultPaymentsFinalizeService.kt",
    "db-transactions": "core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt",
    "cancel-refund-routes": "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt",
    "finalize-routes": "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt",
    "json-error-pages": "app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt",
    "sql-logging-helper": "app-bot/src/main/kotlin/com/example/bot/logging/SqlThrowableLogging.kt",
    "exposed-filter": "app-bot/src/main/kotlin/com/example/bot/logging/DenySensitiveTurboFilter.kt",
    "logback": "app-bot/src/main/resources/logback.xml",
}
production = {name: strip_comments(read(path)) for name, path in critical_files.items()}

for name in ("rbac", "booking-finalize", "booking-template"):
    if re.search(r"\bMDC\.", production[name]):
        reject("PH-LOG-RAW-SINK", f"manual MDC scope remains in {name}")
if "IDEMPOTENCY_MDC_KEY" in production["webhook"]:
    reject("PH-LOG-RAW-SINK", "webhook raw idempotency MDC alias was restored")
if "maskIdemKey" in production["payment-finalize-service"]:
    reject("PH-LOG-RAW-SINK", "partial idempotency-key masking was restored")
if "ex.message" in production["db-transactions"]:
    reject("PH-LOG-SQL-BOUNDARY", "DB transaction logger exposes SQL exception message")
require(production["payment-finalize-service"], "idempotencyKeyPresent={}", "PH-LOG-RAW-SINK", "payment finalize presence-only logging is missing")
require(production["db-transactions"], "sqlState={} cause={}", "PH-LOG-SQL-BOUNDARY", "DB transaction safe diagnostic contract changed")

for name in ("cancel-refund-routes", "finalize-routes"):
    require(production[name], "errorSqlSafe(unexpected)", "PH-LOG-SQL-BOUNDARY", f"SQL-aware unexpected logging is missing in {name}")
require(production["finalize-routes"], "warnSqlSafe(throwable)", "PH-LOG-SQL-BOUNDARY", "SQL-aware finalize callback logging is missing")
require(production["json-error-pages"], "logger.errorSqlSafe", "PH-LOG-SQL-BOUNDARY", "JsonErrorPages logs the original SQL throwable")
require(production["json-error-pages"], "safeSqlFailureOrNull() == null", "PH-LOG-SQL-BOUNDARY", "BadRequest SQL detail can reach the response")

helper = production["sql-logging-helper"]
for needle, description in (
    ("current is SQLException", "SQL graph inspector is missing"),
    ("current.cause", "SQL graph inspector does not traverse causes"),
    ("current.suppressed", "SQL graph inspector does not traverse suppressed failures"),
    ("IdentityHashMap", "SQL graph inspector is not cycle-safe"),
    ("SQL_STATE_PATTERN", "SQLState is not allowlisted"),
):
    require(helper, needle, "PH-LOG-SQL-BOUNDARY", description)
if re.search(r"\.message\b", helper):
    reject("PH-LOG-SQL-BOUNDARY", "SQL-safe helper reads throwable message")

exposed_filter = production["exposed-filter"]
require(
    exposed_filter,
    "logger?.name == EXPOSED_LOGGER && "
    "(containsSqlThrowable(t, params) || isUnsafeExposedTransactionFailure(level, format))",
    "PH-LOG-EXPOSED",
    "Exposed SQL transaction rejection is not wired into the active filter decision",
)
for needle, description in (
    ('EXPOSED_LOGGER = "Exposed"', "Exposed logger scope is missing"),
    ("containsSqlThrowable", "Exposed SQL throwable inspection is missing"),
    ("isUnsafeExposedTransactionFailure", "Exposed 0.49 transaction failure shape is not covered"),
    ("FilterReply.DENY", "unsafe Exposed output is not denied"),
):
    require(exposed_filter, needle, "PH-LOG-EXPOSED", description)
require(production["logback"], "DenySensitiveTurboFilter", "PH-LOG-EXPOSED", "Exposed protection is not registered in Logback")

required_tests = {
    LOGGING_TEST: (
        "com.example.bot.logging.SensitiveIdempotencyLoggingTest",
        (
            "booking finalize route never serializes raw idempotency key",
            "booking template service never serializes generated idempotency key",
            "rbac audit fingerprint does not expose raw key to json logs",
            "webhook keeps business key but never serializes it through mdc",
            "payments finalize logs presence only for long and short keys",
            "db transaction logs never serialize sql exception detail",
        ),
    ),
    SQL_LOGGING_TEST: (
        "com.example.bot.logging.SqlThrowableLoggingPersistenceTest",
        ("postgres sql throwable never reaches payment route status pages or json logs",),
    ),
    PAYMENTS_TEST: (
        "com.example.bot.payments.PaymentsPersistenceTest",
        (
            "refund explicit zero persists terminal success without mutation",
            "refund explicit zero production RBAC route replays stable public result without mutation",
        ),
    ),
    MIGRATION_TEST: (
        "com.example.bot.tools.QuiescedMigrateMainTest",
        (
            "successful process emits only the fixed safe event schema",
            "connection failure emits category without throwable data",
            "cancellation remains nonzero without raw exception output",
            "fatal error uses fixed nonzero diagnostic instead of escaping",
            "real entrypoint suppresses connection canaries and stack traces",
        ),
    ),
}
for path, (fqcn, methods) in required_tests.items():
    require_active_test_declarations(path, fqcn, methods)

logging_test = strip_comments(read(LOGGING_TEST))
for needle, description in (
    ("LoggingEventCompositeJsonEncoder", "real JSON logging encoder regression is missing"),
    ("SECRET_KEY", "distinctive idempotency logging sentinel is missing"),
    ("ThrowableProxyUtil.asString", "throwable proxy is not inspected"),
    ("eventObject.mdcPropertyMap", "MDC output is not inspected"),
    ("eventObject.argumentArray", "structured arguments are not inspected"),
):
    require(logging_test, needle, "PH-TEST-ENCODER", description)

sql_logging_test = strip_comments(read(SQL_LOGGING_TEST))
for needle, description in (
    ("LoggingEventCompositeJsonEncoder", "PostgreSQL boundary test does not use the JSON encoder"),
    ("DefaultPaymentsService", "PostgreSQL boundary test bypasses the real payment service"),
    ("PaymentsRepositoryImpl", "PostgreSQL boundary test bypasses the real payment repository"),
    ("withRetriedTx", "PostgreSQL boundary test bypasses DbTransactions"),
    ("installJsonErrorPages", "PostgreSQL boundary test bypasses StatusPages"),
    ('"Exposed"', "PostgreSQL boundary test does not capture Exposed output"),
    ("capture.assertClean(SQL_THROWABLE_SENTINEL)", "PostgreSQL boundary test does not reject sensitive Exposed output"),
):
    require(sql_logging_test, needle, "PH-TEST-ENCODER", description)

payments_test = strip_comments(read(PAYMENTS_TEST))
for needle, description in (
    ("assertZeroRefundPersistence", "explicit-zero DB truth table assertion is missing"),
    ('"authorized-zero-rbac"', "explicit-zero authorized Ktor request is missing"),
    ("assertEquals(HttpStatusCode.OK, first.status)", "explicit-zero first HTTP 200 assertion is missing"),
    ("assertTrue(replayBody.idempotent)", "explicit-zero replay assertion is missing"),
    ("configureLoggingAndRequestId()", "explicit-zero Ktor regression bypasses production request IDs"),
    ("installJsonErrorPages()", "explicit-zero Ktor regression bypasses production JsonErrorPages"),
    ("install(RbacPlugin)", "explicit-zero Ktor regression bypasses production RBAC"),
    ("ExposedUserRepository(database)", "explicit-zero Ktor regression does not use persisted users"),
    ("ExposedUserRoleRepository(database)", "explicit-zero Ktor regression does not use persisted club roles"),
    (
        'call.request.headers["X-Telegram-Id"]',
        "explicit-zero Ktor regression does not use the production principal input",
    ),
    (
        'header("X-Telegram-Id", principalTelegramId.toString())',
        "explicit-zero Ktor client does not supply the authenticated principal",
    ),
    ("TelegramPrincipal(", "explicit-zero Ktor regression does not construct a real principal"),
    ('"app.APP_PROFILE" to "STAGE"', "explicit-zero Ktor regression is not prod-like"),
    ('"app.RBAC_ENABLED" to "true"', "explicit-zero Ktor regression disables RBAC"),
    ("assertEquals(HttpStatusCode.Forbidden, denied.status)", "explicit-zero Ktor regression lacks an authorization denial"),
    ("Json.decodeFromString<ApiError>(denied.bodyAsText())", "forbidden response is not decoded as the production ApiError"),
    ("assertEquals(ErrorCodes.forbidden, deniedBody.code)", "forbidden ApiError code is not exact"),
    ("assertEquals(HttpStatusCode.Forbidden.value, deniedBody.status)", "forbidden ApiError status is not exact"),
    ("assertEquals(deniedRequestId, deniedBody.requestId)", "forbidden ApiError request ID is not exact"),
    ("assertNull(deniedBody.message)", "forbidden ApiError message contract is not exact"),
    ("assertNull(deniedBody.details)", "forbidden ApiError details contract is not exact"),
    ('val expectedMismatch = mapOf("error" to "idempotency payload mismatch")', "409 mismatch envelope is not exact"),
    ("Json.decodeFromString<Map<String, String>>(amountMismatch.bodyAsText())", "amount mismatch JSON is not decoded exactly"),
    ("Json.decodeFromString<Map<String, String>>(modeMismatch.bodyAsText())", "mode mismatch JSON is not decoded exactly"),
    ("assertEquals(0, actionRowsCount(deniedKey))", "unauthorized explicit-zero mutation is not rejected"),
):
    require(payments_test, needle, "PH-TEST-ZERO", description)

invariants = read(INVARIANTS_DOC)
for needle, description in (
    ("Явный `amountMinor=0`", "explicit-zero public contract is undocumented"),
    ("terminal `REFUND/OK`", "explicit-zero terminal action contract is undocumented"),
    ("`refund_source_kind = NULL`", "explicit-zero source-kind contract is undocumented"),
    ("`payment_refunds` row не создаётся", "explicit-zero no-ledger contract is undocumented"),
    ("RBAC authorization выполняется до финансовой операции", "authorization-before-mutation contract is undocumented"),
):
    require(invariants, needle, "PH-DOC-ZERO", description)

print("payment-hardening-mode: STRUCTURAL_ONLY_NON_AUTHORITATIVE")
print("payment-hardening-contract: OK")
