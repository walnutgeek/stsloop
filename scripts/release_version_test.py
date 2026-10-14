#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Tests for release_version.py.

    uv run scripts/release_version_test.py
"""

import importlib.util
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("release_version", HERE / "release_version.py")
rv = importlib.util.module_from_spec(spec)
sys.modules["release_version"] = rv
spec.loader.exec_module(rv)


class ReleaseVersionTest(unittest.TestCase):
    def test_tag_gives_name_and_code(self):
        self.assertEqual(rv.release_version("v0.1.0"), ("0.1.0", 100))
        self.assertEqual(rv.release_version("v1.2.3"), ("1.2.3", 10203))
        self.assertEqual(rv.release_version("v12.34.56"), ("12.34.56", 123456))

    def test_code_increases_with_every_later_version(self):
        tags = ["v0.1.0", "v0.1.1", "v0.1.99", "v0.2.0", "v0.99.99", "v1.0.0", "v1.0.1", "v2.0.0"]
        codes = [rv.release_version(t)[1] for t in tags]
        self.assertEqual(codes, sorted(codes))
        self.assertEqual(len(set(codes)), len(codes))

    def test_code_is_above_the_pre_release_default(self):
        # Builds before this scheme shipped versionCode 1; the first tag must upgrade them.
        self.assertGreater(rv.release_version("v0.1.0")[1], 1)
        self.assertGreater(rv.release_version("v0.0.2")[1], 1)

    def test_rejects_tags_that_are_not_plain_semver(self):
        for tag in ["0.1.0", "v0.1", "v0.1.0-rc1", "v1.2.3.4", "vx.1.0", "v01.2.3", "", "v0.0.0", "v0.0.1"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                rv.release_version(tag)

    def test_rejects_parts_that_would_overflow_into_the_next(self):
        # minor or patch of 100 would collide with the next major or minor.
        for tag in ["v0.100.0", "v0.1.100"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                rv.release_version(tag)

    def test_rejects_codes_past_the_play_store_ceiling(self):
        # versionCode must stay at or below 2100000000.
        self.assertEqual(rv.release_version("v210000.0.0")[1], 2_100_000_000)
        with self.assertRaises(ValueError):
            rv.release_version("v210000.0.1")

    def test_main_prints_github_output_lines(self):
        lines = rv.main(["v1.2.3"])
        self.assertEqual(lines, ["name=1.2.3", "code=10203"])


if __name__ == "__main__":
    unittest.main()
