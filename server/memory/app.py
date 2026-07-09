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
import time
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


def resolve_names(session_id: str, max_reads_per_cluster: int = 5, candidate_pool: int = 60) -> dict:
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
        # SELECT the sharpest caption crops to read. Most of a cluster's frames
        # are motion-blurred and only a few carry a legible name; reading the
        # highest-detScore frames wastes VLM calls on blurry labels. So bound the
        # pool by detection richness, score each candidate's caption sharpness,
        # and read the sharpest few. Decode each keyframe once, reuse for the read.
        observations.sort(key=lambda o: -o[1].get("detScorePermille", 0))
        scored: list[tuple[float, dict, dict, bytes]] = []
        for obs, opl in observations[:candidate_pool]:
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
                jpeg = da.get_bytes(scene["outputArtifactIds"][0])
                sharp = enrichment.caption_sharpness(jpeg, opl["box"])
            except Exception as exc:  # noqa: BLE001 - selection is best-effort
                print(f"sharpness failed for {obs['eventId']}: {exc}", flush=True)
                continue
            scored.append((sharp, obs, opl, jpeg))
        scored.sort(key=lambda t: -t[0])

        votes: "collections.Counter[str]" = collections.Counter()
        for sharp, obs, opl, jpeg in scored[:max_reads_per_cluster]:
            try:
                name = enrichment.read_name_label(jpeg, opl["box"])
            except Exception as exc:  # noqa: BLE001 - resolution is best-effort
                print(f"name read failed for {obs['eventId']}: {exc}", flush=True)
                continue
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
                        "captionSharpness": round(sharp),
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
    asr_cluster = {}                       # asrEventId -> speaker clusterId
    asr_attr_name = {}                     # asrEventId -> attributed name (glow)

    for event_id in index.by_kind("identity-resolution").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        resolutions[pl["clusterId"]].append({"eventId": event_id, "name": pl.get("resolvedName", "")})

    for kind in ("face-observation", "speaker-observation", "vehicle-observation"):
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
                    asr_cluster[p["parentEventIds"][0]] = cid

    # Screen-glow attributions name the utterance they parent; join to the
    # audio speaker cluster through that shared asr-segment.
    for event_id in index.by_kind("speaker-attribution").get("eventIds", []):
        p = _pointer(event_id)
        if not p or not p.get("parentEventIds"):
            continue
        asr_attr_name[p["parentEventIds"][0]] = {"eventId": event_id, "name": _payload(p).get("attributedName", "")}

    speaker_names = collections.defaultdict(list)   # clusterId -> [{eventId,name}]
    for asr_id, cid in asr_cluster.items():
        hit = asr_attr_name.get(asr_id)
        if hit and hit["name"]:
            speaker_names[cid].append(hit)

    evidence = {
        "resolutions": resolutions,
        "cluster_sessions": cluster_sessions,
        "cluster_event_ids": cluster_event_ids,
        "speaker_langs": speaker_langs,
        "speaker_utterance_ids": speaker_utterance_ids,
        "speaker_names": speaker_names,
        "roster": load_roster(),
    }
    evidence.update(gather_association_evidence())
    return evidence


