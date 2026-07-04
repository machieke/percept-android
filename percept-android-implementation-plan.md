# Implementation Plan: `percept-android` — On-Device Perception → Event-Trace Ingestion

**Target:** Runnable Android app on Moto G84 5G (Snapdragon 695, Adreno 619, 8/12 GB RAM, Android 13/14) that runs local perception over live camera video and microphone audio, converts perception output into `event-trace-v0.1` envelopes per the `machieke/event-trace-memory` schema contract, stores them in an on-device content-addressed DA store, and dispatches batched bundles to a sync endpoint.

**Non-goals (v1):** VLM captioning, on-device claim extraction / NAL truth values, Rholang pointer publication (server-side concern), multi-camera, background recording without visible foreground-service notification.

**Prime directive for the harness:** CID/eventId parity with the Python reference implementation is the highest-priority invariant. Phase 1 golden-vector tests must pass before any perception code is written. If a design choice ever trades convenience against byte-exact canonical JSON parity, parity wins.

---

## 0. Schema Contract Summary (source of truth: `machieke/event-trace-memory`)

Clone the repo into `reference/` inside the project workspace. It is the oracle for all parity tests.

```
git clone https://github.com/machieke/event-trace-memory reference/event-trace-memory
```

### 0.1 Canonical JSON (`event_trace_memory/canonical.py`)

Canonical bytes = UTF-8 of JSON serialized with: **keys sorted lexicographically (by Unicode code point), separators `,` and `:` (no whitespace), non-ASCII characters emitted literally (NOT `\uXXXX`-escaped), NaN/Infinity forbidden**. This is Python `json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)`.

Identity derivations:

| Artifact | Derivation |
|---|---|
| `cid_for_bytes(data)` | `"cidv0-local-sha256:" + sha256hex(data)` |
| `payloadCid` | `cid_for_bytes(canonical_json_bytes(raw_payload))` |
| `eventCid` | `cid_for_bytes(canonical_json_bytes(envelope))` |
| `eventId` | `"event:" + sha256hex(canonical_json_bytes(envelope))` (same digest as eventCid) |
| `content_id(kind, value)` | `kind + ":" + sha256hex(canonical_json_bytes(value))` |

### 0.2 Numeric policy (MANDATORY)

**No floats anywhere in any canonicalized payload or envelope.** Python's shortest-roundtrip float repr and JVM `Double.toString` are not byte-compatible; a single float breaks cross-implementation CID parity. Encode:

- confidences/scores → `scorePerMille` (int, 0–1000)
- box coordinates → int pixels in model input space, `[x1, y1, x2, y2]`
- timestamps → int nanoseconds (monotonic) and second-resolution ISO strings (wall clock)
- audio levels/embedding distances → int per-mille or fixed micro-units, named with the unit suffix (`*PerMille`, `*Nanos`, `*Micro`)

The Kotlin canonical serializer MUST reject `Float`/`Double` inputs at runtime (throw), so violations fail loudly in tests rather than silently diverging.

### 0.3 Envelope shape (`event-trace-v0.1`)

```json
{
  "kind": "event-trace",
  "schema": "event-trace-v0.1",
  "time": {"iso": "2026-07-04T12:00:41Z", "year": 2026, "month": 7, "day": 4, "hour": 12, "minute": 0, "second": 41},
  "actorPath": ["device", "moto-g84-5g", "camera", "0"],
  "channelPath": ["perception", "sess-0001", "video"],
  "value": {"kind": "track-segment", "contentType": "application/json", "payloadCid": "cidv0-local-sha256:…", "preview": "optional ≤160 chars"},
  "provenance": {"source": "android-percept", "observedBy": "percept-app", "ingestionPipeline": "event-trace-v0", "extractionRunId": "…"},
  "causal": {"parentEventIds": ["event:…"], "rootEventId": "event:…", "inputEventIds": [], "outputArtifactIds": []}
}
```

Rules replicated from `ingestion.py`:

