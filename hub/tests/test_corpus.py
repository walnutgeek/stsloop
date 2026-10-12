"""The hub's Corpus store at its seam (no sockets): uploads are idempotent by Turn
directory name, every Recording is checked against its offer's SHA-256, and what
the hub stores loads with scripts/corpus_load.py exactly as the phone's Corpus does."""

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from stsloop_hub import protocol as p
from stsloop_hub.corpus import INCOMING, CorpusStore

from support import fixture

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"


def corpus_load():
    spec = importlib.util.spec_from_file_location("corpus_load", SCRIPTS / "corpus_load.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules["corpus_load"] = module
    # Leave scripts/__pycache__ (tracked) as it is.
    sys.dont_write_bytecode, before = True, sys.dont_write_bytecode
    try:
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = before
    return module


NAME = "2026-10-06T14:22:07.431Z-a3f1c9"


def wav(n: int = 100) -> bytes:
    """Not a real WAV: the hub never reads inside the Recording."""
    return bytes((i * 7) % 256 for i in range(n))


def turn_json(audio: bytes, name: str = NAME, **extra) -> str:
    started_at, _, turn_id = name.rpartition("-")
    obj = {
        "schema": 1, "id": turn_id, "session_id": "0f22ab", "started_at": started_at,
        "audio": {"file": "audio.wav", "sha256": hashlib.sha256(audio).hexdigest(), "sample_rate": 16000, "duration_ms": 3},
        "kind": "note", "declaration": {"bucket": "errands", "position": "leading", "matched": "errands"},
        "bucket": "errands", "bucket_source": "declaration", "content": "order roofing screws", "app_version": "0.1.0",
    }
    obj.update(extra)
    return json.dumps(obj, indent=2) + "\n"


def offer(audio: bytes, name: str = NAME, text: str | None = None, sha256: str | None = None) -> p.TurnOffer:
    return p.TurnOffer(
        turn=name,
        turn_json=text if text is not None else turn_json(audio, name),
        sha256=sha256 or hashlib.sha256(audio).hexdigest(),
        bytes=len(audio),
    )


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "corpus"
        self.store = CorpusStore(self.root)
        self.receiver = self.store.receiver()

    def tearDown(self):
        self.receiver.close()
        self.tmp.cleanup()

    def upload(self, audio: bytes, name: str = NAME, chunk: int = 32, receiver=None, **kwargs) -> p.TurnAck:
        receiver = receiver or self.receiver
        ack = receiver.offer(offer(audio, name, **kwargs))
        while ack.status == p.SEND:
            piece = audio[ack.offset:ack.offset + chunk]
            ack = receiver.audio(p.TurnAudio(name, ack.offset, piece, last=ack.offset + len(piece) >= len(audio)))
        return ack

    # --- storing -------------------------------------------------------------

    def test_an_offer_asks_for_the_recording_from_the_start(self):
        self.assertEqual(self.receiver.offer(offer(wav())), p.TurnAck(NAME, p.SEND, offset=0))

    def test_each_piece_asks_for_the_next_and_the_last_stores_the_turn(self):
        audio = wav(70)
        self.receiver.offer(offer(audio))
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 0, audio[:32], False)), p.TurnAck(NAME, p.SEND, offset=32))
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 32, audio[32:64], False)), p.TurnAck(NAME, p.SEND, offset=64))
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 64, audio[64:], True)), p.TurnAck(NAME, p.STORED))

    def test_a_stored_turn_is_the_phones_two_files_byte_for_byte(self):
        audio = wav()
        text = turn_json(audio)
        self.assertEqual(self.upload(audio, text=text).status, p.STORED)
        self.assertEqual(sorted(f.name for f in (self.root / NAME).iterdir()), ["audio.wav", "turn.json"])
        self.assertEqual((self.root / NAME / "audio.wav").read_bytes(), audio)
        self.assertEqual((self.root / NAME / "turn.json").read_bytes(), text.encode())

    def test_the_recording_is_stored_under_the_file_name_its_turn_json_gives(self):
        audio = wav()
        text = turn_json(audio)
        text = text.replace('"file": "audio.wav"', '"file": "take-1.wav"')
        self.assertEqual(self.upload(audio, text=text).status, p.STORED)
        self.assertEqual((self.root / NAME / "take-1.wav").read_bytes(), audio)

    def test_an_empty_recording_is_stored_from_one_empty_last_piece(self):
        self.assertEqual(self.upload(b"").status, p.STORED)
        self.assertEqual((self.root / NAME / "audio.wav").read_bytes(), b"")

    def test_nothing_is_left_incoming_once_stored(self):
        self.upload(wav())
        self.assertEqual(list((self.root / INCOMING).rglob("*.*")), [])

    # --- idempotency ------------------------------------------------------------

    def test_offering_a_turn_the_hub_holds_is_answered_stored_with_no_audio_asked_for(self):
        audio = wav()
        self.upload(audio)
        before = (self.root / NAME / "audio.wav").stat().st_mtime_ns
        self.assertEqual(self.store.receiver().offer(offer(audio)), p.TurnAck(NAME, p.STORED))
        self.assertEqual((self.root / NAME / "audio.wav").stat().st_mtime_ns, before)

    def test_a_turn_already_in_the_hubs_corpus_by_other_means_counts_as_held(self):
        audio = wav()
        (self.root / NAME).mkdir(parents=True)
        (self.root / NAME / "audio.wav").write_bytes(audio)
        (self.root / NAME / "turn.json").write_text(turn_json(audio))
        self.assertEqual(self.receiver.offer(offer(audio)).status, p.STORED)

    def test_a_different_turn_under_a_held_name_is_a_conflict_and_the_held_one_is_kept(self):
        audio = wav()
        self.upload(audio)
        other = wav(101)
        ack = self.receiver.offer(offer(other))
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "conflict"))
        self.assertEqual((self.root / NAME / "audio.wav").read_bytes(), audio)
        ack = self.receiver.offer(offer(audio, text=turn_json(audio, content="edited")))
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "conflict"))

    def test_a_reconnected_phone_starts_a_half_sent_turn_over(self):
        audio = wav(70)
        self.receiver.offer(offer(audio))
        self.receiver.audio(p.TurnAudio(NAME, 0, audio[:32], False))
        self.receiver.close()  # the connection dropped
        self.assertEqual(self.upload(audio, receiver=self.store.receiver()).status, p.STORED)
        self.assertEqual((self.root / NAME / "audio.wav").read_bytes(), audio)

    def test_a_new_offer_abandons_the_turn_on_offer(self):
        a, b = wav(70), wav(40)
        b_name = "2026-10-06T14:22:09.000Z-0dd5e7"
        self.receiver.offer(offer(a))
        self.receiver.audio(p.TurnAudio(NAME, 0, a[:32], False))
        self.assertEqual(self.upload(b, name=b_name).status, p.STORED)
        ack = self.receiver.audio(p.TurnAudio(NAME, 32, a[32:], True))
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "out_of_order"))
        self.assertFalse((self.root / NAME).exists())

    # --- the hash check ------------------------------------------------------------

    def test_a_recording_that_does_not_match_its_sha256_is_rejected_and_not_stored(self):
        audio = wav()
        ack = self.upload(audio, sha256="0" * 64)
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "sha256_mismatch"))
        self.assertFalse((self.root / NAME).exists())
        self.assertEqual(list((self.root / INCOMING).rglob("*.*")), [])

    def test_a_recording_that_differs_from_its_turn_json_is_stored_as_the_phone_has_it_and_logged(self):
        audio = wav()
        text = turn_json(wav(99))  # the phone's file no longer matches its record
        with self.assertLogs("stsloop.hub.corpus", "WARNING") as logged:
            self.assertEqual(self.upload(audio, text=text).status, p.STORED)
        self.assertIn("does not match the sha256 in its turn.json", "\n".join(logged.output))
        corpus = corpus_load().load_corpus(self.root)
        self.assertEqual([pr.problem for pr in corpus.problems], ["sha256_mismatch"])

    def test_a_piece_corrupted_in_transit_is_caught(self):
        audio = wav(64)
        self.receiver.offer(offer(audio))
        self.receiver.audio(p.TurnAudio(NAME, 0, audio[:32], False))
        bad = bytes([audio[32] ^ 1]) + audio[33:]
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 32, bad, True)).reason, "sha256_mismatch")

    def test_a_recording_longer_or_shorter_than_offered_is_rejected(self):
        audio = wav(64)
        self.receiver.offer(offer(audio))
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 0, audio + b"x", False)).reason, "size_mismatch")
        self.receiver.offer(offer(audio))
        self.assertEqual(self.receiver.audio(p.TurnAudio(NAME, 0, audio[:10], True)).reason, "size_mismatch")
        self.assertFalse((self.root / NAME).exists())

    def test_a_piece_at_an_offset_not_asked_for_is_out_of_order(self):
        audio = wav(64)
        self.receiver.offer(offer(audio))
        ack = self.receiver.audio(p.TurnAudio(NAME, 32, audio[32:], True))
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "out_of_order"))

    def test_a_piece_for_a_turn_not_on_offer_is_out_of_order(self):
        ack = self.receiver.audio(p.TurnAudio(NAME, 0, b"abc", True))
        self.assertEqual((ack.turn, ack.status, ack.reason), (NAME, p.REJECTED, "out_of_order"))

    # --- what the hub will not store -----------------------------------------------

    def test_a_turn_json_that_is_not_an_object_is_a_bad_turn(self):
        for text in ("[1, 2]", "not json", '"x"'):
            with self.subTest(text=text):
                self.assertEqual(self.receiver.offer(offer(wav(), text=text)).reason, "bad_turn")

    def test_an_audio_file_that_is_not_a_plain_name_is_stored_where_corpus_load_looks(self):
        # corpus_load reads audio.wav for any audio.file that is not a plain file name.
        audio = wav()
        for i, name in enumerate(("../escape.wav", "/etc/passwd", "a/b.wav", "", None)):
            with self.subTest(file=name):
                turn = f"2026-10-06T14:22:0{i}.000Z-0dd5e{i}"
                text = turn_json(audio, turn).replace('"file": "audio.wav"', f'"file": {json.dumps(name)}')
                self.assertEqual(self.upload(audio, turn, text=text).status, p.STORED)
                self.assertEqual(sorted(f.name for f in (self.root / turn).iterdir()), ["audio.wav", "turn.json"])
        self.assertFalse((self.root.parent / "escape.wav").exists())

    def test_an_audio_file_named_like_the_turn_json_or_a_directory_is_a_bad_turn(self):
        audio = wav()
        for name in ("turn.json", "..", "a\x00b.wav"):
            with self.subTest(file=name):
                text = turn_json(audio).replace('"file": "audio.wav"', f'"file": {json.dumps(name)}')
                self.assertEqual(self.receiver.offer(offer(audio, text=text)).reason, "bad_turn")

    def test_a_turn_name_that_is_not_a_turn_directory_name_is_a_bad_turn(self):
        # The wire reader refuses these too; the store does not rely on it.
        for name in ("../x", "corpus", ".incoming", "2026-10-06T14:22:07.431Z-a3f1c9/.."):
            with self.subTest(name=name):
                self.assertEqual(self.receiver.offer(offer(wav(), name)).reason, "bad_turn")
        self.assertEqual([d.name for d in self.root.iterdir()], [INCOMING])

    def test_a_recording_over_the_limit_is_too_large(self):
        store = CorpusStore(self.root, max_audio_bytes=50)
        ack = store.receiver().offer(offer(wav(51)))
        self.assertEqual((ack.status, ack.reason), (p.REJECTED, "too_large"))

    def test_leftovers_of_a_hub_that_died_mid_upload_are_cleared_at_start(self):
        audio = wav(64)
        self.receiver.offer(offer(audio))
        self.receiver.audio(p.TurnAudio(NAME, 0, audio[:32], False))
        CorpusStore(self.root)  # the next start
        self.assertEqual(list((self.root / INCOMING).iterdir()), [])


