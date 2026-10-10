#!/usr/bin/env bash
# Fetch the speech models into :audio's assets, checksum-verified, and derive
# bpe.vocab from bpe.model. Everything this writes is git-ignored.
#
#   scripts/fetch-models.sh
#
# Writes:
#   audio/src/main/assets/sherpa-onnx-streaming-zipformer-en-2023-06-26/
#       {encoder,decoder,joiner}-epoch-99-avg-1-chunk-16-left-128.int8.onnx
#       tokens.txt  bpe.vocab
#   audio/src/main/assets/silero_vad.onnx
#   audio/src/androidTest/assets/test_wavs/0.wav   (upstream test wav)
#
# Only the int8 chunk-16-left-128 subset (~70 MB) is fetched, file by file
# from a pinned Hugging Face revision, not the 296 MB tarball. Needs curl,
# sha256sum and uv (bpe.vocab export). Idempotent: verified files are kept.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${STSLOOP_CACHE:-$HOME/.cache/stsloop}"

MODEL="sherpa-onnx-streaming-zipformer-en-2023-06-26"
# k2-fsa's mirror (license: apache-2.0 in its card), pinned to a commit.
HF_REPO="csukuangfj/$MODEL"
HF_REV="672fbf1b30579d6585301139bb363f42a0ad4a24"
PREFIX="epoch-99-avg-1-chunk-16-left-128"

MAIN_ASSETS="$REPO_ROOT/audio/src/main/assets"
TEST_ASSETS="$REPO_ROOT/audio/src/androidTest/assets"

say() { printf '==> %s\n' "$*"; }
for tool in curl sha256sum uv; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 1; }
done

# fetch URL DEST SHA256 — download to DEST unless it already verifies.
fetch() {
  local url="$1" dest="$2" sha="$3"
  if [[ -f "$dest" ]] && echo "$sha  $dest" | sha256sum -c --quiet - 2>/dev/null; then
    return
  fi
  say "Fetching ${dest#"$REPO_ROOT"/}"
  mkdir -p "$(dirname "$dest")"
  curl -fsSL --retry 3 -o "$dest.part" "$url"
  if ! echo "$sha  $dest.part" | sha256sum -c --quiet -; then
    rm -f "$dest.part"
    echo "error: checksum mismatch for $url" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
}

hf() { echo "https://huggingface.co/$HF_REPO/resolve/$HF_REV/$1"; }

# Checksums: LFS files use the Hugging Face API's lfs.oid; the non-LFS
# tokens.txt and test wav were recorded on first download.
dir="$MAIN_ASSETS/$MODEL"
fetch "$(hf "encoder-$PREFIX.int8.onnx")" "$dir/encoder-$PREFIX.int8.onnx" \
  563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1
fetch "$(hf "decoder-$PREFIX.int8.onnx")" "$dir/decoder-$PREFIX.int8.onnx" \
  98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02
fetch "$(hf "joiner-$PREFIX.int8.onnx")" "$dir/joiner-$PREFIX.int8.onnx" \
  d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297
fetch "$(hf tokens.txt)" "$dir/tokens.txt" \
  49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb

# bpe.model is a build input only; the app ships the derived bpe.vocab.
bpe_model="$CACHE/$MODEL/bpe.model"
fetch "$(hf bpe.model)" "$bpe_model" \
  c53433de083c4a6ad12d034550ef22de68cec62c4f58932a7b6b8b2f1e743fa5
if [[ ! -f "$dir/bpe.vocab" || "$bpe_model" -nt "$dir/bpe.vocab" ]]; then
  say "Exporting bpe.vocab"
  uv run --quiet --script "$REPO_ROOT/scripts/export_bpe_vocab.py" "$bpe_model" "$dir/bpe.vocab.part"
  mv "$dir/bpe.vocab.part" "$dir/bpe.vocab"
fi

# Silero VAD, MIT. Checksum is the digest GitHub reports for the release asset.
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx \
  "$MAIN_ASSETS/silero_vad.onnx" \
  9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6

# Test wav for the on-device test. Its transcript is in the model's
# test_wavs/trans.txt: "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP
# HERE AND THERE THE SQUALID QUARTER OF THE BROTHELS".
fetch "$(hf test_wavs/0.wav)" "$TEST_ASSETS/test_wavs/0.wav" \
  6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f

du -sh "$dir" "$MAIN_ASSETS/silero_vad.onnx"
