"""Speaker attribution from the meeting screen an observer is filming.

A cameras-off video call still shows, per participant tile, a name caption and
an active-speaker glow (a brighter border on whoever is talking). The observer
POV is a wearable/handheld camera, so every frame is tilted and moving — a
fixed pixel grid does not survive it (translation-only registration left ~77px
of residual drift, and the glow is only a few percent of border brightness).

So we ENHANCE the capture before reasoning on it: register every keyframe to a
session reference by ORB feature matching + RANSAC homography, which recovers
the full camera motion (rotation, scale, perspective — not just shift) from the
stable scene features (laptop bezel, keyboard, desk) and warps the frame into
one canonical view. In that stabilized view tile positions are fixed, so:

  * per-tile border glow becomes measurable against the tile's own baseline,
  * the active tile during an utterance is the one glowing above the others
    (after removing global exposure swings), and
  * captions crop from fixed regions and read cleanly.

`attribute_session` returns, per utterance, the tile it was spoken from (by
majority glow over the utterance's frames) plus the sharpest caption crop per
tile for naming. Everything downstream is the usual trace pattern: the caller
emits speaker-attribution derivation events and the reasoner concludes
speaker-cluster -> name with an NAL truth value.
"""

import io

import numpy as np


def _sharpness_gray(gray: "np.ndarray") -> float:
    import cv2

    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def _load_bgr(jpeg: bytes) -> "np.ndarray":
    import cv2

    from PIL import Image

    return cv2.cvtColor(np.asarray(Image.open(io.BytesIO(jpeg)).convert("RGB")), cv2.COLOR_RGB2BGR)


def _ring_luminance(warped: "np.ndarray", box: tuple) -> float:
    """Mean luminance of a tile's outer border ring — where the active-speaker
    glow lives. NaN if the warped tile fell outside the frame."""
    x1, y1, x2, y2 = box
    reg = warped[max(0, y1):y2, max(0, x1):x2]
    if reg.size == 0:
        return float("nan")
    h, w = reg.shape[:2]
    b = max(3, int(min(h, w) * 0.14))
    mask = np.ones((h, w), bool)
    mask[b:h - b, b:w - b] = False
    return float(reg[mask].mean())


def _caption_box(tile: tuple) -> tuple:
    """The name-caption region of a tile: its bottom-left strip."""
    x1, y1, x2, y2 = tile
    w, h = x2 - x1, y2 - y1
    return (x1 + int(0.02 * w), y2 - int(0.24 * h), x1 + int(0.72 * w), y2 - int(0.02 * h))


