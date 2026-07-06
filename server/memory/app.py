"""Percept episodic memory server.

Accepts the phone's bundle uploads (PUT /bundles/{bundle_id}), verifies every
event against its content address, and accumulates objects + pointers into a
persistent store built on the reference implementation: FileDA on a volume
plus an append-only pointers.jsonl log replayed into the in-memory
EventTraceIndex at startup.

Each ingested audio-chunk artifact is transcribed via the percept-asr service
(/transcribe-file) and recorded as a server-side asr-segment event parented to
the chunk — the archival transcript accumulates alongside the phone's live
segments in the same causal trace.
"""

import json
import os
import sys
import threading
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request

sys.path.insert(0, os.environ.get("REFERENCE_PATH", "/opt/event-trace-memory"))

from event_trace_memory.canonical import canonical_json_bytes, sha256_hex  # noqa: E402
from event_trace_memory.da import FileDA  # noqa: E402
from event_trace_memory.indexes import EventTraceIndex  # noqa: E402
from event_trace_memory.ingestion import EventIngestor  # noqa: E402

DATA_ROOT = Path(os.environ.get("DATA_ROOT", "/data"))
ASR_URL = os.environ.get("ASR_URL", "http://host.docker.internal:8123")
MEMORY_TOKEN = os.environ.get("MEMORY_TOKEN", "")
POINTER_LOG = DATA_ROOT / "pointers.jsonl"

DATA_ROOT.mkdir(parents=True, exist_ok=True)
da = FileDA(DATA_ROOT / "da")
index = EventTraceIndex()
ingestor = EventIngestor(da, index)
ingest_lock = threading.Lock()


def replay_pointer_log() -> int:
    if not POINTER_LOG.exists():
        return 0
    count = 0
    with POINTER_LOG.open() as log:
        for line in log:
            line = line.strip()
            if not line:
                continue
            ack = index.put_event(json.loads(line))
            if ack.get("ok"):
                count += 1
    return count


REPLAYED = replay_pointer_log()


def append_pointer(pointer: dict) -> None:
    with POINTER_LOG.open("ab") as log:
        log.write(canonical_json_bytes(pointer) + b"\n")


def check_auth(authorization: str | None) -> None:
    if not MEMORY_TOKEN:
        return
    if authorization != f"Bearer {MEMORY_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid bearer token")


def digest_of(cid: str) -> str:
    return cid.rsplit(":", 1)[-1]


def verify_pointer(pointer: dict, objects: dict[str, bytes]) -> None:
    event_id = pointer["eventId"]
    event_digest = digest_of(pointer["eventCid"])
    envelope_bytes = objects.get(event_digest)
    if envelope_bytes is None:
        raise ValueError(f"{event_id}: envelope object missing")
    if sha256_hex(envelope_bytes) != event_digest:
        raise ValueError(f"{event_id}: envelope digest mismatch")
    if canonical_json_bytes(json.loads(envelope_bytes)) != envelope_bytes:
        raise ValueError(f"{event_id}: envelope is not canonical")
    if digest_of(event_id) != event_digest:
        raise ValueError(f"{event_id}: eventId does not match eventCid")
    for cid in [pointer["payloadCid"], *pointer.get("outputArtifactIds", [])]:
        digest = digest_of(cid)
        data = objects.get(digest)
        if data is None:
            raise ValueError(f"{event_id}: referenced object {cid} missing")
        if sha256_hex(data) != digest:
            raise ValueError(f"{event_id}: object {cid} digest mismatch")


def transcribe_chunk(pointer: dict) -> dict | None:
    """Server-side archival transcript for one audio-chunk event."""
    chunk_event_id = pointer["eventId"]
    existing_children = index.by_parent(chunk_event_id).get("eventIds", [])
    for child_id in existing_children:
        child = index.get_event(child_id)
        if child.get("event", {}).get("valueKind") == "asr-segment":
            return None  # already transcribed on a previous upload

    payload = json.loads(da.get_bytes(pointer["payloadCid"]))
    artifact_cid = pointer["outputArtifactIds"][0]
    audio = da.get_bytes(artifact_cid)

    request = urllib.request.Request(f"{ASR_URL}/transcribe-file", data=audio)
    with urllib.request.urlopen(request, timeout=300) as response:
        result = json.load(response)
    segments = result.get("segments")
    if segments is None:  # older ASR server: treat the whole chunk as one segment
        segments = [result] if result.get("text", "").strip() else []

    created = []
    for segment in segments:
        text = segment.get("text", "").strip()
        if not text:
            continue
        ingested = ingestor.ingest_event(
            raw_payload={
                "kind": "raw-payload",
                "schema": "perception-asr-v0.1",
                "sessionId": payload["sessionId"],
                "text": text,
                "langHint": segment.get("lang", "auto"),
                "tStartNanos": payload["tStartNanos"] + segment.get("startMs", 0) * 1_000_000,
                "tEndNanos": payload["tStartNanos"] + segment.get("endMs", 0) * 1_000_000,
                "avgLogProbMicro": 0,
                "observedAt": payload["observedAt"],
            },
            observed_at=payload["observedAt"],
            actor_path=["server", "percept-memory", "asr", "parakeet"],
            channel_path=pointer["channelPath"],
            value_kind="asr-segment",
            preview=text[:160],
            provenance={
                "source": "percept-memory-server",
                "observedBy": "percept-memory",
                "ingestionPipeline": "event-trace-v0",
                "extractionRunId": result.get("modelRunId", "unknown"),
            },
            parent_event_ids=[chunk_event_id],
            root_event_id=pointer["rootEventId"],
            input_event_ids=[chunk_event_id],
        )
        append_pointer(ingested.pointer)
        created.append(ingested.event_id)
    if not created:
        return None
    return {"chunk": chunk_event_id, "asrEventIds": created}


