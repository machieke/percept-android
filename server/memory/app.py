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
import queue
import sys
import threading
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request

import enrich as enrichment
import reason as reasoning
from identity import IdentityRegistry

sys.path.insert(0, os.environ.get("REFERENCE_PATH", "/opt/event-trace-memory"))

from event_trace_memory.canonical import canonical_json_bytes, sha256_hex  # noqa: E402
from event_trace_memory.da import FileDA  # noqa: E402
from event_trace_memory.indexes import EventTraceIndex  # noqa: E402
from event_trace_memory.ingestion import EventIngestor  # noqa: E402

DATA_ROOT = Path(os.environ.get("DATA_ROOT", "/data"))
ASR_URL = os.environ.get("ASR_URL", "http://host.docker.internal:8123")
MEMORY_TOKEN = os.environ.get("MEMORY_TOKEN", "")
ENRICH_ENABLED = os.environ.get("ENRICH_ENABLED", "1") == "1"
MAX_CAPTIONS_PER_CHUNK = int(os.environ.get("MAX_CAPTIONS_PER_CHUNK", "4"))
IDENT_URL = os.environ.get("IDENT_URL", "http://127.0.0.1:8125")
IDENT_ENABLED = os.environ.get("IDENT_ENABLED", "1") == "1"
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


def ingest_or_skip_duplicate(**kwargs):
    """ingest_event + pointer-log append, returning None for duplicates so
    re-runs of identical derivations are free (content addressing)."""
    ingested = ingestor.ingest_event(**kwargs)
    if not ingested.ack.get("ok"):
        return None
    append_pointer(ingested.pointer)
    return ingested


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


