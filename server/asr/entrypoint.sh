#!/bin/sh
# Fetch the pinned parakeet model into the models volume on first start.
set -eu

MODEL_NAME="sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2"
MODEL_SHA256="5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"
MODELS_ROOT="${MODELS_ROOT:-/models}"
MODEL_DIR="${MODELS_ROOT}/${MODEL_NAME}"

if [ ! -f "${MODEL_DIR}/encoder.int8.onnx" ]; then
    echo "downloading ${MODEL_NAME}..."
    mkdir -p "${MODELS_ROOT}"
    tarball="${MODELS_ROOT}/${MODEL_NAME}.tar.bz2"
    curl -fL -o "${tarball}" "${MODEL_URL}"
    echo "${MODEL_SHA256}  ${tarball}" | sha256sum -c -
    tar -xjf "${tarball}" -C "${MODELS_ROOT}"
    rm -f "${tarball}"
fi

export MODEL_DIR
exec uvicorn app:app --host 0.0.0.0 --port 8000
