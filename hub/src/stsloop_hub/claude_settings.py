"""The Claude Code hooks `stsloop-hub init` offers to install, and how it adds and removes them.

The hooks go in Claude Code's user settings (`~/.claude/settings.json`, or
`$CLAUDE_CONFIG_DIR/settings.json`), so every session reports to the hub;
`--claude-settings` picks another file, a project's `.claude/settings.json`
say. Claude Code merges hooks from all its settings files and picks up edits
while it runs.

Each hook is one shell command that posts the event's JSON (on its stdin) to
the hub with `curl`, and always exits 0 with no output: whether the hub is
running or not, a hook never blocks, decides or prints anything in Claude
Code. It sends the herdr pane (`$HERDR_PANE_ID`) and the Claude Code process
(`$PPID`, the hook shell's parent) as headers (`hook_server.py`). A hook runs
in under a few milliseconds, and gives up after `CURL_TIMEOUT_S`.

Only the events the hub reads are hooked: a session's prompts, dialogs
(AskUserQuestion and ExitPlanMode), permission prompts, tools finishing (the
end of a dialog), the notifications that mean it waits on the person, the end
of its turn, and its start and end (`claude_code.py`).

The hub's hooks are the command handlers whose command contains `MARKER`.
Installing replaces them and adds nothing else; removing takes out exactly
those, and the matcher groups and events they leave empty. Everything else in
the file is kept as it was, though the file is written back as JSON indented
by two spaces. The file as it was before each change is kept beside it, as
`settings.json.stsloop-hub.bak`.
"""

from __future__ import annotations

import copy
import json
import os
import stat
from collections.abc import Mapping
from pathlib import Path

from .hook_server import HOST, PATH

MARKER = PATH
CURL_TIMEOUT_S = 2
#: Claude Code's own limit on each hook, in seconds: curl gives up well before it.
HOOK_TIMEOUT_S = 5
BACKUP_SUFFIX = ".stsloop-hub.bak"
#: Each hooked event, and its matcher (None: every occurrence).
EVENTS: tuple[tuple[str, str | None], ...] = (
    ("SessionStart", None),
    ("UserPromptSubmit", None),
    ("PreToolUse", "AskUserQuestion|ExitPlanMode"),
    ("PermissionRequest", None),
    ("PostToolUse", None),
    ("PostToolUseFailure", None),
    ("Notification", "permission_prompt|idle_prompt|elicitation_dialog|elicitation_url_dialog"),
    ("Stop", None),
    ("StopFailure", None),
    ("SessionEnd", None),
)


class SettingsError(Exception):
    """The settings file cannot be read as Claude Code settings; it is left alone."""


def default_path(env: Mapping[str, str] = os.environ) -> Path:
    """Claude Code's user settings file."""
    if env.get("CLAUDE_CONFIG_DIR"):
        return Path(env["CLAUDE_CONFIG_DIR"]) / "settings.json"
    return Path(env.get("HOME") or Path.home()) / ".claude" / "settings.json"


def command(port: int) -> str:
    """The shell command each hook runs."""
    return (
        f"curl -s -m {CURL_TIMEOUT_S} -o /dev/null -H 'Content-Type: application/json'"
        ' -H "X-Herdr-Pane: ${HERDR_PANE_ID:-}" -H "X-Claude-Pid: $PPID"'
        f" --data-binary @- http://{HOST}:{port}{PATH} >/dev/null 2>&1 || true"
    )


def hooks(port: int) -> dict:
    """The `hooks` the hub installs, as they appear in the settings file."""
    handler = {"type": "command", "command": command(port), "timeout": HOOK_TIMEOUT_S}
    out = {}
    for event, matcher in EVENTS:
        group = {"matcher": matcher} if matcher else {}
        group["hooks"] = [dict(handler)]
        out[event] = [group]
    return out


def load(path: Path) -> dict:
    """The settings in `path`; empty when there is no file. Raises SettingsError when they cannot be read."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return {}
    except OSError as e:
        raise SettingsError(f"cannot read {path}: {e}") from None
    if not text.strip():
        return {}
    try:
        settings = json.loads(text)
    except ValueError as e:
        raise SettingsError(f"{path} is not JSON ({e}); fix it, or add the hooks by hand") from None
    if not isinstance(settings, dict):
        raise SettingsError(f"{path} is not a JSON object")
    if "hooks" in settings and not isinstance(settings["hooks"], dict):
        raise SettingsError(f"{path}: hooks is not an object")
    return settings


def _ours(handler: object) -> bool:
    return isinstance(handler, dict) and isinstance(handler.get("command"), str) and MARKER in handler["command"]


def has_ours(settings: dict) -> bool:
    """Whether any of the hub's hooks are in `settings`."""
    return _count(settings) > 0


def without_ours(settings: dict) -> dict:
    """`settings` with the hub's hooks taken out, and the groups and events they leave empty."""
    out = copy.deepcopy(settings)
    if not isinstance(out.get("hooks"), dict):
        return out
    events = {}
    for event, groups in out["hooks"].items():
        if not isinstance(groups, list):
            events[event] = groups
            continue
        kept = []
        for g in groups:
            if isinstance(g, dict) and isinstance(g.get("hooks"), list) and any(_ours(h) for h in g["hooks"]):
                g = dict(g, hooks=[h for h in g["hooks"] if not _ours(h)])
                if not g["hooks"]:
                    continue
            kept.append(g)
        if kept or not groups:
            events[event] = kept
    if events:
        out["hooks"] = events
    else:
        del out["hooks"]
    return out


def with_ours(settings: dict, port: int) -> dict:
    """`settings` with the hub's hooks, and no older ones of the hub's, after everything already there."""
    out = without_ours(settings)
    merged = dict(out.get("hooks") or {})
    for event, groups in hooks(port).items():
        merged[event] = list(merged.get(event) or []) + groups
    out["hooks"] = merged
    return out


def installed(settings: dict, port: int) -> bool:
    """Whether `settings` hold exactly the hub's hooks for `port`."""
    present = settings.get("hooks") or {}
    return _count(settings) == len(EVENTS) and all(
        isinstance(present.get(event), list) and group in present[event] for event, [group] in hooks(port).items()
    )


def _count(settings: dict) -> int:
    return sum(
        1
        for groups in (settings.get("hooks") or {}).values() if isinstance(groups, list)
        for g in groups if isinstance(g, dict) and isinstance(g.get("hooks"), list)
        for h in g["hooks"] if _ours(h)
    )


def save(path: Path, settings: dict) -> None:
    """Writes `settings` to `path` whole (it never holds half a file), keeping its mode, and the file it
    replaces as its backup. A symlink is followed: its target is written, and the link kept."""
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = 0o600
    if path.exists():
        mode = stat.S_IMODE(path.stat().st_mode)
        backup = path.with_name(path.name + BACKUP_SUFFIX)
        backup.write_bytes(path.read_bytes())
        os.chmod(backup, mode)
    tmp = path.with_name(f".{path.name}.stsloop-hub.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(settings, f, indent=2, ensure_ascii=False)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, mode)
    os.replace(tmp, path)
