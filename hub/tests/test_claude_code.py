"""Claude Code sessions from their hook events: recorded payloads replayed through `Sessions`, and through the
agents Provider with and without a (fake) herdr."""

import asyncio
import itertools
import unittest

from stsloop_hub import protocol as p
from stsloop_hub.agents import AgentsProvider
from stsloop_hub.alerts import Mutes
from stsloop_hub.buckets import Buckets
from stsloop_hub.claude_code import STALE_S, Sessions
from stsloop_hub.summary import Summaries

from support import Clock, FakeBackend, FakeHerdr, agent, fixture, recording

PERMISSION = recording("recorded-permission")
TOOLS = recording("recorded-tools")
INTERACTIVE = recording("doc-interactive")
PLAN = recording("doc-plan")


def ids():
    return (f"id-{n}" for n in itertools.count(1)).__next__


class SessionsTest(unittest.TestCase):
    """What each recorded event does to a session's state."""

    def setUp(self):
        self.clock = Clock()
        self.sessions = Sessions(clock=self.clock)

    def replay(self, events, upto: int):
        """Takes in `events[:upto]`, a second apart; the session they are about."""
        for pane, pid, payload in events[:upto]:
            self.clock.now += 1
            self.sessions.take(payload, pane, pid)
        return self.sessions.get(events[0][2]["session_id"])

    def states(self, events):
        """The (status, why) after each event; None once the session has ended."""
        out = []
        for pane, pid, payload in events:
            self.clock.now += 1
            self.sessions.take(payload, pane, pid)
            s = self.sessions.get(payload["session_id"])
            out.append((s.status, s.why) if s else None)
        return out

    def test_a_recorded_session_with_permission_prompts(self):
        self.assertEqual(self.states(PERMISSION), [
            ("idle", None),                    # SessionStart
            ("working", None),                 # UserPromptSubmit
            ("working", None),                 # PreToolUse Bash
            ("blocked", "permission"),         # PermissionRequest Bash
            ("blocked", "permission"),         # PreToolUse Bash (another call): still waiting
            ("blocked", "permission"),         # PermissionRequest Bash
            ("done", None),                    # Stop
            None,                              # SessionEnd
        ])

    def test_a_recorded_session_with_tools(self):
        self.assertEqual(self.states(TOOLS), [
            ("idle", None), ("working", None), ("working", None), ("working", None),
            ("working", None), ("working", None), ("done", None), None,
        ])

    def test_a_question_dialog_then_a_permission_prompt(self):
        self.assertEqual(self.states(INTERACTIVE), [
            ("idle", None),                    # SessionStart
            ("working", None),                 # UserPromptSubmit
            ("blocked", "question"),           # PreToolUse AskUserQuestion
            ("working", None),                 # PostToolUse AskUserQuestion: answered
            ("working", None),                 # PreToolUse Bash
            ("blocked", "permission"),         # PermissionRequest Bash
            ("blocked", "permission"),         # Notification permission_prompt
            ("working", None),                 # PostToolUse Bash: allowed
            ("done", None),                    # Stop
            ("done", None),                    # Notification idle_prompt
            ("working", None),                 # UserPromptSubmit
            ("stopped", None),                 # StopFailure
            None,                              # SessionEnd
        ])

    def test_a_plan_to_approve_is_a_permission_and_an_elicitation_a_question(self):
        self.assertEqual(self.states(PLAN)[1:], [("blocked", "permission"), ("blocked", "permission")])
        sessions = Sessions(clock=self.clock)
        sessions.take(PLAN[2][2])
        self.assertEqual(sessions.get(PLAN[2][2]["session_id"]).why, "question")

    def test_a_state_is_timed_from_the_event_that_began_it(self):
        s = self.replay(INTERACTIVE, 3)
        self.assertEqual((s.since, s.observed), (1003, True))
        self.replay(INTERACTIVE[6:7], 1)  # the notification, some seconds later, does not restart the clock
        self.assertEqual(s.since, 1003)

    def test_a_session_first_heard_mid_turn_has_worked_for_at_least_that_long(self):
        s = self.replay(TOOLS[3:], 1)  # PostToolUse
        self.assertEqual((s.status, s.observed), ("working", False))
        s = self.replay(INTERACTIVE[1:], 1)  # UserPromptSubmit: it began now
        self.assertEqual((s.status, s.observed), ("working", True))

    def test_another_tool_finishing_leaves_a_blocked_session_blocked(self):
        s = self.replay(INTERACTIVE, 3)  # blocked on AskUserQuestion
        self.sessions.take(TOOLS[3][2] | {"session_id": s.id})  # a PostToolUse of Read, a parallel call
        self.assertEqual((s.status, s.why), ("blocked", "question"))

    def test_idle_prompt_ends_a_block_nothing_else_ended(self):
        # A permission refused: Claude Code waits for the person, and no Stop comes.
        s = self.replay(INTERACTIVE, 6)
        self.replay(INTERACTIVE[9:10], 1)
        self.assertEqual(s.status, "done")

    def test_the_session_keeps_what_it_says(self):
        s = self.replay(INTERACTIVE, 9)
        self.assertEqual(s.text().splitlines(), [
            "The owner asked: Bring the branch up to date with main",
            "It asks: Should I rebase onto main or merge it? Options: Rebase, Merge.",
            "It uses Bash: Rebase onto main",
            "It asks permission to use Bash: Rebase onto main",
            "Claude needs your permission to use Bash",
            "It said: I rebased the branch onto main. All 40 tests pass.",
        ])

    def test_the_pane_and_pid_are_kept(self):
        s = self.replay(PERMISSION, 2)
        self.assertEqual((s.pane, s.pid, s.cwd), ("w8:p1", 4242, "/home/user/w/stsloop"))

    def test_events_the_hub_cannot_read_are_ignored(self):
        with self.assertLogs("stsloop.claude_code", "INFO"):
            for payload in ([], {}, {"hook_event_name": "Stop"}, {"hook_event_name": "Stop", "session_id": "../x"}):
                self.assertIsNone(self.sessions.take(payload))
        self.assertEqual(len(self.sessions), 0)

    def test_an_unknown_event_makes_a_session_but_changes_nothing(self):
        self.replay(INTERACTIVE, 2)
        self.sessions.take(INTERACTIVE[1][2] | {"hook_event_name": "CwdChanged"})
        self.assertEqual(self.sessions.get(INTERACTIVE[0][2]["session_id"]).status, "working")

    def test_ended_sessions_whose_process_is_gone_are_forgotten(self):
        self.replay(INTERACTIVE, 2)
        self.assertFalse(self.sessions.prune(alive=lambda pid: True))
        with self.assertLogs("stsloop.claude_code", "INFO"):
            self.assertTrue(self.sessions.prune(alive=lambda pid: pid != 5151))
        self.assertEqual(len(self.sessions), 0)

    def test_a_session_with_no_pid_is_forgotten_when_silent_for_long(self):
        self.sessions.take(INTERACTIVE[1][2])
        self.clock.now += STALE_S - 1
        self.assertFalse(self.sessions.prune())
        self.clock.now += 2
        with self.assertLogs("stsloop.claude_code", "INFO"):
            self.assertTrue(self.sessions.prune())