- `time` is built from the wall-clock `observedAt` ISO string via `parse_utc_time`: normalize to UTC, `iso` rendered with trailing `Z` (i.e. Python `isoformat()` with `+00:00` replaced by `Z`). Second resolution only — **sub-second ordering lives exclusively in the payload** (`tStartNanos` / `tEndNanos` monotonic ints anchored by the session-start event). Do NOT add extra fields to the envelope; keep it schema-pure so fixtures reproduce across implementations.
- `value.preview` is included only when non-null; when absent the key must be entirely absent (presence changes canonical bytes).
- Root event: session-start has empty `parentEventIds` and `rootEventId: null` in the envelope; its pointer's `rootEventId` falls back to its own `eventId`. Child events set `rootEventId` to the session-start `eventId`.

### 0.4 Event pointer (`event-pointer-v0.1`) — SQLite index row

Mirror `ingestion.py` pointer construction exactly: `timePath = [year, month, day, hour]`; `timePrefixKeys` are zero-padded `["/2026", "/2026/07", "/2026/07/04", "/2026/07/04/12"]`; `actorPrefixKeys`/`channelPrefixKeys` come from `paths.prefix_keys` — each path segment percent-encoded with `safe=""` (i.e. `/` inside a segment becomes `%2F`), keys are cumulative prefixes `"/a"`, `"/a/b"`, ….

### 0.5 DA store layout (port of `FileDA`)

```
<appFilesDir>/da/objects/<sha256hex>          # raw bytes
<appFilesDir>/da/manifests/<sha256hex>.json   # {cid, codec, sizeBytes, sha256}
```

Semantics: `put` is idempotent (skip write if object exists); `get` re-verifies digest on read and errors on mismatch; codecs: `dag-json` for canonical JSON objects, `raw` for binary blobs (JPEG keyframes). Manifest JSON is non-canonical (pretty-printed) and excluded from parity tests except for field presence.

---

## 1. Golden Test Vectors (generated from the Python reference; embed verbatim in unit tests)

### 1.1 Canonicalization edge vectors

| Input (conceptual) | Canonical bytes (UTF-8) | sha256 |
|---|---|---|
| `{"a":1,"b":[true,false,null],"c":"héllo/世界","d":{"z":1,"y":2}}` | `{"a":1,"b":[true,false,null],"c":"héllo/世界","d":{"y":2,"z":1}}` | `eec415221dd0565eb75dbe922a01f0a53dd0de3427d28bbcfff8319780f6a178` |
| `{"emoji":"🚲","quote":"\"q\"","slash":"a\\b","ctrl":"line\nbreak\ttab"}` | `{"ctrl":"line\nbreak\ttab","emoji":"🚲","quote":"\"q\"","slash":"a\\b"}` | `99a0a0f93e7d568c7cf56fcc1a110bad4f5043b7c7acd9bc23d52e70dc936095` |
| `{"neg":-42,"zero":0,"big":9007199254740991}` | `{"big":9007199254740991,"neg":-42,"zero":0}` | `2c94976a5419a37e61093c89e04772ce50c87ba9dfc421685a394a6a39dc54c9` |

Notes the serializer must honor: control characters escaped as `\n`, `\t`, `\u00XX` style short escapes exactly as Python's `json` module emits them (`\n`, `\t`, `\r`, `\"`, `\\`, and `\u001f`-style for other C0); emoji and CJK emitted as raw UTF-8; key sort is by UTF-16 code unit order in Python — for safety, restrict all map keys in this codebase to ASCII so code-point vs code-unit ordering can never diverge (enforce with a serializer-level check + lint test).

### 1.2 Session-start event (root)

Payload:
```json
{"kind": "raw-payload", "schema": "perception-session-v0.1", "device": "moto-g84-5g", "sessionId": "sess-0001", "monotonicAnchorNanos": 123456789000, "observedAt": "2026-07-04T12:00:00Z"}
```
Ingested with `observedAt="2026-07-04T12:00:00Z"`, `actorPath=["device","moto-g84-5g","app","percept"]`, `channelPath=["perception","sess-0001","session"]`, `valueKind="session-start"`, `preview="session sess-0001 start"`, `provenance={"source":"android-percept","observedBy":"percept-app","ingestionPipeline":"event-trace-v0"}`.

