# percept-android

On-device perception → event-trace ingestion for Android. The app runs local
computer-vision and audio models over live camera and microphone input,
aggregates what they see and hear into a small stream of semantic events, and
records those events as content-addressed, cryptographically verifiable
`event-trace-v0.1` envelopes that sync to a server as idempotent bundles.

Everything stays on the device until you explicitly export or upload it.

## Why it matters

**A perception log you can trust byte-for-byte.** Every payload and envelope is
serialized as canonical JSON and addressed by its sha256 (`eventId`,
`eventCid`, `payloadCid`). The Kotlin implementation reproduces the Python
reference implementation ([`machieke/event-trace-memory`](https://github.com/machieke/event-trace-memory))
exactly — same bytes, same digests — which is enforced by golden-vector tests
and CI harnesses that re-verify every exported bundle with the reference code.
Cross-implementation CID parity is the project's prime directive: if a design
choice ever trades convenience against byte-exact parity, parity wins. One
consequence: **no floats anywhere** — confidences are integer per-mille,
timestamps are integer nanoseconds, and the serializer throws on
`Float`/`Double` so violations fail loudly in tests instead of silently
diverging between JVM and Python.

**Events, not frames.** The pipeline never emits per-frame records. Object
tracks, audio-tag run-lengths, ASR segments, and scene changes are the atomic
granularity (≈0.5 events/s in a typical scene), each carrying monotonic
nanosecond timing and causal parent links back to the session root. The result
is a compact, queryable, causally-structured record of what a device perceived
— suitable for downstream memory systems, retrieval, and causal analysis —
rather than a firehose of detections.

**Local-first and private by construction.** Recording only runs behind a
visible camera+microphone foreground-service notification. Events, keyframes,
and transcripts live in an on-device content-addressed store and a Room index;
they leave the device only via explicit export (a zip you can `adb pull`) or an
upload endpoint you configure. Bundles are idempotent by construction — every
object is content-addressed, so the server dedupes on digest and retries are
always safe.

## How it works

```
camera ──► CameraX 640×480 ──► EfficientDet-Lite0 (MediaPipe) ──► IoU tracker ──► track-segment
                          └──► luminance histogram ─────────────► scene gate  ──► scene-change (+JPEG keyframe)

mic ────► AudioRecord 16 kHz ─► ring buffer ─┬► VAD ─► whisper.cpp (5 s / 1 s overlap) ─► asr-segment
                                             └► YAMNet (0.975 s / 0.5 s hop) ─► run-length ─► audio-tag-segment

all events ──► bounded channel ──► single ingestion coroutine ──► canonical JSON envelope
           ──► content-addressed DA store (objects + manifests) + Room pointer index
           ──► bundle exporter (zip: objects/, manifests/, pointers.jsonl)
           ──► local export  or  WorkManager upload (charging + unmetered, exponential backoff)
```

Session lifecycle is bracketed by `session-start` (which anchors monotonic
time to wall-clock time) and `session-stop` (which records counters: frames
processed, events emitted, drops, ring-buffer overruns, thermal throttling).
Envelope timestamps are second-resolution and schema-pure; all sub-second
ordering lives in payload `tStartNanos`/`tEndNanos` fields.

## Modules

| Module | Contents |
|---|---|
| `:core:canonical` | Pure JVM. Canonical JSON serializer (byte-compatible with Python `json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False)`), sha256/CID helpers, path/time prefix keys. |
| `:core:da` | Pure JVM. Content-addressed file store (`objects/<sha256>`, `manifests/<sha256>.json`), idempotent puts, digest-verified gets. |
| `:core:trace` | Pure JVM. Envelope builder, ingestion funnel (`PerceptionSession`), event taxonomy, session time base, model-provenance registry. |
| `:core:index` | Room database mirroring `event-pointer-v0.1` rows with time/actor/channel prefix-key tables and dispatch state. |
| `:perception:video` | Detector interface + MediaPipe adapter, IoU tracker, scene-change gate, thermal frame governor, CameraX analyzer. |
| `:perception:audio` | Ring buffer, energy VAD, ASR windowing, tag run-length encoder, YAMNet adapter, whisper.cpp JNI boundary, AudioRecord pipeline. |
| `:dispatch` | Bundle exporter/uploader, retention planner, WorkManager upload worker. |
| `:app` | Foreground service, session controller, Compose UI (start/stop, live event ticker, stats, export/upload, settings). |

The `:core:*` modules are pure JVM by design: all parity-critical code runs on
the host in CI without an emulator or device.

## Building and verifying

```bash
./gradlew test assembleDebug        # full host test suite + debug APK
python3 scripts/run_parity_harness.py        # 50-event fixture, verified by the Python reference
python3 scripts/run_synthetic_m6_harness.py  # synthetic 5-minute multimodal session bundle
python3 scripts/run_live_engine_harness.py   # real engines + fake models, end to end
```

Each harness exports a bundle from the Kotlin side and then uses the Python
reference implementation to re-verify every CID, re-canonicalize every
envelope, validate schemas, and load the pointers into the reference index.
`reference/event-trace-memory` is cloned automatically on first run.

Model assets (EfficientDet-Lite0 int8, YAMNet, whisper tiny q8_0) are fetched
at build time by `:app:downloadModels` with pinned sha256 hashes — they are
never committed. Each model's hash is also registered in
`ExtractionRuns` (`:core:trace`), so every event's `extractionRunId` maps to
exact model bytes.

### whisper.cpp native build (optional)

ASR uses whisper.cpp via JNI. The native build is opt-in so host tests and CI
never need the NDK:

```bash
git submodule update --init third_party/whisper.cpp
./gradlew :app:assembleDebug -PwhisperNative   # packages libwhisper_percept.so (arm64-v8a)
```

Without the flag (or on devices where the library is absent) the app runs with
ASR disabled and everything else intact.

## Status

Code-complete and host-verified through the M6 milestone of the
[implementation plan](percept-android-implementation-plan.md). What remains
requires physical hardware (target: Moto G84 5G): sustained-fps and thermal
soak benchmarks, whisper realtime-factor measurement, GPU-delegate behavior on
the Adreno 619 driver, the camera timestamp clock-base assertion against real
HAL output, and the final acceptance test of a phone-produced bundle ingested
by the reference implementation.