class Setup(unittest.TestCase):
    """The agents Provider fed by hooks, with herdr or without."""

    herdr = False

    def setUp(self):
        self.clock = Clock()
        self.fake = FakeHerdr() if self.herdr else None
        self.sessions = Sessions(clock=self.clock)
        self.backend = FakeBackend()
        self.alive = {4242, 5151, 6262}
        self.provider = AgentsProvider(
            self.fake, clock=self.clock, sessions=self.sessions,
            summaries=Summaries(self.fake, self.backend, timeout=20) if self.fake else None,
            session_summaries=Summaries(None, self.backend, timeout=20),
            alive=lambda pid: pid in self.alive,
        )
        self.buckets = Buckets([self.provider], mutes=Mutes(), new_id=ids())

    def hook(self, *events, after: float = 1):
        for pane, pid, payload in events:
            self.clock.now += after
            self.provider.hook(payload, pane, pid)

    def poll(self, *agents, after: float = 2):
        self.clock.now += after
        self.fake.agents_now = list(agents)
        self.provider.refresh()

    def ask(self, bucket: str, command: str = "status") -> p.Reply:
        return asyncio.run(self.buckets.answer(p.Request(id="r1", bucket=bucket, command=command)))

    def ids(self):
        return [d.id for d in self.buckets.descriptors()]


WOODGLUE = "claude-code:00893aaf-19fa-41d2-8238-13269b9b3ca0"


