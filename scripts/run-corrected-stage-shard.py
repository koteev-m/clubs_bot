#!/usr/bin/env python3
"""Run an exhaustive, disjoint class partition of the unmodified corrected suite.

Each invocation imports the complete suite in a fresh process, preserving its
module fixtures and unittest class/method ordering. No test selector is accepted.
New methods are discovered automatically; new classes require an explicit map.
"""

import argparse
import ast
from collections import Counter
import importlib.util
import json
from pathlib import Path
import sys
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / "scripts/tests/test_corrected_stage_release.py"
MAPPING = ROOT / "scripts/corrected-stage-shards.json"
SHARDS = ("authority", "executor", "import-root", "support")


class ContractError(ValueError):
    pass


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ContractError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def flatten(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from flatten(item)
        elif isinstance(item, unittest.TestCase):
            yield item
        else:
            raise ContractError("discovery returned a non-unittest test")


def partition(tests, mapping):
    if not isinstance(mapping, dict) or set(mapping) != set(SHARDS):
        raise ContractError("missing or unknown shard")
    ids = [test.id() for test in tests]
    if not ids or len(ids) != len(set(ids)):
        raise ContractError("empty or duplicate discovered test IDs")
    owners = {}
    for shard, classes in mapping.items():
        if not isinstance(classes, list) or not classes:
            raise ContractError(f"empty or invalid class mapping: {shard}")
        for name in classes:
            if not isinstance(name, str) or not name.isidentifier():
                raise ContractError("invalid mapped class")
            if name in owners:
                raise ContractError(f"duplicate class coverage: {name}")
            owners[name] = shard
    discovered = {type(test).__name__ for test in tests}
    if set(owners) != discovered:
        raise ContractError(
            f"class coverage mismatch: unmapped={sorted(discovered - set(owners))}; "
            f"undiscovered={sorted(set(owners) - discovered)}"
        )
    groups = {shard: [] for shard in SHARDS}
    for test in tests:
        method = getattr(test, test._testMethodName)
        if any(getattr(target, attribute, False)
               for target in (type(test), method)
               for attribute in ("__unittest_skip__", "__unittest_expecting_failure__")):
            raise ContractError(f"skip/expectedFailure is forbidden: {test.id()}")
        groups[owners[type(test).__name__]].append(test)
    assigned = [test.id() for group in groups.values() for test in group]
    if any(not group for group in groups.values()) or Counter(assigned) != Counter(ids):
        raise ContractError("shards must cover every discovered test exactly once")
    return groups


def discover(path=SUITE):
    source = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    # Cross-check source declarations against unittest discovery. Reject duplicate
    # definitions and custom discovery that could silently hide a source test.
    if any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == "load_tests"
           for node in source.body):
        raise ContractError("custom load_tests is not supported")
    declared = []
    for node in source.body:
        if isinstance(node, ast.ClassDef):
            declared.extend(f"{node.name}.{method.name}" for method in node.body
                            if isinstance(method, (ast.FunctionDef, ast.AsyncFunctionDef))
                            and method.name.startswith("test_"))
    if len(declared) != len(set(declared)):
        raise ContractError("duplicate source test definition")
    spec = importlib.util.spec_from_file_location("corrected_stage_suite", path)
    module = importlib.util.module_from_spec(spec)
    # unittest resolves setUpModule/tearDownModule through sys.modules, not the
    # TestCase objects. Match a normal import so fixtures cannot be bypassed.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    loader = unittest.TestLoader()
    tests = list(flatten(loader.loadTestsFromModule(module)))
    actual = [f"{type(test).__name__}.{test._testMethodName}" for test in tests]
    if loader.errors or Counter(actual) != Counter(declared):
        raise ContractError("source/discovered test coverage mismatch")
    if any(type(test).__module__ != module.__name__ for test in tests):
        raise ContractError("unexpected imported test class")
    return tests


class AccountedResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.started_ids = []

    def startTest(self, test):
        self.started_ids.append(test.id())
        super().startTest(test)


def accepted(result, expected_ids):
    return (
        bool(expected_ids)
        and result.wasSuccessful()
        and result.testsRun == len(expected_ids)
        and Counter(result.started_ids) == Counter(expected_ids)
        and not result.skipped
        and not result.expectedFailures
        and not result.unexpectedSuccesses
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shard", nargs="?", choices=SHARDS)
    parser.add_argument("--check", action="store_true", help="validate complete discovery and mapping only")
    args = parser.parse_args()
    if args.check == bool(args.shard):
        parser.error("choose exactly one shard or --check")
    groups = partition(discover(), json.loads(MAPPING.read_text(), object_pairs_hook=unique_object))
    inventory = {name: [test.id() for test in tests] for name, tests in groups.items()}
    print("corrected-stage-shard-inventory:" + json.dumps(inventory, sort_keys=True), flush=True)
    if args.check:
        return 0
    selected = groups[args.shard]
    expected = inventory[args.shard]
    started = time.monotonic()
    result = unittest.TextTestRunner(verbosity=2, resultclass=AccountedResult).run(unittest.TestSuite(selected))
    ok = accepted(result, expected)
    print("corrected-stage-shard-result:" + json.dumps({
        "shard": args.shard, "discovered": sum(map(len, groups.values())),
        "expected": len(expected), "executed": result.testsRun,
        "test_ids": result.started_ids, "seconds": round(time.monotonic() - started, 3),
        "failures": len(result.failures), "errors": len(result.errors),
        "skipped": len(result.skipped), "expected_failures": len(result.expectedFailures),
        "unexpected_successes": len(result.unexpectedSuccesses), "success": ok,
    }, sort_keys=True), flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ContractError, OSError, SyntaxError, json.JSONDecodeError) as error:
        print(f"corrected-stage-shard-contract: {error}", file=sys.stderr)
        raise SystemExit(1)
