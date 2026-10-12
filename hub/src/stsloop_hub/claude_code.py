"""Claude Code sessions, as their hooks tell the hub about them.

`stsloop-hub init` installs Claude Code hooks (`claude_settings.py`) that post
each hook event to the hub (`hook_server.py`), with the herdr pane the session
runs in, if any, and the pid of its Claude Code process. `Sessions` turns those
events into each session's state: what the agents Provider (`agents.py`)
publishes and says.

- **States.** `working`, `blocked`, `done` (Claude finished its turn),
  `idle` (started, nothing asked yet) and `stopped` (its turn ended on an API
  error). Each is timed from the event that began it.
- **Why blocked.** A blocked session records why (`Session.why`): `question`
  when Claude Code shows a question dialog (the AskUserQuestion tool, or an
  MCP elicitation), and `permission` when it shows a permission prompt (a
  `PermissionRequest`, or approving a plan with ExitPlanMode, which lets the
  agent start editing). Answering a question is rung 4 of the ladder;
  approving a permission is rung 5 (`docs/vision.md`).
- **Leaving blocked.** A blocked session works again when the tool it waits
  on finishes (`PostToolUse` or `PostToolUseFailure` of that tool name), and
  is done or working again at `Stop`, `StopFailure`, `UserPromptSubmit`, or
  Claude Code's `idle_prompt` notification. Other tools starting or finishing
  meanwhile (parallel tool calls, subagents) leave it blocked.
- **Gone.** A session goes at `SessionEnd`, when its Claude Code process is no
  longer running (`prune`), or after `STALE_S` with no event when the hooks
  sent no pid.
- **What it says.** Each session keeps its last few events as plain lines
  (`Session.text`): what was asked, its question and options, the tool it
  wants to use, its last message. "what does it say" summarises these lines
  for a session without herdr, as it summarises herdr's screen for one with.

Nothing here is persisted: a hub that restarts learns of each session again at
its next event. Payload text is kept in memory only, and never logged.
"""

from __future__ import annotations

import logging
import os
import re
import time
from collections import deque
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass, field

log = logging.getLogger("stsloop.claude_code")

WORKING, BLOCKED, DONE, IDLE, STOPPED = "working", "blocked", "done", "idle", "stopped"
#: A session's state before its first event is taken in.
UNKNOWN = ""
QUESTION, PERMISSION = "question", "permission"

#: Tools whose call is itself a dialog for the person, and what it asks for.
DIALOG_TOOLS = {"AskUserQuestion": QUESTION, "ExitPlanMode": PERMISSION}
#: Claude Code notifications that say a session waits on the person, and why.
WAITING_NOTIFICATIONS = {
    "permission_prompt": PERMISSION,
    "elicitation_dialog": QUESTION,
    "elicitation_url_dialog": QUESTION,
}
#: How long a session the hub cannot check on (no pid) lasts with no event.
STALE_S = 12 * 3600.0
#: How many of a session's recent events it keeps, as lines, for "what does it say".
CONTEXT_LINES = 12
#: The longest one context line may be.
LINE_CHARS = 1500

_SESSION_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.\-]{0,127}")


def _cut(text: str, limit: int = LINE_CHARS) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + "…"


def _str(obj: Mapping, key: str) -> str:
    value = obj.get(key)
    return value if isinstance(value, str) else ""


@dataclass
class Session:
    """One Claude Code session, as its hooks have described it so far."""

    id: str
    #: The directory it was first heard from: its Bucket is named after it.
    cwd: str
    #: The herdr pane it runs in, when it runs in one.
    pane: str | None
    #: Its Claude Code process, when the hook said.
    pid: int | None
    status: str
    #: When `status` is `blocked`: `question` or `permission`. Otherwise None.
    why: str | None
    #: When the hub saw `status` begin (the hub's clock).
    since: float
    #: Whether the hub saw `status` begin; if not, `since` is only when the hub first heard of the session.
    observed: bool
    #: When the hub last heard from it.
    heard: float
    #: The tool whose dialog it is blocked on, when one is (its finishing ends the block).
    waiting_on: str | None = None
    context: deque[str] = field(default_factory=lambda: deque(maxlen=CONTEXT_LINES))

    def text(self) -> str:
        """Its recent events as lines, the most recent last."""
        return "\n".join(self.context)


def alive(pid: int) -> bool:
    """Whether a process `pid` is running (one we may not signal counts)."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def _question(tool_input: Mapping) -> str:
    """AskUserQuestion's questions and their options, as a sentence each."""
    said = []
    for q in tool_input.get("questions") or []:
        if not isinstance(q, dict):
            continue
        labels = [o.get("label") for o in q.get("options") or [] if isinstance(o, dict) and isinstance(o.get("label"), str)]
        said.append(_str(q, "question") + (f" Options: {', '.join(labels)}." if labels else ""))
    return " ".join(s for s in said if s)


def _tool_use(tool_name: str, tool_input: object) -> str:
    """What a tool is asked to do, in a few words: its description, command or file."""
    if isinstance(tool_input, dict):
        for key in ("description", "command", "file_path", "url", "pattern", "prompt"):
            if isinstance(tool_input.get(key), str) and tool_input[key].strip():
                return f"{tool_name}: {_cut(tool_input[key], 300)}"
    return tool_name


