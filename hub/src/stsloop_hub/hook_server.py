"""Where Claude Code's hooks reach the hub: plain HTTP on the loopback interface.

The hooks `init` installs (`claude_settings.py`) post each hook event's JSON to
`http://127.0.0.1:<hooks port>/stsloop-hub/claude-code`, with two headers:

- `X-Herdr-Pane`: the herdr pane the session runs in (`$HERDR_PANE_ID`), empty
  outside herdr. It is how the hub knows a session and a herdr agent are one.
- `X-Claude-Pid`: the Claude Code process the hook ran under (the hook
  shell's parent), so the hub can tell when the session has gone without a
  `SessionEnd`.

Only a POST of `application/json` naming a loopback `Host` is read, so a web
page in a browser on this machine cannot post one. Every event is answered
`204 No Content` before it is taken in: an
empty 2xx is a hook that decides nothing, so a hook can never allow, deny or
block anything in Claude Code. Malformed requests get a 4xx and are logged
without their body (it holds what the person typed and what tools returned).
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from collections.abc import Callable

log = logging.getLogger("stsloop.hooks")

PATH = "/stsloop-hub/claude-code"
HOST = "127.0.0.1"
PANE_HEADER = "x-herdr-pane"
PID_HEADER = "x-claude-pid"
#: A PostToolUse carries what the tool returned, which can be a whole file.
MAX_BODY = 64 * 1024 * 1024
MAX_HEAD = 16 * 1024
READ_TIMEOUT_S = 10.0
_PANE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9:_.\-]{0,63}")

#: What the hub does with one event: (payload, herdr pane or None, Claude Code pid or None).
OnEvent = Callable[[object, "str | None", "int | None"], object]


def _response(status: str) -> bytes:
    return f"HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encode()


async def _read(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> tuple[str, bytes | None, dict[str, str]]:
    """(status to answer, body or None, headers) for one request."""
    try:
        head = await reader.readuntil(b"\r\n\r\n")
    except asyncio.LimitOverrunError:
        return "431 Request Header Fields Too Large", None, {}
    lines = head.decode("latin-1").split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) != 3:
        return "400 Bad Request", None, {}
    method, path, _ = parts
    headers = {}
    for line in lines[1:]:
        name, sep, value = line.partition(":")
        if sep:
            headers[name.strip().lower()] = value.strip()
    if path != PATH:
        return "404 Not Found", None, headers
    if method != "POST":
        return "405 Method Not Allowed", None, headers
    try:
        length = int(headers.get("content-length", ""))
    except ValueError:
        return "411 Length Required", None, headers
    if not 0 <= length <= MAX_BODY:
        return "413 Content Too Large", None, headers
    # Only a hook's curl: a web page cannot send this content type without a preflight, which is never
    # answered, nor name a loopback host through DNS rebinding.
    if headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        return "415 Unsupported Media Type", None, headers
    if headers.get("host", "").rsplit(":", 1)[0] not in ("127.0.0.1", "localhost"):
        return "403 Forbidden", None, headers
    if headers.get("expect", "").lower() == "100-continue":
        # curl asks before a large body (a tool's long output), and waits a second for this otherwise.
        writer.write(b"HTTP/1.1 100 Continue\r\n\r\n")
        await writer.drain()
    return "204 No Content", await reader.readexactly(length), headers


def _event(body: bytes, headers: dict[str, str]) -> tuple[object, str | None, int | None]:
    payload = json.loads(body)
    pane = headers.get(PANE_HEADER) or None
    if pane is not None and not _PANE_ID.fullmatch(pane):
        log.info("ignored a hook's herdr pane that is not a pane id")
        pane = None
    try:
        pid = int(headers.get(PID_HEADER, ""))
    except ValueError:
        pid = None
    return payload, pane, pid if pid and pid > 1 else None


def handler(on_event: OnEvent):
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            async with asyncio.timeout(READ_TIMEOUT_S):
                status, body, headers = await _read(reader, writer)
            writer.write(_response(status))
            await writer.drain()
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, TimeoutError, ConnectionError, ValueError):
            log.info("a hook request was cut short or malformed")
            return
        finally:
            writer.close()
        if body is None:
            log.info("answered a hook request %s", status)
            return
        try:
            payload, pane, pid = _event(body, headers)
        except ValueError:
            log.info("a hook posted something that is not JSON")
            return
        try:
            on_event(payload, pane, pid)
        except Exception:  # noqa: BLE001 - one bad event must not stop the hooks
            log.exception("could not take in a hook event")

    return handle


async def listen(on_event: OnEvent, port: int, host: str = HOST) -> asyncio.Server:
    """The hooks' listener, started. Raises OSError when the port is taken."""
    return await asyncio.start_server(handler(on_event), host, port, limit=MAX_HEAD)


async def run(on_event: OnEvent, port: int, host: str = HOST) -> None:
    """Serves the hooks until cancelled. A port already taken is logged, and the hub goes on without hooks."""
    try:
        server = await listen(on_event, port, host)
    except OSError as e:
        log.warning("cannot listen for Claude Code hooks on %s:%d, so no hooks reach the hub: %s", host, port, e)
        return
    log.info("listening for Claude Code hooks on http://%s:%d%s", host, port, PATH)
    async with server:
        await server.serve_forever()
