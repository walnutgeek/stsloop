"""Blocked alerts at the hub's seam: a fake herdr, a hand-moved clock, and Item ids from a counter."""

import asyncio
import itertools
import json
import tempfile
import unittest
from pathlib import Path

from stsloop_hub import protocol as p
from stsloop_hub.agents import AgentsProvider
from stsloop_hub.alerts import Mutes
from stsloop_hub.buckets import Buckets

from support import Clock, FakeHerdr, agent, fixture


def ids(*given: str):
    """Item ids: the given ones, then id-1, id-2, ..."""
    rest = (f"id-{n}" for n in itertools.count(1))
    return itertools.chain(given, rest).__next__


class Setup(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.herdr = FakeHerdr()
        self.clock = Clock()
        self.provider = AgentsProvider(self.herdr, clock=self.clock)
        self.mutes = Mutes(self.dir / "mutes.json")
        self.buckets = Buckets([self.provider], mutes=self.mutes, new_id=ids("7c1e2a90d4b3", "7c1e2a90d4b4"))

    def poll(self, *agents) -> p.Item | None:
        """One poll of herdr seeing `agents`; the Item it raises, if any."""
        self.clock.now += 2
        self.herdr.agents_now = list(agents)
        self.provider.refresh()
        return self.buckets.collect()

    def ask(self, bucket: str, command: str) -> p.Reply:
        return asyncio.run(self.buckets.answer(p.Request(id="r1", bucket=bucket, command=command)))


class TransitionTest(Setup):
    def test_an_agent_becoming_blocked_raises_an_item(self):
        self.assertIsNone(self.poll(agent("w8:p1", "stsloop", "working")))
        item = self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertEqual(p.to_json(item), fixture("v1", "item/blocked.json"))

    def test_staying_blocked_raises_nothing_more(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertIsNone(self.poll(agent("w8:p1", "stsloop", "blocked", seq=2)))

    def test_blocked_again_between_polls_raises_again(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertIsNotNone(self.poll(agent("w8:p1", "stsloop", "blocked", seq=4)), "blocked, working, blocked again")

    def test_done_working_and_idle_raise_nothing(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        for status in ("done", "idle", "working", "unknown"):
            with self.subTest(status=status):
                self.assertIsNone(self.poll(agent("w8:p1", "stsloop", status, seq=self.clock.now)))

    def test_an_agent_already_blocked_when_the_hub_first_sees_herdr_raises_nothing(self):
        # The hub cannot tell when that began; "what's blocked" says it.
        self.assertIsNone(self.poll(agent("w8:p1", "stsloop", "blocked")))

    def test_a_new_agent_that_is_blocked_raises_an_item(self):
        self.poll(agent("w4:p1", "woodglue", "working"))
        item = self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "blocked"))
        self.assertEqual(item.say, "stsloop agent is blocked.")

    def test_herdr_back_after_being_away_raises_nothing_for_what_it_missed(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.herdr.error = "protocol_mismatch"
        with self.assertLogs("stsloop.herdr", "INFO"):
            for _ in range(3):
                self.provider.refresh()
        self.herdr.error = None
        self.assertIsNone(self.poll(agent("w8:p1", "stsloop", "blocked", seq=2)))


class MergeTest(Setup):
    def test_agents_blocked_in_one_poll_merge_into_one_item(self):
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working"))
        self.buckets = Buckets([self.provider], mutes=self.mutes, new_id=ids("7c1e2a90d4b4"))
        item = self.poll(agent("w4:p1", "woodglue", "blocked", seq=2), agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertEqual(p.to_json(item), fixture("v1", "item/merged.json"))

    def test_three_agents(self):
        self.poll(agent("w1:p1", "a", "working"), agent("w2:p1", "b", "working"), agent("w3:p1", "c", "working"))
        item = self.poll(agent("w1:p1", "a", "blocked", 2), agent("w2:p1", "b", "blocked", 2), agent("w3:p1", "c", "blocked", 2))
        self.assertEqual(item.say, "three agents are blocked: a agent, b agent and c agent.")


class OutstandingTest(Setup):
    """What a reconnecting phone is sent again: Items not yet said that still hold."""

    def blocked(self) -> p.Item:
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working"))
        return self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "blocked", seq=2))

    def test_an_item_is_outstanding_until_spoken(self):
        item = self.blocked()
        self.assertEqual(self.buckets.outstanding(), (item,))
        self.buckets.spoken(item.id)
        self.assertEqual(self.buckets.outstanding(), ())

    def test_an_unknown_id_spoken_is_ignored(self):
        item = self.blocked()
        self.buckets.spoken("nope")
        self.assertEqual(self.buckets.outstanding(), (item,))

    def test_an_item_whose_agent_is_no_longer_blocked_is_dropped(self):
        self.blocked()
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working", seq=3))
        self.assertEqual(self.buckets.outstanding(), ())

    def test_an_item_whose_agent_has_gone_is_dropped(self):
        self.blocked()
        self.poll(agent("w4:p1", "woodglue", "working"))
        self.assertEqual(self.buckets.outstanding(), ())

    def test_a_merged_item_holds_while_any_of_its_agents_is_still_blocked(self):
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working"))
        item = self.poll(agent("w4:p1", "woodglue", "blocked", 2), agent("w8:p1", "stsloop", "blocked", 2))
        self.poll(agent("w4:p1", "woodglue", "working", 3), agent("w8:p1", "stsloop", "blocked", 2))
        self.assertEqual(self.buckets.outstanding(), (item,))

    def test_a_newer_item_for_the_same_agent_replaces_the_older(self):
        self.blocked()
        newer = self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "blocked", seq=4))
        self.assertEqual(self.buckets.outstanding(), (newer,))


