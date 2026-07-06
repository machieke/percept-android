"""Percept remote ASR: parakeet-tdt-0.6b-v3 (int8) served over HTTP.

The phone's RemoteAsrEngine POSTs one VAD-gated PCM16 window per request;
this service decodes it with sherpa-onnx on CPU (measured RTF ~0.05-0.07 on
a 4-thread Xeon W-2225) and returns the transcript with the detected
language. The model tarball is downloaded and sha256-verified by
entrypoint.sh before uvicorn starts.
"""

import os
import threading
import time

import numpy as np
import sherpa_onnx
from fastapi import FastAPI, HTTPException, Request

MODEL_DIR = os.environ.get("MODEL_DIR", "/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")
ASR_THREADS = int(os.environ.get("ASR_THREADS", "4"))
MODEL_RUN_ID = "parakeet-tdt-0.6b-v3-int8@sherpa-onnx-1.13.3"

recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
    encoder=f"{MODEL_DIR}/encoder.int8.onnx",
    decoder=f"{MODEL_DIR}/decoder.int8.onnx",
    joiner=f"{MODEL_DIR}/joiner.int8.onnx",
    tokens=f"{MODEL_DIR}/tokens.txt",
    model_type="nemo_transducer",
    num_threads=ASR_THREADS,
)
decode_lock = threading.Lock()

app = FastAPI(title="percept-asr")


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "modelRunId": MODEL_RUN_ID}


def _decode(samples: "np.ndarray", sample_rate: int) -> dict:
    duration_ms = int(len(samples) * 1000 / sample_rate)
    started = time.time()
    with decode_lock:
        stream = recognizer.create_stream()
        stream.accept_waveform(sample_rate, samples)
        recognizer.decode_stream(stream)
        result = stream.result
    decode_ms = int((time.time() - started) * 1000)

    timestamps = list(result.timestamps or [])
    lang = (result.lang or "").strip("<|>").strip() or "auto"
    return {
        "text": result.text.strip(),
        "lang": lang,
        "startMs": int(timestamps[0] * 1000) if timestamps else 0,
        "endMs": duration_ms,
        "decodeMs": decode_ms,
        "modelRunId": MODEL_RUN_ID,
    }


@app.post("/transcribe")
async def transcribe(request: Request, sampleRate: int = 16000) -> dict:
    body = await request.body()
    if not body or len(body) % 2 != 0:
        raise HTTPException(status_code=400, detail="body must be raw PCM16 LE mono")
    if sampleRate < 8000 or sampleRate > 48000:
        raise HTTPException(status_code=400, detail="unsupported sampleRate")
    samples = np.frombuffer(body, dtype="<i2").astype(np.float32) / 32768.0
    return _decode(samples, sampleRate)


def _speech_regions(samples: "np.ndarray", sample_rate: int) -> list[tuple[int, int]]:
    """Energy-VAD speech regions (sample offsets). Language identification
    over a whole chunk gets diluted by silence and room noise — a real
    session's 60 s Dutch chunk was transcribed as English — so archival
    decoding runs per speech region, like the live windows that got the
    language right."""
    frame = int(0.03 * sample_rate)
    n_frames = len(samples) // frame
    if n_frames == 0:
        return [(0, len(samples))]
    frames = samples[: n_frames * frame].reshape(n_frames, frame)
    rms = np.sqrt((frames * frames).mean(axis=1))
    noise_floor = float(np.percentile(rms, 10))
    threshold = max(noise_floor * 4.0, 0.004)
    active = rms > threshold

    raw: list[tuple[int, int]] = []
    start = None
    for i, is_active in enumerate(active):
        if is_active and start is None:
            start = i
        elif not is_active and start is not None:
            raw.append((start, i))
            start = None
    if start is not None:
        raw.append((start, n_frames))
    if not raw:
        return []

    pad = int(0.25 / 0.03)
    gap = int(0.5 / 0.03)
    merged: list[list[int]] = []
    for s, e in raw:
        s, e = max(0, s - pad), min(n_frames, e + pad)
        if merged and s <= merged[-1][1] + gap:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])

    max_frames = int(30 / 0.03)
    regions: list[tuple[int, int]] = []
    for s, e in merged:
        if (e - s) * 0.03 < 0.4:
            continue
        while e - s > max_frames:
            regions.append((s * frame, (s + max_frames) * frame))
            s += max_frames
        regions.append((s * frame, e * frame))
    return regions


@app.post("/transcribe-file")
async def transcribe_file(request: Request) -> dict:
    """Container audio (ogg/opus, flac, wav) — the format of the
    audio-chunk artifacts that bundles carry for episodic memory.
    Decoded with ffmpeg (libsndfile rejects Android MediaMuxer's Ogg),
    then transcribed per speech region for stable language ID."""
    import subprocess

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    proc = subprocess.run(
        [
            "ffmpeg", "-v", "error", "-i", "pipe:0",
            "-f", "f32le", "-ac", "1", "-ar", "16000", "pipe:1",
        ],
        input=body,
        capture_output=True,
    )
    if proc.returncode != 0 or not proc.stdout:
        detail = proc.stderr.decode("utf-8", "replace").strip()[:200] or "unknown"
        raise HTTPException(status_code=400, detail=f"cannot decode audio: {detail}")
    samples = np.frombuffer(proc.stdout, dtype=np.float32)

    started = time.time()
    segments = []
    for region_start, region_end in _speech_regions(samples, 16000):
        region = _decode(samples[region_start:region_end], 16000)
        if not region["text"]:
            continue
        segments.append(
            {
                "text": region["text"],
                "lang": region["lang"],
                "startMs": region_start // 16,
                "endMs": region_end // 16,
            }
        )
    languages = [s["lang"] for s in segments if s["lang"] != "auto"]
    return {
        "text": " ".join(s["text"] for s in segments),
        "lang": max(set(languages), key=languages.count) if languages else "auto",
        "startMs": segments[0]["startMs"] if segments else 0,
        "endMs": int(len(samples) * 1000 / 16000),
        "decodeMs": int((time.time() - started) * 1000),
        "segments": segments,
        "modelRunId": MODEL_RUN_ID,
    }
