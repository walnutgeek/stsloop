---
status: accepted
---

# The hub is Python, installed with uv

The hub serves one WebSocket on the tailnet, talks to herdr's socket API,
takes local HTTP calls from Claude Code hooks, shells out to a summariser,
stores the Corpus, and later re-transcribes it with the best offline model. We
write it in Python and ship it as `uv tool install stsloop-hub`.

## Considered options

- **Kotlin/JVM, sharing `:core`.** Its main argument was preventing phone/hub
  drift, which the fixtures of
  [ADR-0006](./0006-protocol-contract-and-versioning.md) now do without a
  shared language. It would also ask every user to install a JVM.
- **Go or Rust.** A single static binary is the nicest install, but
  re-transcription (NeMo, Parakeet, sherpa-onnx's Python API) would have to
  run as a subprocess, and iteration is slower.
- **Python.** The repo already uses it with uv (`scripts/corpus_load.py`); it
  has the best ASR and model tooling for the Corpus phase; and uv solves the
  packaging problem that used to count against it.

## Consequences

- The hub's correctness against the phone rests on the shared fixtures, so the
  fixtures must cover every message the hub sends or receives.
- If distribution or start-up time hurts later, the hub is small enough to
  rewrite against the same fixtures.
