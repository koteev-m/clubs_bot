#!/usr/bin/env python3
"""Executable contracts for full corrected-stage coverage and the required lint gate."""

from collections import Counter
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/lint.yml"
VALIDATOR = ROOT / "scripts/validate-lint-workflow.rb"
SELFCHECK = ROOT / "scripts/selfcheck-quality-gates.sh"
spec = importlib.util.spec_from_file_location("sharding", ROOT / "scripts/run-corrected-stage-shard.py")
sharding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sharding)
WORKERS = ["lint-scope", "miniapp", "jvm", "pr-image", "container-smoke", "lint-core"] + ["corrected-" + shard for shard in sharding.SHARDS]


def workflow_model(path=WORKFLOW):
    script = 'require "json"; require "validate-workflow-yaml"; w = WorkflowYamlSafety.safe_load_workflow(File.binread(ARGV[0]), ".github/workflows/lint.yml"); w["on"] = w.delete(true) if w.key?(true); print JSON.generate(w)'
    return json.loads(subprocess.check_output(
        ["ruby", "-I" + str(ROOT / "scripts"), "-e", script, str(path)], text=True
    ))


class ShardCoverageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tests = sharding.discover()
        cls.mapping = json.loads(sharding.MAPPING.read_text(), object_pairs_hook=sharding.unique_object)

    def test_partition_matches_full_discovery_exactly_once_and_preserves_order(self):
        groups = sharding.partition(self.tests, self.mapping)
        expected = Counter(test.id() for test in self.tests)
        self.assertTrue(expected)
        self.assertEqual(expected, Counter(test.id() for group in groups.values() for test in group))
        for shard, group in groups.items():
            self.assertEqual(
                [test.id() for test in self.tests if type(test).__name__ in self.mapping[shard]],
                [test.id() for test in group],
            )

    def test_missing_unknown_empty_and_duplicate_shards_fail_closed(self):
        missing = copy.deepcopy(self.mapping)
        missing.pop("authority")
        unknown = {**self.mapping, "unknown": ["UnknownTest"]}
        empty = {**self.mapping, "authority": []}
        duplicate = copy.deepcopy(self.mapping)
        duplicate["support"].append("AuthorityRegressionTest")
        for mapping in (missing, unknown, empty, duplicate):
            with self.subTest(mapping=mapping), self.assertRaises(sharding.ContractError):
                sharding.partition(self.tests, mapping)
        with self.assertRaises(sharding.ContractError):
            json.loads('{"authority": [], "authority": []}', object_pairs_hook=sharding.unique_object)

    def test_omitted_existing_class_and_new_unmapped_class_fail_closed(self):
        omitted = copy.deepcopy(self.mapping)
        omitted["support"].pop()
        new_class = type("NewUnmappedTest", (unittest.TestCase,), {"test_new": lambda self: None})
        for tests, mapping in ((self.tests, omitted), (self.tests + [new_class("test_new")], self.mapping)):
            with self.subTest(count=len(tests)), self.assertRaises(sharding.ContractError):
                sharding.partition(tests, mapping)

    def test_new_method_of_a_mapped_class_is_automatically_included(self):
        new_method = type("AuthorityRegressionTest", (unittest.TestCase,), {"test_new": lambda self: None})
        test = new_method("test_new")
        groups = sharding.partition(self.tests + [test], self.mapping)
        self.assertEqual(1, sum(item.id() == test.id() for group in groups.values() for item in group))
        self.assertIn(test, groups["authority"])

    def test_missing_or_duplicate_discovered_method_is_rejected(self):
        with self.assertRaises(sharding.ContractError):
            sharding.partition(self.tests + [self.tests[0]], self.mapping)
        # Exercise the real source/discovery comparison, not a hardcoded count.
        for source in (
            "import unittest\nclass A(unittest.TestCase):\n def test_a(self): pass\n def test_a(self): pass\n",
            "import unittest\nclass A(unittest.TestCase):\n def test_a(self): pass\ndel A.test_a\n",
            "import unittest\nclass A(unittest.TestCase):\n def test_a(self): pass\ndef load_tests(*args): return unittest.TestSuite()\n",
        ):
            with self.subTest(source=source), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "suite.py"
                path.write_text(source)
                with self.assertRaises(sharding.ContractError):
                    sharding.discover(path)

    def test_skip_and_expected_failure_decorators_are_rejected(self):
        for decorate in (unittest.skip("must fail"), unittest.expectedFailure):
            method = decorate(lambda self: None)
            case = type("AuthorityRegressionTest", (unittest.TestCase,), {"test_forbidden": method})
            with self.subTest(decorate=decorate), self.assertRaises(sharding.ContractError):
                sharding.partition(self.tests + [case("test_forbidden")], self.mapping)

    def test_module_fixtures_are_executed_and_setup_failure_is_fail_closed(self):
        previous = sys.modules.get("corrected_stage_suite")
        try:
            for broken in (False, True):
                source = textwrap.dedent("""\
                    import unittest
                    events = []
                    def setUpModule():
                        events.append("setup")
                        if BROKEN:
                            raise RuntimeError("synthetic fixture failure")
                    def tearDownModule():
                        events.append("teardown")
                    class A(unittest.TestCase):
                        def test_a(self):
                            self.assertEqual(["setup"], events)
                            events.append("test")
                    """).replace("BROKEN", repr(broken))
                with self.subTest(broken=broken), tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "suite.py"
                    path.write_text(source)
                    tests = sharding.discover(path)
                    expected = [test.id() for test in tests]
                    result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=sharding.AccountedResult).run(
                        unittest.TestSuite(tests)
                    )
                    self.assertEqual(not broken, sharding.accepted(result, expected))
                    self.assertEqual(["setup"] if broken else ["setup", "test", "teardown"],
                                     sys.modules["corrected_stage_suite"].events)
        finally:
            if previous is None:
                sys.modules.pop("corrected_stage_suite", None)
            else:
                sys.modules["corrected_stage_suite"] = previous

    def test_execution_accounting_rejects_failure_error_runtime_skip_and_missing_execution(self):
        def failure(case):
            case.fail("synthetic failure")

        def error(case):
            raise RuntimeError("synthetic error")

        def skipped(case):
            case.skipTest("synthetic runtime skip")

        for method, ok in ((lambda case: None, True), (failure, False), (error, False), (skipped, False)):
            case = type("ExecutionProbe", (unittest.TestCase,), {"test_probe": method})("test_probe")
            result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=sharding.AccountedResult).run(
                unittest.TestSuite([case])
            )
            with self.subTest(method=method):
                self.assertEqual(ok, sharding.accepted(result, [case.id()]))
                self.assertFalse(sharding.accepted(result, [case.id(), "missing.test"]))
                result.started_ids.append(case.id())
                self.assertFalse(sharding.accepted(result, [case.id()]))

    def test_runner_rejects_unknown_or_reduced_selection(self):
        for args in ([], ["unknown"], ["authority", "--check"], ["authority", "test_one"]):
            result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/run-corrected-stage-shard.py"), *args],
                                    capture_output=True, text=True)
            with self.subTest(args=args):
                self.assertEqual(2, result.returncode)
                self.assertNotIn("corrected-executor-snapshot:", result.stdout)

    def test_expected_failure_unexpected_success_and_suppressed_execution_are_rejected(self):
        def fail(case):
            case.fail("synthetic expected failure")

        for method in (fail, lambda case: None):
            case = type("ExpectedFailureProbe", (unittest.TestCase,), {
                "test_probe": unittest.expectedFailure(method)
            })("test_probe")
            result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=sharding.AccountedResult).run(
                unittest.TestSuite([case])
            )
            self.assertFalse(sharding.accepted(result, [case.id()]))
        case = type("SuppressedProbe", (unittest.TestCase,), {
            "test_probe": lambda case: None, "run": lambda case, result: result,
        })("test_probe")
        result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=sharding.AccountedResult).run(
            unittest.TestSuite([case])
        )
        self.assertFalse(sharding.accepted(result, [case.id()]))


class LintScopeTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.repo = Path(tmp.name)
        self.git("init", "--quiet", "-b", "main", "--template=")
        self.style = "miniapp/src/index.css"
        self.protected = "scripts/deploy/release.sh"
        for name in ("README.md", self.style, "miniapp/src/widgets/ToastHost.tsx", self.protected):
            self.write(name, "base")
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null",
                                        "-c", "user.name=Fixture", "-c", "user.email=test@example.invalid",
                                        "-c", "commit.gpgsign=false", *args], cwd=self.repo,
                                       text=True, stderr=subprocess.PIPE).strip()

    def write(self, name, value):
        file = self.repo / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(value + "\n")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def mode(self, base=None, candidate=None, revision=None, ok=True):
        head = self.git("rev-parse", "HEAD")
        env = {**os.environ, "GITHUB_EVENT_NAME": "pull_request" if candidate else "push",
               "LINT_BASE": base or self.base, "GITHUB_SHA": revision or head,
               "LINT_CANDIDATE": candidate or head}
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(ROOT / "scripts/lint-scope.py")],
                                cwd=self.repo, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, ok, result.stderr)
        values = dict(row.split("=", 1) for row in result.stdout.splitlines())
        if ok:
            self.assertEqual(set(values), {"mode", "image"})
        return values

    def test_push_checks_whole_range_and_falls_back_for_unknown_and_renamed_paths(self):
        self.write("README.md", "doc")
        self.commit()
        self.assertEqual({"mode": "docs", "image": "false"}, self.mode())
        self.write("miniapp/src/widgets/ToastHost.tsx", "presentation edit")
        self.commit()
        self.assertEqual({"mode": "ui", "image": "false"}, self.mode())
        self.write(self.style, ".test {}")
        self.commit()
        self.assertEqual({"mode": "ui", "image": "false"}, self.mode())
        self.git("mv", self.protected, "miniapp/README.md")
        self.commit()
        self.write("README.md", "last commit touches only docs")
        self.commit()
        self.assertEqual({"mode": "full", "image": "true"}, self.mode())
        self.assertEqual({"mode": "full", "image": "true"}, self.mode(base="0" * 40))
        self.mode(base="1" * 40, ok=False)
        self.mode(revision=self.base, ok=False)

    def test_domains_and_mixed_changes_use_the_union_of_their_checks(self):
        for path, mode, image in (
            ("docs/guide/new-page.md", "docs", "false"),
            ("docs/ops/owner-cabinet.md", "docs", "false"),
            ("miniapp/src/modules/guest/components/HallLegend.tsx", "ui", "false"),
            ("miniapp/dist/packaged.md", "ui", "true"),
            ("miniapp/dist/assets/new-asset.js", "ui", "true"),
            ("app-bot/src/test/kotlin/demo/WorkerTest.kt", "ui-backend", "true"),
            ("core-data/src/main/resources/db/migration/new.sql", "full", "true"),
        ):
            with self.subTest(path=path):
                self.write(path, "fixture")
                self.commit()
                self.assertEqual({"mode": mode, "image": image}, self.mode())

    def test_backend_docs_contracts_dependency_and_security_paths(self):
        for path, mode in (
            ("miniapp/src/main/resources/miniapp/assets/js/api.js", "ui-backend"),
            ("miniapp/src/modules/payments/api/payments.api.ts", "ui-backend"),
            ("miniapp/src/modules/auth/hooks/useInitData.ts", "ui-backend"),
            ("miniapp/src/shared/api/http.ts", "ui-backend"),
            ("miniapp/src/app/CabinetEntry.tsx", "ui-backend"),
            ("miniapp/src/modules/client/onboarding.api.ts", "ui-backend"),
            ("app-bot/src/main/kotlin/demo/Music.kt", "backend"),
            ("core-security/src/main/kotlin/demo/Auth.kt", "full"),
            ("docs/dr.md", "full"),
            ("docs/ops/release-rollback.md", "full"),
            ("docs/ops/owner-team-contract.md", "full"),
            ("docs/ops/owner-manager-contract.md", "full"),
            ("app-bot/src/main/kotlin/bot/routes/PaymentsFinalizeRoutes.kt", "full"),
            ("app-bot/src/main/kotlin/bot/plugins/MigrationsPlugin.kt", "full"),
            ("app-bot/src/main/kotlin/bot/security/Rbac.kt", "full"),
            ("app-bot/src/main/kotlin/bot/di/PaymentsModule.kt", "full"),
            ("app-bot/src/test/kotlin/bot/telegram/webhook/TelegramWebhookIngressWorkerTest.kt", "backend"),
            ("miniapp/pnpm-lock.yaml", "full"),
            ("buildSrc/src/main/kotlin/build/Dependencies.kt", "full"),
            (".github/workflows/tests.yml", "full"),
            ("unknown/new.file", "full"),
        ):
            with self.subTest(path=path):
                before = self.git("rev-parse", "HEAD")
                self.write(path, "fixture")
                self.commit()
                self.assertEqual({"mode": mode, "image": "true"}, self.mode(base=before))

    def test_pr_uses_branch_and_merge_diffs_with_verified_parents(self):
        self.git("switch", "-qc", "candidate")
        self.write("README.md", "documentation")
        head = self.commit()
        self.git("switch", "-q", "main")
        self.write(self.protected, "advanced base")
        base = self.commit()
        self.git("merge", "--no-ff", "-qm", "merge", head)
        self.assertEqual({"mode": "docs", "image": "false"}, self.mode(base=base, candidate=head))
        self.mode(base=self.base, candidate=head, ok=False)
        self.write(self.protected, "change in merged result")
        self.git("add", "-A")
        self.git("commit", "--amend", "--no-edit", "-q")
        self.assertEqual({"mode": "full", "image": "true"}, self.mode(base=base, candidate=head))

    def test_sensitive_candidate_change_already_on_base_is_still_full(self):
        self.git("switch", "-qc", "candidate")
        self.write(self.protected, "shared")
        self.write("README.md", "docs")
        head = self.commit()
        self.git("switch", "-q", "main")
        self.write(self.protected, "shared")
        base = self.commit()
        self.git("merge", "--no-ff", "-qm", "merge", head)
        self.assertEqual({"mode": "full", "image": "true"}, self.mode(base=base, candidate=head))

    def test_mode_changes_and_whitespace_are_not_fast_successes(self):
        self.write("README.md", "trailing ")
        self.commit()
        self.mode(ok=False)
        (self.repo / "README.md").unlink()
        (self.repo / "README.md").symlink_to(self.protected)
        self.commit()
        self.assertEqual({"mode": "full", "image": "true"}, self.mode())


class LintTopologyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = workflow_model()
        cls.security = workflow_model(WORKFLOW.with_name("secret-scan.yml"))

    def validate(self, model, security=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lint.yml"
            path.write_text(json.dumps(model))
            path.with_name("secret-scan.yml").write_text(json.dumps(self.security if security is None else security))
            return subprocess.run(["ruby", str(VALIDATOR), str(path)], capture_output=True, text=True)

    def test_canonical_topology_and_required_contexts(self):
        result = self.validate(self.workflow)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("Lint", self.workflow["name"])
        self.assertEqual("lint", self.workflow["jobs"]["lint"]["name"])
        self.assertEqual("release-state", self.security["jobs"]["release-state"]["name"])
        self.assertNotIn("release-state", self.workflow["jobs"]["lint"]["needs"])

    def test_missing_workers_and_aggregator_dependencies_are_rejected(self):
        for worker in WORKERS:
            for kind in ("job", "needs"):
                model = copy.deepcopy(self.workflow)
                if kind == "job":
                    del model["jobs"][worker]
                else:
                    model["jobs"]["lint"]["needs"].remove(worker)
                with self.subTest(worker=worker, kind=kind):
                    self.assertNotEqual(0, self.validate(model).returncode)

    def test_core_setup_and_payment_structure_cannot_be_disabled(self):
        for index, key, value in (
            (0, "with", {"fetch-depth": 1, "persist-credentials": True}),
            (2, "with", {"cache-disabled": True}),
            (3, "run", "echo payment check omitted"),
            (4, "if", "false"),
            (5, "run", "echo selfcheck omitted"),
        ):
            model = copy.deepcopy(self.workflow)
            model["jobs"]["lint-core"]["steps"][index][key] = value
            with self.subTest(index=index, key=key):
                self.assertNotEqual(0, self.validate(model).returncode)

    def test_conditional_cancelled_hidden_failure_and_duplicate_shard_wiring_rejected(self):
        mutations = [
            ("lint", "if", "success()"),
            ("lint", "continue-on-error", True),
            ("lint", "needs", WORKERS + ["release-state"]),
            ("lint", "needs", WORKERS + ["lint-core"]),
            ("lint", "defaults", {"run": {"shell": "bash -c 'exit 0' -- {0}"}}),
        ]
        mutations += [(worker, key, value) for worker in WORKERS
                      for key, value in (("if", "always()"), ("continue-on-error", True), ("needs", "release-state"))]
        for job, key, value in mutations:
            model = copy.deepcopy(self.workflow)
            model["jobs"][job][key] = value
            with self.subTest(job=job, key=key):
                self.assertNotEqual(0, self.validate(model).returncode)
        for shard in sharding.SHARDS:
            for command in ("echo suite omitted", "python3 -B scripts/run-corrected-stage-shard.py authority",
                            "python3 -B scripts/run-corrected-stage-shard.py " + shard + " || true"):
                model = copy.deepcopy(self.workflow)
                step = model["jobs"]["corrected-" + shard]["steps"][-1]
                if command == step["run"]:
                    continue
                step["run"] = command
                with self.subTest(shard=shard, command=command):
                    self.assertNotEqual(0, self.validate(model).returncode)
        model = copy.deepcopy(self.workflow)
        model["jobs"]["lint"]["steps"][0]["run"] = "echo success"
        self.assertNotEqual(0, self.validate(model).returncode)

    def aggregate(self, needs, event="pull_request"):
        command = self.workflow["jobs"]["lint"]["steps"][0]["run"]
        return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", command],
                              env={**os.environ, "LINT_NEEDS_JSON": json.dumps(needs), "GITHUB_EVENT_NAME": event}, capture_output=True)

    def test_actual_aggregator_success_failure_cancelled_and_unexpected_skipped(self):
        success = {worker: {"result": "success", "outputs": {}} for worker in WORKERS}
        success["lint-scope"]["outputs"] = {"mode": "full", "image": "true"}
        self.assertEqual(0, self.aggregate(success).returncode)
        for worker in WORKERS:
            for status in ("failure", "cancelled", "skipped", "neutral", "", None):
                needs = copy.deepcopy(success)
                needs[worker]["result"] = status
                with self.subTest(worker=worker, status=status):
                    self.assertNotEqual(0, self.aggregate(needs).returncode)
            missing = copy.deepcopy(success)
            del missing[worker]
            self.assertNotEqual(0, self.aggregate(missing).returncode)
            missing = copy.deepcopy(success)
            del missing[worker]["result"]
            self.assertNotEqual(0, self.aggregate(missing).returncode)
        for needs in ({}, [], None, {**success, "release-state": {"result": "success"}}):
            with self.subTest(needs=needs):
                self.assertNotEqual(0, self.aggregate(needs).returncode)

    def test_fast_lint_requires_exact_skips_and_successful_mandatory_jobs(self):
        for mode, image, required in (
            ("docs", "false", []),
            ("ui", "false", ["miniapp"]),
            ("ui", "true", ["miniapp", "container-smoke", "pr-image"]),
            ("backend", "true", ["jvm", "lint-core", "container-smoke", "pr-image"]),
            ("ui-backend", "true", ["jvm", "lint-core", "miniapp", "container-smoke", "pr-image"]),
        ):
            for event in ("push", "pull_request"):
                needs = {job: {"result": "skipped"} for job in WORKERS}
                needs["lint-scope"] = {"result": "success", "outputs": {"mode": mode, "image": image}}
                for job in required:
                    if job != "pr-image" or event == "pull_request":
                        needs[job]["result"] = "success"
                with self.subTest(mode=mode, image=image, event=event):
                    self.assertEqual(0, self.aggregate(needs, event).returncode)
                    for job in WORKERS:
                        bad = copy.deepcopy(needs)
                        bad[job]["result"] = "skipped" if needs[job]["result"] == "success" else "cancelled"
                        self.assertNotEqual(0, self.aggregate(bad, event).returncode)
        for outputs in ({}, {"mode": "full"}, {"mode": "backend", "image": "false"},
                        {"mode": "docs", "image": "true"}, {"mode": "other", "image": "true"}):
            needs["lint-scope"]["outputs"] = outputs
            self.assertNotEqual(0, self.aggregate(needs).returncode)

    def test_release_status_fails_for_unsuccessful_or_absent_scope(self):
        command = self.security["jobs"]["release-state"]["steps"][0]["run"]
        baseline = {**os.environ, "GITLEAKS": "success", "SECURITY": "success", "STATIC": "success"}
        for result, mode, ok in (("success", "docs", True), ("success", "ui", True),
                                 ("success", "full", True), ("success", "backend", True),
                                 ("success", "ui-backend", True), ("success", "", False),
                                 ("success", "other", False), ("cancelled", "full", False),
                                 ("skipped", "docs", False), ("failure", "full", False)):
            with self.subTest(result=result, mode=mode):
                run = subprocess.run(["bash", "-e", "-c", command],
                                     env={**baseline, "MODE": mode, "SCOPE_RESULT": result})
                self.assertEqual(run.returncode == 0, ok)

    def test_release_state_requires_all_security_workers_and_read_only_permissions(self):
        job = self.security["jobs"]["release-state"]
        command = job["steps"][0]["run"]
        success = {"MODE": "ui", "SCOPE_RESULT": "success", "GITLEAKS": "success",
                   "SECURITY": "success", "STATIC": "success"}
        for name in ("GITLEAKS", "SECURITY", "STATIC"):
            for failure in ("failure", "cancelled", "skipped", ""):
                with self.subTest(name=name, failure=failure):
                    result = subprocess.run(["bash", "-e", "-c", command], env={**success, name: failure})
                    self.assertNotEqual(0, result.returncode)
        for key, value in (("permissions", {"contents": "read", "security-events": "write"}),
                           ("needs", ["lint-scope"]), ("if", "success()")):
            changed = copy.deepcopy(self.security)
            changed["jobs"]["release-state"][key] = value
            self.assertNotEqual(0, self.validate(self.workflow, changed).returncode)
        for name in job["needs"]:
            changed = copy.deepcopy(self.security)
            del changed["jobs"][name]
            self.assertNotEqual(0, self.validate(self.workflow, changed).returncode)

    def test_range_wrapper_distinguishes_changed_policy_from_git_error(self):
        scope = workflow_model(WORKFLOW.with_name("change-scope.yml"))
        command = scope["jobs"]["changes"]["steps"][1]["run"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, status in (("git", '"$DIFF_STATUS"'), ("ruby", "0")):
                stub = root / name
                stub.write_text("#!/bin/sh\nexit " + status + "\n")
                stub.chmod(0o700)
            output = root / "output"
            for base, code, expected in (("1" * 40, 1, 0), ("1" * 40, 128, 128), ("0" * 40, 128, 0)):
                output.write_text("")
                env = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"],
                       "GITHUB_OUTPUT": str(output), "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main",
                       "GITHUB_SHA": "2" * 40,
                       "LINT_BASE": base, "DIFF_STATUS": str(code)}
                result = subprocess.run(["bash", "-e", "-c", command], env=env)
                self.assertEqual(expected, result.returncode)
                self.assertEqual("" if expected else "mode=full\nimage=true\n", output.read_text())

    def test_scope_output_and_fast_checks_cannot_be_disabled(self):
        for step in range(len(self.workflow["jobs"]["miniapp"]["steps"])):
            model = copy.deepcopy(self.workflow)
            model["jobs"]["miniapp"]["steps"][step]["if"] = "false"
            self.assertNotEqual(0, self.validate(model).returncode)
        for trigger, spec in (("pull_request", {"paths": ["README.md"]}),
                              ("push", {"branches": ["main"], "paths-ignore": ["scripts/**"]})):
            model = copy.deepcopy(self.workflow)
            model["on"][trigger] = spec
            self.assertNotEqual(0, self.validate(model).returncode)

    def test_all_existing_lint_release_state_mutation_contracts(self):
        # Execute the existing shell regression block itself, including negative
        # YAML, timeout, delegation, exact candidate and shell-failure fixtures.
        source = SELFCHECK.read_text()
        helpers = source[source.index("fail() {"):source.index('retry_log=')]
        contracts = source[source.index("validate_lint_release_state_contract() {"):
                           source.index('quiesced_contract_validator=')]
        with tempfile.TemporaryDirectory() as directory:
            env = {**os.environ, "ROOT_DIR": str(ROOT), "TMP_DIR": directory}
            result = subprocess.run(["bash", "-e", "-u", "-o", "pipefail", "-c", helpers + contracts],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("custom shell fail-open calibration verified (37 -> 0)", result.stdout)

    def test_default_and_legacy_modes_run_full_suite_and_delegated_mode_requires_contracts(self):
        source = SELFCHECK.read_text()
        modes = source[source.index("usage() {"):source.index("# shellcheck disable=SC1091")]
        dispatch = source[source.index("# Corrected-stage delegation is permitted"):
                          source.rindex('echo "selfcheck: OK"')]
        # Run the actual mode parser/dispatch with recording executables. Real
        # workflow and shard validators are independently exercised above.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "commands"
            for name in ("python3", "ruby"):
                stub = root / name
                stub.write_text(textwrap.dedent("""\
                    #!/bin/bash
                    printf '%s %s\\n' "${0##*/}" "$*" >> "$PROBE_LOG"
                    if [[ "$*" == *validate-lint-workflow.rb* ]]; then exit "${TOPOLOGY_EXIT:-0}"; fi
                    if [[ "$*" == *test_project_identity_guard.py* ]]; then exit "${GUARD_EXIT:-0}"; fi
                    if [[ "$*" == *test_stat_fixture.py* ]]; then exit "${STAT_FIXTURE_EXIT:-0}"; fi
                    if [[ "$*" == *test_lint_sharding.py* ]]; then exit "${REGRESSION_EXIT:-0}"; fi
                    if [[ "$*" == *run-corrected-stage-shard.py* ]]; then exit "${INVENTORY_EXIT:-0}"; fi
                    if [[ "$*" == *test_corrected_stage_release.py* ]]; then exit "${SUITE_EXIT:-0}"; fi
                    exit 0
                    """))
                stub.chmod(0o700)
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                   "ROOT_DIR": str(ROOT), "PROBE_LOG": str(log)}
            script = modes + '\nfail() { echo "$1" >&2; exit 1; }\n' + dispatch
            full = str(ROOT / "scripts/tests/test_corrected_stage_release.py")
            for args in ([], ["--ci-delegated-release-state"]):
                for status in (0, 37):
                    log.write_text("")
                    result = subprocess.run(["bash", "-e", "-u", "-o", "pipefail", "-c", script, "probe", *args],
                                            env={**env, "SUITE_EXIT": str(status)}, capture_output=True)
                    self.assertEqual(status, result.returncode)
                    self.assertEqual(1, log.read_text().count(full))
                    self.assertEqual(1, log.read_text().count("test_stat_fixture.py"))
                    self.assertEqual(1, log.read_text().count("test_project_identity_guard.py"))
            args = ["--ci-delegated-release-state-and-corrected-stage"]
            for override, status in (({}, 0), ({"TOPOLOGY_EXIT": "31"}, 31),
                                     ({"REGRESSION_EXIT": "32"}, 32), ({"INVENTORY_EXIT": "33"}, 33),
                                     ({"STAT_FIXTURE_EXIT": "34"}, 34), ({"GUARD_EXIT": "35"}, 35)):
                log.write_text("")
                result = subprocess.run(["bash", "-e", "-u", "-o", "pipefail", "-c", script, "probe", *args],
                                        env={**env, **override}, capture_output=True)
                self.assertEqual(status, result.returncode)
                self.assertNotIn(full, log.read_text())
                self.assertEqual(status == 0, b"DELEGATED_TO_REQUIRED_LINT_SHARDS" in result.stdout)
            for args in (["--skip-tests"], ["--ci-delegated-corrected-stage"], ["extra", "argument"]):
                result = subprocess.run(["bash", "-e", "-u", "-o", "pipefail", "-c", script, "probe", *args],
                                        env=env, capture_output=True)
                self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main(verbosity=2)
