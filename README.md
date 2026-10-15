# stsloop

An audio-first personal context hub: a continuous speech-to-speech loop that
mediates between you and the streams of information, attention demands, and
capture surfaces in your life — hands-free and eyes-free.

**Status: Phase 0 (the MVP) is built and in driving trials.** The Android app
runs the whole loop on the phone, offline. It records each Turn, transcribes it
on-device (sherpa-onnx, Nemotron 0.6B streaming), reads it back, and stores
audio and transcript in an append-only Corpus. Naming a Bucket at the start or
end of a Turn files it there; naming one on its own gives it Focus, and the
Turns after it are filed there until a quiet timeout returns Focus to the
Inbox. Spoken commands: "scratch that", "repeat", "where am I" (or "what
bucket"), "inbox" and "stop listening". In the car it uses the hands-free
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
| [`protocol/`](./protocol/) | The phone ↔ hub contract: a JSON Schema per message and golden fixtures |
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

## The hub

`stsloop-hub` runs on the machine where your agents run, and publishes Buckets
to the phone over one WebSocket on your tailnet
([ADR-0008](./docs/adr/0008-python-hub.md)). It always publishes the "hub"
Bucket, and one Bucket per coding agent: each herdr agent, with herdr (the
terminal multiplexer for coding agents) installed, and each Claude Code
session, with its hooks installed.
It is not on PyPI yet, so install it from the repo with
[uv](https://docs.astral.sh/uv/):

```sh
uv tool install "git+https://github.com/walnutgeek/stsloop#subdirectory=hub"   # or, in a clone: uv tool install ./hub
stsloop-hub init     # writes a pairing token, prints the pairing QR code
stsloop-hub serve    # serves the phone's WebSocket
```

`init` keeps its token in `~/.config/stsloop-hub/hub.json` (mode 0600;
`$STSLOOP_HUB_HOME` or `$XDG_CONFIG_HOME` move it). Running it again shows the
same QR code; `--new-token` replaces the token, and phones must pair again.
Both commands use this machine's tailnet address (`tailscale ip -4`) and port
7717, so the hub listens on the tailnet only. `--host` and `--port` choose
another address the phone can reach; given to `init`, they are kept for
`serve`, which then listens there instead. Off the tailnet the connection is
plain `ws://`, so both commands warn: the token and every message travel
unencrypted unless that network is trusted. A phone with the wrong token gets HTTP 401; one speaking a protocol
major the hub does not support gets a refusal, in a sentence the phone can speak.

On the phone, open **Settings**, tap **Pair with hub** and scan the QR code. The phone keeps the
address and token, and caches the hub's Buckets, so naming one ("the hub")
focuses it even while the hub is unreachable; the loop then says "hub
offline". While a Session runs, the phone stays connected, retries a lost
connection, and reconnects at once when its network changes. A pairing made
during a Session is used from the next one. **Forget hub** unpairs.

With herdr, each agent is a Bucket named from its workspace ("stsloop
agent", or just "stsloop"), and Buckets come and go as agents do. Name one,
then say "status": the loop says whether it is blocked, working or done, and
for how long. "What's blocked", said whatever has Focus, lists the blocked
agents. "What does it say" (or "read it") summarises the agent's screen in a
sentence or two. The hub only reads herdr (`herdr api snapshot`, every 2 s,
and `herdr agent read` for a summary); it never types into an agent. `init`
finds the herdr that matches your running herdr server: it tries
`$HERDR_BIN_PATH`, each `herdr` on the PATH and `~/.local/bin/herdr`, says
any that speaks another protocol (an older `/usr/bin/herdr`, say), and saves
the first that answers for `serve`. `serve` runs `$HERDR_BIN_PATH` when set,
else that one, else `herdr` on the PATH, and says at start if it does not
match. Without herdr, the hub starts as before.

"Hub status" (or "hub health", "how is the hub"), said whatever has Focus, gives the hub in one line ("hub
connected, 6 agents, 2 blocked."); "hub details" (or "hub info", "details of the hub") adds how long it has been
connected (or the last failure, while it is offline), herdr's state, whether
the Claude Code hooks are installed, and how many Turns wait to upload. With
the hub offline, the phone answers them alone.

**Claude Code hooks.** When `init` finds Claude Code, it offers hooks that
tell the hub each session's state the moment it changes. It prints exactly
what it would add to `~/.claude/settings.json` and writes it only if you say
yes (`--hooks` says yes up front, `--no-hooks` skips the question, and
`--claude-settings FILE` picks another settings file, such as a project's
`.claude/settings.json`). Everything else in the file stays as it was, and
the file before is kept as `settings.json.stsloop-hub.bak`.
`stsloop-hub init --remove-hooks` takes the hooks out again. Each hook posts
the event to the hub on `127.0.0.1:7718` with `curl` (`--hooks-port` changes
the port), and always succeeds silently, so it never blocks or slows Claude
Code, hub running or not. The hub never answers a hook with a decision.

A session in a herdr pane is the same Bucket as its herdr agent; the hooks
make its state precise, and "status" says why it is blocked: "blocked on a
question" or "blocked on a permission prompt". A session outside herdr is
its own Bucket, named from its project directory, with "status", "what does
it say" (a summary of what its hooks reported: what it asks, the tool it
wants, its last message) and blocked alerts; "send" there says sending needs
herdr, since only herdr can type into the agent. The hub forgets a session
when it ends or its Claude Code process goes, and hears of one again at its
next event after a hub restart.

Summaries come from `claude -p` (with Claude Code's own login, the `haiku`
model, and every tool off) unless `hub.json` names another backend:

```json
"summariser": {"backend": "ollama", "model": "llama3.2", "url": "http://127.0.0.1:11434", "timeout_s": 20}
```

`backend` is `claude`, `pi` or `ollama`; `model`, `command` (the binary, when
it is not on the PATH), `url` (Ollama) and `timeout_s` (1–22, default 20) are
optional. Obvious secrets (keys, tokens, private keys, `.env` values) are
redacted before the screen reaches the model and again before the summary
is spoken. With a hosted model, the redacted screen leaves this machine. When
the model fails or takes too long, the loop says the agent's state and the
screen's last line instead.
While the hub is online during a Session, the phone also uploads its Corpus to
it, new and backlog Turns alike, in the background. `serve` stores them in
`~/.local/share/stsloop-hub/corpus` (`$STSLOOP_HUB_HOME/corpus` when that is
set, or `--corpus DIR`), laid out as the phone writes them, so
`uv run scripts/corpus_load.py <that directory>` reads it
([`docs/corpus-sync.md`](./docs/corpus-sync.md)).

### The settings screen

**Settings** on the main screen sets the trailing silence and the focus
timeout, pairs and forgets the hub, and lists every Bucket (the capture
Buckets, then the hub's cached ones) with its aliases. Every change applies
from the next Session. The screen writes the same files adb can, described
below: `files/timings.json`, `files/buckets.json`, and
`files/hub/aliases.json` for hub Buckets. Each save changes only what was
edited and keeps the rest of the file as it was, so values pushed over adb
survive. A file a Session cannot use as a whole (not strict JSON, say) is
never overwritten: the save is refused until it is fixed or deleted over
adb. Trailing silence takes 0.3 to 10 s on the screen; the file still
takes anything it allows. An alias is refused when another Bucket is already
heard by it, since a Session would drop it from both.

### The name-recognisability check

The recogniser can't hear every word, so the phone checks each phrase it
listens for: every Bucket name and alias, and every command phrase. The system
TTS voice speaks the phrase into a file, never aloud, and the phrase goes
through the Session's own recogniser. Each phrase is spoken in up to two
installed en-US voices, at 0.8×, 1× and 1.25× speed. It counts as heard only
if every variant comes back as the phrase, or as another phrase of the same
Bucket ("worklog" heard as "work log" still focuses work). The check runs when the app or the
settings screen opens, after pairing, after an alias edit, and when a Session
ends, which picks up Descriptors the hub sent during it. It checks only the
phrases it has no result for yet on the installed model. It never runs during
a Session, and it stops, keeping the phrases it has finished, once a Session
starts.

After a pairing fetches the hub's Buckets, the phone checks their names first
and then says "ready, N agents." Each Bucket on the settings screen shows its
result, and so does the list of commands. A Bucket phrase that isn't reliably
heard is announced once, at the start of the next Session ("hard to hear:
stsloop. try other aliases in settings."), and not again unless a new model
can't hear it either. Results and announcements are kept in
`files/recognisability.json` by phrase and model, and logged under
`stsloop.NameCheck`. Deleting the file checks and announces everything again.

The messages are specified in [`protocol/`](./protocol/README.md).

```sh
uv run scripts/validate_protocol.py                          # every fixture against its schema
cd hub && uv run python -m unittest discover -s tests -t tests   # the hub's tests
```

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
   Silero VAD into `audio/src/debug/assets/`, so only debug builds bundle
   them (a checkout fetched before #50 has them in `audio/src/main/assets/`;
   the script moves them). Into the test assets it fetches
   the int8 `chunk-16-left-128` files of
   `sherpa-onnx-streaming-zipformer-en-2023-06-26` (about 70 MB), the model
   shipped before #30, and generates its `bpe.vocab` from `bpe.model` with
   `scripts/export_bpe_vocab.py` (run through `uv`). Every file is checked
   against a pinned SHA-256. The debug APK is about 670 MB.

The `.so` files and models are git-ignored, so the repo stays small. CI builds
without them: it checks that everything compiles and the JVM tests pass, and
its APK has no speech. The on-device test needs both scripts run first:
`mise exec -- ./gradlew :audio:connectedDebugAndroidTest`. The real
first-run download of the shipped models (465 MB) is an opt-in device test:
add `-Pandroid.testInstrumentationRunnerArguments.fullDownload=true`.

**Speech models in a release build.** A release APK bundles no models
(`checkNoReleaseModels` fails the build if any are where it would pick them
up). On first run the app downloads them from the upstream sherpa-onnx
releases on GitHub: the STT tarball (464 MB) and Silero VAD. It checks each
download and each extracted file against the pins in `core`'s
`SpeechManifest`, which `scripts/fetch-models.sh` shares, and installs them in
the app's `no_backup/models` (kept out of Auto Backup). It starts by itself on
Wi-Fi; on mobile data the app offers a "Download now over mobile data"
button. A dropped download resumes from where it stopped. Until the models
are in place, Start says why it cannot run, in the app and on the
notification. Logs are under `stsloop.Models`. A debug build with bundled
models uses those and downloads nothing.

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
`{"buckets": []}`, turns Declarations off. `inbox` is reserved for the Inbox
(see "Focus" below): a Bucket by that name, or an alias "inbox", is dropped and
logged.

A hub Bucket's aliases come from its Provider. Aliases set for it on the
settings screen replace them, on the phone only, and are kept by Descriptor
`id` in `files/hub/aliases.json`. Forgetting the hub keeps them:

```json
{ "aliases": { "herdr:stsloop": ["loop agent"] } }
```

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
changed without a rebuild. `trailing_silence_ms` and `focus_timeout_ms` are
also on the settings screen. The file is optional, and so is every key in it: a
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
  "release_threshold": 0.35,
  "focus_timeout_ms": 120000
}
```

`speech_threshold` is the Silero probability that starts an utterance;
once one is being captured, `release_threshold` is enough to keep it going.
`pre_roll_ms` of audio before the first speech window is kept in the Turn.
`guard_ms` is how long the mic stays closed after an echo ends (see "The
spoken echo" below); 0 reopens it as soon as the engine reports the end.
`focus_timeout_ms` (10 s to 1 h) is how long Focus stays on a Bucket with no
speech before it returns to the Inbox (see "Focus" below).

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
obeyed. "Scratch that" and "repeat" act on the last Turn the loop read back
to you in this Session:

- **"Scratch that"** (or "discard that") tombstones it, and the loop says
  "dropped." (or "nothing to drop."). The dropped Turn stays in the Corpus,
  audio and all; the command's `tombstones` names the dropped Turn's
  directory, and the transcript list strikes the dropped Turn through.
- **"Repeat"** (or "say that again") reads its transcript back again.
- **"Where am I"** (or "what bucket", "which bucket") says the Bucket that
  has Focus, "inbox." in the Inbox.
- **"Stop listening"** (or "end session") says "stopped listening." and ends
  the Session, as the Stop button does.

The rules in full are in `docs/phase0.md`, "Commands".

### Focus

A Bucket's name or alias said on its own ("errands") gives it Focus, and the
loop says its name ("errands."). Every Turn after that which is not a command
is filed in that Bucket as a Note, with `bucket_source: "focus"`, so
"errands", a pause, "order roofing screws" lands in errands. A Declaration
("work, fix the build") still files its own Turn and leaves Focus where it
was. "Inbox" on its own returns Focus to the Inbox, where Turns are
unlabelled as before. After `focus_timeout_ms` (2 minutes) with no speech,
Focus returns to the Inbox and the loop says "back to inbox.". Every Session
starts in the Inbox.

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
