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

When the phone cannot use the hub, it says why, naming the one thing to check
(#74): the hub is unreachable (Tailscale off, or `stsloop-hub serve` not
running), it does not know the phone's token, one side needs updating, or it
answered and did not finish the handshake. A dialog with Retry and Ignore
shows after a pairing whose first connection fails, and when the app or the
settings screen opens after a Session that could not reach the hub. A Session
shows no dialog: it says "can't reach the hub." once.

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

A name the recogniser cannot hear as written gets a **learned alias** (#81):
when the recognisability check hears a Bucket's name or alias as the same
other words in at least 5 of its 6 variants, the phone adds those words
("stsloop agent" heard as "STS loop agent"), unless they are already another
Bucket's phrase or any command phrase. The loop says it once ("stsloop agent
also answers to STS loop agent."). Settings shows learned aliases apart, each
removable; a removed one is not learned again for that phrase and recogniser
model. A capture Bucket's are kept under `learned` in its `buckets.json`
entry, a hub Bucket's under `learned` in `files/hub/aliases.json`.

Settled in #47: each hook posts its event to the hub on loopback with the
session's herdr pane (`$HERDR_PANE_ID`) and Claude Code pid, so a session in
a herdr pane is that pane's Bucket (`herdr:<pane>`), and one outside herdr is
`claude-code:<session id>`, whose "send it" is a `request` that says sending
needs herdr. Where the two disagree, the state that began later wins. The
hooks also tell why an agent is blocked (a question dialog or a permission
prompt), which "status" says and the hub keeps for a later rung.

### Commands

Commands are a deterministic grammar declared in the Descriptor, with at most
one free-text slot each, as the payload.

**Every primary phrase must score 6/6 on the dev phone.** A command's first
phrase, and a Bucket's name, is its primary: the one the docs and every
spoken prompt teach ("say send it"). It must be heard as itself in all six
variants of `NameCheckDeviceTest` (two voices × three speech rates, the
shipped recogniser) on the dev phone, because the grammar is exact and a
phrase missed one time in six fails one time in six. A phrase that scores
less can stay as an alias, but never as the primary; a one-word phrase is the
usual casualty, so the fix is a longer primary (#82). Results for every
phrase are in #82, #75 and #49.

Starting set for agent Buckets (primary first, then aliases):

| Phrase | Does |
| --- | --- |
| "status" | Blocked, working or done, and how long |
| "what does it say" (or "read it") | The summary of the agent's last output |
| "read it back" | Read the Draft |
| "send it" (or "send") | Read the Draft back, then send it |
| "cancel" | Drop the Draft |
| "option one" … "option four" | Pick an option of the question the agent's dialog asks next, and read it back |
| "my answer" (or "answer with my draft") | Take the Draft as the free-text answer to that question |
| "quiet" (or "mute alerts") / "unmute" (or "unmute alerts") | Mute or unmute this Bucket's alerts |

Settled in #80: an agent blocked on Claude Code's question dialog
(AskUserQuestion, known from the hooks) can be answered. "What does it
say" reads each question with its numbered options. "Option two" picks one
and the loop reads it back ("option two: Merge. say send it to answer.");
a dialog of several questions is answered in order, and after each choice
the loop reads the next question. "Send it" then reads the whole answer back
and sends it with an idempotency key, at most once, like a Draft. The hub
reads the agent's screen, checks that it shows that dialog and no permission
prompt, and types the answer with `herdr agent send-keys`, one key at a time
(a free-text answer through `herdr pane send-text`). "Cancel" drops the
answer. A permission prompt is refused ("that's a permission prompt. I don't
approve those."), and so is a dialog the hub cannot tell (herdr without
hooks, an MCP elicitation). By voice a multi-select question takes one
option, and only a one-choice question takes a free-text answer.

Loop-wide, whatever has Focus:

| Phrase | Does |
| --- | --- |
| a Bucket name on its own ("the hub", or "hub") | Focus that Bucket, and say its name |
| "where am I" (or "what bucket", "which bucket") | Say the focused Bucket |
| "what's blocked" (or "what is blocked") | List blocked agents |
| "inbox" | Focus the Inbox |
| "how is the hub" (or "hub status", "hub health") | "hub connected, 6 agents, 2 blocked."; offline, the phone alone: "hub offline for 12 minutes, last saw 6 agents." |
| "details of the hub" (or "hub details", "hub info") | Connected for how long, or offline and the last failure; agents; herdr's state; the Claude Code hooks; Turns waiting to upload |
| "summarize bucket" (or "summarize", "sum it up") | Summarise the focused capture Bucket's new Notes (see "Summaries") |

Phase 0's, also loop-wide: "scratch that" (or "discard that"), "repeat" (or
"say that again"), "stop listening" (or "end session").

On the dev phone (#82; nemotron int8, `en-US-language` and
`en-us-x-iob-local` at 0.8×, 1× and 1.25×), every primary above scores 6/6.
The aliases that do not: "send" 2/6 (four times heard as nothing), "hub"
4/6 (twice nothing), "hub status" 3/6 ("Hob status"), "hub details" 2/6
("Hug details", "Hog details") and "unmute alerts" 5/6 ("On mute alerts").
They stay, because a mishearing falls through to what any unheard phrase
does. Agent names are a separate matter (#81).

Settled in #75: "hub status" and "hub details" (`hub_status` and
`hub_details`, whose primaries became "how is the hub" and "details of the
hub" in #82) are the loop's own commands, so they are heard with no hub and
no cached Descriptors. Online,
the phone asks the hub (`status_request`) and words its `status_report`
itself; offline it answers at once from `reach.json` (since when the last
welcomed connection ended, `left_at`, and the last failure) and the cached
Descriptors. Only the phone knows how long it has been connected and how
many Turns wait to upload, so it adds those. `stsloop-hub init` tries each
herdr binary (`$HERDR_BIN_PATH`, every `herdr` on the PATH, `~/.local/bin/herdr`)
against the running herdr server and saves the first it answers in
`hub.json` for `serve`; a binary that does not match is said, at `init` and
when `serve` starts.

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

"Send it" always reads the Draft back first, then sends it with an idempotency
key. The hub delivers a key at most once, so a reconnect cannot resend. With
the hub offline, the loop says "hub offline, draft kept".

### Alerts

When an agent becomes blocked, the hub raises an Item. The phone speaks it into
the next Silence as an earcon and one sentence. Changes that arrive together
merge ("two agents blocked: …"). Muted Buckets raise nothing.

Settled in #46: the hub raises an Item when it sees an agent enter
`blocked` (not one already blocked at its first poll), merging everything
one poll sees; Items carry ids and are sent again on reconnect until the
phone says it has spoken them. "Quiet" and "unmute" are `request` commands
the hub answers for any `speak_item` Bucket, kept in `mutes.json` beside
its config. An Item waits for a longer Silence than a reply: twice
`trailing_silence_ms` after the last speech (`docs/phase0.md`, "The loop").

## Summaries

The hub summarises agent output into one or two sentences before it reaches
the phone ([ADR-0007](./adr/0007-summariser-on-the-hub.md)). Backends: `claude
-p` by default, and pi or Ollama as options. Secrets are redacted before the
model sees the text and again before it is spoken. When the summariser fails
or times out, the phone gets herdr's raw state and the last line.

**"Summarize bucket"** (#76; also "summarize", "sum it up") speaks a short
summary of the Notes said in the focused capture Bucket (the Inbox's
unlabelled Turns included) **since its last summary**; the first time, those
of the last 24 hours. Either way at most the newest 50, and tombstoned Notes
are left out.

- The phone reads the Notes from its own Corpus and sends their words with
  the request (`summarize`, with the `summaries` capability), so the newest
  are included before they are uploaded. The hub summarises them with the
  same backend, redacting as for agent summaries; when that fails it reads
  them raw.
- Under an agent Bucket's Focus the same phrases do what "what does it say"
  does (its `read` command).
- Hub offline, or no answer within 30 s: the phone answers alone, "errands:
  7 notes since last time; the latest three: …", reading them raw.
- The "since last summary" point is kept on the phone per Bucket
  (`files/summaries.json`), and moves only once a summary from the hub has
  been spoken. A raw reading does not move it, so those Notes are still in
  the next summary.
- The command Turn records the Notes it covered (`docs/phase0.md`, "Corpus
  format").

## The settings screen

Phase 0's tunables were adb-only (findings, point 8). Strangers cannot use
adb. v0.1 adds one screen with:

- trailing silence;
- focus timeout;
- hub pairing (scan, forget), and the hub line: connected, or the last
  failure and when;
- Buckets and their aliases, with each one's recognisability result, and
  their learned aliases, each removable.

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