def gather_association_evidence() -> dict:
    """Cross-modal aggregates for the entity-resolution reasoners: each cluster's
    resolved name (from accumulated has-name conclusions), the modality it lives
    in, the GPS locations where it was observed, and face<->voice temporal
    co-occurrence with the marginals needed for a PMI (lift) guard so a
    video-call grid — where every face is always on screen — does not produce
    spurious bindings."""
    import bisect
    import collections

    # Each cluster's current best name, from the accumulated has-name conclusions
    # (weight competing names by confidence; keep them all for honesty).
    name_conf = collections.defaultdict(dict)
    for event_id in index.by_kind("conclusion").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        if pl.get("predicate") != "has-name":
            continue
        cur = name_conf[pl["subjectId"]].get(pl["object"], 0)
        name_conf[pl["subjectId"]][pl["object"]] = max(cur, pl.get("confidencePerMille", 0))
    cluster_name = {cid: max(v, key=v.get) for cid, v in name_conf.items() if v}

    # Location fixes per session (sorted) for nearest-in-time lookup.
    fixes = collections.defaultdict(list)
    for event_id in index.by_kind("location-fix").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        if pl.get("latE7") is not None:
            fixes[pl["sessionId"]].append((pl.get("tNanos", 0), pl["latE7"], pl["lonE7"]))
    for sid in fixes:
        fixes[sid].sort()
    fix_times = {sid: [f[0] for f in arr] for sid, arr in fixes.items()}

    def nearest_fix(sid: str, t: int):
        arr = fixes.get(sid)
        if not arr:
            return None
        i = bisect.bisect_left(fix_times[sid], t)
        cand = [arr[j] for j in (i - 1, i) if 0 <= j < len(arr)]
        if not cand:
            return None
        best = min(cand, key=lambda f: abs(f[0] - t))
        return (best[1], best[2])

    # Per-cluster observation times + per-session face/speaker streams.
    cluster_latlon = collections.defaultdict(list)
    face_obs = collections.defaultdict(list)   # sid -> [(t, faceCluster)]
    spk_obs = collections.defaultdict(list)    # sid -> [(t0, t1, speakerCluster)]
    for event_id in index.by_kind("face-observation").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        t = pl.get("tNanos", 0)
        face_obs[pl["sessionId"]].append((t, pl["clusterId"]))
        fx = nearest_fix(pl["sessionId"], t)
        if fx:
            cluster_latlon[pl["clusterId"]].append(fx)
    for kind, tfield in (("speaker-observation", "tStartNanos"), ("vehicle-observation", "tNanos")):
        for event_id in index.by_kind(kind).get("eventIds", []):
            p = _pointer(event_id)
            if not p:
                continue
            pl = _payload(p)
            t = pl.get(tfield, 0)
            if kind == "speaker-observation":
                spk_obs[pl["sessionId"]].append((pl.get("tStartNanos", t), pl.get("tEndNanos", t), pl["clusterId"]))
            fx = nearest_fix(pl["sessionId"], t)
            if fx:
                cluster_latlon[pl["clusterId"]].append(fx)

    # Face<->voice co-occurrence within a window, with marginals for PMI.
    window = 2_000_000_000
    cooc = collections.Counter()
    face_marg = collections.Counter()
    spk_marg = collections.Counter()
    total_windows = 0
    for sid, spks in spk_obs.items():
        faces = sorted(face_obs.get(sid, []))
        ftimes = [f[0] for f in faces]
        for t0, t1, scid in spks:
            total_windows += 1
            spk_marg[scid] += 1
            lo = bisect.bisect_left(ftimes, t0 - window)
            hi = bisect.bisect_right(ftimes, t1 + window)
            present = {faces[i][1] for i in range(lo, hi)}
            for fcid in present:
                cooc[(fcid, scid)] += 1
                face_marg[fcid] += 1

    return {
        "cluster_name": cluster_name,
        "cluster_name_conf": {cid: v for cid, v in name_conf.items() if v},
        "cluster_latlon": cluster_latlon,
        "cooc": cooc,
        "cooc_face_marg": face_marg,
        "cooc_spk_marg": spk_marg,
        "cooc_total": total_windows,
    }


def load_roster() -> list:
    """Known-contact names the reasoner may resolve reads to. Prior vocabulary,
    not per-cluster labels — the reasoner still deduces which cluster is whom."""
    try:
        with open(DATA_ROOT / "roster.json") as f:
            data = json.load(f)
        names = data.get("names", data) if isinstance(data, dict) else data
        return [str(n) for n in names if str(n).strip()]
    except Exception:
        return []


def emit_conclusion(reasoner_id: str, conclusion: dict) -> str | None:
    # Evidence may include synthetic "cluster:<id>" placeholders when a reasoner
    # binds clusters rather than events; only real event ids become causal links.
    real_parents = [c for c in conclusion["evidenceEventIds"] if isinstance(c, str) and c.startswith("event:")][:32]
    now = "2026-01-01T00:00:00Z"
    for cid in real_parents:
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
            parent_event_ids=real_parents,
            input_event_ids=real_parents,
        )
    return ingested.event_id if ingested else None


def run_reasoning() -> dict:
    """Run every reasoner over the current trace and emit conclusion events.
    Idempotent by content address: an unchanged conclusion deduplicates, a
    changed truth value lands as a new revisable conclusion."""
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


@app.post("/reason")
def reason_endpoint(authorization: str | None = Header(None)) -> dict:
    check_auth(authorization)
    return run_reasoning()