def attribute_session(frames: list, utterances: list, tiles_norm: list) -> dict:
    """frames: [(tNanos, jpeg_bytes)] sorted; utterances: [(eventId, t0, t1)];
    tiles_norm: [[x1,y1,x2,y2], ...] in [0,1] of the reference frame.

    Returns {"attributions": {utteranceEventId: {tileIndex, votes, frames,
    marginMean}}, "tileCaptions": {tileIndex: jpeg_bytes}, "registered": n,
    "medianInliers": m, "tiles": [pixel boxes]}."""
    import cv2

    if not frames or not tiles_norm:
        return {"attributions": {}, "tileCaptions": {}, "registered": 0, "medianInliers": 0, "tiles": []}

    # Reference = sharpest keyframe; tile boxes are defined against it. Use edge
    # variance (Sobel magnitude) — deterministic, so the reference is stable and
    # the configured tile geometry keeps matching it.
    def _edge_var(jpeg):
        g = cv2.cvtColor(_load_bgr(jpeg), cv2.COLOR_BGR2GRAY)
        gx = cv2.Sobel(g, cv2.CV_32F, 1, 0)
        gy = cv2.Sobel(g, cv2.CV_32F, 0, 1)
        return float((gx * gx + gy * gy).var())

    ref_idx = max(range(len(frames)), key=lambda i: _edge_var(frames[i][1]))
    ref = _load_bgr(frames[ref_idx][1])
    H, W = ref.shape[:2]
    tiles = [(int(a * W), int(b * H), int(c * W), int(d * H)) for a, b, c, d in tiles_norm]

    orb = cv2.ORB_create(3000)
    ref_gray = cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY)
    kpr, desr = orb.detectAndCompute(ref_gray, None)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    glow_by_time: list = []          # (t, {tileIdx: ring_lum})
    inliers_all: list = []
    # Keep the sharpest caption crops per tile — a single read is noisy, so the
    # caller reads several and votes (same as face name resolution).
    best_caption = {i: [] for i in range(len(tiles))}

    for t, jpeg in frames:
        img = _load_bgr(jpeg)
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        kp, des = orb.detectAndCompute(gray, None)
        if des is None or len(kp) < 20:
            continue
        matches = bf.knnMatch(des, desr, k=2)
        good = [m for m, n in matches if m.distance < 0.75 * n.distance]
        if len(good) < 15:
            continue
        src = np.float32([kp[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kpr[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        Hmat, mask = cv2.findHomography(src, dst, cv2.RANSAC, 4.0)
        if Hmat is None:
            continue
        inl = int(mask.sum())
        if inl < 12:
            continue
        warped = cv2.warpPerspective(img, Hmat, (W, H))
        inliers_all.append(inl)
        glow_by_time.append((t, {i: _ring_luminance(warped, tiles[i]) for i in range(len(tiles))}))
        wgray = cv2.cvtColor(warped, cv2.COLOR_BGR2GRAY)
        for i, tile in enumerate(tiles):
            cx1, cy1, cx2, cy2 = _caption_box(tile)
            crop = wgray[max(0, cy1):cy2, max(0, cx1):cx2]
            if crop.size == 0:
                continue
            s = _sharpness_gray(crop.astype(np.float64))
            lst = best_caption[i]
            lst.append((s, warped[max(0, cy1):cy2, max(0, cx1):cx2]))
            if len(lst) > 16:  # keep memory bounded: retain the sharpest 8
                lst.sort(key=lambda t: -t[0])
                del lst[8:]

    if not glow_by_time:
        return {"attributions": {}, "tileCaptions": {}, "registered": 0, "medianInliers": 0, "tiles": tiles}

    med = {i: np.nanmedian([g[i] for _, g in glow_by_time]) for i in range(len(tiles))}

    def active_tile(glow: dict):
        raw = {i: glow[i] - med[i] for i in range(len(tiles)) if not np.isnan(glow[i])}
        if not raw:
            return None, 0.0
        g = np.mean(list(raw.values()))  # remove global exposure swing
        rel = {i: raw[i] - g for i in raw}
        winner = max(rel, key=rel.get)
        vals = sorted(rel.values())
        margin = vals[-1] - vals[-2] if len(vals) > 1 else vals[-1]
        return winner, float(margin)

    attributions = {}
    for event_id, t0, t1 in utterances:
        votes = {}
        margins = []
        for t, glow in glow_by_time:
            if t0 <= t <= t1:
                w, m = active_tile(glow)
                if w is not None:
                    votes[w] = votes.get(w, 0) + 1
                    margins.append(m)
        if not votes:
            continue
        top = max(votes, key=votes.get)
        attributions[event_id] = {
            "tileIndex": top,
            "votes": votes[top],
            "frames": sum(votes.values()),
            "marginMean": round(float(np.mean(margins)), 2),
        }

    # Encode the sharpest few caption crops per tile for the caller to read+vote.
    captions_per_tile = 4
    tile_captions = {}
    for i, lst in best_caption.items():
        lst.sort(key=lambda t: -t[0])
        crops = []
        for _, cap in lst[:captions_per_tile]:
            if cap is not None and cap.size:
                ok, buf = cv2.imencode(".jpg", cap, [cv2.IMWRITE_JPEG_QUALITY, 95])
                if ok:
                    crops.append(buf.tobytes())
        tile_captions[i] = crops

    return {
        "attributions": attributions,
        "tileCaptions": tile_captions,
        "registered": len(glow_by_time),
        "medianInliers": int(np.median(inliers_all)) if inliers_all else 0,
        "tiles": tiles,
    }
