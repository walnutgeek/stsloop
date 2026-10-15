"""Summaries at their seam: redaction, the backends' invocations, and the fallback.
No test runs a model, or the real herdr."""

import json
import subprocess
import unittest

from stsloop_hub import protocol as p
from stsloop_hub import summary
from stsloop_hub.config import SummariserConfig
from stsloop_hub.summary import (
    REDACTED, ClaudeBackend, NoteSummaries, OllamaBackend, PiBackend, SummaryError, Summaries, backend, last_line, redact,
    spoken,
)

from support import FakeBackend, FakeHerdr, fixture

#: Representative secrets, each as it might appear in an agent's terminal. None of them is real.
SECRETS = {
    "anthropic key": "sk-" + "ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCdEfGh",
    "openai key": "sk-" + "proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
    "github token": "ghp_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
    "github fine-grained token": "github_" + "pat_11ABCDEFG0123456789_AbCdEfGhIjKlMnOpQrStUv",
    "gitlab token": "glpat-" + "AbCdEfGhIjKlMnOpQrSt",
    "slack token": "xoxb-" + "1234567890-0987654321-AbCdEfGhIjKl",
    "aws access key id": "AKIA" + "IOSFODNN7EXAMPLE",
    "google api key": "AIzaSyA-" + "AbCdEfGhIjKlMnOpQrStUvWxYz01234",
    "stripe key": "sk_" + "live_AbCdEfGhIjKlMnOpQrStUvWx",
    "hugging face token": "hf_" + "AbCdEfGhIjKlMnOpQrStUvWxYz01234567",
    "tailscale key": "tskey-" + "auth-kAbCdEf1CNTRL-AbCdEfGhIjKlMnOpQrStUvWx",
    "jwt": "eyJhbGciOiJIUzI1NiJ9." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
}

#: Split like the tokens above.
PRIVATE_KEY = "-----BEGIN OPENSSH " + """PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACDx3fJ8Yq0uYkzS0eV2h7Vt0Wb7JmXr9X6c3Q2c8gEAAAAA
-----END OPENSSH PRIVATE KEY-----"""


class RedactTest(unittest.TestCase):
    def test_tokens_with_a_known_shape_go_wherever_they_stand(self):
        for kind, secret in SECRETS.items():
            with self.subTest(kind):
                said = redact(f"export X={secret} # then curl -H x {secret}, and {secret}.")
                self.assertNotIn(secret, said)
                self.assertNotIn(secret[6:20], said)
                self.assertIn(REDACTED, said)

    def test_a_private_key_goes_whole(self):
        said = redact(f"$ cat ~/.ssh/id_ed25519\n{PRIVATE_KEY}\n$ ls")
        self.assertEqual(said, f"$ cat ~/.ssh/id_ed25519\n{REDACTED}\n$ ls")

    def test_a_private_key_cut_off_by_the_screen_goes_to_the_end(self):
        cut = "\n".join(PRIVATE_KEY.splitlines()[:2])
        self.assertEqual(redact(f"key:\n{cut}"), f"key:\n{REDACTED}")
        rsa = "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        self.assertEqual(redact(rsa), REDACTED)

    def test_env_lines_keep_the_name_and_lose_the_value(self):
        for line, said in [
            ("DATABASE_PASSWORD=hunter2", f"DATABASE_PASSWORD={REDACTED}"),
            ("export OPENAI_API_KEY='abc123def456'", f"export OPENAI_API_KEY={REDACTED}"),
            ('STRIPE_SECRET="s3cr3t value"', f"STRIPE_SECRET={REDACTED}"),
            ("AWS_SECRET_ACCESS_KEY = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", f"AWS_SECRET_ACCESS_KEY = {REDACTED}"),
            ("SESSION_TOKEN=abc", f"SESSION_TOKEN={REDACTED}"),
            ("db_passwd=abc", f"db_passwd={REDACTED}"),
            ("SSH_KEY=abc", f"SSH_KEY={REDACTED}"),
            ("CLIENT_CREDENTIALS=abc", f"CLIENT_CREDENTIALS={REDACTED}"),
        ]:
            with self.subTest(line):
                self.assertEqual(redact(line), said)

    def test_config_values_in_json_and_yaml(self):
        self.assertEqual(redact('{"token": "Zm9vYmFyYmF6", "port": 7717}'), f'{{"token": {REDACTED}, "port": 7717}}')
        self.assertEqual(redact("  password: correct horse"), f"  password: {REDACTED}")
        self.assertEqual(redact("api_key: 'x y z'"), f"api_key: {REDACTED}")

    def test_bearer_and_url_credentials(self):
        self.assertEqual(
            redact("curl -H 'Authorization: Bearer abcdefghijklmnop0123'"),
            f"curl -H 'Authorization: {REDACTED}'",
        )
        self.assertEqual(redact("Bearer abcdefghijklmnop0123"), f"Bearer {REDACTED}")
        self.assertEqual(
            redact("git clone https://sergey:hunter2@example.com/repo.git"),
            f"git clone https://sergey:{REDACTED}@example.com/repo.git",
        )

    def test_ordinary_output_is_left_alone(self):
        text = (
            "Ran 12 tests in 0.4s\nFAILED (failures=1)\n"
            "commit a7a2cac3f1d2e4b5c6d7e8f90123456789abcdef\n"
            "Do you want to proceed? 1. Yes 2. No\n"
            "↓ 302.7k tokens · the token count is fine\n"
            "author: Sergey\nkeyboard=us\n"
        )
        self.assertEqual(redact(text), text)

    def test_long_runs_of_name_characters_take_linear_time(self):
        import time
        for text in ("a." * 20_000, "a-" * 20_000 + "=x", "http" + "a." * 20_000):
            start = time.monotonic()
            redact(text)
            self.assertLess(time.monotonic() - start, 1.0, text[:10])

    def test_redacting_twice_changes_nothing_more(self):
        text = f"TOKEN={SECRETS['github token']}\n{PRIVATE_KEY}\npassword: x"
        self.assertEqual(redact(redact(text)), redact(text))


