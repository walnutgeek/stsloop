# stsloop — long-term vision

stsloop is an audio-first personal context hub: a continuous speech-to-speech
loop that sits between you and the things that want your attention or your
words. It is built for the times when your hands and eyes are busy, driving
first, and for the thought or the decision that is lost because typing was
impossible.

It is not a voice assistant. A voice assistant waits to be asked. stsloop
holds the floor with you.

> Vocabulary used here is defined in [`../CONTEXT.md`](../CONTEXT.md). Terms
> appear capitalised on first use. The first shipped scope is
> [`./mvp.md`](./mvp.md) (v0.1); what was built and driven before it is
> [`./phase0.md`](./phase0.md).

## The wedge: your agents, from the car

The job that makes someone install stsloop is **supervising coding agents
hands-free**. You start five agent sessions, get in the car, and the loop
tells you when one is blocked and what it is asking. You answer by voice, hear
your answer read back, and say "send". Nothing else does this well, and the
people who run several agents at once are the people who will try it.

Capture is the base layer under the wedge. Notes filed into Buckets work with
no hub at all, and the Corpus they build is still the most valuable thing
stsloop produces. Podcast triage, the original idea, stays last.

## The spine

Everything in stsloop is a **Turn**: one handover of the floor, in either
direction. A Session is a sequence of Turns. There is one loop, and everything
you can talk to is a **Bucket**.

```
             ┌──────────────────────────────┐
             │       stsloop (Session)      │
             │      listen  ⇄  speak        │
             │   Focus: one current Bucket  │
             └──────────────┬───────────────┘
                            │
        ┌───────────────────┼────────────────────────┐
        │                   │                        │
  capture Provider    herdr Provider       Claude Code hooks Provider
   (on the phone)       (on the hub)            (on the hub)
        │                   │                        │
  Inbox, errands,     one Bucket per        blocked / done events
  house-project …     agent session         per agent session
```

The crucial property is still that **inbound and outbound share one floor**.
The same loop that takes your dictation tells you an agent is blocked and
carries your reply to it. There is no app to open and no wake word. A Session
is running or it is not.

## Buckets, Providers and Focus

A **Bucket** is anything addressable by name. It has a name, aliases, a type,
the commands you can say to it, and optionally Inbound Items it can raise.
"Errands" is a Bucket; so is the agent session working on stsloop.

A **Provider** publishes a set of Buckets. The capture Provider lives on the
phone. The herdr and Claude Code hooks Providers live on the hub, and podcast
will be another. The Provider is the plug-in boundary; it replaces what the
earlier vision called a Lane ([ADR-0005](./adr/0005-bucket-absorbs-lane.md)).

A Provider describes each Bucket to the phone in a **Descriptor**. The phone
does not run Provider code. It knows a small, fixed set of **Primitives**, and
a Descriptor composes them:

| Primitive | What the phone does |
| --- | --- |
| `note` | File the Turn under the Bucket |
| `draft` | Accumulate Turns into a Draft; read it back; send or cancel it |
| `speak_item` | Speak an Inbound Item into Silence |
| `request` | Send a named command to the Provider and speak the reply |

A new Provider needs no phone release as long as it uses known Primitives. A
Descriptor that names a Primitive the phone does not know degrades to its
plain `request` commands, and the loop says so once.

**Focus** is the current Bucket. Naming a Bucket on its own ("stsloop agent")
focuses it, and later Turns are read against its commands. Focus is sticky
with a timeout, and the loop **says the Bucket name every time Focus changes**,
including when it times out back to the **Inbox**. This is a mode, so it must
never be a silent one: you always hear where you are, and "where am I" tells
you again.

Focus also fixes the most common capture failure from driving: "errands",
a pause, "order roofing screws" used to lose its label. Now the lone alias
focuses errands, and the next Turn is filed there.

## Talking to an agent

A focused agent Bucket turns speech that is not a command into a **Draft**.
You can dictate across as many pauses as you need. "Read it back" reads the
Draft. "Send" reads it back once more and then sends it, with an idempotency
key, so a reconnect can never send it twice. "Cancel" drops it. A Draft left
when Focus times out is kept and announced, never sent.

When an agent becomes blocked, the hub raises an Item and the loop speaks it
into the next Silence: an earcon and one sentence, such as "stsloop agent is
blocked: asking whether to rebase or merge." Several changes merge into one
Item. An agent that finishes waits until you ask. Each Bucket can be muted.

Agent output is markdown, diffs and code, which is useless read aloud. The hub
summarises it into a sentence or two with a model, by default `claude -p`
([ADR-0007](./adr/0007-summariser-on-the-hub.md)). The model chooses what to
say; it never owns a control.

## Silence is the mechanic

The loop's only scheduling primitive is **Silence**.

- Silence past a short threshold **closes your Turn**: it is how the loop
  knows you have finished speaking.
- Silence past a longer threshold **grants the machine a Turn**: it is how the
  Attention Queue drains.

That single signal replaces turn-taking heuristics, wake words and buttons.
It also sets the loop's defining risk: a loop that mistakes a pause for
consent will talk over your thinking. The silence floor is therefore a
first-class, tunable product surface, adjustable on the phone, not a constant
buried in a state machine.

The loop is **half-duplex**: the microphone is closed for the duration of any
machine Turn plus a guard interval, so stsloop can never transcribe its own
voice. stsloop is deaf exactly while it is speaking, which is when you most
want to say "skip". Fixing that is Barge-in. Phase 0 found that a car's
hands-free link already cancels the echo of what the car plays
([findings](./brainstorm/2026-10-10-phase0-findings.md), point 1), so
Barge-in may be cheap in the car and stay hard everywhere else.

## Topology: the phone owns the loop

