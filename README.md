# stsloop

An audio-first personal context hub: a continuous speech-to-speech loop that
mediates between you and the streams of information, attention demands, and
capture surfaces in your life — hands-free and eyes-free.

Nothing is built yet. These are the design documents.

| Document | What it is |
| --- | --- |
| [`CONTEXT.md`](./CONTEXT.md) | Glossary. The project's vocabulary, and the words it deliberately avoids |
| [`docs/vision.md`](./docs/vision.md) | Long-term vision: the spine, lanes, the control-surface ladder, six phases |
| [`docs/mvp.md`](./docs/mvp.md) | Phase 0 spec: the loop, phrase grammar, corpus format, done-criteria |
| [`docs/adr/`](./docs/adr/) | Architectural decisions and the alternatives rejected |
| [`docs/research/`](./docs/research/) | Primary-source verification of load-bearing technical claims |
| [`docs/brainstorm/`](./docs/brainstorm/) | The original exploratory session. **Inputs, not requirements** |

## In one page

The spine is a **bidirectional turn loop**. Everything is a Turn — one handover
of the floor, in either direction — and capabilities attach as Lanes that are
either Inbound (something competes for your attention) or Outbound (you emit a
note, a command, a prompt).

**Silence is the only scheduler.** A short silence closes your turn; a longer
one grants the machine a turn and drains the attention queue. No wake word, no
button, no modes.

**The phone owns the loop.** Transcription, synthesis, turn-taking and storage
all run on-device and work with no network. The Linux hub is one optional Lane.
The use case is driving, where connectivity gaps are normal
([ADR-0001](./docs/adr/0001-phone-owns-the-loop.md)).

**Phase 0 produces a corpus, not a product.** The loop records, transcribes,
echoes and stores everything you say, assigning a Bucket when you name one
aloud. Because a named Bucket is known with certainty, every declaration is a
free labelled training example — the system bootstraps the classifier that
eventually makes declaring optional.
