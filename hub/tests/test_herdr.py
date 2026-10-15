"""Reading herdr: finding the binary, running only `herdr api snapshot`, and parsing its answer.
No test runs the real herdr."""

import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from stsloop_hub.herdr import (
    AGENT_READ, AGENT_READ_AS, SNAPSHOT, Agent, HerdrCli, HerdrError, Probe, binary, candidates, parse_snapshot, probe,
    resolve, version,
)

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
        with self.assertRaisesRegex(HerdrError, "protocol_mismatch") as caught:
            parse_snapshot(text)
        self.assertEqual(caught.exception.code, "protocol_mismatch")

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
    def test_it_only_ever_runs_api_snapshot_and_agent_read(self):
        run = Recorder(stdout=SNAPSHOT_TEXT)
        cli = HerdrCli("/opt/herdr", run=run)
        self.assertEqual(len(cli.agents()), 3)
        cli.read("w8:p1", 150)
        self.assertEqual(run.calls, [
            ["/opt/herdr", "api", "snapshot"],
            ["/opt/herdr", "agent", "read", "w8:p1", "--source", "recent", "--format", "text", "--lines", "150"],
        ])
        # Both only read: never send-keys, prompt, rename, focus, start or stop.
        self.assertEqual((SNAPSHOT, AGENT_READ, AGENT_READ_AS),
                         (("api", "snapshot"), ("agent", "read"), ("--source", "recent", "--format", "text")))

    def test_read_gives_the_screen_as_herdr_prints_it(self):
        cli = HerdrCli("/opt/herdr", run=Recorder(stdout="line 1\nline 2\n"))
        self.assertEqual(cli.read("w8:p1", 2), "line 1\nline 2\n")

    def test_read_of_a_pane_herdr_does_not_know_raises(self):
        run = Recorder(returncode=1, stderr='{"error":{"code":"agent_not_found","message":"agent target w9:p9 not found"}}')
        with self.assertRaisesRegex(HerdrError, "agent_not_found"):
            HerdrCli("/opt/herdr", run=run).read("w9:p9", 10)

    def test_read_never_passes_herdr_something_that_is_not_a_pane_id(self):
        run = Recorder()
        for pane in ("--help", "", "w8:p1 --x", "-w8"):
            with self.subTest(pane=pane), self.assertRaises(HerdrError):
                HerdrCli("/opt/herdr", run=run).read(pane, 10)
        self.assertEqual(run.calls, [])

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

    def test_the_binary_init_saved_wins_over_the_path(self):
        self.assertEqual(binary(env={}, which=lambda n: "/usr/bin/herdr", saved=sys.executable), sys.executable)

    def test_the_binary_herdr_names_wins_over_the_saved_one(self):
        named = os.path.realpath(sys.executable)
        self.assertEqual(binary(env={"HERDR_BIN_PATH": named}, which=lambda n: None, saved="/bin/sh"), named)

    def test_a_saved_binary_that_is_gone_falls_back_to_the_path(self):
        with self.assertLogs("stsloop.herdr", "WARNING"):
            self.assertEqual(binary(env={}, which=lambda n: "/usr/bin/herdr", saved="/nonexistent/herdr"), "/usr/bin/herdr")


MISMATCH = ('{"id":"cli:api:snapshot","error":{"code":"protocol_mismatch","message":"client protocol 20 is older '
            'than server protocol 22; upgrade the Herdr client before using this command"}}')
NOT_RUNNING = '{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running"}}'


class FakeHerdrs:
    """Several herdr binaries as `subprocess.run` sees them: each path has its own `--version` line and
    snapshot answer. Nothing real is ever run, and anything but those two fails the test."""

    def __init__(self, binaries: dict[str, tuple[str, int, str]]):
        #: path -> (version line, snapshot exit status, snapshot output: stdout when 0, else stderr)
        self.binaries = binaries
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        line, code, output = self.binaries[argv[0]]
        if argv[1:] == ["--version"]:
            return subprocess.CompletedProcess(argv, 0, line + "\n", "")
        if tuple(argv[1:]) == SNAPSHOT:
            return subprocess.CompletedProcess(argv, code, output if code == 0 else "", output if code else "")
        raise AssertionError(f"a probe ran {argv}")


class ResolveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def herdr(self, rel: str, executable: bool = True) -> str:
        """A file standing in for a herdr binary; never run."""
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/false\n")
        if executable:
            path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return str(path)

    def test_candidates_are_every_herdr_on_the_path_then_local_bin(self):
        old, new = self.herdr("usr/bin/herdr"), self.herdr("home/.local/bin/herdr")
        self.herdr("opt/bin/herdr", executable=False)
        path = [str(self.root / "usr/bin"), str(self.root / "opt/bin"), str(self.root / "none")]
        self.assertEqual(candidates({"PATH": os.pathsep.join(path), "HOME": str(self.root / "home")}), [old, new])

    def test_herdr_bin_path_comes_first_and_each_binary_once(self):
        new = self.herdr("home/.local/bin/herdr")
        os.symlink(new, self.root / "herdr")
        env = {"HERDR_BIN_PATH": new, "PATH": os.pathsep.join([str(self.root), str(self.root / "home/.local/bin")]),
               "HOME": str(self.root / "home")}
        self.assertEqual(candidates(env), [new], "the same file under another name is tried once")

    def test_no_herdr_anywhere(self):
        self.assertEqual(candidates({"PATH": str(self.root), "HOME": str(self.root)}), [])

    def test_version_is_what_herdr_prints(self):
        self.assertEqual(version("/opt/herdr", FakeHerdrs({"/opt/herdr": ("herdr 0.9.1", 0, SNAPSHOT_TEXT)})), "0.9.1")
        self.assertIsNone(version("/opt/herdr", FakeHerdrs({"/opt/herdr": ("something else", 0, "")})))
        self.assertIsNone(version("/opt/herdr", Recorder(raises=FileNotFoundError("gone"))))

    def test_a_probe_reads_the_version_and_one_snapshot_and_nothing_else(self):
        run = FakeHerdrs({
            "/usr/bin/herdr": ("herdr 0.8.2", 1, MISMATCH),
            "/home/me/.local/bin/herdr": ("herdr 0.9.1", 0, SNAPSHOT_TEXT),
        })
        old, new = probe("/usr/bin/herdr", run), probe("/home/me/.local/bin/herdr", run)
        self.assertEqual((old.version, old.answers, old.code), ("0.8.2", False, "protocol_mismatch"))
        self.assertIn("client protocol 20", old.error)
        self.assertEqual(new, Probe("/home/me/.local/bin/herdr", "0.9.1", answers=True))
        self.assertEqual({tuple(c[1:]) for c in run.calls}, {("--version",), SNAPSHOT}, "read-only")

    def test_resolve_takes_the_first_the_server_answers(self):
        run = FakeHerdrs({
            "/usr/bin/herdr": ("herdr 0.8.2", 1, MISMATCH),
            "/home/me/.local/bin/herdr": ("herdr 0.9.1", 0, SNAPSHOT_TEXT),
            "/later/herdr": ("herdr 0.9.1", 0, SNAPSHOT_TEXT),
        })
        found, tried = resolve(["/usr/bin/herdr", "/home/me/.local/bin/herdr", "/later/herdr"], lambda p: probe(p, run))
        self.assertEqual(found.path, "/home/me/.local/bin/herdr")
        self.assertEqual([t.path for t in tried], ["/usr/bin/herdr", "/home/me/.local/bin/herdr"], "no further")

    def test_resolve_finds_none_without_a_server(self):
        run = FakeHerdrs({"/usr/bin/herdr": ("herdr 0.8.2", 1, NOT_RUNNING)})
        found, [tried] = resolve(["/usr/bin/herdr"], lambda p: probe(p, run))
        self.assertIsNone(found)
        self.assertEqual(tried.code, "server_not_running")


if __name__ == "__main__":
    unittest.main()
