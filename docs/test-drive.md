# Test drive checklist (Bluetooth test mode, #27 → #8)

One drive to answer two questions from `docs/mvp.md` ("Known unknowns"): which
microphone path survives road noise, and what the Tesla's Bluetooth routing does
when the phone speaks while it records. Every Session below is a **test
Session**: the phone speaks *"This is the machine speaking, test number N"*
every 5 s while it keeps recording, and logs every route change.

Expect the phone to transcribe its own voice in some configurations. That is
what is being measured. Test Turns are tagged in `turn.json` (`test` block), so
they never mix with real Notes.

## Safety first

- **While the car is moving, the driver reads nothing, taps nothing and ticks
  nothing.** Do the driving runs either with a **passenger** who reads the
  phrases aloud (from the passenger seat, toward the phone) and keeps the
  checklist, or with the driver saying only the **three short memorised
  phrases** below, eyes on the road. Note afterwards which way it was done.
- Change configuration **only when parked or stopped safely**. A driving run
  is started before pulling out and stopped after pulling over.
- Fill in the checklist only when parked.

## Before you leave

- [ ] The debug build with speech is installed (the app lists transcripts, not "(no transcript)").
- [ ] Phone paired and connected to the car over Bluetooth; media volume up to normal.
- [ ] Open stsloop. Tap **Test mode: off** to turn it **ON**. The test controls appear.
- [ ] Keep the phone where it usually rides (mount or cup holder). Note it here: ________
- [ ] Memorise the three driving phrases (below).

## Switching configuration (parked, ~10 s)

Stop Session → tap the buttons → Start Session. Settings apply at the next Start.

| Button | Cycles through |
| --- | --- |
| **Condition** | `desk` → `parked-ac-off` → `parked-ac-on` → `driving` |
| **Mic** | the six presets below |
| **TTS** | every 5 s → 10 s → 20 s → off |
| **TTS usage** | `assistant` → `media` → `navigation` → `voice_communication` |

At each Start the phone announces the configuration aloud, naming the mic it
**actually** got, e.g. "Test mode. parked ac off. voice recognition, built in
mic." If it says **"bluetooth unavailable, built in mic"**, the Bluetooth preset
fell back to the phone mic (see below). Presets in call mode end with "Call mode."

Mic presets:

| # | Mic preset | What it is |
| --- | --- | --- |
| 1 | `voice_recognition/default` | What the first drive used: no preference, the system picks |
| 2 | `voice_recognition/builtin` | Phone mic, ASR-tuned source |
| 3 | `mic/builtin` | Phone mic, plain source (louder at the desk: about +10 dB) |
| 4 | `unprocessed/builtin` | Phone mic, no processing. Very quiet at the desk (−59 dBFS speech, empty transcripts). Not in the run table |
| 5 | `voice_recognition/bluetooth` | Car mic via the hands-free (SCO) communication device |
| 6 | `voice_communication/bluetooth (in_communication)` | Car mic as a call would use it: call mode + echo cancellation |

## The runs: exactly 15, ~2 min each

One run per box: 5 mic presets × 3 conditions = **15 runs**, about 40 minutes
with switching. Do all 10 parked runs first, then the 5 driving runs. Set
**Condition** to match each run.

In each run, wait for the announcement, then say the phrases **between** the
machine's phrases, slowly, in your normal voice: the full list of six when
parked, the three driving phrases when moving (or the full list read by a
passenger). Then stay silent for the rest of the 2 min; that silence measures
noise-only Turns.

| Condition → / Mic ↓ | parked, AC off | parked, AC on | driving |
| --- | --- | --- | --- |
| 1 `voice_recognition/default` | [ ] | [ ] | [ ] |
| 2 `voice_recognition/builtin` | [ ] | [ ] | [ ] |
| 3 `mic/builtin` | [ ] | [ ] | [ ] |
| 5 `voice_recognition/bluetooth` | [ ] | [ ] | [ ] |
| 6 `voice_communication/bluetooth` | [ ] | [ ] | [ ] |

When parked, note anything odd next to the box: an audible route switch, music
ducking, the car screen showing a call, the phrase not heard. Optional extra
(parked, if time allows): preset 1 with **TTS usage** `voice_communication`,
which sends the phrase over the hands-free route instead of A2DP.

What to listen for:

- Is the phrase heard from the car speakers, the phone, or not at all?
- Does anything switch audibly when a Session starts or the phrase plays (music
  pausing, a call-like screen, quality dropping)?

### If you hear "bluetooth unavailable"

The phone found no hands-free device to use, so presets 5 and 6 recorded from
the phone mic instead (logged as `bluetooth_unavailable`). Parked: check the
phone shows the car as connected *for calls* (Bluetooth settings → the car →
"Phone calls" on), then Stop and Start again. If it persists, still run the box
(the fallback is data too) and mark it "BT unavailable".

### After preset 6 (call mode): confirm the phone is back to normal

Call mode should be released when the Session stops. Parked, after stopping a
preset 6 run: play any music. It should come out of the car speakers in media
quality, and the car screen should show no call. If it does not, start and stop
a normal (preset 1) Session; if still stuck, toggle the phone's Bluetooth off
and on. Afterwards, `adb shell dumpsys audio | grep -iE "mode|communication device"`
should show mode `NORMAL` and no preferred communication device, and the
Session's log ends with `session_end` showing `"mode":"normal"`.

## Phrase list (parked: all six, in this order)

1. "Errands, order roofing screws."
2. "House project, check the joist spacing."
3. "Remind me to call the dentist on Monday morning."
4. "Work, finish the quarterly report draft."
5. "The quick brown fox jumps over the lazy dog."
6. "Ideas, a podcast about long road trips."

**Driving phrases** (memorised, the driver says only these): 1, 3 and 5.

Phrases 1, 2, 4 and 6 start with a default Bucket name (`docs/mvp.md`), so they
also test Declarations under each configuration.

## Afterwards: pull the Corpus and run the report

```sh
mkdir -p ~/stsloop-drive && cd ~/stsloop-drive
mise exec -- adb exec-out run-as com.walnutgeek.stsloop sh -c 'cd files && tar cf - corpus' | tar xf -
uv run <repo>/scripts/test_drive_report.py corpus > report.md
```

The report groups Turns by configuration (Condition + Mic + TTS) and gives each
group's Turn count, empty-transcript share, noise floor, speech level, and the
Turns that overlapped the machine's phrase (self-capture candidates) with their
transcripts. A section per Session summarises its route log
(`corpus/sessions/*.jsonl`): where TTS went (`bluetooth_a2dp`, `bluetooth_sco`,
`builtin_speaker`, `builtin_earpiece`), which mic was actually used, whether
the recording was ever silenced, how much louder the mic got while the phrase
played, and problems such as `bluetooth_unavailable`. Add `--session ID` to
look at one Session.

When done, tap **Test mode: ON** to turn it off, so normal Sessions stop speaking.