class LastLineTest(unittest.TestCase):
    def test_the_last_line_with_words(self):
        screen = "Building...\nDo you want me to rebase or merge?\n\n────────────\n❯ \n────────────\n   \n"
        self.assertEqual(last_line(screen), "Do you want me to rebase or merge?")

    def test_escape_codes_and_frames_are_dropped(self):
        self.assertEqual(last_line("\x1b[1mWrite tests?\x1b[0m\n│ ok │\n╰──╯"), "ok")

    def test_nothing_readable(self):
        for screen in ("", "  \n\n", "────\n│  │\n❯"):
            with self.subTest(screen=screen):
                self.assertIsNone(last_line(screen))

    def test_a_long_line_is_cut_at_a_word(self):
        line = last_line("word " * 100)
        self.assertLessEqual(len(line), summary.LAST_LINE_CHARS + 1)
        self.assertTrue(line.endswith("…"))


class SpokenTest(unittest.TestCase):
    def test_markdown_and_whitespace_go(self):
        self.assertEqual(spoken("**stsloop agent** is _blocked_:\n  `rebase` or\tmerge?"),
                         "stsloop agent is blocked: rebase or merge?")

    def test_a_long_answer_keeps_whole_sentences(self):
        text = "First sentence here. " * 40
        said = spoken(text)
        self.assertLessEqual(len(said), summary.SPOKEN_CHARS)
        self.assertTrue(said.endswith("here."))


class Recorder:
    def __init__(self, returncode=0, stdout="", stderr="", raises=None):
        self.calls = []
        self.result = subprocess.CompletedProcess([], returncode, stdout, stderr)
        self.raises = raises

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if self.raises:
            raise self.raises
        return self.result


