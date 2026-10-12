# stsloop

An audio-first personal context hub: a continuous speech-to-speech loop that
mediates between you and the streams of information, attention demands, and
capture surfaces in your life — hands-free and eyes-free.

**Status: Phase 0 (the MVP) is built and in driving trials.** The Android app
runs the whole loop on the phone, offline. It records each Turn, transcribes it
on-device (sherpa-onnx, Nemotron 0.6B streaming), reads it back, and stores
audio and transcript in an append-only Corpus. Naming a Bucket at the start or
end of a Turn files it there. Four spoken commands work: "scratch that",
"repeat", "what bucket" and "stop listening". In the car it uses the hands-free
mic and speaker. The loop says aloud when the mic or its speech fails silently.
A script loads the pulled Corpus on Linux.

**Next: v0.1, supervising coding agents from the car** ([`docs/mvp.md`](./docs/mvp.md)):
a hub on your own machine publishes your herdr and Claude Code sessions as
Buckets, the loop tells you when one is blocked, and you answer by voice.

What Phase 0 left open is in [`docs/phase0.md`, "Done when"](./docs/phase0.md#done-when):
weeks of real driving use, a tuned trailing silence, the battery cost, and a
first classifier experiment on the Corpus. A release build has no way to export
the Corpus yet ([`docs/corpus-sync.md`](./docs/corpus-sync.md)).
To build and install it, see [Development setup](#development-setup).

| Document | What it is |
| --- | --- |
| [`CONTEXT.md`](./CONTEXT.md) | Glossary. The project's vocabulary, and the words it deliberately avoids |
| [`docs/vision.md`](./docs/vision.md) | Long-term vision: the agent wedge, Buckets and Providers, Focus, the control-surface ladder, phases |
| [`docs/mvp.md`](./docs/mvp.md) | v0.1 scope: the hub, agent Buckets, Drafts, alerts, onboarding, done-criteria |
| [`docs/phase0.md`](./docs/phase0.md) | Phase 0 spec, as built: the loop, timings, phrase grammar, corpus format |
| [`docs/corpus-sync.md`](./docs/corpus-sync.md) | Getting the Corpus onto the Linux box, and loading it with `scripts/corpus_load.py` |
| [`docs/adr/`](./docs/adr/) | Architectural decisions and the alternatives rejected |
| [`docs/research/`](./docs/research/) | Primary-source verification of load-bearing technical claims |
| [`docs/brainstorm/`](./docs/brainstorm/) | The original exploratory session, and Phase 0 findings that push on the vision. **Inputs, not requirements** |

## In one page

The spine is a **bidirectional turn loop**. Everything is a Turn — one handover
of the floor, in either direction — and everything you can talk to is a
**Bucket**: an errands list, an agent session, later a podcast. Providers
publish Buckets; naming one on its own gives it **Focus**, and the loop says
where you are every time Focus changes.

**Silence is the only scheduler.** A short silence closes your turn; a longer
one grants the machine a turn and drains the attention queue. No wake word, no
button. Focus is the one mode, and it is always spoken.

**The phone owns the loop.** Transcription, synthesis, turn-taking and storage
all run on-device and work with no network. The hub on your own machine hosts
optional Providers, and the two meet at JSON fixtures, not shared code.
The use case is driving, where connectivity gaps are normal
([ADR-0001](./docs/adr/0001-phone-owns-the-loop.md)).

**Phase 0 produces a corpus, not a product.** The loop records, transcribes,
echoes and stores everything you say, assigning a Bucket when you name one
aloud. Because a named Bucket is known with certainty, every declaration is a
free labelled training example — the system bootstraps the classifier that
eventually makes declaring optional.

## Development setup

The Android toolchain is installed per user, with no sudo, and pinned in the
repo:

1. Install [mise](https://mise.jdx.dev), then in the repo run `mise install`.
   This installs the JDK pinned in [`mise.toml`](./mise.toml), and sets
   `ANDROID_HOME` (`~/Android/Sdk`) and the SDK tool paths while you are in the
   repo.
2. Run `scripts/setup-android.sh`. It installs the Android command-line tools,
   then uses the Android CLI (`android sdk`) to install platform-tools (adb),
   the Android platform, build-tools, the NDK and CMake at the versions pinned
   in the script. Re-running it once everything is installed changes nothing.

Using the Android CLI is subject to the
[Android SDK terms](https://developer.android.com/studio/terms); there is no
separate license-acceptance step any more. The script passes `--no-metrics`.

The NDK and CMake are needed because sherpa-onnx ships no prebuilt AAR; its
native libraries are built locally:

3. Run `scripts/build-sherpa-onnx.sh`. It clones sherpa-onnx at the pinned
   tag and commit (`v1.13.8`, `11afbd0`) into `~/.cache/stsloop`, builds it
   for `arm64-v8a` with the pinned NDK (about 2 minutes), and copies
   `libsherpa-onnx-jni.so` and `libonnxruntime.so` into
   `audio/src/main/jniLibs/`. It also refreshes the vendored Kotlin API in
   `audio/src/main/kotlin/com/k2fsa/sherpa/onnx/`, copied from upstream's
   `sherpa-onnx/kotlin-api/` (the example apps symlink to it).
4. Run `scripts/fetch-models.sh`. It downloads the STT model,
   `sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25`
   (a 464 MB tarball, kept in `~/.cache/stsloop`; about 660 MB extracted), and
   Silero VAD into `audio/src/main/assets/`. Into the test assets it fetches
   the int8 `chunk-16-left-128` files of
   `sherpa-onnx-streaming-zipformer-en-2023-06-26` (about 70 MB), the model
   shipped before #30, and generates its `bpe.vocab` from `bpe.model` with
   `scripts/export_bpe_vocab.py` (run through `uv`). Every file is checked
   against a pinned SHA-256. The app APK is about 690 MB.

The `.so` files and models are git-ignored, so the repo stays small. CI builds
without them: it checks that everything compiles and the JVM tests pass, and
its APK has no speech. The on-device test needs both scripts run first:
`mise exec -- ./gradlew :audio:connectedDebugAndroidTest`.

5. With both scripts run and the phone attached, build and install the app:
   `mise exec -- ./gradlew :app:installDebug`. Installing over an earlier
   debug build keeps the Corpus. A reinstall removes the Session
   notification, so start the first Session from the app.

`mise.toml` puts `adb` on the PATH only in shells where mise is activated; in
any other shell use `mise exec -- adb …`.

### Development phone

| | |
| --- | --- |
| Model | Pixel 10 Pro (`blazer`) |
| Android | 17 (API 37), build `CP3A.260905.009` |

It runs Android 17, so the background-audio hardening in
[`docs/phase0.md`](./docs/phase0.md#platform-constraints-on-the-loop) applies, and
every on-device measurement is taken on this phone. For screenshots while
plugged in, `adb shell svc power stayon usb` keeps the screen awake (undo with
`svc power stayon false`).

### Editing the Buckets

The Buckets a Turn can be Declared into (`docs/phase0.md`, "Declaration") are read
at the start of every Session from `files/buckets.json` in the app's private
storage. Without the file, the four phase0.md Buckets apply:

```json
{
  "buckets": [
    { "name": "errands",       "aliases": ["errands", "errand", "shopping"] },
    { "name": "house-project", "aliases": ["house project", "house", "the house"] },
    { "name": "work",          "aliases": ["work", "worklog", "work log"] },
    { "name": "ideas",         "aliases": ["idea", "ideas", "thought"] }
  ]
}
```

`name` is written to `turn.json` as `bucket`, and it always counts as an alias.
`aliases` is optional. Matching ignores case and punctuation, and an alias of
several words also matches run together ("houseproject"). Fallback is per
Bucket. A Bucket with no usable name, or with the same name as an earlier one,
is dropped. So is an alias that is not a string. An alias shared by two Buckets
is dropped from both. Each problem is logged as an error under
`stsloop.TurnCapture`, and the rest of the file still applies. Only a file that
is not strict JSON (a duplicate key counts as malformed) or has no `buckets`
array means the defaults. The effective
Buckets are logged under `stsloop.Session` when a Session starts. An empty list,
`{"buckets": []}`, turns Declarations off.

```sh
# write (debug build; takes effect at the next Session)
mise exec -- adb exec-in run-as com.walnutgeek.stsloop sh -c 'cat > files/buckets.json' < buckets.json
# read back, or delete to return to the defaults
mise exec -- adb exec-out run-as com.walnutgeek.stsloop cat files/buckets.json
mise exec -- adb shell run-as com.walnutgeek.stsloop rm files/buckets.json
```

### Tuning the timings

The loop's timings (`docs/phase0.md`, "Timings") are read at the start of every
Session from `files/timings.json` in the app's private storage, so they can be
changed without a rebuild. The file is optional, and so is every key in it: a
missing key keeps its default. Fallback is per key: an unknown key, a
non-number or an out-of-range value is logged as an error under
`stsloop.TurnCapture` and keeps its default, while the other keys still apply.
Keys that only make sense together (`release_threshold` ≤ `speech_threshold`,
`pre_roll_ms` + `min_utterance_ms` < `max_utterance_ms`) are checked together;
on a conflict the last-listed offending key is dropped. A file that is not
strict JSON, a duplicate key included, means all defaults. The effective values
are logged under `stsloop.Session` when a Session starts.

```json
{
  "trailing_silence_ms": 1500,
  "guard_ms": 300,
  "max_utterance_ms": 60000,
  "min_utterance_ms": 300,
  "pre_roll_ms": 300,
  "speech_threshold": 0.5,
  "release_threshold": 0.35
}
```

`speech_threshold` is the Silero probability that starts an utterance;
once one is being captured, `release_threshold` is enough to keep it going.
`pre_roll_ms` of audio before the first speech window is kept in the Turn.
`guard_ms` is how long the mic stays closed after an echo ends (see "The
spoken echo" below); 0 reopens it as soon as the engine reports the end.

```sh
# write (the app must be a debug build; takes effect at the next Session)
mise exec -- adb exec-in run-as com.walnutgeek.stsloop sh -c 'cat > files/timings.json' < timings.json
# read back, or delete to return to defaults
mise exec -- adb exec-out run-as com.walnutgeek.stsloop cat files/timings.json
mise exec -- adb shell run-as com.walnutgeek.stsloop rm files/timings.json
```

### The spoken echo

Every Turn is spoken back once it is written to the Corpus, with Android's
offline `TextToSpeech` (`USAGE_ASSISTANT`), from the Session's `microphone`
foreground service. An all-caps transcript is lower-cased first, so the
engine reads words rather than spelling letters; an empty transcript is not
echoed. The loop is strictly Half-duplex: from the moment an echo starts
until `guard_ms` after the engine reports its end, the recording keeps
running but its samples are dropped before the VAD, so nothing the phone
says can become a Turn. The engine reports the end a little before the
last of the audio leaves the speaker or the car (output latency, more over
Bluetooth), and the guard has to cover that gap. If the engine never reports
an end, the echo is stopped and the mic reopens after 10 s plus 150 ms per
character. An echo is spoken only into Silence: if you are
already speaking when a transcript is ready, it waits until your Turn ends,
and echoes that waited are spoken back to back.

When a Bluetooth hands-free headset (the car) is connected, a Session uses
it, per the #8 spike: it becomes the communication device in normal mode, so
the car's mic records (`voice_recognition`) and the echo plays over the
hands-free link. Without one, the phone mic records and the echo plays over
A2DP or the speaker. The route is put back when the Session ends, however
it ends. The route and each echo are logged under `stsloop.Session`,
`stsloop.TurnCapture` and `stsloop.Echo`; an echo the engine reports done
while nothing plausibly played (no start, no player, or volume 0) is logged
as a warning, and the Session end logs the counts.

The loop also says when it notices a failure Android does not report (#15,
`docs/phase0.md`, "Platform constraints on the loop"): "The microphone is
silenced by another app", "The microphone is recording only silence" (3 s
of digital zeros), "That recording was silent", "My speech may not be
playing". Each is spoken like an echo, once when it starts, and at most once
a minute; its end is only logged. Every `turn.json` says whether its
Recording is silent (`audio.silent`), and Turns a crash left in
`corpus-staging/` are published at the next Session with `"recovered": true`.

```sh
mise exec -- adb logcat -s stsloop.Echo stsloop.TurnCapture stsloop.Session
```

### Commands

Each is obeyed only when it is all you said ("um" and "okay" at either
end aside), and each is stored as a Turn of its own,
`kind: "command"`. A Note that only contains the phrase is stored, not
obeyed. They act on the last Turn the loop read back to you in this
Session:

- **"Scratch that"** (or "discard that") tombstones it, and the loop says
  "dropped." (or "nothing to drop."). The dropped Turn stays in the Corpus,
  audio and all; the command's `tombstones` names the dropped Turn's
  directory, and the transcript list strikes the dropped Turn through.
- **"Repeat"** (or "say that again") reads its transcript back again.
- **"What bucket"** (or "which bucket") says its Bucket, or "no bucket.".
- **"Stop listening"** (or "end session") says "stopped listening." and ends
  the Session, as the Stop button does.

The rules in full are in `docs/phase0.md`, "Commands".

### Reading the Corpus on Linux

Pull the Corpus (debug build) and print its stats: Turn counts by kind and
Bucket, the Declaration rate, tombstones, and any sha256 mismatch. Syncthing
and rsync need an app-side export that does not exist yet; the full story is
in [`docs/corpus-sync.md`](./docs/corpus-sync.md).

```sh
mise exec -- adb exec-out run-as com.walnutgeek.stsloop sh -c 'cd files && tar cf - corpus' | tar xf -
uv run scripts/corpus_load.py corpus          # add --tombstoned mark to count tombstoned Turns
uv run scripts/corpus_load_test.py            # the loader's own tests
```

### Bluetooth test mode (debug builds)

For the in-car experiment (#8, checklist in
[`docs/test-drive.md`](./docs/test-drive.md)), a debug build shows test-mode
buttons under Start/Stop. With test mode on, a Session speaks a fixed phrase
every N seconds through Android's offline `TextToSpeech` **while it keeps
recording** (deliberately not Half-duplex), records with the chosen microphone
path, and logs every routing fact. A test Session does not echo its Turns:
the echo and its mic gating would defeat the experiment. Each Turn gets a `test` block in
`turn.json`, and each Session a route-event log in
`corpus/sessions/<started_at>-<session_id>.jsonl` (one JSON object per line,
append-only; the transcript list skips that directory).

A release (non-debuggable) build ignores the file. The buttons
write `files/testmode.json`, read at every Session start. Every key
is optional and falls back per key like `timings.json`; a missing file means
test mode is off:

```json
{
  "enabled": true,
  "label": "parked-ac-off",
  "mic_source": "voice_recognition",
  "mic_input": "builtin",
  "audio_mode": "normal",
  "tts_interval_ms": 5000,
  "tts_usage": "assistant",
  "tts_phrase": "This is the machine speaking, test number {n}."
}
```

- `mic_source`: `voice_recognition` | `mic` | `unprocessed` | `voice_communication` (the `AudioRecord` source).
- `mic_input`: `default` (no preference) | `builtin` (`setPreferredDevice` on the phone mic) |
  `bluetooth` (`setCommunicationDevice` on the first Bluetooth SCO / LE headset, then prefer its mic;
  logs `bluetooth_unavailable` and records from the default mic when there is none).
- `audio_mode`: `normal` | `in_communication` (held for the Session, restored at the end).
- `tts_interval_ms`: 0 (off) or 2000–600000. `tts_usage`: `assistant` | `media` | `navigation` |
  `voice_communication`, the phrase's `AudioAttributes` usage, which picks its output route.

```sh
mise exec -- adb exec-in run-as com.walnutgeek.stsloop sh -c 'cat > files/testmode.json' < testmode.json
mise exec -- adb shell run-as com.walnutgeek.stsloop rm files/testmode.json   # test mode off
mise exec -- adb logcat -s stsloop.TestMode                                  # the route log, live
uv run scripts/test_drive_report.py <pulled corpus dir>                      # summary per configuration
uv run scripts/test_drive_report_test.py                                     # the report's own tests
```
