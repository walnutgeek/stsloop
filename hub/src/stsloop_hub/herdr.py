"""herdr, the terminal multiplexer for coding agents, as the hub uses it.

The hub's herdr Provider (`agents.py`) sees herdr only through `Herdr`, so its
tests run against a fake. `HerdrCli` is the real one, and it runs three herdr
commands only: two reads, `herdr api snapshot` for the agents and `herdr
agent read` for one agent's recent output ("what does it say"), and `herdr
agent prompt <pane> <text>`, to submit a Draft the person sent (rung 4). The
hub never sends keys, renames, focuses, starts or stops anything in herdr.

herdr's agent detection states are `idle`, `working`, `blocked`, `done` and
`unknown` (`herdr api schema`, `AgentStatus`): `blocked` means herdr sees an
approval or question UI; `done` is `idle` after background work nobody has
looked at yet.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Protocol

log = logging.getLogger("stsloop.herdr")

#: The herdr commands the hub runs: the snapshot, an agent's recent output
#: (`AGENT_READ` + its pane id + `AGENT_READ_AS`), and submitting a Draft (`PROMPT` + pane id + text).
SNAPSHOT = ("api", "snapshot")
AGENT_READ = ("agent", "read")
AGENT_READ_AS = ("--source", "recent", "--format", "text")
PROMPT = ("agent", "prompt")
TIMEOUT_S = 5.0
#: A pane id as herdr writes them ("w8:p1"); anything else is never passed to herdr.
_PANE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9:_.\-]*")
#: herdr's error codes for a prompt it refused before writing anything to the agent. Any other
#: failure may have come after the text was written, so it is not known whether the agent got it.
NOT_SENT = frozenset({"agent_blocked", "agent_not_found", "protocol_mismatch"})
#: herdr's exit status for a command line it could not parse: nothing was sent.
USAGE = 2


@dataclass(frozen=True)
class Agent:
    """One coding agent herdr recognises in a pane."""

    pane_id: str
    workspace_id: str
    #: The workspace's label, which names the agent's Bucket.
    workspace: str
    #: herdr's detection state: idle, working, blocked, done or unknown (or a newer one).
    status: str
    #: herdr's `state_change_seq`: it moves whenever the agent's state changes, even and back.
    seq: int


class HerdrError(Exception):
    """herdr is installed but did not answer: its server is down, or speaks another protocol. For a prompt:
    it is not known whether the agent got it."""


class HerdrRefused(HerdrError):
    """herdr refused a prompt before writing anything to the agent; `code` is herdr's reason."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(f"herdr: {code}: {message}" if message else f"herdr: {code}")
        self.code = code


class Herdr(Protocol):
    def agents(self) -> list[Agent]:
        """Every agent herdr sees now, in herdr's order. Raises HerdrError when herdr does not answer."""
        ...

    def read(self, pane_id: str, lines: int) -> str:
        """The last `lines` lines of the agent's terminal, as text. Raises HerdrError when herdr cannot read it."""
        ...

    def prompt(self, pane_id: str, text: str) -> None:
        """Submits `text` to the agent in `pane_id`, as typed and then Enter. Blocking. Raises HerdrRefused
        when herdr sent nothing, and HerdrError when it is not known whether the agent got it."""
        ...


def binary(env: Mapping[str, str] = os.environ, which: Callable[[str], str | None] = shutil.which) -> str | None:
    """The herdr binary: the one herdr names in `$HERDR_BIN_PATH` (set inside its panes), else `herdr` on
    the PATH. None when herdr is not installed."""
    named = env.get("HERDR_BIN_PATH")
    if named and os.access(named, os.X_OK):
        return named
    return which("herdr")


