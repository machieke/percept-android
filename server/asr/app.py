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


@app.post("/transcribe")
async def transcribe(request: Request, sampleRate: int = 16000) -> dict:
    body = await request.body()
    if not body or len(body) % 2 != 0:
        raise HTTPException(status_code=400, detail="body must be raw PCM16 LE mono")
    if sampleRate < 8000 or sampleRate > 48000:
        raise HTTPException(status_code=400, detail="unsupported sampleRate")

    samples = np.frombuffer(body, dtype="<i2").astype(np.float32) / 32768.0
    duration_ms = int(len(samples) * 1000 / sampleRate)

    started = time.time()
    with decode_lock:
        stream = recognizer.create_stream()
        stream.accept_waveform(sampleRate, samples)
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
