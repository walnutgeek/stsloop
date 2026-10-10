#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Summarise a Bluetooth test drive (#27) from a synced Corpus.

Reads the Corpus as plain files: every `<dir>/turn.json` (+ `audio.wav`) and
every `sessions/*.jsonl` route-event log. Groups Turns by their test
configuration (the `test` block of turn.json) and prints, per configuration:

- Turn count, empty-transcript share (and Turns with no transcript at all);
- noise floor and speech level, the method of the #8 first-drive comment:
  per Turn, the 10th and 90th percentile of 100 ms RMS frames, then the
  median over the group's Turns (RMS on the int16 scale, and dBFS);
- input devices actually routed;
- Turns whose audio overlapped TTS playback (self-capture candidates), with
  their transcripts and whether the transcript looks like the test phrase.

Then one line per test Session from its route-event log: TTS output routes,
input routes, silenced recordings, the mic level rise during TTS, and
anything that went wrong (Bluetooth unavailable, lost or unobserved phrases).

    uv run scripts/test_drive_report.py CORPUS_DIR [--session ID ...] [--include-untested]

Pull the Corpus first (see docs/test-drive.md). Turns written outside test
mode have no `test` block and are skipped unless --include-untested.
"""

from __future__ import annotations

import argparse
import array
import json
import math
import re
import statistics
import sys
import wave
from dataclasses import dataclass, field
from pathlib import Path

SESSIONS_DIR = "sessions"
FRAME_MS = 100
CONFIG_KEYS = ("label", "mic_source", "mic_input", "audio_mode", "tts_usage", "tts_interval_ms")
DEFAULT_PHRASE = "This is the machine speaking, test number {n}."
USAGES = {1: "media", 2: "voice_communication", 12: "navigation", 16: "assistant"}


# --- audio levels ---------------------------------------------------------


def frame_rms(path: Path, frame_ms: int = FRAME_MS) -> list[float]:
    """RMS of each whole `frame_ms` frame of a 16-bit mono WAV, on the int16 scale."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError(f"{path}: expected 16-bit mono")
        rate = w.getframerate()
        pcm = array.array("h")
        pcm.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        pcm.byteswap()
    n = rate * frame_ms // 1000
    out = []
    for start in range(0, len(pcm) - n + 1, n):
        chunk = pcm[start : start + n]
        out.append(math.sqrt(sum(s * s for s in chunk) / n))
    return out


