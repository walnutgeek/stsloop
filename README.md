# stsloop

An audio-first personal context hub: a continuous speech-to-speech loop that
mediates between you and the streams of information, attention demands, and
capture surfaces in your life — hands-free and eyes-free.

Nothing is built yet. These are the design documents; see [Development setup](#development-setup) for the toolchain.

| Document | What it is |
| --- | --- |
| [`CONTEXT.md`](./CONTEXT.md) | Glossary. The project's vocabulary, and the words it deliberately avoids |
| [`docs/vision.md`](./docs/vision.md) | Long-term vision: the spine, lanes, the control-surface ladder, six phases |
| [`docs/mvp.md`](./docs/mvp.md) | Phase 0 spec: the loop, phrase grammar, corpus format, done-criteria |
| [`docs/adr/`](./docs/adr/) | Architectural decisions and the alternatives rejected |
| [`docs/research/`](./docs/research/) | Primary-source verification of load-bearing technical claims |
| [`docs/brainstorm/`](./docs/brainstorm/) | The original exploratory session. **Inputs, not requirements** |

## In one page

The spine is a **bidirectional turn loop**. Everything is a Turn — one handover
of the floor, in either direction — and capabilities attach as Lanes that are
either Inbound (something competes for your attention) or Outbound (you emit a
note, a command, a prompt).

**Silence is the only scheduler.** A short silence closes your turn; a longer
one grants the machine a turn and drains the attention queue. No wake word, no
button, no modes.

**The phone owns the loop.** Transcription, synthesis, turn-taking and storage
all run on-device and work with no network. The Linux hub is one optional Lane.
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
4. Run `scripts/fetch-models.sh`. It downloads the int8 `chunk-16-left-128`
   files of `sherpa-onnx-streaming-zipformer-en-2023-06-26` and Silero VAD
   (about 70 MB) into `audio/src/main/assets/`, checks each against a pinned
   SHA-256, and generates `bpe.vocab` from `bpe.model` with
   `scripts/export_bpe_vocab.py` (run through `uv`).

The `.so` files and models are git-ignored, so the repo stays small. CI builds
without them: it checks that everything compiles and the JVM tests pass, and
its APK has no speech. The on-device test needs both scripts run first:
`mise exec -- ./gradlew :audio:connectedDebugAndroidTest`.

`mise.toml` puts `adb` on the PATH only in shells where mise is activated; in
any other shell use `mise exec -- adb …`.

### Development phone

| | |
| --- | --- |
| Model | Pixel 10 Pro (`blazer`) |
| Android | 17 (API 37), build `CP3A.260905.009` |

It runs Android 17, so the background-audio hardening in
[`docs/mvp.md`](./docs/mvp.md#platform-constraints-on-the-loop) applies, and
every on-device measurement is taken on this phone. For screenshots while
plugged in, `adb shell svc power stayon usb` keeps the screen awake (undo with
`svc power stayon false`).

### Tuning the timings

The loop's timings (`docs/mvp.md`, "Timings") are read at the start of every
Session from `files/timings.json` in the app's private storage, so they can be
changed without a rebuild. The file is optional, and so is every key in it: a
missing key keeps its default. An unknown key, a duplicate, or an out-of-range
value makes the app log an error under `stsloop.TurnCapture` and use the
defaults for that Session. The effective values are logged under
`stsloop.Session` when a Session starts.

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
`guard_ms` is parsed but unused until the machine speaks.

```sh
# write (the app must be a debug build; takes effect at the next Session)
mise exec -- adb exec-in run-as com.walnutgeek.stsloop sh -c 'cat > files/timings.json' < timings.json
# read back, or delete to return to defaults
mise exec -- adb exec-out run-as com.walnutgeek.stsloop cat files/timings.json
mise exec -- adb shell run-as com.walnutgeek.stsloop rm files/timings.json
```