def parse_snapshot(text: str) -> list[Agent]:
    """The agents of `herdr api snapshot`'s output. Raises HerdrError for an error or an unreadable answer."""
    try:
        obj = json.loads(text)
    except ValueError as e:
        raise HerdrError(f"herdr answered something that is not JSON: {e}") from None
    if not isinstance(obj, dict):
        raise HerdrError("herdr answered something that is not an object")
    if "error" in obj:
        error = obj["error"] if isinstance(obj["error"], dict) else {}
        raise HerdrError(f"herdr: {error.get('code', 'error')}: {error.get('message', obj['error'])}")
    snapshot = (obj.get("result") or {}).get("snapshot")
    if not isinstance(snapshot, dict):
        raise HerdrError("herdr's answer has no snapshot")
    labels = {
        w.get("workspace_id"): w.get("label")
        for w in snapshot.get("workspaces") or []
        if isinstance(w, dict)
    }
    agents = []
    for a in snapshot.get("agents") or []:
        if not isinstance(a, dict) or not isinstance(a.get("pane_id"), str) or not a["pane_id"]:
            continue
        workspace_id = a.get("workspace_id") if isinstance(a.get("workspace_id"), str) else ""
        label = labels.get(workspace_id)
        seq = a.get("state_change_seq")
        agents.append(Agent(
            pane_id=a["pane_id"],
            workspace_id=workspace_id,
            workspace=label if isinstance(label, str) and label.strip() else workspace_id,
            status=a.get("agent_status") if isinstance(a.get("agent_status"), str) else "unknown",
            seq=seq if isinstance(seq, int) and not isinstance(seq, bool) else -1,
        ))
    return agents


class HerdrCli:
    """herdr through its CLI. Each call runs one read-only herdr command once, with a timeout."""

    def __init__(self, path: str, run=subprocess.run, timeout: float = TIMEOUT_S):
        self.path = path
        self._run = run
        self._timeout = timeout

    def agents(self) -> list[Agent]:
        result = self._call([self.path, *SNAPSHOT])
        # herdr prints its errors as JSON on stderr, with exit status 1.
        return parse_snapshot(result.stdout if result.returncode == 0 else (result.stderr or result.stdout))

    def read(self, pane_id: str, lines: int) -> str:
        if not _PANE_ID.fullmatch(pane_id):
            raise HerdrError(f"not a pane id: {pane_id!r}")
        # herdr takes the target first: options before it are refused.
        result = self._call([self.path, *AGENT_READ, pane_id, *AGENT_READ_AS, "--lines", str(lines)])
        if result.returncode != 0:
            raise HerdrError(f"herdr could not read {pane_id}: {_error(result.stderr or result.stdout)}")
        return result.stdout

    def _call(self, argv: list[str]) -> subprocess.CompletedProcess:
        try:
            return self._run(argv, capture_output=True, text=True, timeout=self._timeout, stdin=subprocess.DEVNULL)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise HerdrError(f"cannot run {self.path}: {e}") from None

    def prompt(self, pane_id: str, text: str) -> None:
        if not _PANE_ID.fullmatch(pane_id):
            raise HerdrRefused("not_a_pane", repr(pane_id))
        # herdr reads an argument starting with "-" as an option, so such a text is given a leading space.
        argv = [self.path, *PROMPT, pane_id, f" {text}" if text.startswith("-") else text]
        try:
            result = self._run(
                argv, capture_output=True, text=True, timeout=self._timeout, stdin=subprocess.DEVNULL,
            )
        except subprocess.TimeoutExpired:
            raise HerdrError(f"herdr agent prompt did not finish in {self._timeout:g} s") from None
        except (OSError, ValueError) as e:  # herdr never ran (a NUL in the text is a ValueError)
            raise HerdrRefused("not_run", str(e)) from None
        if result.returncode == 0:
            return
        if result.returncode == USAGE:
            raise HerdrRefused("usage", (result.stderr or "").strip())
        code, message = prompt_error(result.stderr or result.stdout)
        if code in NOT_SENT:
            raise HerdrRefused(code, message)
        raise HerdrError(f"herdr agent prompt: {code}: {message}")


def _error(text: str) -> str:
    """herdr's JSON error as "code: message", or its text as it is."""
    try:
        error = json.loads(text).get("error")
    except (ValueError, AttributeError):
        return text.strip() or "no output"
    if isinstance(error, dict):
        return f"{error.get('code', 'error')}: {error.get('message', '')}"
    return str(error)


def prompt_error(text: str) -> tuple[str, str]:
    """herdr's error code and message from a failed command's output; ("unknown", the text) when unreadable."""
    try:
        error = json.loads(text).get("error")
    except (ValueError, AttributeError):
        error = None
    if not isinstance(error, dict) or not isinstance(error.get("code"), str):
        return "unknown", (text or "").strip()
    return error["code"], str(error.get("message", ""))
