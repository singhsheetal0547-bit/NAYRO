#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="${ROOT_DIR}/app"
KWS_DIR="${APP_DIR}/src/main/assets/kws-model"

# This is the single source of truth for the pinned AAR checksum, official API classes,
# JNI libraries and supported ABIs. It also downloads the AAR if a local checkout lacks it.
bash "${ROOT_DIR}/scripts/prepare-sherpa-onnx-android.sh"

for model_file in \
  "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
  "decoder-epoch-12-avg-2-chunk-16-left-64.onnx" \
  "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
  "tokens.txt" \
  "keywords.txt"; do
  file="${KWS_DIR}/${model_file}"
  if [[ ! -s "${file}" ]]; then
    echo "ERROR: Missing or empty Sherpa-ONNX KWS asset: ${file}" >&2
    exit 1
  fi
  echo "Verified KWS model asset: ${model_file}"
done

echo "Sherpa-ONNX v2.0.3 runtime and all KWS model assets verified."
