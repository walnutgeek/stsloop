"""The hub's answer to "hub status" and "hub details": a `status_report`, facts the phone words itself.

- `agents`: the agent Buckets published now (Descriptor `type` `agent`), and
  `blocked`: how many of them are blocked.
- `herdr`: the agents Provider's view of herdr (`AgentsProvider.herdr_state`)
  and the version of the binary the hub runs, read once at start.
- `hooks`: whether the hub's Claude Code hooks are in the Claude Code settings
  file, read again for each report (a few kilobytes): the person may install
  or remove them while the hub runs.

Everything is already known, so a report is answered at once, on the event loop.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from pathlib import Path

from . import claude_settings
from . import protocol as p

log = logging.getLogger("stsloop.hub")

AGENT = p.AGENT


def hooks_installed(path: Path, port: int) -> Callable[[], bool]:
    """Whether the hub's hooks for `port` are in the settings file `path`, read when called. A file Claude
    Code cannot read either counts as not installed."""

    def installed() -> bool:
        try:
            return claude_settings.installed(claude_settings.load(path), port)
        except claude_settings.SettingsError as e:
            log.info("Claude Code hooks: %s", e)
            return False

    return installed


class Status:
    """Builds each `status_report` from what the hub knows now."""

    def __init__(
        self,
        descriptors: Callable[[], tuple[p.Descriptor, ...]],
        blocked: Callable[[], frozenset[str]] = frozenset,
        herdr_state: Callable[[], str] | None = None,
        herdr_version: str | None = None,
        hooks: Callable[[], bool] | None = None,
    ):
        self._descriptors = descriptors
        self._blocked = blocked
        self._herdr_state = herdr_state
        self._herdr_version = herdr_version
        self._hooks = hooks

    def report(self, request: p.StatusRequest) -> p.StatusReport:
        descriptors = self._descriptors()
        ids = {d.id for d in descriptors if d.type == AGENT}
        herdr = None
        if self._herdr_state is not None:
            state = self._herdr_state()
            herdr = p.HerdrReport(state, self._herdr_version if state != p.HERDR_NOT_INSTALLED else None)
        return p.StatusReport(
            id=request.id,
            agents=len(ids),
            blocked=len(self._blocked() & ids),
            herdr=herdr,
            hooks_installed=self._hooks() if self._hooks is not None else None,
        )
