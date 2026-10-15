# stsloop

An audio-first personal context hub. stsloop is a continuous speech-to-speech
loop that mediates between a person and the streams of information, attention
demands, and capture surfaces in their life — primarily hands-free and
eyes-free.

## Language

### The loop

**Turn**:
One complete handover of the floor, either human-to-machine or
machine-to-human. The atomic unit of a Session.
_Avoid_: utterance (that's only the audio of a human turn), exchange, message

**Session**:
A bounded period during which the loop is active and the microphone is
claimed. Begins by explicit user action, ends by explicit user action.
_Avoid_: conversation, call, chat

**Silence**:
An observed gap in human speech past a threshold. Silence both closes a human
Turn and grants the machine permission to take one.
_Avoid_: pause, timeout, idle

**Half-duplex**:
The property that the loop never listens and speaks at the same instant. The
microphone is closed for the duration of any machine Turn plus a guard
interval.

**Barge-in**:
A human Turn that pre-empts a machine Turn already in progress. Not available
while the loop is strictly Half-duplex.
_Avoid_: interruption (overloaded — the OS also "interrupts" audio sessions)

### What flows through it

**Bucket**:
Anything the person can address by name: a capture topic, an agent session,
later a podcast show. It has a name, aliases, a type, declared commands and,
optionally, Inbound Items it can raise. A capture Bucket also owns a Word list
and is where its Notes live. Absorbed the former Lane (ADR-0005).
_Avoid_: folder, tag, category, list, destination, lane, channel, context

**Provider**:
What publishes a set of Buckets and answers their commands: capture on the
phone; herdr and Claude Code hooks on the hub; later podcast. The plug-in
boundary.
_Avoid_: lane (retired), plugin, connector, integration, skill

**Descriptor**:
A Provider's description of one Bucket for the phone: name, aliases, type,
commands and the Primitives they use. The phone caches Descriptors so Buckets
resolve with the hub offline.
_Avoid_: manifest, schema

**Primitive**:
One of the fixed behaviours the phone knows how to run for a Bucket: `note`,
`draft`, `speak_item`, `request`. Descriptors compose Primitives; the phone
never runs Provider code.
_Avoid_: capability, mixin (deferred idea, not yet a thing)

**Focus**:
The current Bucket. Naming a Bucket on its own sets it; later Turns are read
against its commands. Sticky with a timeout back to the Inbox, and spoken on
every change. The one mode the loop has.
_Avoid_: mode, context, selection

**Inbox**:
The capture Bucket Focus falls back to. A Note in the Inbox is unlabelled.
_Avoid_: default bucket, unsorted

**Draft**:
The text accumulating for an agent Bucket under Focus, Turn by Turn. Read back
on request and always before it is sent; kept, never sent, when Focus times
out.
_Avoid_: prompt (that is what it becomes once sent), message

**Answer**:
The reply to an agent's question dialog, put together by voice a question
at a time (an option, or the Draft as its own answer), read back, and sent
like a Draft. Permission prompts are never answered.
_Avoid_: response, approval (that is rung 5)

**Hub**:
The person's own machine running `stsloop-hub`, where the hub-side Providers,
the summariser and the Corpus store live. Optional: the loop runs without it.
_Avoid_: server, backend, cloud

**Capability**:
A protocol feature the phone and the hub agree to use, named in the handshake
(`descriptors`, `corpus` and `summaries` in v1). Not a Primitive: a Capability is about the connection,
a Primitive about what the phone does for a Bucket.
_Avoid_: feature flag

**Inbound**:
The direction in which something outside the person competes for their
attention. Inbound Buckets raise Items for the Attention Queue.

**Outbound**:
The direction in which the person emits something: a Note, a command, a Draft
bound for an agent. Outbound Turns are classified, not queued.

**Attention Queue**:
The ordered set of Inbound items awaiting a machine Turn. Drains into Silence.
_Avoid_: playlist, inbox, feed, backlog

**Item**:
One unit of Inbound content occupying a position in the Attention Queue — a
podcast segment, an agent state change, a page summary, an approval request.
_Avoid_: content unit, event, notification

### Capture

**Word list**:
The set of terms belonging to a capture Bucket, used to bias or repair transcription
of a Turn assigned to that Bucket.
_Avoid_: dictionary (overloaded with the data-structure sense), vocabulary

**Note**:
A durable record produced by an Outbound Turn the person meant to keep,
assigned to exactly one Bucket.
_Avoid_: memo, capture, entry, item (Item is reserved for the Attention Queue)

**Refinement**:
The act of improving a transcription using the Word list of the Bucket the
Turn was assigned to. Produces a corrected transcript alongside the original.
Deferred: the loop retains the Recording so Refinement can be developed later
against a real Corpus rather than designed up front.
_Avoid_: correction, post-processing, cleanup

**Recording**:
The retained audio of a human Turn, kept alongside its transcript so that
transcription and classification can be re-run later with better models.
_Avoid_: clip, sample, buffer

**Corpus**:
The accumulated set of Recordings paired with their transcripts and resolved
Buckets. The primary output of early stsloop, and the training and evaluation
material for classification.
_Avoid_: dataset, log, history

**Tombstone**:
The mark "scratch that" leaves on the previous Turn: that Turn stays in the
Corpus, audio and all, but is dropped from use. The tombstone is the command
Turn itself, which names its target; the target is never edited.
_Avoid_: delete, removal (nothing is removed)

**Declaration**:
An explicit spoken naming of a Bucket at the start or end of an utterance.
A declared Turn needs no classification and yields free ground truth. A Bucket
name said on its own is not a Declaration; it sets Focus.
_Avoid_: prefix, tag, keyword

### Consumption

**Library**:
The navigable body of everything stsloop holds that can be played back —
Notes, past Turns, Recordings, podcast segments. Skip moves through it.
_Avoid_: archive, history, catalog

**Daily brief**:
A composed sequence of Items assembled ahead of time (on the hub) and played
on request as one continuous Inbound run, rather than drained opportunistically
into Silence.
_Avoid_: digest, summary, briefing, roundup
