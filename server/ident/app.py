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

# titanet-large won an A/B on glow-labeled far-field meeting audio: 6x the
# same/diff cosine separation of wespeaker CAM++ (0.177 vs 0.030) and 62% vs
# 40% leave-one-out speaker accuracy.
SPEAKER_MODEL = os.environ.get(
    "SPEAKER_MODEL", "/models/nemo_en_titanet_large.onnx"
)
SPEAKER_RUN_ID = (
    os.path.splitext(os.path.basename(SPEAKER_MODEL))[0] + "@sherpa-onnx-1.13.3"
)
FACE_RUN_ID = "buffalo_sc@insightface-0.7"

# YOLO11n won an A/B on real stored keyframes: animal precision 0.33->1.0 (zero
# false positives) vs the phone's COCO EfficientDet-Lite0, and correct species
# where COCO called the dog a cat. Runs here on the server (subject-triggered
# keyframes) via onnxruntime — no torch in the image.
DETECT_MODEL = os.environ.get("DETECT_MODEL", "/opt/models/yolo11n.onnx")
DETECT_RUN_ID = "yolo11n@onnxruntime"
DETECT_INPUT = 640
COCO80 = (
    "person bicycle car motorcycle airplane bus train truck boat traffic_light "
    "fire_hydrant stop_sign parking_meter bench bird cat dog horse sheep cow "
    "elephant bear zebra giraffe backpack umbrella handbag tie suitcase frisbee "
    "skis snowboard sports_ball kite baseball_bat baseball_glove skateboard "
    "surfboard tennis_racket bottle wine_glass cup fork knife spoon bowl banana "
    "apple sandwich orange broccoli carrot hot_dog pizza donut cake chair couch "
    "potted_plant bed dining_table toilet tv laptop mouse remote keyboard "
    "cell_phone microwave oven toaster sink refrigerator book clock vase "
    "scissors teddy_bear hair_drier toothbrush"
).split()

_detect_session = None
_detect_lock = threading.Lock()


def detect_session():
    global _detect_session
    with _detect_lock:
        if _detect_session is None:
            import onnxruntime

            _detect_session = onnxruntime.InferenceSession(
                DETECT_MODEL, providers=["CPUExecutionProvider"]
            )
    return _detect_session

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
    return {
        "ok": True,
        "speakerRunId": SPEAKER_RUN_ID,
        "faceRunId": FACE_RUN_ID,
        "detectRunId": DETECT_RUN_ID,
    }


def _letterbox(image, size: int):
    """Resize keeping aspect ratio and pad to a square, YOLO-style. Returns the
    padded image plus the scale and (dx, dy) pad to map boxes back."""
    import cv2

    h, w = image.shape[:2]
    scale = min(size / w, size / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    resized = cv2.resize(image, (nw, nh))
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    dx, dy = (size - nw) // 2, (size - nh) // 2
    canvas[dy:dy + nh, dx:dx + nw] = resized
    return canvas, scale, dx, dy


def _nms(boxes, scores, iou_thresh: float):
    """Plain greedy NMS on [x1,y1,x2,y2] boxes; returns kept indices."""
    if len(boxes) == 0:
        return []
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = order[0]
        keep.append(int(i))
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        iou = inter / (areas[i] + areas[order[1:]] - inter + 1e-9)
        order = order[1:][iou <= iou_thresh]
    return keep


@app.post("/detect")
async def detect(request: Request, conf: float = 0.25, iou: float = 0.5) -> dict:
    """JPEG body → YOLO11 detections in ORIGINAL image pixels:
    [{label, scorePermille, box:[x1,y1,x2,y2]}]. The memory server uses this as
    the animal/vehicle detection authority instead of the phone's COCO tracks."""
    import cv2

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    image = cv2.imdecode(np.frombuffer(body, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="cannot decode image")
    h, w = image.shape[:2]

    padded, scale, dx, dy = _letterbox(image, DETECT_INPUT)
    rgb = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    tensor = np.transpose(rgb, (2, 0, 1))[None]  # NCHW

    session = detect_session()
    with _detect_lock:
        out = session.run(None, {session.get_inputs()[0].name: tensor})[0]
    # YOLO11 output: [1, 84, 8400] = 4 box + 80 class scores per anchor.
    pred = out[0].T  # [8400, 84]
    class_scores = pred[:, 4:]
    class_ids = class_scores.argmax(1)
    confidences = class_scores.max(1)
    mask = confidences >= conf
    pred, class_ids, confidences = pred[mask], class_ids[mask], confidences[mask]
    if len(pred) == 0:
        return {"detections": [], "modelRunId": DETECT_RUN_ID}

    cx, cy, bw, bh = pred[:, 0], pred[:, 1], pred[:, 2], pred[:, 3]
    boxes = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], axis=1)
    # undo letterbox → original pixels
    boxes[:, [0, 2]] = (boxes[:, [0, 2]] - dx) / scale
    boxes[:, [1, 3]] = (boxes[:, [1, 3]] - dy) / scale
    boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, w)
    boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, h)

    detections = []
    for cid in np.unique(class_ids):
        idx = np.where(class_ids == cid)[0]
        keep = _nms(boxes[idx], confidences[idx], iou)
        for k in keep:
            j = idx[k]
            detections.append(
                {
                    "label": COCO80[int(cid)],
                    "scorePermille": int(float(confidences[j]) * 1000),
                    "box": [int(v) for v in boxes[j]],
                }
            )
    detections.sort(key=lambda d: -d["scorePermille"])
    return {"detections": detections, "modelRunId": DETECT_RUN_ID}


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
