#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?Usage: ensure-sherpa-aar.sh <target-aar-path>}"
EXPECTED_SHA256="4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar"

mkdir -p "$(dirname "$TARGET")"

if [[ -s "$TARGET" ]]; then
  echo "$EXPECTED_SHA256  $TARGET" | sha256sum --check -
  exit 0
fi

TMP="$TARGET.download"
trap 'rm -f "$TMP"' EXIT
curl --fail --location --retry 4 --retry-delay 2 "$URL" --output "$TMP"
echo "$EXPECTED_SHA256  $TMP" | sha256sum --check -
mv "$TMP" "$TARGET"
test -s "$TARGET"
echo "Verified official Sherpa-ONNX AAR at $TARGET"