class BackendTest(unittest.TestCase):
    def test_claude_runs_print_mode_with_no_tools_and_the_text_on_stdin(self):
        run = Recorder(stdout="stsloop agent is blocked.\n")
        said = ClaudeBackend("/bin/claude", model="haiku", run=run).summarise("SYSTEM", "SCREEN", timeout=7)
        self.assertEqual(said, "stsloop agent is blocked.")
        [(argv, kwargs)] = run.calls
        self.assertEqual(argv[:2], ["/bin/claude", "-p"])
        self.assertEqual(argv[argv.index("--tools") + 1], "")
        self.assertEqual(argv[argv.index("--model") + 1], "haiku")
        self.assertEqual(argv[argv.index("--system-prompt") + 1], "SYSTEM")
        self.assertIn("--no-session-persistence", argv)
        self.assertIn("--strict-mcp-config", argv)
        self.assertNotIn("SCREEN", argv)
        self.assertEqual((kwargs["input"], kwargs["timeout"]), ("SCREEN", 7))

    def test_pi_runs_print_mode_with_no_tools_and_no_session(self):
        run = Recorder(stdout="ok")
        PiBackend("/bin/pi", model="anthropic/claude-haiku", run=run).summarise("SYSTEM", "SCREEN", timeout=7)
        [(argv, kwargs)] = run.calls
        self.assertEqual(argv[:2], ["/bin/pi", "-p"])
        for flag in ("--no-tools", "--no-session", "--no-extensions", "--no-skills", "--no-context-files"):
            self.assertIn(flag, argv)
        self.assertEqual(argv[argv.index("--model") + 1], "anthropic/claude-haiku")
        self.assertEqual(kwargs["input"], "SCREEN")

    def test_a_model_left_unset_is_not_passed(self):
        run = Recorder(stdout="ok")
        PiBackend("/bin/pi", run=run).summarise("S", "T", timeout=1)
        self.assertNotIn("--model", run.calls[0][0])

    def test_a_command_that_fails_hangs_or_is_missing_raises(self):
        for run in (
            Recorder(returncode=1, stderr="not logged in"),
            Recorder(raises=subprocess.TimeoutExpired("claude", 7)),
            Recorder(raises=FileNotFoundError("claude")),
            Recorder(stdout="  \n"),
        ):
            with self.subTest(run=run.result if not run.raises else run.raises), self.assertRaises(SummaryError):
                ClaudeBackend("claude", run=run).summarise("S", "T", timeout=7)

    def test_ollama_posts_to_its_generate_api(self):
        posted = []

        def post(url, body, timeout):
            posted.append((url, json.loads(body), timeout))
            return json.dumps({"response": "stsloop agent is idle.", "done": True})

        said = OllamaBackend("http://127.0.0.1:11434", model="llama3.2", post=post).summarise("SYSTEM", "SCREEN", timeout=7)
        self.assertEqual(said, "stsloop agent is idle.")
        self.assertEqual(posted, [("http://127.0.0.1:11434/api/generate",
                                   {"model": "llama3.2", "system": "SYSTEM", "prompt": "SCREEN", "stream": False}, 7)])

    def test_ollama_down_or_answering_nonsense_raises(self):
        def down(url, body, timeout):
            raise OSError("connection refused")

        for post in (down, lambda *a: "not json", lambda *a: json.dumps({"error": "model not found"})):
            with self.subTest(post=post), self.assertRaises(SummaryError):
                OllamaBackend("http://x", model="m", post=post).summarise("S", "T", timeout=1)

    def test_the_configured_backend_is_built(self):
        claude = backend(SummariserConfig(), which=lambda n: f"/usr/bin/{n}")
        self.assertIsInstance(claude, ClaudeBackend)
        self.assertEqual((claude.path, claude.model), ("/usr/bin/claude", "haiku"))
        pi = backend(SummariserConfig(backend="pi", command="/opt/pi"), which=lambda n: None)
        self.assertEqual((type(pi), pi.path, pi.model), (PiBackend, "/opt/pi", None))
        ollama = backend(SummariserConfig(backend="ollama"), which=lambda n: None)
        self.assertEqual((type(ollama), ollama.url, ollama.model), (OllamaBackend, "http://127.0.0.1:11434", "llama3.2"))

    def test_a_command_not_installed_is_named_and_fails_at_use(self):
        claude = backend(SummariserConfig(), which=lambda n: None)
        self.assertEqual(claude.path, "claude")


