---
status: accepted
---

# Streaming Zipformer *transducer* with modified_beam_search, because hotwords are transducer-only

The STT model is `sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8,
`chunk-16-left-128`), configured with `decodingMethod = "modified_beam_search"`,
`modelingUnit = "bpe"` and an exported `bpe.vocab`, from the first commit —
even though v1 does not yet use hotwords at all.

The reason is that sherpa-onnx supports hotword biasing **only on transducer
models**, and only under `modified_beam_search`. Refinement — correcting
transcription using a Bucket's Word list — is the feature that makes Buckets
more than folders, and hotwords are how it will be implemented. Choosing a CTC
model, including the otherwise-attractive Zipformer2-CTC, would permanently
foreclose it. Of the streaming English transducers the project ships, this is
the only one that includes a BPE model, which English hotword encoding
requires.

## Considered options

- **Zipformer2-CTC (streaming).** Newer and architecturally appealing.
  Rejected: no hotword support, and the failure mode for trying is a hard
  process kill, so there is no graceful degradation path.
- **A smaller 20M-parameter streaming transducer.** Attractive on footprint,
  but its released file listing ships no BPE vocabulary, so English hotwords
  cannot be encoded with it as distributed.
- **`sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06`.** Possibly the best
  option on paper — newer, smaller tarball, zipformer2 transducer — but it is
  undocumented beyond a release asset and an enum entry, with no published
  license, accuracy or footprint. Not adoptable without verification; worth
  revisiting.
- **Whisper or Moonshine.** Both support no hotwords whatsoever, so neither can
  serve Refinement. Still useful as offline accuracy benchmarks against the
  Corpus.
- **Deciding later.** Rejected because `modified_beam_search` is slower than
  the `greedy_search` configuration all published benchmarks used, so switching
  later would invalidate every performance measurement taken before the switch.
  Benchmarking in the final configuration from the start is cheaper than
  re-measuring.

## Consequences

- Performance is a genuine unknown. No published RTF figure exists for
  `modified_beam_search` on any model, and none for Pixel-class hardware for
  any sherpa-onnx streaming model. This must be measured on-device early; it is
  the one number that could force this decision to be revisited.
- The build must export `bpe.vocab` from the released `bpe.model`, since
  sherpa-onnx requires the former and the tarball ships the latter.
- There is no Maven artifact or prebuilt AAR: native libraries are NDK-built
  and vendored as `jniLibs`, against a pinned commit.
- A chokepoint is required in front of stream creation. Passing hotwords to a
  non-transducer model calls `_Exit(-1)` — an uncatchable process kill.
- The model's license is asserted only on a HuggingFace mirror (`apache-2.0`),
  not in the sherpa-onnx repo or docs, and the upstream icefall repo carries no
  license. Harmless for personal use; resolve before any public release.