@app.get("/entities")
def list_entities(authorization: str | None = Header(None)) -> dict:
    """The resolved entity graph, assembled live: each named entity with the
    per-modality clusters bound to it (face / voice / vehicle), its competing
    name candidates with confidence, the sessions it recurs across, and where it
    is usually observed. This is the cross-modal 'who/what is this' view the
    association reasoners deduce."""
    check_auth(authorization)
    ev = gather_reasoning_evidence()
    cluster_name = ev["cluster_name"]
    name_conf = ev["cluster_name_conf"]
    sessions = ev["cluster_sessions"]
    latlon = ev["cluster_latlon"]
    by_name: dict = {}
    for cid, nm in cluster_name.items():
        by_name.setdefault(nm, []).append(cid)
    entities = []
    for name, members in sorted(by_name.items()):
        members = sorted(members)
        pts = [p for m in members for p in latlon.get(m, [])]
        loc = None
        if len(pts) >= 3:
            lat = sorted(p[0] for p in pts)[len(pts) // 2]
            lon = sorted(p[1] for p in pts)[len(pts) // 2]
            loc = {"lat": round(lat / 1e7, 5), "lon": round(lon / 1e7, 5), "fixes": len(pts)}
        sess = sorted(set().union(*[sessions.get(m, set()) for m in members])) if members else []
        entities.append({
            "name": name,
            "members": members,
            "modalities": sorted({m.split("-")[0] for m in members}),
            "sessions": sess,
            "sessionCount": len(sess),
            "usuallyAt": loc,
            "nameCandidates": {m: name_conf.get(m, {}) for m in members},
        })
    entities.sort(key=lambda e: -e["sessionCount"])
    return {"ok": True, "entities": entities}


@app.get("/roster")
def get_roster(authorization: str | None = Header(None)) -> dict:
    check_auth(authorization)
    return {"ok": True, "names": load_roster()}


@app.post("/roster")
async def set_roster(request: Request, authorization: str | None = Header(None)) -> dict:
    """Set the known-contact roster the reasoner snaps noisy name reads to.
    Body: {"names": [...]} or a bare JSON list. Persisted on the data volume."""
    check_auth(authorization)
    body = await request.json()
    names = body.get("names", []) if isinstance(body, dict) else body
    names = [str(n).strip() for n in names if str(n).strip()]
    with open(DATA_ROOT / "roster.json", "w") as f:
        json.dump({"names": names}, f)
    return {"ok": True, "count": len(names)}


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


# --- Speaker attribution from the meeting screen (cameras-off calls) ---------

def load_tiles(session_id: str) -> list:
    """Normalized [x1,y1,x2,y2] tile boxes for a session's meeting grid, in the
    reference frame's coordinates. Prior geometry (set via POST /tiles), like
    the roster is prior vocabulary — the attribution itself is automatic."""
    for name in (f"tiles-{session_id}.json", "tiles.json"):
        try:
            with open(DATA_ROOT / name) as f:
                data = json.load(f)
            tiles = data.get("tiles", data) if isinstance(data, dict) else data
            return [[float(v) for v in box] for box in tiles]
        except Exception:
            continue
    return []


def attribute_speakers(session_id: str) -> dict:
    """Attribute each utterance to the meeting tile glowing during it, name the
    tiles by reading their captions (roster-snapped), and emit a
    speaker-attribution derivation event per utterance, parented to its
    asr-segment. Enhancement (homography registration) + glow live in
    attribute.py; here we gather the trace, name tiles, and record the result."""
    import attribute as attribution

    tiles_norm = load_tiles(session_id)
    if not tiles_norm:
        return {"ok": False, "reason": "no tile geometry set for session (POST /tiles)"}

    frames = []
    utterances = []
    for event_id in index.by_kind("scene-change").get("eventIds", []):
        p = _pointer(event_id)
        if not p or _payload(p).get("sessionId") != session_id or not p.get("outputArtifactIds"):
            continue
        frames.append((_payload(p).get("tNanos", 0), da.get_bytes(p["outputArtifactIds"][0])))
    for event_id in index.by_kind("asr-segment").get("eventIds", []):
        p = _pointer(event_id)
        if not p or _payload(p).get("sessionId") != session_id:
            continue
        pl = _payload(p)
        t0 = pl.get("tStartNanos", pl.get("tNanos", 0))
        utterances.append((event_id, t0, pl.get("tEndNanos", t0)))
    frames.sort()

    result = attribution.attribute_session(frames, utterances, tiles_norm)

    # Name each tile by reading its sharpest few caption crops and voting over
    # roster-snapped reads — a single read misreads surnames (same as faces).
    import collections

    roster = load_roster()
    tile_names = {}
    for idx, cap_list in result["tileCaptions"].items():
        snapped_votes: "collections.Counter[str]" = collections.Counter()
        raw_votes: "collections.Counter[str]" = collections.Counter()
        for cap_jpeg in cap_list:
            try:
                raw = enrichment.read_caption_name(cap_jpeg)
            except Exception as exc:  # noqa: BLE001
                print(f"tile {idx} caption read failed: {exc}", flush=True)
                continue
            if not raw:
                continue
            raw_votes[raw] += 1
            snapped = reasoning.snap_to_roster(raw, roster)[0]
            if snapped:
                snapped_votes[snapped] += 1
        if snapped_votes:
            tile_names[idx] = snapped_votes.most_common(1)[0][0]
        elif raw_votes:
            tile_names[idx] = raw_votes.most_common(1)[0][0]
        else:
            tile_names[idx] = f"tile-{idx}"

    emitted = 0
    for asr_event_id, attr in result["attributions"].items():
        asr = _pointer(asr_event_id)
        if not asr:
            continue
        apl = _payload(asr)
        idx = attr["tileIndex"]
        with ingest_lock:
            ingest_or_skip_duplicate(
                raw_payload={
                    "kind": "raw-payload",
                    "schema": "perception-speaker-attribution-v0.1",
                    "sessionId": session_id,
                    "tileIndex": idx,
                    "attributedName": tile_names.get(idx, f"tile-{idx}"),
                    "method": "active-speaker-glow",
                    "glowVotes": attr["votes"],
                    "glowFrames": attr["frames"],
                    "marginMean": attr["marginMean"],
                    "text": apl.get("text", ""),
                    "observedAt": apl.get("observedAt", "2026-01-01T00:00:00Z"),
                },
                observed_at=apl.get("observedAt", "2026-01-01T00:00:00Z"),
                actor_path=["server", "percept-memory", "speaker-attributor"],
                channel_path=asr["channelPath"],
                value_kind="speaker-attribution",
                preview=f"{tile_names.get(idx)} : {apl.get('text','')[:60]}",
                provenance={
                    "source": "percept-memory-server",
                    "observedBy": "percept-memory",
                    "ingestionPipeline": "event-trace-v0",
                    "extractionRunId": "active-speaker-glow+homography@percept-memory",
                },
                parent_event_ids=[asr_event_id],
                root_event_id=asr["rootEventId"],
                input_event_ids=[asr_event_id],
            )
        emitted += 1

    return {
        "ok": True,
        "registered": result["registered"],
        "medianInliers": result["medianInliers"],
        "tileNames": tile_names,
        "attributed": emitted,
    }


attribute_queue: "queue.Queue[str]" = queue.Queue()


def attribute_worker() -> None:
    while True:
        session_id = attribute_queue.get()
        try:
            out = attribute_speakers(session_id)
            print(f"attributed speakers for {session_id}: {out}", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"speaker attribution failed for {session_id}: {exc}", flush=True)


if ENRICH_ENABLED:
    threading.Thread(target=attribute_worker, daemon=True, name="attribute").start()


@app.get("/tiles")
def get_tiles(sessionId: str, authorization: str | None = Header(None)) -> dict:
    check_auth(authorization)
    return {"ok": True, "tiles": load_tiles(sessionId)}


@app.post("/tiles")
async def set_tiles(sessionId: str, request: Request, authorization: str | None = Header(None)) -> dict:
    """Set the meeting-grid tile boxes for a session, normalized [x1,y1,x2,y2]
    in [0,1] of the reference frame. Body: {"tiles": [[...],...]} or a bare list."""
    check_auth(authorization)
    body = await request.json()
    tiles = body.get("tiles", []) if isinstance(body, dict) else body
    with open(DATA_ROOT / f"tiles-{sessionId}.json", "w") as f:
        json.dump({"tiles": tiles}, f)
    return {"ok": True, "count": len(tiles)}


@app.post("/attribute-speakers")
def attribute_speakers_endpoint(sessionId: str, authorization: str | None = Header(None)) -> dict:
    """Queue meeting-screen speaker attribution for a session."""
    check_auth(authorization)
    attribute_queue.put(sessionId)
    return {"ok": True, "queued": sessionId}


# --- Vehicle re-identification (the automotive analog of face/voice id) ------

def identify_vehicles(session_id: str, min_box_px: int = 56) -> dict:
    """Cluster vehicles by appearance so a car can be recognized across
    appearances and sessions. The on-device tracker already gives each vehicle a
    trackId + a box over its lifetime; where a stored keyframe falls inside that
    lifetime we interpolate the box, crop the vehicle out of the keyframe pixels,
    embed its appearance (vehicle.appearance_embedding), and assign it to a
    persistent cluster — emitting a vehicle-observation parented to the track.
    The recurrence reasoner then surfaces vehicles that recur across sessions."""
    import cv2
    import numpy as np

    import vehicle as veh

    frames = []
    for event_id in index.by_kind("scene-change").get("eventIds", []):
        p = _pointer(event_id)
        if not p or _payload(p).get("sessionId") != session_id or not p.get("outputArtifactIds"):
            continue
        frames.append((_payload(p).get("tNanos", 0), p["outputArtifactIds"][0]))

    tracks = []
    for event_id in index.by_kind("track-segment").get("eventIds", []):
        p = _pointer(event_id)
        if not p:
            continue
        pl = _payload(p)
        if pl.get("sessionId") != session_id or pl.get("label") not in ("car", "truck", "bus"):
            continue
        tracks.append((pl["tStartNanos"], pl["tEndNanos"], pl["boxFirst"], pl["boxLast"],
                       pl["label"], pl.get("trackId"), p))

    emitted = 0
    clusters_seen: "set[str]" = set()
    for t_frame, artifact_id in frames:
        overlapping = [t for t in tracks if t[0] <= t_frame <= t[1]]
        if not overlapping:
            continue
        img = cv2.imdecode(np.frombuffer(da.get_bytes(artifact_id), np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            continue
        H, W = img.shape[:2]
        for t0, t1, bf, bl, label, track_id, tp in overlapping:
            box = veh.interpolate_box(t_frame, t0, t1, bf, bl)
            x1, y1, x2, y2 = (int(box[0]), int(box[1]), int(box[2]), int(box[3]))
            if min(x2 - x1, y2 - y1) < min_box_px:
                continue
            crop = img[max(0, y1):min(H, y2), max(0, x1):min(W, x2)]
            embedding = veh.appearance_embedding(crop)
            if not embedding:
                continue
            cluster_id, similarity = identities.assign("vehicle", embedding)
            clusters_seen.add(cluster_id)
            tpl = _payload(tp)
            with ingest_lock:
                if ingest_or_skip_duplicate(
                    raw_payload={
                        "kind": "raw-payload",
                        "schema": "perception-vehicle-v0.1",
                        "sessionId": session_id,
                        "clusterId": cluster_id,
                        "similarityPermille": similarity,
                        "vehicleType": label,
                        "trackId": track_id,
                        "box": [x1, y1, x2, y2],
                        "tNanos": t_frame,
                        "observedAt": tpl.get("observedAt", "2026-01-01T00:00:00Z"),
                    },
                    observed_at=tpl.get("observedAt", "2026-01-01T00:00:00Z"),
                    actor_path=["server", "percept-memory", "vehicle-id"],
                    channel_path=[tp["channelPath"][0], tp["channelPath"][1], "identity"],
                    value_kind="vehicle-observation",
                    preview=f"{cluster_id} ({label})",
                    provenance={
                        "source": "percept-memory-server",
                        "observedBy": "percept-memory",
                        "ingestionPipeline": "event-trace-v0",
                        "extractionRunId": "vehicle-appearance-v0@percept-memory",
                    },
                    parent_event_ids=[tp["eventId"]],
                    root_event_id=tp["rootEventId"],
                    input_event_ids=[tp["eventId"]],
                ):
                    emitted += 1

    return {"ok": True, "observations": emitted, "clusters": len(clusters_seen)}


vehicle_queue: "queue.Queue[str]" = queue.Queue()


def vehicle_worker() -> None:
    while True:
        session_id = vehicle_queue.get()
        try:
            out = identify_vehicles(session_id)
            print(f"identified vehicles for {session_id}: {out}", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"vehicle identification failed for {session_id}: {exc}", flush=True)


if ENRICH_ENABLED:
    threading.Thread(target=vehicle_worker, daemon=True, name="vehicle").start()


@app.post("/identify-vehicles")
def identify_vehicles_endpoint(sessionId: str, authorization: str | None = Header(None)) -> dict:
    """Queue vehicle re-identification for a session's tracked cars/trucks/buses."""
    check_auth(authorization)
    vehicle_queue.put(sessionId)
    return {"ok": True, "queued": sessionId}


# --- Continuous derivation: keep entities/conclusions current on a schedule ---

REASON_INTERVAL_S = int(os.environ.get("REASON_INTERVAL_S", "900"))   # tier 1: cheap reasoning sweep
AUTO_DERIVE = os.environ.get("AUTO_DERIVE", "1") == "1"               # tier 2: per-session VLM derivations


def _sessions_by_kind(*kinds: str) -> dict:
    """Which sessions contain events of each kind (channelPath[1])."""
    out = {k: set() for k in kinds}
    for k in kinds:
        for event_id in index.by_kind(k).get("eventIds", []):
            p = _pointer(event_id)
            if not p:
                continue
            cp = p.get("channelPath") or []
            if len(cp) >= 2 and cp[0] == "perception":
                out[k].add(cp[1])
    return out


def enqueue_session_derivations() -> dict:
    """Tier 2: enqueue each per-session derivation ONCE, gated by applicability
    and by whether its output already exists — so a new session is derived
    automatically without re-hammering the CPU-only VLM on every tick.

    The VLM-heavy passes (name reads, glow attribution) only auto-run where tile
    geometry has been configured, i.e. a known on-screen/meeting session — that
    keeps the shared ollama from being saturated reading faces in arbitrary
    scenes; any session can still be resolved by hand via the endpoints. Vehicle
    re-id is CPU-cheap (no VLM), so it auto-runs wherever there are vehicles."""
    present = _sessions_by_kind(
        "face-observation", "vehicle-observation", "identity-resolution",
        "speaker-attribution", "session-stop",
    )
    complete = present["session-stop"]          # only derive finished sessions
    tiled = {p.stem[len("tiles-"):] for p in DATA_ROOT.glob("tiles-*.json")}
    # Sessions that actually contain cars/trucks/buses.
    veh_sessions = set()
    for event_id in index.by_kind("track-segment").get("eventIds", []):
        p = _pointer(event_id)
        if p and _payload(p).get("label") in ("car", "truck", "bus"):
            cp = p.get("channelPath") or []
            if len(cp) >= 2:
                veh_sessions.add(cp[1])

    # Persisted attempted-set so a session that yields no output (e.g. all
    # vehicles too small to crop) is not re-attempted every tick.
    done = _load_derived_state()
    queued = {"resolve": [], "attribute": [], "vehicle": []}
    marks = []

    def enqueue(kind: str, q: "queue.Queue", sids: set) -> None:
        for sid in sorted(sids & complete):
            key = f"{sid}:{kind}"
            if key in done:
                continue
            q.put(sid)
            queued[kind].append(sid)
            marks.append(key)

    enqueue("resolve", resolve_queue, (tiled & present["face-observation"]) - present["identity-resolution"])
    enqueue("attribute", attribute_queue, tiled - present["speaker-attribution"])
    enqueue("vehicle", vehicle_queue, veh_sessions - present["vehicle-observation"])
    if marks:
        _save_derived_state(done | set(marks))
    return queued


def _load_derived_state() -> set:
    try:
        return set(json.loads((DATA_ROOT / "derivation-state.json").read_text()))
    except Exception:
        return set()


def _save_derived_state(keys: set) -> None:
    (DATA_ROOT / "derivation-state.json").write_text(json.dumps(sorted(keys)))


def periodic_worker() -> None:
    """Run the cheap reasoning sweep on an interval and, when AUTO_DERIVE is on,
    enqueue any new session's per-session derivations first so they fold in on
    the next passes. Reasoning is content-addressed, so unchanged conclusions
    dedup and this is free when nothing changed."""
    last_count = -1
    time.sleep(60)  # startup grace: let the index finish replaying
    while True:
        try:
            if AUTO_DERIVE:
                q = enqueue_session_derivations()
                if any(q.values()):
                    print(f"auto-derive queued: {q}", flush=True)
            count = sum(len(index.by_kind(k).get("eventIds", []))
                        for k in ("face-observation", "speaker-observation", "vehicle-observation",
                                  "identity-resolution", "speaker-attribution", "asr-segment"))
            if count != last_count:
                out = run_reasoning()
                new = sum(1 for c in out["conclusions"] if c["new"])
                print(f"periodic reasoning: {new} new conclusions ({count} identity/asr events)", flush=True)
                last_count = count
        except Exception as exc:  # noqa: BLE001
            print(f"periodic worker error: {exc}", flush=True)
        time.sleep(REASON_INTERVAL_S)


if ENRICH_ENABLED:
    threading.Thread(target=periodic_worker, daemon=True, name="periodic").start()


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
