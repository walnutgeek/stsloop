# stsloop MVP (Phase 0) — the loop and the Corpus

The MVP is an always-on, half-duplex dictation loop on Android that records,
transcribes, echoes and stores everything you say, assigning a Bucket when you
name one aloud.

It has two jobs, and neither of them is "be useful as a note-taking app":

1. **Produce a labelled Corpus.** Recordings paired with transcripts and, where
   declared, a known-correct Bucket. This is the training and evaluation
   material for every classifier that follows.
2. **Answer a behavioural question.** Does an always-on loop that speaks into
   your silences feel natural at 70mph, or intolerable? No amount of design
   settles this. It has to be driven.

> Vocabulary: [`../CONTEXT.md`](../CONTEXT.md). Long-term shape:
> [`./vision.md`](./vision.md).

## In scope

- A Session: started by a visible user action, running as a foreground service
  with a persistent notification, ended by voice or by tapping stop.
- Voice activity detection, utterance segmentation by trailing Silence.
- On-device transcription of each utterance.
- Spoken echo of the transcript (this is the whole "output" of v1).
- A phrase grammar: Declarations plus four commands.
- Retention of every Turn as immutable audio + JSON.
- Sync of the Corpus to the Linux box as plain files.

## Out of scope — explicitly

Each of these is deferred on purpose, not forgotten:

| Not in v1 | Why |
| --- | --- |
| Any classifier or model beyond VAD + STT | Declaration supplies Buckets deterministically; the classifier is laptop work against the Corpus until it beats Declaration |
| Refinement (Word-list-biased transcription) | Retained audio means this is decidable with evidence later; designing it now is guessing |
| Barge-in | Half-duplex by decision. Needs AEC or a keyword spotter |
| The Attention Queue, push-on-silence | Nothing Inbound exists yet to queue |
| Daily brief, podcast, Herdr, browser | Phases 2–4 |
| Any LLM, local or remote | Nothing in v1 needs generation |
| iOS | Phase 5 |
| Wake word | A Session is started deliberately |
| A real UI | Eyes-free. A notification, a stop button, and a transcript list for sanity-checking |

## The loop

```
  ┌──────────────────────────── SESSION ACTIVE ───────────────────────────┐
  │                                                                       │
  │   LISTENING ──speech──────→ CAPTURING ──trailing silence──→ TRANSCRIBE│
  │    ↑                            │                               │     │
  │    │                       (max duration)                        │     │
  │    │                            └───────────────────────────────┤     │
  │    │                                                            ▼     │
  │    │                                                    CLASSIFY (grammar)
  │    │                                                            │     │
  │    │                                                     ┌──────┴─────┐
  │    │                                                     ▼            ▼
  │    │                                                  command       note
  │    │                                                     │            │
  │    │                                                     ▼            ▼
  │    └──── guard interval ──── SPEAKING ◄──────────── execute      persist
  │                            (mic CLOSED)                                │
  └───────────────────────────────────────────────────────────────────────┘
```

The microphone is open in `LISTENING` and `CAPTURING`, and closed from the
moment `SPEAKING` begins until the guard interval after it ends. This is what
makes self-transcription structurally impossible rather than merely unlikely.

### Timings — all tunable, none final

These are starting points to be tuned in the car, and they are the most
important numbers in the product. They should be adjustable without a
rebuild: they are read from `files/timings.json` at Session start (see the
README, "Tuning the timings").

| Parameter | Start at | Governs |
| --- | --- | --- |
| Trailing silence to close an utterance | 1500 ms | How long you can pause mid-thought before being cut off |
| Guard interval after TTS | 300 ms | Whether speaker bleed re-opens capture |
| Max utterance duration | 60 s | Runaway capture |
| Min utterance duration | 300 ms | Rejecting coughs, road noise, door slams |

The first one is the one that will annoy you. Expect to change it.

## Speech

The engine is **sherpa-onnx for VAD and STT, Android's offline `TextToSpeech`
for speech out**. All claims below are verified against primary sources in
[`./research/speech-stack-verification.md`](./research/speech-stack-verification.md);
read it before changing anything in this section.

### One microphone, software tee

```
AudioRecord (16 kHz mono, ENCODING_PCM_16BIT — the app owns the mic)
   │
   ├──→ Silero VAD          (sherpa-onnx)
   ├──→ streaming STT       (sherpa-onnx)
   └──→ Corpus writer       ← the same int16 bytes
```

