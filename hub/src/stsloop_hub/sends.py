"""Delivering Drafts at most once per idempotency key, across reconnects and hub restarts.

The phone sends a Draft as a `send` with a key it never reuses, and sends the
same key again after a reconnect when no `sent` came back. `Deliveries`
tries each key once, ever:

- A key the `SendLog` already holds is answered with the answer it got the
  first time, and nothing is delivered.
- A key being delivered right now (the phone resent it on a new connection)
  waits for that delivery and gets its answer.
- Otherwise the key is written to the log, and fsynced, *before* the
  delivery runs, and its answer after. A key started but never answered (the
  hub stopped mid-delivery) reads back as `uncertain`: it may have arrived,
  so it is never tried again. A key the log cannot record is not delivered.

The log is `sends.jsonl` in the hub's config directory: append-only, one JSON
object per line, 0600 (it holds what the person dictated). Its lines are
`{"key", "bucket", "text", "at"}` when a delivery starts and `{"key", "say",
"reason", "at"}` when it ends. It is also the hub's record of every Draft sent.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

from . import protocol as p
from .buckets import Delivery

log = logging.getLogger("stsloop.sends")

FILE = "sends.jsonl"

#: Said for a key whose delivery started but never ended: the hub stopped in the middle.
INTERRUPTED = "the hub stopped while sending that, so it may not have arrived."
#: Said when the log cannot record a key: nothing is delivered without a record.
NOT_RECORDED = "the hub could not record the send, so it was not sent."
#: Said when a delivery failed in a way nobody expected: it may have arrived.
UNCONFIRMED = "the hub could not confirm that it arrived."


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class SendLog:
    """Every key the hub has started to deliver, and the answers it gave. `path` None keeps it in memory."""

    def __init__(self, path: Path | None = None):
        self._path = path
        self._started: set[str] = set()
        self._answers: dict[str, p.Sent] = {}
        if path is not None and path.exists():
            self._load(path)

    def _load(self, path: Path) -> None:
        with path.open(encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                try:
                    obj = json.loads(line)
                    key = obj["key"]
                    if not isinstance(key, str):
                        raise TypeError("key")
                    if "say" in obj:
                        self._answers[key] = p.Sent(key=key, say=obj["say"], reason=obj.get("reason"))
                    else:
                        self._started.add(key)
                except (ValueError, KeyError, TypeError):
                    # A hub that died mid-write leaves a broken last line; that key's start is lost with it,
                    # and it was never delivered: its start is written before the delivery runs.
                    log.warning("%s line %d is not a send record; skipped", path, n)

    def answer(self, key: str) -> p.Sent | None:
        """The answer `key` got, or will get: None for a key never started."""
        if key in self._answers:
            return self._answers[key]
        if key in self._started:
            return p.Sent(key=key, reason="uncertain", say=INTERRUPTED)
        return None

    def start(self, send: p.Send) -> None:
        """Records that `send` is about to be delivered, durably. Raises OSError when it cannot."""
        self._append({"key": send.key, "bucket": send.bucket, "text": send.text, "at": _now()})
        self._started.add(send.key)

    def finish(self, sent: p.Sent) -> None:
        """Records the answer to a started send. It holds in memory even when it cannot be written: the key then
        reads as interrupted after a restart, which is still never delivered twice."""
        self._answers[sent.key] = sent
        record = {"key": sent.key, "say": sent.say, "at": _now()}
        if sent.reason is not None:
            record["reason"] = sent.reason
        try:
            self._append(record)
        except OSError:
            log.exception("could not record the answer to the send %s", sent.key)

    def _append(self, record: dict) -> None:
        if self._path is None:
            return
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        fd = os.open(self._path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, line.encode("utf-8"))
            os.fsync(fd)
        finally:
            os.close(fd)


class Deliveries:
    """Delivers each send's key at most once (see the module). Runs on the event loop; each delivery itself
    runs off it."""

    def __init__(self, prepare: Callable[[p.Send], Delivery], sends: SendLog | None = None):
        self._prepare = prepare
        self._log = sends if sends is not None else SendLog()
        self._delivering: dict[str, asyncio.Future[p.Sent]] = {}

    async def deliver(self, send: p.Send) -> p.Sent:
        """The answer to `send`: delivered now, or the answer its key already got."""
        delivering = self._delivering.get(send.key)
        if delivering is not None:
            return await asyncio.shield(delivering)
        known = self._log.answer(send.key)
        if known is not None:
            log.info("send %s again: answered as before, not delivered again", send.key)
            return known
        future: asyncio.Future[p.Sent] = asyncio.get_running_loop().create_future()
        self._delivering[send.key] = future
        # What anyone waiting hears if this is cancelled mid-delivery (the hub is stopping): it may have arrived.
        sent = p.Sent(key=send.key, reason="uncertain", say=UNCONFIRMED)
        try:
            sent = await self._deliver(send)
            return sent
        finally:
            del self._delivering[send.key]
            future.set_result(sent)

    async def _deliver(self, send: p.Send) -> p.Sent:
        delivery = self._prepare(send)  # on the loop, where the Providers' state lives
        try:
            self._log.start(send)
        except OSError:
            log.exception("could not record the send %s; not delivering it", send.key)
            return p.Sent(key=send.key, reason="refused", say=NOT_RECORDED)
        try:
            sent = await asyncio.to_thread(delivery)
        except Exception:  # noqa: BLE001 - a delivery must never raise, but if it does, it may have delivered
            log.exception("the delivery of %s failed", send.key)
            sent = p.Sent(key=send.key, reason="uncertain", say=UNCONFIRMED)
        self._log.finish(sent)
        return sent
