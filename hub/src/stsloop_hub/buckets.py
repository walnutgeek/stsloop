"""Every Bucket the hub publishes, gathered from its Providers, and who answers each request and send.

The hub Bucket (`stub.STUB_BUCKETS`) always comes first. It carries the
commands Providers add to it, heard whatever has Focus ("what's blocked");
with no Provider it has none, and is exactly the stub. Then each Provider's
Buckets follow, in order.
"""

from __future__ import annotations

import inspect
import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import replace
from typing import Protocol

from . import protocol as p
from .stub import STUB_BUCKETS

log = logging.getLogger("stsloop.hub")

HUB = STUB_BUCKETS[0]

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
    def __init__(self, providers: Sequence[Provider] = ()):
        self._providers = tuple(providers)

    def hub(self) -> p.Descriptor:
        commands = tuple(c for provider in self._providers for c in provider.hub_commands())
        if not commands:
            return HUB
        primitives = tuple(dict.fromkeys(HUB.primitives + tuple(c.primitive for c in commands)))
        return replace(HUB, primitives=primitives, commands=HUB.commands + commands)

    def descriptors(self) -> tuple[p.Descriptor, ...]:
        return (self.hub(),) + tuple(d for provider in self._providers for d in provider.descriptors())

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
