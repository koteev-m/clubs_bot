#!/usr/bin/env python3
"""Equivalence and dispatch contracts for disposable helper metadata fixtures.

Optional --benchmark measures the original Python fixture and installed fixture
in this same interpreter/environment. It does not predict hosted CI duration.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_quiesced_release_state as state


class StatFixtureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="clb198-stat-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.reference = self.root / "reference-stat"
        state.write_executable(self.reference, state.FAKE_STAT)
        state.install_stat_fixture(self.bin)
        self.env = {**os.environ, "FAKE_REAL_STAT": "/usr/bin/stat", "PYTHONDONTWRITEBYTECODE": "1"}
        self.file = self.root / "file with spaces"
        self.file.write_text("fixture")
        self.stdin = self.root / "stable-stdin"
        self.stdin.write_bytes(b"fixture stdin")

    def invoke(self, executable, *args):
        # BSD stat with no arguments reports stdin metadata. Keep both probes
        # on one regular inode instead of an active terminal whose times change.
        with self.stdin.open("rb") as source:
            return subprocess.run([str(executable), *map(str, args)], env=self.env,
                                  stdin=source, capture_output=True)

    def assert_equivalent(self, *args):
        original = self.invoke(self.reference, *args)
        candidate = self.invoke(self.bin / "stat", *args)
        self.assertEqual((original.returncode, original.stdout), (candidate.returncode, candidate.stdout), args)
        return candidate

    def test_permissions_owner_and_links_follow_real_metadata(self):
        for permissions in (0o000, 0o600, 0o644, 0o700, 0o4755, 0o2750, 0o1777):
            self.file.chmod(permissions)
            for flag in ("-c", "-f"):
                for template in ("%a", "%Lp", "%u", "%h", "%l", "%a:%u:%h", "%Lp:%u:%l"):
                    with self.subTest(permissions=permissions, flag=flag, template=template):
                        self.assertEqual(0, self.assert_equivalent(flag, template, self.file).returncode)

    def test_directories_symlinks_and_hardlinks_keep_follow_semantics(self):
        link = self.root / "symlink"
        link.symlink_to(self.file)
        hardlink = self.root / "hardlink"
        os.link(self.file, hardlink)
        for path in (self.root, self.file, link, hardlink):
            for template in ("%a", "%Lp", "%u", "%h", "%l", "%a:%u:%h", "%Lp:%u:%l"):
                with self.subTest(path=path.name, template=template):
                    self.assertEqual(0, self.assert_equivalent("-c", template, path).returncode)
        self.assertEqual(b"2\n", self.invoke(self.bin / "stat", "-c", "%h", link).stdout)

    def test_missing_and_dangling_paths_do_not_become_success(self):
        missing = self.root / "missing"
        dangling = self.root / "dangling"
        dangling.symlink_to(missing)
        for path in (missing, dangling):
            for template in ("%a", "%Lp", "%u", "%h", "%l", "%a:%u:%h", "%Lp:%u:%l"):
                with self.subTest(path=path.name, template=template):
                    self.assertNotEqual(0, self.assert_equivalent("-c", template, path).returncode)

    def test_non_fast_formats_preserve_python_rendering_and_timestamp_rounding(self):
        for timestamp in (1_700_000_000.25, -0.5):
            os.utime(self.file, (timestamp, timestamp))
            for template in ("%Y", "%m", "%a %u %h %Y", "%%a", "literal:%Lp:%l", "%Z"):
                with self.subTest(timestamp=timestamp, template=template):
                    self.assert_equivalent("-c", template, self.file)

    def test_unrecognized_argv_uses_the_unchanged_fallback(self):
        for args in ((), ("--version",), ("-c",), ("-L", "-c", "%a", self.file),
                     ("-c", "%a", self.file, self.root)):
            with self.subTest(args=args):
                self.assert_equivalent(*args)

    def test_linux_fast_dispatch_avoids_python_and_has_exact_safe_argv(self):
        with patch.object(state.sys, "platform", "linux"):
            state.install_stat_fixture(self.bin)
        # A recorder exercises GNU argv without assuming GNU stat on the host.
        recorder = self.root / "native-stat"
        trace = self.root / "argv"
        state.write_executable(recorder, '#!/bin/bash\nprintf "%s\\n" "$@" > "$STAT_TRACE"\nprintf "native-result\\n"\n')
        self.env.update(FAKE_REAL_STAT=str(recorder), STAT_TRACE=str(trace))
        state.write_executable(self.bin / "stat-python-fallback", "#!/bin/sh\nexit 79\n")
        for flag in ("-c", "-f"):
            for requested, native in (("%a", "%a"), ("%Lp", "%a"), ("%u", "%u"),
                                      ("%h", "%h"), ("%l", "%h"),
                                      ("%a:%u:%h", "%a:%u:%h"), ("%Lp:%u:%l", "%a:%u:%h")):
                result = self.invoke(self.bin / "stat", flag, requested, self.file)
                self.assertEqual((0, b"native-result\n"), (result.returncode, result.stdout))
                self.assertEqual(["-L", "-c", native, "--", str(self.file)], trace.read_text().splitlines())
        for args in (("-c", "%Y", self.file), ("-c", "%a %u", self.file), ("--version",)):
            self.assertEqual(79, self.invoke(self.bin / "stat", *args).returncode)

    def test_native_failure_is_not_hidden_or_retried(self):
        with patch.object(state.sys, "platform", "linux"):
            state.install_stat_fixture(self.bin)
        recorder = self.root / "failed-stat"
        trace = self.root / "attempts"
        state.write_executable(recorder, '#!/bin/bash\nprintf "attempt\\n" >> "$STAT_TRACE"\nexit 73\n')
        self.env.update(FAKE_REAL_STAT=str(recorder), STAT_TRACE=str(trace))
        self.assertEqual(73, self.invoke(self.bin / "stat", "-c", "%a", self.file).returncode)
        self.assertEqual(["attempt"], trace.read_text().splitlines())

    def test_non_linux_retains_the_original_python_fixture(self):
        other = self.root / "portable"
        other.mkdir()
        with patch.object(state.sys, "platform", "darwin"):
            state.install_stat_fixture(other)
        self.assertEqual(state.FAKE_STAT, (other / "stat").read_text())
        self.assertFalse((other / "stat-python-fallback").exists())

    def test_remote_harness_installs_the_selected_fixture_without_preflight(self):
        with state.RemoteHarness(auto_preflight=False) as harness:
            expected = state.FAST_LINUX_STAT if sys.platform == "linux" else state.FAKE_STAT
            self.assertEqual(expected, (harness.fake_bin / "stat").read_text())
            result = subprocess.run([str(harness.fake_bin / "stat"), "-c", "%a", str(harness.root)],
                                    env=harness.env, capture_output=True)
            self.assertEqual((0, b"700\n"), (result.returncode, result.stdout))


def benchmark():
    with tempfile.TemporaryDirectory(prefix="clb198-stat-benchmark-") as directory:
        root = Path(directory)
        reference = root / "reference"
        state.write_executable(reference, state.FAKE_STAT)
        state.install_stat_fixture(root)
        env = {**os.environ, "FAKE_REAL_STAT": "/usr/bin/stat"}
        samples = {"python": [], "candidate": []}
        for order in (("python", "candidate"), ("candidate", "python")):
            for name in order:
                executable = reference if name == "python" else root / "stat"
                started = time.monotonic()
                for index in range(200):
                    template = ("%a", "%u", "%h", "%a:%u:%h")[index % 4]
                    result = subprocess.run([str(executable), "-c", template, str(reference)],
                                            env=env, capture_output=True, check=True)
                    if not result.stdout.strip():
                        raise AssertionError("missing metadata")
                samples[name].append(round(time.monotonic() - started, 6))
        print("stat-fixture-benchmark:" + json.dumps({"platform": sys.platform,
              "calls_per_sample": 200, "samples_seconds": samples}, sort_keys=True))


if __name__ == "__main__":
    if sys.argv[1:] == ["--benchmark"]:
        benchmark()
    else:
        unittest.main(verbosity=2)
