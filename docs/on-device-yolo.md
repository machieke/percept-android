# On-device YOLO11n detector (opt-in, model pending)

## Why

A server-side A/B on 32 real stored keyframes proved YOLO11n beats the phone's
default COCO EfficientDet-Lite0 decisively on animal detection: precision
**0.33 → 1.0**, eight false positives → **zero**, correct species where COCO
called the dog a cat. Open-vocab YOLO-World was *worse* (0.50), so the win is a
stronger COCO detector, not open vocabulary. YOLO11n is 2.6M params — a smaller
footprint than EfficientDet-Lite0 (4.5M) — so it is phone-viable.

The swap already shipped **server-side** (`server/ident` `/detect`, the
identification authority). This is the **on-device** counterpart.

## What is built (percept-0.4.6)

Fully implemented, compiled, and host-tested:

- `perception/video/.../YoloDecode.kt` — pure, host-tested decoder for the raw
  `[1,84,8400]` YOLO output: letterbox mapping, threshold, box conversion to
  original pixels, per-class NMS. Covered by `YoloDecodeTest` (planted-detection
  decode with letterbox undo, threshold drop, NMS suppression, cross-class
  non-suppression).
- `YoloTfliteFrameDetector.kt` — a `FrameDetector` running the raw TFLite
  Interpreter (MediaPipe's ObjectDetector cannot consume a raw YOLO model), then
  `YoloDecode`.
- `PerceptSettings.useYoloDetector` — **opt-in, defaults OFF**.
- `CameraMicrophoneRig.createDetector()` — picks the detector from the setting
  and **falls back to EfficientDet if YOLO fails to load** (missing asset /
  delegate init), so enabling it can never lose video capture.
- MainActivity toggle: "Experimental: YOLO11n detector".

## What is NOT done: the model binary

The one missing piece is `app/src/main/assets/models/yolo11n_float32.tflite`.
Producing it was blocked by the current broken YOLO→TFLite export toolchain —
every documented path failed on a different version-matrix conflict:

| path | failure |
|---|---|
| `ultralytics export format=tflite` (LiteRT) | `No module named 'litert_torch'` |
| ultralytics + onnx2tf (tf 2.16) | `No module named 'tf_keras'`, then numpy `StringDType` |
| onnx2tf directly on yolo11n.onnx | numpy `allow_pickle=False` pickled-data error (multiple numpy/onnx2tf pins) |
| ai-edge-torch 0.2.0 | `torch_xla` ABI mismatch with torch 2.4 |
| ai-edge-torch (latest) | API moved: `module 'ai_edge_torch' has no attribute 'convert'` |

The validated ONNX (`server/ident/models/yolo11n.onnx`, precision 1.0 on the
A/B) is the source of truth for the model weights.

### To finish (when the toolchain cooperates, or on a machine with a working
`yolo export`)

1. Produce `yolo11n_float32.tflite` at 640×640 input. On a working setup:
   `yolo export model=yolo11n.pt format=tflite imgsz=640` — the desired output
   is the `*_float32.tflite` from the saved-model dir.
2. Confirm the output tensor is `[1, 84, 8400]` (NMS-free raw head — what
   `YoloDecode` expects). If the export bakes in NMS or transposes to
   `[1, 8400, 84]`, adjust `YoloDecode.decode` indexing accordingly.
3. Place it at `app/src/main/assets/models/yolo11n_float32.tflite` (or wire it
   into the pinned `downloadModels` task in `app/build.gradle.kts` with its
   sha256, matching the project's model-provenance convention).
4. Enable the toggle, record a session, and **validate against the server**:
   the server runs the same YOLO11n as ground truth, so the on-device
   detections should match. This is the on-device validation that cannot be
   done from the host.

Until step 3, the toggle is safe but inert: turning it on falls back to
EfficientDet-Lite0.
