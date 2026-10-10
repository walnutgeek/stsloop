#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["sentencepiece==0.2.1"]
# ///
"""Export a sentencepiece `bpe.model` to the `bpe.vocab` sherpa-onnx reads.

sherpa-onnx tokenizes hotwords with its own small sentencepiece encoder that
takes `bpe.vocab` (one `piece<TAB>score` line per id), not `bpe.model`. The
model tarball ships only `bpe.model`. Same output format as upstream
sherpa-onnx scripts/export_bpe_vocab.py.

    uv run scripts/export_bpe_vocab.py BPE_MODEL BPE_VOCAB
"""

import argparse

import sentencepiece as spm


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("bpe_model", help="input sentencepiece model")
    parser.add_argument("bpe_vocab", help="output vocabulary file")
    args = parser.parse_args()

    sp = spm.SentencePieceProcessor()
    sp.Load(args.bpe_model)
    with open(args.bpe_vocab, "w", encoding="utf-8", newline="\n") as out:
        for i in range(sp.get_piece_size()):
            out.write(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n")


if __name__ == "__main__":
    main()
