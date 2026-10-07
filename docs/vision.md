# stsloop — long-term vision

stsloop is an audio-first personal context hub: a continuous speech-to-speech
loop that sits between you and the streams of information, attention demands,
and capture surfaces in your life. It is built for the times when your hands
and eyes are busy — driving, walking, cooking — and for the thought that
arrives in those times and is lost because writing it down was impossible.

It is not a voice assistant. A voice assistant waits to be asked. stsloop
holds the floor with you.

> Vocabulary used here is defined in [`../CONTEXT.md`](../CONTEXT.md). Terms
> appear capitalised on first use.

## The spine

Everything in stsloop is a **Turn** — one handover of the floor, in either
direction. A Session is a sequence of Turns. There is one loop, and
capabilities attach to it as **Lanes**.

```
             ┌──────────────────────────────┐
             │       stsloop (Session)      │
             │      listen  ⇄  speak        │
             └───────┬──────────────┬───────┘
      INBOUND        │              │       OUTBOUND
  (competes for me)  │              │     (I emit)
  ┌──────────────────┴───┐    ┌─────┴─────────────────┐
  │ podcast segment      │    │ Note → Bucket         │
  │ agent went blocked   │    │ command               │
  │ marked page summary  │    │ prompt bound for an   │
  │ daily brief item     │    │   agent               │
  │ approval request     │    │ question              │
  └──────────────────────┘    └───────────────────────┘
```

The crucial property is that **inbound and outbound share one floor**. The
same loop that takes your dictation reads you a podcast segment and tells you
an agent is blocked. There is no mode switch, no app to open, no wake word.
A Session is running or it is not.

## Silence is the mechanic

The loop's only scheduling primitive is **Silence**.

- Silence past a short threshold **closes your Turn** — it is how the loop
  knows you have finished speaking.
- Silence past a longer threshold **grants the machine a Turn** — it is how
  the Attention Queue drains.

That single signal replaces turn-taking heuristics, wake words, and buttons.
It also sets the loop's defining risk: a loop that mistakes a pause for
consent will talk over your thinking. The silence floor is therefore a
first-class, tunable product surface, not a constant buried in a state
machine.

The loop is **half-duplex**: the microphone is closed for the duration of any
machine Turn plus a guard interval, so stsloop can never transcribe its own
voice. The cost is honest and should be stated plainly — stsloop is deaf
exactly while it is speaking, which is when you most want to say "skip". Fixing
that is Barge-in, and it is a known future problem rather than a v1 promise.
It needs either acoustic echo cancellation or a second always-listening path
narrow enough that TTS cannot trigger it (a keyword spotter).

## Topology: the phone owns the loop

The phone is authoritative. It runs voice activity detection, transcription,
speech synthesis, turn-taking, the Attention Queue, and storage. **It works
with no network at all.** The Linux hub is not a brain the phone talks to; it
is one more Lane, which contributes Items when reachable and goes quiet when
not.

```
PHONE  (authoritative, offline-capable)
  VAD · STT · TTS · turn-taking · Attention Queue · Corpus
    │
    ├── Lane: capture        (local)
    ├── Lane: podcast        (local playback, local scoring)
    └── Lane: hub   ┈┈┈┈ Tailscale ┈┈┈┈┈┈┐
         optional, degrades gracefully    │
                                     LINUX HUB
                                  Herdr · browser · daily brief
```

This is the reverse of the obvious design, and the reason is the use case:
stsloop is for driving. Tunnels, dead zones, and handoffs are normal
conditions, not edge cases. A loop whose turn-taking depends on a round trip
to your house fails exactly when you are using it. See
[ADR-0001](./adr/0001-phone-owns-the-loop.md).

There is a second, quieter reason. Silence grants the machine a Turn, and the
machine must be able to take that Turn in a few hundred milliseconds. That is
a local decision. It cannot be a network round trip.

## Capture: Buckets and Declaration

The capture Lane is the part of stsloop that justifies itself on day one.

A **Note** is something you said and meant to keep. Every Note belongs to
exactly one **Bucket** — a named topic that is both where the Note lives and
the owner of a **Word list** used to transcribe it correctly. "Order roofing
screws" belongs in an errands Bucket, and that Bucket's word list is what
stops the recognizer hearing *ruffing* or *screwed*.

You assign a Bucket in one of two ways:

- **Declaration** — naming it aloud, at the start or end of the utterance:
  *"errands — order roofing screws."* Deterministic, unambiguous.
- **Classification** — the loop infers it. Eventually. Not at first.

Declaration is doing more work than it appears to. Because a declared Turn's
Bucket is known with certainty, **every Declaration is a labelled training
example.** The system bootstraps itself: you declare heavily at first, a
classifier learns from the accumulating **Corpus**, and declaration gradually
becomes optional. You are not labelling data as a chore; you are labelling it
as a side effect of being understood.

