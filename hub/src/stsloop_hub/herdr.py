"""herdr, the terminal multiplexer for coding agents, as the hub uses it.

The hub's herdr Provider (`agents.py`) sees herdr only through `Herdr`, so its
tests run against a fake. `HerdrCli` is the real one, and it runs five herdr
commands only: two reads, `herdr api snapshot` for the agents and `herdr
agent read` for one agent's recent output ("what does it say"), and three
writes, all rung 4: `herdr agent prompt <pane> <text>`, to submit a Draft the
person sent, and `herdr agent send-keys <pane> <key>` (a digit, Enter or Tab
only) with `herdr pane send-text <pane> <text>`, to answer a question dialog
the person answered (`dialogs.py`). The hub never renames, focuses, starts or
stops anything in herdr.

Which herdr binary: a herdr client only talks to a herdr server of its own
protocol, and an older herdr earlier on the PATH (`/usr/bin/herdr` 0.8.2
before `~/.local/bin/herdr` 0.9.1) gets `protocol_mismatch` from the running
server. So `stsloop-hub init` tries each `candidates` binary with `probe`
(`herdr --version` and one `herdr api snapshot`, both read-only) and saves
the first the server answers (`resolve`) in `hub.json`; `serve` runs that one
(`binary`), unless `$HERDR_BIN_PATH` names one, as it does inside herdr's panes.

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
#: Answering a question dialog (#80): key presses to the agent (`SEND_KEYS` + pane id + keys), and the text of a
#: free-text answer (`SEND_TEXT` + pane id + text), which `agent send-keys` cannot type.
SEND_KEYS = ("agent", "send-keys")
SEND_TEXT = ("pane", "send-text")
#: The only keys the hub ever presses: an option's digit, Enter and Tab.
_KEY = re.compile(r"[1-9]|enter|tab")
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
    it is not known whether the agent got it. `code` is herdr's own error code when it gave one
    (`protocol_mismatch`, `server_not_running`)."""

    def __init__(self, message: str = "", code: str | None = None):
        super().__init__(message)
        self.code = code


