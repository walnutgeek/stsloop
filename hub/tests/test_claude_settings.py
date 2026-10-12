"""The Claude Code hooks `init` installs, against settings files in a temporary directory only."""

import json
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from stsloop_hub import claude_settings as cs

#: Someone's own settings: hooks of their own on two of the events the hub uses, and other keys.
THEIRS = {
    "model": "opus",
    "permissions": {"allow": ["Bash(git status)"]},
    "hooks": {
        "Stop": [{"hooks": [{"type": "command", "command": "notify-send done"}]}],
        "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "./check.sh"}]}],
    },
}


class HooksTest(unittest.TestCase):
    def test_every_hooked_event_runs_one_command_with_a_timeout(self):
        hooks = cs.hooks(7718)
        self.assertEqual(list(hooks), [e for e, _ in cs.EVENTS])
        self.assertEqual(hooks["PreToolUse"][0]["matcher"], "AskUserQuestion|ExitPlanMode")
        self.assertNotIn("matcher", hooks["Stop"][0])
        for groups in hooks.values():
            [group] = groups
            [handler] = group["hooks"]
            self.assertEqual(handler, {"type": "command", "command": cs.command(7718), "timeout": 5})

    def test_the_command_posts_stdin_to_the_hub_on_loopback_and_always_succeeds(self):
        command = cs.command(7718)
        self.assertIn("http://127.0.0.1:7718/stsloop-hub/claude-code", command)
        self.assertIn("--data-binary @-", command)
        self.assertIn('"X-Herdr-Pane: ${HERDR_PANE_ID:-}"', command)
        self.assertIn('"X-Claude-Pid: $PPID"', command)
        self.assertTrue(command.endswith("|| true"))

    def test_the_command_exits_0_silently_with_no_hub(self):
        # Port 9 (discard) on loopback: nothing listens there.
        result = subprocess.run(["sh", "-c", cs.command(9)], input='{"hook_event_name":"Stop"}',
                                capture_output=True, text=True, timeout=10)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))


class InstallTest(unittest.TestCase):
    def test_installing_adds_after_what_is_there_and_changes_nothing_else(self):
        merged = cs.with_ours(THEIRS, 7718)
        self.assertEqual({k: v for k, v in merged.items() if k != "hooks"}, {"model": "opus", "permissions": THEIRS["permissions"]})
        self.assertEqual(merged["hooks"]["Stop"][0], THEIRS["hooks"]["Stop"][0])
        self.assertEqual(merged["hooks"]["Stop"][1], cs.hooks(7718)["Stop"][0])
        self.assertEqual(merged["hooks"]["PreToolUse"][0], THEIRS["hooks"]["PreToolUse"][0])
        self.assertEqual(THEIRS["hooks"]["Stop"], [{"hooks": [{"type": "command", "command": "notify-send done"}]}],
                         "the original is not changed")

    def test_removing_gives_back_exactly_what_was_there(self):
        self.assertEqual(cs.without_ours(cs.with_ours(THEIRS, 7718)), THEIRS)
        self.assertEqual(cs.without_ours(cs.with_ours({}, 7718)), {})

    def test_installing_again_replaces_and_never_duplicates(self):
        once = cs.with_ours(THEIRS, 7718)
        self.assertEqual(cs.with_ours(once, 7718), once)
        moved = cs.with_ours(once, 7799)
        self.assertNotIn("7718", json.dumps(moved))
        self.assertEqual(cs.without_ours(moved), THEIRS)

    def test_installed_means_exactly_ours_for_that_port(self):
        self.assertFalse(cs.installed(THEIRS, 7718))
        once = cs.with_ours(THEIRS, 7718)
        self.assertTrue(cs.installed(once, 7718))
        self.assertFalse(cs.installed(once, 7799))
        partial = json.loads(json.dumps(once))
        del partial["hooks"]["Notification"]
        self.assertFalse(cs.installed(partial, 7718))
        self.assertTrue(cs.has_ours(partial))

    def test_a_handler_of_ours_sharing_a_group_with_theirs_leaves_theirs(self):
        mixed = {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "notify-send done"},
                                                cs.hooks(7718)["Stop"][0]["hooks"][0]]}]}}
        self.assertEqual(cs.without_ours(mixed), {"hooks": {"Stop": THEIRS["hooks"]["Stop"]}})


class FileTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / ".claude" / "settings.json"

    def test_no_file_reads_as_no_settings(self):
        self.assertEqual(cs.load(self.path), {})

    def test_a_file_that_is_not_settings_is_refused(self):
        self.path.parent.mkdir()
        for text in ("{nope", "[]", '{"hooks": []}'):
            with self.subTest(text=text):
                self.path.write_text(text)
                with self.assertRaises(cs.SettingsError):
                    cs.load(self.path)

    def test_saving_keeps_the_mode_and_a_backup_of_the_file_before(self):
        self.path.parent.mkdir()
        self.path.write_text(json.dumps(THEIRS))
        self.path.chmod(0o640)
        cs.save(self.path, cs.with_ours(cs.load(self.path), 7718))
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o640)
        self.assertEqual(json.loads((self.dir / ".claude" / "settings.json.stsloop-hub.bak").read_text()), THEIRS)
        self.assertTrue(cs.installed(cs.load(self.path), 7718))
        self.assertEqual(sorted(p.name for p in self.path.parent.iterdir()), ["settings.json", "settings.json.stsloop-hub.bak"])

    def test_a_new_file_is_only_the_users(self):
        cs.save(self.path, cs.with_ours({}, 7718))
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_the_default_file(self):
        self.assertEqual(cs.default_path({"HOME": "/h"}), Path("/h/.claude/settings.json"))
        self.assertEqual(cs.default_path({"HOME": "/h", "CLAUDE_CONFIG_DIR": "/c"}), Path("/c/settings.json"))


if __name__ == "__main__":
    unittest.main()
