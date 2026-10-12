"""`stsloop-hub init` pairs a phone; `stsloop-hub serve` runs the hub.

    stsloop-hub init [--host HOST] [--port PORT] [--new-token]
    stsloop-hub serve [--host HOST] [--port PORT]

Both listen on, or advertise, this machine's tailnet address unless a host is
given; a host given to `init` is kept for `serve`. `serve` publishes one
Bucket per herdr agent when herdr is installed (`$HERDR_BIN_PATH`, else
`herdr` on the PATH), and only the hub Bucket when it is not.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from collections.abc import Awaitable, Callable, Mapping, Sequence
from importlib.metadata import PackageNotFoundError, version
from typing import TextIO

from . import config, herdr, pairing, tailnet
from .agents import HerdrProvider
from .buckets import Buckets
from .hub import Hub

#: Background work that runs while the hub serves, told when the Buckets change.
Poller = Callable[[Callable[[], None]], Awaitable[None]]

NO_TAILNET = (
    "No tailnet address found: is Tailscale installed and up (`tailscale ip -4`)? "
    "To use another address the phone can reach, pass --host."
)


def _port(text: str) -> int:
    try:
        port = int(text)
    except ValueError:
        port = 0
    if not 0 < port < 65536:
        raise argparse.ArgumentTypeError(f"{text!r} is not a port (1-65535)")
    return port


def _warn_if_unencrypted(host: str, err: TextIO) -> None:
    if not tailnet.is_private_path(host):
        print(f"stsloop-hub: warning: {host} is not a tailnet address; the token and every message "
              "travel unencrypted unless that network is trusted", file=err)


def _version() -> str:
    try:
        return version("stsloop-hub")
    except PackageNotFoundError:
        return "unknown"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="stsloop-hub", description="The stsloop hub.")
    parser.add_argument("--version", action="version", version=f"%(prog)s {_version()}")
    commands = parser.add_subparsers(dest="command", required=True)

    def where(p):
        p.add_argument("--host", help="address to listen on (default: this machine's tailnet address)")
        p.add_argument("--port", type=_port, help=f"port (default: {config.DEFAULT_PORT})")

    init = commands.add_parser("init", help="make a pairing token and print the pairing QR code")
    where(init)
    init.add_argument("--new-token", action="store_true", help="replace the token; paired phones must pair again")
    serve = commands.add_parser("serve", help="serve the phone's WebSocket")
    where(serve)
    return parser


def _run_forever(hub: Hub, host: str, port: int, pollers: Sequence[Poller] = ()) -> None:
    async def run():
        async with hub.listen(host, port):
            logging.getLogger("stsloop.hub").info("listening on %s", pairing.url(host, port))
            tasks = [asyncio.create_task(poll(hub.buckets_changed)) for poll in pollers]
            try:
                await asyncio.get_running_loop().create_future()
            finally:
                for task in tasks:
                    task.cancel()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


def main(
    argv: list[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
    out: TextIO | None = None,
    err: TextIO | None = None,
    find_tailnet: Callable[[], str | None] = tailnet.address,
    run_hub: Callable[[Hub, str, int, Sequence[Poller]], None] = _run_forever,
    find_herdr: Callable[[], herdr.Herdr | None] | None = None,
) -> int:
    out, err = out or sys.stdout, err or sys.stderr
    args = _parser().parse_args(argv)
    directory = config.config_dir(os.environ if env is None else env)
    try:
        saved = config.load(directory)
    except config.ConfigError as e:
        print(f"stsloop-hub: {e}", file=err)
        return 2

    if args.command == "init":
        cfg = saved or config.HubConfig(token=config.new_token())
        if args.new_token:
            cfg.token = config.new_token()
        if args.host:
            cfg.host = args.host
        if args.port:
            cfg.port = args.port
        path = config.save(directory, cfg)
        print(f"Pairing token {'kept in' if saved and not args.new_token else 'written to'} {path}", file=out)
        host = cfg.host or find_tailnet()
        if not host:
            print(f"stsloop-hub: {NO_TAILNET}", file=err)
            return 1
        _warn_if_unencrypted(host, err)
        hub_url = pairing.url(host, cfg.port)
        print("Scan this with the stsloop app on your phone:\n", file=out)
        pairing.print_qr(pairing.payload(hub_url, cfg.token), out)
        print(f"\nHub: {hub_url}\nThen run: stsloop-hub serve", file=out)
        return 0

    if saved is None:
        print("stsloop-hub: not paired yet; run `stsloop-hub init` first", file=err)
        return 2
    host = args.host or saved.host or find_tailnet()
    if not host:
        print(f"stsloop-hub: {NO_TAILNET}", file=err)
        return 1
    _warn_if_unencrypted(host, err)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s", stream=err)
    found = (find_herdr or (lambda: _herdr(os.environ if env is None else env)))()
    providers = [HerdrProvider(found)] if found else []
    if not found:
        logging.getLogger("stsloop.hub").info("herdr is not installed; no agent Buckets")
    buckets = Buckets(providers)
    hub = Hub(token=saved.token, descriptors=buckets.descriptors, answer=buckets.answer)
    run_hub(hub, host, args.port or saved.port, [provider.run for provider in providers])
    return 0


def _herdr(env: Mapping[str, str]) -> herdr.Herdr | None:
    path = herdr.binary(env)
    if path:
        logging.getLogger("stsloop.hub").info("herdr found at %s; one Bucket per agent", path)
    return herdr.HerdrCli(path) if path else None
