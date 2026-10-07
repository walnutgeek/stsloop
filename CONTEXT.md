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

**Lane** _(provisional name — not yet grilled)_:
A named source or sink of Turns, plugged into the loop. A Lane is either
Inbound or Outbound. Examples: podcast, agent status, browser page, note
capture.
_Avoid_: channel, plugin, connector, integration, skill

**Inbound**:
A Lane direction in which something outside the person competes for their
attention. Inbound Lanes produce items for the Attention Queue.

**Outbound**:
A Lane direction in which the person emits something — a note, a command, a
prompt bound for an agent. Outbound Turns are classified, not queued.

**Attention Queue**:
The ordered set of Inbound items awaiting a machine Turn. Drains into Silence.
_Avoid_: playlist, inbox, feed, backlog

**Item**:
One unit of Inbound content occupying a position in the Attention Queue — a
podcast segment, an agent state change, a page summary, an approval request.
_Avoid_: content unit, event, notification

### Capture

**Bucket**:
A named topic that both owns a word list used to refine transcription and
serves as the place its Notes live. One concept, two jobs.
_Avoid_: folder, tag, category, list, lexicon, destination

**Word list**:
The set of terms belonging to a Bucket, used to bias or repair transcription
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

**Declaration**:
An explicit spoken naming of a Bucket at the start or end of an utterance.
A declared Turn needs no classification and yields free ground truth.
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