class HerdrRefused(HerdrError):
    """herdr refused a prompt before writing anything to the agent; `code` is herdr's reason."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(f"herdr: {code}: {message}" if message else f"herdr: {code}", code)


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

    def send_keys(self, pane_id: str, keys: list[str]) -> None:
        """Presses `keys` (herdr's key names: a digit, `enter`, `tab`) in the agent's terminal. Blocking. Raises
        as `prompt` does."""
        ...

    def send_text(self, pane_id: str, text: str) -> None:
        """Types `text`, as it is, into the terminal of the pane `pane_id`. Blocking. Raises as `prompt` does."""
        ...


def binary(
    env: Mapping[str, str] = os.environ,
    which: Callable[[str], str | None] | None = None,
    saved: str | None = None,
) -> str | None:
    """The herdr binary: the one herdr names in `$HERDR_BIN_PATH` (set inside its panes), else the one
    `init` saved (`saved`, which matched the running herdr server then), else `herdr` on `env`'s PATH.
    None when herdr is not installed."""
    for path in (env.get("HERDR_BIN_PATH"), saved):
        if path and _executable(path):
            return path
    if saved:
        log.warning("the herdr init saved, %s, is not there any more; using the PATH's", saved)
    if which is None:
        return shutil.which("herdr", path=env["PATH"]) if env.get("PATH") else None
    return which("herdr")


def _executable(path: str) -> bool:
    return os.path.isfile(path) and os.access(path, os.X_OK)


# --- finding the herdr that matches the running server (`stsloop-hub init`) -------------------

#: What `herdr --version` prints: "herdr 0.9.1".
VERSION = ("--version",)
_VERSION = re.compile(r"\bherdr\s+v?(\S+)")


@dataclass(frozen=True)
class Probe:
    """One herdr binary, tried against the running herdr server with `herdr api snapshot`."""

    path: str
    #: What `herdr --version` says ("0.9.1"); None when it could not tell.
    version: str | None
    #: True when its snapshot answered: it speaks the running server's protocol.
    answers: bool
    #: herdr's error code when it did not answer (`protocol_mismatch`, `server_not_running`), else None.
    code: str | None = None
    #: Why it did not answer, as herdr or the system said it.
    error: str | None = None

    def describe(self) -> str:
        """For the person: "/usr/bin/herdr (0.8.2)"."""
        return f"{self.path} ({self.version})" if self.version else self.path


def candidates(env: Mapping[str, str] = os.environ, executable: Callable[[str], bool] = _executable) -> list[str]:
    """Every herdr binary worth trying, in order, each once: `$HERDR_BIN_PATH`, each `herdr` on the PATH
    (not just the first: an old one may shadow the one that matches), then `~/.local/bin/herdr`, where
    herdr's own installer puts it."""
    found: list[str] = []
    seen: set[str] = set()

    def add(path: str) -> None:
        if not executable(path):
            return
        real = os.path.realpath(path)
        if real not in seen:
            seen.add(real)
            found.append(path)

    if env.get("HERDR_BIN_PATH"):
        add(env["HERDR_BIN_PATH"])
    for directory in (env.get("PATH") or "").split(os.pathsep):
        if directory:
            add(os.path.join(directory, "herdr"))
    home = env.get("HOME")
    if home:
        add(os.path.join(home, ".local", "bin", "herdr"))
    return found


def version(path: str, run=subprocess.run, timeout: float = TIMEOUT_S) -> str | None:
    """The version `path --version` prints ("0.9.1"), or None when it does not say."""
    try:
        result = run([path, *VERSION], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None
    match = _VERSION.search(result.stdout or "") if result.returncode == 0 else None
    return match.group(1) if match else None


def probe(path: str, run=subprocess.run) -> Probe:
    """Tries `path` against the running herdr server: its version, and one `herdr api snapshot`. Read-only."""
    try:
        HerdrCli(path, run=run).agents()
    except HerdrError as e:
        return Probe(path, version(path, run), answers=False, code=e.code, error=str(e))
    return Probe(path, version(path, run), answers=True)


def resolve(paths: list[str], probe: Callable[[str], Probe] = probe) -> tuple[Probe | None, list[Probe]]:
    """The first of `paths` whose snapshot the running herdr server answers, and every probe made on the
    way, in order. None when none answers: no server is running, or no binary matches it."""
    tried = []
    for path in paths:
        found = probe(path)
        tried.append(found)
        if found.answers:
            return found, tried
    return None, tried


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
        code = error.get("code") if isinstance(error.get("code"), str) and error.get("code") else None
        raise HerdrError(f"herdr: {code or 'error'}: {error.get('message', obj['error'])}", code)
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
        # herdr reads an argument starting with "-" as an option, so such a text is given a leading space.
        self._write(PROMPT, pane_id, [f" {text}" if text.startswith("-") else text])

    def send_keys(self, pane_id: str, keys: list[str]) -> None:
        if not keys or not all(_KEY.fullmatch(k) for k in keys):
            raise HerdrRefused("not_a_key", repr(keys))
        self._write(SEND_KEYS, pane_id, list(keys))

    def send_text(self, pane_id: str, text: str) -> None:
        if not text or text.startswith("-") or any(c in text for c in "\r\n\x00"):
            raise HerdrRefused("not_text", "an empty text, one starting with -, or one with a line break")
        self._write(SEND_TEXT, pane_id, [text])

    def _write(self, command: tuple[str, ...], pane_id: str, args: list[str]) -> None:
        """Runs a herdr command that writes to the agent: `command`, the pane, then `args`."""
        if not _PANE_ID.fullmatch(pane_id):
            raise HerdrRefused("not_a_pane", repr(pane_id))
        what = "herdr " + " ".join(command)
        try:
            result = self._run(
                [self.path, *command, pane_id, *args], capture_output=True, text=True, timeout=self._timeout,
                stdin=subprocess.DEVNULL,
            )
        except subprocess.TimeoutExpired:
            raise HerdrError(f"{what} did not finish in {self._timeout:g} s") from None
        except (OSError, ValueError) as e:  # herdr never ran (a NUL in the text is a ValueError)
            raise HerdrRefused("not_run", str(e)) from None
        if result.returncode == 0:
            return
        if result.returncode == USAGE:
            raise HerdrRefused("usage", (result.stderr or "").strip())
        code, message = prompt_error(result.stderr or result.stdout)
        if code in NOT_SENT:
            raise HerdrRefused(code, message)
        raise HerdrError(f"{what}: {code}: {message}")


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
