#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="2.0.3"
AAR_NAME="sherpa-onnx-${VERSION}.aar"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${AAR_NAME}"
EXPECTED_SHA256="4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
DEST_DIR="${ROOT_DIR}/app/libs"
DEST="${DEST_DIR}/${AAR_NAME}"
TMP="${DEST}.download"

for tool in curl sha256sum unzip jar; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool is required." >&2; exit 1; }
done
mkdir -p "${DEST_DIR}"

verify_aar() {
  local file="$1"
  echo "${EXPECTED_SHA256}  ${file}" | sha256sum --check --status
  unzip -t "${file}" >/dev/null
  local abi
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    unzip -Z1 "${file}" | grep -Fxq "jni/${abi}/libsherpa-onnx-jni.so"
    unzip -Z1 "${file}" | grep -Fxq "jni/${abi}/libonnxruntime.so"
  done
  local nested_jar="${file}.classes.jar"
  unzip -p "${file}" classes.jar > "${nested_jar}"
  for class_file in \
    "com/k2fsa/sherpa/onnx/KeywordSpotter.class" \
    "com/k2fsa/sherpa/onnx/KeywordSpotterConfig.class" \
    "com/k2fsa/sherpa/onnx/OnlineStream.class" \
    "com/k2fsa/sherpa/onnx/FeatureConfig.class"; do
    jar tf "${nested_jar}" | grep -Fxq "${class_file}"
  done
  rm -f "${nested_jar}"
}

if [[ -s "${DEST}" ]] && verify_aar "${DEST}"; then
  echo "Verified cached Sherpa-ONNX Android AAR: ${DEST}"
  exit 0
fi
rm -f "${TMP}" "${TMP}.classes.jar"
trap 'rm -f "${TMP}" "${TMP}.classes.jar"' EXIT
echo "Downloading official sherpa-onnx v${VERSION} Android AAR..."
curl --fail --location --retry 3 --retry-delay 2 --output "${TMP}" "${AAR_URL}"
verify_aar "${TMP}"
mv "${TMP}" "${DEST}"
echo "Installed and verified official Sherpa-ONNX AAR: ${DEST}"
