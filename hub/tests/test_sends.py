"""Sending a Draft: the herdr Provider delivers it with `herdr agent prompt`, and each idempotency key is
delivered at most once, across resends, reconnects and hub restarts. Every test uses a fake herdr."""

import asyncio
import json
import os
import stat
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

from stsloop_hub import protocol as p
from stsloop_hub.agents import HerdrProvider
from stsloop_hub.buckets import Buckets
from stsloop_hub.herdr import PROMPT, HerdrCli, HerdrError, HerdrRefused
from stsloop_hub.sends import INTERRUPTED, NOT_RECORDED, Deliveries, SendLog

from support import FakeHerdr, agent, fixture, pairing_token, phone, running_hub

SEND = p.from_json(fixture("v1", "send/basic.json"))
HELLO = json.dumps(fixture("v1", "hello/basic.json"))


def herdr_with_agents() -> tuple[FakeHerdr, Buckets]:
    herdr = FakeHerdr([agent("w4:p1", "woodglue", "idle"), agent("w8:p1", "stsloop", "working")])
    provider = HerdrProvider(herdr)
    provider.refresh()
    return herdr, Buckets([provider])


class ProviderSendTest(unittest.TestCase):
    """What the herdr Provider answers, as the `sent/` fixtures have it."""

    def setUp(self):
        self.herdr, self.buckets = herdr_with_agents()

    def test_a_draft_is_prompted_to_the_agents_pane_and_answered_delivered(self):
        sent = self.buckets.prepare_send(SEND)()
        self.assertEqual(self.herdr.prompts, [("w8:p1", "rebase onto main and run the tests")])
        self.assertEqual(p.to_json(sent), fixture("v1", "sent/delivered.json"))

    def test_an_agent_waiting_on_a_question_refuses_it(self):
        self.herdr.prompt_error = HerdrRefused("agent_blocked", "agent is blocked")
        self.assertEqual(p.to_json(self.buckets.prepare_send(SEND)()), fixture("v1", "sent/refused.json"))
        self.assertEqual(self.herdr.prompts, [])

    def test_a_prompt_herdr_did_not_confirm_is_uncertain(self):
        self.herdr.prompt_error = HerdrError("timed out")
        self.assertEqual(p.to_json(self.buckets.prepare_send(SEND)()), fixture("v1", "sent/uncertain.json"))

    def test_a_bucket_the_hub_does_not_have_is_not_sent(self):
        unknown = p.from_json(fixture("v1", "send/unknown-bucket.json"))
        self.assertEqual(p.to_json(self.buckets.prepare_send(unknown)()), fixture("v1", "sent/unknown-bucket.json"))
        hub = p.Send(key="k" * 32, bucket="hub", text="hello")
        self.assertEqual(self.buckets.prepare_send(hub)().reason, "unknown_bucket")
        self.assertEqual(self.herdr.prompts, [])

    def test_without_herdr_nothing_is_sent(self):
        self.assertEqual(Buckets().prepare_send(SEND)().reason, "unknown_bucket")


class DeliveriesTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.herdr, self.buckets = herdr_with_agents()
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "sends.jsonl"

    def tearDown(self):
        self.tmp.cleanup()

    def deliveries(self) -> Deliveries:
        return Deliveries(self.buckets.prepare_send, SendLog(self.path))

    async def test_the_same_key_again_gets_the_same_answer_and_is_not_delivered_again(self):
        d = self.deliveries()
        first = await d.deliver(SEND)
        again = await d.deliver(SEND)
        self.assertIsNone(first.reason)
        self.assertEqual(again, first)
        self.assertEqual(len(self.herdr.prompts), 1)

    async def test_a_new_key_is_a_new_delivery(self):
        d = self.deliveries()
        await d.deliver(SEND)
        await d.deliver(p.Send(key="f" * 32, bucket=SEND.bucket, text=SEND.text))
        self.assertEqual(len(self.herdr.prompts), 2)

    async def test_a_key_resent_while_it_is_being_delivered_waits_for_that_delivery(self):
        gate = threading.Event()
        prompt = self.herdr.prompt
        self.herdr.prompt = lambda pane, text: (gate.wait(5), prompt(pane, text))
        d = self.deliveries()
        first = asyncio.create_task(d.deliver(SEND))
        await asyncio.sleep(0.05)
        second = asyncio.create_task(d.deliver(SEND))
        await asyncio.sleep(0.05)
        gate.set()
        self.assertEqual(await first, await second)
        self.assertEqual(len(self.herdr.prompts), 1)

    async def test_a_key_delivered_before_a_restart_is_not_delivered_again(self):
        first = await self.deliveries().deliver(SEND)
        again = await self.deliveries().deliver(SEND)  # a new hub, the same log
        self.assertEqual(again, first)
        self.assertEqual(len(self.herdr.prompts), 1)

    async def test_a_refusal_is_remembered_too(self):
        self.herdr.prompt_error = HerdrRefused("agent_blocked")
        first = await self.deliveries().deliver(SEND)
        self.herdr.prompt_error = None
        self.assertEqual(await self.deliveries().deliver(SEND), first)
        self.assertEqual(self.herdr.prompts, [])

    async def test_a_key_the_hub_stopped_in_the_middle_of_is_uncertain_and_never_tried_again(self):
        SendLog(self.path).start(SEND)  # and then the hub died
        sent = await self.deliveries().deliver(SEND)
        self.assertEqual(sent, p.Sent(key=SEND.key, reason="uncertain", say=INTERRUPTED))
        self.assertEqual(self.herdr.prompts, [])

    async def test_the_start_is_recorded_before_herdr_is_run(self):
        seen = []
        prompt = self.herdr.prompt
        self.herdr.prompt = lambda pane, text: (seen.append(self.path.read_text()), prompt(pane, text))
        await self.deliveries().deliver(SEND)
        self.assertIn(SEND.key, seen[0])

    async def test_a_send_the_log_cannot_record_is_not_delivered(self):
        d = self.deliveries()
        self.path.mkdir()  # a directory where the log should be: every append fails
        with self.assertLogs("stsloop.sends", "ERROR"):
            sent = await d.deliver(SEND)
        self.assertEqual(sent, p.Sent(key=SEND.key, reason="refused", say=NOT_RECORDED))
        self.assertEqual(self.herdr.prompts, [])

    async def test_the_log_is_private_and_a_broken_last_line_is_skipped(self):
        await self.deliveries().deliver(SEND)
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)
        records = [json.loads(line) for line in self.path.read_text().splitlines()]
        self.assertEqual([r["key"] for r in records], [SEND.key, SEND.key])
        self.assertEqual(records[0]["text"], SEND.text)
        with self.path.open("a") as f:
            f.write('{"key": "half a li')
        self.assertIsNone(SendLog(self.path).answer("f" * 32))
        self.assertIsNone(SendLog(self.path).answer(SEND.key).reason)

    async def test_a_delivery_that_raises_is_uncertain(self):
        d = Deliveries(lambda send: (lambda: 1 / 0), SendLog(self.path))
        with self.assertLogs("stsloop.sends", "ERROR"):
            self.assertEqual((await d.deliver(SEND)).reason, "uncertain")


