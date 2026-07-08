"""Vehicle re-identification: appearance embeddings for clustering, the
automotive analog of face/voice identity.

The on-device detector already tracks each vehicle within a run (track-segment
with a trackId and a box that moves from boxFirst to boxLast over the track's
lifetime). What it does not give is a cross-appearance / cross-session identity:
whether the silver SUV in today's drive is the one seen last week. That needs an
appearance vector, clustered like faces and voices.

No server-side detector is needed: a track's box, interpolated to a stored
keyframe's timestamp when the keyframe falls inside the track's lifetime, gives
the vehicle's location in that keyframe — so we crop the on-device detection
straight out of the pixels we already keep.

The embedding here is a deliberately coarse, model-free appearance descriptor
(a spatial Lab colour layout plus edge energy) — matching what dashcam capture
actually supports: colour/body-type-level re-id, not plate-precise identity.
`appearance_embedding` is the single swap point for a deep vehicle-reID model
(OSNet / VeRi) when a GPU is available; everything downstream is unchanged.
"""

import numpy as np


def interpolate_box(t: int, t0: int, t1: int, box_first: list, box_last: list) -> list:
    """A track's box at time t, linearly interpolated over its lifetime."""
    f = 0.0 if t1 <= t0 else max(0.0, min(1.0, (t - t0) / (t1 - t0)))
    return [box_first[i] + f * (box_last[i] - box_first[i]) for i in range(4)]


def appearance_embedding(crop_bgr: "np.ndarray") -> list:
    """Coarse, model-free vehicle appearance vector: a spatial grid of mean Lab
    colour (what colour, where on the body) plus per-cell edge energy (coarse
    shape/texture), L2-normalized for cosine clustering. Returns [] if the crop
    is too small to be meaningful."""
    import cv2

    h, w = crop_bgr.shape[:2]
    if h < 24 or w < 24:
        return []
    img = cv2.resize(crop_bgr, (96, 96), interpolation=cv2.INTER_AREA)
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    edge = np.sqrt(gx * gx + gy * gy)

    grid = 6
    feats = []
    ys = np.linspace(0, 96, grid + 1, dtype=int)
    xs = np.linspace(0, 96, grid + 1, dtype=int)
    for i in range(grid):
        for j in range(grid):
            cell = lab[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
            ecell = edge[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
            feats.extend([cell[:, :, 0].mean(), cell[:, :, 1].mean(), cell[:, :, 2].mean(), ecell.mean()])
    vec = np.asarray(feats, dtype=np.float32)
    # Standardize channels to comparable scale before L2 norm so colour and edge
    # contribute jointly rather than L*/edge magnitude dominating.
    vec = (vec - vec.mean()) / (vec.std() + 1e-6)
    norm = float(np.linalg.norm(vec))
    return (vec / norm).tolist() if norm > 0 else []
