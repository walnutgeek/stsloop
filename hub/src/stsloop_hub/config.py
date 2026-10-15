"""The hub's config file: the pairing token, an optional host and port, the port
Claude Code's hooks post to (`hooks_port`, on 127.0.0.1, default 7718), and the
summariser; the herdr binary `init` found (`herdr`, see `herdr.py`); and where
the uploaded Corpus is kept.

`hub.json` lives in `$STSLOOP_HUB_HOME`, else `$XDG_CONFIG_HOME/stsloop-hub`,
else `~/.config/stsloop-hub`. The directory is 0700 and the file 0600: the
token is a shared secret, stored in plain text because `init` shows it again
in the QR code.

The optional `summariser` object picks the model that turns an agent's screen
into a spoken sentence ("what does it say", ADR-0007):

    "summariser": {"backend": "claude", "model": "haiku", "timeout_s": 20}

- `backend`: `claude` (the default: `claude -p`, with the user's Claude Code
  login), `pi`, or `ollama`.
- `model`: passed to the backend; `claude` defaults to `haiku`, `ollama` to
  `llama3.2`, and `pi` uses its own default.
- `command`: the `claude` or `pi` binary, when it is not on the PATH.
- `url`: Ollama's address, default `http://127.0.0.1:11434`.
- `timeout_s`: how long the model may take before the phone gets the
  fallback (herdr's state and the last line), from 1 to
  `MAX_SUMMARY_TIMEOUT_S`.
"""

from __future__ import annotations

import json
import os
import secrets
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

FILE = "hub.json"
DEFAULT_PORT = 7717
#: Where Claude Code's hooks reach the hub, on 127.0.0.1 (`hook_server.py`).
DEFAULT_HOOKS_PORT = 7718
BACKENDS = ("claude", "pi", "ollama")
DEFAULT_SUMMARY_TIMEOUT_S = 20.0
#: The longest a model may take; the phone's wait for a summary (`agents.READ_WITHIN_MS`) is built on it.
MAX_SUMMARY_TIMEOUT_S = 22.0


class ConfigError(Exception):
    pass


@dataclass(frozen=True)
class SummariserConfig:
    backend: str = "claude"
    model: str | None = None
    command: str | None = None
    url: str | None = None
    timeout_s: float = DEFAULT_SUMMARY_TIMEOUT_S

    def to_json(self) -> dict:
        obj: dict = {"backend": self.backend}
        for key in ("model", "command", "url"):
            if getattr(self, key):
                obj[key] = getattr(self, key)
        if self.timeout_s != DEFAULT_SUMMARY_TIMEOUT_S:
            obj["timeout_s"] = self.timeout_s
        return obj


@dataclass
class HubConfig:
    token: str
    host: str | None = None
    port: int = DEFAULT_PORT
    hooks_port: int = DEFAULT_HOOKS_PORT
    #: As `hub.json` has it: None when it has none, and the defaults apply.
    summariser: SummariserConfig | None = None
    #: The herdr binary `init` found to match the running herdr server; None: `herdr` on the PATH.
    herdr: str | None = None


def new_token() -> str:
    """32 random bytes, URL-safe base64 (43 characters)."""
    return secrets.token_urlsafe(32)


def config_dir(env: Mapping[str, str] = os.environ) -> Path:
    if env.get("STSLOOP_HUB_HOME"):
        return Path(env["STSLOOP_HUB_HOME"])
    if env.get("XDG_CONFIG_HOME"):
        return Path(env["XDG_CONFIG_HOME"]) / "stsloop-hub"
    return Path(env.get("HOME") or Path.home()) / ".config" / "stsloop-hub"


def corpus_dir(env: Mapping[str, str] = os.environ) -> Path:
    """Where `serve` stores the uploaded Corpus: `$STSLOOP_HUB_HOME/corpus`, else
    `$XDG_DATA_HOME/stsloop-hub/corpus`, else `~/.local/share/stsloop-hub/corpus`."""
    if env.get("STSLOOP_HUB_HOME"):
        return Path(env["STSLOOP_HUB_HOME"]) / "corpus"
    if env.get("XDG_DATA_HOME"):
        return Path(env["XDG_DATA_HOME"]) / "stsloop-hub" / "corpus"
    return Path(env.get("HOME") or Path.home()) / ".local" / "share" / "stsloop-hub" / "corpus"


def load(directory: Path) -> HubConfig | None:
    """The saved config, or None before `init`. Raises ConfigError if it is unusable."""
    path = directory / FILE
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        raise ConfigError(f"{path} is not readable JSON ({e}); fix it or run `stsloop-hub init --new-token`") from None
    token, host, port = obj.get("token") if isinstance(obj, dict) else None, None, DEFAULT_PORT
    if not isinstance(token, str) or len(token) < 32:
        raise ConfigError(f"{path} has no usable token; run `stsloop-hub init --new-token`")
    if isinstance(obj.get("host"), str) and obj["host"]:
        host = obj["host"]
    if "port" in obj:
        port = obj["port"]
        if not isinstance(port, int) or isinstance(port, bool) or not 0 < port < 65536:
            raise ConfigError(f"{path} has an unusable port {port!r}")
    hooks_port = obj.get("hooks_port", DEFAULT_HOOKS_PORT)
    if not isinstance(hooks_port, int) or isinstance(hooks_port, bool) or not 0 < hooks_port < 65536:
        raise ConfigError(f"{path} has an unusable hooks_port {hooks_port!r}")
    summariser = _summariser(obj["summariser"], path) if "summariser" in obj else None
    herdr = obj.get("herdr")
    if herdr is not None and (not isinstance(herdr, str) or not herdr):
        raise ConfigError(f"{path}: herdr must be the path of a herdr binary; run `stsloop-hub init` to find it")
    return HubConfig(token=token, host=host, port=port, hooks_port=hooks_port, summariser=summariser, herdr=herdr)


def _summariser(obj, path: Path) -> SummariserConfig:
    if not isinstance(obj, dict):
        raise ConfigError(f"{path}: summariser must be an object")
    backend = obj.get("backend", "claude")
    if backend not in BACKENDS:
        raise ConfigError(f"{path}: summariser backend {backend!r} is not one of {', '.join(BACKENDS)}")
    strings = {}
    for key in ("model", "command", "url"):
        value = obj.get(key)
        if value is not None and (not isinstance(value, str) or not value):
            raise ConfigError(f"{path}: summariser {key} must be a non-empty string")
        strings[key] = value
    timeout = obj.get("timeout_s", DEFAULT_SUMMARY_TIMEOUT_S)
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 1 <= timeout <= MAX_SUMMARY_TIMEOUT_S:
        raise ConfigError(f"{path}: summariser timeout_s must be from 1 to {MAX_SUMMARY_TIMEOUT_S:g} seconds")
    return SummariserConfig(backend=backend, timeout_s=float(timeout), **strings)


def save(directory: Path, config: HubConfig) -> Path:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    obj = {"token": config.token, "port": config.port}
    if config.host:
        obj["host"] = config.host
    if config.hooks_port != DEFAULT_HOOKS_PORT:
        obj["hooks_port"] = config.hooks_port
    if config.summariser:
        obj["summariser"] = config.summariser.to_json()
    if config.herdr:
        obj["herdr"] = config.herdr
    path = directory / FILE
    tmp = directory / f".{FILE}.tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return path
