"""The agents Provider: one Bucket per coding agent, seen through herdr, Claude Code hooks, or both.

It answers "status", "what does it say" and "what's blocked", takes the Drafts
the person sends, and raises blocked alerts. It has two sources, either of
which may be missing:

- **herdr**, which it polls (`refresh`): one agent per herdr pane. herdr
  reports the state and not its age, so the hub times each state from the
  poll that saw it begin; one already held when the hub first saw it has been
  so "for at least" that long.
- **Claude Code hooks** (`claude_code.Sessions`), which it is told of as they
  fire (`hook`): one session per Claude Code session, with precise states,
  timed from the event, and why a blocked one is blocked (a question or a
  permission prompt).

**One agent, one Bucket.** A Claude Code session whose hooks name a herdr pane
herdr lists as an agent is that agent: its Bucket stays herdr's
(`herdr:<pane id>`), and the session refines its state. When the two
disagree, the one whose state began later is right: hooks are precise, but
herdr also sees what the installed hooks do not (a dialog they do not report,
a refused permission). With several sessions in one pane (one run inside
another), the one heard from last counts. Until herdr has first answered, a
session in a herdr pane has no Bucket, so the agent does not appear twice.

- **Buckets.** A herdr agent is `herdr:<pane id>` (herdr never reuses a pane
  id), named from its workspace; a Claude Code session herdr does not show is
  `claude-code:<session id>`, named from the directory it started in. Both
  are "stsloop agent", with the label as an alias; further agents with the
  same label, from either source, are "stsloop agent two", "three", and so
  on, without the alias. An agent keeps its name while it lives; a new one
  takes the lowest number free.
- **Commands.** "status" says the state and for how long, and why when hooks
  say it is blocked; "what does it say" summarises herdr's screen, or, for a
  session without herdr, its recent hook events (`summary.py`). The hub
  Bucket's "what's blocked", heard whatever has Focus, lists blocked agents.
  All are `request` commands.
- **Drafts.** A herdr agent declares the `draft` Primitive's commands ("read
  it back", "send", "cancel"), which the phone runs itself; a sent Draft
  arrives as a `send`, and the delivery `prepare_send` returns submits it with
  `herdr agent prompt`. Each key is delivered at most once by
  `sends.Deliveries`, not here. A session without herdr has no Draft: there
  is no way into its terminal, and its "send" is a `request` that says so.
- **Alerts.** An agent seen to become blocked is raised (`take_raised`) for
  the hub to speak (`alerts.py`), once, whichever source saw it first. herdr
  alone: from another state, blocked again since the last poll, or new and
  already blocked; not one already blocked at the hub's first poll, or when
  herdr answers again after being away (the hub did not see it happen). A
  finished agent is never raised.
- **Without either source** there is no Provider at all (`cli.py`). With
  herdr installed but not answering, its agents' Buckets go (sessions in its
  panes come back as Claude Code Buckets), and "what's blocked" says so.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import PurePath

from . import protocol as p
from .alerts import Raised
from .buckets import HUB, Delivery, unknown_bucket, unknown_bucket_sent, unknown_command
from .claude_code import BLOCKED as SESSION_BLOCKED
from .claude_code import PERMISSION, QUESTION, Session, Sessions
from .claude_code import alive as process_alive
from .config import MAX_SUMMARY_TIMEOUT_S
from .herdr import TIMEOUT_S as HERDR_TIMEOUT_S
from .herdr import Agent, Herdr, HerdrError, HerdrRefused
from .summary import SCREEN_LINES, Summaries

log = logging.getLogger("stsloop.herdr")

PREFIX = "herdr:"
CLAUDE_PREFIX = "claude-code:"
STATUS = p.Command(id="status", phrases=("status",), primitive="request")
#: A summary asks a model, so the phone waits longer for it than for other replies: long enough for herdr's
#: read, the slowest model the config allows, and the trip, so the fallback comes before the phone gives up.
READ_WITHIN_MS = int((HERDR_TIMEOUT_S + MAX_SUMMARY_TIMEOUT_S + 3) * 1000)
READ = p.Command(id="read", phrases=("what does it say", "read it"), primitive="request", reply_within_ms=READ_WITHIN_MS)
BLOCKED = p.Command(
    id="blocked", phrases=("what's blocked", "what is blocked"), primitive="request", anywhere=True,
)
#: The `draft` Primitive's commands. The phone knows each by its id and runs it itself.
READ_BACK = p.Command(id="read_back", phrases=("read it back",), primitive="draft")
SEND = p.Command(id="send", phrases=("send",), primitive="draft")
CANCEL = p.Command(id="cancel", phrases=("cancel",), primitive="draft")
#: "send" to a session without herdr: a `request`, answered with why it cannot be sent.
SEND_NEEDS_HERDR = p.Command(id="send", phrases=("send",), primitive="request")
POLL_S = 2.0
#: Polls herdr may fail in a row before its agents' Buckets go.
GRACE = 3
#: herdr's error code for a client binary that does not speak the running server's protocol.
MISMATCH = p.HERDR_PROTOCOL_MISMATCH

NOT_ANSWERING = "herdr is not answering."
NOTHING_BLOCKED = "nothing is blocked."
#: How each state is said after the agent's name.
STATES = {
    "blocked": "is blocked",
    "working": "is working",
    "done": "is done",
    "idle": "is idle",
    "stopped": "has stopped on an error",
}
#: How a blocked agent is said when the hooks say why.
BLOCKED_ON = {QUESTION: "is blocked on a question", PERMISSION: "is blocked on a permission prompt"}
ORDINALS = ("", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")


def duration(seconds: float, *, at_least: bool = False) -> str:
    """How long, in words a TTS engine reads well: "for 3 minutes", "for 1 hour 5 minutes"."""
    minutes = int(seconds // 60)
    if minutes < 1:
        return "for at least a few seconds" if at_least else "for less than a minute"
    hours, minutes = divmod(minutes, 60)
    parts = []
    if hours:
        parts.append(f"{hours} hour{'s' if hours != 1 else ''}")
    if minutes or not hours:
        parts.append(f"{minutes} minute{'s' if minutes != 1 else ''}")
    return ("for at least " if at_least else "for ") + " ".join(parts)


def _refused(name: str, code: str) -> str:
    if code == "agent_blocked":
        return f"{name} is waiting on a question, so it was not sent."
    if code == "agent_not_found":
        return f"{name} is gone, so it was not sent."
    return f"herdr would not send it to {name}."


def _spoken(label: str) -> str:
    """A workspace label or directory name as words: dashes, underscores and dots become spaces."""
    return re.sub(r"[\s_.\-/]+", " ", label).strip() or label


def _directory(cwd: str) -> str:
    """A session's label: the name of the directory it started in."""
    return _spoken(PurePath(cwd).name) if cwd and PurePath(cwd).name else "claude code"


