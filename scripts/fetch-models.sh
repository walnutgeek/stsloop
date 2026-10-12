#!/usr/bin/env bash
# Fetch the speech models into :audio's debug assets, checksum-verified, and
# derive bpe.vocab from bpe.model. Everything this writes is git-ignored.
#
#   scripts/fetch-models.sh
#
# Writes:
#   audio/src/debug/assets/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25/
#       {encoder,decoder,joiner}.int8.onnx  tokens.txt          (the app's STT, ~662 MB)
#   audio/src/debug/assets/silero_vad.onnx
#   audio/src/androidTest/assets/sherpa-onnx-streaming-zipformer-en-2023-06-26/
#       {encoder,decoder,joiner}-epoch-99-avg-1-chunk-16-left-128.int8.onnx
#       tokens.txt  bpe.vocab                                   (test APK only, ~70 MB)
#   audio/src/androidTest/assets/test_wavs/{0,1}.wav            (upstream test wavs)
#
# Only debug builds (and the device tests) bundle the models. A release build
# bundles none and downloads the same pinned files on first run (#50); the pins
# below must match core's SpeechManifest, which SpeechManifestTest checks.
#
# The STT model comes from sherpa-onnx's release tarball (464 MB, kept in the
# cache), of which only the int8 model files are extracted. The previous model,
# a zipformer, is now only a test baseline and the one hotwords-capable model
# for the device tests (#30); its int8 chunk-16-left-128 subset is fetched file
# by file from a pinned Hugging Face revision. Needs curl, sha256sum, tar with
# bzip2, and uv (bpe.vocab export). Idempotent: verified files are kept.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${STSLOOP_CACHE:-$HOME/.cache/stsloop}"

# NVIDIA's nemotron-speech-streaming-en-0.6b (NVIDIA Open Model License),
# exported by k2-fsa. Release asset checksum: the digest GitHub reports for it.
STT_MODEL="sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"
STT_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$STT_MODEL.tar.bz2"
STT_SHA256="78e2b79fcf7271553a74402a76b771b09ea40117a39566a79f52235b23db6358"

ZIPFORMER="sherpa-onnx-streaming-zipformer-en-2023-06-26"
# k2-fsa's mirror (license: apache-2.0 in its card), pinned to a commit.
HF_REPO="csukuangfj/$ZIPFORMER"
HF_REV="672fbf1b30579d6585301139bb363f42a0ad4a24"
PREFIX="epoch-99-avg-1-chunk-16-left-128"

MAIN_ASSETS="$REPO_ROOT/audio/src/debug/assets"
TEST_ASSETS="$REPO_ROOT/audio/src/androidTest/assets"
# Before #50 the models were in main/assets, which a release build bundles too.
OLD_ASSETS="$REPO_ROOT/audio/src/main/assets"

say() { printf '==> %s\n' "$*"; }
for tool in curl sha256sum uv tar bzip2; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 1; }
done

verified() { [[ -f "$1" ]] && echo "$2  $1" | sha256sum -c --quiet - 2>/dev/null; }

