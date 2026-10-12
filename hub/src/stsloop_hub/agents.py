"""The herdr Provider: one agent Bucket per herdr agent, answering "status" and "what's blocked".

It polls herdr (`refresh`) and remembers since when each agent has been in its
state, because herdr reports the state and not its age. An agent already in
its state when the hub first saw it has been so "for at least" that long.

- **Buckets.** Each agent is a Bucket with the id `herdr:<pane id>` (herdr
  never reuses a pane id), named from its workspace: "stsloop agent", with
  the workspace label as an alias. Further agents in the same workspace are
  "stsloop agent two", "three", and so on, in herdr's order, without the alias.
- **Commands.** An agent's "status" says its state and for how long. The hub
  Bucket's "what's blocked", heard whatever has Focus, lists blocked agents.
  Both are `request` commands: the phone sends them and speaks the reply.
- **Without herdr** there is no Provider at all (`cli.py`); with herdr
  installed but not answering, there are no agent Buckets, and "what's
  blocked" says herdr is not answering.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass

from . import protocol as p
from .buckets import unknown_bucket, unknown_command
from .herdr import Agent, Herdr, HerdrError

log = logging.getLogger("stsloop.herdr")

PREFIX = "herdr:"
STATUS = p.Command(id="status", phrases=("status",), primitive="request")
BLOCKED = p.Command(
    id="blocked", phrases=("what's blocked", "what is blocked"), primitive="request", anywhere=True,
)
POLL_S = 2.0

NOT_ANSWERING = "herdr is not answering."
NOTHING_BLOCKED = "nothing is blocked."
#: How each herdr state is said after the agent's name.
STATES = {
    "blocked": "is blocked",
    "working": "is working",
    "done": "is done",
    "idle": "is idle",
}
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


def _spoken(label: str) -> str:
    """A workspace label as words: dashes, underscores and dots become spaces."""
    return re.sub(r"[\s_.\-/]+", " ", label).strip() or label


@dataclass
class _Seen:
    agent: Agent
    since: float
    #: Whether the hub saw the agent enter this state; if not, `since` is only when the hub first saw it.
    observed: bool


class HerdrProvider:
    """Publishes herdr's agents as Buckets and answers their `request` commands.

    `refresh` is blocking (it runs herdr); `run` calls it off the event loop.
    Everything else reads what the last `refresh` saw, on the event loop."""

    def __init__(self, herdr: Herdr, clock: Callable[[], float] = time.monotonic):
        self._herdr = herdr
        self._clock = clock
        self._seen: dict[str, _Seen] = {}
        self._answering = False
        self._first = True
        self._descriptors: tuple[p.Descriptor, ...] = ()

    # --- what the hub publishes -------------------------------------------------

    def descriptors(self) -> tuple[p.Descriptor, ...]:
        return self._descriptors

    def hub_commands(self) -> tuple[p.Command, ...]:
        """Commands this Provider adds to the hub Bucket, heard anywhere."""
        return (BLOCKED,)

    # --- polling ------------------------------------------------------------------

    def refresh(self) -> bool:
        """Asks herdr for its agents. True when the Buckets changed."""
        now = self._clock()
        try:
            agents = self._herdr.agents()
        except HerdrError as e:
            if self._answering or self._first:
                log.warning("herdr is not answering: %s", e)
            self._answering, self._first = False, False
            self._seen.clear()
            return self._publish()
        # The first poll that answers, at start or after herdr was away, cannot know how old a state is.
        first = not self._answering
        if first:
            log.info("herdr answers: %d agents", len(agents))
        self._answering, self._first = True, False
        seen = {}
        for agent in agents:
            before = self._seen.get(agent.pane_id)
            if before and before.agent.status == agent.status and before.agent.seq == agent.seq:
                seen[agent.pane_id] = _Seen(agent, before.since, before.observed)
            else:
                # A new agent, or one whose state moved, entered this state at most a poll ago.
                seen[agent.pane_id] = _Seen(agent, now, observed=not first)
        self._seen = seen
        return self._publish()

    def _publish(self) -> bool:
        descriptors = self._build()
        changed = descriptors != self._descriptors
        self._descriptors = descriptors
        return changed

    def _build(self) -> tuple[p.Descriptor, ...]:
        out = []
        per_workspace: dict[str, int] = {}
        for pane_id, s in self._seen.items():
            base = _spoken(s.agent.workspace)
            n = per_workspace.get(base, 0)
            per_workspace[base] = n + 1
            name = f"{base} agent" if n == 0 else f"{base} agent {ORDINALS[n] if n < len(ORDINALS) else n + 1}"
            aliases = (base,) if n == 0 else ()
            out.append(p.Descriptor(
                id=PREFIX + pane_id, name=name, aliases=aliases, type="agent",
                primitives=("request",), commands=(STATUS,),
            ))
        return tuple(out)

    async def run(self, changed: Callable[[], object], interval: float = POLL_S) -> None:
        """Polls herdr every `interval` seconds, calling `changed` when the Buckets change."""
        while True:
            try:
                if await asyncio.to_thread(self.refresh):
                    changed()
            except Exception:  # noqa: BLE001 - a bug here must not stop the polling
                log.exception("herdr poll failed")
            await asyncio.sleep(interval)

    # --- answering ------------------------------------------------------------------

    def owns(self, bucket_id: str) -> bool:
        return bucket_id.startswith(PREFIX)

    def answer(self, request: p.Request) -> p.Reply:
        if request.bucket == "hub":
            if request.command != BLOCKED.id:
                return unknown_command(request)
            return p.Reply(id=request.id, say=self._blocked())
        name = self._name(request.bucket)
        seen = self._seen.get(request.bucket.removeprefix(PREFIX))
        if name is None or seen is None:
            return unknown_bucket(request)
        if request.command != STATUS.id:
            return unknown_command(request)
        return p.Reply(id=request.id, say=self._status(name, seen) + ".")

    def _name(self, bucket_id: str) -> str | None:
        return next((d.name for d in self._descriptors if d.id == bucket_id), None)

    def _status(self, name: str, seen: _Seen) -> str:
        state = STATES.get(seen.agent.status)
        if state is None:
            return f"herdr cannot tell what {name} is doing"
        return f"{name} {state}, {duration(self._clock() - seen.since, at_least=not seen.observed)}"

    def _blocked(self) -> str:
        if not self._answering:
            return NOT_ANSWERING
        blocked = [
            (d.name, self._seen[d.id.removeprefix(PREFIX)])
            for d in self._descriptors
            if self._seen[d.id.removeprefix(PREFIX)].agent.status == "blocked"
        ]
        # The longest blocked first: that agent has waited longest for the person.
        blocked.sort(key=lambda b: b[1].since)
        if not blocked:
            return NOTHING_BLOCKED
        if len(blocked) == 1:
            return self._status(*blocked[0]) + "."
        now = self._clock()
        items = "; ".join(f"{name}, {duration(now - s.since, at_least=not s.observed)}" for name, s in blocked)
        return f"{len(blocked)} agents are blocked: {items}."

