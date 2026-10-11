#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Load a synced Corpus as plain files and print summary stats (#17).

Reads every Turn directory (`<started_at>-<id>/turn.json` + `audio.wav`),
skipping `sessions/` (test-mode route logs), dot entries (Syncthing's
`.stfolder`, `.stversions`) and `corpus-staging/`. For each Turn it:

- checks `audio.sha256` against the WAV on disk (`--no-verify` skips the
  hashing; a missing WAV is still reported);
- applies tombstones, derived on read as `docs/mvp.md` "Corpus format" says:
  every `kind: "command"` Turn's `tombstones` names a target directory, the
  earliest command wins, and a Turn's own legacy non-null `tombstoned_by` is
  honoured.

Then it prints Turn counts by kind and by Bucket, the Declaration rate per
Bucket and overall, the tombstone count, and every problem found (sha256
mismatch, missing audio, missing or unparseable `turn.json`).

    uv run scripts/corpus_load.py CORPUS_DIR [--tombstoned exclude|mark] [--no-verify]

CORPUS_DIR is the `corpus/` directory, or the pulled `files/` directory that
contains it. Sync it first: docs/corpus-sync.md.

From Python (e.g. a classifier experiment), import the loader:

    sys.path.insert(0, "<repo>/scripts"); import corpus_load
    for t in corpus_load.load_turns("corpus"):   # live Turns with verified audio
        t.audio_path, t.text, t.bucket, t.declared, t.kind, t.raw
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

SESSIONS_DIR = "sessions"
STAGING_DIR = "corpus-staging"
TURN_FILE = "turn.json"
DEFAULT_AUDIO = "audio.wav"
NONE = "(none)"
MODES = ("exclude", "mark")


@dataclass
class Turn:
    """One Turn directory. `raw` is the whole turn.json; the rest are conveniences."""

    dir: Path
    raw: dict
    tombstoned_by: str | None = None  # the command Turn's directory name, or a legacy value
    audio_problem: str | None = None  # "sha256_mismatch", "audio_missing", ...

    @property
    def name(self) -> str:
        return self.dir.name

    @property
    def kind(self) -> str | None:
        return _str(self.raw.get("kind"))

    @property
    def bucket(self) -> str | None:
        return _str(self.raw.get("bucket"))

    @property
    def declared(self) -> bool:
        return isinstance(self.raw.get("declaration"), dict) or self.raw.get("bucket_source") == "declaration"

    @property
    def text(self) -> str | None:
        """`transcript.text` exactly as the engine produced it, None without a transcript."""
        tr = self.raw.get("transcript")
        return _str(tr.get("text")) if isinstance(tr, dict) else None

    @property
    def tombstones(self) -> str | None:
        return _str(self.raw.get("tombstones")) if self.kind == "command" else None

    @property
    def tombstoned(self) -> bool:
        return self.tombstoned_by is not None

    @property
    def audio_path(self) -> Path:
        audio = self.raw.get("audio")
        f = _str(audio.get("file")) if isinstance(audio, dict) else None
        # A file name only: turn.json never points outside its own directory.
        return self.dir / (f if f and Path(f).name == f else DEFAULT_AUDIO)


@dataclass
class Problem:
    name: str  # Turn directory name
    problem: str
    detail: str = ""


@dataclass
class Corpus:
    root: Path
    turns: list[Turn] = field(default_factory=list)  # every loadable Turn, sorted by directory name
    problems: list[Problem] = field(default_factory=list)
    dangling_tombstones: list[str] = field(default_factory=list)  # targets not (yet) in this Corpus


def _str(v) -> str | None:
    return v if isinstance(v, str) else None


def corpus_dir(path: Path) -> Path:
    """The Corpus directory itself, given it or the `files/` directory holding it."""
    path = Path(path).expanduser()
    if not path.is_dir():
        raise FileNotFoundError(f"not a directory: {path}")
    inner = path / "corpus"
    return inner if inner.is_dir() and not (path / TURN_FILE).exists() else path


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def tombstone_map(turns: list[Turn]) -> dict[str, str]:
    """Target directory name -> the earliest command Turn's directory name that tombstones it."""
    out: dict[str, str] = {}
    for t in sorted(turns, key=lambda t: t.name):
        if t.tombstones:
            out.setdefault(t.tombstones, t.name)
    return out


