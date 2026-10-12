"""The hub's copy of the phone's Corpus (the `corpus` capability, `protocol/README.md`).

Turns arrive as the phone wrote them and are stored the same way, one directory
per Turn (`docs/phase0.md`, "Corpus format"), so `scripts/corpus_load.py` reads
the hub's Corpus directory unchanged:

    <root>/<started_at>-<id>/turn.json    byte for byte the phone's
    <root>/<started_at>-<id>/audio.wav    checked against the offer's SHA-256
    <root>/.incoming/                     uploads in progress (the loader skips dot entries)

- **Idempotent by Turn directory name.** An offer for a Turn the hub holds is
  answered `stored` when both files are the same, without asking for the
  Recording; when they differ it is a `conflict`, and the held Turn is kept: a
  Turn never changes once published.
- **Checked.** A Recording is stored only if the bytes received match the offer's
  length and SHA-256. Nothing is half-stored: a Turn is assembled under
  `.incoming/` and renamed into place whole, as the phone publishes it.
- One `TurnReceiver` per connection, holding at most one Turn on offer. Its
  calls block on the disk, so the hub runs them off its event loop.
"""

from __future__ import annotations

import hashlib
import itertools
import json
import logging
import os
import shutil
from dataclasses import dataclass, field
from pathlib import Path

from . import protocol as p

log = logging.getLogger("stsloop.hub.corpus")

INCOMING = ".incoming"
TURN_FILE = "turn.json"
DEFAULT_AUDIO = "audio.wav"
#: A Recording at the phone's longest max_utterance_ms (600 s at 16 kHz) is about 19 MB.
MAX_AUDIO_BYTES = 64 << 20


def audio_file(raw: dict) -> str | None:
    """The Recording's file name, as `corpus_load.py` resolves it (a plain file name,
    else `audio.wav`), or None when that would not be a file of its own."""
    audio = raw.get("audio")
    name = audio.get("file") if isinstance(audio, dict) else None
    name = name if isinstance(name, str) and name and Path(name).name == name else DEFAULT_AUDIO
    return None if name in (TURN_FILE, ".", "..") or "\0" in name else name


def _sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class CorpusStore:
    """The Corpus directory `root`, created if missing. Clears what a hub that died
    mid-upload left in `.incoming/`, so construct one per hub process, before serving."""

    def __init__(self, root: Path, max_audio_bytes: int = MAX_AUDIO_BYTES):
        self.root = Path(root)
        self.max_audio_bytes = max_audio_bytes
        self._incoming = self.root / INCOMING
        self._ids = itertools.count(1)
        self.root.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(self._incoming, ignore_errors=True)
        self._incoming.mkdir()

    def receiver(self) -> TurnReceiver:
        return TurnReceiver(self, self._incoming / f"r{next(self._ids)}")

    def held(self, offer: p.TurnOffer, audio_name: str) -> bool | None:
        """None when the hub holds no Turn of this name; else whether it is the offered one."""
        held = self.root / offer.turn
        if not held.exists():
            return None
        try:
            audio = held / audio_name
            return (
                (held / TURN_FILE).read_bytes() == offer.turn_json.encode("utf-8")
                and audio.stat().st_size == offer.bytes
                and _sha256_of(audio) == offer.sha256
            )
        except OSError:
            return False


@dataclass
class _Upload:
    offer: p.TurnOffer
    audio_name: str
    dir: Path
    file: object  # the open audio file
    received: int = 0
    hash: object = field(default_factory=hashlib.sha256)


