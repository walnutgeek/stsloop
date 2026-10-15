"""`stsloop-hub init` pairs a phone and offers Claude Code hooks; `stsloop-hub serve` runs the hub.

    stsloop-hub init [--host HOST] [--port PORT] [--new-token]
                     [--hooks | --no-hooks | --remove-hooks] [--claude-settings FILE] [--hooks-port PORT]
    stsloop-hub serve [--host HOST] [--port PORT] [--corpus DIR]

Both listen on, or advertise, this machine's tailnet address unless a host is
given; a host given to `init` is kept for `serve`.

`init` also offers to install Claude Code hooks (`claude_settings.py`) when it
finds Claude Code: it shows exactly what it would add to the settings file,
and writes it only when the person says yes (or passes `--hooks`); with no
terminal to ask on, it writes nothing. `--remove-hooks` takes them out again,
and does nothing else.

`init` also finds the herdr binary that matches the running herdr server: it
tries `$HERDR_BIN_PATH`, each `herdr` on the PATH and `~/.local/bin/herdr`,
read-only (`herdr --version`, `herdr api snapshot`), says each one that does
not match, and saves the first that does in `hub.json` (`herdr.py`).

`serve` publishes one Bucket per coding agent (`agents.py`): each herdr agent
when herdr is installed (`$HERDR_BIN_PATH`, else the herdr `init` saved, else
`herdr` on the PATH; tried once at start, and a mismatch is said), and
each Claude Code session its hooks report (on 127.0.0.1, `hooks_port`) that
herdr does not show. An agent's "what does it say" is summarised by the
backend `hub.json` names (`claude -p` by default; see `config.py`). It stores the
Turns the phone uploads in DIR (default: `config.corpus_dir`), as
`scripts/corpus_load.py` reads them. Every Draft the phone sends is recorded
in `sends.jsonl` in the config directory, which keeps each delivered at most
once across restarts (`sends.py`).
"""

from __future__ import annotations

import argparse
import asyncio
import functools
import json
import logging
import os
import shutil
import sys
from collections.abc import Awaitable, Callable, Mapping, Sequence
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import TextIO

from . import alerts, claude_settings, config, herdr, hook_server, pairing, summary, tailnet
from . import protocol as p
from .alerts import Mutes
from .agents import AgentsProvider
from .claude_code import Sessions
from .buckets import Buckets
from .corpus import CorpusStore
from .sends import FILE as SENDS_FILE
from .sends import Deliveries, SendLog
from .hub import Hub
from .status import Status, hooks_installed

#: Background work that runs while the hub serves.
Poller = Callable[[], Awaitable[None]]

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
    hooks = init.add_mutually_exclusive_group()
    hooks.add_argument("--hooks", action="store_true", help="install the Claude Code hooks without asking")
    hooks.add_argument("--no-hooks", action="store_true", help="do not offer the Claude Code hooks")
    hooks.add_argument("--remove-hooks", action="store_true", help="remove the Claude Code hooks, and do nothing else")
    init.add_argument("--claude-settings", type=Path, metavar="FILE",
                      help="the Claude Code settings file for the hooks (default: ~/.claude/settings.json)")
    init.add_argument("--hooks-port", type=_port, help=f"the port hooks post to on {hook_server.HOST} "
                      f"(default: {config.DEFAULT_HOOKS_PORT})")
    serve = commands.add_parser("serve", help="serve the phone's WebSocket")
    where(serve)
    serve.add_argument("--corpus", type=Path, help="where to store the Turns the phone uploads "
                       "(default: $STSLOOP_HUB_HOME/corpus, else ~/.local/share/stsloop-hub/corpus)")
    return parser


