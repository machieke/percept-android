"""Percept identity embeddings: voice and face vectors for clustering.

Stateless by design — this service only turns audio slices and keyframes
into embedding vectors. The memory server owns clustering, the persistent
identity registry, and the resulting trace events, so the biometric
templates live in exactly one place.
"""

import os
import subprocess
import threading

import numpy as np
import sherpa_onnx
from fastapi import FastAPI, HTTPException, Request

SPEAKER_MODEL = os.environ.get(
    "SPEAKER_MODEL", "/models/wespeaker_en_voxceleb_CAM++.onnx"
)
SPEAKER_RUN_ID = "wespeaker-cam++-voxceleb@sherpa-onnx-1.13.3"
FACE_RUN_ID = "buffalo_sc@insightface-0.7"

speaker_extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
    sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=SPEAKER_MODEL,
        num_threads=int(os.environ.get("IDENT_THREADS", "2")),
    )
)
speaker_lock = threading.Lock()

# insightface loads lazily: model pack download happens on first start via
# the entrypoint warm-up so requests never pay it.
_face_app = None
_face_lock = threading.Lock()


def face_app():
    global _face_app
    with _face_lock:
        if _face_app is None:
            from insightface.app import FaceAnalysis

            app_ = FaceAnalysis(
                name=os.environ.get("FACE_PACK", "buffalo_sc"),
                root=os.environ.get("FACE_ROOT", "/models/insightface"),
                providers=["CPUExecutionProvider"],
            )
            app_.prepare(ctx_id=-1, det_size=(640, 640))
            _face_app = app_
    return _face_app


app = FastAPI(title="percept-ident")


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "speakerRunId": SPEAKER_RUN_ID, "faceRunId": FACE_RUN_ID}


@app.post("/embed-speaker")
async def embed_speaker(request: Request, startMs: int = 0, endMs: int = 0) -> dict:
    """Container audio in the body; optional [startMs, endMs) slice.
    Returns a unit-normalized voice embedding."""
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    command = ["ffmpeg", "-v", "error", "-i", "pipe:0"]
    if endMs > startMs:
        command += ["-ss", f"{startMs / 1000:.3f}", "-t", f"{(endMs - startMs) / 1000:.3f}"]
    command += ["-f", "f32le", "-ac", "1", "-ar", "16000", "pipe:1"]
    proc = subprocess.run(command, input=body, capture_output=True)
    if proc.returncode != 0 or not proc.stdout:
        detail = proc.stderr.decode("utf-8", "replace").strip()[:200] or "unknown"
        raise HTTPException(status_code=400, detail=f"cannot decode audio: {detail}")
    samples = np.frombuffer(proc.stdout, dtype=np.float32)
    if len(samples) < 8000:  # < 0.5 s of speech gives junk embeddings
        raise HTTPException(status_code=422, detail="audio slice too short")

    with speaker_lock:
        stream = speaker_extractor.create_stream()
        stream.accept_waveform(16000, samples)
        stream.input_finished()
        embedding = np.array(speaker_extractor.compute(stream), dtype=np.float32)
    norm = float(np.linalg.norm(embedding))
    if norm > 0:
        embedding = embedding / norm
    return {
        "embedding": embedding.tolist(),
        "durationMs": int(len(samples) / 16),
        "modelRunId": SPEAKER_RUN_ID,
    }


@app.post("/embed-faces")
async def embed_faces(request: Request) -> dict:
    """JPEG body → detected faces with unit-normalized embeddings."""
    import cv2

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    image = cv2.imdecode(np.frombuffer(body, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="cannot decode image")
    faces = []
    for face in face_app().get(image):
        embedding = np.asarray(face.normed_embedding, dtype=np.float32)
        box = [int(v) for v in face.bbox]
        faces.append(
            {
                "box": box,
                "detScorePermille": int(float(face.det_score) * 1000),
                "embedding": embedding.tolist(),
            }
        )
    return {"faces": faces, "modelRunId": FACE_RUN_ID}