This is what the shipped sherpa-onnx Android examples do. int16 is the native
capture format and the `/32768` float conversion happens **downstream** of the
tee, so byte-identical retention is natural rather than contrived. The JNI
layer does not mutate the caller's buffer, so one array can serve both
consumers. `SpeechSegment(start, samples)` gives a sample offset, so each VAD
utterance maps to an exact byte range in the retained stream.

### TRANSCRIBE: streamed while capturing, published once

Each utterance gets its own recognizer stream, opened at its first sample
(pre-roll included) and fed while it is still being captured. Samples go to
it once the VAD has judged them, so a stream never receives audio past the
cut. When trailing Silence closes the utterance, almost all of it is already
decoded; the only work left is the final flush (300 ms of zero padding,
`inputFinished`, the last decode). That is what gates the echo, so it is kept
as small as possible.

```
capture thread                         STT thread (one per Session)
AudioRecord → Segmenter ─ opened ───→  recognizer.open()       (one stream per Turn)
              (VAD cuts) ─ captured ─→  stream.accept + decode
                         ─ closed ───→  stream.finish → transcript
                                        → CorpusSink → FileCorpusWriter (one atomic publish)
```

- **Capture never waits on decoding.** The capture thread only posts work to
  the Session's STT thread. The recognizer, its streams and the Corpus writes
  all live there, so the mic is never starved.
- **Audio is never lost to recognition.** A transcript may be missing, but a
  Turn's audio is always written. The Turn goes out without a `transcript`
  block in four cases:
  - the model fails to load, or any recognizer call throws (an `Error` also
    stops recognition for the rest of the Session);
  - decoding falls more than 10 s of audio behind capture. The Turn then stops
    being fed and its queued samples are skipped, so memory and latency stay
    bounded even at RTF > 1 (thermal throttling);
  - a Stop has waited 30 s for decoding;
  - the system tears the service down. `onDestroy` hurries the capture so only
    the writes remain, within its 5 s join.

  Each case is logged, and the Session end logs the count. The Recording is the
  primary record, and transcription can be re-run later.
- **One recognizer per Session**, loaded on the STT thread when the Session
  starts, so the mic opens without waiting about 1.3 s for the model. It is
  never reloaded per Turn, and it is released when the Session ends.
- **A Turn is published once, transcript included.** `audio.wav` and
  `turn.json` are staged and renamed into the Corpus only after the transcript
  is final, so a Turn directory never changes after it appears.
- **Latency is stored per Turn as `transcript.latency_ms`.** It runs on one
  monotonic clock (`elapsedRealtimeNanos`), from when the Turn's last sample
  was captured (the time of the `AudioRecord.read` that returned it, placed by
  the sample rate) to when the text was final. `finished_at - ended_at` is not
  used, because it mixes the wall clock with the sample-offset clock, which
  absorbs AudioRecord buffering and drifts over a long Session. How the
  latency splits into queued decoding and the final flush, and the RTF
  (recognizer time over audio time), are logged per Turn under
  `stsloop.Session`.