def _run_forever(hub: Hub, host: str, port: int, pollers: Sequence[Poller] = ()) -> None:
    async def run():
        async with hub.listen(host, port):
            logging.getLogger("stsloop.hub").info("listening on %s", pairing.url(host, port))
            tasks = [asyncio.create_task(poll()) for poll in pollers]
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
    make_backend: Callable[[config.SummariserConfig], summary.Backend] = summary.backend,
    ask: Callable[[str], bool] | None = None,
    which: Callable[[str], str | None] = shutil.which,
    probe_herdr: Callable[[str], herdr.Probe] = herdr.probe,
) -> int:
    out, err = out or sys.stdout, err or sys.stderr
    args = _parser().parse_args(argv)
    env = os.environ if env is None else env
    if args.command == "init" and args.remove_hooks:
        # Needs nothing of the hub's own config, which may be the thing that is broken.
        return _remove_hooks(args.claude_settings or claude_settings.default_path(env), out, err)
    directory = config.config_dir(env)
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
        if args.hooks_port:
            cfg.hooks_port = args.hooks_port
        _find_herdr(cfg, env, probe_herdr, out, err)
        path = config.save(directory, cfg)
        print(f"Pairing token {'kept in' if saved and not args.new_token else 'written to'} {path}", file=out)
        if not args.no_hooks:
            _offer_hooks(args, cfg.hooks_port, env, out, err, ask or functools.partial(_ask, out=out), which)
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
    corpus_root = args.corpus or config.corpus_dir(env)
    try:
        corpus = CorpusStore(corpus_root)
    except OSError as e:
        print(f"stsloop-hub: cannot use {corpus_root} for the Corpus: {e}", file=err)
        return 1
    print(f"stsloop-hub: storing uploaded Turns in {corpus.root}", file=err)
    if find_herdr is not None:
        found, herdr_version = find_herdr(), None
    else:
        found, herdr_version = _herdr(env, saved.herdr, probe_herdr, err)
    if not found:
        logging.getLogger("stsloop.hub").info("herdr is not installed; agent Buckets come from Claude Code hooks only")
    summariser = saved.summariser or config.SummariserConfig()
    print(f"stsloop-hub: summarising agents and notes with {summariser.backend}, within {summariser.timeout_s:g} s", file=err)
    model = make_backend(summariser)
    provider = AgentsProvider(
        found, sessions=Sessions(),
        summaries=summary.Summaries(found, model, summariser.timeout_s) if found else None,
        session_summaries=summary.Summaries(None, model, summariser.timeout_s),
    )
    buckets = Buckets([provider], mutes=Mutes(directory / alerts.FILE))
    try:
        sends = SendLog(directory / SENDS_FILE)
    except OSError as e:
        print(f"stsloop-hub: cannot read {directory / SENDS_FILE}: {e}", file=err)
        return 1
    status = Status(
        buckets.descriptors, buckets.blocked, herdr_state=provider.herdr_state, herdr_version=herdr_version,
        hooks=hooks_installed(claude_settings.default_path(env), saved.hooks_port),
    )
    hub = Hub(token=saved.token, descriptors=buckets.descriptors, answer=buckets.answer, corpus=corpus,
              deliver=Deliveries(buckets.prepare_send, sends).deliver,
              items=buckets.outstanding, spoken=buckets.spoken,
              summarise=summary.NoteSummaries(model, summariser.timeout_s).answer,
              status=status.report)

    def polled(changed: bool) -> None:
        """After each poll, on the event loop: a new snapshot if the Buckets changed, and any new Item."""
        if changed:
            hub.buckets_changed()
        item = buckets.collect()
        if item is not None:
            hub.item_raised(item)

    def hooked(payload: object, pane: str | None, pid: int | None) -> None:
        """On the event loop: a Claude Code hook event; a new snapshot if the Buckets changed. Its Items wait
        for the next poll, so changes that come together are said together."""
        if provider.hook(payload, pane, pid):
            hub.buckets_changed()

    run_hub(hub, host, args.port or saved.port, [
        functools.partial(provider.run, polled),
        functools.partial(hook_server.run, hooked, saved.hooks_port),
    ])
    return 0


def _ask(question: str, out: TextIO) -> bool:
    """A yes from the person at the terminal; no without one to ask."""
    if not sys.stdin.isatty():
        return False
    print(question, end="", file=out, flush=True)
    try:
        return input().strip().lower() in ("y", "yes")
    except EOFError:
        return False