app = FastAPI(title="percept-memory")


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "stats": index.state_stats(), "replayed": REPLAYED}


@app.post("/events")
async def post_event(request: Request, authorization: str | None = Header(None)) -> dict:
    """Live ingest of a single event: the phone streams each event as it is
    ingested locally, so reasoning sees the trace in near-real-time; bundles
    remain the idempotent backfill for anything the stream drops."""
    check_auth(authorization)
    import base64

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    try:
        message = json.loads(body)
        pointer = message["pointer"]
        objects = {
            digest: base64.b64decode(encoded)
            for digest, encoded in message.get("objects", {}).items()
        }
    except (ValueError, KeyError, TypeError) as exc:
        raise HTTPException(status_code=400, detail=f"malformed event message: {exc}")

    with ingest_lock:
        try:
            verify_pointer(pointer, objects)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc))

        objects_dir = DATA_ROOT / "da" / "objects"
        objects_dir.mkdir(parents=True, exist_ok=True)
        for digest, data in objects.items():
            target = objects_dir / digest
            if not target.exists():
                target.write_bytes(data)

        ack = index.put_event(pointer)
        duplicate = not ack.get("ok")
        if not duplicate:
            append_pointer(pointer)
        transcription = None
        if pointer.get("valueKind") == "audio-chunk" and pointer.get("outputArtifactIds"):
            try:
                transcription = transcribe_chunk(pointer)
            except Exception as exc:  # noqa: BLE001 - ASR downtime must not fail ingest
                transcription = {"chunk": pointer["eventId"], "error": str(exc)[:200]}

    return {
        "ok": True,
        "eventId": pointer["eventId"],
        "duplicate": duplicate,
        "chunkTranscription": transcription,
    }


@app.put("/bundles/{bundle_id}")
async def put_bundle(bundle_id: str, request: Request, authorization: str | None = Header(None)) -> dict:
    check_auth(authorization)
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    try:
        archive = zipfile.ZipFile(BytesIO(body))
    except zipfile.BadZipFile:
        raise HTTPException(status_code=400, detail="body is not a zip")

    objects: dict[str, bytes] = {}
    manifests: dict[str, bytes] = {}
    pointer_lines: list[str] = []
    for name in archive.namelist():
        parts = Path(name).parts
        if "objects" in parts and not name.endswith("/"):
            objects[Path(name).name] = archive.read(name)
        elif "manifests" in parts and not name.endswith("/"):
            manifests[Path(name).name] = archive.read(name)
        elif name.endswith("pointers.jsonl"):
            pointer_lines = archive.read(name).decode("utf-8").splitlines()
    if not pointer_lines:
        raise HTTPException(status_code=400, detail="bundle has no pointers.jsonl")

    pointers = [json.loads(line) for line in pointer_lines if line.strip()]
    with ingest_lock:
        try:
            for pointer in pointers:
                verify_pointer(pointer, objects)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc))

        objects_dir = DATA_ROOT / "da" / "objects"
        manifests_dir = DATA_ROOT / "da" / "manifests"
        for digest, data in objects.items():
            target = objects_dir / digest
            if not target.exists():
                target.write_bytes(data)
        for name, data in manifests.items():
            target = manifests_dir / name
            if not target.exists():
                target.write_bytes(data)

        accepted = 0
        duplicates = 0
        transcribed = []
        for pointer in pointers:
            ack = index.put_event(pointer)
            if ack.get("ok"):
                accepted += 1
                append_pointer(pointer)
            else:
                duplicates += 1
        for pointer in pointers:
            if pointer.get("valueKind") == "audio-chunk" and pointer.get("outputArtifactIds"):
                try:
                    outcome = transcribe_chunk(pointer)
                except Exception as exc:  # noqa: BLE001 - ASR downtime must not fail ingest
                    outcome = {"chunk": pointer["eventId"], "error": str(exc)[:200]}
                if outcome:
                    transcribed.append(outcome)

    return {
        "ok": True,
        "bundleId": bundle_id,
        "events": accepted,
        "duplicates": duplicates,
        "chunkTranscriptions": transcribed,
    }