class TurnReceiver:
    """One connection's uploads: answers each `TurnOffer` and `TurnAudio` with a `TurnAck`."""

    def __init__(self, store: CorpusStore, scratch: Path):
        self._store = store
        self._scratch = scratch
        self._upload: _Upload | None = None

    def close(self) -> None:
        """The connection ended: a Turn still on offer is dropped, to be offered again."""
        self._abandon()
        shutil.rmtree(self._scratch, ignore_errors=True)

    def offer(self, offer: p.TurnOffer) -> p.TurnAck:
        self._abandon()
        turn = offer.turn
        if not p.TURN_NAME.fullmatch(turn):
            return _rejected(turn, "bad_turn", "That is not a Turn directory name.")
        try:
            raw = json.loads(offer.turn_json)
        except ValueError:
            raw = None
        if not isinstance(raw, dict):
            return _rejected(turn, "bad_turn", "Its turn.json is not a JSON object.")
        audio_name = audio_file(raw)
        if audio_name is None:
            return _rejected(turn, "bad_turn", "Its turn.json names no file of its own for the Recording.")
        if offer.bytes > self._store.max_audio_bytes:
            return _rejected(turn, "too_large", f"The Recording is over the hub's {self._store.max_audio_bytes} bytes.")
        recorded = raw.get("audio", {}).get("sha256") if isinstance(raw.get("audio"), dict) else None
        if isinstance(recorded, str) and recorded != offer.sha256:
            # Stored all the same, as the phone has it: corpus_load.py reports it as sha256_mismatch.
            log.warning("Turn %s: its Recording on the phone does not match the sha256 in its turn.json", turn)
        held = self._store.held(offer, audio_name)
        if held is not None:
            if held:
                log.info("Turn %s: already held", turn)
                return p.TurnAck(turn, p.STORED)
            log.warning("Turn %s: offered, but the hub holds a different Turn under that name; kept the held one", turn)
            return _rejected(turn, "conflict", "The hub holds a different Turn under this name.")
        try:
            directory = self._scratch / turn
            shutil.rmtree(directory, ignore_errors=True)
            directory.mkdir(parents=True)
            self._upload = _Upload(offer, audio_name, directory, open(directory / audio_name, "wb"))
        except OSError as e:
            return self._failed(turn, e)
        return p.TurnAck(turn, p.SEND, offset=0)

    def audio(self, piece: p.TurnAudio) -> p.TurnAck:
        upload = self._upload
        if upload is None or upload.offer.turn != piece.turn or piece.offset != upload.received:
            self._abandon()
            return _rejected(piece.turn, "out_of_order", "The hub did not ask for that piece.")
        offer = upload.offer
        if upload.received + len(piece.data) > offer.bytes or (piece.last and upload.received + len(piece.data) != offer.bytes):
            self._abandon()
            return _rejected(offer.turn, "size_mismatch", f"The Recording is not the {offer.bytes} bytes offered.")
        try:
            upload.file.write(piece.data)
            upload.hash.update(piece.data)
            upload.received += len(piece.data)
            if not piece.last:
                return p.TurnAck(offer.turn, p.SEND, offset=upload.received)
            if upload.hash.hexdigest() != offer.sha256:
                self._abandon()
                log.warning("Turn %s: the Recording received does not match its sha256; not stored", offer.turn)
                return _rejected(offer.turn, "sha256_mismatch", "The audio the hub received does not match its sha256.")
            return self._store_it(upload)
        except OSError as e:
            return self._failed(offer.turn, e)

    def _store_it(self, upload: _Upload) -> p.TurnAck:
        offer = upload.offer
        upload.file.flush()
        os.fsync(upload.file.fileno())
        upload.file.close()
        with open(upload.dir / TURN_FILE, "wb") as f:
            f.write(offer.turn_json.encode("utf-8"))
            f.flush()
            os.fsync(f.fileno())
        _fsync_dir(upload.dir)
        self._upload = None
        target = self._store.root / offer.turn
        try:
            upload.dir.rename(target)
        except OSError:
            # Another connection stored it first (a phone that reconnected): fine if it is the same Turn.
            shutil.rmtree(upload.dir, ignore_errors=True)
            if not self._store.held(offer, upload.audio_name):
                return _rejected(offer.turn, "conflict", "The hub holds a different Turn under this name.")
        _fsync_dir(self._store.root)
        log.info("Turn %s: stored (%d bytes of audio)", offer.turn, offer.bytes)
        return p.TurnAck(offer.turn, p.STORED)

    def _failed(self, turn: str, error: OSError) -> p.TurnAck:
        log.error("Turn %s: cannot store it: %s", turn, error)
        self._abandon()
        return _rejected(turn, "storage_failed", "The hub could not write the Turn.")

    def _abandon(self) -> None:
        upload, self._upload = self._upload, None
        if upload is not None:
            try:
                upload.file.close()
            except OSError:
                pass
            shutil.rmtree(upload.dir, ignore_errors=True)


def _rejected(turn: str, reason: str, message: str) -> p.TurnAck:
    return p.TurnAck(turn, p.REJECTED, reason=reason, message=message)