def percentile(values: list[float], p: float) -> float:
    """Linear-interpolated percentile (numpy's default), p in 0..100."""
    if not values:
        raise ValueError("no values")
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    lo = math.floor(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def dbfs(rms: float) -> float:
    return 20 * math.log10(rms / 32768) if rms > 0 else -120.0


def ratio_db(signal: float, noise: float) -> float:
    if noise <= 0:
        return math.inf if signal > 0 else 0.0
    return 20 * math.log10(signal / noise) if signal > 0 else -math.inf


# --- reading the Corpus ---------------------------------------------------


@dataclass
class TurnRow:
    dir: str
    session_id: str | None
    transcript: str | None
    test: dict | None
    noise: float | None = None  # p10 of 100 ms RMS
    speech: float | None = None  # p90 of 100 ms RMS
    problem: str | None = None
    wav: Path | None = None

    @property
    def config_key(self) -> tuple:
        if self.test is None:
            return ("(no test block)",)
        return tuple(self.test.get(k) for k in CONFIG_KEYS)


def read_turn(d: Path) -> TurnRow:
    try:
        t = json.loads((d / "turn.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return TurnRow(d.name, None, None, None, problem=f"turn.json: {e}")
    if not isinstance(t, dict):
        return TurnRow(d.name, None, None, None, problem="turn.json is not an object")
    tr = t.get("transcript")
    text = tr.get("text") if isinstance(tr, dict) and isinstance(tr.get("text"), str) else None
    test = t.get("test") if isinstance(t.get("test"), dict) else None
    audio = t.get("audio") if isinstance(t.get("audio"), dict) else {}
    row = TurnRow(d.name, t.get("session_id"), text, test)
    row.wav = d / (audio.get("file") or "audio.wav")
    return row


def measure(row: TurnRow) -> None:
    """Reads the Turn's audio for its noise floor and speech level (the slow part, so only for Turns reported)."""
    try:
        frames = frame_rms(row.wav)
        if frames:
            row.noise = percentile(frames, 10)
            row.speech = percentile(frames, 90)
    except (OSError, ValueError, EOFError, wave.Error) as e:
        row.problem = f"audio: {e}"


def read_corpus(corpus: Path, keep=lambda row: True) -> list[TurnRow]:
    """Every Turn's turn.json; audio levels are read only for the Turns [keep] selects (and unreadable ones)."""
    rows = []
    for d in sorted(corpus.iterdir()):
        if d.is_dir() and d.name != SESSIONS_DIR and not d.name.startswith("."):
            row = read_turn(d)
            if row.problem:
                rows.append(row)
            elif keep(row):
                measure(row)
                rows.append(row)
    return rows


def read_log(path: Path) -> list[dict]:
    """Events of one `.jsonl` log; an incomplete or broken line (a killed Session) is skipped."""
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if isinstance(e, dict):
            events.append(e)
    return events


# --- summaries ------------------------------------------------------------


def words(s: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", s.lower())


def phrase_like(transcript: str | None, phrase: str) -> bool:
    """Whether at least half of the phrase's fixed words appear in the transcript."""
    if not transcript:
        return False
    fixed = [w for w in words(phrase.replace("{n}", " ")) if w]
    if not fixed:
        return False
    heard = set(words(transcript))
    return sum(w in heard for w in fixed) * 2 >= len(fixed)


@dataclass
class Group:
    key: tuple
    rows: list[TurnRow] = field(default_factory=list)

    def name(self) -> str:
        if len(self.key) == 1:
            return self.key[0]
        label, src, inp, mode, usage, interval = self.key
        tts = f"TTS {interval / 1000:g}s {usage}" if interval else "TTS off"
        mode_s = "" if mode == "normal" else f" ({mode})"
        return f"{label or '-'}: {src}/{inp}{mode_s}, {tts}"

    def summary(self) -> dict:
        rows = self.rows
        transcribed = [r for r in rows if r.transcript is not None]
        empty = [r for r in transcribed if not r.transcript.strip()]
        noise = [r.noise for r in rows if r.noise is not None]
        speech = [r.speech for r in rows if r.speech is not None]
        snr = [ratio_db(r.speech, r.noise) for r in rows if r.noise is not None and r.speech is not None]
        snr = [x for x in snr if math.isfinite(x)]
        devices = sorted({d for r in rows if r.test for d in r.test.get("input_devices") or []})
        return {
            "turns": len(rows),
            "no_transcript": len(rows) - len(transcribed),
            "empty_share": len(empty) / len(transcribed) if transcribed else None,
            "noise_rms": statistics.median(noise) if noise else None,
            "speech_rms": statistics.median(speech) if speech else None,
            "snr_db": statistics.median(snr) if snr else None,
            "input_devices": devices,
            "tts_overlap": sum(1 for r in rows if r.test and r.test.get("tts_overlap")),
        }


def group_rows(rows: list[TurnRow]) -> list[Group]:
    groups: dict[tuple, Group] = {}
    for r in rows:
        groups.setdefault(r.config_key, Group(r.config_key)).rows.append(r)
    return list(groups.values())


def session_summary(name: str, events: list[dict]) -> dict:
    start = next((e for e in events if e.get("event") == "session_start"), {})
    end = next((e for e in events if e.get("event") == "session_end"), None)
    tts_out = sorted({d.get("type") for e in events if e.get("event") == "tts_start" for d in e.get("output_devices") or []})
    inputs = sorted({(e.get("device") or {}).get("type") for e in events if e.get("event") == "input_routed" and e.get("device")})
    silenced = sum(1 for e in events if e.get("event") == "recording" and (e.get("ours") or {}).get("silenced"))
    rises = [e["mic_rise_db"] for e in events if e.get("event") == "tts_done" and isinstance(e.get("mic_rise_db"), (int, float))]
    problems = {}
    for kind in ("bluetooth_unavailable", "tts_init_failed", "tts_unobserved", "tts_lost", "tts_error", "tts_skipped", "tts_inaudible"):
        n = sum(1 for e in events if e.get("event") == kind)
        if n:
            problems[kind] = n
    comm = [e for e in events if e.get("event") in ("communication_device_set", "communication_device")]
    return {
        "log": name,
        "session_id": start.get("session_id"),
        "summary": start.get("summary"),
        "phrases": (end or {}).get("phrases"),
        "ended": end is not None,
        "tts_outputs": tts_out,
        "inputs": inputs,
        "silenced": silenced,
        "mic_rise_db": statistics.median(rises) if rises else None,
        "communication_events": len(comm),
        "problems": problems,
    }


# --- report ---------------------------------------------------------------


def fmt(v, spec="") -> str:
    if v is None:
        return "-"
    return format(v, spec)


def cell(v) -> str:
    """A Markdown table cell: pipes and newlines would break the row."""
    return str(v).replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")


def level(rms: float | None) -> str:
    return "-" if rms is None else f"{rms:.0f} ({dbfs(rms):.1f} dBFS)"


def report(corpus: Path, sessions: set[str] | None = None, include_untested: bool = False) -> str:
    def keep(r: TurnRow) -> bool:
        return (not sessions or r.session_id in sessions) and (include_untested or r.test is not None)

    rows = read_corpus(corpus, keep)
    problems = [r for r in rows if r.problem]
    rows = [r for r in rows if not (r.problem or "").startswith("turn.json")]  # nothing to group by
    out = [f"# Test drive report: {corpus}", ""]
    out.append(f"{len(rows)} Turn(s) in {len({r.session_id for r in rows})} Session(s).")
    out.append("")
    out.append("## Per configuration")
    out.append("")
    out.append("| Configuration | Turns | Empty transcript | No transcript | Noise floor RMS | Speech level RMS | Speech/noise | Input devices | TTS overlap |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    groups = group_rows(rows)
    for g in groups:
        s = g.summary()
        empty = "-" if s["empty_share"] is None else f"{s['empty_share']:.0%}"
        out.append(
            f"| {cell(g.name())} | {s['turns']} | {empty} | {s['no_transcript']} | {level(s['noise_rms'])} | "
            f"{level(s['speech_rms'])} | {fmt(s['snr_db'], '.1f')} dB | {', '.join(s['input_devices']) or '-'} | "
            f"{s['tts_overlap']} |"
        )
    out.append("")
    out.append("## Self-capture candidates (Turns overlapping TTS)")
    out.append("")
    candidates = [(g, r) for g in groups for r in g.rows if r.test and r.test.get("tts_overlap")]
    logs_dir = corpus / SESSIONS_DIR
    logs = {p.name: read_log(p) for p in sorted(logs_dir.glob("*.jsonl"))} if logs_dir.is_dir() else {}
    phrases = {}  # session id -> its configured phrase, from the log (turn.json does not repeat it)
    for events in logs.values():
        start = next((e for e in events if e.get("event") == "session_start"), {})
        phrase = (start.get("config") or {}).get("tts_phrase")
        if start.get("session_id") and phrase:
            phrases[start["session_id"]] = phrase
    if not candidates:
        out.append("None.")
    else:
        out.append("| Configuration | Turn | Overlap | Phrase-like | Transcript |")
        out.append("| --- | --- | --- | --- | --- |")
        for g, r in candidates:
            phrase = phrases.get(r.session_id, DEFAULT_PHRASE)
            like = "yes" if phrase_like(r.transcript, phrase) else "no"
            text = "(none)" if r.transcript is None else (r.transcript or "(empty)")
            out.append(f"| {cell(g.name())} | {cell(r.dir)} | {r.test.get('tts_overlap_ms')} ms | {like} | {cell(text)} |")
    out.append("")
    out.append("## Sessions (route-event logs)")
    out.append("")
    shown = 0
    for name, events in logs.items():
        s = session_summary(name, events)
        if sessions and s["session_id"] not in sessions:
            continue
        shown += 1
        out.append(f"- **{s['session_id']}** {s['summary'] or ''}")
        out.append(
            f"  TTS out: {', '.join(t for t in s['tts_outputs'] if t) or '-'}; input: {', '.join(s['inputs']) or '-'}; "
            f"silenced: {s['silenced']}; mic rise during TTS: {fmt(s['mic_rise_db'], '.1f')} dB; "
            f"communication-device events: {s['communication_events']}"
        )
        out.append(f"  phrases: {s['phrases'] or '-'}" + ("" if s["ended"] else " (no session_end: killed?)"))
        if s["problems"]:
            out.append("  problems: " + ", ".join(f"{k} x{v}" for k, v in s["problems"].items()))
    if not shown:
        out.append("None.")
    if problems:
        out.append("")
        out.append("## Unreadable Turns")
        out.append("")
        for r in problems:
            out.append(f"- {r.dir}: {r.problem}")
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("corpus", type=Path, help="the Corpus directory (contains Turn directories and sessions/)")
    ap.add_argument("--session", action="append", help="only this Session id (repeatable)")
    ap.add_argument("--include-untested", action="store_true", help="also report Turns without a test block")
    a = ap.parse_args(argv)
    if not a.corpus.is_dir():
        ap.error(f"{a.corpus} is not a directory")
    sys.stdout.write(report(a.corpus, set(a.session) if a.session else None, a.include_untested))
    return 0


if __name__ == "__main__":
    sys.exit(main())