def _offer_hooks(args, port: int, env: Mapping[str, str], out: TextIO, err: TextIO,
                 ask: Callable[[str], bool], which: Callable[[str], str | None]) -> None:
    """Offers the Claude Code hooks, showing exactly what they add, and installs them only with consent."""
    path = args.claude_settings or claude_settings.default_path(env)
    if args.claude_settings is None and not args.hooks and not which("claude") and not path.parent.is_dir():
        print("Claude Code not found; no hooks offered.", file=out)
        return
    try:
        settings = claude_settings.load(path)
    except claude_settings.SettingsError as e:
        print(f"stsloop-hub: Claude Code hooks not installed: {e}", file=err)
        return
    if claude_settings.installed(settings, port):
        print(f"Claude Code hooks: installed in {path}. To remove them: stsloop-hub init --remove-hooks", file=out)
        return
    if not which("curl"):
        print("stsloop-hub: Claude Code hooks need curl, which is not installed; no hooks installed.", file=err)
        return
    print(f"\nClaude Code: the hub can hear when each session is blocked or done, through hooks in {path}.", file=out)
    if claude_settings.has_ours(settings):
        print("It would replace its older hooks there with these, and change nothing else:", file=out)
    else:
        print("It would add exactly this, and change nothing else:", file=out)
    print(json.dumps({"hooks": claude_settings.hooks(port)}, indent=2), file=out)
    if not (args.hooks or ask("Install these hooks? [y/N] ")):
        print("Hooks not installed. To install them later: stsloop-hub init --hooks", file=out)
        return
    try:
        claude_settings.save(path, claude_settings.with_ours(settings, port))
    except OSError as e:
        print(f"stsloop-hub: cannot write {path}: {e}", file=err)
        return
    print(f"Hooks installed in {path}. To remove them: stsloop-hub init --remove-hooks\n", file=out)


def _remove_hooks(path: Path, out: TextIO, err: TextIO) -> int:
    """Takes the hub's hooks out of `path`, and nothing else."""
    try:
        settings = claude_settings.load(path)
    except claude_settings.SettingsError as e:
        print(f"stsloop-hub: {e}", file=err)
        return 1
    if not claude_settings.has_ours(settings):
        print(f"No stsloop-hub hooks in {path}; nothing changed.", file=out)
        return 0
    try:
        claude_settings.save(path, claude_settings.without_ours(settings))
    except OSError as e:
        print(f"stsloop-hub: cannot write {path}: {e}", file=err)
        return 1
    print(f"Removed the stsloop-hub hooks from {path}; the file as it was is kept as "
          f"{path.name}{claude_settings.BACKUP_SUFFIX}.", file=out)
    return 0


def _herdr(env: Mapping[str, str], saved: str | None, probe: Callable[[str], herdr.Probe],
           err: TextIO) -> tuple[herdr.Herdr | None, str | None]:
    """The herdr `serve` runs, and its version. It is tried once, so a herdr that does not match the
    running server is said at start, not found out from a quiet phone."""
    path = herdr.binary(env, saved=saved)
    if not path:
        return None, None
    found = probe(path)
    if found.answers:
        print(f"stsloop-hub: herdr {found.describe()} answers; one Bucket per agent", file=err)
    elif found.code == p.HERDR_PROTOCOL_MISMATCH:
        print(f"stsloop-hub: warning: herdr {found.describe()} does not speak the running herdr server's protocol, "
              "so no agents are published from herdr. Run `stsloop-hub init` to find the herdr that does.", file=err)
    else:
        print(f"stsloop-hub: warning: herdr {found.describe()} does not answer yet ({found.error}); "
              "the hub keeps trying", file=err)
    return herdr.HerdrCli(path), found.version


def _find_herdr(cfg: config.HubConfig, env: Mapping[str, str], probe: Callable[[str], herdr.Probe],
                out: TextIO, err: TextIO) -> None:
    """Tries every herdr binary against the running herdr server and keeps the first it answers in `cfg`,
    for `serve`. Says what it tried; a binary that does not match is said, never skipped silently."""
    paths = herdr.candidates(env)
    if not paths:
        print("herdr not found; agent Buckets come from Claude Code hooks only.", file=out)
        return
    found, tried = herdr.resolve(paths, probe)
    for t in tried:
        if t.answers:
            continue
        if t.code == p.HERDR_PROTOCOL_MISMATCH:
            print(f"herdr {t.describe()} does not speak the running herdr server's protocol; skipped.", file=out)
        else:
            print(f"herdr {t.describe()} does not answer: {t.error}", file=out)
    if found is not None:
        cfg.herdr = found.path
        print(f"herdr {found.describe()} answers the running herdr server; serve will use it.", file=out)
        return
    kept = f" serve keeps using {cfg.herdr}." if cfg.herdr else ""
    if all(t.code == p.HERDR_PROTOCOL_MISMATCH for t in tried):
        print("stsloop-hub: no herdr here speaks the running herdr server's protocol: update herdr, then run "
              f"init again.{kept}", file=err)
    else:
        print(f"stsloop-hub: no herdr answered; is the herdr server running? Start herdr, then run init again.{kept}",
              file=err)
