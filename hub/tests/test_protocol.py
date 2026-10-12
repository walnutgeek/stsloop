"""The handshake rules and message model, at the pure seam (no sockets)."""

import json
import unittest

from stsloop_hub import protocol as p


def hello(major=1, minor=0, capabilities=("descriptors",)):
    return p.Hello(protocol=p.Version(major, minor), capabilities=tuple(capabilities))


class NegotiateTest(unittest.TestCase):
    def test_a_current_phone_is_welcomed_with_the_shared_capabilities(self):
        self.assertEqual(
            p.negotiate(hello()),
            p.Welcome(protocol=p.Version(1, 0), capabilities=("descriptors",)),
        )

    def test_a_newer_minor_gets_the_hubs_minor(self):
        self.assertEqual(p.negotiate(hello(minor=7)).protocol, p.Version(1, 0))

    def test_an_older_minor_is_answered_in_its_own_minor(self):
        supported = (p.Version(1, 3),)
        self.assertEqual(p.negotiate(hello(minor=1), supported=supported).protocol, p.Version(1, 1))

    def test_capabilities_the_hub_does_not_know_are_dropped(self):
        welcome = p.negotiate(hello(capabilities=("teleport", "descriptors")))
        self.assertEqual(welcome.capabilities, ("descriptors",))

    def test_a_phone_without_descriptors_gets_no_capabilities(self):
        self.assertEqual(p.negotiate(hello(capabilities=())).capabilities, ())

    def test_the_previous_major_is_still_welcomed(self):
        supported = (p.Version(2, 1), p.Version(1, 4))
        self.assertEqual(p.negotiate(hello(major=1, minor=4), supported=supported).protocol, p.Version(1, 4))

    def test_a_newer_major_is_refused_and_told_to_upgrade_the_hub(self):
        refusal = p.negotiate(hello(major=2))
        self.assertIsInstance(refusal, p.Refusal)
        self.assertEqual(refusal.reason, "unsupported_protocol")
        self.assertEqual(refusal.supported, (p.Version(1, 0),))
        self.assertIn("Upgrade the hub", refusal.message)
        self.assertIn("2", refusal.message)

    def test_an_older_major_is_refused_and_told_to_update_the_phone(self):
        refusal = p.negotiate(hello(major=1), supported=(p.Version(3, 0), p.Version(2, 5)))
        self.assertEqual(refusal.reason, "unsupported_protocol")
        self.assertIn("2 or 3", refusal.message)
        self.assertIn("Update the phone app", refusal.message)


class DecodeTest(unittest.TestCase):
    def test_an_unknown_type_decodes_to_none(self):
        self.assertIsNone(p.decode('{"type": "teleport", "to": "mars"}'))

    def test_unknown_fields_are_ignored(self):
        self.assertEqual(
            p.decode('{"type": "hello", "protocol": {"major": 1, "minor": 0, "patch": 3},'
                     ' "capabilities": ["descriptors"], "client": "x"}'),
            hello(),
        )

    def test_a_hello_without_a_protocol_is_a_bad_message(self):
        with self.assertRaises(p.BadMessage):
            p.decode('{"type": "hello", "capabilities": []}')

    def test_a_hello_with_a_non_integer_major_is_a_bad_message(self):
        for major in ('"1"', "1.5", "true", "-1"):
            with self.subTest(major=major), self.assertRaises(p.BadMessage):
                p.decode('{"type": "hello", "protocol": {"major": %s, "minor": 0}, "capabilities": []}' % major)

    def test_capabilities_must_be_strings(self):
        with self.assertRaises(p.BadMessage):
            p.decode('{"type": "hello", "protocol": {"major": 1, "minor": 0}, "capabilities": [1]}')

    def test_text_that_is_not_a_message_is_a_bad_message(self):
        for text in ("not json", "[1]", '{"no": "type"}', '{"type": 3}', '{"type":"a","type":"b"}'):
            with self.subTest(text=text), self.assertRaises(p.BadMessage):
                p.decode(text)

    def test_a_descriptor_missing_its_name_is_a_bad_message(self):
        with self.assertRaises(p.BadMessage):
            p.decode(json.dumps({"type": "descriptors", "buckets": [
                {"id": "x", "aliases": [], "type": "agent", "primitives": [], "commands": []}]}))


class EncodeTest(unittest.TestCase):
    def test_encode_is_compact_json_that_decodes_back(self):
        message = p.Refusal(reason="bad_hello", message="No.")
        text = p.encode(message)
        self.assertNotIn(" ", text.replace("No.", ""))
        self.assertEqual(p.decode(text), message)

    def test_a_refusal_without_supported_versions_leaves_the_field_out(self):
        self.assertNotIn("supported", p.to_json(p.Refusal(reason="bad_hello", message="No.")))

    def test_bad_hello_refusal_is_a_sentence(self):
        refusal = p.bad_hello("protocol is missing")
        self.assertEqual(refusal.reason, "bad_hello")
        self.assertTrue(refusal.message.endswith("."))


if __name__ == "__main__":
    unittest.main()