Expected:
```
payloadCid = cidv0-local-sha256:6561e92f73fd964ebb5eae0c0f2c312c0b81346dc0993396e138625ef980fb03
eventId    = event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b
eventCid   = cidv0-local-sha256:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b
```

### 1.3 Track-segment event (child of 1.2)

Payload:
```json
{"kind": "raw-payload", "schema": "perception-track-segment-v0.1", "sessionId": "sess-0001", "trackId": 7, "label": "person", "labelSpace": "coco-80", "scorePerMille": 874, "tStartNanos": 1500000000, "tEndNanos": 41500000000, "frameCount": 412, "boxFirst": [120, 88, 340, 460], "boxLast": [180, 92, 400, 470], "observedAt": "2026-07-04T12:00:41Z"}
```
Ingested with `observedAt="2026-07-04T12:00:41Z"`, `actorPath=["device","moto-g84-5g","camera","0"]`, `channelPath=["perception","sess-0001","video"]`, `valueKind="track-segment"`, no preview, `parentEventIds=[<session eventId>]`, `rootEventId=<session eventId>`, `provenance={"source":"android-percept","observedBy":"percept-app","ingestionPipeline":"event-trace-v0","extractionRunId":"yolov8n-int8-320@litert-gpu-v1"}`.

Expected:
```
payloadCid = cidv0-local-sha256:d090e40e8a30d21980c60e95d4cb368e7c0f94e2667469d0142f0aaf39892fdb
eventId    = event:a5a4f6db55fd7abf26756ed062316486d51ebe294895d2ea1a31c5509b51019f
eventCid   = cidv0-local-sha256:a5a4f6db55fd7abf26756ed062316486d51ebe294895d2ea1a31c5509b51019f
```

Full expected canonical envelope bytes for 1.3 (single line):
```
{"actorPath":["device","moto-g84-5g","camera","0"],"causal":{"inputEventIds":[],"outputArtifactIds":[],"parentEventIds":["event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b"],"rootEventId":"event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b"},"channelPath":["perception","sess-0001","video"],"kind":"event-trace","provenance":{"extractionRunId":"yolov8n-int8-320@litert-gpu-v1","ingestionPipeline":"event-trace-v0","observedBy":"percept-app","source":"android-percept"},"schema":"event-trace-v0.1","time":{"day":4,"hour":12,"iso":"2026-07-04T12:00:41Z","minute":0,"month":7,"second":41,"year":2026},"value":{"contentType":"application/json","kind":"track-segment","payloadCid":"cidv0-local-sha256:d090e40e8a30d21980c60e95d4cb368e7c0f94e2667469d0142f0aaf39892fdb"}}
```

---

## 2. Event Taxonomy (v1)

All payloads carry `"kind": "raw-payload"`, a payload `schema` string, `sessionId`, `observedAt`, and monotonic-nanos timing fields. All child events parent to the session-start event unless noted; scene-scoped events additionally parent to the current scene-change event (multiple parents allowed by schema).

| `value.kind` | Payload schema | Emitted when | Key payload fields |
|---|---|---|---|
| `session-start` | `perception-session-v0.1` | Recording starts | device, sessionId, `monotonicAnchorNanos` ↔ `observedAt` anchor pair, model manifest (list of `extractionRunId`s active) |
| `session-stop` | `perception-session-stop-v0.1` | Recording stops | counters: frames processed, events emitted, drops, thermal throttle events |
| `scene-change` | `perception-scene-v0.1` | Scene gate fires | `sceneIndex`, `tNanos`, gate metric per-mille, keyframe artifact CID in `causal.outputArtifactIds` |
| `track-segment` | `perception-track-segment-v0.1` | Object track closes (or 30 s heartbeat split for long-lived tracks) | trackId, label, labelSpace, `scorePerMille` (max over track), tStart/tEndNanos, frameCount, boxFirst/boxLast |
| `audio-tag-segment` | `perception-audio-tag-v0.1` | YAMNet class run-length closes | label (AudioSet), labelSpace, scorePerMille (mean), tStart/tEndNanos |
| `asr-segment` | `perception-asr-v0.1` | whisper.cpp finalizes a segment | text, langHint, tStart/tEndNanos, `avgLogProbMicro` (int micro-units), preview = first 160 chars of text |

