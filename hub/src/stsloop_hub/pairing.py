"""The pairing payload `init` shows as a QR code (protocol/schemas/v1/pairing.schema.json)."""

from __future__ import annotations

import ipaddress
from typing import TextIO

import segno

from . import protocol as p


def url(host: str, port: int) -> str:
    try:
        if ipaddress.ip_address(host).version == 6:
            host = f"[{host}]"
    except ValueError:
        pass  # a name, used as it is
    return f"ws://{host}:{port}/"


def payload(hub_url: str, token: str) -> p.Pairing:
    return p.Pairing(protocol=p.SUPPORTED[0], url=hub_url, token=token)


def print_qr(pairing: p.Pairing, out: TextIO) -> None:
    """The payload as compact JSON in a QR code, drawn with block characters."""
    segno.make(p.encode(pairing), error="m").terminal(out=out, compact=True, border=2)
