---
status: accepted
---

# The phone owns the loop; the Linux hub is a Lane

stsloop is used primarily while driving, where tunnels, dead zones and network
handoffs are normal conditions rather than edge cases. We therefore make the
phone authoritative for the entire loop — voice activity detection,
transcription, speech synthesis, turn-taking, the Attention Queue and the
Corpus all run on-device and work with no network at all — and treat the Linux
hub as one optional Lane that contributes Items when reachable and goes quiet
when not.

## Considered options

The obvious alternative, and the one the project's initial brainstorm
recommended, was the reverse: a thin phone client owning only the microphone,
speaker and Bluetooth routing, with audio carried over WebRTC to a Linux host
running STT, LLM and TTS and holding all state. That design has real
advantages — larger models, one place to iterate, no mobile model packaging —
and we rejected it for two reasons.

First, it makes connectivity a hard dependency for the core interaction. The
brainstorm itself proposed, as its Phase 0 success criterion, that
"reconnecting after a tunnel/network change does not lose playback position" —
treating as a reconnection bug what is actually a verdict on the architecture.
A loop whose turn-taking requires a round trip to the user's house is broken
precisely when it is being used.

Second, Silence grants the machine a Turn, and the machine must be able to
take that Turn within a few hundred milliseconds to feel like a loop rather
than a query interface. That is a local decision; it cannot be a network round
trip.

A third option — running locally but escalating low-confidence turns to the
hub — remains available later, and this decision does not foreclose it. It was
rejected for v1 only because it means building and keeping consistent two
inference paths before either is proven.

## Consequences

- Models must be packaged on-device, with the APK size and update costs that
  implies.
- Model quality is bounded by what a phone can run. Anything needing a large
  model becomes a hub Lane feature and must degrade when offline.
- Every Lane must define its offline behaviour. "Goes quiet" is the default
  and must be genuinely silent, not an error read aloud.
- The phone holds the Corpus first, making it the most loseable copy. Egress
  to the hub is therefore a reliability requirement, not a convenience.
