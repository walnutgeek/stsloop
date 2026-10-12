"""Blocked alerts: the Items the hub raises for the phone's Attention Queue (`docs/mvp.md`, "Alerts").

- **Raised.** A Provider reports each Bucket that has just become blocked
  (`Raised`). Everything reported by one round of polling becomes one Item,
  so agents blocked together are said together: "two agents are blocked:
  woodglue agent and stsloop agent." A finished agent raises nothing.
- **Muted.** A muted Bucket raises nothing. Mutes are kept in `mutes.json`
  next to the hub's config, so they outlive a hub restart.
- **Outstanding.** An Item stays outstanding until the phone says it has
  spoken it (`item_spoken`), and every new connection is sent the outstanding
  ones again; the phone ignores an id it already has, so a reconnect never
  repeats an alert. An Item stops being outstanding, unsaid, once none of its
  Buckets still holds it: each is no longer blocked, gone, muted, or blocked
  again since (a newer Item says that).
"""

from __future__ import annotations

import json
import logging
import os
import uuid
from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass
from pathlib import Path

from . import protocol as p

log = logging.getLogger("stsloop.hub")

FILE = "mutes.json"
NUMBERS = ("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")


@dataclass(frozen=True)
class Raised:
    """One Bucket that has just become blocked, as its Provider reports it."""

    #: The Bucket's Descriptor id.
    bucket: str
    #: Its name, as the loop says it.
    name: str


def sentence(raised: Sequence[Raised]) -> str:
    """What the phone says for Buckets blocked together."""
    names = [r.name for r in raised]
    if len(names) == 1:
        return f"{names[0]} is blocked."
    count = NUMBERS[len(names)] if len(names) < len(NUMBERS) else str(len(names))
    return f"{count} agents are blocked: {', '.join(names[:-1])} and {names[-1]}."


class Mutes:
    """The muted Buckets, by Descriptor id, kept in `path` (or only in memory, without one).
    An unreadable file is logged and means nothing is muted."""

    def __init__(self, path: Path | None = None):
        self._path = path
        self._muted: set[str] = set()
        if path is None or not path.exists():
            return
        try:
            obj = json.loads(path.read_text(encoding="utf-8"))
            muted = obj["muted"]
            if not isinstance(muted, list) or not all(isinstance(b, str) for b in muted):
                raise ValueError("muted is not a list of strings")
        except (OSError, ValueError, KeyError, TypeError) as e:
            log.warning("ignoring %s, nothing is muted: %s", path, e)
            return
        self._muted = set(muted)

    def __contains__(self, bucket: object) -> bool:
        return bucket in self._muted

    def set(self, bucket: str, muted: bool) -> None:
        """Mutes or unmutes `bucket`, and saves the change. A save that fails is logged; the mute still holds."""
        if (bucket in self._muted) == muted:
            return
        if muted:
            self._muted.add(bucket)
        else:
            self._muted.discard(bucket)
        if self._path is None:
            return
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self._path.with_name(f".{self._path.name}.tmp")
            tmp.write_text(json.dumps({"muted": sorted(self._muted)}, indent=2) + "\n", encoding="utf-8")
            os.replace(tmp, self._path)
        except OSError as e:
            log.error("cannot save %s; the mute lasts until the hub stops: %s", self._path, e)


class Alerts:
    """Turns what Providers raise into Items, and keeps the outstanding ones. On the event loop only."""

    def __init__(self, mutes: Mutes, new_id: Callable[[], str] = lambda: uuid.uuid4().hex):
        self._mutes = mutes
        self._new_id = new_id
        #: Each outstanding Item, with the Buckets that still hold it.
        self._outstanding: list[tuple[p.Item, set[str]]] = []

    def raised(self, raised: Sequence[Raised], blocked: Collection[str]) -> p.Item | None:
        """One round of polling: `raised` just became blocked, and `blocked` are all blocked now.
        The new Item, if any of `raised` is not muted."""
        fresh = [r for r in raised if r.bucket not in self._mutes]
        newer = {r.bucket for r in fresh}
        for _, holds in self._outstanding:
            holds.intersection_update(blocked)
            holds.difference_update(newer)
        self._prune()
        if not fresh:
            return None
        item = p.Item(id=self._new_id(), buckets=tuple(r.bucket for r in fresh), say=sentence(fresh))
        self._outstanding.append((item, set(item.buckets)))
        log.info("raised item %s: %s", item.id, item.say)
        return item

    def outstanding(self) -> tuple[p.Item, ...]:
        """Every Item not yet spoken that still holds, oldest first."""
        self._prune()
        return tuple(item for item, _ in self._outstanding)

    def spoken(self, item_id: str) -> None:
        """The phone has said `item_id`; an id the hub does not know is ignored."""
        self._outstanding = [(item, holds) for item, holds in self._outstanding if item.id != item_id]

    def _prune(self) -> None:
        for _, holds in self._outstanding:
            holds.difference_update([b for b in holds if b in self._mutes])
        self._outstanding = [(item, holds) for item, holds in self._outstanding if holds]
