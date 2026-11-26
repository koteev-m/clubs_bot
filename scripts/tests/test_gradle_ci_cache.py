#!/usr/bin/env python3
"""Exercise the Docker dependency retry at the boundary where PR #574 failed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
RETRY = ROOT / "scripts/retry-command.sh"
INSTALL = ("./gradlew --no-daemon :app-bot:installDist -x test --dependency-verification=strict "
           "--no-configuration-cache --console=plain").split()
RATE_LIMIT = ("Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/"
              "kotlin-stdlib/2.2.20/kotlin-stdlib-2.2.20.pom'. "
              "Received status code 429 from server: Too Many Requests")
FAILURE = f"""FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring project ':buildSrc'.
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not resolve org.jetbrains.kotlin:kotlin-stdlib:2.2.20.
     Required by:
         buildscript of project ':buildSrc'
      > Could not get resource 'https://plugins.gradle.org/m2/lib.pom'.
         > {RATE_LIMIT}
> There are 3 more failures with identical causes.

* Try:
> Get more help at https://help.gradle.org.

BUILD FAILED in 39s
"""


class GradleCacheTest(unittest.TestCase):
    def test_live_setup_uses_pinned_basic_cache_and_supported_inputs(self):
        paths = sorted((ROOT / ".github/workflows").glob("*.yml"))
        # Check the live workflow steps, including aliases in the JVM workflow.
        loader = "puts JSON.generate(ARGV.map { |p| YAML.safe_load(File.read(p), aliases: true) })"
        workflows = json.loads(subprocess.check_output(
            ["ruby", "-ryaml", "-rjson", "-e", loader, *paths], text=True))
        checked = set()
        writer_jobs = []
        writer_policy = ("${{ github.job != 'unit-tests' || github.event_name != 'push' || "
                         "github.ref != 'refs/heads/main' }}")
        gradle_pin = "3f131e8634966bd73d06cc69884922b02e6faf92"
        for path, workflow in zip(paths, workflows):
            for job_id, job in workflow["jobs"].items():
                for step in job.get("steps", []):
                    action, _, revision = step.get("uses", "").partition("@")
                    inputs = step.get("with", {})
                    if action == "actions/setup-java":
                        self.assertNotEqual(inputs.get("cache"), "gradle", path.name)
                    if action not in {"gradle/actions/setup-gradle", "gradle/actions/dependency-submission"}:
                        continue
                    with self.subTest(workflow=path.name, job=job_id):
                        checked.add(path.stem)
                        self.assertEqual(revision, gradle_pin)
                        self.assertEqual(inputs["cache-provider"], "basic")
                        self.assertEqual(inputs["gradle-version"], "wrapper")
                        if path.stem == "tests":
                            self.assertEqual(inputs["cache-read-only"], writer_policy)
                            if job_id == "unit-tests":
                                writer_jobs.append(job_id)
                        else:
                            self.assertIs(inputs["cache-read-only"], True)
                        if action == "gradle/actions/setup-gradle":
                            self.assertIs(inputs["cache-disabled"], False)
                            self.assertLessEqual(set(inputs), {"cache-provider", "gradle-version",
                                                              "cache-disabled", "cache-read-only"})
        self.assertEqual(checked, {"build-static", "dependency-drift", "dependency-submission",
                                   "lint", "sca", "security-scan", "static-check", "tests"})
        self.assertEqual(writer_jobs, ["unit-tests"])

    def test_docker_runs_exact_guarded_distribution_build(self):
        source = (ROOT / "Dockerfile").read_text()
        distribution = source.index("./gradlew --version")
        self.assertLess(distribution, source.index("COPY . ."))
        self.assertNotIn("--mount=", source[:distribution])
        self.assertIn("target=/root/.gradle/caches ", source)
        self.assertIn("scripts/retry-command.sh --maven-429 " + " ".join(INSTALL), source)

    def run_fixture(self, diagnostic, args=INSTALL, attempts="3", failures=1, mode=("--maven-429",)):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "gradlew"
            wrapper.write_text('#!/bin/sh\necho call >>calls\n'
                               'if [ "$(wc -l <calls)" -gt "$FAILURES" ]; then exit 0; fi\n'
                               'cat diagnostic >&2\nexit 39\n')
            wrapper.chmod(0o700)
            (root / "diagnostic").write_text(diagnostic)
            calls = root / "calls"
            calls.touch()
            result = subprocess.run([RETRY, *mode, *args], cwd=root,
                                    env=dict(os.environ, RETRY_DELAY_SECONDS="0",
                                             RETRY_MAX_ATTEMPTS=attempts, FAILURES=str(failures)),
                                    text=True, capture_output=True, timeout=5)
            return result.returncode, len(calls.read_text().splitlines())

    def test_retries_only_pre_task_central_rate_limits(self):
        self.assertEqual(self.run_fixture(FAILURE), (0, 2))
        self.assertEqual(self.run_fixture(FAILURE.replace("Could not GET", "Could not HEAD"),
                                          failures=3), (39, 3))
        for message in (
            "Dependency verification failed", "Compilation error", "There were failing tests",
            "> Task :app-bot:compileKotlin FAILED\n" + FAILURE,
            FAILURE + "unrelated fatal error\n",
            FAILURE.replace("3 more failures with identical causes.", "mixed failures"),
            FAILURE.replace("repo.maven.apache.org/", "other.example/"),
            FAILURE.replace("429 from server", "403 from server"),
            FAILURE.replace("* Try:", "> Dependency verification failed for configuration 'classpath'.\n\n* Try:"),
            FAILURE.replace("* Try:", "> Could not resolve other:missing:1.\n\n* Try:"),
            FAILURE.replace("BUILD FAILED in 39s", ""),
            FAILURE + FAILURE,
        ):
            with self.subTest(message=message):
                self.assertEqual(self.run_fixture(message), (39, 1))

    def test_rejects_other_invocations_and_unbounded_attempts(self):
        for args in (["./gradlew", "test"], ["./gradlew", "help", "test"], INSTALL + ["build"],
                     ["./gradlew", "--no-daemon :app-bot:installDist", *INSTALL[3:]]):
            with self.subTest(args=args):
                self.assertEqual(self.run_fixture(FAILURE, args=args), (2, 0))
        self.assertEqual(self.run_fixture(FAILURE, attempts="4"), (2, 0))
        self.assertEqual(self.run_fixture(FAILURE, mode=()), (2, 0))


if __name__ == "__main__":
    unittest.main()
