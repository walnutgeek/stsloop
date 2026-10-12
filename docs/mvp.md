# stsloop v0.1 — supervise your agents from the car

v0.1 is the first release meant for someone other than the owner. It adds a
hub on the owner's own machine, and with it the job that makes stsloop worth
installing: hearing when a coding agent is blocked, and answering it by voice.

> Vocabulary: [`../CONTEXT.md`](../CONTEXT.md). Long-term shape:
> [`./vision.md`](./vision.md). The loop, timings, phrase grammar and Corpus
> format it builds on are specified in [`./phase0.md`](./phase0.md) and do not
> change unless this document says so.

## Done when

1. **The owner uses it.** Two weeks of the owner's real drives, unblocking
   agents by voice on most drives without touching the phone.
2. **A stranger can install it.** The onboarding below works from a clean
   machine and a clean phone, following only the README.
3. **There is a demo.** A 90-second video cut from a real drive: several
   agents, voice only.

Then a Show HN.

## In scope

- **Hub** (`hub/`, Python, `uv tool install stsloop-hub`):
  - `stsloop-hub init` detects herdr and Claude Code, installs the Claude
    Code hooks with the user's consent, and prints a pairing QR code;
  - the herdr Provider;
  - the Claude Code hooks Provider;
  - the summariser;
  - Corpus upload, as an owner feature, not advertised.
- **Protocol** (`protocol/`): JSON Schema per message and golden fixtures, per
  [ADR-0006](./adr/0006-protocol-contract-and-versioning.md).
- **Phone:**
  - QR pairing; the hub connection; cached Descriptors;
  - the four Primitives: `note`, `draft`, `speak_item`, `request`;
  - Focus, with a timeout and an Inbox;
  - Drafts with readback and confirmed send;
  - blocked alerts spoken into Silence; per-Bucket mute;
  - a settings screen;
  - the name-recognisability check;
  - models downloaded on first run rather than bundled;
  - signed release APKs on GitHub releases.

## Out of scope, on purpose

| Not in v0.1 | Why |
| --- | --- |
| Approving permission prompts (rung 5) | Real and permanent; run agents in a permissive mode before driving instead |
| Speaking "done" agents unasked | Only a blocked agent makes you the bottleneck |
| A tmux Provider | Contributors can write one against the protocol |
| Best-model re-transcription, the classifier | The Corpus hub phase, after launch |
| Barge-in | Summaries are short; evidence first (findings, point 1) |
| Daily brief, podcast | Later phases |
| F-Droid, the Play Store | After launch; the Play Store's review of an always-listening service is real work |
| Behaviour declared by a Provider | Primitives first; see ADR-0006, "Deferred" |
| A relay or account | Not a cloud service |

## Onboarding

1. On the machine running the agents: `uv tool install stsloop-hub`, then
   `stsloop-hub init`. It finds herdr and Claude Code, asks before installing
   the hooks, and prints a QR code holding the hub's tailnet address and a
   token.
2. On the phone: install the signed APK from GitHub releases. On first run it
   downloads its models, once, over Wi-Fi.
3. Scan the QR code. The phone pulls the Descriptors, runs the
   name-recognisability check, and says "ready, N agents".
4. Start a Session.

Tailscale is the documented path. The hub listens on its tailnet address
only. Underneath, any address the phone can reach works with the same token.

Herdr is optional. Without it, Claude Code hooks still give blocked and done
events and summaries (rungs 1–2). Sending a reply needs a way into the
agent's terminal, which herdr provides.

## Buckets in v0.1

| Provider | Buckets | Primitives |
| --- | --- | --- |
| capture (phone) | Inbox, plus the configured capture Buckets | `note` |
| herdr (hub) | one per agent pane | `draft`, `request`, `speak_item` |
| Claude Code hooks (hub) | one per Claude Code session | `request`, `speak_item` |

When herdr and the hooks both see the same agent, the hub merges them into one
Bucket. An agent Bucket's name comes from its herdr workspace or its project
directory, and its aliases can be edited in settings.

### Commands

Commands are a deterministic grammar declared in the Descriptor, with at most
one free-text slot each, as the payload. Starting set for agent Buckets:

