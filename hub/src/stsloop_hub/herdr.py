"""Reading herdr, the terminal multiplexer for coding agents, read-only.

The hub's herdr Provider (`agents.py`) sees herdr only through `Herdr`, so its
tests run against a fake. `HerdrCli` is the real one: it runs `herdr api
snapshot`, the one herdr command it ever runs. The hub never sends keys or
prompts, renames, focuses, starts or stops anything in herdr.

herdr's agent detection states are `idle`, `working`, `blocked`, `done` and
`unknown` (`herdr api schema`, `AgentStatus`): `blocked` means herdr sees an
approval or question UI; `done` is `idle` after background work nobody has
looked at yet.
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Protocol

log = logging.getLogger("stsloop.herdr")

#: The only herdr command the hub runs.
SNAPSHOT = ("api", "snapshot")
TIMEOUT_S = 5.0


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
    """herdr is installed but did not answer: its server is down, or speaks another protocol."""


class Herdr(Protocol):
    def agents(self) -> list[Agent]:
        """Every agent herdr sees now, in herdr's order. Raises HerdrError when herdr does not answer."""
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
    """herdr through its CLI. Each call runs `herdr api snapshot` once, with a timeout."""

    def __init__(self, path: str, run=subprocess.run, timeout: float = TIMEOUT_S):
        self.path = path
        self._run = run
        self._timeout = timeout

    def agents(self) -> list[Agent]:
        try:
            result = self._run(
                [self.path, *SNAPSHOT], capture_output=True, text=True, timeout=self._timeout,
                stdin=subprocess.DEVNULL,
            )
        except (OSError, subprocess.TimeoutExpired) as e:
            raise HerdrError(f"cannot run {self.path}: {e}") from None
        # herdr prints its errors as JSON on stderr, with exit status 1.
        return parse_snapshot(result.stdout if result.returncode == 0 else (result.stderr or result.stdout))