The phone is authoritative. It runs voice activity detection, transcription,
speech synthesis, turn-taking, Focus, the phrase grammar, the Attention Queue
and storage. **It works with no network at all.** The hub is not a brain the
phone talks to; it is where some Providers live.

```
PHONE  (authoritative, offline-capable)
  VAD · STT · TTS · turn-taking · Focus · grammar · Attention Queue · Corpus
  cached Descriptors
    │
    ├── capture Provider  (local)
    └── hub connection ┈┈┈┈ Tailscale, one WebSocket ┈┈┈┈┐
         optional, degrades gracefully                    │
                                                         HUB  (Python)
                               herdr Provider · Claude Code hooks Provider
                               summariser · Corpus store · re-transcription
```

The reason is the use case. Tunnels, dead zones and handoffs are normal
conditions, not edge cases, and Silence must grant the machine a Turn in a few
hundred milliseconds. See [ADR-0001](./adr/0001-phone-owns-the-loop.md).

The phone caches every Descriptor it has seen. With the hub out of reach, an
agent Bucket still resolves by name and Focus still works; the loop says "hub
offline" and holds the Draft. Capture never notices.

The phone and the hub meet at a contract, not at shared code: JSON messages,
a schema per message, and golden fixtures that both sides' tests must pass.
The hub keeps working with phones one major version behind
([ADR-0006](./adr/0006-protocol-contract-and-versioning.md)).

## Capture and the Corpus

A **Note** is something you said and meant to keep. It is filed in a capture
Bucket by **Declaration** (naming it aloud), by Focus, or into the Inbox.

Declaration is doing more work than it appears to. Because a declared Turn's
Bucket is known with certainty, **every Declaration is a labelled training
example.** You declare heavily at first, a classifier learns from the
accumulating **Corpus**, and declaration gradually becomes optional.

This is why stsloop retains the **Recording** of every Turn alongside its
transcript, and why the hub matters to capture. The loop needs a fast
streaming transcript on the phone; the Corpus deserves the best transcript
there is. Once the Corpus reaches the hub, it can re-transcribe every
Recording with the best offline model, as a derived layer keyed by model,
without asking you to say anything again.

Bucket names are a recognition problem as much as a naming one. Some words
fail in every model tried. A Bucket name or a command phrase is therefore
checked when it is registered, by speaking it through TTS into the recogniser,
and the loop warns about names it cannot reliably hear.

## The control surface

The ladder still sets how far a Provider may reach:

| Rung | Capability | Risk |
| --- | --- | --- |
| 1 | **Read state.** Which agents are blocked, done, working. | None |
| 2 | **Read content.** Bounded, summarised agent output. | Leakage into transcripts and models |
| 3 | **Draft.** Compose a reply by voice; nothing is sent. | None |
| 4 | **Send, confirmed.** Send a Draft after a spoken readback and "send". | Wrong agent, duplicate sends |
| 5 | **Execute.** Approve an agent's permission request, run a command. | Real, and permanent |

v0.1 goes up to rung 4. Most blocked agents are asking a question, and
answering is rung 4. Permission approvals are rung 5 and stay out; run agents
in a permissive mode before you drive if you want fewer prompts. Anything
above rung 1 is redacted for obvious secrets before it reaches a model or a
speaker.

## Phases

The ordering principle: **prove the behaviour cheaply, then make the content
expensive.**

| Phase | What | Proves |
| --- | --- | --- |
| **0 — Loop** (done) | Half-duplex Session on the phone. Record, transcribe, echo, store. Declaration and four commands. | Talking to it in a car works; recognition, not audio, is the bottleneck |
| **v0.1 — Agents** | Hub with herdr and Claude Code hooks Providers. Focus, Drafts, blocked alerts, settings on the phone. Signed APKs; Show HN. | Is supervising agents by voice worth installing |
| **Corpus hub** | Corpus upload, best-model re-transcription, the classifier experiment against transcripts of record. | Is the taxonomy real; can declaration become optional |
| **Daily brief** | Composed Inbound run; skip navigates the Library. | Push-on-silence beyond alerts; Library navigation |
| **Podcast** | RSS, transcripts and chapters, topic segmentation, explainable scoring, seek playback. | The original idea, with every prerequisite in place |
| **Distribution** | F-Droid, then the Play Store; iPhone via the KMP-ready core. | — |

Barge-in, a tmux Provider and wake words slot in where the evidence says they
are needed. None of them are gates.

## Principles

1. **The loop is local.** Turn-taking, Focus and the grammar never wait on a
   network.
2. **Silence is the only scheduler.** No wake word, no button. Focus is the
   one mode, and it is always spoken.
3. **Deterministic controls, probabilistic content.** A model may choose what
   to say; it may never own stop, skip, Focus or send.
4. **Retain the audio.** Today's transcript is a guess; the Recording is
   evidence. The Corpus is the most valuable thing stsloop produces.
5. **Declaration over inference.** Prefer the thing you said plainly to the
   thing a model guessed, and use the former to train the latter.
6. **Read before write.** Every Provider starts read-only and earns its way up
   the ladder.
7. **Degrade, don't fail.** A Provider that cannot be reached goes quiet. The
   loop keeps running.
8. **Contracts over shared code.** The phone and the hub agree on fixtures,
   not on a language.

## What stsloop is not

- **Not a general assistant.** It has Buckets with declared commands, not
  open-ended capabilities.
- **Not a podcast player** that happens to talk, nor a dictaphone that happens
  to read back. The loop is the product.
- **Not a cloud service.** No account system and no relay. Your phone, your
  own machine, your own tailnet. Open source.
- **Not always listening.** A Session is started deliberately and is visible
  while it runs. Nothing captures audio outside one.
