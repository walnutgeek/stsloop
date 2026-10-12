# Brainstorm inputs

These documents are **inputs, not requirements.** Most are the output of an
exploratory Perplexity session that kicked off the project.
[`2026-10-10-phase0-findings.md`](./2026-10-10-phase0-findings.md) is later:
what building and test-driving Phase 0 found that pushes on the vision.

The grilled decisions that supersede them live in:

- [`../vision.md`](../vision.md) — long-term vision
- [`../mvp.md`](../mvp.md) — v0.1 scope ([`../phase0.md`](../phase0.md) is the built Phase 0)
- [`../../CONTEXT.md`](../../CONTEXT.md) — glossary
- [`../adr/`](../adr/) — architectural decisions

Four places where the current design deliberately departs from these docs:

| These docs say | We decided |
| --- | --- |
| Intelligence on the Linux box, phone is a thin client | Phone owns the loop; the hub is an optional Provider of Buckets |
| Tauri is defensible for cross-platform | Native Kotlin with a KMP-ready core; no Tauri |
| Start with system `SpeechRecognizer`, migrate to Sherpa later | Sherpa-ONNX from the start |
| Podcast queue is Phase 1 | Podcast is last; supervising coding agents is the v0.1 wedge |
