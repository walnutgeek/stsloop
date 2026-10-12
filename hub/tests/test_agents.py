"""The herdr Provider at its seam: a fake herdr and a hand-moved clock."""

import asyncio
import threading
import unittest

from stsloop_hub import protocol as p
from stsloop_hub.agents import GRACE, AgentsProvider, duration
from stsloop_hub.buckets import Buckets
from stsloop_hub.summary import Summaries, SummaryError

from support import Clock, FakeBackend, FakeHerdr, agent, fixture


def request(bucket: str, command: str = "status", rid: str = "r1") -> p.Request:
    return p.Request(id=rid, bucket=bucket, command=command)


def answer(buckets: Buckets, request: p.Request) -> p.Reply:
    return asyncio.run(buckets.answer(request))


class Setup(unittest.TestCase):
    def setUp(self):
        self.herdr = FakeHerdr()
        self.clock = Clock()
        self.backend = FakeBackend()
        self.provider = AgentsProvider(self.herdr, clock=self.clock, summaries=Summaries(self.herdr, self.backend, timeout=20))
        self.buckets = Buckets([self.provider])

    def poll(self, *agents, after: float = 0):
        self.clock.now += after
        self.herdr.agents_now = list(agents)
        return self.provider.refresh()

    def say(self, bucket: str, command: str = "status") -> str:
        return answer(self.buckets, request(bucket, command)).say