class OverTheWireTest(unittest.IsolatedAsyncioTestCase):
    """The phone's resend after a reconnect: the agent gets the Draft once."""

    async def handshake(self, ws):
        await ws.send(HELLO)
        await ws.recv(), await ws.recv()

    async def test_a_send_resent_on_a_new_connection_reaches_the_agent_once(self):
        herdr, buckets = herdr_with_agents()
        with tempfile.TemporaryDirectory() as tmp:
            sends = SendLog(Path(tmp) / "sends.jsonl")
            deliver = Deliveries(buckets.prepare_send, sends).deliver
            async with running_hub(descriptors=buckets.descriptors, answer=buckets.answer, deliver=deliver) as (_, url):
                async with phone(url, pairing_token()) as ws:
                    await self.handshake(ws)
                    await ws.send(json.dumps(fixture("v1", "send/extras.json")))
                    first = json.loads(await asyncio.wait_for(ws.recv(), 5))
                # The answer was lost, say: the phone reconnects and sends the same key again.
                async with phone(url, pairing_token()) as ws:
                    await self.handshake(ws)
                    await ws.send(json.dumps(fixture("v1", "send/basic.json")))
                    again = json.loads(await asyncio.wait_for(ws.recv(), 5))
            # And once more to a restarted hub, over the same log.
            restarted = Deliveries(buckets.prepare_send, SendLog(Path(tmp) / "sends.jsonl")).deliver
            async with running_hub(descriptors=buckets.descriptors, deliver=restarted) as (_, url):
                async with phone(url, pairing_token()) as ws:
                    await self.handshake(ws)
                    await ws.send(json.dumps(fixture("v1", "send/basic.json")))
                    after_restart = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(first, fixture("v1", "sent/delivered.json"))
        self.assertEqual(again, first)
        self.assertEqual(after_restart, first)
        self.assertEqual(herdr.prompts, [("w8:p1", "rebase onto main and run the tests")])

    async def test_a_send_before_the_welcome_is_ignored(self):
        herdr, buckets = herdr_with_agents()
        async with running_hub(deliver=Deliveries(buckets.prepare_send).deliver) as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send(json.dumps(fixture("v1", "send/basic.json")))
                await self.handshake(ws)
                with self.assertRaises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.recv(), 0.2)
        self.assertEqual(herdr.prompts, [])


class Recorder:
    def __init__(self, returncode=0, stdout="", stderr="", raises=None):
        self.calls = []
        self.result = subprocess.CompletedProcess([], returncode, stdout, stderr)
        self.raises = raises

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        if self.raises:
            raise self.raises
        return self.result


class CliPromptTest(unittest.TestCase):
    """`herdr agent prompt`, with a recorded subprocess: no test runs the real herdr."""

    OK = '{"id":"cli:agent:prompt","result":{"type":"agent_prompted","agent":{}}}'

    def test_it_runs_agent_prompt_with_the_pane_and_the_text(self):
        run = Recorder(stdout=self.OK)
        HerdrCli("/opt/herdr", run=run).prompt("w8:p1", "rebase onto main")
        self.assertEqual(run.calls, [["/opt/herdr", "agent", "prompt", "w8:p1", "rebase onto main"]])
        self.assertEqual(PROMPT, ("agent", "prompt"))

    def test_a_text_herdr_would_read_as_an_option_gets_a_leading_space(self):
        run = Recorder(stdout=self.OK)
        HerdrCli("/opt/herdr", run=run).prompt("w8:p1", "-v is verbose")
        self.assertEqual(run.calls[0][-1], " -v is verbose")

    def test_errors_herdr_raises_before_sending_are_refusals(self):
        for code in ("agent_blocked", "agent_not_found", "protocol_mismatch"):
            stderr = json.dumps({"error": {"code": code, "message": "no"}, "id": "cli:agent:prompt"})
            with self.subTest(code=code), self.assertRaises(HerdrRefused) as caught:
                HerdrCli("/opt/herdr", run=Recorder(returncode=1, stderr=stderr)).prompt("w8:p1", "hi")
            self.assertEqual(caught.exception.code, code)
        with self.assertRaises(HerdrRefused):
            HerdrCli("/opt/herdr", run=Recorder(returncode=2, stderr="unknown option")).prompt("w8:p1", "hi")
        with self.assertRaises(HerdrRefused):
            HerdrCli("/opt/herdr", run=Recorder(raises=FileNotFoundError("gone"))).prompt("w8:p1", "hi")

    def test_anything_else_is_uncertain(self):
        cases = {
            "an unknown error": Recorder(returncode=1, stderr='{"error":{"code":"write_failed","message":"x"}}'),
            "an unreadable error": Recorder(returncode=1, stderr="boom"),
            "a timeout": Recorder(raises=subprocess.TimeoutExpired("herdr", 5)),
        }
        for name, run in cases.items():
            with self.subTest(name), self.assertRaises(HerdrError) as caught:
                HerdrCli("/opt/herdr", run=run).prompt("w8:p1", "hi")
            self.assertNotIsInstance(caught.exception, HerdrRefused)


if __name__ == "__main__":
    unittest.main()
