"""Shared test helpers: the repo's protocol/ fixtures and a hub on a loopback port."""

from __future__ import annotations

import contextlib
import json
from pathlib import Path

from websockets.asyncio.client import connect

from stsloop_hub.herdr import Agent, HerdrError
from stsloop_hub.hub import Hub
from stsloop_hub.stub import STUB_BUCKETS

PROTOCOL = Path(__file__).resolve().parents[2] / "protocol"
FIXTURES = PROTOCOL / "fixtures"
SCHEMAS = PROTOCOL / "schemas"


def versions() -> list[str]:
    """Every fixture version present: the current major and any frozen previous one."""
    return sorted(p.name for p in FIXTURES.glob("v*") if p.is_dir())


def fixture(version: str, rel: str) -> dict:
    return json.loads((FIXTURES / version / rel).read_text())


def fixture_paths(version: str) -> list[Path]:
    return sorted((FIXTURES / version).glob("*/*.json"))


def manifest(version: str, name: str) -> dict:
    return json.loads((FIXTURES / version / name).read_text())


def pairing_token(version: str = "v1") -> str:
    return fixture(version, "pairing/basic.json")["token"]


@contextlib.asynccontextmanager
async def running_hub(token: str | None = None, descriptors=lambda: STUB_BUCKETS, **kwargs):
    """A hub on 127.0.0.1, publishing only the stub Bucket unless told otherwise; yields (hub, ws url)."""
    hub = Hub(token=token or pairing_token(), descriptors=descriptors, **kwargs)
    async with hub.listen("127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        yield hub, f"ws://127.0.0.1:{port}/"


def phone(url: str, token: str | None = None, authorization: str | None = None):
    """Connect as the phone does: `Authorization: Bearer <token>`, or a raw header."""
    if token is not None:
        authorization = f"Bearer {token}"
    headers = {"Authorization": authorization} if authorization is not None else {}
    return connect(url, additional_headers=headers, open_timeout=5)


def agent(pane_id: str, workspace: str, status: str, seq: int = 1) -> Agent:
    return Agent(pane_id=pane_id, workspace_id=pane_id.split(":")[0], workspace=workspace, status=status, seq=seq)


class FakeHerdr:
    """herdr as the hub sees it: the test sets `agents`, or `error` for a herdr that does not answer."""

    def __init__(self, agents=(), error: str | None = None):
        self.agents_now = list(agents)
        self.error = error
        self.calls = 0

    def agents(self):
        self.calls += 1
        if self.error:
            raise HerdrError(self.error)
        return list(self.agents_now)


class Clock:
    def __init__(self, now: float = 1000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now