@dataclass
class _Seen:
    agent: Agent
    since: float
    #: Whether the hub saw the agent enter this state; if not, `since` is only when the hub first saw it.
    observed: bool


@dataclass(frozen=True)
class State:
    """An agent's state as the hub says it: herdr's, a session's, or the later of the two."""

    status: str
    since: float
    observed: bool
    #: Why it is blocked, when the hooks say: `question` or `permission`.
    why: str | None = None


@dataclass(frozen=True)
class _Entry:
    """One published agent: its herdr pane, its Claude Code session, or both."""

    pane: str | None
    session: str | None


class AgentsProvider:
    """Publishes coding agents as Buckets and answers their `request` commands.

    `poll` is blocking (it runs herdr), so `run` calls it off the event loop and
    `apply`s its result on the loop, where every other method runs, `hook`
    included: the state is only ever read and written on one thread. `refresh`
    does both, for tests."""

    def __init__(
        self,
        herdr: Herdr | None,
        clock: Callable[[], float] = time.monotonic,
        summaries: Summaries | None = None,
        sessions: Sessions | None = None,
        session_summaries: Summaries | None = None,
        alive: Callable[[int], bool] = process_alive,
    ):
        self._herdr = herdr
        self._sessions = sessions
        #: Without a model, "what does it say" gives the state and the last line.
        self._summaries = summaries or (Summaries(herdr, None, timeout=0) if herdr is not None else None)
        self._session_summaries = session_summaries or (
            Summaries(None, None, timeout=0) if sessions is not None else None)
        self._clock = clock
        self._alive = alive
        self._seen: dict[str, _Seen] = {}
        self._answering = False
        self._first = True
        self._failures = 0
        #: herdr's error code from the last poll that failed, while it is failing.
        self._error_code: str | None = None
        self._descriptors: tuple[p.Descriptor, ...] = ()
        self._entries: dict[str, _Entry] = {}
        #: Each live agent's (spoken label, number within it), by Bucket id: kept for the agent's
        #: lifetime, so "stsloop agent two" stays two when "stsloop agent" goes.
        self._names: dict[str, tuple[str, int]] = {}
        #: Bucket ids seen to become blocked since the last `take_raised`.
        self._raised: list[str] = []

    # --- what the hub publishes -------------------------------------------------

    def descriptors(self) -> tuple[p.Descriptor, ...]:
        return self._descriptors

    def herdr_state(self) -> str:
        """How herdr is doing, as a `status_report` says it (`p.HERDR_*`)."""
        if self._herdr is None:
            return p.HERDR_NOT_INSTALLED
        if self._answering:
            return p.HERDR_OK
        if self._first:
            return p.HERDR_CHECKING
        return p.HERDR_PROTOCOL_MISMATCH if self._error_code == MISMATCH else p.HERDR_NOT_ANSWERING

    def hub_commands(self) -> tuple[p.Command, ...]:
        """Commands this Provider adds to the hub Bucket, heard anywhere."""
        return (BLOCKED,)

    def state(self, bucket_id: str) -> State | None:
        """The agent's state now, or None for no such agent."""
        entry = self._entries.get(bucket_id)
        if entry is None:
            return None
        seen = self._seen.get(entry.pane) if entry.pane else None
        session = self._sessions.get(entry.session) if entry.session and self._sessions is not None else None
        herdr = State(seen.agent.status, seen.since, seen.observed) if seen else None
        hooks = State(session.status, session.since, session.observed, session.why) if session else None
        if herdr is None or hooks is None:
            return herdr or hooks
        if herdr.status not in STATES:
            return hooks
        if herdr.status == hooks.status:
            earlier = herdr if herdr.since < hooks.since else hooks
            return State(herdr.status, earlier.since, earlier.observed, hooks.why)
        # The later word is right: a hook just fired, or herdr just saw what no installed hook reports.
        return hooks if hooks.since >= herdr.since else herdr

    # --- herdr ----------------------------------------------------------------------

    def refresh(self) -> bool:
        """Asks herdr for its agents and takes them in. True when the Buckets changed."""
        return self.apply(self.poll())

    def poll(self) -> list[Agent] | HerdrError:
        """Blocking: herdr's agents now, or why herdr did not answer. Changes nothing."""
        if self._herdr is None:
            return []
        try:
            return self._herdr.agents()
        except HerdrError as e:
            return e

    def apply(self, polled: list[Agent] | HerdrError) -> bool:
        """Takes in what `poll` found. True when the Buckets changed."""
        if self._herdr is None:
            return False
        now = self._clock()
        if isinstance(polled, HerdrError):
            e = polled
            # One slow or failed poll is a hiccup: the agents keep their Buckets (and Focus on the phone)
            # until herdr has failed GRACE polls in a row.
            self._failures += 1
            self._error_code = e.code
            if self._failures < GRACE:
                log.info("herdr did not answer (%d in a row): %s", self._failures, e)
                return False
            if (self._answering or self._first) and e.code == MISMATCH:
                log.warning("herdr does not speak the running herdr server's protocol (%s): run `stsloop-hub init` "
                            "to find the herdr binary that does", e)
            elif self._answering or self._first:
                log.warning("herdr is not answering: %s", e)
            self._answering, self._first = False, False
            self._seen = {}
            return self._publish()
        agents = polled
        self._failures = 0
        self._error_code = None
        # The first poll that answers, at start or after herdr was away, cannot know how old a state is.
        first = not self._answering
        if first:
            log.info("herdr answers: %d agents", len(agents))
        self._answering, self._first = True, False
        blocked_before = {bucket_id for bucket_id in self._entries if self._is_blocked(bucket_id)}
        seen, entered = {}, []
        for agent in agents:
            before = self._seen.get(agent.pane_id)
            if before and before.agent.status == agent.status and before.agent.seq == agent.seq:
                seen[agent.pane_id] = _Seen(agent, before.since, before.observed)
            else:
                # A new agent, or one whose state moved, entered this state at most a poll ago.
                seen[agent.pane_id] = _Seen(agent, now, observed=not first)
                if agent.status == "blocked" and not first:
                    entered.append(PREFIX + agent.pane_id)
        self._seen = seen
        changed = self._publish()
        for bucket_id in entered:
            # An agent its hooks already showed blocked was raised then.
            entry = self._entries.get(bucket_id)
            if entry is not None and (entry.session is None or bucket_id not in blocked_before):
                self._raised.append(bucket_id)
        return changed

    # --- Claude Code hooks ----------------------------------------------------------

    def hook(self, payload: object, pane: str | None = None, pid: int | None = None) -> bool:
        """Takes in one Claude Code hook event. True when the Buckets changed."""
        if self._sessions is None:
            return False
        session_id = payload.get("session_id") if isinstance(payload, dict) else None
        before = self._bucket_of(session_id)
        was_blocked = before is not None and self._is_blocked(before)
        session = self._sessions.get(session_id) if isinstance(session_id, str) else None
        # A new dialog in a session already blocked on one (a second permission prompt) is a new block.
        redialog = (session.dialogs, session.status) if session is not None else None
        if self._sessions.take(payload, pane, pid) is None:
            return False
        changed = self._publish()
        after = self._bucket_of(session_id)
        if after is None or not self._is_blocked(after):
            return changed
        session = self._sessions.get(session_id)
        reblocked = redialog is not None and redialog[1] == SESSION_BLOCKED and session.dialogs != redialog[0]
        if not (was_blocked and after == before) or reblocked:
            self._raised.append(after)
        return changed

    def prune(self) -> bool:
        """Forgets the Claude Code sessions that have ended without saying so. True when the Buckets changed."""
        if self._sessions is None or not self._sessions.prune(self._alive):
            return False
        return self._publish()

    def _bucket_of(self, session_id: object) -> str | None:
        """The Bucket whose state `session_id` gives, if it has one."""
        if not isinstance(session_id, str):
            return None
        return next((b for b, e in self._entries.items() if e.session == session_id), None)

    # --- the Buckets ------------------------------------------------------------------

    def _publish(self) -> bool:
        descriptors = self._build()
        changed = descriptors != self._descriptors
        self._descriptors = descriptors
        return changed

    def _agents(self) -> list[tuple[str, str, _Entry]]:
        """Each agent to publish, in order: (Bucket id, spoken label, its sources)."""
        sessions: list[Session] = list(self._sessions) if self._sessions is not None else []
        # The session a herdr pane's state comes from: the one heard from last.
        in_pane: dict[str, Session] = {}
        for s in sessions:
            if s.pane in self._seen and (s.pane not in in_pane or s.heard >= in_pane[s.pane].heard):
                in_pane[s.pane] = s
        out = [
            (PREFIX + pane, _spoken(seen.agent.workspace), _Entry(pane, in_pane[pane].id if pane in in_pane else None))
            for pane, seen in self._seen.items()
        ]
        for s in sessions:
            if s.pane in self._seen:
                continue
            if s.pane and self._herdr is not None and self._first:
                continue  # herdr has not answered yet: this may be one of its agents
            out.append((CLAUDE_PREFIX + s.id, _directory(s.cwd), _Entry(None, s.id)))
        return out

    def _build(self) -> tuple[p.Descriptor, ...]:
        agents = self._agents()
        labels = {bucket_id: label for bucket_id, label, _ in agents}
        self._entries = {bucket_id: entry for bucket_id, _, entry in agents}
        # A gone agent frees its number, and one whose label changed takes a new one.
        self._names = {b: name for b, name in self._names.items() if labels.get(b) == name[0]}
        for bucket_id, label, _ in agents:
            if bucket_id not in self._names:
                taken = {n for b, n in self._names.values() if b == label}
                self._names[bucket_id] = (label, next(n for n in range(len(taken) + 1) if n not in taken))
        out = []
        for bucket_id, _, entry in agents:
            base, n = self._names[bucket_id]
            name = f"{base} agent" if n == 0 else f"{base} agent {ORDINALS[n] if n < len(ORDINALS) else n + 1}"
            aliases = (base,) if n == 0 else ()
            if entry.pane is not None:
                primitives, commands = ("draft", "request", "speak_item"), (STATUS, READ, READ_BACK, SEND, CANCEL)
            else:
                primitives, commands = ("request", "speak_item"), (STATUS, READ, SEND_NEEDS_HERDR)
            out.append(p.Descriptor(
                id=bucket_id, name=name, aliases=aliases, type=p.AGENT, primitives=primitives, commands=commands,
            ))
        return tuple(out)

    # --- alerts -----------------------------------------------------------------------

    def take_raised(self) -> tuple[Raised, ...]:
        """The agents seen to become blocked since the last call, still here and still blocked, in order."""
        raised, self._raised = set(self._raised), []
        return tuple(Raised(bucket=d.id, name=d.name) for d in self._descriptors
                     if d.id in raised and self._is_blocked(d.id))

    def blocked(self) -> frozenset[str]:
        return frozenset(d.id for d in self._descriptors if self._is_blocked(d.id))

    def _is_blocked(self, bucket_id: str) -> bool:
        state = self.state(bucket_id)
        return state is not None and state.status == SESSION_BLOCKED

    async def run(self, polled: Callable[[bool], object], interval: float | None = None) -> None:
        """Every `interval` seconds (default `POLL_S`), polls herdr and forgets ended sessions, then calls
        `polled` with whether the Buckets changed."""
        while True:
            try:
                changed = self.apply(await asyncio.to_thread(self.poll)) if self._herdr is not None else False
                changed = self.prune() or changed
                polled(changed)
            except Exception:  # noqa: BLE001 - a bug here must not stop the polling
                log.exception("agents poll failed")
            await asyncio.sleep(POLL_S if interval is None else interval)

    # --- answering ------------------------------------------------------------------

    def owns(self, bucket_id: str) -> bool:
        return bucket_id.startswith((PREFIX, CLAUDE_PREFIX))

    def answer(self, request: p.Request) -> p.Reply | Awaitable[p.Reply]:
        if request.bucket == HUB.id:
            if request.command != BLOCKED.id:
                return unknown_command(request)
            return p.Reply(id=request.id, say=self._blocked())
        name = self._name(request.bucket)
        state = self.state(request.bucket)
        if name is None or state is None:
            return unknown_bucket(request)
        entry = self._entries[request.bucket]
        if request.command == READ.id:
            return self._read(request, name, state, entry)
        if request.command == SEND_NEEDS_HERDR.id and entry.pane is None:
            return p.Reply(id=request.id, say=f"sending to {name} needs herdr.")
        if request.command != STATUS.id:
            return unknown_command(request)
        return p.Reply(id=request.id, say=self._status(name, state) + ".")

    async def _read(self, request: p.Request, name: str, state: State, entry: _Entry) -> p.Reply:
        # What the summary needs is taken here, on the event loop; the reading and the model run off it.
        status = self._status(name, state)
        if entry.pane is not None:
            say = await asyncio.to_thread(self._summaries.say, name, state.status, status, entry.pane)
        else:
            # A session's events live on the event loop: they are read here, and only the model runs off it.
            text = self._sessions.read(entry.session, SCREEN_LINES)
            if not text.strip():
                return p.Reply(id=request.id, say=f"{status}. Its hooks have said nothing more yet.")
            say = await asyncio.to_thread(self._session_summaries.say_text, name, state.status, status, text)
        return p.Reply(id=request.id, say=say)

    def prepare_send(self, send: p.Send) -> Delivery:
        """On the event loop: the delivery of `send` to its agent as the hub knows it now. The delivery
        blocks (it runs herdr), never raises, and is run once at most."""
        name = self._name(send.bucket)
        entry = self._entries.get(send.bucket)
        if name is None or entry is None:
            return lambda: unknown_bucket_sent(send)
        if entry.pane is None or self._herdr is None:
            return lambda: p.Sent(key=send.key, reason="refused", say=f"sending to {name} needs herdr, so it was not sent.")
        herdr, pane_id = self._herdr, entry.pane

        def deliver() -> p.Sent:
            try:
                herdr.prompt(pane_id, send.text)
            except HerdrRefused as e:
                log.warning("herdr refused the send %s to %s: %s", send.key, pane_id, e)
                return p.Sent(key=send.key, reason="refused", say=_refused(name, e.code))
            except HerdrError as e:
                log.warning("herdr did not confirm the send %s to %s: %s", send.key, pane_id, e)
                return p.Sent(key=send.key, reason="uncertain", say=f"herdr did not confirm that {name} got it.")
            log.info("sent %s to %s (%s)", send.key, name, pane_id)
            return p.Sent(key=send.key, say=f"sent to {name}.")

        return deliver

    def _name(self, bucket_id: str) -> str | None:
        return next((d.name for d in self._descriptors if d.id == bucket_id), None)

    def _status(self, name: str, state: State) -> str:
        said = BLOCKED_ON.get(state.why) if state.status == SESSION_BLOCKED else None
        said = said or STATES.get(state.status)
        if said is None:
            return f"herdr cannot tell what {name} is doing"
        return f"{name} {said}, {duration(self._clock() - state.since, at_least=not state.observed)}"

    def _blocked(self) -> str:
        herdr_away = self._herdr is not None and not self._answering
        states = ((d.name, self.state(d.id)) for d in self._descriptors)
        blocked = [(name, s) for name, s in states if s is not None and s.status == SESSION_BLOCKED]
        # The longest blocked first: that agent has waited longest for the person.
        # A state already held when the hub first saw it began before any the hub saw begin.
        blocked.sort(key=lambda b: (b[1].observed, b[1].since))
        if not blocked:
            return NOT_ANSWERING if herdr_away else NOTHING_BLOCKED
        if len(blocked) == 1:
            said = self._status(*blocked[0]) + "."
        else:
            now = self._clock()
            items = "; ".join(f"{name}, {duration(now - s.since, at_least=not s.observed)}" for name, s in blocked)
            said = f"{len(blocked)} agents are blocked: {items}."
        return f"{said} {NOT_ANSWERING}" if herdr_away else said

