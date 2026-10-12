#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["jsonschema>=4.23"]
# ///
"""Tests for validate_protocol.py, against the real protocol/ and small broken copies.

    uv run scripts/validate_protocol_test.py
"""

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROTOCOL = HERE.parent / "protocol"
spec = importlib.util.spec_from_file_location("validate_protocol", HERE / "validate_protocol.py")
vp = importlib.util.module_from_spec(spec)
sys.modules["validate_protocol"] = vp
spec.loader.exec_module(vp)


class RealProtocolTest(unittest.TestCase):
    def test_the_checked_in_protocol_has_no_problems(self):
        self.assertEqual(vp.check(PROTOCOL), [])

    def test_it_covers_every_v1_message_type(self):
        types = {p.parent.name for p in (PROTOCOL / "fixtures" / "v1").glob("*/*.json")}
        self.assertTrue({"hello", "welcome", "refusal", "descriptors", "pairing", "message"} <= types)


class BrokenProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "protocol"
        shutil.copytree(PROTOCOL, self.root)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def write(self, rel, obj):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(obj) if not isinstance(obj, str) else obj)

    def problems(self):
        return "\n".join(vp.check(self.root))

    def test_a_fixture_that_breaks_its_schema_is_reported(self):
        self.write("fixtures/v1/hello/bad.json", {"type": "hello", "protocol": {"major": "one"}})
        self.assertIn("hello/bad.json", self.problems())

    def test_a_fixture_whose_type_does_not_match_its_directory_is_reported(self):
        self.write("fixtures/v1/hello/wrong-type.json", json.loads(
            (PROTOCOL / "fixtures/v1/welcome/basic.json").read_text()))
        self.assertIn("wrong-type.json", self.problems())

    def test_a_fixture_with_no_schema_is_reported(self):
        self.write("fixtures/v1/teleport/one.json", {"type": "teleport"})
        self.assertIn("teleport", self.problems())

    def test_a_schema_with_no_fixture_is_reported(self):
        shutil.rmtree(self.root / "fixtures/v1/refusal")
        self.assertIn("refusal", self.problems())

    def test_a_fixture_that_is_not_json_is_reported(self):
        self.write("fixtures/v1/hello/broken.json", "{not json")
        self.assertIn("broken.json", self.problems())

    def test_a_duplicate_key_counts_as_malformed(self):
        self.write("fixtures/v1/hello/dup.json", '{"type": "hello", "type": "hello"}')
        self.assertIn("dup.json", self.problems())

    def test_an_exchange_naming_a_missing_fixture_is_reported(self):
        exchanges = json.loads((self.root / "fixtures/v1/exchanges.json").read_text())
        exchanges["exchanges"][0]["steps"][0]["fixture"] = "hello/nope.json"
        self.write("fixtures/v1/exchanges.json", exchanges)
        self.assertIn("hello/nope.json", self.problems())

    def test_a_schema_that_forbids_extra_fields_is_reported(self):
        schema = json.loads((self.root / "schemas/v1/welcome.schema.json").read_text())
        schema["additionalProperties"] = False
        self.write("schemas/v1/welcome.schema.json", schema)
        self.assertIn("additionalProperties", self.problems())

    def test_an_invalid_schema_is_reported(self):
        self.write("schemas/v1/welcome.schema.json", {"type": 5})
        self.assertIn("welcome.schema.json", self.problems())


if __name__ == "__main__":
    unittest.main()