# Move a checkout's models from the old location rather than fetch them again.
if [[ -d "$OLD_ASSETS" ]]; then
  say "Moving models from ${OLD_ASSETS#"$REPO_ROOT"/} to ${MAIN_ASSETS#"$REPO_ROOT"/}"
  mkdir -p "$MAIN_ASSETS"
  for old in "$OLD_ASSETS"/* "$OLD_ASSETS"/.[!.]*; do
    [[ -e "$old" ]] || continue
    rm -rf "${MAIN_ASSETS:?}/$(basename "$old")"
    mv "$old" "$MAIN_ASSETS/"
  done
  rmdir "$OLD_ASSETS"
fi

# fetch URL DEST SHA256 — download to DEST unless it already verifies.
fetch() {
  local url="$1" dest="$2" sha="$3"
  verified "$dest" "$sha" && return
  say "Fetching ${dest#"$REPO_ROOT"/}"
  mkdir -p "$(dirname "$dest")"
  curl -fSL --retry 3 -o "$dest.part" "$url"
  if ! echo "$sha  $dest.part" | sha256sum -c --quiet -; then
    rm -f "$dest.part"
    echo "error: checksum mismatch for $url" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
}

# --- STT: nemotron, from the pinned release tarball --------------------------
# Each extracted file is pinned too (recorded from the verified tarball), so a
# half-written asset is re-extracted rather than shipped.
STT_FILES=(
  "encoder.int8.onnx 7d932213491ad355c6e5576705dc3494731a52af87d7a1b954559340147909d8"
  "decoder.int8.onnx 0be9702c2f427a2b6bb241d298e0d3836a558de1f5b9fd3018f1cce6e2b3fa98"
  "joiner.int8.onnx a35eac38a22ebceb04d230ed7afe0d68f446ba6914a036b97f14fece95967e23"
  "tokens.txt dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"
)
dir="$MAIN_ASSETS/$STT_MODEL"
missing=()
for entry in "${STT_FILES[@]}"; do
  read -r name sha <<<"$entry"
  verified "$dir/$name" "$sha" || missing+=("$STT_MODEL/$name")
done
if ((${#missing[@]})); then
  tarball="$CACHE/$STT_MODEL.tar.bz2"
  fetch "$STT_URL" "$tarball" "$STT_SHA256"
  say "Extracting ${#missing[@]} file(s) of $STT_MODEL"
  # On the cache's disk, not /tmp (often RAM): the encoder alone is 653 MB.
  tmp="$(mktemp -d "$CACHE/extract.XXXXXX")"
  trap 'rm -rf "$tmp"' EXIT
  tar -xjf "$tarball" -C "$tmp" "${missing[@]}"
  mkdir -p "$dir"
  for entry in "${STT_FILES[@]}"; do
    read -r name sha <<<"$entry"
    [[ -f "$tmp/$STT_MODEL/$name" ]] || continue
    if ! verified "$tmp/$STT_MODEL/$name" "$sha"; then
      echo "error: checksum mismatch for $name in $tarball" >&2
      exit 1
    fi
    mv "$tmp/$STT_MODEL/$name" "$dir/$name"
  done
  for entry in "${STT_FILES[@]}"; do
    read -r name sha <<<"$entry"
    verified "$dir/$name" "$sha" || { echo "error: $name missing from $tarball" >&2; exit 1; }
  done
fi

# The zipformer used to ship in the app; a stale copy would add 70 MB to it.
rm -rf "${MAIN_ASSETS:?}/$ZIPFORMER"

# --- Test-only baseline: the zipformer subset, file by file ------------------
hf() { echo "https://huggingface.co/$HF_REPO/resolve/$HF_REV/$1"; }

# Checksums: LFS files use the Hugging Face API's lfs.oid; the non-LFS
# tokens.txt and test wav were recorded on first download.
zdir="$TEST_ASSETS/$ZIPFORMER"
fetch "$(hf "encoder-$PREFIX.int8.onnx")" "$zdir/encoder-$PREFIX.int8.onnx" \
  563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1
fetch "$(hf "decoder-$PREFIX.int8.onnx")" "$zdir/decoder-$PREFIX.int8.onnx" \
  98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02
fetch "$(hf "joiner-$PREFIX.int8.onnx")" "$zdir/joiner-$PREFIX.int8.onnx" \
  d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297
fetch "$(hf tokens.txt)" "$zdir/tokens.txt" \
  49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb

# bpe.model is a build input only; the tests ship the derived bpe.vocab.
bpe_model="$CACHE/$ZIPFORMER/bpe.model"
fetch "$(hf bpe.model)" "$bpe_model" \
  c53433de083c4a6ad12d034550ef22de68cec62c4f58932a7b6b8b2f1e743fa5
if [[ ! -f "$zdir/bpe.vocab" || "$bpe_model" -nt "$zdir/bpe.vocab" ]]; then
  say "Exporting bpe.vocab"
  uv run --quiet --script "$REPO_ROOT/scripts/export_bpe_vocab.py" "$bpe_model" "$zdir/bpe.vocab.part"
  mv "$zdir/bpe.vocab.part" "$zdir/bpe.vocab"
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
# A second, longer utterance (16.7 s) for the segmentation test, which splices
# 0.wav and 1.wav between stretches of silence. Checksum recorded on first download.
fetch "$(hf test_wavs/1.wav)" "$TEST_ASSETS/test_wavs/1.wav" \
  5143a6ba93c4b274e2c4ac22deb75c2c48936c853f0519add1de828b6c79cc5a

du -sh "$dir" "$zdir" "$MAIN_ASSETS/silero_vad.onnx"
