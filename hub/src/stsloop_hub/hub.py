"""The hub's WebSocket: one phone at a time, token first, then the handshake.

- The token travels in the upgrade request (`Authorization: Bearer <token>`).
  A wrong or missing one is answered `401` and never becomes a WebSocket.
- The phone's first known message must be `hello`. Frames that are not
  messages, and messages of other or unknown types, are ignored until then.
  No hello within `hello_timeout` closes the connection (1008).
- An acceptable hello gets `welcome`, then a `descriptors` snapshot when both
  sides have that capability. Anything else gets `refusal`, then close (1008).
- With a Corpus to store into and the `corpus` capability agreed, each
  `turn_offer` and `turn_audio` is answered with a `turn_ack` (`corpus.py`),
  in order, with the disk work off the event loop. A broken one that names its
  Turn is answered `rejected`, so the phone moves on.
- Once welcomed (after the `descriptors` snapshot), the phone is sent every
  outstanding Item (`items`), then each new one as it is raised
  (`item_raised`). An `item_spoken` goes to `spoken`.
- A newer welcomed connection replaces an older one (closed with 1000, in the
  background): a phone that changed networks reconnects before the old socket
  has noticed it is dead. A refused connection replaces nothing.
- Once welcomed, each `request` gets one `reply` from `answer`, which may
  take its time (a summary), and each `send` one `sent` from `deliver`
  (`sends.py`: each key is delivered at most once, so a send repeated after
  a reconnect gets the first answer again). Both are answered as they are
  ready, in the background, while other messages go on being read. Other
  messages are ignored. `buckets_changed` sends the welcomed phone a new
  `descriptors` snapshot.
- With the `summaries` capability agreed, each `summarize` gets one `reply`
  from `summarise` (`summary.NoteSummaries`: a model's summary of the
  phone's Notes, or the Notes read raw), worked out off the event loop. The
  hub always offers `summaries`: without a model it reads the Notes raw.
"""

from __future__ import annotations

import asyncio
import hmac
import inspect
import json
import logging
from collections.abc import Awaitable, Callable
from http import HTTPStatus

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from websockets.protocol import State