def transcribe_chunk(pointer: dict, force: bool = False) -> dict | None:
    """Server-side archival transcript for one audio-chunk event. With
    force=True the existing-children check is skipped so an improved
    pipeline can re-process legacy chunks — identical re-runs deduplicate
    via content addressing, only genuinely different segments land."""
    chunk_event_id = pointer["eventId"]
    if not force:
        for child_id in index.by_parent(chunk_event_id).get("eventIds", []):
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
        ingested = ingest_or_skip_duplicate(
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
        if ingested is None:
            continue
        created.append(ingested.event_id)
    if not created:
        return None
    if ENRICH_ENABLED:
        enrich_queue.put(chunk_event_id)
    return {"chunk": chunk_event_id, "asrEventIds": created}


identities = IdentityRegistry(DATA_ROOT / "identities.json")


def _pointer(event_id: str) -> dict | None:
    result = index.get_event(event_id)
    return result.get("event") if result.get("ok") else None


def _payload(pointer: dict) -> dict:
    return json.loads(da.get_bytes(pointer["payloadCid"]))


def identify_chunk(chunk_event_id: str, force: bool = False) -> None:
    """Pseudonymous who-was-there: voice embeddings per archival asr segment
    and face embeddings per keyframe, clustered against the persistent
    registry into speaker-observation / face-observation events."""
    chunk = _pointer(chunk_event_id)
    if not chunk:
        return
    chunk_payload = _payload(chunk)
    root = chunk["rootEventId"]
    audio = da.get_bytes(chunk["outputArtifactIds"][0])
    chunk_start = chunk_payload["tStartNanos"]

    def has_child_of_kind(event_id: str, kind: str) -> bool:
        return any(
            (child := _pointer(child_id)) and child["valueKind"] == kind
            for child_id in index.by_parent(event_id).get("eventIds", [])
        )

    for segment_id in index.by_parent(chunk_event_id).get("eventIds", []):
        segment = _pointer(segment_id)
        if not segment or segment["valueKind"] != "asr-segment":
            continue
        if not force and has_child_of_kind(segment_id, "speaker-observation"):
            continue
        seg_payload = _payload(segment)
        start_ms = max(0, (seg_payload["tStartNanos"] - chunk_start) // 1_000_000)
        end_ms = (seg_payload["tEndNanos"] - chunk_start) // 1_000_000
        if end_ms - start_ms < 700:
            continue  # too short for a stable voiceprint
        try:
            request = urllib.request.Request(
                f"{IDENT_URL}/embed-speaker?startMs={start_ms}&endMs={end_ms}", data=audio,
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
        except Exception as exc:  # noqa: BLE001 - identity is best-effort
            print(f"speaker embed failed for {segment_id}: {exc}", flush=True)
            continue
        cluster_id, similarity = identities.assign("speaker", result["embedding"])
        payload = {
            "kind": "raw-payload",
            "schema": "perception-speaker-v0.1",
            "sessionId": seg_payload["sessionId"],
            "clusterId": cluster_id,
            "similarityPermille": similarity,
            "tStartNanos": seg_payload["tStartNanos"],
            "tEndNanos": seg_payload["tEndNanos"],
            "observedAt": seg_payload["observedAt"],
        }
        label = identities.label_of(cluster_id)
        if label:
            payload["label"] = label
        with ingest_lock:
            ingest_or_skip_duplicate(
                raw_payload=payload,
                observed_at=seg_payload["observedAt"],
                actor_path=["server", "percept-memory", "speaker-id"],
                channel_path=[chunk["channelPath"][0], chunk["channelPath"][1], "identity"],
                value_kind="speaker-observation",
                preview=label or cluster_id,
                provenance={
                    "source": "percept-memory-server",
                    "observedBy": "percept-memory",
                    "ingestionPipeline": "event-trace-v0",
                    "extractionRunId": result.get("modelRunId", "unknown"),
                },
                parent_event_ids=[segment_id],
                root_event_id=root,
                input_event_ids=[segment_id],
            )

    window = (chunk_start - 5_000_000_000, chunk_payload["tEndNanos"] + 5_000_000_000)
    for event_id in index.by_root(root).get("eventIds", []):
        scene = _pointer(event_id)
        if not scene or scene["valueKind"] != "scene-change" or not scene.get("outputArtifactIds"):
            continue
        scene_payload = _payload(scene)
        if not window[0] <= scene_payload["tNanos"] <= window[1]:
            continue
        if not force and has_child_of_kind(event_id, "face-observation"):
            continue
        try:
            request = urllib.request.Request(
                f"{IDENT_URL}/embed-faces", data=da.get_bytes(scene["outputArtifactIds"][0]),
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
        except Exception as exc:  # noqa: BLE001
            print(f"face embed failed for {event_id}: {exc}", flush=True)
            continue
        for face in result.get("faces", []):
            cluster_id, similarity = identities.assign("face", face["embedding"])
            payload = {
                "kind": "raw-payload",
                "schema": "perception-face-v0.1",
                "sessionId": scene_payload["sessionId"],
                "clusterId": cluster_id,
                "similarityPermille": similarity,
                "detScorePermille": face["detScorePermille"],
                "box": face["box"],
                "tNanos": scene_payload["tNanos"],
                "observedAt": scene_payload["observedAt"],
            }
            label = identities.label_of(cluster_id)
            if label:
                payload["label"] = label
            with ingest_lock:
                ingest_or_skip_duplicate(
                    raw_payload=payload,
                    observed_at=scene_payload["observedAt"],
                    actor_path=["server", "percept-memory", "face-id"],
                    channel_path=[scene["channelPath"][0], scene["channelPath"][1], "identity"],
                    value_kind="face-observation",
                    preview=label or cluster_id,
                    provenance={
                        "source": "percept-memory-server",
                        "observedBy": "percept-memory",
                        "ingestionPipeline": "event-trace-v0",
                        "extractionRunId": result.get("modelRunId", "unknown"),
                    },
                    parent_event_ids=[event_id],
                    root_event_id=root,
                    input_event_ids=[event_id],
                )


def resolve_names(session_id: str, max_reads_per_cluster: int = 5) -> dict:
    """Read on-screen name labels (video-call tiles, name tags) for the face
    clusters seen in a session, emit identity-resolution derivation events
    parented to the face-observations, and project the majority reading into
    the registry (human labels are never overwritten).

    A resolution is a proposal recorded in the trace with its model and
    confidence — competing/wrong reads coexist and are out-voted, never
    erased."""
    import collections

    # Gather face-observations per cluster, richest detections first.
    per_cluster: dict[str, list[tuple[dict, dict]]] = collections.defaultdict(list)
    for event_id in index.by_kind("face-observation").get("eventIds", []):
        obs = _pointer(event_id)
        if not obs:
            continue
        opl = _payload(obs)
        if opl.get("sessionId") != session_id:
            continue
        per_cluster[opl["clusterId"]].append((obs, opl))

    resolved: dict[str, str] = {}
    for cluster_id, observations in per_cluster.items():
        observations.sort(key=lambda o: -o[1].get("detScorePermille", 0))
        votes: "collections.Counter[str]" = collections.Counter()
        reads = 0
        for obs, opl in observations:
            if reads >= max_reads_per_cluster:
                break
            # The keyframe is the face-observation's parent scene-change artifact.
            scene = _pointer(obs["parentEventIds"][0]) if obs.get("parentEventIds") else None
            if not scene or not scene.get("outputArtifactIds"):
                continue
            already = any(
                (child := _pointer(cid)) and child["valueKind"] == "identity-resolution"
                for cid in index.by_parent(obs["eventId"]).get("eventIds", [])
            )
            if already:
                continue
            try:
                name = enrichment.read_name_label(da.get_bytes(scene["outputArtifactIds"][0]), opl["box"])
            except Exception as exc:  # noqa: BLE001 - resolution is best-effort
                print(f"name read failed for {obs['eventId']}: {exc}", flush=True)
                continue
            reads += 1
            confidence = 700 if name else 0
            with ingest_lock:
                ingest_or_skip_duplicate(
                    raw_payload={
                        "kind": "raw-payload",
                        "schema": "perception-identity-resolution-v0.1",
                        "sessionId": session_id,
                        "clusterId": cluster_id,
                        "resolvedName": name or "",
                        "method": "on-screen-label",
                        "confidencePermille": confidence,
                        "box": opl["box"],
                        "tNanos": opl["tNanos"],
                        "observedAt": opl["observedAt"],
                    },
                    observed_at=opl["observedAt"],
                    actor_path=["server", "percept-memory", "identity-resolver"],
                    channel_path=obs["channelPath"],
                    value_kind="identity-resolution",
                    preview=f"{cluster_id} -> {name or 'NONE'}",
                    provenance={
                        "source": "percept-memory-server",
                        "observedBy": "percept-memory",
                        "ingestionPipeline": "event-trace-v0",
                        "extractionRunId": f"{enrichment.VLM_MODEL}+name-label@ollama",
                    },
                    parent_event_ids=[obs["eventId"]],
                    root_event_id=obs["rootEventId"],
                    input_event_ids=[obs["eventId"]],
                )
            if name:
                votes[name] += 1
        if votes:
            # Vote on the first-name token too: the VLM reads first names
            # stably off pixelated labels but hallucinates surnames, so full
            # names fragment the tally. Prefer the most-corroborated full
            # name that shares the winning first name.
            first_votes: "collections.Counter[str]" = collections.Counter()
            for full, n in votes.items():
                first_votes[full.split()[0]] += n
            top_first, first_count = first_votes.most_common(1)[0]
            candidates = {f: n for f, n in votes.items() if f.split()[0] == top_first}
            winner = max(candidates, key=lambda f: (candidates[f], len(f)))
            conf = min(950, 500 + first_count * 100)
            if identities.label(cluster_id, winner, method="on-screen-label", confidence=conf):
                resolved[cluster_id] = winner
    return resolved


def enrich_chunk(chunk_event_id: str) -> None:
    """Caption keyframes around one audio chunk and LLM-correct its archival
    asr segments using that visual context. Runs on the enrichment worker;
    only event creation holds the ingest lock — VLM/LLM latency never blocks
    ingestion."""
    chunk = _pointer(chunk_event_id)
    if not chunk:
        return
    chunk_payload = _payload(chunk)
    root = chunk["rootEventId"]
    window = (chunk_payload["tStartNanos"] - 5_000_000_000, chunk_payload["tEndNanos"] + 5_000_000_000)

    # Speech first: captioning on CPU costs minutes per keyframe, so only
    # scenes near actual utterances are captioned, closest first, capped.
    segment_windows = []
    for segment_id in index.by_parent(chunk_event_id).get("eventIds", []):
        segment = _pointer(segment_id)
        if segment and segment["valueKind"] == "asr-segment":
            sp = _payload(segment)
            segment_windows.append((sp["tStartNanos"], sp["tEndNanos"]))
    if not segment_windows:
        return

    def distance_to_speech(t_nanos: int) -> int:
        return min(
            0 if start <= t_nanos <= end else min(abs(t_nanos - start), abs(t_nanos - end))
            for start, end in segment_windows
        )

    scene_candidates = []
    captions: list[tuple[int, str, str]] = []  # (tNanos, text, captionEventId)
    labels: list[str] = []
    for event_id in index.by_root(root).get("eventIds", []):
        pointer = _pointer(event_id)
        if not pointer:
            continue
        if pointer["valueKind"] == "scene-change":
            scene = _payload(pointer)
            if not window[0] <= scene["tNanos"] <= window[1]:
                continue
            if distance_to_speech(scene["tNanos"]) > 10_000_000_000:
                continue
            scene_candidates.append((distance_to_speech(scene["tNanos"]), event_id, scene))
        elif pointer["valueKind"] == "track-segment":
            track = _payload(pointer)
            if track["tEndNanos"] >= window[0] and track["tStartNanos"] <= window[1]:
                labels.append(track["label"])

    for _, event_id, scene in sorted(scene_candidates, key=lambda c: c[0])[:MAX_CAPTIONS_PER_CHUNK]:
        pointer = _pointer(event_id)
        existing = next(
            (
                child for child_id in index.by_parent(event_id).get("eventIds", [])
                if (child := _pointer(child_id)) and child["valueKind"] == "scene-caption"
            ),
            None,
        )
        if existing:
            captions.append((scene["tNanos"], _payload(existing)["text"], existing["eventId"]))
            continue
        if not pointer.get("outputArtifactIds"):
            continue
        text = enrichment.caption_keyframe(da.get_bytes(pointer["outputArtifactIds"][0]))
        if not text:
            continue
        with ingest_lock:
            ingested = ingestor.ingest_event(
                raw_payload={
                    "kind": "raw-payload",
                    "schema": "perception-scene-caption-v0.1",
                    "sessionId": scene["sessionId"],
                    "text": text,
                    "tNanos": scene["tNanos"],
                    "observedAt": scene["observedAt"],
                },
                observed_at=scene["observedAt"],
                actor_path=["server", "percept-memory", "vlm"],
                channel_path=pointer["channelPath"],
                value_kind="scene-caption",
                preview=text[:160],
                provenance={
                    "source": "percept-memory-server",
                    "observedBy": "percept-memory",
                    "ingestionPipeline": "event-trace-v0",
                    "extractionRunId": f"{enrichment.VLM_MODEL}@ollama",
                },
                parent_event_ids=[event_id],
                root_event_id=root,
                input_event_ids=[event_id],
            )
            append_pointer(ingested.pointer)
        captions.append((scene["tNanos"], text, ingested.event_id))

    for segment_id in index.by_parent(chunk_event_id).get("eventIds", []):
        segment = _pointer(segment_id)
        if not segment or segment["valueKind"] != "asr-segment":
            continue
        already_corrected = any(
            (child := _pointer(child_id)) and child["valueKind"] == "asr-segment"
            for child_id in index.by_parent(segment_id).get("eventIds", [])
        )
        if already_corrected:
            continue
        seg_payload = _payload(segment)
        seg_window = (seg_payload["tStartNanos"] - 15_000_000_000, seg_payload["tEndNanos"] + 15_000_000_000)
        nearby = [(t, c, cid) for (t, c, cid) in captions if seg_window[0] <= t <= seg_window[1]] or captions
        corrected = enrichment.correct_transcript(
            seg_payload["text"],
            seg_payload.get("langHint", "auto"),
            [c for (_, c, _) in nearby],
            labels,
        )
        if not corrected:
            continue
        with ingest_lock:
            ingested = ingestor.ingest_event(
                raw_payload={
                    **seg_payload,
                    "text": corrected,
                },
                observed_at=seg_payload["observedAt"],
                actor_path=["server", "percept-memory", "llm"],
                channel_path=segment["channelPath"],
                value_kind="asr-segment",
                preview=corrected[:160],
                provenance={
                    "source": "percept-memory-server",
                    "observedBy": "percept-memory",
                    "ingestionPipeline": "event-trace-v0",
                    "extractionRunId": f"{enrichment.LLM_MODEL}+visual-context@ollama",
                },
                parent_event_ids=[segment_id],
                root_event_id=root,
                input_event_ids=[segment_id, *[cid for (_, _, cid) in nearby]],
            )
            append_pointer(ingested.pointer)


enrich_queue: "queue.Queue" = queue.Queue()


def enrich_worker() -> None:
    while True:
        item = enrich_queue.get()
        chunk_event_id, force = item if isinstance(item, tuple) else (item, False)
        if IDENT_ENABLED:
            try:
                # Identity first: embeddings take milliseconds, captions minutes.
                identify_chunk(chunk_event_id, force=force)
            except Exception as exc:  # noqa: BLE001 - identity is best-effort
                print(f"identification failed for {chunk_event_id}: {exc}", flush=True)
        try:
            enrich_chunk(chunk_event_id)
        except Exception as exc:  # noqa: BLE001 - enrichment is best-effort
            print(f"enrichment failed for {chunk_event_id}: {exc}", flush=True)


if ENRICH_ENABLED:
    threading.Thread(target=enrich_worker, daemon=True, name="enrich").start()


app = FastAPI(title="percept-memory")


@app.get("/healthz")
def healthz() -> dict:
    return {
        "ok": True,
        "stats": index.state_stats(),
        "replayed": REPLAYED,
        "enrichQueue": enrich_queue.qsize(),
    }


@app.post("/enrich")
def enrich_backfill(
    sessionId: str, force: int = 0, authorization: str | None = Header(None),
) -> dict:
    """Queue enrichment for every audio chunk of a session (backfill).
    force=1 re-runs identity observations (e.g. after threshold retuning)."""
    check_auth(authorization)
    queued = 0
    for event_id in index.by_kind("audio-chunk").get("eventIds", []):
        pointer = _pointer(event_id)
        if pointer and _payload(pointer).get("sessionId") == sessionId:
            enrich_queue.put((event_id, bool(force)))
            queued += 1
    return {"ok": True, "queued": queued}


def gather_reasoning_evidence() -> dict:
    """Scan the trace once into the aggregates the reasoners consume."""
    import collections

    resolutions = collections.defaultdict(list)
    cluster_sessions = collections.defaultdict(set)
    cluster_event_ids = collections.defaultdict(list)
    speaker_langs = collections.defaultdict(list)
    speaker_utterance_ids = collections.defaultdict(list)

    for event_id in index.by_kind("identity-resolution").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        resolutions[pl["clusterId"]].append({"eventId": event_id, "name": pl.get("resolvedName", "")})

    for kind in ("face-observation", "speaker-observation"):
        for event_id in index.by_kind(kind).get("eventIds", []):
            p = _pointer(event_id)
            if not p:
                continue
            pl = _payload(p)
            cid = pl["clusterId"]
            cluster_sessions[cid].add(pl["sessionId"])
            cluster_event_ids[cid].append(event_id)
            if kind == "speaker-observation" and p.get("parentEventIds"):
                asr = _pointer(p["parentEventIds"][0])
                if asr and asr["valueKind"] == "asr-segment":
                    speaker_langs[cid].append(_payload(asr).get("langHint", "auto"))
                    speaker_utterance_ids[cid].append(p["parentEventIds"][0])

    return {
        "resolutions": resolutions,
        "cluster_sessions": cluster_sessions,
        "cluster_event_ids": cluster_event_ids,
        "speaker_langs": speaker_langs,
        "speaker_utterance_ids": speaker_utterance_ids,
    }


def emit_conclusion(reasoner_id: str, conclusion: dict) -> str | None:
    now = "2026-01-01T00:00:00Z"
    for cid in conclusion["evidenceEventIds"]:
        p = _pointer(cid)
        if p:
            now = _payload(p).get("observedAt", now)
            break
    payload = {
        "kind": "raw-payload",
        "schema": "reasoning-conclusion-v0.1",
        "subjectKind": conclusion["subjectKind"],
        "subjectId": conclusion["subjectId"],
        "predicate": conclusion["predicate"],
        "object": conclusion["object"],
        "frequencyPerMille": conclusion["frequencyPerMille"],
        "confidencePerMille": conclusion["confidencePerMille"],
        "positiveEvidence": conclusion["positiveEvidence"],
        "totalEvidence": conclusion["totalEvidence"],
        "statement": conclusion["statement"],
        "observedAt": now,
    }
    with ingest_lock:
        ingested = ingest_or_skip_duplicate(
            raw_payload=payload,
            observed_at=now,
            actor_path=["server", "percept-memory", "reasoner", reasoner_id],
            channel_path=["reasoning", reasoner_id, conclusion["predicate"]],
            value_kind="conclusion",
            preview=conclusion["statement"][:160],
            provenance={
                "source": "percept-memory-server",
                "observedBy": "percept-memory",
                "ingestionPipeline": "event-trace-v0",
                "extractionRunId": f"{reasoner_id}@percept-reasoner",
            },
            # Conclusions are causally grounded in their evidence but span
            # sessions, so they self-root (rootEventId = own eventId).
            parent_event_ids=conclusion["evidenceEventIds"][:32],
            input_event_ids=conclusion["evidenceEventIds"][:32],
        )
    return ingested.event_id if ingested else None


@app.post("/reason")
def reason_endpoint(authorization: str | None = Header(None)) -> dict:
    """Run every reasoner over the current trace and emit conclusion events.
    Idempotent by content address: an unchanged conclusion deduplicates, a
    changed truth value lands as a new revisable conclusion."""
    check_auth(authorization)
    evidence = gather_reasoning_evidence()
    emitted = []
    for reasoner_id, conclusion in reasoning.run_all(evidence):
        event_id = emit_conclusion(reasoner_id, conclusion)
        emitted.append(
            {
                "statement": conclusion["statement"],
                "freq": conclusion["frequencyPerMille"],
                "conf": conclusion["confidencePerMille"],
                "new": event_id is not None,
            }
        )
    return {"ok": True, "conclusions": emitted}


@app.get("/conclusions")
def list_conclusions(authorization: str | None = Header(None)) -> dict:
    """The trace's current conclusions, newest truth per (subject,predicate)."""
    check_auth(authorization)
    latest = {}
    for event_id in index.by_kind("conclusion").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        latest[(pl["subjectId"], pl["predicate"], pl["object"])] = {
            "statement": pl["statement"],
            "frequencyPerMille": pl["frequencyPerMille"],
            "confidencePerMille": pl["confidencePerMille"],
        }
    return {"ok": True, "conclusions": list(latest.values())}


@app.get("/identities")
def list_identities(authorization: str | None = Header(None)) -> dict:
    """Pseudonymous cluster summary: observation counts and any labels."""
    check_auth(authorization)
    return {"ok": True, "identities": identities.summary()}


@app.post("/label")
def label_identity(clusterId: str, name: str, authorization: str | None = Header(None)) -> dict:
    """Attach a human name to a speaker/face cluster (applies prospectively;
    past events keep their pseudonymous clusterId, resolvable via the
    registry). A human label always outranks model resolutions."""
    check_auth(authorization)
    if not identities.label(clusterId, name, method="human"):
        raise HTTPException(status_code=404, detail=f"unknown cluster: {clusterId}")
    return {"ok": True, "clusterId": clusterId, "label": name}


resolve_queue: "queue.Queue[str]" = queue.Queue()


def resolve_worker() -> None:
    while True:
        session_id = resolve_queue.get()
        try:
            resolved = resolve_names(session_id)
            print(f"resolved names for {session_id}: {resolved}", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"name resolution failed for {session_id}: {exc}", flush=True)


if ENRICH_ENABLED:
    threading.Thread(target=resolve_worker, daemon=True, name="resolve").start()


@app.post("/resolve-names")
def resolve_names_endpoint(sessionId: str, authorization: str | None = Header(None)) -> dict:
    """Queue on-screen name resolution for a session's face clusters."""
    check_auth(authorization)
    resolve_queue.put(sessionId)
    return {"ok": True, "queued": sessionId}


@app.post("/retranscribe")
def retranscribe_backfill(sessionId: str, authorization: str | None = Header(None)) -> dict:
    """Re-run segmented archival ASR on a session's chunks (e.g. chunks
    ingested before per-region transcription existed) and queue enrichment.
    Identical segments deduplicate; only improved ones land."""
    check_auth(authorization)
    results = []
    with ingest_lock:
        for event_id in index.by_kind("audio-chunk").get("eventIds", []):
            pointer = _pointer(event_id)
            if not pointer or _payload(pointer).get("sessionId") != sessionId:
                continue
            try:
                outcome = transcribe_chunk(pointer, force=True)
            except Exception as exc:  # noqa: BLE001
                outcome = {"chunk": event_id, "error": str(exc)[:200]}
            if outcome:
                results.append(outcome)
    return {"ok": True, "chunks": results}


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