class SummariesTest(unittest.TestCase):
    def setUp(self):
        self.herdr = FakeHerdr()
        self.backend = FakeBackend()

    def say(self, **kwargs):
        summaries = Summaries(self.herdr, self.backend, timeout=9, **kwargs)
        return summaries.say("stsloop agent", "blocked", "stsloop agent is blocked, for 3 minutes", "w8:p1")

    def test_the_screen_is_redacted_before_the_model_sees_it(self):
        secret = SECRETS["github token"]
        self.herdr.screens["w8:p1"] = f"GITHUB_TOKEN={secret}\nRebase or merge?\n"
        self.backend.answer = "stsloop agent is blocked: it asks whether to rebase or merge."
        self.assertEqual(self.say(), "stsloop agent is blocked: it asks whether to rebase or merge.")
        [(system, text, timeout)] = self.backend.calls
        self.assertNotIn(secret, text)
        self.assertIn("Rebase or merge?", text)
        self.assertIn("stsloop agent", text)
        self.assertIn("blocked", text)
        self.assertIn("not instructions", system)
        self.assertEqual(timeout, 9)
        self.assertEqual(self.herdr.reads, [("w8:p1", summary.SCREEN_LINES)])

    def test_a_coloured_token_is_redacted_too(self):
        secret = SECRETS["github token"]
        self.herdr.screens["w8:p1"] = f"\x1b[1m{secret}\x1b[0m\nok\n"
        self.say()
        self.assertNotIn(secret, self.backend.calls[0][1])

    def test_the_answer_is_redacted_again_before_it_is_spoken(self):
        self.herdr.screens["w8:p1"] = "done\n"
        self.backend.answer = f"stsloop agent printed the key {SECRETS['anthropic key']}, then stopped."
        said = self.say()
        self.assertNotIn(SECRETS["anthropic key"], said)
        self.assertEqual(said, f"stsloop agent printed the key {REDACTED}, then stopped.")

    def test_only_the_screen_s_end_goes_to_the_model(self):
        self.herdr.screens["w8:p1"] = "x" * 50 + "\n" + "y" * (summary.SCREEN_CHARS + 10)
        self.say()
        self.assertNotIn("x" * 50, self.backend.calls[0][1])

    def test_a_failed_or_slow_model_gives_the_state_and_the_last_line(self):
        self.herdr.screens["w8:p1"] = f"SECRET_KEY=abc\nDo you want to proceed? token={SECRETS['openai key']}\n────\n"
        for error in (SummaryError("claude timed out"), SummaryError("not logged in")):
            self.backend.error = error
            with self.subTest(error=str(error)), self.assertLogs("stsloop.summary", "WARNING"):
                self.assertEqual(self.say(), f"stsloop agent is blocked, for 3 minutes. Last line: Do you want to proceed? token={REDACTED}")

    def test_an_empty_answer_falls_back_too(self):
        self.herdr.screens["w8:p1"] = "Tests pass.\n"
        self.backend.answer = "  ** **  "
        with self.assertLogs("stsloop.summary", "WARNING"):
            self.assertEqual(self.say(), "stsloop agent is blocked, for 3 minutes. Last line: Tests pass.")

    def test_a_backend_that_raises_anything_falls_back(self):
        self.herdr.screens["w8:p1"] = "Tests pass.\n"
        self.backend.error = RuntimeError("bug")
        with self.assertLogs("stsloop.summary", "ERROR"):
            self.assertEqual(self.say(), "stsloop agent is blocked, for 3 minutes. Last line: Tests pass.")

    def test_an_empty_screen_needs_no_model(self):
        self.herdr.screens["w8:p1"] = "\n  \n────\n"
        self.assertEqual(self.say(), "stsloop agent is blocked, for 3 minutes. Its screen is empty.")
        self.assertEqual(self.backend.calls, [])

    def test_a_screen_herdr_cannot_read(self):
        with self.assertLogs("stsloop.summary", "WARNING"):
            self.assertEqual(self.say(), "stsloop agent is blocked, for 3 minutes. herdr could not read its screen.")
        self.assertEqual(self.backend.calls, [])