class CorpusLoadTest(unittest.TestCase):
    """The hub's Corpus directory is one `scripts/corpus_load.py` reads unchanged."""

    def test_uploaded_turns_load_verified_with_tombstones_applied(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "corpus"
            receiver = CorpusStore(root).receiver()
            note, scratch = wav(80), wav(90)
            scratch_name = "2026-10-06T14:22:10.000Z-b4e2d0"
            scratch_json = turn_json(scratch, scratch_name, kind="command", declaration=None, bucket=None,
                                     bucket_source=None, content=None,
                                     command={"name": "scratch_that", "matched": "scratch that"}, tombstones=NAME)
            for name, audio, text in ((NAME, note, turn_json(note)), (scratch_name, scratch, scratch_json)):
                ack = receiver.offer(offer(audio, name, text=text))
                ack = receiver.audio(p.TurnAudio(name, 0, audio, True))
                self.assertEqual(ack.status, p.STORED)

            loader = corpus_load()
            corpus = loader.load_corpus(root)
            self.assertEqual(corpus.problems, [])
            self.assertEqual([t.name for t in corpus.turns], [NAME, scratch_name])
            self.assertEqual(corpus.turns[0].tombstoned_by, scratch_name)
            # Also from the directory that holds corpus/, as for a pulled files/ directory.
            self.assertEqual(len(loader.load_corpus(Path(tmp)).turns), 2)

    def test_the_upload_exchange_fixture_stores_a_turn_that_loads(self):
        offered = p.from_json(fixture("v1", "turn_offer/basic.json"))
        pieces = [p.from_json(fixture("v1", f"turn_audio/{n}.json")) for n in ("first", "last")]
        with tempfile.TemporaryDirectory() as tmp:
            receiver = CorpusStore(Path(tmp)).receiver()
            receiver.offer(offered)
            acks = [receiver.audio(piece) for piece in pieces]
            self.assertEqual(acks[-1].status, p.STORED)
            corpus = corpus_load().load_corpus(Path(tmp))
            self.assertEqual(corpus.problems, [])
            self.assertEqual(corpus.turns[0].bucket, "errands")


if __name__ == "__main__":
    unittest.main()
