"""Fixture conformance (ADR-0006): the hub parses every fixture in protocol/, and
everything it sends is fixture-equivalent (equal as parsed JSON) and valid against
its schema. Runs against every fixture version present, so a frozen previous
major is covered as soon as it lands in protocol/fixtures/."""

import asyncio
import json
import unittest

from jsonschema import Draft202012Validator
from websockets.exceptions import ConnectionClosed

from stsloop_hub import pairing
from stsloop_hub import protocol as p
from stsloop_hub.stub import STUB_BUCKETS

from support import SCHEMAS, fixture, fixture_paths, manifest, pairing_token, phone, running_hub, versions


def schema_validator(version: str, message_type: str) -> Draft202012Validator:
    path = SCHEMAS / version / f"{message_type}.schema.json"
    return Draft202012Validator(json.loads(path.read_text()))


class ParseEveryFixtureTest(unittest.TestCase):
    def test_there_are_fixtures(self):
        self.assertIn("v1", versions())
        self.assertGreater(len(fixture_paths("v1")), 5)

    def test_every_fixture_of_a_known_type_parses_and_reencodes_to_its_known_fields(self):
        for version in versions():
            extras = {e["fixture"] for e in manifest(version, "equivalents.json")["equivalents"]}
            for path in fixture_paths(version):
                rel = f"{path.parent.name}/{path.name}"
                if path.parent.name == "message":
                    continue
                with self.subTest(fixture=f"{version}/{rel}"):
                    raw = path.read_text()
                    message = p.decode(raw)
                    self.assertIsNotNone(message, "a known type must parse")
                    if rel not in extras:
                        self.assertEqual(p.to_json(message), json.loads(raw))

    def test_a_message_of_unknown_type_parses_to_nothing(self):
        for version in versions():
            for path in (p_ for p_ in fixture_paths(version) if p_.parent.name == "message"):
                with self.subTest(fixture=path.name):
                    self.assertIsNone(p.decode(path.read_text()))

    def test_a_fixture_with_extra_fields_reads_as_its_plain_twin(self):
        for version in versions():
            for pair in manifest(version, "equivalents.json")["equivalents"]:
                with self.subTest(**pair):
                    self.assertEqual(
                        p.from_json(fixture(version, pair["fixture"])),
                        p.from_json(fixture(version, pair["reads_as"])),
                    )


class EmittedMessagesTest(unittest.TestCase):
    def test_the_stub_hubs_descriptors_are_the_stub_fixture(self):
        message = p.to_json(p.Descriptors(buckets=STUB_BUCKETS))
        self.assertEqual(message, fixture("v1", "descriptors/stub.json"))
        schema_validator("v1", "descriptors").validate(message)

    def test_the_pairing_payload_is_the_pairing_fixture(self):
        expected = fixture("v1", "pairing/basic.json")
        message = p.to_json(pairing.payload(pairing.url("100.101.102.103", 7717), expected["token"]))
        self.assertEqual(message, expected)
        schema_validator("v1", "pairing").validate(message)

    def test_the_bad_hello_refusal_satisfies_its_schema(self):
        schema_validator("v1", "refusal").validate(p.to_json(p.bad_hello("protocol is missing")))


class ExchangesTest(unittest.IsolatedAsyncioTestCase):
    """Plays each exchange in exchanges.json against a real hub over a WebSocket."""

    async def test_every_exchange(self):
        for version in versions():
            for exchange in manifest(version, "exchanges.json")["exchanges"]:
                with self.subTest(version=version, exchange=exchange["name"]):
                    await self.play(version, exchange["steps"])

    async def play(self, version, steps):
        async with running_hub(token=pairing_token(version)) as (_, url):
            async with phone(url, pairing_token(version)) as ws:
                for step in steps:
                    if step["from"] == "phone":
                        await ws.send(json.dumps(fixture(version, step["fixture"])))
                    elif "fixture" in step:
                        expected = fixture(version, step["fixture"])
                        received = json.loads(await asyncio.wait_for(ws.recv(), 5))
                        self.assertEqual(received, expected)
                        schema_validator(version, expected["type"]).validate(received)
                    else:
                        with self.assertRaises(ConnectionClosed):
                            await asyncio.wait_for(ws.recv(), 5)
                        self.assertEqual(ws.close_code, step["close"])
                if steps[-1]["from"] == "hub" and "fixture" in steps[-1]:
                    # The hub has said everything it will; nothing more arrives.
                    with self.assertRaises(asyncio.TimeoutError):
                        await asyncio.wait_for(ws.recv(), 0.2)


if __name__ == "__main__":
    unittest.main()
