"""Every Bucket the hub publishes, gathered from its Providers, and who answers each request and send.

The hub Bucket (`stub.STUB_BUCKETS`) always comes first. It carries the
commands Providers add to it, heard whatever has Focus ("what's blocked");
with no Provider it has none, and is exactly the stub. Then each Provider's
Buckets follow, in order.

A Bucket whose Descriptor uses `speak_item` raises Items (`alerts.py`), and
gets "quiet" and "unmute" here, whatever its Provider: they mute and unmute
its alerts, and the hub answers them itself.
"""

from __future__ import annotations

import inspect
import logging
import uuid
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import replace
from typing import Protocol

from . import protocol as p
from .alerts import Alerts, Mutes, Raised
from .stub import STUB_BUCKETS

log = logging.getLogger("stsloop.hub")

HUB = STUB_BUCKETS[0]
SPEAK_ITEM = "speak_item"
QUIET = p.Command(id="quiet", phrases=("quiet", "mute alerts"), primitive="request")
UNMUTE = p.Command(id="unmute", phrases=("unmute", "unmute alerts"), primitive="request")

#: One send's delivery, ready to run: blocking, run at most once, never raises.
Delivery = Callable[[], p.Sent]


class Provider(Protocol):
    """What publishes a set of Buckets and answers their commands (CONTEXT.md, Provider)."""

    def descriptors(self) -> tuple[p.Descriptor, ...]: ...

    def hub_commands(self) -> tuple[p.Command, ...]:
        """`request` commands on the hub Bucket that this Provider answers."""
        ...

    def owns(self, bucket_id: str) -> bool: ...

    def answer(self, request: p.Request) -> p.Reply | Awaitable[p.Reply]:
        """The reply, or, for one that takes time (a summary), an awaitable of it. Called on the event loop."""
        ...

    def take_raised(self) -> tuple[Raised, ...]:
        """The Buckets that have become blocked since the last call."""
        ...

    def blocked(self) -> frozenset[str]:
        """The ids of the Buckets blocked now."""
        ...

    def prepare_send(self, send: p.Send) -> Delivery:
        """On the event loop: the delivery of a Draft to one of this Provider's Buckets."""
        ...


def unknown_bucket(request: p.Request) -> p.Reply:
    return p.Reply(id=request.id, reason="unknown_bucket", say="the hub has no such Bucket now.")


def unknown_command(request: p.Request) -> p.Reply:
    return p.Reply(id=request.id, reason="unknown_command", say="the hub has no such command.")


def unknown_bucket_sent(send: p.Send) -> p.Sent:
    return p.Sent(key=send.key, reason="unknown_bucket", say="the hub has no such agent now, so it was not sent.")


def not_sent(send: p.Send) -> p.Sent:
    return p.Sent(key=send.key, reason="refused", say="the hub could not send that.")


def failed(request: p.Request) -> p.Reply:
    return p.Reply(id=request.id, reason="failed", say="the hub could not answer that.")


class Buckets:
    def __init__(
        self,
        providers: Sequence[Provider] = (),
        mutes: Mutes | None = None,
        new_id: Callable[[], str] = lambda: uuid.uuid4().hex,
    ):
        self._providers = tuple(providers)
        self._mutes = mutes if mutes is not None else Mutes()
        self._alerts = Alerts(self._mutes, new_id)

    def hub(self) -> p.Descriptor:
        commands = tuple(c for provider in self._providers for c in provider.hub_commands())
        if not commands:
            return HUB
        primitives = tuple(dict.fromkeys(HUB.primitives + tuple(c.primitive for c in commands)))
        return replace(HUB, primitives=primitives, commands=HUB.commands + commands)

    def descriptors(self) -> tuple[p.Descriptor, ...]:
        return (self.hub(),) + tuple(_alerting(d) for provider in self._providers for d in provider.descriptors())

    async def answer(self, request: p.Request) -> p.Reply:
        """The reply to `request`. Never raises: a Provider that fails gets a `failed` reply, logged."""
        if request.bucket == HUB.id:
            provider = next(
                (pr for pr in self._providers if any(c.id == request.command for c in pr.hub_commands())), None,
            )
            if provider is None:
                return unknown_command(request)
        else:
            provider = next((pr for pr in self._providers if pr.owns(request.bucket)), None)
            if provider is None:
                return unknown_bucket(request)
        try:
            if request.command in (QUIET.id, UNMUTE.id):
                return self._mute(provider, request)
            reply = provider.answer(request)
            return await reply if inspect.isawaitable(reply) else reply
        except Exception:  # noqa: BLE001 - one Provider's bug must not drop the phone's connection
            log.exception("the Provider of %s could not answer %s", request.bucket, request.command)
            return failed(request)

    def prepare_send(self, send: p.Send) -> Delivery:
        """On the event loop: the delivery of `send` to its Bucket's Provider. Never raises: a Provider that
        fails to prepare it gets a delivery that sends nothing, logged."""
        provider = None if send.bucket == HUB.id else next(
            (pr for pr in self._providers if pr.owns(send.bucket)), None,
        )
        if provider is None:
            return lambda: unknown_bucket_sent(send)
        try:
            return provider.prepare_send(send)
        except Exception:  # noqa: BLE001 - one Provider's bug must not drop the phone's connection
            log.exception("the Provider of %s could not take the send %s", send.bucket, send.key)
            return lambda: not_sent(send)

    def _mute(self, provider: Provider, request: p.Request) -> p.Reply:
        bucket = next((d for d in provider.descriptors() if d.id == request.bucket), None)
        if bucket is None:
            return unknown_bucket(request)
        if SPEAK_ITEM not in bucket.primitives:
            return unknown_command(request)
        muted = request.command == QUIET.id
        self._mutes.set(bucket.id, muted)
        log.info("%s %s", "muted" if muted else "unmuted", bucket.id)
        return p.Reply(id=request.id, say=f"{bucket.name} {'muted' if muted else 'unmuted'}.")

    # --- alerts -------------------------------------------------------------------

    def collect(self) -> p.Item | None:
        """After a round of polling: what the Providers raised, as one Item, if anything is not muted."""
        raised = tuple(r for provider in self._providers for r in provider.take_raised())
        blocked = frozenset(b for provider in self._providers for b in provider.blocked())
        return self._alerts.raised(raised, blocked)

    def outstanding(self) -> tuple[p.Item, ...]:
        """Items not yet spoken that still hold: what a newly welcomed phone is sent."""
        return self._alerts.outstanding()

    def spoken(self, item_id: str) -> None:
        self._alerts.spoken(item_id)


def _alerting(d: p.Descriptor) -> p.Descriptor:
    """A Bucket that raises Items, with "quiet" and "unmute" after its own commands."""
    if SPEAK_ITEM not in d.primitives:
        return d
    primitives = tuple(dict.fromkeys(d.primitives + (QUIET.primitive,)))
    return replace(d, primitives=primitives, commands=d.commands + (QUIET, UNMUTE))
