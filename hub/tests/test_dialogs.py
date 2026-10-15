"""Answering a blocked agent's question dialog by voice (#80): the dialog the hooks report, read out and shown in
the Descriptor; an answer typed into it with herdr's keys, at most once; and every dialog that is not a question
refused. The hook payloads and the screens were recorded from Claude Code 2.1.291 (a throwaway session against a
fake API, outside herdr); herdr is a fake, so nothing is ever typed anywhere."""

import asyncio
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from stsloop_hub import dialogs
from stsloop_hub import protocol as p
from stsloop_hub.agents import PERMISSION_REFUSED, AgentsProvider
from stsloop_hub.alerts import Mutes
from stsloop_hub.buckets import Buckets
from stsloop_hub.claude_code import Sessions
from stsloop_hub.herdr import SEND_KEYS, SEND_TEXT, HerdrCli, HerdrError, HerdrRefused
from stsloop_hub.sends import Deliveries, SendLog

from support import Clock, FakeHerdr, agent, fixture, pairing_token, phone, recording, running_hub, screen

QUESTION = recording("recorded-question")
PERMISSION = recording("recorded-permission")
#: The events of the recorded session, by what they are.
ASK_ONE, ASK_ONE_PERMISSION, ONE_ANSWERED = QUESTION[2], QUESTION[3], QUESTION[4]
ASK_TWO, ASK_TWO_PERMISSION, TWO_ANSWERED = QUESTION[7], QUESTION[8], QUESTION[9]
BASH, BASH_PERMISSION = QUESTION[12], QUESTION[13]
TWO_ID = ASK_TWO[2]["tool_use_id"]
ANSWER = p.from_json(fixture("v1", "send/answer.json"))
WOODGLUE = "herdr:w4:p1"


def in_pane(event, pane: str):
    """A recorded event as if its session ran in herdr's pane `pane`."""
    return (pane, event[1], event[2])


class KeysTest(unittest.TestCase):
    """The keys that answer a dialog, as a real Claude Code dialog took them."""

    ONE = dialogs.questions(ASK_ONE[2]["tool_input"])
    TWO = dialogs.questions(ASK_TWO[2]["tool_input"])

    Q1, Q2 = "Should I rebase onto main or merge it?", "Which test suite should run after?"
    SUBMIT = dialogs.Key("1", expects=dialogs.SUBMIT_ROW)

    def test_an_option_of_one_question_is_its_digit(self):
        self.assertEqual(dialogs.keys(self.ONE, (p.Choice(option=2),)), [dialogs.Key("2", expects=self.Q1)])

    def test_several_questions_are_answered_in_order_then_submitted(self):
        self.assertEqual(dialogs.keys(self.TWO, (p.Choice(option=2), p.Choice(option=3))),
                         [dialogs.Key("2", expects=self.Q1), dialogs.Key("3", expects=self.Q2), self.SUBMIT])

    def test_a_free_text_answer_is_the_row_after_the_options_the_text_then_enter(self):
        self.assertEqual(dialogs.keys(self.TWO, (p.Choice(option=1), p.Choice(text="Only the\nfast ones"))), [
            dialogs.Key("1", expects=self.Q1), dialogs.Key("4", expects=self.Q2), dialogs.Text("Only the fast ones"),
            dialogs.Key("enter"), self.SUBMIT,
        ])

    def test_a_text_starting_with_a_dash_gets_a_space(self):
        self.assertEqual(dialogs.keys(self.ONE, (p.Choice(text="-f is fine"),))[1], dialogs.Text(" -f is fine"))

    def test_a_question_with_more_than_nine_rows_has_no_key_for_them(self):
        many = (p.DialogQuestion("Which?", tuple(f"o{n}" for n in range(1, 11)), other=True),)
        self.assertEqual(dialogs.keys(many, (p.Choice(option=9),)), [dialogs.Key("9", expects="Which?")])
        for choice in (p.Choice(option=10), p.Choice(text="mine")):
            with self.subTest(choice=choice), self.assertRaisesRegex(dialogs.Unfit, "no key of its own"):
                dialogs.keys(many, (choice,))

    def test_a_multi_select_question_takes_its_digit_tab_and_the_submit(self):
        multi = (p.DialogQuestion("Which files should I format?", ("Kotlin", "Python"), multi_select=True),)
        self.assertEqual(dialogs.keys(multi, (p.Choice(option=2),)),
                         [dialogs.Key("2", expects="Which files should I format?"), dialogs.Key("tab"), self.SUBMIT])
        with self.assertRaisesRegex(dialogs.Unfit, "no answer of your own"):
            dialogs.keys(multi, (p.Choice(text="Shell too"),))

    def test_an_answer_that_does_not_fit_is_unfit(self):
        for choices, why in (
            ((p.Choice(option=3),), "question one has no option three"),
            ((p.Choice(option=1), p.Choice(option=1)), "it asks 1 question, and the answer has 2"),
            ((), "it asks 1 question, and the answer has 0"),
            ((p.Choice(text=" \n "),), "no answer of your own"),
        ):
            with self.subTest(why=why), self.assertRaisesRegex(dialogs.Unfit, why):
                dialogs.keys(self.ONE, choices)

    def test_the_questions_come_from_the_hook_payload(self):
        self.assertEqual(self.TWO, (
            p.DialogQuestion("Should I rebase onto main or merge it?", ("Rebase", "Merge"), other=True),
            p.DialogQuestion("Which test suite should run after?", ("Unit", "Device", "Both"), other=True),
        ))
        for broken in (None, {}, {"questions": []}, {"questions": [{"question": "Why?", "options": []}]},
                       {"questions": [{"question": "", "options": [{"label": "a"}]}]}):
            with self.subTest(broken=broken):
                self.assertIsNone(dialogs.questions(broken))

    def test_the_screen_check_finds_the_question_and_never_a_permission_prompt(self):
        self.assertTrue(dialogs.on_screen(screen("question"), self.ONE))
        self.assertTrue(dialogs.on_screen(screen("questions"), self.TWO))
        self.assertFalse(dialogs.on_screen(screen("question"), self.TWO[1:]), "another question")
        self.assertFalse(dialogs.on_screen(screen("permission"), self.ONE))
        # The question in the history above, with a permission prompt below it: not the dialog.
        self.assertFalse(dialogs.on_screen(screen("question") + "\n" + screen("permission"), self.ONE))
        # A long question wraps anywhere on a narrow terminal.
        wrapped = screen("question").replace("onto main or", "onto\nmain or")
        self.assertTrue(dialogs.on_screen(wrapped, self.ONE))