Measured on the Pixel 10 Pro (#10), with upstream test wavs fed at real time
through the service's pipeline, `latency_ms` was 281–332 ms per Turn
(158–221 ms of decoding still queued at the cut, 44–47 ms of final flush,
and the rest the close being seen: 100 ms chunks, 32 ms VAD windows). An
earlier run, measured as `finished_at - ended_at`, gave 313–352 ms (202–225
ms queued). Paced at real time the recognizer's RTF is about 0.47–0.52; in a
batch it is 0.08–0.12. That gap is probably CPU frequency scaling between chunks, and it
is where any further latency would come from. Decoding only on close would
cost about 0.6 s for a 7.5 s Turn even at the batch RTF, so streaming stays.

### Never run the system recognizer concurrently

This is the sharpest finding of the research, and it is a hard rule.
`android.speech.SpeechRecognizer` runs its own `AudioRecord` in the recognizer
service's process — a different UID — and **two ordinary apps can never
capture audio at the same time. The loser receives silence, not an error.**

There is no exception thrown, no callback, no log. The outcome is a recording
of silence, or a transcript of silence, with the app none the wiser. For a
Corpus whose entire value rests on audio being provably the audio that produced
the transcript, that is the worst available failure mode.

So: if a system-recognizer path is ever added, it must be **exclusive** with
the sherpa path, never concurrent. Any code that touches both should register
an `AudioManager.AudioRecordingCallback` and check `isClientSilenced()`
defensively.

### Model: forced, not preferred

Use **`sherpa-onnx-streaming-zipformer-en-2023-06-26`**, int8,
`chunk-16-left-128` (~70 MB on disk), configured from day one as:

```kotlin
decodingMethod = "modified_beam_search"   // required for hotwords
modelingUnit   = "bpe"
bpeVocab       = "<path>/bpe.vocab"
```

The reason this is forced rather than chosen: **hotwords are transducer-only.**
Any CTC model — including Zipformer2-CTC, which would otherwise be the
attractive modern streaming option — permanently forecloses Refinement. And
this is the only streaming English transducer in the project that ships a BPE
model, so it is the only one where English hotwords work without sourcing extra
files upstream. See [ADR-0003](./adr/0003-streaming-transducer-for-hotwords.md).

Three build-time consequences, all cheap now and annoying later:

- **`bpe.model` is not `bpe.vocab`.** The released tarball ships the former;
  sherpa-onnx requires the latter (it deliberately avoids the sentencepiece C++
  dependency). Add `scripts/export_bpe_vocab.py` to the build now.
- **There is no Maven artifact and no prebuilt AAR.** The native libraries must
  be built with the NDK and vendored as `jniLibs`, and the Kotlin API files
  copied in. Pin a sherpa-onnx commit and decide once where those files come
  from — the repo duplicates them per example app.
- **Benchmark in the final decoding configuration.** Every published RTF figure
  used `greedy_search`, on unstated hardware. There is **no** published RTF for
  `modified_beam_search`, and no latency, RTF or RAM figure for Pixel-class
  hardware for any sherpa-onnx streaming model. These numbers have to be
  measured on the actual phone, early.

### `_Exit(-1)` is a safety constraint

Passing a non-empty hotwords string to a non-transducer recognizer calls
`_Exit(-1)` — an uncatchable process kill. No Java exception, no crash report.
Put a **single chokepoint** in front of stream creation that refuses to pass
hotwords unless the loaded model is a transducer configured for
`modified_beam_search`.

The inverse failure is also silent: *malformed* hotwords are logged and
skipped, with no runtime signal. Bucket Word lists must therefore be validated
at authoring time, not at use time.

Two API details worth writing down before they cause a confusing afternoon: the
per-stream hotword separator is `/`, not newline; and per-stream hotwords are
**unioned with** any config-time defaults, so leave `hotwordsFile` and
`hotwordsBuf` empty if a Bucket's list should be the only vocabulary in play.

### Refinement is already well-shaped for later

Not needed in v1, but worth knowing the path exists and costs little:

```kotlin
fun createStream(hotwords: String = ""): OnlineStream
```

One recognizer instance, N streams, one per utterance, each with its own Bucket
Word list, **with no model reload**. This is exactly the shape a two-pass
Refinement needs. The offline transducer path has the same support if a slower,
better second pass is wanted. Note that Whisper and Moonshine support no
hotwords at all, so neither can serve Refinement.

### Platform constraints on the loop

Two Android behaviours directly shape the Session lifecycle:

- **The loop must be started from the foreground.** `RECORD_AUDIO` is a
  while-in-use permission, so creating a `microphone` foreground service from
  the background throws `SecurityException`, and it cannot be launched from a
  `BOOT_COMPLETED` receiver. Any restart path must use a documented exemption —
  a notification action, an app widget, or a `PendingIntent` from a visible app.
  Verified on one Pixel 10 Pro / Android 17 build (#5): a notification action
  whose `PendingIntent` is `getForegroundService(...)` aimed straight at the
  Session service starts the `microphone` FGS with the app in the background,
  with its process killed, and from the shade pulled over the keyguard
  (`reasonCode:NOTIFICATION_SERVICE`, `allowWiu`). Whether the action is
  reachable on the lock screen itself depends on the user's lock-screen
  notification setting. Per the docs (not tested here): a direct activity
  `PendingIntent` also works but shows UI and needs an unlock on the
  keyguard; a service or receiver that then starts an activity (a
  trampoline) is blocked since Android 12.
- **Android 17 hardens background audio playback** — `AudioTrack.write()`,
  audio focus and volume APIs — for **all apps regardless of targetSdk**,
  requiring a visible activity or a non-`SHORT_SERVICE` foreground service. A
  correctly-typed `microphone` FGS is a while-in-use type and should satisfy
  this, but it must be registered as such and must not be a `SHORT_SERVICE`.
  The failure mode here is silent too: the playback and volume APIs "fail
  silently without throwing an exception."

Three silent failure modes in one subsystem is a theme. Instrument deliberately:
if a Turn produces an all-zero Recording, or TTS reports success with no audible
output, the loop should notice and say so rather than quietly filling the Corpus
with silence.

## Phrase grammar

Pure string matching against the transcript. No inference, no model, no
thresholds.

### Declaration

A Bucket named at the **start** or **end** of an utterance, optionally
separated by a filler word:

```
"errands, order roofing screws"           → bucket=errands,  note="order roofing screws"
"order roofing screws, errands"           → bucket=errands,  note="order roofing screws"
"house project — check the joist spacing" → bucket=house-project
"order roofing screws"                    → bucket=null (unlabelled; test data)
```

Buckets are declared in a user-editable config, each with a canonical name and
spoken aliases, because STT output varies and "house project" may arrive as
"house-project" or "houseproject":

```json
{
  "buckets": [
    { "name": "errands",      "aliases": ["errands", "errand", "shopping"] },
    { "name": "house-project", "aliases": ["house project", "house", "the house"] },
    { "name": "work",          "aliases": ["work", "worklog", "work log"] },
    { "name": "ideas",         "aliases": ["idea", "ideas", "thought"] }
  ]
}
```

Matching is case-insensitive, punctuation-insensitive, and anchored — a Bucket
alias occurring mid-utterance is **not** a Declaration. "I need to work on the
roof" must not land in `work`.

### Commands

Four, chosen because the loop is unusable in a car without them:

| Said | Effect |
| --- | --- |
| "scratch that" / "discard that" | Delete the previous Turn, audio and all. Echo "dropped." |
| "repeat" / "say that again" | Re-speak the previous transcript |
| "stop listening" / "end session" | End the Session cleanly |
| "what bucket" / "which bucket" | Speak the Bucket assigned to the previous Turn |

Commands are recognised only as a **whole utterance**, not as a substring. A
Note that happens to contain the words "repeat" must be stored, not obeyed.
Ambiguity resolves toward storing — losing a thought is worse than ignoring a
command.

Every command invocation is itself persisted as a Turn with `kind: "command"`,
because command phrasing is also something the eventual classifier must learn,
and these are labelled examples too.

## Corpus format

Immutable, append-only, one directory per Turn. Nothing is ever edited;
"scratch that" writes a tombstone rather than deleting in place, so the Corpus
stays append-only and the deletion itself is data.

```
corpus/
  2026-10-06T14:22:07.431Z-a3f1c9/
    audio.wav      16 kHz mono PCM — immutable
    turn.json
```

```json
{
  "schema": 1,
  "id": "a3f1c9",
  "session_id": "0f22ab",
  "started_at": "2026-10-06T14:22:07.431Z",
  "ended_at": "2026-10-06T14:22:12.411Z",
  "audio": { "file": "audio.wav", "sha256": "…", "sample_rate": 16000, "duration_ms": 4980 },
  "vad": { "speech_ms": 3180, "trailing_silence_ms": 1500 },
  "transcript": {
    "text": "ERRANDS ORDER ROOFING SCREWS",
    "engine": "sherpa-onnx",
    "model": "…",
    "finished_at": "2026-10-06T14:22:12.650Z",
    "latency_ms": 239
  },
  "kind": "note",
  "declaration": { "bucket": "errands", "position": "leading", "matched": "errands" },
  "bucket": "errands",
  "bucket_source": "declaration",
  "content": "order roofing screws",
  "app_version": "0.1.0",
  "tombstoned_by": null
}
```

Design notes worth keeping:

- `bucket_source` will later take the value `classifier`, so predictions and
  ground truth never get confused in the same field.
- `model` and `app_version` are recorded on every Turn, so the Corpus remains
  interpretable after the engine changes underneath it.
- `kind` is one of `note`, `command`, `unclassified`. Until the phrase
  grammar exists, every Turn is `unclassified`.
- `transcript.text` is exactly what the engine produced. The current model
  emits upper case with no punctuation. A
  future cased or punctuated model must not be flattened, so normalisation is
  left to readers (the phrase grammar does its own). `transcript.model` names the
  model directory, encoder variant and decoding method. The block is absent
  when no recognizer was available for the Turn.
- `transcript.latency_ms` is the end-of-utterance to transcript latency: from
  when the Turn's last sample was captured to when the text was final, on one
  monotonic clock (see "TRANSCRIBE" above).
- A Turn's audio is pre-roll + speech + trailing Silence, so `duration_ms` is
  `pre_roll_ms` (300 above) + `vad.speech_ms` + `vad.trailing_silence_ms`, less
  any pre-roll clipped by the previous Turn. `speech_ms` spans first to last
  speech window, gaps shorter than the trailing Silence included.
- A tombstoned Turn keeps its audio. "Scratch that" usually means *I misspoke*,
  and the misspeaking is training data.

## Modules

```
:app      Android. Session UI, notification, stop button, transcript list.
:audio    Android. AudioRecord, foreground service, Bluetooth route handling,
          sherpa-onnx VAD + STT bindings, system TextToSpeech.
:core     Pure Kotlin — no android.* imports, enforced.
            turn state machine
            silence / guard policy
            Declaration + command grammar
            Bucket config
            Corpus record types + writer interface
```

`:core` having no Android dependency is what makes the Phase 5 KMP lift cheap,
and it also makes the state machine and grammar unit-testable on the JVM
without an emulator — which matters, because those are the parts that need
dozens of small tests.

## Egress

The phone writes the layout above to app storage. An off-the-shelf folder sync
(Syncthing, or `rsync` over Tailscale) mirrors it to the Linux box. No protocol
is built, because the Corpus is immutable and append-only — there is nothing to
reconcile, no conflicts, and order does not matter.

On the Linux side it is a directory of files that `jq` and a Python script can
read directly. That is the entire Phase 1 toolchain.

## Done when

The MVP is finished when it has answered its two questions, not when a feature
list is ticked off:

- [ ] Two weeks of real driving use, with Sessions started without reluctance.
- [ ] Several hundred Turns in the Corpus, a healthy share of them declared.
- [ ] **Zero** instances of the loop transcribing its own TTS.
- [ ] Trailing-silence threshold tuned to a value that does not cut you off
      mid-thought, and it is written down.
- [ ] A Bucket taxonomy that emerged from use rather than from this document —
      including Buckets not listed here, and the removal of ones that were.
- [ ] Battery cost over a commute is known and acceptable.
- [ ] Transcription latency measured on-device in the **final** decoding
      configuration (`modified_beam_search`), not the default, and recorded.
- [ ] The Corpus is readable on the Linux box with a script, and a first
      classifier experiment has been run against it even if it performs badly.

The last point is the real gate. Phase 1 cannot start until the Corpus has
proven itself usable as training data.

## Known unknowns, to be settled empirically

These cannot be designed away and are the reason Phase 0 exists:

- **Tesla Bluetooth routing.** Whether the phone microphone stays usable while
  audio plays over A2DP, or whether the stack drops to the hands-free profile
  and degrades output. Test this before building anything else; it is the one
  finding that could force a redesign.
- **Does the echo help or grate?** Hearing every utterance read back may be
  reassuring confirmation or instantly tiresome. If the latter, the echo
  becomes an earcon plus the Bucket name.
- **Declaration rate.** If, in practice, you rarely remember to declare, the
  Corpus starves and Q7's bootstrap fails. The fallback is confirmation-driven
  labelling — the loop guessing aloud and learning from the correction.
- **Whether road noise defeats the VAD**, and whether the min-utterance filter
  is enough to keep the Corpus clean.
- **Real-time factor of `modified_beam_search` on the actual phone.** No figure
  is published for any model in this configuration, so transcription latency is
  genuinely unknown. Measure this before anything else is built on top of it —
  it is the one number that could force a different model or a chunked
  non-streaming approach.
- **Peak memory of the recommended model on Android.** Also unpublished.
- **Whether shipping only the int8 `chunk-16-left-128` subset loads standalone.**
  Inferred from the release file listing, not verified by running it.
