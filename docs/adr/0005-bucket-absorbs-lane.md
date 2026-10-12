---
status: accepted
---

# Bucket absorbs Lane: Providers publish Buckets, and the phone keeps a Focus

The earlier vision had two concepts: a **Lane**, a source or sink of Turns
plugged into the loop (podcast, agent status, capture), and a **Bucket**, a
passive topic owning a Word list and its Notes. Grilling the product after
Phase 0 found that what the person talks *to* is the same thing in both: an
errands list, an agent session, later a podcast show. We therefore merge them.
A **Bucket** is anything addressable by name, with aliases, a type, declared
commands and optional Inbound Items. A **Provider** publishes a set of Buckets
and is the plug-in boundary that Lane used to be. Lane is retired as a term.

The phone holds a **Focus**, the current Bucket. Naming a Bucket on its own
focuses it; later Turns are read against its commands. Focus is sticky with a
timeout back to an **Inbox** capture Bucket, and every change of Focus is
spoken.

## Considered options

- **Keep Bucket and Lane separate.** Buckets stay topics for Notes, and Lanes
  own commands. Rejected: an agent session would then be both a Lane and,
  for its Notes, a Bucket, and the person would have to know which word
  applies to what they are talking to.
- **A third word** (Context, Thread, Room, Target). Rejected for now. The
  person speaks a Bucket's own name, almost never the concept, and renaming
  costs churn in `:core` and the docs. "Context" also collides with an agent's
  context window. The name can change in the launch copy without changing the
  model.
- **Focus without a timeout.** Rejected: you forget you are focused on an
  agent, and a grocery note becomes a Draft. **Per-Turn Declaration only**
  (Phase 0) was rejected because a natural pause after the Bucket name lost
  the label (Phase 0 findings, point 5).

## Consequences

- Principle 2 ("no modes") becomes "Focus is the one mode, and it is always
  spoken". "Where am I" says the focused Bucket.
- The phone does not run Provider code. It knows fixed Primitives (`note`,
  `draft`, `speak_item`, `request`) and Descriptors compose them; see
  [ADR-0006](./0006-protocol-contract-and-versioning.md).
- A Bucket no longer necessarily owns a Word list. Capture Buckets may; agent
  Buckets have commands instead.
- Every Bucket name and command phrase becomes a recognition risk, so each is
  checked by a TTS round trip into the recogniser when it is registered.
- [ADR-0001](./0001-phone-owns-the-loop.md) holds with "Lane" read as
  "Provider": the hub hosts Providers and is optional.
