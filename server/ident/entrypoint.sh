#!/bin/sh
# Fetch the pinned speaker model and warm the insightface pack on first start.
set -eu

MODELS_ROOT="${MODELS_ROOT:-/models}"
SPEAKER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM%2B%2B.onnx"
SPEAKER_SHA256="c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef"
SPEAKER_FILE="${MODELS_ROOT}/wespeaker_en_voxceleb_CAM++.onnx"

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