def load_corpus(path, verify: bool = True) -> Corpus:
    """Every Turn of the Corpus at `path`, each marked with its tombstone and audio problem."""
    root = corpus_dir(Path(path))
    corpus = Corpus(root)
    for d in sorted(root.iterdir()):
        if not d.is_dir() or d.name.startswith(".") or d.name in (SESSIONS_DIR, STAGING_DIR):
            continue
        tj = d / TURN_FILE
        if not tj.is_file():
            corpus.problems.append(Problem(d.name, "turn_json_missing"))
            continue
        try:
            raw = json.loads(tj.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
            corpus.problems.append(Problem(d.name, "turn_json_unparseable", str(e)))
            continue
        if not isinstance(raw, dict):
            corpus.problems.append(Problem(d.name, "turn_json_unparseable", "not a JSON object"))
            continue
        turn = Turn(d, raw, tombstoned_by=_str(raw.get("tombstoned_by")))
        turn.audio_problem, detail = _check_audio(turn, verify)
        if turn.audio_problem:
            corpus.problems.append(Problem(d.name, turn.audio_problem, detail))
        corpus.turns.append(turn)

    by_name = {t.name: t for t in corpus.turns}
    for target, by in tombstone_map(corpus.turns).items():
        t = by_name.get(target)
        if t is None:
            corpus.dangling_tombstones.append(target)
        elif t.tombstoned_by is None:  # a legacy tombstoned_by wins
            t.tombstoned_by = by
    return corpus


def _check_audio(turn: Turn, verify: bool) -> tuple[str | None, str]:
    path = turn.audio_path
    if not path.is_file():
        return "audio_missing", path.name
    if not verify:
        return None, ""
    audio = turn.raw.get("audio")
    want = _str(audio.get("sha256")) if isinstance(audio, dict) else None
    if not want:
        return "sha256_missing", ""
    got = sha256_of(path)
    if got != want.lower():
        return "sha256_mismatch", f"turn.json {want[:12]}…, file {got[:12]}…"
    return None, ""


def load_turns(path, include_tombstoned: bool = False, verify: bool = True) -> list[Turn]:
    """The classifier's view: Turns whose audio is present (and verified), tombstoned ones dropped."""
    return [
        t for t in load_corpus(path, verify).turns
        if t.audio_problem is None and (include_tombstoned or not t.tombstoned)
    ]


# --- stats ----------------------------------------------------------------


def stats(corpus: Corpus, tombstoned: str = "exclude") -> dict:
    if tombstoned not in MODES:
        raise ValueError(f"tombstoned must be one of {MODES}")
    counted = [t for t in corpus.turns if tombstoned == "mark" or not t.tombstoned]
    by_bucket: dict[str, list[int]] = {}
    for t in counted:
        row = by_bucket.setdefault(t.bucket or NONE, [0, 0])
        row[0] += 1
        row[1] += t.declared
    speech = [t for t in counted if t.kind != "command"]
    heard = [t for t in speech if (t.text or "").strip()]
    return {
        "turns": len(corpus.turns),
        "counted": len(counted),
        "tombstoned": sum(t.tombstoned for t in corpus.turns),
        "by_kind": dict(Counter(t.kind or NONE for t in counted)),
        "tombstoned_by_kind": dict(Counter(t.kind or NONE for t in counted if t.tombstoned)),
        "by_bucket": {b: tuple(v) for b, v in by_bucket.items()},
        "declared": (sum(t.declared for t in speech), len(speech)),
        "declared_heard": (sum(t.declared for t in heard), len(heard)),
    }


def _pct(n: int, d: int) -> str:
    return f"{100 * n / d:.0f}%" if d else "n/a"


def report(corpus: Corpus, tombstoned: str = "exclude") -> str:
    s = stats(corpus, tombstoned)
    mark = tombstoned == "mark"
    how = "counted below, see column" if mark else "excluded below"
    out = [
        f"# Corpus: {corpus.root}",
        "",
        f"{s['turns']} Turn(s), {s['tombstoned']} tombstoned ({how}), {len(corpus.problems)} problem(s).",
        "",
        "## By kind",
        "",
        "| kind | Turns | tombstoned |" if mark else "| kind | Turns |",
        "| --- | ---: | ---: |" if mark else "| --- | ---: |",
    ]
    for kind, n in sorted(s["by_kind"].items(), key=lambda kv: (-kv[1], kv[0])):
        out.append(f"| {kind} | {n} | {s['tombstoned_by_kind'].get(kind, 0)} |" if mark else f"| {kind} | {n} |")
    out += ["", "## By Bucket", "", "| Bucket | Turns | declared | Declaration rate |", "| --- | ---: | ---: | ---: |"]
    for b, (n, d) in sorted(s["by_bucket"].items(), key=lambda kv: (kv[0] == NONE, -kv[1][0], kv[0])):
        out.append(f"| {b} | {n} | {d} | {_pct(d, n)} |")
    (dn, dd), (hn, hd) = s["declared"], s["declared_heard"]
    out += [
        "",
        f"Declared: {dn} of {dd} non-command Turn(s) ({_pct(dn, dd)}); "
        f"{hn} of {hd} with a non-empty transcript ({_pct(hn, hd)}).",
        "",
        f"Tombstones: {s['tombstoned']} Turn(s) tombstoned.",
    ]
    if corpus.dangling_tombstones:
        out.append(f"{len(corpus.dangling_tombstones)} tombstone(s) name a Turn not in this Corpus "
                   f"(not synced yet?): {', '.join(corpus.dangling_tombstones)}")
    if corpus.problems:
        out += ["", "## Problems", ""]
        out += [f"- {p.name}: {p.problem}" + (f" ({p.detail})" if p.detail else "") for p in corpus.problems]
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("corpus", type=Path, help="the corpus/ directory, or the files/ directory holding it")
    ap.add_argument("--tombstoned", choices=MODES, default="exclude",
                    help="leave tombstoned Turns out of the counts (default) or count and mark them")
    ap.add_argument("--no-verify", action="store_true", help="skip hashing the audio (still reports missing WAVs)")
    args = ap.parse_args(argv)
    try:
        corpus = load_corpus(args.corpus, verify=not args.no_verify)
    except FileNotFoundError as e:
        print(e, file=sys.stderr)
        return 2
    sys.stdout.write(report(corpus, args.tombstoned))
    return 0


if __name__ == "__main__":
    sys.exit(main())
