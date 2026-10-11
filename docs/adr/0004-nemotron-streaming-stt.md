---
status: accepted
---

# Streaming nemotron-speech-0.6b, accepting no hotwords until a sherpa-onnx bump

The STT model is `sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25`
(NVIDIA's nemotron-speech-streaming-en-0.6b, int8, 560 ms chunks), a streaming
NeMo transducer under `greedy_search`. It replaces the zipformer of
[ADR-0003](./0003-streaming-transducer-for-hotwords.md) (#30).

The reason is recognition quality on the owner's voice. In the offline A/B on
the drive Recordings (#8), the zipformer's word error rate was 64–73%;
nemotron's was 17–25% parked and 38–51% driving. It is still a streaming
model, so the per-Session STT thread and one stream per Turn stay as they are.

On the Pixel 10 Pro (#30, `SttBenchmarkDeviceTest`, the app's 2 threads, 20
wavs, 141 s), it keeps up with the mic:

| | zipformer (before) | nemotron, 0.3 s flush | nemotron, 1.2 s flush (shipped) |
| --- | --- | --- | --- |
| RTF, unpaced | 0.08 | 0.21 | 0.23 |
| RTF, paced at real time | 0.48 | 0.53–0.56 | 0.57 |
| `transcript.latency_ms`, median (p90) | 152 ms (214) | 207–219 ms (279–439) | 325 ms (547) |
| Load | 1.3 s | 3.2–3.4 s | 3.2 s |
| Memory after load (PSS) | 170 MB | 757 MB | 757 MB |
| App APK | ~102 MB | 692 MB | 692 MB |

The bar set for #30 was "RTF comfortably under 0.5 and a median end-of-Turn
latency under about 1 s". The RTF it was meant against is the unpaced one,
comparable with the A/B's desktop 0.166: 0.23. Paced at real time, the CPU
idles between 100 ms chunks and the clocks drop, so every model reads higher
(the zipformer 0.08 → 0.48); a paced RTF under 1 still keeps up, and the
latency, which includes any backlog, is the number that gates the echo.

## Considered options

- **Keep the zipformer.** Rejected: at two words in three wrong, the Corpus is
  barely readable, and Declarations rarely match.
- **`parakeet-tdt-0.6b-v2`.** As accurate, but offline: it decodes only after
  the Turn closes, so its whole decode would sit in front of the echo.
- **`fastconf-480ms`.** Streaming and small, but only slightly better than the
  zipformer.
- **A smaller-chunk nemotron (160 ms / 80 ms).** Not needed: the 560 ms
  variant's latency is already well under a second.

## Consequences

- **No hotwords for now.** In the pinned sherpa-onnx v1.13.8, the NeMo
  streaming transducer takes only `greedy_search` (anything else exits at load)
  and has no hotwords support (a hotwords stream exits). Upstream added
  `modified_beam_search` with hotwords for it in `3df7adacc0` (#3895,
  2026-09-14), after v1.13.8, unreleased so far. Refinement needs a sherpa-onnx
  bump, and probably a `bpe.vocab`, which the release tarball does not ship.
  The chokepoint (`Stt` / `HotwordsGate`) refuses hotwords on this model, as
  it refuses them on any model type outside the zipformer family.
- **The zipformer stays in the test APK only**, as the device tests' baseline
  and their one hotwords-capable model.
- **Mixed case, with punctuation.** The text is no longer upper case; the
  phrase grammar is case- and punctuation-insensitive already. Punctuation and
  some spellings ("blest", "forever") vary with the audio around a Turn.
- **The flush pads 1.2 s, not 300 ms**, as the A/B did. The model drops an
  incomplete last 560 ms chunk and emits a word well after it is spoken: with
  300 ms, the system TTS voice's "scratch that" came out as "Scratch", and one
  of 20 drive Recordings lost its last word despite 1.5 s of Silence. This
  costs about 100 ms of median latency.
- **The command phrases are heard.** With the system TTS voice
  (`CommandsDeviceTest`), all eight command phrases came out exactly, so
  classified as their commands, including "end session", which the zipformer
  heard as "IN SESSION" (#14).
- **Size and memory.** About 660 MB of model against 73 MB, read whole into
  memory at Session start. The APK is about 690 MB and needs `adb install`
  rather than any store.
- **License.** The NVIDIA Open Model License, as the Hugging Face card states.
  Harmless for personal use; resolve before any public release.
