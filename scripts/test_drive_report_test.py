#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Tests for test_drive_report.py against a small synthetic fixture Corpus.

    uv run scripts/test_drive_report_test.py
"""

import importlib.util
import json
import math
import struct
import sys
import tempfile
import unittest
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("test_drive_report", HERE / "test_drive_report.py")
tdr = importlib.util.module_from_spec(spec)
sys.modules["test_drive_report"] = tdr
spec.loader.exec_module(tdr)

RATE = 16000


def write_wav(path: Path, frames: list[int]) -> None:
    """Each entry is one 100 ms frame of a constant-magnitude square wave (RMS == entry)."""
    pcm = []
    for amp in frames:
        pcm += [amp if i % 2 == 0 else -amp for i in range(RATE // 10)]
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f"<{len(pcm)}h", *pcm))


def test_block(label, source, overlap_ms=0, devices=("builtin_mic",)):
    return {
        "label": label, "mic_source": source, "mic_input": "builtin", "audio_mode": "normal",
        "input_devices": list(devices), "tts_interval_ms": 5000, "tts_usage": "assistant",
        "tts_overlap": overlap_ms > 0, "tts_overlap_ms": overlap_ms,
        "tts_phrases": ["tts-1"] if overlap_ms else [],
    }


def write_turn(corpus: Path, name: str, session: str, text, test, frames) -> None:
    d = corpus / name
    d.mkdir()
    t = {"schema": 1, "id": name[-6:], "session_id": session, "audio": {"file": "audio.wav", "sample_rate": RATE}}
    if text is not None:
        t["transcript"] = {"text": text}
    if test is not None:
        t["test"] = test
    (d / "turn.json").write_text(json.dumps(t))
    write_wav(d / "audio.wav", frames)


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        c = self.corpus = Path(self.tmp.name)
        # Config A (voice_recognition): noise 100, speech 1000 → 20 dB; one empty transcript; one TTS overlap.
        ten = [100] * 10 + [1000] * 10  # p10 = 100, p90 = 1000
        write_turn(c, "2026-10-10T18:00:00.000Z-aaaaa1", "s1", "ERRANDS ORDER ROOFING SCREWS", test_block("parked", "voice_recognition"), ten)
        write_turn(c, "2026-10-10T18:00:10.000Z-aaaaa2", "s1", "", test_block("parked", "voice_recognition"), ten)
        write_turn(c, "2026-10-10T18:00:20.000Z-aaaaa3", "s1", "THIS IS THE MACHINE SPEAKING TEST NUMBER THREE",
                   test_block("parked", "voice_recognition", overlap_ms=2500), ten)
        # Config B (mic): no transcript at all on one Turn.
        write_turn(c, "2026-10-10T18:01:00.000Z-bbbbb1", "s2", None, test_block("parked", "mic"), [50] * 20)
        write_turn(c, "2026-10-10T18:01:10.000Z-bbbbb2", "s2", "HELLO", test_block("parked", "mic", overlap_ms=300), [50] * 20)
        # Outside test mode, and one broken Turn.
        write_turn(c, "2026-10-10T17:00:00.000Z-ccccc1", "s0", "OLD", None, [10] * 20)
        broken = c / "2026-10-10T17:00:10.000Z-ddddd1"
        broken.mkdir()
        (broken / "turn.json").write_text("{not json")
        # Session logs; s1's last line is cut off mid-write.
        logs = c / "sessions"
        logs.mkdir()
        s1 = [
            {"at": "x", "event": "session_start", "session_id": "s1", "summary": "parked: voice_recognition/builtin",
             "config": {"tts_phrase": "This is the machine speaking, test number {n}."}},
            {"at": "x", "event": "input_routed", "device": {"type": "builtin_mic"}},
            {"at": "x", "event": "tts_start", "phrase": "tts-1", "output_devices": [{"type": "bluetooth_a2dp"}]},
            {"at": "x", "event": "tts_done", "phrase": "tts-1", "mic_rise_db": 9.0},
            {"at": "x", "event": "tts_done", "phrase": "tts-2", "mic_rise_db": 11.0},
            {"at": "x", "event": "recording", "ours": {"silenced": True}},
            {"at": "x", "event": "tts_unobserved", "phrase": "tts-2"},
        ]
        (logs / "2026-10-10T18:00:00.000Z-s1.jsonl").write_text(
            "".join(json.dumps(e) + "\n" for e in s1) + '{"at": "x", "event": "session_e')
        s2 = [
            {"at": "x", "event": "session_start", "session_id": "s2", "summary": "parked: mic/builtin"},
            {"at": "x", "event": "bluetooth_unavailable"},
            {"at": "x", "event": "session_end", "phrases": {"requested": 2}},
        ]
        (logs / "2026-10-10T18:01:00.000Z-s2.jsonl").write_text("".join(json.dumps(e) + "\n" for e in s2))

    def tearDown(self):
        self.tmp.cleanup()

    def test_frame_rms_and_percentiles(self):
        rms = tdr.frame_rms(self.corpus / "2026-10-10T18:00:00.000Z-aaaaa1" / "audio.wav")
        self.assertEqual(20, len(rms))
        self.assertAlmostEqual(100.0, rms[0])
        self.assertAlmostEqual(1000.0, rms[-1])
        self.assertAlmostEqual(2.5, tdr.percentile([1, 2, 3, 4], 50))
        self.assertAlmostEqual(-20.0, tdr.dbfs(3276.8), places=6)

    def test_groups_by_configuration(self):
        rows = [r for r in tdr.read_corpus(self.corpus) if r.test]
        groups = {g.name(): g.summary() for g in tdr.group_rows(rows)}
        a = groups["parked: voice_recognition/builtin, TTS 5s assistant"]
        self.assertEqual(3, a["turns"])
        self.assertAlmostEqual(1 / 3, a["empty_share"])
        self.assertEqual(0, a["no_transcript"])
        self.assertAlmostEqual(100.0, a["noise_rms"])
        self.assertAlmostEqual(1000.0, a["speech_rms"])
        self.assertAlmostEqual(20.0, a["snr_db"])
        self.assertEqual(1, a["tts_overlap"])
        self.assertEqual(["builtin_mic"], a["input_devices"])
        b = groups["parked: mic/builtin, TTS 5s assistant"]
        self.assertEqual(2, b["turns"])
        self.assertEqual(1, b["no_transcript"])
        self.assertAlmostEqual(0.0, b["empty_share"])
        self.assertAlmostEqual(0.0, b["snr_db"])

    def test_phrase_like(self):
        p = "This is the machine speaking, test number {n}."
        self.assertTrue(tdr.phrase_like("THE MACHINE SPEAKING TEST NUMBER ONE", p))
        self.assertFalse(tdr.phrase_like("HELLO", p))
        self.assertFalse(tdr.phrase_like("", p))
        self.assertFalse(tdr.phrase_like(None, p))

    def test_session_log_skips_a_cut_off_line(self):
        events = tdr.read_log(self.corpus / "sessions" / "2026-10-10T18:00:00.000Z-s1.jsonl")
        self.assertEqual(7, len(events))
        s = tdr.session_summary("s1", events)
        self.assertFalse(s["ended"])
        self.assertEqual(["bluetooth_a2dp"], s["tts_outputs"])
        self.assertEqual(["builtin_mic"], s["inputs"])
        self.assertEqual(1, s["silenced"])
        self.assertAlmostEqual(10.0, s["mic_rise_db"])
        self.assertEqual({"tts_unobserved": 1}, s["problems"])

    def test_report_lists_self_capture_candidates_and_problems(self):
        out = tdr.report(self.corpus)
        self.assertIn("5 Turn(s) in 2 Session(s).", out)
        self.assertIn("| 2026-10-10T18:00:20.000Z-aaaaa3 | 2500 ms | yes | THIS IS THE MACHINE SPEAKING TEST NUMBER THREE |", out)
        self.assertIn("| 2026-10-10T18:01:10.000Z-bbbbb2 | 300 ms | no | HELLO |", out)
        self.assertNotIn("aaaaa1 |", out.split("Self-capture")[1])
        self.assertIn("(no session_end: killed?)", out)
        self.assertIn("bluetooth_unavailable x1", out)
        self.assertIn("2026-10-10T17:00:10.000Z-ddddd1: turn.json", out)
        self.assertNotIn("(no test block)", out)

    def test_pipes_in_transcripts_do_not_break_the_table(self):
        write_turn(self.corpus, "2026-10-10T18:02:00.000Z-eeeee1", "s2", "A | B", test_block("parked", "mic", overlap_ms=100), [50] * 20)
        self.assertIn("| A \\| B |", tdr.report(self.corpus))

    def test_audio_is_read_only_for_reported_turns(self):
        rows = tdr.read_corpus(self.corpus, keep=lambda r: r.test is not None)
        self.assertNotIn("2026-10-10T17:00:00.000Z-ccccc1", [r.dir for r in rows])
        self.assertTrue(all(r.noise is not None for r in rows if not r.problem))

    def test_untested_turns_and_session_filter(self):
        self.assertIn("| (no test block) | 1 |", tdr.report(self.corpus, include_untested=True))
        only = tdr.report(self.corpus, sessions={"s2"})
        self.assertIn("2 Turn(s) in 1 Session(s).", only)
        self.assertNotIn("**s1**", only)
        self.assertIn("**s2**", only)


if __name__ == "__main__":
    unittest.main()
