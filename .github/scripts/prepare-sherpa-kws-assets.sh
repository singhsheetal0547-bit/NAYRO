#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "$0")/../.." && pwd)}"
ASSET_DIR="$ROOT/app/src/main/assets/kws-model"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
ARCHIVE="$WORK_DIR/gigaspeech-kws.tar.bz2"

mkdir -p "$ASSET_DIR"
find "$ASSET_DIR" -maxdepth 1 -type f -delete

echo "Downloading official Sherpa-ONNX GigaSpeech Zipformer2 KWS model..."
curl --fail --location --retry 4 --retry-delay 2 "$MODEL_URL" --output "$ARCHIVE"
tar -xjf "$ARCHIVE" -C "$WORK_DIR"

ENCODER_NAME="encoder-epoch-12-avg-2-chunk-16-left-64.onnx"
DECODER_NAME="decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
JOINER_NAME="joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
MODEL_DIR="$(find "$WORK_DIR" -type f -name "$ENCODER_NAME" -print -quit | xargs -r dirname)"

if [[ -z "$MODEL_DIR" || ! -s "$MODEL_DIR/$DECODER_NAME" || ! -s "$MODEL_DIR/$JOINER_NAME" || ! -s "$MODEL_DIR/tokens.txt" || ! -s "$MODEL_DIR/bpe.model" ]]; then
  echo "ERROR: Official Sherpa archive is missing a required model or tokenizer file." >&2
  exit 1
fi

# Ship the three matching full-precision Zipformer2 transducer weights and tokenizer.
cp "$MODEL_DIR/$ENCODER_NAME" "$ASSET_DIR/"
cp "$MODEL_DIR/$DECODER_NAME" "$ASSET_DIR/"
cp "$MODEL_DIR/$JOINER_NAME" "$ASSET_DIR/"
cp "$MODEL_DIR/tokens.txt" "$ASSET_DIR/"

cat > "$WORK_DIR/keywords_raw.txt" <<'KEYWORDS'
HI BABU :2.0 #0.25 @HI_BABU
HEY BABU :2.0 #0.25 @HEY_BABU
HI NAYRO :2.0 #0.25 @HI_NAYRO
HEY NAYRO :2.0 #0.25 @HEY_NAYRO
KEYWORDS

echo "Generating BPE keyword tokens with the official sherpa-onnx-cli..."
sherpa-onnx-cli text2token \
  --tokens "$ASSET_DIR/tokens.txt" \
  --tokens-type bpe \
  --bpe-model "$MODEL_DIR/bpe.model" \
  "$WORK_DIR/keywords_raw.txt" \
  "$ASSET_DIR/keywords.txt"

if [[ -f "$MODEL_DIR/README.md" ]]; then
  cp "$MODEL_DIR/README.md" "$ASSET_DIR/MODEL_README.md"
fi

for file in "$ENCODER_NAME" "$DECODER_NAME" "$JOINER_NAME" "tokens.txt" "keywords.txt"; do
  if [[ ! -s "$ASSET_DIR/$file" ]]; then
    echo "ERROR: required Sherpa asset missing after preparation: $file" >&2
    exit 1
  fi
done

echo "Sherpa KWS assets prepared:"
ls -lh "$ASSET_DIR"
sha256sum "$ASSET_DIR/$ENCODER_NAME" "$ASSET_DIR/$DECODER_NAME" "$ASSET_DIR/$JOINER_NAME" "$ASSET_DIR/tokens.txt" "$ASSET_DIR/keywords.txt"