class Setup(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.herdr = FakeHerdr()
        self.herdr.screens = {"w4:p1": screen("questions"), "w8:p1": screen("permission")}
        self.slept = []
        self.provider = AgentsProvider(self.herdr, clock=self.clock, sessions=Sessions(clock=self.clock),
                                       alive=lambda pid: True, sleep=self.slept.append)
        self.buckets = Buckets([self.provider], mutes=Mutes(), new_id=lambda: "item-1")

    def poll(self, *agents):
        self.clock.now += 2
        self.herdr.agents_now = list(agents)
        self.provider.refresh()

    def hook(self, *events):
        for pane, pid, payload in events:
            self.clock.now += 1
            self.provider.hook(payload, pane, pid)

    def blocked_on_two_questions(self):
        self.poll(agent("w4:p1", "woodglue", "working"))
        self.hook(*(in_pane(e, "w4:p1") for e in QUESTION[:2]), in_pane(ASK_TWO, "w4:p1"))

    def send(self, send: p.Send = ANSWER) -> p.Sent:
        """Delivers `send`, woodglue agent's screen going through its dialog as each key answers it."""
        self.herdr.screens["w4:p1"] = [screen("questions"), screen("questions-second"), screen("review")]
        return self.buckets.prepare_send(send)()

    def ask(self, bucket: str, command: str = "read") -> p.Reply:
        return asyncio.run(self.buckets.answer(p.Request(id="r1", bucket=bucket, command=command)))


class DialogTest(Setup):
    """What the hub shows and says of each dialog."""

    def test_each_agent_shows_the_dialog_it_is_blocked_on(self):
        self.poll(agent("w4:p1", "woodglue", "working"), agent("w8:p1", "stsloop", "working"),
                  agent("w5:p1", "direction-wiki", "working"))
        self.hook(*(in_pane(e, "w4:p1") for e in QUESTION[:2]), in_pane(ASK_TWO, "w4:p1"), *PERMISSION[:4])
        self.poll(agent("w4:p1", "woodglue", "blocked", seq=2), agent("w8:p1", "stsloop", "blocked", seq=2),
                  agent("w5:p1", "direction-wiki", "blocked", seq=2))
        self.assertEqual(p.to_json(p.Descriptors(buckets=self.buckets.descriptors())),
                         fixture("v1", "descriptors/dialogs.json"))

    def test_what_does_it_say_reads_the_question_and_its_numbered_options(self):
        self.blocked_on_two_questions()
        reply = self.ask(WOODGLUE)
        self.assertEqual(p.to_json(reply), fixture("v1", "reply/question.json"))
        self.hook(in_pane(ASK_ONE, "w4:p1"))
        self.assertEqual(self.ask(WOODGLUE).say,
                         "woodglue agent asks: Should I rebase onto main or merge it? option one: Rebase; option two: "
                         "Merge. Or dictate your own answer and say my answer.")

    def test_the_dialog_goes_once_it_is_answered(self):
        self.blocked_on_two_questions()
        self.hook(in_pane(ASK_TWO_PERMISSION, "w4:p1"))
        self.assertEqual(self.provider.descriptors()[0].dialog.id, TWO_ID, "its permission request is the same dialog")
        self.hook(in_pane(TWO_ANSWERED, "w4:p1"))
        self.assertIsNone(self.provider.descriptors()[0].dialog)

    def test_its_permission_request_is_not_a_second_alert(self):
        self.blocked_on_two_questions()
        self.assertEqual(len(self.provider.take_raised()), 1)
        self.hook(in_pane(ASK_TWO_PERMISSION, "w4:p1"))
        self.assertEqual(self.provider.take_raised(), ())

    def test_a_dialog_seen_only_by_its_permission_request_still_has_an_id(self):
        self.poll(agent("w4:p1", "woodglue", "working"))
        self.hook(in_pane(ASK_TWO_PERMISSION, "w4:p1"))
        dialog = self.provider.descriptors()[0].dialog
        self.assertEqual((dialog.kind, len(dialog.questions)), ("question", 2))
        self.assertTrue(dialog.id.startswith("ask-"))

    def test_a_session_without_herdr_shows_no_dialog(self):
        self.provider = AgentsProvider(None, clock=self.clock, sessions=Sessions(clock=self.clock), alive=lambda pid: True)
        self.provider.hook(ASK_TWO[2], None, 4343)
        self.assertIsNone(self.provider.descriptors()[0].dialog)


class AnswerTest(Setup):
    """Typing an answer: the right keys, into the right dialog, or nothing at all."""

    def test_an_answer_is_typed_into_the_dialog_a_key_at_a_time(self):
        self.blocked_on_two_questions()
        self.assertEqual(p.to_json(self.send()), fixture("v1", "sent/answered.json"))
        self.assertEqual(self.herdr.reads, [("w4:p1", dialogs.SCREEN_LINES)] * 3,
                         "the screen is checked before each question's key and the submit")
        self.assertEqual(self.herdr.typed, [
            ("w4:p1", "key", "1"), ("w4:p1", "key", "4"), ("w4:p1", "text", "Only the fast ones"),
            ("w4:p1", "key", "enter"), ("w4:p1", "key", "1"),
        ])
        self.assertEqual(self.slept, [dialogs.KEY_GAP_S] * 4, "a pause between keys, none before the first")
        self.assertEqual(self.herdr.prompts, [], "never as a prompt")

    def test_a_permission_prompt_is_refused(self):
        self.poll(agent("w8:p1", "stsloop", "working"))
        self.hook(*PERMISSION[:4])
        sent = self.send(p.Send(key="b" * 32, bucket="herdr:w8:p1", text="option one: Yes",
                                answer=p.Answer(dialog=TWO_ID, choices=(p.Choice(option=1),))))
        self.assertEqual(p.to_json(sent), fixture("v1", "sent/permission.json"))
        self.assertEqual((sent.say, self.herdr.typed, self.herdr.reads), (PERMISSION_REFUSED, [], []))

    def test_the_recorded_bash_prompt_after_a_question_is_refused(self):
        self.blocked_on_two_questions()
        self.hook(*(in_pane(e, "w4:p1") for e in (ASK_TWO_PERMISSION, TWO_ANSWERED, QUESTION[10], QUESTION[11],
                                                  BASH, BASH_PERMISSION)))
        self.assertEqual(self.provider.descriptors()[0].dialog, p.Dialog(kind="permission"))
        self.assertEqual(self.send().say, PERMISSION_REFUSED)
        self.assertEqual(self.herdr.typed, [])

    def test_a_dialog_herdr_alone_sees_is_refused(self):
        self.poll(agent("w4:p1", "woodglue", "blocked"))
        sent = self.send()
        self.assertEqual((sent.reason, sent.say), ("refused", "I can't tell what woodglue agent is asking, so it was not sent."))
        self.assertEqual(self.herdr.typed, [])

    def test_a_dialog_of_another_kind_is_refused(self):
        # An MCP elicitation is a question, but not AskUserQuestion's: the hub has no questions for it.
        self.poll(agent("w4:p1", "woodglue", "working"))
        elicitation = dict(ASK_ONE[2], hook_event_name="Notification", notification_type="elicitation_dialog",
                           message="The server asks for a token")
        self.hook(("w4:p1", 4343, elicitation))
        self.assertEqual(self.provider.descriptors()[0].dialog, p.Dialog(kind="unknown"))
        self.assertEqual(self.send().reason, "refused")
        self.assertEqual(self.herdr.typed, [])

    def test_an_agent_not_blocked_is_not_answered(self):
        self.poll(agent("w4:p1", "woodglue", "working"))
        self.assertEqual(self.send().say, "woodglue agent is not asking anything now, so it was not sent.")

    def test_an_answer_to_a_dialog_no_longer_open_is_refused(self):
        self.blocked_on_two_questions()
        self.hook(in_pane(TWO_ANSWERED, "w4:p1"), in_pane(ASK_ONE, "w4:p1"))
        self.assertEqual(self.send().say, "woodglue agent is asking something else now, so it was not sent.")
        self.assertEqual(self.herdr.typed, [])

    def test_an_answer_that_does_not_fit_is_refused(self):
        self.blocked_on_two_questions()
        unfit = p.Send(key="c" * 32, bucket=WOODGLUE, text="option three",
                       answer=p.Answer(dialog=TWO_ID, choices=(p.Choice(option=3), p.Choice(option=1))))
        self.assertEqual(self.send(unfit).say, "that answer does not fit: question one has no option three. it was not sent.")
        self.assertEqual(self.herdr.typed, [])

    def test_a_screen_that_does_not_show_the_question_gets_nothing(self):
        self.blocked_on_two_questions()
        for shown in (screen("permission"), screen("question") + "\n" + screen("permission"), screen("review"), ""):
            with self.subTest(shown=shown[-40:]):
                self.herdr.screens["w4:p1"] = shown
                sent = self.buckets.prepare_send(ANSWER)()
                self.assertEqual((sent.reason, sent.say),
                                 ("refused", "woodglue agent's screen does not show that question, so it was not sent."))
        self.herdr.screens.pop("w4:p1")
        self.assertEqual(self.buckets.prepare_send(ANSWER)().say, "herdr could not show woodglue agent's screen, so it was not sent.")
        self.assertEqual(self.herdr.typed, [])

    def test_a_dialog_that_changes_partway_gets_no_more_keys(self):
        # The second question answered at the keyboard meanwhile, and a permission prompt up: its "1" would approve it.
        self.blocked_on_two_questions()
        for later in (screen("permission"), screen("question")):
            with self.subTest(later=later[-40:]):
                self.herdr.typed = []
                self.herdr.screens["w4:p1"] = [screen("questions"), later]
                sent = self.buckets.prepare_send(ANSWER)()
                self.assertEqual((sent.reason, sent.say), ("uncertain", "woodglue agent's dialog changed partway through the answer; check it."))
                self.assertEqual(self.herdr.typed, [("w4:p1", "key", "1")], "only the first question's key")
        self.herdr.typed = []
        self.herdr.screens["w4:p1"] = [screen("questions"), screen("questions-second"), screen("permission")]
        self.assertEqual(self.buckets.prepare_send(ANSWER)().reason, "uncertain")
        self.assertNotIn(("w4:p1", "key", "1"), self.herdr.typed[1:], "no submit on a permission prompt")

    def test_a_first_key_herdr_refuses_is_not_sent_and_a_later_one_is_uncertain(self):
        self.blocked_on_two_questions()
        self.herdr.type_error = (0, HerdrRefused("agent_not_found"))
        self.assertEqual(self.send(), p.Sent(key=ANSWER.key, reason="refused", say="woodglue agent is gone, so it was not sent."))
        self.herdr.type_error = (2, HerdrRefused("agent_not_found"))
        self.assertEqual(self.send().reason, "uncertain")
        self.herdr.typed, self.herdr.type_error = [], (0, HerdrError("timed out"))
        self.assertEqual(self.send(), p.Sent(key=ANSWER.key, reason="uncertain",
                                             say="herdr did not confirm that woodglue agent got the answer."))

    def test_a_draft_sent_meanwhile_is_still_a_prompt(self):
        self.blocked_on_two_questions()
        self.send(p.Send(key="d" * 32, bucket=WOODGLUE, text="rebase please"))
        self.assertEqual((self.herdr.prompts, self.herdr.typed), ([("w4:p1", "rebase please")], []))


class AtMostOnceTest(Setup, unittest.IsolatedAsyncioTestCase):
    """An answer's key is typed once, ever: resent after a reconnect, it gets the first answer again."""

    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "sends.jsonl"
        self.blocked_on_two_questions()
        self.herdr.screens["w4:p1"] = [screen("questions"), screen("questions-second"), screen("review")]

    def tearDown(self):
        self.tmp.cleanup()

    async def test_the_same_key_again_types_nothing(self):
        d = Deliveries(self.buckets.prepare_send, SendLog(self.path))
        first = await d.deliver(ANSWER)
        self.assertEqual(await d.deliver(ANSWER), first)
        self.assertEqual(len(self.herdr.typed), 5)
        # And after a hub restart, from the log.
        self.assertEqual(await Deliveries(self.buckets.prepare_send, SendLog(self.path)).deliver(ANSWER), first)
        self.assertEqual(len(self.herdr.typed), 5)
        logged = [json.loads(line) for line in self.path.read_text().splitlines()]
        self.assertEqual(logged[0]["text"], ANSWER.text, "the readback is the record")

    async def test_a_phone_that_reconnects_and_sends_it_again_gets_the_same_answer(self):
        hello = json.dumps(fixture("v1", "hello/basic.json"))
        deliveries = Deliveries(self.buckets.prepare_send, SendLog(self.path))
        async with running_hub(descriptors=self.buckets.descriptors, deliver=deliveries.deliver) as (_, url):
            answers = []
            for _ in range(2):
                async with phone(url, pairing_token()) as ws:
                    await ws.send(hello)
                    await ws.recv()  # welcome
                    await ws.recv()  # descriptors
                    await ws.send(json.dumps(fixture("v1", "send/answer.json")))
                    answers.append(json.loads(await ws.recv()))
        self.assertEqual(answers, [fixture("v1", "sent/answered.json")] * 2)
        self.assertEqual(len(self.herdr.typed), 5)


class CliTest(unittest.TestCase):
    """herdr's commands for answering: each key on its own, the text as it is, and nothing else."""

    def run_cli(self, call, returncode=0, stderr=""):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, returncode, "", stderr)

        call(HerdrCli("/opt/herdr", run=run))
        return calls

    def test_keys_and_text_go_to_the_agents_pane(self):
        self.assertEqual(self.run_cli(lambda cli: cli.send_keys("w8:p1", ["2"])),
                         [["/opt/herdr", *SEND_KEYS, "w8:p1", "2"]])
        self.assertEqual(self.run_cli(lambda cli: cli.send_text("w8:p1", "Only the fast ones")),
                         [["/opt/herdr", *SEND_TEXT, "w8:p1", "Only the fast ones"]])
        self.assertEqual((SEND_KEYS, SEND_TEXT), (("agent", "send-keys"), ("pane", "send-text")))

    def test_only_digits_enter_and_tab_are_ever_pressed(self):
        for keys in ([], ["y"], ["ctrl+c"], ["esc"], ["0"], ["1", "--help"], ["enter "]):
            with self.subTest(keys=keys), self.assertRaises(HerdrRefused):
                self.run_cli(lambda cli: cli.send_keys("w8:p1", keys))

    def test_a_text_herdr_could_misread_is_never_passed(self):
        for text in ("", "-v", "two\nlines", "a\rb"):
            with self.subTest(text=text), self.assertRaises(HerdrRefused):
                self.run_cli(lambda cli: cli.send_text("w8:p1", text))

    def test_herdrs_refusal_is_not_sent_and_anything_else_uncertain(self):
        with self.assertRaises(HerdrRefused):
            self.run_cli(lambda cli: cli.send_keys("w8:p1", ["1"]), 1, '{"error":{"code":"agent_not_found","message":"x"}}')
        with self.assertRaises(HerdrRefused):
            self.run_cli(lambda cli: cli.send_keys("w8:p1", ["1"]), 2, "unknown key")
        with self.assertRaises(HerdrError) as e:
            self.run_cli(lambda cli: cli.send_text("w8:p1", "hi"), 1, '{"error":{"code":"internal","message":"x"}}')
        self.assertNotIsInstance(e.exception, HerdrRefused)


if __name__ == "__main__":
    unittest.main()