| Phrase | Does |
| --- | --- |
| "status" | Blocked, working or done, and how long |
| "what does it say" / "read it" | The summary of the agent's last output |
| "read it back" | Read the Draft |
| "send" | Read the Draft back, then send it |
| "cancel" | Drop the Draft |
| "quiet" / "unmute" | Mute or unmute this Bucket's alerts |

Loop-wide, whatever has Focus:

| Phrase | Does |
| --- | --- |
| a Bucket name on its own | Focus that Bucket, and say its name |
| "where am I" | Say the focused Bucket |
| "what's blocked" | List blocked agents |
| "inbox" | Focus the Inbox |

Phase 0's commands ("scratch that", "repeat", "stop listening") keep working.
"What bucket" stays as an alias of "where am I". Every phrase here goes
through the recognisability check before it ships.

### Focus

- Naming a Bucket alone focuses it. A Declaration at the start or end of a
  Turn files that Turn and does not move Focus.
- Focus times out after a quiet period (starting at 2 minutes, tunable as
  `focus_timeout_ms` in `files/timings.json`) back to the Inbox. Every change
  of Focus, timeout included, is spoken ("back to inbox.").
- In a capture Bucket, a Turn that is not a command is a Note.
- In an agent Bucket, a Turn that is not a command is appended to the Draft.
- A Draft left at the timeout is kept and announced ("draft kept for stsloop
  agent"), never sent.

### Send

"Send" always reads the Draft back first, then sends it with an idempotency
key. The hub delivers a key at most once, so a reconnect cannot resend. With
the hub offline, the loop says "hub offline, draft kept".

### Alerts

When an agent becomes blocked, the hub raises an Item. The phone speaks it into
the next Silence as an earcon and one sentence. Changes that arrive together
merge ("two agents blocked: …"). Muted Buckets raise nothing.

## Summaries

The hub summarises agent output into one or two sentences before it reaches
the phone ([ADR-0007](./adr/0007-summariser-on-the-hub.md)). Backends: `claude
-p` by default, and pi or Ollama as options. Secrets are redacted before the
model sees the text and again before it is spoken. When the summariser fails
or times out, the phone gets herdr's raw state and the last line.

## The settings screen

Phase 0's tunables were adb-only (findings, point 8). Strangers cannot use
adb. v0.1 adds one screen with:

- trailing silence;
- focus timeout;
- hub pairing (scan, forget);
- Buckets and their aliases, with each one's recognisability result.

## Phase 0 findings, settled

| Finding | In v0.1 |
| --- | --- |
| 1. The car brings echo cancellation | Noted; Barge-in stays out |
| 2. The phone mic in a moving car barely works | The README says plainly: best over the car's hands-free |
| 3. Two transcripts | Corpus upload now; re-transcription in the Corpus hub phase |
| 4. Bucket names are a recognition problem | The recognisability check at pairing and in settings |
| 5. Declaration does not survive a pause | Focus: a lone alias focuses the Bucket |
| 6. The Corpus count needs a definition | Corpus hub phase |
| 7. Egress | Corpus upload over the hub connection replaces folder sync |
| 8. Tunables are adb-only | The settings screen |
| 9. Play Protect | Signed release builds |

## Open questions

- ~~The Corpus format for Drafts.~~ Settled in #45, additive to `schema: 1`
  (`kind: "draft"`, and a `draft` block on its commands), like Focus
  changes and Notes filed by Focus (#40) and `request` commands (#43):
  `docs/phase0.md`, "Corpus format".
- ~~How the hub detects "blocked" from herdr alone, without hooks.~~ Settled
  in #43: herdr's own agent detection state (`agent_status: "blocked"` in
  `herdr api snapshot`), polled every 2 s. herdr does not say how long a
  state has lasted, so the hub times it from when it saw the state begin.
- The focus timeout's real value, and whether a Draft should extend it.
- ~~The model download host and its integrity check.~~ Settled in #50: the
  upstream sherpa-onnx GitHub releases, each download and each extracted
  file checked against a SHA-256 pinned in the app.
