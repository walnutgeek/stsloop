# Speech stack verification: Android SpeechRecognizer vs. sherpa-onnx

Research date: 2026-10-06.
Sources restricted to: `developer.android.com`, `source.android.com`,
AOSP via `android.googlesource.com`, `developers.google.com/ml-kit`,
the `k2-fsa/sherpa-onnx` GitHub repo, and `k2-fsa.github.io/sherpa/onnx/`.

Evidence is tagged throughout:

- **[DOC-GUARANTEED]** — stated in official docs as a guarantee.
- **[DOC-IMPL-DEP]** — documented, but the docs explicitly say behaviour depends on the implementation.
- **[SOURCE]** — observable in AOSP / sherpa-onnx source, but not promised by any doc.
- **[UNVERIFIED]** — could not be confirmed from a primary source.

AOSP line numbers refer to `refs/heads/main` as fetched on the research date.

---

## Verdict

**CLAIM 1 — "`SpeechRecognizer` owns the mic and cannot cleanly be fed caller-owned PCM" — HELD UP WITH CAVEATS, and one sub-claim was WRONG.**

The core of the claim is correct and well-supported. `RecognitionService` is architecturally
built so that *the recognizer service process* opens the microphone, attributed to the caller
(AOSP ships a code sample doing exactly that in the `onStartListening` javadoc). The caveat is
that a caller-owned-PCM path **does exist and is public API since API 33**:
`RecognizerIntent.EXTRA_AUDIO_SOURCE` takes a `ParcelFileDescriptor`. But its javadoc states in
the same breath that the recognizer may not support it, there is **no API to query whether a
given recognizer honours it**, and whether Google's on-device recognizer honours it is
**[UNVERIFIED]** — that recognizer is not AOSP code. So "awkward or version-dependent" is the
right characterisation; "impossible" would be wrong.

Two corrections to the brief:

1. Sub-question 3 ("is there ANY supported custom-vocabulary / biasing API?") — the expected
   answer "no" is **wrong**. `RecognizerIntent.EXTRA_BIASING_STRINGS` (API 33) is public API and
   is exactly a contextual-biasing word list. It is `[DOC-IMPL-DEP]` and untestable for support,
   but it exists. Separately, the brief's premise that no newer GenAI speech surface exists is
   also wrong: ML Kit now ships a **GenAI Speech Recognition API (alpha)** that accepts a
   `ParcelFileDescriptor` audio source — though it has no biasing API.
2. The concurrency question (sub-question 2) resolves **against** running `SpeechRecognizer`
   alongside your own `AudioRecord`: "Two ordinary apps can never capture audio at the same
   time", and the loser gets **silence, not an error**.

**CLAIM 2 — "sherpa-onnx accepts caller-owned PCM and supports hotword biasing, including per-utterance" — HELD UP, with one sharp and important caveat.**

Per-stream hotwords are real, are in the Kotlin API, and need no model reload:
`fun createStream(hotwords: String = ""): OnlineStream`. Caller-owned PCM is the normal input
path. But hotwords are **transducer-only** and require `decoding_method = "modified_beam_search"`,
and the failure mode for calling them on a non-transducer model is `_Exit(-1)` — a hard process
kill, not an exception. This constrains the model choice absolutely: **a CTC model (including
Zipformer2-CTC) is off the table if Refinement matters.**

Recommended model, on the evidence: **`sherpa-onnx-streaming-zipformer-en-2023-06-26`, int8,
`chunk-16-left-128`** (≈70 MB on disk), with `decodingMethod = "modified_beam_search"`,
`modelingUnit = "bpe"`, `bpeVocab = <bpe.vocab>`. It is the only streaming English transducer in
the project that ships the BPE model hotwords require. No published latency, RTF or RAM figure
exists for Pixel-class hardware for any sherpa-onnx streaming model, and no published RTF figure
exists for `modified_beam_search` at all — those numbers must be measured on-device.

---

## CLAIM 1 — Android `SpeechRecognizer` and microphone exclusivity

### 1.1 Do the `EXTRA_AUDIO_SOURCE` extras exist? From what API level? Are they honoured?

**Yes, all four exist, and all four are public API added in API level 33 (Android 13).**
Confirmed in the public API surface file and in the rendered reference ("Added in API level 33"):

