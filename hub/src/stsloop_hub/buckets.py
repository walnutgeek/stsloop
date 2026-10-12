"""Every Bucket the hub publishes, gathered from its Providers, and who answers each request.

The hub Bucket (`stub.STUB_BUCKETS`) always comes first. It carries the
commands Providers add to it, heard whatever has Focus ("what's blocked");
with no Provider it has none, and is exactly the stub. Then each Provider's
Buckets follow, in order.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import replace
from typing import Protocol

from . import protocol as p
from .stub import STUB_BUCKETS

log = logging.getLogger("stsloop.hub")

HUB = STUB_BUCKETS[0]


class Provider(Protocol):
    """What publishes a set of Buckets and answers their commands (CONTEXT.md, Provider)."""

    def descriptors(self) -> tuple[p.Descriptor, ...]: ...

    def hub_commands(self) -> tuple[p.Command, ...]:
        """`request` commands on the hub Bucket that this Provider answers."""
        ...

    def owns(self, bucket_id: str) -> bool: ...

    def answer(self, request: p.Request) -> p.Reply: ...


def unknown_bucket(request: p.Request) -> p.Reply:
    return p.Reply(id=request.id, reason="unknown_bucket", say="the hub has no such Bucket now.")


def unknown_command(request: p.Request) -> p.Reply:
    return p.Reply(id=request.id, reason="unknown_command", say="the hub has no such command.")


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

    def answer(self, request: p.Request) -> p.Reply:
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
            return provider.answer(request)
        except Exception:  # noqa: BLE001 - one Provider's bug must not drop the phone's connection
            log.exception("the Provider of %s could not answer %s", request.bucket, request.command)
            return failed(request)
