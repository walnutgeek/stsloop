# Phase 0 findings that push on the vision (2026-10-10)

What building and test-driving Phase 0 taught, where it disagrees with
[`../vision.md`](../vision.md) or [`../mvp.md`](../mvp.md), and the ideas it
suggests. **Inputs to a brainstorm, not decisions.** Evidence for each point is
on the issue named. The numbers come from two drives in one Tesla with one
speaker and one phone (Pixel 10 Pro).

## 1. The car is the audio device, and it brings echo cancellation

- **Evidence (#8):** over Bluetooth SCO, none of 126 machine phrases played
  through the car while the phone recorded on purpose became a Turn. On the
  phone mic, phrases from the car speakers leaked in.
- **Vision says:** Barge-in "needs either acoustic echo cancellation or a
  keyword spotter" and is "a known future problem".
- **Idea:** in the car, the primary use case, the hardware already provides
  the AEC. Barge-in ("skip", "stop") may be cheap there and could arrive well
  before Phase 4's podcast, where the vision needs it most. Half-duplex stays
  for the phone-mic fallback.
- **Topology:** the vision's diagram has no car in it. The hands-free route is
  now the default audio path (#11), so the car's audio link is part of the loop.

## 2. Off the hands-free route, the phone mic in a moving car barely works

- **Evidence (#8):** driving on the phone mic, speech was 5–10 dB above road
  noise. 23–60% of Turns came back empty, and about two thirds of drive 1's
  driving Turns were noise only. The car's mic gave about 30 dB less noise.
- **Question:** the vision names walking and cooking too. Those run on the
  phone mic or earbuds, with no evidence yet. Is the loop car-first, with other
  settings as later Lanes of evidence?

## 3. Two transcripts: one for the loop, one of record

- **Evidence (#8 A/B, #30):** recognition, not audio, was the bottleneck. The
  zipformer had a word error rate of 64–73%; nemotron (streaming, shipped) has
  17–25% parked and 38–51% driving. The best model was offline: Parakeet TDT,
  24% overall. Recognition costs size: 692 MB APK, 757 MB of memory.
- **Idea:** the loop needs a fast streaming transcript on the phone for the
  echo, commands and Declaration. The Corpus needs the *best* transcript, and
  the Recording is retained precisely so it can be re-made. The Linux box could
  re-transcribe every Recording with the best offline model, as an
  append-only derived layer keyed by model. This is Refinement without
  hotwords, and it fits "Retain the audio" and the hub-as-Lane boundary. The
  classifier would train on transcripts of record, not loop transcripts.

## 4. Word lists lost their mechanism; Bucket names are a recognition problem

- **Evidence (ADR-0004, #8 A/B):** the shipped model has no hotwords until a
  sherpa-onnx bump, so the vision's "the Bucket's word list stops the
  recogniser hearing *ruffing*" is not available on the phone. Some words fail
  in every model tried: "errands" as errants, aarons or arens, and "roofing
  screws" as "ruffins cross".
- **Ideas:**
  - Choose Bucket names and aliases for what the recogniser reliably emits.
    Learn aliases from the Corpus, and check a new Bucket name by speaking it
    through TTS into the recogniser at authoring time (as `CommandsDeviceTest`
    does for commands).
  - Word-list Refinement could live in the hub's second pass (point 3) rather
    than on the phone.

## 5. Declaration does not survive a natural pause

- **Evidence (#8, drive 2):** "Errands, order roofing screws" was said with
  a comma pause, and it became two Turns: "ERRANDS" and "ORDER ROOF INSCREWS".
  A Turn that is only an alias is not a Declaration, so the label was lost.
- **Ideas:** a lone-Bucket Turn could declare the *next* Turn (a short-lived
  "current Bucket"), or close a Turn with a longer silence after a lone alias.
  Either changes the "one Turn, one Declaration" shape in `mvp.md`.

## 6. The Corpus count needs a definition

- **Evidence (#17 loader, #8):** a large share of Turns on the phone mic were
  noise only or empty. In the drive Corpus, 6% of all Turns were declared, or
  10% of those with a transcript (scripted drives, so not a real rate).
- **Idea:** "several hundred Turns, a healthy share declared" in "Done when"
  should count Turns with speech, or noise will meet the target.

## 7. Egress as "folder sync" does not hold yet

- **Evidence (#17):** the Corpus lives in app-private storage. Syncthing and
  rsync cannot read it, and only `adb run-as` on a debug build gets it out. A
  release build has no way out at all.
- **Needed:** an app-side export, for example to a folder chosen through
  Android's Storage Access Framework, before "the Corpus syncs to the Linux box
  as files" is true. No ticket exists yet.

## 8. Tunable surfaces are adb-only

- **Evidence:** timings, Buckets and test mode are files in app storage,
  written with `adb` on a debug build.
- **Tension:** the vision calls the silence floor "a first-class, tunable
  product surface". Today it cannot be tuned from the car or even from the
  phone. A spoken or on-screen way to adjust trailing silence may belong in
  Phase 0.

## 9. Smaller observations

- **Car mic quality:** for every strong model, the car mic transcribes worse
  than the phone mic even when parked, despite far better signal-to-noise. The
  cause is unexplained; the SCO codec is a guess.
- **Guard and Bluetooth latency:** the guard (300 ms) runs from the TTS
  engine's end report, so over Bluetooth it must also cover output latency.
  It has not been checked in the car.
- **Play Protect:** it flagged the sideloaded debug build as harmful but did
  not block it. Daily use on a personal phone may need a decision about
  distribution and signing.
- **Readback of errors:** the echo now reads back the transcript, errors
  included. Whether that helps (you hear what was stored) or grates is still
  the open question in `mvp.md`. An earcon plus the Bucket name remains the
  fallback.
