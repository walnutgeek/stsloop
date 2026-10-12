"""Fixture conformance (ADR-0006): the hub parses every fixture in protocol/, and
everything it sends is fixture-equivalent (equal as parsed JSON) and valid against
its schema. Runs against every fixture version present, so a frozen previous
major is covered as soon as it lands in protocol/fixtures/."""

import asyncio
import json
import unittest

from jsonschema import Draft202012Validator
from websockets.exceptions import ConnectionClosed, InvalidStatus

from stsloop_hub import pairing
from stsloop_hub import protocol as p
from stsloop_hub.hub import HELLO_TIMEOUT_S
from stsloop_hub.stub import STUB_BUCKETS

from support import SCHEMAS, fixture, fixture_paths, manifest, pairing_token, phone, running_hub, versions

#: Exchanges run this much faster than real time: an `idle_ms` of 10 s takes 0.2 s.
TIME_SCALE = 0.02


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

    def test_the_bad_hello_refusal_is_the_bad_hello_fixture(self):
        self.assertEqual(p.to_json(p.BAD_HELLO), fixture("v1", "refusal/bad-hello.json"))


class ExchangesTest(unittest.IsolatedAsyncioTestCase):
    """Plays each exchange in exchanges.json against a real hub over a WebSocket."""

    async def test_every_exchange(self):
        for version in versions():
            for exchange in manifest(version, "exchanges.json")["exchanges"]:
                with self.subTest(version=version, exchange=exchange["name"]):
                    await self.play(version, exchange)

    def test_the_idle_step_is_the_hubs_hello_timeout(self):
        idles = [
            step["idle_ms"]
            for version in versions()
            for exchange in manifest(version, "exchanges.json")["exchanges"]
            for step in exchange["steps"]
            if "idle_ms" in step
        ]
        self.assertTrue(idles)
        for idle_ms in idles:
            self.assertEqual(idle_ms, HELLO_TIMEOUT_S * 1000)

    async def play(self, version, exchange):
        steps = exchange["steps"]
        authorization = exchange.get("authorization", f"Bearer {pairing_token(version)}")
        # The hub's hello timeout, scaled down so the exchange takes a fraction of a second.
        async with running_hub(token=pairing_token(version), hello_timeout=HELLO_TIMEOUT_S * TIME_SCALE) as (_, url):
            if "http" in steps[0]:
                with self.assertRaises(InvalidStatus) as caught:
                    async with phone(url, authorization=authorization):
                        pass
                self.assertEqual(caught.exception.response.status_code, steps[0]["http"])
                return
            async with phone(url, authorization=authorization) as ws:
                idle_until = 0.0
                loop = asyncio.get_running_loop()
                for step in steps:
                    if "idle_ms" in step:
                        idle_until = loop.time() + step["idle_ms"] / 1000 * TIME_SCALE
                    elif step["from"] == "phone":
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
                        # Not much before the phone has been idle as long as the step says (the hub's
                        # clock starts at accept, a little before the phone's connect returns).
                        self.assertGreaterEqual(loop.time(), idle_until - 0.1)
                if steps[-1]["from"] == "hub" and "fixture" in steps[-1]:
                    # The hub has said everything it will; nothing more arrives.
                    with self.assertRaises(asyncio.TimeoutError):
                        await asyncio.wait_for(ws.recv(), 0.2)


if __name__ == "__main__":
    unittest.main()