from . import protocol as p
from .buckets import Buckets, failed
from .corpus import CorpusStore
from .sends import Deliveries
from .summary import NoteSummaries, notes_fallback

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
        answer: Callable[[p.Request], p.Reply | Awaitable[p.Reply]] | None = None,
        corpus: CorpusStore | None = None,
        deliver: Callable[[p.Send], Awaitable[p.Sent]] | None = None,
        items: Callable[[], tuple[p.Item, ...]] = tuple,
        spoken: Callable[[str], object] = lambda item_id: None,
        summarise: Callable[[p.Summarize], p.Reply] | None = None,
    ):
        self._items = items
        self._spoken = spoken
        self._token = token.encode()
        self._descriptors = descriptors
        self._answer = answer or Buckets().answer
        #: Blocking (it may wait on a model), so it runs in a thread.
        self._summarise = summarise or NoteSummaries(None, 0).answer
        self._deliver = deliver or Deliveries(Buckets().prepare_send).deliver
        self._delivering: set[asyncio.Task] = set()
        #: The welcomed connection, when it asked for `descriptors`: who hears of new snapshots.
        self._subscriber: ServerConnection | None = None
        self._sending: set[asyncio.Task] = set()
        self._hello_timeout = hello_timeout
        self._supported = supported
        self._corpus = corpus
        self._capabilities = tuple(c for c in p.CAPABILITIES if c != p.CORPUS or corpus is not None)
        self._current: ServerConnection | None = None
        self._open: set[ServerConnection] = set()
        self._closing: set[asyncio.Task] = set()

    @property
    def corpus(self) -> CorpusStore | None:
        """Where uploaded Turns are stored; None means the hub does not offer `corpus`."""
        return self._corpus

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
            welcome = await self._handshake(ws)
            if welcome is not None:
                await self._serve(ws, welcome)
        except ConnectionClosed:
            pass
        finally:
            self._open.discard(ws)
            if self._current is ws:
                self._current = None
            if self._subscriber is ws:
                self._subscriber = None
            log.info("phone %s disconnected", ws.remote_address)

    async def _handshake(self, ws: ServerConnection) -> p.Welcome | None:
        try:
            async with asyncio.timeout(self._hello_timeout):
                answer = await self._answer_hello(ws)
        except TimeoutError:
            await ws.close(p.POLICY_VIOLATION, "no hello")
            return None
        await ws.send(p.encode(answer))
        if isinstance(answer, p.Refusal):
            log.warning("refused the phone: %s", answer.message)
            await ws.close(p.POLICY_VIOLATION, answer.reason)
            return None
        log.info("welcomed the phone: protocol %d.%d, capabilities %s",
                 answer.protocol.major, answer.protocol.minor, list(answer.capabilities))
        self._replace_current(ws)
        self._subscriber = ws if "descriptors" in answer.capabilities else None
        if self._subscriber is ws:
            await ws.send(p.encode(p.Descriptors(buckets=self._descriptors())))
        for item in self._items():
            await ws.send(p.encode(item))
        return answer

    def buckets_changed(self) -> None:
        """On the event loop: a Bucket came, went or changed; the welcomed phone gets a new snapshot."""
        if self._subscriber is not None:
            self._push(self._subscriber, p.Descriptors(buckets=self._descriptors()))

    def item_raised(self, item: p.Item) -> None:
        """On the event loop: a new Item; the welcomed phone, if any, gets it now, and any later one on its
        welcome (`items`) until it has been spoken."""
        if self._current is not None:
            self._push(self._current, item)

    def _push(self, ws: ServerConnection, message: p.Message) -> None:
        """Sends `message` to `ws` in the background; a phone that has gone misses it."""

        async def send() -> None:
            try:
                await ws.send(p.encode(message))
            except ConnectionClosed:
                pass

        task = asyncio.create_task(send())
        self._sending.add(task)
        task.add_done_callback(self._sending.discard)

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
                return p.negotiate(message, self._supported, self._capabilities)
            log.debug("ignored a %s before hello", type(message).__name__ if message else "message of unknown type")
        raise ConnectionClosed(None, None)

    async def _serve(self, ws: ServerConnection, welcome: p.Welcome) -> None:
        """After the welcome: answer each request, and each Corpus upload message if
        `corpus` was agreed; ignore everything else, as receivers do."""
        receiver = self._corpus.receiver() if self._corpus is not None and p.CORPUS in welcome.capabilities else None
        try:
            async for frame in ws:
                if isinstance(frame, bytes):
                    continue
                try:
                    message = p.decode(frame)
                except p.BadMessage as e:
                    log.warning("ignored a broken %s from the phone: %s", e.message_type or "frame", e)
                    turn = _turn_of(frame) if receiver is not None and e.message_type in ("turn_offer", "turn_audio") else None
                    if turn is not None:
                        # Answered, so the phone moves on to its next Turn.
                        await ws.send(p.encode(p.TurnAck(turn, p.REJECTED, reason="bad_message",
                                                         message="The hub could not read that message.")))
                    continue
                if isinstance(message, p.Request):
                    # In the background: a summary takes seconds, and the phone's other messages must not wait on it.
                    task = asyncio.create_task(self._reply(ws, message))
                    self._sending.add(task)
                    task.add_done_callback(self._sending.discard)
                elif isinstance(message, p.Summarize) and p.SUMMARIES in welcome.capabilities:
                    task = asyncio.create_task(self._summary(ws, message))
                    self._sending.add(task)
                    task.add_done_callback(self._sending.discard)
                elif isinstance(message, p.Send):
                    task = asyncio.create_task(self._send(ws, message))
                    self._delivering.add(task)
                    task.add_done_callback(self._delivering.discard)
                elif isinstance(message, p.ItemSpoken):
                    log.info("the phone has spoken item %s", message.id)
                    self._spoken(message.id)
                elif receiver is not None and isinstance(message, (p.TurnOffer, p.TurnAudio)):
                    await ws.send(p.encode(await _receive(receiver, message)))
        finally:
            if receiver is not None:
                await asyncio.to_thread(receiver.close)

    async def _reply(self, ws: ServerConnection, request: p.Request) -> None:
        try:
            reply = self._answer(request)
            if inspect.isawaitable(reply):
                reply = await reply
        except Exception:  # noqa: BLE001 - a bug in answering must not leave the phone waiting in silence
            log.exception("request %s: answering it failed", request.id)
            reply = failed(request)
        log.info("request %s %s/%s: %s", request.id, request.bucket, request.command, reply.say)
        try:
            await ws.send(p.encode(reply))
        except ConnectionClosed:
            log.info("request %s: the phone left before its reply", request.id)

    async def _summary(self, ws: ServerConnection, summarize: p.Summarize) -> None:
        try:
            reply = await asyncio.to_thread(self._summarise, summarize)
        except Exception:  # noqa: BLE001 - a bug in summarising must not leave the phone waiting in silence
            log.exception("summarize %s: summarising it failed", summarize.id)
            reply = p.Reply(id=summarize.id, reason=p.FALLBACK, say=notes_fallback(summarize))
        # The Notes are the person's own words: only their count goes to the log.
        log.info("summarize %s: %d notes of %s%s", summarize.id, len(summarize.notes), summarize.bucket,
                 f" ({reply.reason})" if reply.reason else "")
        try:
            await ws.send(p.encode(reply))
        except ConnectionClosed:
            log.info("summarize %s: the phone left before its summary", summarize.id)

    async def _send(self, ws: ServerConnection, send: p.Send) -> None:
        sent = await self._deliver(send)
        log.info("send %s to %s: %s%s", send.key, send.bucket, sent.say, f" ({sent.reason})" if sent.reason else "")
        try:
            await ws.send(p.encode(sent))
        except ConnectionClosed:
            # The phone sends the key again once it reconnects, and gets this answer then.
            log.info("send %s: the phone went before its answer", send.key)


async def _receive(receiver, message: p.TurnOffer | p.TurnAudio) -> p.TurnAck:
    handle = receiver.offer if isinstance(message, p.TurnOffer) else receiver.audio
    try:
        return await asyncio.to_thread(handle, message)
    except Exception:
        # A Turn the store chokes on must not drop the connection, or the phone retries it forever.
        log.exception("Turn %s: storing it failed", message.turn)
        await asyncio.to_thread(receiver.close)
        return p.TurnAck(message.turn, p.REJECTED, reason="storage_failed", message="The hub could not write the Turn.")


def _turn_of(frame: str) -> str | None:
    try:
        turn = json.loads(frame).get("turn")
    except (ValueError, AttributeError):
        return None
    return turn if isinstance(turn, str) and turn else None