class MuteTest(Setup):
    def test_agent_buckets_carry_quiet_and_unmute(self):
        self.poll(agent("w4:p1", "woodglue", "idle"), agent("w8:p1", "stsloop", "working"), agent("w8:p4", "stsloop", "done"))
        self.assertEqual(
            p.to_json(p.Descriptors(buckets=self.buckets.descriptors())),
            fixture("v1", "descriptors/herdr.json"),
        )

    def test_a_muted_agent_raises_nothing(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(self.ask("herdr:w8:p1", "quiet").say, "stsloop agent muted.")
        self.assertIsNone(self.poll(agent("w8:p1", "stsloop", "blocked", seq=2)))

    def test_unmute_lets_it_alert_again(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.ask("herdr:w8:p1", "quiet")
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertEqual(self.ask("herdr:w8:p1", "unmute").say, "stsloop agent unmuted.")
        self.poll(agent("w8:p1", "stsloop", "working", seq=3))
        self.assertIsNotNone(self.poll(agent("w8:p1", "stsloop", "blocked", seq=4)))

    def test_a_muted_agent_is_left_out_of_a_merged_item(self):
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working"))
        self.ask("herdr:w4:p1", "quiet")
        item = self.poll(agent("w4:p1", "woodglue", "blocked", 2), agent("w8:p1", "stsloop", "blocked", 2))
        self.assertEqual((item.buckets, item.say), (("herdr:w8:p1",), "stsloop agent is blocked."))

    def test_muting_drops_an_outstanding_item(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.ask("herdr:w8:p1", "quiet")
        self.assertEqual(self.buckets.outstanding(), ())

    def test_mutes_persist_across_hub_restarts(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.ask("herdr:w8:p1", "quiet")
        self.assertEqual(json.loads((self.dir / "mutes.json").read_text()), {"muted": ["herdr:w8:p1"]})
        restarted = Mutes(self.dir / "mutes.json")
        self.assertIn("herdr:w8:p1", restarted)
        self.assertNotIn("herdr:w4:p1", restarted)

    def test_quiet_on_a_bucket_that_has_gone(self):
        self.assertEqual(self.ask("herdr:w9:p9", "quiet").reason, "unknown_bucket")

    def test_the_hub_bucket_has_no_quiet(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(self.ask("hub", "quiet").reason, "unknown_command")


class MutesFileTest(unittest.TestCase):
    def test_no_file_means_nothing_muted(self):
        self.assertNotIn("herdr:w8:p1", Mutes(Path(tempfile.mkdtemp()) / "mutes.json"))

    def test_an_unreadable_file_means_nothing_muted_and_is_logged(self):
        path = Path(tempfile.mkdtemp()) / "mutes.json"
        path.write_text("{not json")
        with self.assertLogs("stsloop.hub", "WARNING"):
            mutes = Mutes(path)
        self.assertNotIn("herdr:w8:p1", mutes)

    def test_without_a_file_mutes_live_in_memory(self):
        mutes = Mutes(None)
        mutes.set("herdr:w8:p1", True)
        self.assertIn("herdr:w8:p1", mutes)


if __name__ == "__main__":
    unittest.main()