Keyframes: JPEG bytes → `da.putBytes(codec="raw")`; the returned CID goes only into the scene-change event's `outputArtifactIds`. One keyframe per scene, quality 70, longest edge 640 px.

Aggregation invariant: **no per-frame events, ever.** Track segments, tag run-lengths, and ASR segments are the atomic granularity. Target steady-state rate: ≤ 0.5 events/s typical scene, ≤ 5 events/s worst case.

---

## 3. Module Architecture (Gradle multi-module, Kotlin, minSdk 31, targetSdk 34)

```
:core:canonical      pure JVM. Canonical JSON serializer, sha256, CID helpers, path/prefix/time helpers. Zero Android deps.
:core:da             pure JVM + java.io. FileDA port. Depends on :core:canonical.
:core:trace          pure JVM. Envelope builder (EventIngestor port), payload dataclasses, event taxonomy constants. Depends on :core:canonical, :core:da.
:core:index          Android lib. Room DB mirroring event-pointer-v0.1 rows + prefix-key tables + dispatch-state column. 
:perception:video    Android lib. CameraX ImageAnalysis → LiteRT detector → tracker → scene gate → TraceSink.
:perception:audio    Android lib. AudioRecord fan-out → whisper.cpp JNI + YAMNet TFLite → TraceSink.
:dispatch            Android lib. Bundle exporter + WorkManager uploader.
:app                 Foreground service, single-screen Compose UI (preview, live event log, start/stop, session stats), settings.
```

`:core:*` modules being pure JVM is deliberate: all parity tests run on the host JVM in CI without an emulator.

### 3.1 `:core:canonical` — the critical module

Implement a dedicated serializer over an internal `CanonicalValue` sum type (`CMap` — sorted `TreeMap<String,…>` with ASCII-only key enforcement, `CList`, `CString`, `CLong`, `CBool`, `CNull`). Do NOT use kotlinx.serialization/Gson/Moshi for canonical bytes — none of them reproduce Python's escaping and separator behavior reliably; hand-roll the ~100-line emitter against the edge vectors in §1.1. Public API:

```kotlin
fun canonicalBytes(v: CanonicalValue): ByteArray
fun sha256Hex(b: ByteArray): String
fun cidForBytes(b: ByteArray): String            // "cidv0-local-sha256:…"
fun contentId(kind: String, v: CanonicalValue): String
fun parseUtcTime(iso: String): TimeParts          // port of paths.parse_utc_time
fun prefixKeys(path: List<String>): List<String>  // percent-encode safe="" per segment
fun timePrefixKeys(t: TimeParts): List<String>
```

Percent-encoding must match Python `urllib.parse.quote(s, safe="")`: uppercase hex, encode everything outside unreserved `A–Z a–z 0–9 _ . - ~`.

### 3.2 `:core:trace` — ingestor port

Port `EventIngestor.ingest_event` and `log_child_event` 1:1, including: payload → DA first, envelope assembly in the exact field structure of §0.3, conditional `preview`, eventId/eventCid derivation, pointer construction, `rootEventId` fallback. `TraceSink` is the single funnel all perception threads write to (a `Channel<PendingEvent>` consumed by one ingestion coroutine → serialized DA + Room writes; no locking subtleties).

### 3.3 `:perception:video`

