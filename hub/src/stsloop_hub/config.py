"""The hub's config file: the pairing token, and an optional host and port.

`hub.json` lives in `$STSLOOP_HUB_HOME`, else `$XDG_CONFIG_HOME/stsloop-hub`,
else `~/.config/stsloop-hub`. The directory is 0700 and the file 0600: the
token is a shared secret, stored in plain text because `init` shows it again
in the QR code.
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


class ConfigError(Exception):
    pass


@dataclass
class HubConfig:
    token: str
    host: str | None = None
    port: int = DEFAULT_PORT


def new_token() -> str:
    """32 random bytes, URL-safe base64 (43 characters)."""
    return secrets.token_urlsafe(32)


def config_dir(env: Mapping[str, str] = os.environ) -> Path:
    if env.get("STSLOOP_HUB_HOME"):
        return Path(env["STSLOOP_HUB_HOME"])
    if env.get("XDG_CONFIG_HOME"):
        return Path(env["XDG_CONFIG_HOME"]) / "stsloop-hub"
    return Path(env.get("HOME") or Path.home()) / ".config" / "stsloop-hub"


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
    return HubConfig(token=token, host=host, port=port)


def save(directory: Path, config: HubConfig) -> Path:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    obj = {"token": config.token, "port": config.port}
    if config.host:
        obj["host"] = config.host
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
