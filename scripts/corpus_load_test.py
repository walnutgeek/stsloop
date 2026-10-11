#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Tests for corpus_load.py against a small synthetic fixture Corpus.

    uv run scripts/corpus_load_test.py
"""

import hashlib
import importlib.util
import io
import json
import struct
import sys
import tempfile
import unittest
import wave
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("corpus_load", HERE / "corpus_load.py")
cl = importlib.util.module_from_spec(spec)
sys.modules["corpus_load"] = cl
spec.loader.exec_module(cl)

RATE = 16000


def wav_bytes(n_samples: int, amp: int = 100) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f"<{n_samples}h", *([amp] * n_samples)))
    return buf.getvalue()


def write_turn(corpus: Path, name: str, *, sha=None, audio=True, **fields) -> Path:
    """A Turn directory with a tiny WAV and a turn.json whose sha256 matches unless `sha` is given."""
    d = corpus / name
    d.mkdir(parents=True)
    data = wav_bytes(160)
    if audio:
        (d / "audio.wav").write_bytes(data)
    started_at, tid = name.rsplit("-", 1)
    t = {
        "schema": 1, "id": tid, "session_id": "s1", "started_at": started_at,
        "audio": {"file": "audio.wav", "sha256": sha or hashlib.sha256(data).hexdigest(),
                  "sample_rate": RATE, "duration_ms": 10},
        "app_version": "0.1.0",
    }
    t.update(fields)
    (d / "turn.json").write_text(json.dumps(t))
    return d


def note(bucket, text="ERRANDS BUY MILK"):
    return dict(kind="note", transcript={"text": text},
                declaration={"bucket": bucket, "position": "leading", "matched": bucket},
                bucket=bucket, bucket_source="declaration", content=text.lower())


def unclassified(text="SOMETHING UNDECLARED"):
    t = dict(kind="unclassified", declaration=None, bucket=None, bucket_source=None, content=None)
    if text is not None:
        t["transcript"] = {"text": text}
    return t


def command(tombstones, name="scratch_that"):
    t = dict(kind="command", transcript={"text": "SCRATCH THAT"}, declaration=None, bucket=None,
             bucket_source=None, content=None, command={"name": name, "matched": "scratch that"})
    if name == "scratch_that":
        t["tombstones"] = tombstones
    return t


T = "2026-10-10T18:00:{:02d}.000Z-{}"


class CorpusLoadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        # A pulled `files/` directory: the Corpus plus the staging dir beside it.
        self.files = root / "files"
        c = self.corpus = self.files / "corpus"
        c.mkdir(parents=True)
        write_turn(c, T.format(0, "aaaaa0"), **note("errands"))             # live note
        write_turn(c, T.format(1, "aaaaa1"), **note("errands", "ERRANDS OOPS"))  # tombstoned by a2
        write_turn(c, T.format(2, "aaaaa2"), **command(T.format(1, "aaaaa1")))
        write_turn(c, T.format(3, "aaaaa3"), **command(None))               # "nothing to drop"
        write_turn(c, T.format(4, "aaaaa4"), **command(T.format(1, "aaaaa1")))  # later, loses
        write_turn(c, T.format(5, "aaaaa5"), **unclassified())
        write_turn(c, T.format(6, "aaaaa6"), **unclassified(None))          # no transcript block
        write_turn(c, T.format(7, "aaaaa7"), **note("ideas", "IDEAS A PODCAST"))
        write_turn(c, T.format(8, "aaaaa8"), sha="0" * 64, **note("ideas"))  # audio tampered with
        write_turn(c, T.format(9, "aaaaa9"), audio=False, **unclassified())  # audio not synced yet
        # Pre-#13 Turns: no kind, `tombstoned_by` null or (legacy, honoured) non-null.
        write_turn(c, T.format(10, "bbbbb0"), tombstoned_by=None)
        write_turn(c, T.format(11, "bbbbb1"), tombstoned_by="c0ffee", **unclassified("LEGACY"))
        # A command naming a Turn that has not arrived (yet).
        write_turn(c, T.format(12, "bbbbb2"), **command("2026-10-10T19:00:00.000Z-ffffff"))
        # Broken and partial directories.
        (c / T.format(13, "ccccc0")).mkdir()
        (c / T.format(13, "ccccc0") / "turn.json").write_text("{not json")
        (c / T.format(14, "ccccc1")).mkdir()                                  # synced dir, no files yet
        (c / T.format(15, "ccccc2")).mkdir()
        (c / T.format(15, "ccccc2") / "turn.json").write_text("[1, 2]")
        # Not Turns: the test-mode route logs, Syncthing's marker, a stray file, staging.
        (c / "sessions").mkdir()
        (c / "sessions" / "2026-10-10T18:00:00.000Z-s1.jsonl").write_text('{"event": "session_start"}\n')
        (c / ".stfolder").mkdir()
        (c / "README.txt").write_text("not a Turn")
        write_turn(self.files / "corpus-staging", T.format(30, "ddddd0"), **note("errands"))

    def tearDown(self):
        self.tmp.cleanup()

    def names(self, turns):
        return [t.name[-6:] for t in turns]

    def test_loads_every_turn_directory_and_nothing_else(self):
        corpus = cl.load_corpus(self.corpus)
        self.assertEqual(
            ["aaaaa0", "aaaaa1", "aaaaa2", "aaaaa3", "aaaaa4", "aaaaa5", "aaaaa6", "aaaaa7", "aaaaa8",
             "aaaaa9", "bbbbb0", "bbbbb1", "bbbbb2"],
            self.names(corpus.turns))

    def test_a_files_directory_resolves_to_its_corpus_and_skips_staging(self):
        self.assertEqual(cl.load_corpus(self.corpus).turns, cl.load_corpus(self.files).turns)

    def test_reports_sha_mismatch_missing_audio_and_bad_turn_json(self):
        corpus = cl.load_corpus(self.corpus)
        problems = {(p.name[-6:], p.problem) for p in corpus.problems}
        self.assertEqual({
            ("aaaaa8", "sha256_mismatch"),
            ("aaaaa9", "audio_missing"),
            ("ccccc0", "turn_json_unparseable"),
            ("ccccc1", "turn_json_missing"),
            ("ccccc2", "turn_json_unparseable"),
        }, problems)
        by_name = {t.name[-6:]: t for t in corpus.turns}
        self.assertEqual("sha256_mismatch", by_name["aaaaa8"].audio_problem)
        self.assertIsNone(by_name["aaaaa0"].audio_problem)

    def test_skipping_verification_still_reports_missing_audio(self):
        problems = {p.problem for p in cl.load_corpus(self.corpus, verify=False).problems}
        self.assertNotIn("sha256_mismatch", problems)
        self.assertIn("audio_missing", problems)

    def test_a_turn_without_a_recorded_sha256_is_a_problem_only_when_verifying(self):
        d = write_turn(self.corpus, T.format(20, "eeeee1"), **unclassified())
        t = json.loads((d / "turn.json").read_text())
        del t["audio"]["sha256"]
        (d / "turn.json").write_text(json.dumps(t))
        self.assertIn(("eeeee1", "sha256_missing"), {(p.name[-6:], p.problem) for p in cl.load_corpus(self.corpus).problems})
        self.assertNotIn("eeeee1", self.names(cl.load_turns(self.corpus)))
        self.assertIn("eeeee1", self.names(cl.load_turns(self.corpus, verify=False)))

    def test_tombstones_are_derived_from_command_turns_earliest_wins(self):
        corpus = cl.load_corpus(self.corpus)
        by_name = {t.name[-6:]: t for t in corpus.turns}
        self.assertEqual(T.format(2, "aaaaa2"), by_name["aaaaa1"].tombstoned_by)
        self.assertEqual("c0ffee", by_name["bbbbb1"].tombstoned_by)   # legacy field honoured
        self.assertIsNone(by_name["bbbbb0"].tombstoned_by)            # legacy null means nothing
        self.assertIsNone(by_name["aaaaa0"].tombstoned_by)
        self.assertEqual(["aaaaa1", "bbbbb1"], self.names(t for t in corpus.turns if t.tombstoned))
        self.assertEqual(["2026-10-10T19:00:00.000Z-ffffff"], corpus.dangling_tombstones)

    def test_only_command_turns_tombstone(self):
        write_turn(self.corpus, T.format(20, "eeeee0"), **unclassified(), tombstones=T.format(0, "aaaaa0"))
        by_name = {t.name[-6:]: t for t in cl.load_corpus(self.corpus).turns}
        self.assertIsNone(by_name["aaaaa0"].tombstoned_by)

    def test_declared_kind_and_bucket(self):
        by_name = {t.name[-6:]: t for t in cl.load_corpus(self.corpus).turns}
        self.assertTrue(by_name["aaaaa0"].declared)
        self.assertEqual("errands", by_name["aaaaa0"].bucket)
        self.assertFalse(by_name["aaaaa5"].declared)
        self.assertEqual("unclassified", by_name["aaaaa5"].kind)
        self.assertIsNone(by_name["bbbbb0"].kind)
        self.assertEqual("command", by_name["aaaaa2"].kind)
        self.assertEqual("ERRANDS BUY MILK", by_name["aaaaa0"].text)
        self.assertIsNone(by_name["aaaaa6"].text)
        self.assertEqual(self.corpus / T.format(0, "aaaaa0") / "audio.wav", by_name["aaaaa0"].audio_path)

    def test_load_turns_is_the_classifier_view(self):
        # Live (not tombstoned), with verified audio.
        self.assertEqual(
            ["aaaaa0", "aaaaa2", "aaaaa3", "aaaaa4", "aaaaa5", "aaaaa6", "aaaaa7", "bbbbb0", "bbbbb2"],
            self.names(cl.load_turns(self.corpus)))
        self.assertIn("aaaaa1", self.names(cl.load_turns(self.corpus, include_tombstoned=True)))

    def test_stats_exclude_tombstoned_by_default(self):
        s = cl.stats(cl.load_corpus(self.corpus))
        self.assertEqual(13, s["turns"])
        self.assertEqual(11, s["counted"])
        self.assertEqual(2, s["tombstoned"])
        self.assertEqual({"note": 3, "command": 4, "unclassified": 3, "(none)": 1}, s["by_kind"])
        self.assertEqual({"errands": (1, 1), "ideas": (2, 2), "(none)": (4, 0)}, s["by_bucket"])
        # Non-command Turns: a0 a5 a6 a7 a8 a9 b0 -> 7, of which 3 declared.
        self.assertEqual((3, 7), s["declared"])
        # ... and of those with a non-empty transcript: a0 a5 a7 a8 a9 -> 5.
        self.assertEqual((3, 5), s["declared_heard"])

    def test_stats_mark_mode_counts_tombstoned_turns(self):
        s = cl.stats(cl.load_corpus(self.corpus), tombstoned="mark")
        self.assertEqual(13, s["counted"])
        self.assertEqual({"note": 4, "command": 4, "unclassified": 4, "(none)": 1}, s["by_kind"])
        self.assertEqual({"note": 1, "unclassified": 1}, s["tombstoned_by_kind"])
        self.assertEqual({"errands": (2, 2), "ideas": (2, 2), "(none)": (5, 0)}, s["by_bucket"])

    def test_report_prints_the_numbers(self):
        out = cl.report(cl.load_corpus(self.corpus))
        self.assertIn("13 Turn(s), 2 tombstoned (excluded below), 5 problem(s).", out)
        self.assertIn("| note | 3 |", out)
        self.assertIn("| ideas | 2 | 2 | 100% |", out)
        self.assertIn("| (none) | 4 | 0 | 0% |", out)
        self.assertIn("Declared: 3 of 7 non-command Turn(s) (43%); 3 of 5 with a non-empty transcript (60%).", out)
        self.assertIn("2026-10-10T18:00:08.000Z-aaaaa8: sha256_mismatch", out)
        self.assertIn("1 tombstone(s) name a Turn not loaded", out)
        marked = cl.report(cl.load_corpus(self.corpus), tombstoned="mark")
        self.assertIn("2 tombstoned (counted below, see column)", marked)
        self.assertIn("| note | 4 | 1 |", marked)

    def test_main_runs(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = cl.main([str(self.files), "--tombstoned", "mark"])
        self.assertEqual(0, rc)
        self.assertIn("13 Turn(s)", buf.getvalue())

    def test_missing_directory_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            cl.load_corpus(Path(self.tmp.name) / "nope")


if __name__ == "__main__":
    unittest.main()