- CameraX `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, YUV → RGB, analysis resolution 640×480, detector input 320×320 letterboxed.
- Detector: **EfficientDet-Lite0 int8 via MediaPipe Tasks `ObjectDetector`** as the v1 default (lowest integration risk, GPU delegate supported on Adreno 619); `extractionRunId = "efficientdet-lite0-int8-320@mediapipe-<ver>-gpu"`. Keep the detector behind an interface so YOLOv8n-int8 via LiteRT `Interpreter` can be swapped in behind a build flag in v1.1.
- Tracker: minimal IoU-based ByteTrack-lite (Kalman optional; constant-velocity or even pure IoU matching is sufficient at 10–15 fps). Track closes after 15 consecutive missed frames → emit `track-segment`. Tracks alive > 30 s emit a segment and continue with the same `trackId` (heartbeat split), so long presences aren't invisible until they end.
- Scene gate v1: **detection-set change + luminance-histogram L1 distance** (both integer-computable, no extra model). Gate fires → keyframe JPEG → `scene-change` event. MobileCLIP embedding gate is a v2 item behind the same interface.
- Frame budget: target 10 fps analysis; drop frames rather than queue. Count drops for `session-stop`.

### 3.4 `:perception:audio`

- One `AudioRecord` at 16 kHz mono PCM16, fan-out ring buffer to two consumers.
- **whisper.cpp** via the upstream `whisper.android` JNI bindings (git submodule; build with CMake/NDK, `-O3`, fp16 kv, 4 threads). Model: `ggml-tiny-q8_0` bundled in assets (~43 MB). Streaming: 5 s window, 1 s overlap, emit on finalized segments; VAD-gate (simple energy threshold) to skip silent windows. `extractionRunId = "whisper-tiny-q8_0@whispercpp-<commit>-cpu4"`.
- **YAMNet** TFLite (float16 or int8) on 0.975 s frames, hop 0.5 s; class run-length encoding over top-1 above 0.3 (i.e. 300 per-mille) → `audio-tag-segment` on run close. Suppress the `Speech` class when whisper is active (redundant with ASR segments).

### 3.5 Timing discipline

Single source: `SystemClock.elapsedRealtimeNanos()`. Session-start payload records the anchor pair (`monotonicAnchorNanos`, wall-clock `observedAt`). Every payload's `tStartNanos`/`tEndNanos` are monotonic-anchored (nanos since anchor). Envelope `observedAt` per event = anchor wall clock + (tEndNanos), truncated to seconds for `time` per §0.3. Camera timestamps come from `ImageProxy.imageInfo.timestamp` (already `elapsedRealtimeNanos` domain on this device class — verify in M4 and fall back to receipt time if the HAL uses a different clock base); audio timestamps from sample counting against the record-start monotonic timestamp.

### 3.6 `:dispatch`

- Bundle format: a directory (zipped) `bundle-<sessionId>-<seq>/` containing `objects/` (DA objects, digest-named), `manifests/`, and `pointers.jsonl` (one event-pointer per line, canonical JSON). Idempotent by construction — everything is content-addressed; the server dedupes on digest.
- Transport v1a: **local export** to `Downloads/percept-bundles/` + share intent (adb-pull/manual workflow; unblocks end-to-end testing with zero server work). Transport v1b: WorkManager periodic + charging-constrained upload, `PUT /bundles/<bundleId>` multipart or chunked zip to a configurable HTTPS endpoint with bearer token from settings; exponential backoff; Room `dispatchState` column (PENDING/BUNDLED/ACKED) drives retry.
- Retention: DA objects deletable after server ACK, keyframes last; configurable local cap (default 2 GB) with oldest-ACKed-first eviction.

### 3.7 `:app`

Foreground service (`camera|microphone` FGS types, Android 14 compliant), runtime permissions (CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS), single Compose screen: preview with detection overlay, scrolling event ticker (valueKind + preview + eventId prefix), session stats (events, fps, drops, thermal status via `PowerManager.getThermalHeadroom`), start/stop, settings (endpoint, token, model toggles). On `THERMAL_STATUS_SEVERE`: halve analysis fps; on `CRITICAL`: suspend video path, keep audio (audio is the cheap modality).

---

## 4. Milestones (each gated by acceptance criteria; do not proceed on red)

**M0 — Skeleton.** Gradle multi-module project builds; empty app installs and shows permission flow. *Accept: `./gradlew assembleDebug` green; app launches on device/emulator.*

**M1 — Canonical core + golden parity.** `:core:canonical`, `:core:da`, `:core:trace` complete. Unit tests: all §1.1 edge vectors byte-exact; §1.2/§1.3 golden events reproduce payloadCid/eventId/eventCid exactly; serializer throws on Float/Double and non-ASCII keys; `prefixKeys`/`timePrefixKeys` match reference outputs for segments containing `/`, spaces, and Unicode. *Accept: host-JVM test suite green; this is the hard gate for everything else.*

**M2 — Python parity harness (CI job).** Script: run the Kotlin ingestor over a fixture list of 50 synthetic events (JVM CLI in `:core:trace`), export a bundle, then run a Python script using `reference/event-trace-memory` to (a) `verify()` every CID, (b) re-canonicalize every envelope and compare digests, (c) validate every object against the JSON Schemas in `reference/…/schemas/` (jsonschema lib). *Accept: parity script exits 0 in CI.*

**M3 — Storage + index on device.** Room index with pointer rows and prefix-key query support (`eventsByTimePrefix`, `eventsByChannelPrefix`, `childrenOf`); instrumented test writes 1 000 events, queries by hour shard and channel prefix, verifies a random sample of CIDs from disk. *Accept: instrumented tests green on emulator.*

**M4 — Video pipeline.** CameraX → detector → tracker → scene gate → real `track-segment`/`scene-change` events into the live ingestor; overlay UI. Bench on device: sustained ≥ 8 fps analysis over 10 min at ≤ SEVERE thermal, event rate within §2 bounds, zero ingestion-channel overflows. *Accept: 10-min soak log meets bounds; manual review of one exported bundle shows sane tracks.*

**M5 — Audio pipeline.** whisper.cpp JNI built for arm64-v8a; YAMNet path; both emit real segments concurrently with video. Bench: whisper realtime factor ≤ 1.2 on 5 s windows with video running; no audio dropouts (ring-buffer overrun counter = 0). *Accept: soak with simultaneous speech + objects produces correctly interleaved, monotonically ordered `tStartNanos` across modalities.*

**M6 — Dispatch + end-to-end.** Local export and HTTPS upload paths; retention/eviction; `session-stop` counters. End-to-end test: record 5-min real session → export → Python parity harness verifies bundle → load into reference `EventTraceIndex` and confirm hour-shard and channel queries return the session's events. *Accept: reference implementation ingests a phone-produced bundle with zero verification errors.*

---

## 5. Dependencies (pin exact versions at M0; do not float)

androidx CameraX (camera2 + lifecycle + view), MediaPipe Tasks Vision (`object-detector`), LiteRT (org.tensorflow:tensorflow-lite + gpu delegate) for YAMNet, whisper.cpp as git submodule (NDK r26, CMake), Room, WorkManager, kotlinx-coroutines, Compose BOM. Models fetched by a Gradle download task with sha256 pinning into `assets/models/`: `efficientdet_lite0_int8.tflite`, `yamnet.tflite`, `ggml-tiny-q8_0.bin`. Every model file's sha256 becomes part of its `extractionRunId` registry entry (a static Kotlin map in `:core:trace`), so provenance survives model swaps.

## 6. Risks / decided defaults (change only with justification in the PR description)

1. **Float leakage** into canonical payloads via careless payload construction → mitigated by serializer-level rejection (M1 test).
2. **Whisper + detector CPU contention** on 695's 2×A78 + 6×A55: pin whisper to 4 threads, detector to GPU delegate; if GPU delegate init fails on this driver, fall back to XNNPACK 2 threads and drop analysis to 6 fps rather than starving audio.
3. **Camera timestamp clock base** ambiguity → M4 explicitly asserts `imageInfo.timestamp` is in the `elapsedRealtimeNanos` domain (compare against a captured reference); fall back documented in §3.5.
4. **Envelope purity vs sub-second ordering**: decided — envelope stays second-resolution/schema-pure; all fine timing in payloads. Downstream PCMCI-style analysis reads payloads, not envelopes.
5. **16 KB page size / NDK**: build whisper.cpp with `-Wl,-z,max-page-size=16384` for Android 15+ forward-compat.
6. **Privacy**: ASR text and keyframes are the sensitive artifacts; v1 keeps everything local until explicit upload, endpoint is user-configured, and the FGS notification is always visible while recording. Hashed-path privacy transforms (reference `privacy.py`) are a server-side concern, out of scope on-device.