class Sessions:
    """Every Claude Code session the hub has heard of, in the order it first heard of each. On the event loop only."""

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._sessions: dict[str, Session] = {}

    def __iter__(self) -> Iterator[Session]:
        return iter(list(self._sessions.values()))

    def __len__(self) -> int:
        return len(self._sessions)

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def read(self, session_id: str, lines: int) -> str:
        """The session's recent events, for the summariser (`summary.Screens`). KeyError when it has gone."""
        return "\n".join(self._sessions[session_id].text().splitlines()[-lines:])

    def take(self, payload: object, pane: str | None = None, pid: int | None = None) -> str | None:
        """Takes in one hook event. The id of the session it was about, or None when it was not one the hub
        reads (logged)."""
        if not isinstance(payload, dict):
            log.info("ignored a hook event that is not an object")
            return None
        event, session_id = _str(payload, "hook_event_name"), _str(payload, "session_id")
        if not event or not _SESSION_ID.fullmatch(session_id):
            log.info("ignored a hook event without an event name or a session id")
            return None
        now = self._clock()
        session = self._sessions.get(session_id)
        if event == "SessionEnd":
            if self._sessions.pop(session_id, None) is not None:
                log.info("Claude Code session %s ended (%s)", session_id, _str(payload, "reason") or "no reason")
            return session_id
        if session is None:
            session = Session(
                id=session_id, cwd=_str(payload, "cwd"), pane=pane, pid=pid, status=UNKNOWN, why=None,
                since=now, observed=False, heard=now,
            )
            self._sessions[session_id] = session
            log.info("Claude Code session %s in %s%s", session_id, session.cwd or "an unknown directory",
                     f", herdr pane {pane}" if pane else "")
        session.heard = now
        if pane:
            session.pane = pane
        if pid:
            session.pid = pid
        self._apply(session, event, payload, now)
        if session.status == UNKNOWN:
            # Heard of mid-turn (the hub started after the session): it works, since some time before now.
            self._set(session, WORKING, now, begins=False)
        return session_id

    def _apply(self, s: Session, event: str, payload: dict, now: float) -> None:
        tool = _str(payload, "tool_name")
        tool_input = payload.get("tool_input")
        if event == "SessionStart":
            if _str(payload, "source") != "compact":
                self._set(s, IDLE, now)
        elif event == "UserPromptSubmit":
            s.context.append(f"The owner asked: {_cut(_str(payload, 'prompt'))}")
            self._set(s, WORKING, now)
        elif event == "PreToolUse":
            if tool in DIALOG_TOOLS:
                if tool == "AskUserQuestion" and isinstance(tool_input, dict):
                    s.context.append(f"It asks: {_cut(_question(tool_input))}")
                else:
                    s.context.append("It asks the owner to approve its plan.")
                self._set(s, BLOCKED, now, why=DIALOG_TOOLS[tool], waiting_on=tool)
            else:
                s.context.append(f"It uses {_tool_use(tool, tool_input)}")
                if s.status != BLOCKED:
                    self._set(s, WORKING, now, begins=False)
        elif event == "PermissionRequest":
            why = DIALOG_TOOLS.get(tool, PERMISSION)
            if why == PERMISSION and tool != "ExitPlanMode":
                s.context.append(f"It asks permission to use {_tool_use(tool, tool_input)}")
            self._set(s, BLOCKED, now, why=why, waiting_on=tool or None)
        elif event in ("PostToolUse", "PostToolUseFailure"):
            if event == "PostToolUseFailure":
                s.context.append(f"{tool or 'A tool'} failed.")
            if s.status != BLOCKED or s.waiting_on in (None, tool):
                self._set(s, WORKING, now, begins=s.status == BLOCKED)
        elif event == "Notification":
            kind = _str(payload, "notification_type")
            if kind in WAITING_NOTIFICATIONS:
                if _str(payload, "message"):
                    s.context.append(_cut(_str(payload, "message")))
                if s.status != BLOCKED:
                    # Claude Code sends it some seconds after the dialog opened.
                    self._set(s, BLOCKED, now, why=WAITING_NOTIFICATIONS[kind], begins=False)
            elif kind == "idle_prompt" and s.status not in (DONE, IDLE, STOPPED):
                # About a minute after Claude finished: a session left blocked (a permission refused) is done.
                self._set(s, DONE, now, begins=False)
        elif event == "Stop":
            if _str(payload, "last_assistant_message"):
                s.context.append(f"It said: {_cut(_str(payload, 'last_assistant_message'))}")
            self._set(s, DONE, now)
        elif event == "StopFailure":
            detail = _str(payload, "last_assistant_message") or _str(payload, "error")
            s.context.append(f"It stopped on an error: {_cut(detail, 300)}")
            self._set(s, STOPPED, now)

    @staticmethod
    def _set(s: Session, status: str, now: float, why: str | None = None, waiting_on: str | None = None,
             begins: bool = True) -> None:
        """`s` is now in `status`. `begins`: the event marks the moment the state began, so a change of state is
        timed from it; otherwise only a change from a known state is (a session first heard of mid-turn is not)."""
        if status != s.status or why != s.why:
            s.since, s.observed = now, begins or s.status != UNKNOWN
        s.status, s.why, s.waiting_on = status, why if status == BLOCKED else None, waiting_on

    def prune(self, alive: Callable[[int], bool] = alive) -> bool:
        """Forgets the sessions whose Claude Code has stopped running, or, with no pid to check, that have been
        silent for `STALE_S`. True when any went."""
        now = self._clock()
        gone = [s.id for s in self._sessions.values()
                if (not alive(s.pid) if s.pid else now - s.heard > STALE_S)]
        for session_id in gone:
            log.info("Claude Code session %s is gone", session_id)
            del self._sessions[session_id]
        return bool(gone)
