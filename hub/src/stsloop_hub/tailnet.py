"""Finding this machine's tailnet address, which the hub listens on by default.

Asks the Tailscale CLI (`tailscale ip -4`), on the PATH or inside the macOS
app, and trusts only an answer in Tailscale's range, 100.64.0.0/10.
"""

from __future__ import annotations

import ipaddress
import subprocess

TAILNET = ipaddress.ip_network("100.64.0.0/10")
CANDIDATES = ("tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale")


def address(run=subprocess.run) -> str | None:
    for binary in CANDIDATES:
        try:
            result = run([binary, "ip", "-4"], capture_output=True, text=True, timeout=5)
        except (FileNotFoundError, PermissionError, subprocess.TimeoutExpired):
            continue
        if result.returncode != 0:
            continue
        for line in result.stdout.split():
            try:
                ip = ipaddress.ip_address(line.strip())
            except ValueError:
                continue
            if ip in TAILNET:
                return str(ip)
    return None