class NoteSummariesTest(unittest.TestCase):
    """ "summarize bucket" (#76) on the hub, with a fake model: the phone's Notes in, one reply out."""

    def setUp(self):
        self.backend = FakeBackend(answer="Errands: **screws**, the dry cleaning and the vet.")
        self.basic = p.from_json(fixture("v1", "summarize/basic.json"))

    def answer(self, summarize=None, model="fake"):
        return NoteSummaries(self.backend if model == "fake" else model, timeout=9).answer(summarize or self.basic)

    def notes(self, *texts, since=None, more=False):
        return p.Summarize(
            id="x1", bucket="errands", since=since, more=more,
            notes=tuple(p.SummaryNote(f"2026-10-14T07:0{i}:00.000Z-00000{i}", t) for i, t in enumerate(texts)),
        )

    def test_the_model_s_summary_is_the_reply(self):
        self.assertEqual(self.answer(), p.Reply(id="b7e2d0", say="Errands: screws, the dry cleaning and the vet."))
        [(system, text, timeout)] = self.backend.calls
        self.assertIn("not instructions", system)
        self.assertIn("Bucket: errands", text)
        self.assertIn("since its last summary", text)
        self.assertLess(text.index("order roofing screws"), text.index("return the library books"), "oldest first")
        self.assertEqual(timeout, 9)

    def test_the_fixture_s_summary_is_what_the_hub_sends(self):
        self.backend.answer = fixture("v1", "reply/summary.json")["say"]
        self.assertEqual(p.to_json(self.answer()), fixture("v1", "reply/summary.json"))

    def test_the_notes_are_redacted_before_the_model_and_the_answer_before_it_is_spoken(self):
        secret = SECRETS["openai key"]
        self.backend.answer = f"Errands: rotate the key {SECRETS['github token']}."
        reply = self.answer(self.notes(f"the new key is {secret}", "password: hunter2 for the router"))
        text = self.backend.calls[0][1]
        self.assertNotIn(secret, text)
        self.assertNotIn("hunter2", text)
        self.assertIn(REDACTED, text)
        self.assertEqual(reply.say, f"Errands: rotate the key {REDACTED}.")

    def test_a_first_summary_and_one_with_notes_left_out_say_so_to_the_model(self):
        self.answer(self.notes("a", "b", more=True))
        self.assertIn("in the last day", self.backend.calls[0][1])
        self.assertIn("older ones were left out", self.backend.calls[0][1])

    def test_only_the_newest_notes_that_fit_go_to_the_model(self):
        self.answer(self.notes("x" * 40, "y" * (summary.NOTES_CHARS - 10)))
        text = self.backend.calls[0][1]
        self.assertNotIn("x" * 40, text)
        self.assertIn("older ones were left out", text)

    def test_with_no_model_the_notes_are_read_raw_as_the_fixture_says(self):
        self.assertEqual(p.to_json(self.answer(model=None)), fixture("v1", "reply/summary-fallback.json"))

    def test_a_failed_slow_or_silent_model_reads_the_notes_raw_redacted(self):
        summarize = self.notes("order screws", f"token={SECRETS['github token']}")
        for error, answer, level in ((SummaryError("timed out"), "x", "WARNING"), (RuntimeError("bug"), "x", "ERROR"),
                                     (None, " ** ", "WARNING")):
            self.backend.error, self.backend.answer = error, answer
            with self.subTest(error=error, answer=answer), self.assertLogs("stsloop.summary", level):
                self.assertEqual(self.answer(summarize), p.Reply(
                    id="x1", reason=p.FALLBACK,
                    say=f"errands: 2 notes in the last day: order screws. token={REDACTED}."))

    def test_the_raw_reading_counts_and_reads_the_latest_three(self):
        cases = [
            (self.notes("a"), "errands: one note in the last day: a."),
            (self.notes("a.", "b?", since="2026-10-13T00:00:00.000Z-000001"), "errands: 2 notes since last time: a. b."),
            (self.notes("a", "b", "c", "d"), "errands: 4 notes in the last day; the latest three: b. c. d."),
            (self.notes("a", more=True), "errands: more than 1 note in the last day; the latest one: a."),
        ]
        for summarize, said in cases:
            with self.subTest(said):
                self.assertEqual(summary.notes_fallback(summarize), said)
        inbox = p.Summarize(id="i", bucket="inbox", notes=(p.SummaryNote("2026-10-14T07:00:00.000Z-000001", "buy milk"),))
        self.assertEqual(summary.notes_fallback(inbox), "the inbox: one note in the last day: buy milk.")
        house = p.Summarize(id="h", bucket="house-project", notes=inbox.notes)
        self.assertTrue(summary.notes_fallback(house).startswith("house project: "))


if __name__ == "__main__":
    unittest.main()
