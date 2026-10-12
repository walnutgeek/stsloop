"""The hub's WebSocket: one phone at a time, token first, then the handshake.

- The token travels in the upgrade request (`Authorization: Bearer <token>`).
  A wrong or missing one is answered `401` and never becomes a WebSocket.
- The phone's first known message must be `hello`. Frames that are not
  messages, and messages of other or unknown types, are ignored until then.
  No hello within `hello_timeout` closes the connection (1008).
- An acceptable hello gets `welcome`, then a `descriptors` snapshot when both
  sides have that capability. Anything else gets `refusal`, then close (1008).
- A newer welcomed connection replaces an older one (closed with 1000, in the
  background): a phone that changed networks reconnects before the old socket
  has noticed it is dead. A refused connection replaces nothing.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
from collections.abc import Callable
from http import HTTPStatus

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from websockets.protocol import State

from . import protocol as p

log = logging.getLogger("stsloop.hub")

HELLO_TIMEOUT_S = 10.0
UNAUTHORISED_BODY = "Wrong or missing token. Pair the phone again from `stsloop-hub init`.\n"


class Hub:
    def __init__(
        self,
        token: str,
        descriptors: Callable[[], tuple[p.Descriptor, ...]],
        hello_timeout: float = HELLO_TIMEOUT_S,
        supported: tuple[p.Version, ...] = p.SUPPORTED,
    ):
        self._token = token.encode()
        self._descriptors = descriptors
        self._hello_timeout = hello_timeout
        self._supported = supported
        self._current: ServerConnection | None = None
        self._open: set[ServerConnection] = set()
        self._closing: set[asyncio.Task] = set()

    @property
    def connections(self) -> int:
        """How many phone connections are open; at most one once a replacement settles."""
        return sum(1 for ws in self._open if ws.state is State.OPEN)

    def authorised(self, authorization: str | None) -> bool:
        scheme, _, token = (authorization or "").partition(" ")
        return scheme.lower() == "bearer" and hmac.compare_digest(token.encode(), self._token)

    def listen(self, host: str, port: int):
        """An async context manager serving on (host, port); port 0 picks a free one."""
        return serve(self._handle, host, port, process_request=self._check_token)

    def _check_token(self, connection: ServerConnection, request):
        if self.authorised(request.headers.get("Authorization")):
            return None
        log.warning("refused a connection from %s: wrong or missing token", connection.remote_address)
        return connection.respond(HTTPStatus.UNAUTHORIZED, UNAUTHORISED_BODY)

    async def _handle(self, ws: ServerConnection) -> None:
        self._open.add(ws)
        try:
            log.info("phone connected from %s", ws.remote_address)
            if await self._handshake(ws):
                await self._ignore_the_rest(ws)
        except ConnectionClosed:
            pass
        finally:
            self._open.discard(ws)
            if self._current is ws:
                self._current = None
            log.info("phone %s disconnected", ws.remote_address)

    async def _handshake(self, ws: ServerConnection) -> bool:
        try:
            async with asyncio.timeout(self._hello_timeout):
                answer = await self._answer_hello(ws)
        except TimeoutError:
            await ws.close(p.POLICY_VIOLATION, "no hello")
            return False
        await ws.send(p.encode(answer))
        if isinstance(answer, p.Refusal):
            log.warning("refused the phone: %s", answer.message)
            await ws.close(p.POLICY_VIOLATION, answer.reason)
            return False
        log.info("welcomed the phone: protocol %d.%d, capabilities %s",
                 answer.protocol.major, answer.protocol.minor, list(answer.capabilities))
        self._replace_current(ws)
        if "descriptors" in answer.capabilities:
            await ws.send(p.encode(p.Descriptors(buckets=self._descriptors())))
        return True

    def _replace_current(self, ws: ServerConnection) -> None:
        previous, self._current = self._current, ws
        if previous is not None and previous is not ws:
            log.info("a newer connection from %s replaces %s", ws.remote_address, previous.remote_address)
            # Not awaited: a dead socket would hold the new phone up for close_timeout.
            task = asyncio.create_task(previous.close(1000, "replaced by a newer connection"))
            self._closing.add(task)
            task.add_done_callback(self._closing.discard)

    async def _answer_hello(self, ws: ServerConnection) -> p.Welcome | p.Refusal:
        async for frame in ws:
            if isinstance(frame, bytes):
                continue
            try:
                message = p.decode(frame)
            except p.BadMessage as e:
                if e.message_type == "hello":
                    log.warning("could not read the phone's hello: %s", e)
                    return p.BAD_HELLO
                log.debug("ignored a frame before hello: %s", e)
                continue
            if isinstance(message, p.Hello):
                return p.negotiate(message, self._supported)
            log.debug("ignored a %s before hello", type(message).__name__ if message else "message of unknown type")
        raise ConnectionClosed(None, None)

    async def _ignore_the_rest(self, ws: ServerConnection) -> None:
        # v1 defines nothing the phone sends after hello; receivers ignore what they do not know.
        async for _ in ws:
            pass