This is why stsloop retains the **Recording** of every Turn alongside its
transcript. The Corpus is replayable. A better model, or a better Refinement
strategy, can be evaluated against everything you have ever said, offline,
without needing you to say it again.

**Refinement** — actually using a Bucket's Word list to correct transcription
— is deliberately unspecified. There is a genuine ordering problem (you need
the Word list to transcribe well, but the transcript to pick the Word list),
and several escapes: re-transcribe a second time with the Bucket's bias,
repair tokens lexically against the word list, or bias from the start when the
Bucket is declared up front. Retaining audio means this can be decided with
evidence rather than argument.

## Consumption: Library and brief

Everything stsloop holds that can be played back forms the **Library** —
Notes, past Turns, Recordings, podcast segments. Skip navigates it. This is
the eyes-free equivalent of scrolling, and it is the only way to review
anything while driving.

The **daily brief** is a composed sequence of Items, assembled in advance and
played on request as one continuous run rather than drained opportunistically
into Silence. It is the answer to "catch me up" — and architecturally it is
the cheapest possible real Inbound Lane, which is why it comes first.

## The control surface

stsloop should let you reach the things running on your behalf: terminal
sessions with coding agents in them, and the browser tab you were looking at.

The honest framing is a ladder, climbed slowly:

| Rung | Capability | Risk |
| --- | --- | --- |
| 1 | **Read state.** Which agents are blocked, done, working. What is in the marked tab. | None |
| 2 | **Read content.** Bounded tails of agent output, page text, selections. | Leakage into transcripts and models |
| 3 | **Draft.** Compose a prompt or an input by voice; nothing is sent. | None |
| 4 | **Send, confirmed.** Submit a prompt to an agent after explicit spoken confirmation. | Wrong agent, duplicate sends |
| 5 | **Execute.** Approve an agent's permission request, run a command. | Real, and permanent |

Rungs 1–3 are read-only or inert and can be built freely. Rung 4 is where
idempotency, confirmation, and an audit trail stop being good practice and
become mandatory — a reconnect must never resend a prompt. Rung 5 should feel
uncomfortable to reach for, and should probably never be available while
driving.

Anything above rung 1 needs reads to be bounded by length and redacted for
obvious secrets before being spoken aloud or submitted to a model. The browser
in particular should never stream pages continuously — only a tab you have
explicitly marked, only on request.

## Phases

The ordering principle: **prove the behaviour cheaply, then make the content
expensive.** The interaction model is the risky part; podcast triage is merely
a lot of work.

| Phase | What | Proves |
| --- | --- | --- |
| **0 — Loop** | Always-on half-duplex Session. Record, transcribe, echo, store. Declaration and a handful of commands by phrase grammar. Corpus syncs to the Linux box as files. | Does talking to this thing in a car feel natural at all |
| **1 — Classifier** | Bucket and command detection developed against the Corpus on a laptop. Deployed to the phone only when it beats Declaration. | Is the taxonomy real; is inference viable on-device |
| **2 — Daily brief** | First Inbound Lane. Hub composes a brief; phone plays it as one run. Skip navigates the Library. | Push-on-silence; skip; Library navigation |
| **3 — Hub lanes** | Herdr agent state and browser page context, feeding the brief and the queue. Rungs 1–3, then 4. | Is the hub-as-Lane boundary right; does degradation work |
| **4 — Podcast** | RSS, Podcasting 2.0 transcripts and chapters, fallback transcription, topic segmentation, explainable scoring, source-audio seek playback. | The original idea, with every prerequisite already in place |
| **5 — iPhone** | The non-audio core lifts to Kotlin Multiplatform; a native Swift audio shell is added. | — |

Barge-in, Refinement, and wake words slot in wherever the evidence says they
are needed. None of them are gates.

## Principles

1. **The loop is local.** Turn-taking never waits on a network.
2. **Silence is the only scheduler.** No modes, no wake word, no button.
3. **Deterministic controls, probabilistic content.** A model may choose what
   to say; it may never own stop, skip, or send.
4. **Retain the audio.** Today's transcript is a guess; the Recording is
   evidence. The Corpus is the most valuable thing stsloop produces.
5. **Declaration over inference.** Prefer the thing you said plainly to the
   thing a model guessed — and use the former to train the latter.
6. **Read before write.** Every connector starts read-only and earns its way
   up the ladder.
7. **Degrade, don't fail.** A Lane that cannot be reached goes quiet. The loop
   keeps running.

## What stsloop is not

- **Not a general assistant.** It has Lanes, not open-ended capabilities.
- **Not a podcast player** that happens to talk, nor a dictaphone that happens
  to read back. The loop is the product; both are Lanes.
- **Not a cloud service.** There is no account system, no multi-user model,
  and no public listener. One person, their phone, and their own machine on a
  private network.
- **Not always listening.** A Session is started deliberately and is visible
  while it runs. Nothing captures audio outside one.