class WithoutHerdrTest(Setup):
    def test_a_session_is_a_bucket_named_from_its_directory(self):
        self.hook(*INTERACTIVE[:2])
        self.assertEqual(
            p.to_json(p.Descriptors(buckets=self.buckets.descriptors())), fixture("v1", "descriptors/claude-code.json"),
        )

    def test_buckets_come_and_go_with_sessions(self):
        self.assertTrue(self.provider.hook(INTERACTIVE[1][2], None, 5151))
        self.assertFalse(self.provider.hook(INTERACTIVE[2][2], None, 5151), "a change of state alone")
        self.assertTrue(self.provider.hook(INTERACTIVE[-1][2], None, 5151))
        self.assertEqual(self.ids(), ["hub"])

    def test_a_session_whose_process_has_gone_goes(self):
        self.hook(*INTERACTIVE[:2])
        self.alive.clear()
        with self.assertLogs("stsloop.claude_code", "INFO"):
            self.assertTrue(self.provider.prune())
        self.assertEqual(self.ids(), ["hub"])

    def test_two_sessions_in_one_directory(self):
        self.hook(*INTERACTIVE[:2])
        self.hook(*[(None, 7, e | {"session_id": "second"}) for _, _, e in INTERACTIVE[:2]])
        self.assertEqual([d.name for d in self.buckets.descriptors()], ["hub", "woodglue agent", "woodglue agent two"])

    def test_status_says_why_it_is_blocked(self):
        self.hook(*INTERACTIVE[:3], after=30)
        self.clock.now += 120
        self.assertEqual(self.ask(WOODGLUE).say, "woodglue agent is blocked on a question, for 2 minutes.")
        self.hook(*INTERACTIVE[3:6])
        self.assertEqual(self.ask(WOODGLUE).say, "woodglue agent is blocked on a permission prompt, for less than a minute.")

    def test_status_done_and_stopped(self):
        self.hook(*INTERACTIVE[:9])
        self.assertEqual(self.ask(WOODGLUE).say, "woodglue agent is done, for less than a minute.")
        self.hook(*INTERACTIVE[10:12])
        self.assertEqual(self.ask(WOODGLUE).say, "woodglue agent has stopped on an error, for less than a minute.")

    def test_a_session_first_heard_mid_turn(self):
        self.hook((None, 5151, INTERACTIVE[4][2]))
        self.clock.now += 200
        self.assertEqual(self.ask(WOODGLUE).say, "woodglue agent is working, for at least 3 minutes.")

    def test_whats_blocked(self):
        self.hook(*INTERACTIVE[:3])
        self.assertEqual(self.ask("hub", "blocked").say, "woodglue agent is blocked on a question, for less than a minute.")

    def test_nothing_blocked(self):
        self.assertEqual(self.ask("hub", "blocked").say, "nothing is blocked.")

    def test_what_does_it_say_summarises_its_hook_events(self):
        self.hook(*INTERACTIVE[:3])
        self.backend.answer = "woodglue agent asks whether to rebase onto main or merge."
        reply = self.ask(WOODGLUE, "read")
        self.assertEqual(reply.say, "woodglue agent asks whether to rebase onto main or merge.")
        [(_, text, _)] = self.backend.calls
        self.assertIn("It asks: Should I rebase onto main or merge it? Options: Rebase, Merge.", text)

    def test_what_does_it_say_falls_back_to_the_last_thing_it_said(self):
        self.hook(*INTERACTIVE[:3])
        self.backend.error = RuntimeError("no model")
        with self.assertLogs("stsloop.summary", "ERROR"):
            say = self.ask(WOODGLUE, "read").say
        self.assertEqual(say, "woodglue agent is blocked on a question, for less than a minute. "
                              "Last line: It asks: Should I rebase onto main or merge it? Options: Rebase, Merge.")

    def test_what_does_it_say_before_it_has_said_anything(self):
        self.hook((None, 5151, TOOLS[3][2] | {"session_id": INTERACTIVE[0][2]["session_id"]}))
        self.assertEqual(self.ask(WOODGLUE, "read").say,
                         "stsloop agent is working, for at least a few seconds. Its hooks have said nothing more yet.")

    def test_send_says_sending_needs_herdr(self):
        self.hook(*INTERACTIVE[:3])
        self.assertEqual(p.to_json(asyncio.run(self.buckets.answer(p.Request(id="a3f1cd", bucket=WOODGLUE, command="send")))),
                         fixture("v1", "reply/needs-herdr.json"))
        sent = self.buckets.prepare_send(p.Send(key="k" * 32, bucket=WOODGLUE, text="rebase"))()
        self.assertEqual((sent.reason, sent.say), ("refused", "sending to woodglue agent needs herdr, so it was not sent."))

    def test_a_blocked_session_raises_an_item(self):
        self.hook(*INTERACTIVE[:2])
        self.assertIsNone(self.buckets.collect())
        self.hook(INTERACTIVE[2])
        item = self.buckets.collect()
        self.assertEqual((item.buckets, item.say), ((WOODGLUE,), "woodglue agent is blocked."))

    def test_one_block_raises_one_item(self):
        self.hook(*INTERACTIVE[:6])  # question, answered, then a permission prompt: two blocks
        self.buckets.collect()
        self.hook(INTERACTIVE[6])  # the permission prompt's notification: the same block
        self.assertIsNone(self.buckets.collect())

    def test_a_muted_session_raises_nothing(self):
        self.hook(*INTERACTIVE[:2])
        self.assertEqual(self.ask(WOODGLUE, "quiet").say, "woodglue agent muted.")
        self.hook(INTERACTIVE[2])
        self.assertIsNone(self.buckets.collect())

    def test_done_is_not_raised(self):
        self.hook(*INTERACTIVE[:2], *INTERACTIVE[8:10])
        self.assertIsNone(self.buckets.collect())


