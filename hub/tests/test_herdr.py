"""Reading herdr: finding the binary, running only `herdr api snapshot`, and parsing its answer.
No test runs the real herdr."""

import json
import subprocess
import unittest

from stsloop_hub.herdr import SNAPSHOT, Agent, HerdrCli, HerdrError, binary, parse_snapshot

#: The shape of `herdr api snapshot` (herdr 0.9, protocol 22), trimmed to what the hub reads plus a few extras.
SNAPSHOT_TEXT = json.dumps({
    "id": "cli:api:snapshot",
    "result": {"snapshot": {
        "agents": [
            {"agent": "claude", "agent_status": "blocked", "cwd": "/src/stsloop", "focused": False,
             "pane_id": "w8:p1", "revision": 9, "state_change_seq": 322, "tab_id": "w8:t1",
             "terminal_title": "x", "workspace_id": "w8"},
            {"agent": "codex", "agent_status": "working", "pane_id": "w9:p2", "state_change_seq": 4,
             "workspace_id": "w9"},
            {"agent": "pi", "pane_id": "w3:p1", "workspace_id": "w3"},
            {"agent": "broken"},
        ],
        "workspaces": [
            {"label": "stsloop", "workspace_id": "w8", "agent_status": "blocked"},
            {"label": "  ", "workspace_id": "w9"},
            {"label": "lythonic", "workspace_id": "w3"},
        ],
        "panes": [], "tabs": [], "layouts": [], "version": "0.9.1",
    }},
})


class ParseTest(unittest.TestCase):
    def test_agents_are_read_with_their_workspace_labels(self):
        self.assertEqual(parse_snapshot(SNAPSHOT_TEXT), [
            Agent("w8:p1", "w8", "stsloop", "blocked", 322),
            Agent("w9:p2", "w9", "w9", "working", 4),
            Agent("w3:p1", "w3", "lythonic", "unknown", -1),
        ])

    def test_an_error_answer_raises(self):
        text = '{"id":"cli:api:snapshot","error":{"code":"protocol_mismatch","message":"client protocol 20 is older"}}'
        with self.assertRaisesRegex(HerdrError, "protocol_mismatch"):
            parse_snapshot(text)

    def test_an_unreadable_answer_raises(self):
        for text in ("", "nope", "[]", '{"result": {}}'):
            with self.subTest(text=text), self.assertRaises(HerdrError):
                parse_snapshot(text)


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


class CliTest(unittest.TestCase):
    def test_it_only_ever_runs_api_snapshot(self):
        run = Recorder(stdout=SNAPSHOT_TEXT)
        cli = HerdrCli("/opt/herdr", run=run)
        self.assertEqual(len(cli.agents()), 3)
        self.assertEqual(run.calls, [["/opt/herdr", "api", "snapshot"]])
        self.assertEqual(SNAPSHOT, ("api", "snapshot"))

    def test_an_error_on_stderr_raises(self):
        run = Recorder(returncode=1, stderr='{"error":{"code":"server_unavailable","message":"no server"}}')
        with self.assertRaisesRegex(HerdrError, "server_unavailable"):
            HerdrCli("/opt/herdr", run=run).agents()

    def test_a_herdr_that_cannot_run_or_hangs_raises(self):
        for error in (FileNotFoundError("gone"), subprocess.TimeoutExpired("herdr", 5)):
            with self.subTest(error=type(error).__name__), self.assertRaises(HerdrError):
                HerdrCli("/opt/herdr", run=Recorder(raises=error)).agents()


class BinaryTest(unittest.TestCase):
    def test_not_installed(self):
        self.assertIsNone(binary(env={}, which=lambda name: None))

    def test_on_the_path(self):
        self.assertEqual(binary(env={}, which=lambda name: f"/usr/bin/{name}"), "/usr/bin/herdr")

    def test_the_binary_herdr_names_wins_over_the_path(self):
        import sys
        self.assertEqual(binary(env={"HERDR_BIN_PATH": sys.executable}, which=lambda n: "/usr/bin/herdr"), sys.executable)

    def test_a_named_binary_that_is_not_there_falls_back_to_the_path(self):
        self.assertEqual(binary(env={"HERDR_BIN_PATH": "/nonexistent/herdr"}, which=lambda n: "/usr/bin/herdr"), "/usr/bin/herdr")


if __name__ == "__main__":
    unittest.main()