- [`core/api/current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt) lists `EXTRA_AUDIO_SOURCE`, `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`, `EXTRA_AUDIO_SOURCE_ENCODING`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`, `EXTRA_BIASING_STRINGS`, `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT`, `EXTRA_SEGMENTED_SESSION` as `field public static final String`.
- [`RecognizerIntent`](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE) — "Added in API level 33".

Defaults, from [`RecognizerIntent.java#168-188`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#168): channel count default **1**, encoding default **`AudioFormat.ENCODING_PCM_16BIT`**, sampling rate default **16000**. That matches a 16 kHz mono int16 tee exactly. **[DOC-GUARANTEED]** (as defaults, not as support).

The javadoc of `EXTRA_AUDIO_SOURCE` itself ([`RecognizerIntent.java#148-166`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#148)) is the load-bearing text — note the second sentence:

> Optional `android.os.ParcelFileDescriptor` pointing to an already opened audio
> source for the recognizer to use. The caller of the recognizer is responsible for closing
> the audio. **If this extra is not set or the recognizer does not support this feature, the
> recognizer will open the mic for audio and close it when the recognition is finished.**
>
> Along with this extra, please send `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`,
> `EXTRA_AUDIO_SOURCE_ENCODING`, and `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`
> extras, otherwise the default values of these extras will be used.
>
> Additionally, `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` may have no effect when this
> extra is set.
>
> This can also be used as the string value for `EXTRA_SEGMENTED_SESSION` to
> enable segmented session mode. The audio must be passed in using this extra. The
> recognition session will end when and only when the audio is closed.

So: **support is optional for recognizer services, by design.** **[DOC-IMPL-DEP]**

**Is it honoured by the on-device recognizer? [UNVERIFIED], and structurally unverifiable from AOSP.**
`createOnDeviceSpeechRecognizer` does not resolve to AOSP code. It resolves a component name out
of a device config string ([`SpeechRecognizer.java#311-316`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/SpeechRecognizer.java#311)):

```java
public static boolean isOnDeviceRecognitionAvailable(@NonNull Context context) {
    ComponentName componentName =
            ComponentName.unflattenFromString(
                    context.getString(R.string.config_defaultOnDeviceSpeechRecognitionService));
    return componentName != null;
}
```

On a Pixel that component is a Google-signed, closed-source app. AOSP ships no
`RecognitionService` implementation, so no primary source can establish whether
`EXTRA_AUDIO_SOURCE` is honoured there, on any given Android version, for any given device.

**And there is no way to ask.** `checkRecognitionSupport` returns a
[`RecognitionSupport`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionSupport.java)
object whose entire surface is **language lists** — `getInstalledOnDeviceLanguages()`,
`getPendingOnDeviceLanguages()`, `getSupportedOnDeviceLanguages()`, `getOnlineLanguages()`.
There is **no per-extra capability query**. **[SOURCE]** So an app cannot feature-detect
`EXTRA_AUDIO_SOURCE` or `EXTRA_BIASING_STRINGS`; it can only try it and compare results, and the
documented fallback is silent (the recognizer just opens the mic instead).

**What the framework *does* do with the extra** — this is the most informative find.
In [`RecognitionService.java#143-156`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionService.java#143):

```java
boolean preflightPermissionCheckPassed =
        intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
                || checkPermissionForPreflightNotHardDenied(attributionSource);
```

The framework treats the mere *presence* of `EXTRA_AUDIO_SOURCE` as grounds to skip the
microphone **preflight** check — i.e. the framework itself understands "caller supplied the
audio, the service won't open the mic". **[SOURCE]** But note the full check still runs
([`#156-157`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionService.java#156)):
`checkPermissionAndStartDataDelivery` calls
`PermissionChecker.checkPermissionAndStartDataDelivery(..., Manifest.permission.RECORD_AUDIO, ...)`
([`#854-869`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionService.java#854)),
so **`RECORD_AUDIO` is still required even when you supply your own audio**, and a denial yields
`ERROR_INSUFFICIENT_PERMISSIONS`. **[SOURCE]**

**The contract tells services to own the mic.** The `onStartListening` javadoc
([`RecognitionService.java#349-380`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionService.java#349))
is unambiguous about the intended architecture, and ships sample code:

> If you are recognizing speech from the microphone, in this callback you
> should create an attribution context for the caller such that when you access
> the mic the caller would be properly blamed (and their permission checked in
> the process) for accessing the microphone and that you served as a proxy for
> this sensitive data (and your permissions would be checked in the process).
> **You should also open the mic in this callback via the attribution context
> and close the mic before returning the recognized result.**
>
> ```
> Context attributionContext = context.createContext(new ContextParams.Builder()
>     .setNextAttributionSource(callback.getCallingAttributionSource())
>     .build());
>
> AudioRecord recorder = AudioRecord.Builder()
>     .setContext(attributionContext);
>     . . .
>    .build();
>
> recorder.startRecording()
> ```

This is the heart of the claim, and it is `[DOC-GUARANTEED]` as *intent*: the `AudioRecord` lives
in the **recognizer's process**, merely *attributed* to the caller. PCM injection is the
exception path, not the main path.

Two further relevant facts:

- `EXTRA_AUDIO_INJECT_SOURCE` (a URI-based predecessor) is **deprecated** in favour of `EXTRA_AUDIO_SOURCE` ([`RecognizerIntent.java#258-268`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#258)) and also carries "Depending on the recognizer implementation, this value may have no effect."
- `EXTRA_SEGMENTED_SESSION` ([`#563`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#563)) is the only documented route to a long session: with `EXTRA_AUDIO_SOURCE` as its value, "The recognition session will end when and only when the audio is closed", results arriving via `RecognitionListener#onSegmentResults(Bundle)`. It too is **[DOC-IMPL-DEP]**: "Depending on the recognizer implementation, this value may have no effect."

### 1.2 Can an app hold its own `AudioRecord` concurrently with an active `SpeechRecognizer`?

**Effectively no, for an ordinary app — and the failure is silent.** **[DOC-GUARANTEED]**

The authoritative page is
[Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input). Its
rules, as published:

- "Privileged apps have higher priority than ordinary apps."
- "Apps with visible foreground UIs have higher priority than background apps."
- "Apps capturing audio from a privacy-sensitive source have higher priority than apps that are not."
- **"Two ordinary apps can never capture audio at the same time."**
- "In some situations, a privileged app can share audio input with another app."
- "If two background apps of same priority are capturing audio, the last one started has higher priority."

And the critical consequence:

> **If a new app acquires the audio input, the previously capturing app continues to run, but receives silence.**

The platform-side description confirms the mechanism rather than an error return
([source.android.com — Concurrent capture](https://source.android.com/docs/core/audio/concurrent)):

> The concurrency policy is implemented by silencing its captured audio rather than by preventing
> an application from starting capturing.

Why this bites here: per §1.1, the recognizer's `AudioRecord` is created **in the recognizer
service's process** (a different app/UID — on a Pixel, a preinstalled Google app, i.e. plausibly
"privileged"). So your app's `AudioRecord` plus an active `SpeechRecognizer` is a two-app
contention, not an intra-app one. Whichever side loses gets **silence**, with no exception thrown.
That is the worst possible failure mode for a corpus writer that must be byte-identical to what
produced the transcript: it would produce a silent recording, or a transcript of silence, with no
error.

Detection, not prevention, is what the platform offers: register an
`AudioManager.AudioRecordingCallback` and check
[`AudioRecordingConfiguration.isClientSilenced()`](https://developer.android.com/reference/android/media/AudioRecordingConfiguration#isClientSilenced()),
which "returns true if the audio returned to the client is currently being silenced due to the
capture policy".

Also note [`AudioRecord.Builder.setPrivacySensitive(boolean)`](https://developer.android.com/reference/android/media/AudioRecord.Builder#setPrivacySensitive(boolean)) (API 30+): marking your
capture privacy-sensitive means "any concurrent capture is not permitted" — which would
*deliberately* lock out a recognizer service.

**Android 14/15/16-specific concurrency changes: [UNVERIFIED].** I found no Android 14, 15 or 16
behaviour-change entry altering these rules; the `sharing-audio-input` page documents the
Android 10 priority scheme and the Android 11 `setPrivacySensitive` addition and nothing later.
Absence of a found entry is not proof of absence.

One AOSP detail worth recording: `RecognitionService.getMaxConcurrentSessionsCount()`
([`#539-544`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognitionService.java#539))
has javadoc "The default value is 1, meaning concurrency should be enabled by overriding this
method." So even multiple recognition sessions are single by default. **[SOURCE]**

### 1.3 Is there any supported custom-vocabulary / biasing / hotword API?

**Yes — the brief's expected "no" is wrong, though the API is weak.**

`RecognizerIntent.EXTRA_BIASING_STRINGS`, public, API 33
([`RecognizerIntent.java#199-203`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#199),
[reference](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_BIASING_STRINGS)):

> Optional list of strings, towards which the recognizer should bias the recognition results.
> These are separate from the device context.

Also `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` ([`#190-197`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java#190)):

> Optional boolean to enable biasing towards device context. The recognizer will use the
> device context to tune the recognition results.
>
> Depending on the recognizer implementation, this value may have no effect.

Caveats that matter a great deal:

- `EXTRA_BIASING_STRINGS`'s javadoc carries **no documented scoring, no size limit, and no
  stated semantics** beyond "should bias". **[DOC-IMPL-DEP]** by API convention (the whole extras
  family is), and there is no capability query (§1.1).
- Per the `EXTRA_AUDIO_SOURCE` javadoc, `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` "may have no effect
  when this extra is set" — i.e. biasing and PCM injection interact. The javadoc says nothing
  about whether `EXTRA_BIASING_STRINGS` survives PCM injection. **[UNVERIFIED]**
- Whether Google's on-device recognizer implements `EXTRA_BIASING_STRINGS` at all, and with what
  phrase limit: **[UNVERIFIED]** (closed source, no query API).

**No hotword/wake-word API on `SpeechRecognizer`.** Wake-word on Android is a separate,
privileged surface (`VoiceInteractionService` / `AlwaysOnHotwordDetector`), and the
`CAPTURE_AUDIO_HOTWORD` permission is noted on the sharing-audio-input page as a privileged-app
affordance. Nothing hotword-like exists on `SpeechRecognizer`. **[SOURCE/DOC]**

**Newer surface: ML Kit GenAI Speech Recognition (alpha).** This is a real and relevant find.
[developers.google.com/ml-kit/genai/speech-recognition/android](https://developers.google.com/ml-kit/genai/speech-recognition/android):

- Dependency `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`.
- **It accepts caller-supplied audio**: `AudioSource.fromMic()` *or*
  `AudioSource.fromPfd(parcelFileDescriptor)`, with required format "Raw, headerless 16-bit PCM",
  "Mono (single channel)", "16 kHz". That is exactly the tee format.
- Streaming results: [`SpeechRecognizer.startRecognition(SpeechRecognizerRequest)`](https://developers.google.com/android/reference/com/google/mlkit/genai/speechrecognition/SpeechRecognizer)
  returns `Flow<SpeechRecognizerResponse>` with `PartialTextResponse` / `FinalTextResponse`.
- **No custom-vocabulary, biasing, or hotword parameter is documented** on
  `SpeechRecognizerOptions` or `SpeechRecognizerRequest` (only `Mode` — `MODE_ADVANCED` vs basic —
  and locale). **[DOC]**
- Status and reach: "This API is offered in alpha, and is not subject to any SLA or deprecation
  policy."; "This API is not supported on devices with an unlocked bootloader."; basic mode
  "API level 31 and higher", advanced (GenAI) mode limited to Pixel 10 / Pixel 11.

So: ML Kit GenAI solves the PCM-injection problem cleanly (via PFD) but **does not** solve the
biasing problem, and is alpha + bootloader-locked + top-tier-Pixel-gated for its good mode.

### 1.4 Documented statement that `SpeechRecognizer` is not for continuous recognition

**Yes.** This is the class-level javadoc, and it is the authoritative wording
([`SpeechRecognizer.java#51-53`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/SpeechRecognizer.java#51),
also rendered on the
[reference page](https://developer.android.com/reference/android/speech/SpeechRecognizer)):

> The implementation of this API is likely to stream audio to remote servers to perform speech
> recognition. As such this API is not intended to be used for continuous recognition, which would
> consume a significant amount of battery and bandwidth.

Two adjacent constraints from the same javadoc block
([`#41-57`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/SpeechRecognizer.java#41)):

> This class's methods must be invoked only from the main application thread.

> **Important:** the caller MUST invoke `destroy()` on a SpeechRecognizer object when it is no
> longer needed.

> Please note that the application must have `android.Manifest.permission#RECORD_AUDIO`
> permission to use this class.

Note the stated *reason* for the warning is network streaming — which is weaker when
`createOnDeviceSpeechRecognizer()` is used. The javadoc has **not** been narrowed to exclude the
on-device recognizer, so the sentence stands as written for the whole class, but reading it as a
hard prohibition on on-device continuous use over-reads it. The mechanism that actually blocks an
always-on loop is the concurrency rule in §1.2 and the absence of a reliable long-session
contract (§1.1, `EXTRA_SEGMENTED_SESSION` being `[DOC-IMPL-DEP]`).

### 1.5 Android 14+ foreground-service-type requirements for microphone use

**[DOC-GUARANTEED]**, from
[Foreground service types](https://developer.android.com/develop/background-work/services/fg-service-types#microphone):

> Beginning with Android 14 (API level 34): you must declare an appropriate service type for each
> foreground service. That means you must declare the service type in your app manifest, and also
> request the appropriate foreground service permission for that type (in addition to requesting
> the `FOREGROUND_SERVICE` permission).

For microphone capture specifically:

- Manifest `android:foregroundServiceType="microphone"`.
- Manifest permission [`FOREGROUND_SERVICE_MICROPHONE`](https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE_MICROPHONE) (plus `FOREGROUND_SERVICE`).
- Pass [`ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`](https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_MICROPHONE) to `startForeground()`.
- Runtime prerequisite: "Request and be granted the `RECORD_AUDIO` runtime permission."
- Use case: "Continue microphone capture from the background, such as voice recorders or communication apps."

And the restriction that directly shapes an always-on loop:

> The `RECORD_AUDIO` runtime permission is subject to while-in-use restrictions. For this reason,
> you cannot create a `microphone` foreground service while your app is in the background and you
> cannot launch a `microphone` foreground service from a `BOOT_COMPLETED` receiver, with a few
> exceptions.

**Design consequence:** the half-duplex loop must be *started from the foreground* (a visible
activity / user gesture). It can then continue in the background, but it cannot auto-start on
boot, and if it is ever fully stopped while backgrounded it cannot restart itself. See
[Exemptions from while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions-exemptions).

---

## CLAIM 2 — sherpa-onnx hotword / contextual biasing and buffer input

### 2.1 Does sherpa-onnx support hotwords, and which architectures?

**Yes, and the architecture answer is narrow and unambiguous: transducer only.**

The docs state it flatly
([Hotwords (contextual biasing)](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html)):

> **Only transducer models support hotwords in sherpa-onnx.** That is, only models from Offline
> transducer models and Online transducer models support hotwords.

> You have to change the decoding method to `modified_beam_search` to use hotwords. The default
> decoding method `greedy_search` does not support hotwords.

The source corroborates this exactly, and reveals the failure mode. The base class provides a
default `CreateStream(hotwords)` that **terminates the process**
([`sherpa-onnx/csrc/online-recognizer-impl.h#38-41`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-impl.h#L38)):

```cpp
  virtual std::unique_ptr<OnlineStream> CreateStream(
      const std::string &hotwords) const {
    SHERPA_ONNX_LOGE("Only transducer models support contextual biasing.");
    SHERPA_ONNX_EXIT(-1);
  }
```

The offline base class is identical
([`offline-recognizer-impl.h#36-40`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-recognizer-impl.h#L36)).
And `SHERPA_ONNX_EXIT` is not an exception
([`macros.h#54-59`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/macros.h#L54)):

```cpp
#define SHERPA_ONNX_EXIT(code) \
  do {                         \
    fflush(stdout);            \
    fflush(stderr);            \
    _Exit(code);               \
  } while (0)
```

`_Exit(-1)` from JNI is an **immediate, uncatchable process kill** — no Java exception, no
`try/catch`, no crash handler. On Android that is an app death. **[SOURCE]** This is a safety
finding, not just a capability finding: the code must never pass a non-empty hotwords string to a
non-transducer recognizer.

Which impls actually override it (checked file by file, `master`):

| Recognizer impl | Streaming? | Overrides `CreateStream(hotwords)`? |
|---|---|---|
| [`online-recognizer-transducer-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-impl.h#L209) (Zipformer/Zipformer2 **transducer**) | yes | **YES** (`#209`) |
| [`online-recognizer-transducer-nemo-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-nemo-impl.h#L148) (NeMo transducer) | yes | **YES** (`#148`) |
| [`online-recognizer-ctc-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-ctc-impl.h) (**Zipformer2-CTC**, NeMo-CTC, WeNet-CTC) | yes | **NO** — only `CreateStream()` (`#107`) |
| [`online-recognizer-paraformer-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-paraformer-impl.h) (streaming Paraformer) | yes | **NO** (`#149`) |
| [`online-recognizer-transducer-nemo-parakeet-unified-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-nemo-parakeet-unified-impl.h) | yes | **NO** (`#71`) |
| [`offline-recognizer-transducer-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h#L163) | no | **YES** (`#163`) |
| `offline-recognizer-whisper-impl.h` | no | **NO** |
| `offline-recognizer-moonshine-impl.h`, `-moonshine-v2-impl.h` | no | **NO** |
| `offline-recognizer-ctc-impl.h`, `-paraformer-impl.h`, `-sense-voice-impl.h` | no | **NO** |

**So, directly answering the brief: transducer only. Not CTC. Not Zipformer2-CTC. Not Whisper.
Not Moonshine. Not Paraformer.** **[DOC-GUARANTEED + SOURCE, mutually confirming]**

Mechanism and configuration surface:

- `hotwords_file` — "Path to a hotwords file", one word/phrase per line ([`c-api.h#380-381`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L380); registered with that help text in [`online-recognizer.cc#110-111`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer.cc#L110)).
- `hotwords_score` — "Bonus score added to each hotword token during decoding" ([`c-api.h#383-384`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L383)); docs: "The boosting score for each matched token". Kotlin default `1.5f`.
- `hotwords_buf` / `hotwords_buf_size` — "Optional in-memory hotwords text used instead of `hotwords_file`" ([`c-api.h#395-398`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L395)). Useful on Android: no file needed.
- Implemented as a **`ContextGraph`** consumed by the modified-beam-search decoder ([`online-recognizer-transducer-impl.h#228-233`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-impl.h#L228)).
- `modeling_unit` — "cjkchar", "bpe", or "cjkchar+bpe" ([`c-api.h#259-267`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L259)). **For English set `modeling_unit = "bpe"`.**
- `bpe_vocab` — "Path to the BPE vocabulary file when BPE is used" ([`c-api.h#268-269`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L268)); required when `modeling_unit` includes bpe. The BPE encoder is only constructed when `decoding_method == "modified_beam_search"` ([`online-recognizer-transducer-impl.h#110-114`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-impl.h#L110)).

The decoding-method requirement is enforced in config validation
([`online-recognizer.cc#149-155`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer.cc#L149)):

```cpp
  if (!hotwords_file.empty() && decoding_method != "modified_beam_search") {
    SHERPA_ONNX_LOGE(
        "Please use --decoding-method=modified_beam_search if you"
        " provide --hotwords-file. Given --decoding-method=%s",
        decoding_method.c_str());
    return false;
  }
```

Per-entry score syntax, from the docs: a trailing `:<score>` on a line
(e.g. `语音识别 :3.5`), with the caveat quoted verbatim:

> The specific score MUST BE the last item of each hotword (i.e You shouldn't break the hotword
> into two parts by the score).

**Aside — a *different*, prompt-style hotwords mechanism exists on some new offline LLM-ASR
models**, unrelated to the context graph: `SherpaOnnxOfflineQwen3ASRModelConfig.hotwords`
("Optional comma-separated hotwords (UTF-8, ASCII ','), e.g. `"foo,bar,baz"`",
[`c-api.h#1046-1048`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L1046))
and `SherpaOnnxOfflineFunASRNanoModelConfig.hotwords`
([`c-api.h#1022-1023`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L1022)).
These are large offline models, not streaming, and are not the recommended path here — but they
are a possible future Refinement engine. Their on-device Android viability is **[UNVERIFIED]**.

### 2.2 Can hotwords be changed at runtime, per utterance, without reloading the model?

**Yes. This is a first-class per-stream API, and it is exposed in Kotlin.** **[SOURCE]**

C API (streaming), [`c-api.h#521-537`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L521):

```c
/**
 * @brief Create a streaming ASR state object with per-stream hotwords.
 *
 * @param recognizer A pointer returned by SherpaOnnxCreateOnlineRecognizer().
 * @param hotwords Hotwords text to associate with the stream.
 * ...
 *     SherpaOnnxCreateOnlineStreamWithHotwords(recognizer, "▁HELLO ▁WORLD");
 */
SHERPA_ONNX_API const SherpaOnnxOnlineStream *
SherpaOnnxCreateOnlineStreamWithHotwords(
    const SherpaOnnxOnlineRecognizer *recognizer, const char *hotwords);
```

Offline equivalent, [`c-api.h#1329-1345`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L1329):

```c
SHERPA_ONNX_API const SherpaOnnxOfflineStream *
SherpaOnnxCreateOfflineStreamWithHotwords(
    const SherpaOnnxOfflineRecognizer *recognizer, const char *hotwords);
```

**Kotlin API** — the signature that matters for this app
([`sherpa-onnx/kotlin-api/OnlineRecognizer.kt#117-118`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt#L117)):

```kotlin
    fun createStream(hotwords: String = ""): OnlineStream {
        val p = createStream(ptr, hotwords)
```

backed by `private external fun createStream(ptr: Long, hotwords: String): Long`
([`#141`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt#L141)).
The recognizer (`ptr`) is unchanged — **no model reload.** One recognizer, N streams, each with
its own context graph. That is precisely the per-topic Refinement shape.

Three operational details from the implementation
([`online-recognizer-transducer-impl.h#209-235`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-recognizer-transducer-impl.h#L209)):

1. **Separator is `/`, not newline**, for the per-stream string:
   `auto hws = std::regex_replace(hotwords, std::regex("/"), "\n");` — so pass
   `"OCTOPUS/BYGRAMS/ZIPFORMER"`.
2. **Per-stream hotwords are unioned with the config-time defaults**, not replacing them:
   `current.insert(current.end(), hotwords_.begin(), hotwords_.end());`. If you want purely
   per-utterance vocabulary, leave `hotwordsFile`/`hotwordsBuf` empty at config time.
3. **Encoding failure is soft here** (unlike the architecture mismatch): it logs
   `"Encode hotwords failed, skipping, hotwords are : %s"` and proceeds. So malformed hotwords
   degrade silently rather than crashing — you will not get an error back in Kotlin. Validate
   your word lists yourself.

Also note `Kotlin OnlineRecognizerConfig` defaults
([`OnlineRecognizer.kt#52-77`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt#L52)):
`modelingUnit = ""`, `bpeVocab = ""`, `decodingMethod = "greedy_search"`, `hotwordsFile = ""`,
`hotwordsScore = 1.5f`. **All three of `decodingMethod`, `modelingUnit`, `bpeVocab` must be
changed from their defaults** for hotwords to work.

### 2.3 Does it accept raw PCM from the caller on Android?

**Yes — this is the normal and only input path. The single-`AudioRecord`-tee design is exactly how
the shipped Android examples work.** **[SOURCE]**

Kotlin streaming API
([`kotlin-api/OnlineStream.kt#L8-L9`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineStream.kt#L8-L9)):

```kotlin
fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
    acceptWaveform(ptr, samples, sampleRate)
```

- **`FloatArray` only.** There is no `ShortArray` overload in the Kotlin or Java API — the caller
  must do the int16 to float conversion.
- Value range **`[-1, 1]`**, documented in the C header the JNI wraps
  ([`c-api.h#L563`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L563)):
  "`@param samples Pointer to @p n samples in the range [-1, 1].`"
- `sampleRate` is **per call**; sherpa-onnx resamples internally if it differs from the feature
  config rate ([`c-api.h#~556`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L556)).
- **Your buffer is not mutated**: the JNI shim copies out and releases with `JNI_ABORT`
  ([`jni/online-stream.cc#L16-L25`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/jni/online-stream.cc#L16-L25)) — so the
  same `FloatArray` can safely be handed to the corpus writer. Good for a tee.
- Offline (Refinement) path is identical: `OfflineStream.acceptWaveform(samples: FloatArray, sampleRate: Int)`
  ([`OfflineStream.kt#L8-L9`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineStream.kt#L8-L9)).
- Java: `public void acceptWaveform(float[] samples, int sampleRate)`
  ([`OnlineStream.java#L26`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/OnlineStream.java#L26)).

The reference Android app `android/SherpaOnnx` owns `AudioRecord` itself and feeds the PCM in —
precisely the design under test
([`MainActivity.kt#L87-L94`](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt#L87-L94)):

```kotlin
private val audioSource = MediaRecorder.AudioSource.MIC
private val sampleRateInHz = 16000
private val channelConfig = AudioFormat.CHANNEL_IN_MONO
// Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
// since the AudioRecord.read(float[]) needs API level >= 23
// but we are targeting API level >= 21
private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
```

and the feed loop, 100 ms chunks with an explicit `/32768.0f`
([`#L169-L181`](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt#L169-L181)):

```kotlin
val interval = 0.1 // i.e., 100 ms
val bufferSize = (interval * sampleRateInHz).toInt() // in samples
val buffer = ShortArray(bufferSize)

while (isRecording) {
    val ret = audioRecord?.read(buffer, 0, buffer.size)
    if (ret != null && ret > 0) {
        val samples = FloatArray(ret) { buffer[it] / 32768.0f }
        stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
```

**Every Android example uses 16000 Hz, mono, `ENCODING_PCM_16BIT`.** Same in
[`SherpaOnnxVadAsr`](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt#L44-L50)
and
[`SherpaOnnxSimulateStreamingAsr`](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxSimulateStreamingAsr/app/src/main/java/com/k2fsa/sherpa/onnx/simulate/streaming/asr/screens/Home.kt#L120-L129).

**This is good news for byte-identical retention:** int16 is the *native* capture format, the
float conversion is a lossless-in-practice `/32768` scale applied downstream of the tee point, and
nothing in sherpa-onnx wants to own the mic. Retain the `ShortArray` chunks, derive the
`FloatArray` for ASR. The retained bytes are then exactly the bytes that produced the transcript.

### 2.4 Silero VAD on Android

**Yes, fully exposed in Kotlin.** **[SOURCE]**
[`kotlin-api/Vad.kt`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Vad.kt).

Config ([`#L6-L31`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Vad.kt#L6-L31)):

```kotlin
data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)
```

API ([`#L33-L79`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Vad.kt#L33-L79)):

```kotlin
class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(assetManager: AssetManager? = null, var config: VadModelConfig) {
    fun compute(samples: FloatArray): Float = compute(ptr, samples)
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)
    fun empty(): Boolean = empty(ptr)
    fun pop() = pop(ptr)
    fun front(): SpeechSegment { return front(ptr) }
    fun clear() = clear(ptr)
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)
    fun reset() = reset(ptr)
    fun flush() = flush(ptr)
```

Note `Vad.acceptWaveform` takes **no `sampleRate`** — the rate comes from
`VadModelConfig.sampleRate` (default 16000). Range again `[-1, 1]`
([`c-api.h#L2148`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h#L2148)).

Crucially for retention: `SpeechSegment` carries **`start: Int` (a sample offset) and `samples`**,
so a VAD-detected utterance can be mapped back to an exact byte range in the retained stream.

**Model file and size.** The download URL is named in a comment in `Vad.kt` itself
([`#L110-L128`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Vad.kt#L110-L128)):
`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`, "and put it
inside the assets/ directory", with `windowSize = 512`.

Sizes, from the [`asr-models` release asset listing](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
(independently published as 629 KB / 208 KB / 1.72 MB / 2.22 MB at
[silero-vad docs](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html)):

| asset | bytes | approx |
|---|---|---|
| `silero_vad.onnx` | 643,854 | **629 KB** |
| `silero_vad.int8.onnx` | 212,860 | 208 KB |
| `ten-vad.onnx` | 332,211 | 324 KB |
| `silero_vad_v4.onnx` | 1,807,522 | 1.72 MB |
| `silero_vad_v5.onnx` | 2,313,101 | 2.21 MB |

The docs note the k2-fsa-exported `silero_vad.onnx` **supports 16 kHz only**. Negligible footprint
either way.

Working Android VAD loop with 512-sample windows:
[`SherpaOnnxVadAsr/MainActivity.kt#L184-L209`](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt#L184-L209).
Caveat: that demo's default second-pass model is **Chinese** (`sherpa-onnx-paraformer-zh-2023-09-14`),
so an English build must pick a different `type`.

### 2.5 Recommended streaming English model and realistic numbers

**Recommendation: `sherpa-onnx-streaming-zipformer-en-2023-06-26`, int8, chunk-16-left-128.**

The recommendation is forced far more by the hotwords constraint than by speed. Given §2.1, the
model **must be a transducer**, which eliminates every CTC and Paraformer streaming option. Of the
remaining streaming English transducers, only one ships the file hotwords need.

| Model | arch | hotwords-capable? | ships `bpe.model`? | int8 on-disk (published) |
|---|---|---|---|---|
| **`sherpa-onnx-streaming-zipformer-en-2023-06-26`** | zipformer2 **transducer** | **yes** | **yes** (240K) | **≈70 MB** |
| `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | zipformer transducer | yes (arch) | **NO** | ≈42 MB |
| `sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06` | zipformer2 transducer | yes (arch) | undocumented | tarball 54.6 MB |
| `sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-*` | **CTC** | **no** | n/a | int8 99–103 MB |
| streaming Paraformer | Paraformer | **no** | n/a | — |
| `sherpa-onnx-lstm-en-2023-02-17` | LSTM transducer | yes (arch) | undocumented | — |

Published per-file sizes for the recommendation, from
[zipformer-transducer-models docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
([rst#L800-L815](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/online-transducer/zipformer-transducer-models.rst#L800)):

```
240K  bpe.model
1.3M  decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx
 68M  encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx
254K  joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx
5.0K  tokens.txt
```

So **≈70 MB on disk for the int8 set** (the 296 MB tarball figure is misleading — it bundles fp32
plus int8 plus two chunk configurations plus test wavs). Ship only the int8 `chunk-16-left-128`
files plus `tokens.txt` plus the derived `bpe.vocab` (see below).

**The `bpe.model` / `bpe.vocab` trap.** Confirmed from the
[hotwords docs](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html)
([rst#L276-L284](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/hotwords/index.rst#L276)):

> `bpe-vocab` — The bpe vocabulary generated by sentencepiece toolkit, it also can be exported
> from `bpe.model` (see `script/export_bpe_vocab.py` for details). This vocabulary is used to
> tokenize words/phrases into bpe units. It is only used when `modeling-unit` is `bpe` or `cjkchar+bpe`.
>
> **We need `bpe.vocab` rather than `bpe.model`**, because we don't introduce sentencepiece c++
> codebase into `sherpa-onnx` (which has a depandancy issue of protobuf), we implement a simple
> sentencepiece encoder and decoder which takes `bpe.vocab` as input.

The model tarball ships `bpe.model`; sherpa-onnx wants `bpe.vocab`. The converter is
[`scripts/export_bpe_vocab.py`](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/export_bpe_vocab.py)
(the docs' path `script/...` is a typo; `scripts/...` resolves, `script/...` 404s). **This is a
one-time offline build step** — run it on the desktop, ship `bpe.vocab` in `assets/`.

**Why not the 20M model.** Its published HuggingFace file listing
([`csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17))
contains **no `bpe.model` and no `bpe.vocab`** — only encoder/decoder/joiner, `tokens.txt`,
`export-onnx-en-20M.sh` and test wavs. Without a BPE vocabulary, English (`modeling_unit = "bpe"`)
hotwords cannot be encoded. You would have to source the BPE model from the upstream icefall repo
and verify it matches this model's token inventory. **[SOURCE]** Keep the 20M model as the
low-footprint fallback *if Refinement is dropped*, not as the primary.

**Published latency / RTF — read the caveat.** The only figures in the project are CLI console
transcripts in the docs
([code-zipformer/](https://github.com/k2-fsa/sherpa/tree/master/docs/source/onnx/pretrained_models/online-transducer/code-zipformer)):

| Model | precision | threads | Elapsed | RTF |
|---|---|---|---|---|
| `...-zipformer-en-2023-06-26` | fp32 | 2 | 0.51 s | 0.077 |
| `...-zipformer-en-2023-06-26` | **int8** | 2 | 0.41 s | **0.062** |
| `...-zipformer-en-20M-2023-02-17` | fp32 | 1 | 0.32 s | 0.049 |
| `...-zipformer-en-20M-2023-02-17` | int8 | 1 | 0.25 s | 0.038 |
| `...-zipformer-en-2023-02-21` | fp32 / int8 | — | 0.825 / 0.633 s | 0.125 / 0.096 |
| `sherpa-onnx-lstm-en-2023-02-17` | fp32 / int8 | — | 2.927 / 1.009 s | 0.442 / 0.152 |

**The hardware for these is not stated.** The transcripts leak only a developer macOS path
(`/Users/fangjun/open-source/sherpa-onnx/...`), `provider="cpu"`, `num_threads=2` (2023-06-26) or
`1` (20M). Treat them as desktop-CPU order-of-magnitude indicators, not phone numbers.

**There are NO published latency, RTF, or RAM figures for Pixel-class or any modern ARM phone, for
any streaming model.** This was searched for directly across the repo README and the whole
`docs/source/onnx` tree (`RTF`, `real-time factor`, `latency`, `benchmark`, `peak memory`, `RSS`,
`RAM`). The only *named* ARM figures are for **non-streaming** models on SBCs:

- Raspberry Pi 4 Model B, Whisper tiny.en (offline): RTF 0.685 / 0.559 / 0.526 at 1/2/3 threads — [docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/tiny.en.html)
- RK3588 Cortex-A76, Parakeet TDT 0.6B v2 (offline) — [rst#L183-L250](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/offline-transducer/nemo/parakeet-tdt-0.6b-v2.rst#L183)
- RK3588, SenseVoice int8 (offline) — [rst#L239](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/sense-voice/pretrained.rst#L239)
- `docs/source/onnx/tts/pretrained_models/rtf.rst` is **TTS only** (RPi 4).

**No RAM figure is published for any model.** The only resource guidance is qualitative: "If you
are using Raspberry Pi 4, this section is not so helpful for you since all models in sherpa-onnx
are able to run in real-time on it"
([small-online-models.rst](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/small-online-models.rst)).
**You must measure RTF, chunk latency and RSS on the target device yourself.** Note also that
`modified_beam_search` (mandatory for hotwords) is **slower than the `greedy_search` used for all
published RTF numbers**, so even the desktop figures above understate the configuration this app
will actually run. No published figure exists for `modified_beam_search` RTF. **[UNVERIFIED]**

Chunk latency is a *configuration*, not a measured number: the docs say only "The larger the chunk
size, the higher the accuracy" and "The larger the number, the lower the RTF"
([rst#L1467](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/online-transducer/zipformer-transducer-models.rst#L1467)).

**Moonshine: offline / chunk-based, and no hotwords.** **[SOURCE + DOC]** Every Moonshine
implementation in sherpa-onnx is under the **Offline** recognizer family, with no `Online`
counterpart anywhere in the tree:
[`offline-recognizer-moonshine-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-recognizer-moonshine-impl.h),
[`offline-recognizer-moonshine-v2-impl.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-recognizer-moonshine-v2-impl.h),
[`offline-moonshine-model-config.h`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-moonshine-model-config.h).
Kotlin exposes it only as `OfflineMoonshineModelConfig`, a field of `OfflineModelConfig`
([`OfflineRecognizer.kt#L126`](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt#L126)),
and the docs drive it with the `sherpa-onnx-offline` binary
([rst#L60](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/moonshine/models.rst#L60)).
Since it is not a transducer, it **does not support hotwords** (§2.1) — and calling
`createStream(hotwords)` on it would `_Exit(-1)`. Streaming-feel requires VAD-chunked offline
decoding, as `SherpaOnnxVadAsr` / `SherpaOnnxSimulateStreamingAsr` do.
Sizes: `moonshine-tiny-en-int8` ≈118 MB on disk
([rst#L36-L45](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/moonshine/models.rst#L36));
newer `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2` is 29.9 MB.

### 2.6 Licenses

- **sherpa-onnx itself: Apache License 2.0.** [LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE). The project states the split explicitly: "The code is licensed under Apache-2.0. **Please check the license of your selected model separately.**" ([rst#L258-L263](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/tauri/vad-asr-mic.rst#L258))
- **`silero_vad.onnx`: MIT.** Stated on the [VAD docs page](https://k2-fsa.github.io/sherpa/onnx/vad/index.html) ([rst#L8-L12](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/vad/index.rst#L8)): "`silero-vad` uses [MIT license](https://github.com/snakers4/silero-vad/blob/master/LICENSE) while `ten-vad` uses a modified version of Apache License 2.0. **Please read the license of the model before you use it.**" (So prefer Silero over TEN-VAD on licensing grounds.)
- **`sherpa-onnx-streaming-zipformer-en-2023-06-26`: not stated by the sherpa-onnx repo or docs; `apache-2.0` on the k2-fsa HuggingFace mirror.** The docs page carries no license statement and the published tarball listing contains no LICENSE file. The upstream source the docs link, [`Zengwei/icefall-asr-librispeech-streaming-zipformer-2023-05-17`](https://huggingface.co/Zengwei/icefall-asr-librispeech-streaming-zipformer-2023-05-17), has **no license tag and no model card**. The k2-fsa mirror [`csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) does declare `license: apache-2.0` in its card metadata — weaker evidence than a repo-stated license, but it is the redistributor's own declaration of the artifact actually being shipped. **Treat as Apache-2.0 with a confirm-before-commercial-ship flag.**
- `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`: upstream [`desh2608/...-streaming-small`](https://huggingface.co/desh2608/icefall-asr-librispeech-pruned-transducer-stateless7-streaming-small) **does** declare `license: apache-2.0`. Cleanest licensing of the candidates — but it lacks the BPE vocab (§2.5).
- `sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06`: **license not stated anywhere in the repo or docs**, and the model is entirely undocumented (release asset + a Kotlin `getModelConfig()` entry only). Kroko models are commonly dual/commercially licensed upstream. **Do not ship without resolving this.** **[UNVERIFIED]**
- Training data is LibriSpeech, mentioned only as "trained on the LibriSpeech corpus" with no license claim attached.

Where the project *does* know a model is restricted, it says so plainly (GigaAM "License_NC"
[rst#L121](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/offline-ctc/nemo/russian.rst#L121),
Reverb diarization "non-commercial license"
[rst#L181](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/speaker-diarization/models.rst#L181)).
So silence on the English streaming zipformers is silence, **not** an implied permissive grant.

**Build/packaging note:** sherpa-onnx publishes **no Maven/Gradle coordinate and no prebuilt AAR**.
The [Android docs](https://k2-fsa.github.io/sherpa/onnx/android/index.html) offer only "Pre-built
APKs" and "Build sherpa-onnx for Android" (install NDK, build the C++, generate APK). Integration
means building the native libraries with the NDK and vendoring them as `jniLibs`, plus copying the
`sherpa-onnx/kotlin-api/*.kt` sources. Budget for this. **[DOC]**

---

## Consequences for the design

**1. Keep sherpa-onnx for VAD + STT. The claim that motivated it holds.** One `AudioRecord`,
teed to Silero VAD + streaming ASR and to a corpus writer, is exactly what the shipped Android
examples do. int16 is the native capture format and the `/32768` float conversion happens
*downstream* of the tee, so byte-identical retention is natural rather than contrived. The JNI
layer does not mutate your buffer (`JNI_ABORT`), so one array can serve both consumers.

**2. The model choice is now forced, not a preference. Use
`sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8, chunk-16-left-128) and configure
`decodingMethod = "modified_beam_search"`, `modelingUnit = "bpe"`, `bpeVocab = <bpe.vocab>` from
day one.** Reasons, in priority order:

   - Hotwords are **transducer-only**. Any CTC model — including Zipformer2-CTC, which would
     otherwise be an attractive streaming option — permanently forecloses Refinement.
   - It is the **only** streaming English transducer in the project that ships a BPE model, so it
     is the only one where English hotwords work without sourcing extra files from upstream.
   - Setting `modified_beam_search` later is not a free switch: it is slower than the
     `greedy_search` all published RTF numbers used, so **benchmark in the final decoding
     configuration, not the default one.** Do this early; it is the main performance unknown.
   - Add the `bpe.vocab` export (`scripts/export_bpe_vocab.py`) to the build now. Discovering the
     `bpe.model` vs `bpe.vocab` mismatch late is a needless surprise.

**3. Treat `_Exit(-1)` as a hard safety constraint in the code, not a footnote.** Passing a
non-empty hotwords string to a non-transducer recognizer kills the app process uncatchably — no
Java exception, no crash report. Put a single chokepoint in front of stream creation that refuses
to pass hotwords unless the loaded model is a transducer configured for `modified_beam_search`.
Conversely, *malformed* hotwords fail **silently** (logged, skipped), so validate per-topic word
lists at authoring time; you will get no runtime signal. Also remember the per-stream separator is
`/`, not newline, and per-stream hotwords are **unioned with** config-time defaults — so leave
`hotwordsFile`/`hotwordsBuf` empty if you want purely per-topic vocabulary.

**4. Refinement is well-supported and cheap to build — the API is exactly the right shape.**
`createStream(hotwords: String = "")` creates a fresh context graph per stream with **no model
reload**. One recognizer instance, N streams, one per utterance, each with its own topic word
list. And `SpeechSegment(start, samples)` gives a sample offset, so a VAD utterance maps to an
exact byte range in the retained stream. **Nothing here argues against retaining recordings; the
retention decision is well-founded.** Two notes: (a) for a *better* second pass you can use the
offline transducer path, which has the same `CreateStream(hotwords)` support; (b) do not pick
Whisper or Moonshine for Refinement if biasing is the point of Refinement — neither supports
hotwords.

**5. Do not use `SpeechRecognizer` as a fallback that runs alongside your `AudioRecord`.** This
is the sharpest negative finding. "Two ordinary apps can never capture audio at the same time",
and the loser **receives silence, not an error**. The recognizer's `AudioRecord` lives in the
recognizer service's process (a different UID), so this is a cross-app contention. The failure
mode is a silent recording or a transcript of silence with no exception — the single worst outcome
for a byte-identical corpus. If a system-recognizer path is ever added, it must be **exclusive**
with the sherpa path, never concurrent, and should register an `AudioManager.AudioRecordingCallback`
and check `isClientSilenced()` defensively.

**6. If a system-recognizer fallback is still wanted, prefer ML Kit GenAI over
`SpeechRecognizer`** — but only as a clearly-labelled experiment. `AudioSource.fromPfd()` takes
raw headerless 16-bit mono 16 kHz PCM, which the tee already produces, so you can feed it a pipe
without giving up the mic. But: it is alpha with no SLA, it is unsupported on unlocked
bootloaders, its good (GenAI) mode is Pixel 10/11 only, and **it has no biasing API at all** — so
it cannot serve Refinement. `SpeechRecognizer`'s own `EXTRA_AUDIO_SOURCE` is the more portable
PCM-injection route on paper (API 33+), but support is optional per its javadoc, unqueryable
(`RecognitionSupport` reports only languages), and silently falls back to the recognizer opening
the mic — which lands you right back in finding 5.

**7. Keep Android system TTS, but add two guards.** Nothing found argues against system TTS. Two
platform constraints to design around:

   - The mic loop must be **started from the foreground**. `RECORD_AUDIO` is while-in-use, so
     creating a `microphone` foreground service from the background throws `SecurityException`,
     and it cannot be launched from a `BOOT_COMPLETED` receiver. Plan a user-visible start
     gesture, and use the documented exemptions (a notification action, an app widget, a
     `PendingIntent` from a visible app) for any restart path.
   - **Android 17 background audio hardening** restricts background *playback* — `AudioTrack.write()`,
     audio focus, volume APIs — for **all apps on Android 17 regardless of targetSdk**, requiring
     a visible activity or a non-`SHORT_SERVICE` foreground service; apps targeting API 37 need a
     **while-in-use-capable** FGS. The `microphone` FGS type *is* a while-in-use type, so a
     correctly-typed mic FGS should satisfy both. Register it as such and do not rely on a
     `SHORT_SERVICE`. Note the failure mode is again silent: "the audio playback and volume change
     APIs fail silently without throwing an exception or providing a failure message."

**8. Budget for the build, not just the code.** There is no Maven artifact or prebuilt AAR — the
native libraries must be built with the NDK and vendored as `jniLibs`, and the Kotlin API files
copied in. Pin a sherpa-onnx commit; the Kotlin API files are duplicated per example app in the
repo, so decide once where yours come from.

**9. Resolve the model license before any public release.** The recommended model's license is
asserted only on the k2-fsa HuggingFace mirror (`apache-2.0`), not in the sherpa-onnx repo or
docs, and the upstream icefall repo has no license at all. Low risk, but it is a real open item,
and it is cheap to resolve now by asking upstream.

---

## Unverified / open

**Android**

1. **Whether Google's on-device recognizer honours `EXTRA_AUDIO_SOURCE`** — on any device or
   Android version. Structurally unverifiable from primary sources: `createOnDeviceSpeechRecognizer`
   resolves a closed-source component named by `config_defaultOnDeviceSpeechRecognitionService`,
   and AOSP ships no `RecognitionService` implementation. Only empirical device testing can answer it.
2. **Whether it honours `EXTRA_BIASING_STRINGS`**, and with what phrase-count or length limits.
   Same reason. No limits are documented anywhere.
3. **Whether `EXTRA_BIASING_STRINGS` still applies when `EXTRA_AUDIO_SOURCE` is set.** The javadoc
   says `EXTRA_ENABLE_BIASING_DEVICE_CONTEXT` "may have no effect" in that case but is silent about
   `EXTRA_BIASING_STRINGS`.
4. **Whether `EXTRA_SEGMENTED_SESSION` works on the on-device recognizer**, and for how long a
   session. Documented as "may have no effect".
5. **Android 14/15/16-specific changes to audio-capture concurrency.** I found no behaviour-change
   entry altering the Android 10 priority scheme or the Android 11 `setPrivacySensitive` addition.
   Absence of a found entry is not proof of absence.
6. **Whether a preinstalled recognizer counts as "privileged"** for concurrency purposes on a given
   device, and therefore whether it could share input with an ordinary app rather than silencing it.
   The sharing-audio-input page defines privileged as preinstalled but does not enumerate recognizers.
7. **Whether same-UID concurrent `AudioRecord` sessions are permitted.** Not relevant to this design
   (one `AudioRecord`, software tee), but not established either.
8. **ML Kit GenAI speech recognition: maximum session duration, and whether the PFD path works with
   a live pipe** (as opposed to a file). No duration limit is documented; the live-pipe case is untested.

**sherpa-onnx**

9. **Any latency, RTF or RAM figure for Pixel-class or any modern ARM phone hardware.** None is
   published for any streaming model. The existing RTF numbers have **no stated hardware** (a
   developer macOS path is the only hint) and were measured with `greedy_search`.
10. **RTF of `modified_beam_search`** — the configuration this app must use for hotwords. No
    published figure for any model. Expected to be slower than the published greedy numbers by an
    unknown factor. **This is the most important thing to measure first.**
11. **Peak RSS for the recommended model on Android.** No RAM figure is published for any model in
    the project.
12. **Accuracy cost or benefit of hotwords** — no WER-with-biasing figures are published, and no
    guidance on a sensible `hotwords_score` beyond the `1.5f` default and a `2.0` example.
13. **Any practical limit on hotword count per stream.** The context graph is rebuilt per stream;
    no documented maximum, and no documented cost of rebuilding it per utterance.
14. **`sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06`** — license, accuracy, and footprint.
    Completely undocumented: a release asset and a Kotlin enum entry only. Potentially the most
    attractive option (54.6 MB tarball, zipformer2 transducer, 2025) but unverifiable as shipped.
15. **Whether the 20M model has a compatible BPE vocabulary available upstream**, which would make
    it a hotwords-capable low-footprint option.
16. **The recommended model's license as stated by its author** (see consequence 9).
17. **Whether the on-disk int8 subset actually loads standalone** — i.e. that shipping only the
    `chunk-16-left-128` int8 files plus `tokens.txt` plus `bpe.vocab` is sufficient. Inferred from
    the file listing, not verified by running it.