class WithHerdrTest(Setup):
    herdr = True

    def test_the_same_agent_seen_through_herdr_and_hooks_is_one_bucket(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:3])
        self.assertEqual(self.ids(), ["hub", "herdr:w8:p1"])
        self.assertEqual([c.id for c in self.buckets.descriptors()[1].commands],
                         ["status", "read", "read_back", "send", "cancel", "quiet", "unmute"])

    def test_hooks_give_the_merged_agent_a_precise_state(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        self.assertEqual(self.ask("herdr:w8:p1").say, "stsloop agent is blocked on a permission prompt, for less than a minute.")
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2), after=1)
        self.clock.now += 61
        self.assertEqual(self.ask("herdr:w8:p1").say, "stsloop agent is blocked on a permission prompt, for 1 minute.",
                         "timed from the hook, which saw it first")

    def test_herdr_seeing_a_later_change_wins(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        self.poll(agent("w8:p1", "stsloop", "idle", seq=3))  # a permission refused: no hook says so
        self.assertEqual(self.ask("herdr:w8:p1").say, "stsloop agent is idle, for less than a minute.")

    def test_a_merged_agent_is_raised_once_whoever_sees_it_first(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        item = self.buckets.collect()
        self.assertEqual(item.buckets, ("herdr:w8:p1",))
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertIsNone(self.buckets.collect())

    def test_herdr_seeing_the_block_first_is_raised_once(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:3])
        self.poll(agent("w8:p1", "stsloop", "blocked", seq=2))
        self.assertIsNotNone(self.buckets.collect())
        self.hook(PERMISSION[3])
        self.assertIsNone(self.buckets.collect())

    def test_before_herdr_first_answers_a_session_in_its_pane_waits(self):
        self.hook(*PERMISSION[:2])
        self.assertEqual(self.ids(), ["hub"])
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.assertEqual(self.ids(), ["hub", "herdr:w8:p1"])

    def test_a_session_outside_herdr_has_its_own_bucket_and_name(self):
        self.poll(agent("w4:p1", "woodglue", "working"))
        self.hook(*INTERACTIVE[:2])
        self.assertEqual([d.name for d in self.buckets.descriptors()], ["hub", "woodglue agent", "woodglue agent two"])

    def test_herdr_away_leaves_its_sessions_as_claude_code_buckets(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        self.fake.error = "protocol_mismatch"
        with self.assertLogs("stsloop.herdr", "INFO"):
            for _ in range(3):
                self.provider.refresh()
        self.assertEqual(self.ids(), ["hub", "claude-code:32d3e381-c86e-4edd-8e18-99440f3ecd46"])
        self.assertEqual(self.ask("hub", "blocked").say,
                         "stsloop agent is blocked on a permission prompt, for less than a minute. herdr is not answering.")

    def test_the_last_session_heard_in_a_pane_gives_its_state(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        self.hook(*[(pane, pid, e | {"session_id": "inner"}) for pane, pid, e in TOOLS[:2]])
        # Working, as herdr has seen it since its first poll.
        self.assertEqual(self.ask("herdr:w8:p1").say, "stsloop agent is working, for at least a few seconds.")

    def test_what_does_it_say_reads_herdrs_screen_for_a_merged_agent(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        self.fake.screens["w8:p1"] = "Allow rm -f nothing-here.txt?\n"
        self.ask("herdr:w8:p1", "read")
        self.assertEqual(self.fake.reads, [("w8:p1", 150)])

    def test_a_send_to_a_merged_agent_goes_through_herdr(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:2])
        sent = self.buckets.prepare_send(p.Send(key="k" * 32, bucket="herdr:w8:p1", text="go on"))()
        self.assertEqual((sent.say, self.fake.prompts), ("sent to stsloop agent.", [("w8:p1", "go on")]))


if __name__ == "__main__":
    unittest.main()
