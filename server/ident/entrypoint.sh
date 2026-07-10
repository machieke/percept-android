#!/bin/sh
# Fetch the pinned speaker model and warm the insightface pack on first start.
set -eu

MODELS_ROOT="${MODELS_ROOT:-/models}"
SPEAKER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_large.onnx"
SPEAKER_SHA256="d51abcf31717ef28162f26acb9d44dd4127c3d44c9b8624f699f3425daca8e77"
SPEAKER_FILE="${MODELS_ROOT}/nemo_en_titanet_large.onnx"

mkdir -p "${MODELS_ROOT}"
if [ ! -f "${SPEAKER_FILE}" ]; then
    echo "downloading speaker embedding model..."
    curl -fL -o "${SPEAKER_FILE}" "${SPEAKER_URL}"
fi
echo "${SPEAKER_SHA256}  ${SPEAKER_FILE}" | sha256sum -c -

# Pre-download the insightface pack so the first request is fast.
python3 - <<'EOF'
import os
from insightface.app import FaceAnalysis
app = FaceAnalysis(
    name=os.environ.get("FACE_PACK", "buffalo_sc"),
    root=os.environ.get("FACE_ROOT", "/models/insightface"),
    providers=["CPUExecutionProvider"],
)
app.prepare(ctx_id=-1, det_size=(640, 640))
print("insightface ready")
EOF

exec uvicorn app:app --host 0.0.0.0 --port 8000