class BucketsTest(Setup):
    def test_each_agent_is_a_bucket_named_from_its_workspace(self):
        self.poll(agent("w4:p1", "woodglue", "idle"), agent("w8:p1", "stsloop", "working"), agent("w8:p4", "stsloop", "done"))
        self.assertEqual(
            p.to_json(p.Descriptors(buckets=self.buckets.descriptors())),
            fixture("v1", "descriptors/herdr.json"),
        )

    def test_a_label_is_said_as_words(self):
        self.poll(agent("w5:p1", "direction-wiki", "idle"), agent("w2:p1", "try_laya", "idle"))
        self.assertEqual([(d.name, d.aliases) for d in self.provider.descriptors()],
                         [("direction wiki agent", ("direction wiki",)), ("try laya agent", ("try laya",))])

    def test_an_agent_keeps_its_name_while_it_lives(self):
        self.poll(agent("w8:p1", "stsloop", "idle"), agent("w8:p4", "stsloop", "idle"))
        self.poll(agent("w8:p4", "stsloop", "idle"), after=2)
        self.assertEqual([d.name for d in self.provider.descriptors()], ["stsloop agent two"])
        self.poll(agent("w8:p4", "stsloop", "idle"), agent("w8:p7", "stsloop", "idle"), after=2)
        self.assertEqual([(d.id, d.name, d.aliases) for d in self.provider.descriptors()],
                         [("herdr:w8:p4", "stsloop agent two", ()), ("herdr:w8:p7", "stsloop agent", ("stsloop",))])

    def test_buckets_come_and_go_as_agents_do(self):
        self.assertTrue(self.poll(agent("w8:p1", "stsloop", "working")))
        self.assertFalse(self.poll(agent("w8:p1", "stsloop", "working"), after=2))
        self.assertTrue(self.poll(agent("w8:p1", "stsloop", "working"), agent("w4:p1", "woodglue", "idle"), after=2))
        self.assertTrue(self.poll(agent("w4:p1", "woodglue", "idle"), after=2))
        self.assertEqual([d.id for d in self.provider.descriptors()], ["herdr:w4:p1"])

    def test_a_change_of_state_alone_sends_no_new_snapshot(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertFalse(self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), after=2))

    def test_the_hub_bucket_carries_whats_blocked_heard_anywhere(self):
        hub = self.buckets.descriptors()[0]
        self.assertEqual(hub.id, "hub")
        self.assertEqual(hub.primitives, ("request",))
        [blocked] = hub.commands
        self.assertEqual((blocked.id, blocked.phrases, blocked.anywhere), ("blocked", ("what's blocked", "what is blocked"), True))

    def test_herdr_not_answering_means_no_agent_buckets(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.herdr.error = "protocol_mismatch"
        with self.assertLogs("stsloop.herdr", "INFO"):
            for _ in range(GRACE - 1):
                self.assertFalse(self.provider.refresh(), "a hiccup keeps the Buckets")
        self.assertEqual(self.say("herdr:w8:p1"), "stsloop agent is working, for at least a few seconds.")
        with self.assertLogs("stsloop.herdr", "WARNING"):
            self.assertTrue(self.provider.refresh())
        self.assertEqual([d.id for d in self.buckets.descriptors()], ["hub"])
        self.assertEqual(self.say("hub", "blocked"), "herdr is not answering.")
        self.assertEqual(answer(self.buckets, request("herdr:w8:p1")).reason, "unknown_bucket")


class StatusTest(Setup):
    def test_status_says_the_state_and_for_how_long(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), after=30)
        self.clock.now += 180
        reply = answer(self.buckets, request("herdr:w8:p1", rid="a3f1c9"))
        self.assertEqual(p.to_json(reply), fixture("v1", "reply/status.json"))

    def test_a_state_already_held_at_the_first_poll_is_held_for_at_least_that_long(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.clock.now += 125
        self.assertEqual(self.say("herdr:w8:p1"), "stsloop agent is working, for at least 2 minutes.")

    def test_a_state_left_and_entered_again_between_polls_starts_again(self):
        self.poll(agent("w8:p1", "stsloop", "working", seq=1))
        self.poll(agent("w8:p1", "stsloop", "working", seq=3), after=600)
        self.clock.now += 59
        self.assertEqual(self.say("herdr:w8:p1"), "stsloop agent is working, for less than a minute.")

    def test_done_idle_and_unknown(self):
        self.poll(agent("w1:p1", "a", "done"), agent("w2:p1", "b", "idle"), agent("w3:p1", "c", "unknown"))
        self.assertEqual(self.say("herdr:w1:p1"), "a agent is done, for at least a few seconds.")
        self.assertEqual(self.say("herdr:w2:p1"), "b agent is idle, for at least a few seconds.")
        self.assertEqual(self.say("herdr:w3:p1"), "herdr cannot tell what c agent is doing.")

    def test_an_agent_that_has_gone_or_a_command_it_lacks(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(p.to_json(answer(self.buckets, request("herdr:w9:p9", rid="a3f1cb"))), fixture("v1", "reply/unknown-bucket.json"))
        self.assertEqual(answer(self.buckets, request("herdr:w8:p1", "write")).reason, "unknown_command")
        self.assertEqual(answer(self.buckets, request("claude-code:x")).reason, "unknown_bucket")


class ReadTest(Setup):
    def test_what_does_it_say_gives_the_summary(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), after=30)
        self.herdr.screens["w8:p1"] = "Should I rebase onto main or merge it?\n❯ 1. Rebase\n  2. Merge\n"
        self.backend.answer = "stsloop agent is blocked: it asks whether to rebase onto main or merge."
        reply = answer(self.buckets, request("herdr:w8:p1", "read", rid="a3f1cc"))
        self.assertEqual(p.to_json(reply), fixture("v1", "reply/read.json"))
        [(system, text, timeout)] = self.backend.calls
        self.assertIn("stsloop agent", text)
        self.assertIn("blocked", text)
        self.assertEqual(timeout, 20)

    def test_without_a_model_it_gives_the_state_and_the_last_line(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), after=30)
        self.clock.now += 180
        self.herdr.screens["w8:p1"] = "Should I rebase onto main or merge it?\n"
        self.backend.error = SummaryError("claude took more than 20 s")
        with self.assertLogs("stsloop.summary", "WARNING"):
            reply = answer(self.buckets, request("herdr:w8:p1", "read", rid="a3f1cc"))
        self.assertEqual(p.to_json(reply), fixture("v1", "reply/read-fallback.json"))

    def test_a_provider_with_no_summariser_gives_the_fallback(self):
        provider = AgentsProvider(self.herdr, clock=self.clock)
        self.herdr.agents_now = [agent("w8:p1", "stsloop", "idle")]
        provider.refresh()
        self.herdr.screens["w8:p1"] = "All 12 tests pass.\n"
        self.assertEqual(answer(Buckets([provider]), request("herdr:w8:p1", "read")).say,
                         "stsloop agent is idle, for at least a few seconds. Last line: All 12 tests pass.")

    def test_an_agent_that_has_gone_is_not_read(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(answer(self.buckets, request("herdr:w9:p9", "read")).reason, "unknown_bucket")
        self.assertEqual(self.herdr.reads, [])

    def test_a_summary_does_not_hold_up_the_event_loop(self):
        self.poll(agent("w8:p1", "stsloop", "idle"))
        self.herdr.screens["w8:p1"] = "done\n"
        release = threading.Event()
        self.backend.wait = release

        async def both():
            read = asyncio.ensure_future(self.buckets.answer(request("herdr:w8:p1", "read", rid="a")))
            status = await self.buckets.answer(request("herdr:w8:p1", rid="b"))
            self.assertFalse(read.done())
            release.set()
            return status, await read

        status, read = asyncio.run(both())
        self.assertEqual((status.id, read.id, read.say), ("b", "a", "a summary."))


class BlockedTest(Setup):
    def test_nothing_blocked(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(self.say("hub", "blocked"), "nothing is blocked.")

    def test_one_blocked_agent(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), agent("w4:p1", "woodglue", "done"), after=2)
        self.clock.now += 65
        self.assertEqual(self.say("hub", "blocked"), "stsloop agent is blocked, for 1 minute.")

    def test_several_blocked_agents_the_longest_waiting_first(self):
        self.poll(agent("w4:p1", "woodglue", "blocked"), agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w4:p1", "woodglue", "blocked"), agent("w8:p1", "stsloop", "blocked", seq=2), after=60)
        self.clock.now += 180
        reply = answer(self.buckets, request("hub", "blocked", rid="a3f1ca"))
        self.assertEqual(p.to_json(reply), fixture("v1", "reply/blocked.json"))


class DurationTest(unittest.TestCase):
    def test_durations(self):
        for seconds, at_least, said in [
            (0, False, "for less than a minute"),
            (59, False, "for less than a minute"),
            (5, True, "for at least a few seconds"),
            (60, False, "for 1 minute"),
            (61 * 60, False, "for 1 hour 1 minute"),
            (120 * 60, True, "for at least 2 hours"),
            (3 * 3600 + 5 * 60, False, "for 3 hours 5 minutes"),
        ]:
            with self.subTest(seconds=seconds):
                self.assertEqual(duration(seconds, at_least=at_least), said)


class WithoutHerdrTest(unittest.TestCase):
    def test_no_provider_means_only_the_stub_and_every_request_is_unknown(self):
        buckets = Buckets()
        self.assertEqual(p.to_json(p.Descriptors(buckets=buckets.descriptors())), fixture("v1", "descriptors/stub.json"))
        self.assertEqual(p.to_json(answer(buckets, request("hub", "blocked", rid="a3f1ca"))), fixture("v1", "reply/unknown-command.json"))


class FailingProviderTest(unittest.TestCase):
    def test_a_provider_that_raises_gets_a_failed_reply(self):
        class Broken(AgentsProvider):
            def answer(self, request):
                raise RuntimeError("bug")

        buckets = Buckets([Broken(FakeHerdr())])
        with self.assertLogs("stsloop.hub", "ERROR"):
            reply = answer(buckets, request("herdr:w1:p1"))
        self.assertEqual(reply.reason, "failed")


if __name__ == "__main__":
    unittest.main()
