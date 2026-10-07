# Brainstorm inputs

These documents are **inputs, not requirements.** They are the output of an
exploratory Perplexity session that kicked off the project.

The grilled decisions that supersede them live in:

- [`../vision.md`](../vision.md) — long-term vision
- [`../mvp.md`](../mvp.md) — v1 scope
- [`../../CONTEXT.md`](../../CONTEXT.md) — glossary
- [`../adr/`](../adr/) — architectural decisions

Four places where the current design deliberately departs from these docs:

| These docs say | We decided |
| --- | --- |
| Intelligence on the Linux box, phone is a thin client | Phone owns the loop; hub is an optional Lane |
| Tauri is defensible for cross-platform | Native Kotlin with a KMP-ready core; no Tauri |
| Start with system `SpeechRecognizer`, migrate to Sherpa later | Sherpa-ONNX from the start |
| Podcast queue is Phase 1 | Podcast is the last lane; daily brief comes first |
