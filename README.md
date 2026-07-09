# percept-android

A phone that perceives, and a server that remembers, recognizes — and reasons.

The Android app runs local perception over live camera, microphone, and the
phone's sensor suite, recording everything as content-addressed,
cryptographically verifiable `event-trace-v0.1` events across sixteen
modalities: object tracks, scenes with keyframes, live and archival speech,
audio tags, full-session audio, location and GPS velocity, motion state,
camera pointing direction, ambient light, pocket state, network identity,
and power. Events stream to a self-hosted memory server within a second of
happening. The server accumulates the trace continuously, transcribes the
archival audio at server-model quality, captions keyframes with a VLM,
corrects transcripts using visual context, and clusters voices, faces, and
vehicles into persistent pseudonymous identities. Above the observations, a
reasoning layer accumulates **conclusions** — named identities, cross-modal
entities, recurring places, candidate changes — each carried as a trace
event with an explicit truth value, revised as evidence accumulates. The
phone is a lean buffer: once the server acknowledges an event, the phone
sheds its artifacts. A read-only web browser over the trace shows every
event, entity, item, and conclusion, linked back to its evidence.

## Why it matters

**A perception log you can trust byte-for-byte.** Every payload and envelope is
serialized as canonical JSON and addressed by its sha256 (`eventId`,
`eventCid`, `payloadCid`). The Kotlin implementation reproduces the Python
reference implementation ([`machieke/event-trace-memory`](https://github.com/machieke/event-trace-memory))
exactly — same bytes, same digests — enforced by golden-vector tests and
harnesses that re-verify every exported bundle with the reference code. The
memory server verifies each event against its content address before
accepting it. Cross-implementation CID parity is the prime directive; one
consequence is **no floats anywhere** — coordinates are degrees ×10⁷,
confidences integer per-mille, timestamps integer nanoseconds, and the
serializer throws on `Float`/`Double`.

**Events, not streams.** No modality emits per-frame or per-sample records:
every source is gated into human-timescale run-lengths and change events
(a scene change, a 10 m movement, a pocket transition, an order-of-magnitude
light change), each carrying monotonic nanosecond timing and causal parent
links back to the session root. Because all modalities share one timeline in
one causal graph, cross-modal queries are lookups — "what was visible while
this was said, where, and by whom" needs no fusion step. The enrichment
layer exploits exactly that: some transcription errors are acoustically
unfixable (Dutch final devoicing makes *mosterd* sound like *mostert*), and
only temporally-aligned visual context can recover them.

**Understanding accumulates like perception.** Server-side reasoners scan the
evidence and emit `conclusion` events carrying a Non-Axiomatic-Logic truth
value — a `{frequency, confidence}` pair, integer per-mille — causally
parented to the exact events they were drawn from. Nothing is hand-labeled:
a name is a conclusion the reasoner became confident about (on-screen
caption reads, roster-snapped, voted); a person is a face cluster and a
voice cluster bound by evidence; a place is a revisited GPS cell; a change
is a diff between passes. Conclusions are content-addressed like everything
else, so re-running a reasoner dedups unchanged truths and lands revised
ones as new revisable history — competing interpretations coexist with
honest provenance. Weak evidence stays honest: modalities whose embeddings
are known to over-merge (far-field voices, classical vehicle appearance)
carry a larger evidential horizon, and an entity resting only on them is
flagged uncorroborated until a reliable modality (a face) supports it.

**Identity without a leak.** Voices, faces, and vehicles are embedded
server-side and clustered into pseudonymous ids (`speaker-4`, `face-1`,
`vehicle-12`) that recur across sessions; names attach only when you label a
cluster or a reasoner concludes one from on-screen evidence. The biometric
templates live in exactly one file on your own volume — never in the trace,
never off your infrastructure.

**Self-hosted end to end.** Audio, transcripts, and identities leave the
device only to infrastructure you run: all services are docker compose
deployments, models are sha256-pinned downloads, and the recommended
transport is your own WireGuard tailnet (works with headscale), so the phone
reaches the server from any network — Wi-Fi to 5G mid-sentence — without
anything becoming public. Recording only ever runs behind a visible
foreground-service notification.

## How it works

```
PHONE (lean buffer)
camera ─► CameraX ─► EfficientDet (MediaPipe, GPU→CPU probe) ─► IoU tracker ─► track-segment
                └──► luminance histogram ─► debounced scene gate ─► scene-change
                                            (keyframe = sharpest frame in a 1 s window)
mic ────► AudioRecord ─► ring buffer ─┬► VAD ─► ASR windows ──────► asr-segment (live)
                                      ├► YAMNet (TFLite) ─► RLE ──► audio-tag-segment
                                      └► 60 s chunks ─► Opus ─────► audio-chunk (full audio)
GPS ────► gated fixes + Doppler speed/bearing ─────────────────────► location-fix
IMU ────► linear-accel RMS run-lengths ────────────────────────────► motion-segment
        └► rotation vector ─► camera elevation/azimuth (rate-capped) ─► camera-pose
light/proximity/connectivity/battery ─► gated change events ───────► ambient-light,
                                          proximity, network-context (SSID), power-state

all events ─► canonical envelopes ─► content-addressed DA store + Room index
          ─► LIVE: POST /events to the memory server (sub-second, ACK ⇒ evict)
          ─► BACKFILL: idempotent zip bundles (in-session, at stop, hourly sweeper)

SERVER (accumulating memory :8124)              SERVER (ASR :8123)
verify CIDs ─► FileDA + pointer log               parakeet-tdt-0.6b-v3 int8 (sherpa-onnx)
audio-chunk ─► archival ASR per speech region     RTF ~0.05 on CPU, multilingual
asr-segment ─► voice embedding ─► speaker-observation      SERVER (identity :8125)
keyframes ──► face embeddings ──► face-observation           wespeaker CAM++ voices
keyframes ──► VLM captions (ollama) ─► scene-caption         insightface faces
transcripts ─► LLM correction (acoustic-similarity guarded)

DERIVATIONS (auto-run per completed session, or per endpoint)
track-segments × keyframes ─► vehicle crops ─► appearance clusters ─► vehicle-observation
meeting keyframes ─► homography-stabilized view ─► active-speaker glow ─► speaker-attribution
                 └─► super-res-stacked captions ─► on-screen name reads ─► identity-resolution
keyframes ─► open-vocab VLM inventory ─► item-observation (brands/labels read)
prominent COCO tracks ─► VLM re-label ─► object-observation ("carrot" → flower pot)
revisited GPS cells (adjacent-merged) ─► per-pass VLM description ─► place-observation
                                          (spot vs drive-by, speed + bearing recorded)

REASONING (periodic sweep, conclusion events with NAL {frequency, confidence})
identity-namer      name reads, roster-snapped + voted   →  face-2 has-name '…'
speaker-namer       glow attribution joined to voice     →  speaker-2 has-name '…'
entity-resolver     same name across modalities          →  face↔voice same-as, one entity
recurrence          cluster seen across sessions         →  recurring entity (weak modalities
                                                            demand more evidence)
location-binder     GPS fix at observation times         →  usually-at (lat,lon)
place-recurrence    revisited GPS cells                  →  place revisited in N sessions
place-change        stable-feature diff, same bearing    →  candidate change (capped conf)
item-recurrence     named item across sessions           →  persistent object in a space

BROWSER (:8131, read-only)  sessions · cross-modal timelines · entities · items ·
image fragments per observation · causal links · conclusions · map links
```

Live speech recognition is remote-first (the LAN/tailnet Parakeet answers a
5 s window in ~250 ms) with per-window fallback to on-device Zipformer and
whisper.cpp. Every model, local or remote, is pinned by sha256 and recorded
per-event as an `extractionRunId`. Server-side derived events are causally
parented to the events they derive from; competing interpretations coexist
in the trace with honest provenance, and `/retranscribe` + `/enrich?force=1`
re-run improved pipelines over old chunks with content-address dedup making
identical re-runs free.

Derivations run continuously without intervention: identity embeddings race
ahead in their own worker (milliseconds per chunk, never queued behind the
CPU VLM), a periodic worker enqueues each completed session's applicable
derivations exactly once (vehicles wherever there are car tracks; the
VLM-heavy name reads and glow attribution only where meeting-tile geometry
is configured, so a single CPU ollama is never saturated), and the reasoning
sweep re-runs every `REASON_INTERVAL_S`, folding new evidence into revised
conclusions. Voiceprints are gated on audio quality: speech overlapping a
Music tag (car radio) never feeds the speaker registry.

## Layout

| Component | Contents |
|---|---|
| `:core:canonical` | Pure JVM. Canonical JSON serializer (byte-compatible with the Python reference), sha256/CID helpers, path/time prefix keys. |
| `:core:da` | Pure JVM. Content-addressed file store, idempotent puts, digest-verified gets. |
| `:core:trace` | Pure JVM. Envelope builder, ingestion funnel, the full event taxonomy, session time base, model registry. |
| `:core:index` | Room database mirroring `event-pointer-v0.1` rows with prefix-key tables and dispatch state. |
| `:perception:video` | Detector interface + MediaPipe adapter, IoU tracker, debounced scene gate, sharpness-selected keyframes, thermal governor, CameraX analyzer. |
| `:perception:audio` | Ring buffer, VAD, ASR window/lag-skip engine, tag RLE, Opus chunk recorder, remote/Zipformer/whisper ASR adapters. |
| `:dispatch` | Bundle exporter/uploader, live event streamer, WorkManager workers, post-ACK retention. |
| `:app` | Foreground service, session controller, sensor trackers (location, motion, pose, environment, network, power), Compose UI with capture-quality knobs. |
| `server/asr` | Docker compose: Parakeet via sherpa-onnx; PCM window + compressed-file transcription with speech-region segmentation. |
| `server/memory` | Docker compose: bundle/event ingest with CID verification, persistent DA + replayed index, archival transcription, VLM/LLM enrichment, identity clustering + registry (`identity.py`), speaker attribution from meeting screens (`attribute.py`), vehicle re-id (`vehicle.py`), open-vocab items, place resolution, and the reasoning layer (`reason.py`) with its periodic scheduler. |
| `server/ident` | Docker compose: stateless voice (wespeaker CAM++) and face (insightface) embedding endpoints. |
| `server/browser` | Docker compose: read-only single-page trace browser on the memory volume (`:ro`) — sessions, timelines, entities, items, observation image crops, causal links, DMS map links. |

## Building and verifying

Host-side (no device or emulator needed):

```bash
./gradlew test assembleDebug             # full test suite + APK
python3 scripts/run_parity_harness.py    # 50-event fixture vs Python reference
python3 scripts/run_synthetic_m6_harness.py
python3 scripts/run_live_engine_harness.py   # real engines end to end, reference-verified
```

Servers (any docker host; models are downloaded and sha256-verified on first
start):

```bash
(cd server/asr && docker compose up -d)      # :8123 transcription
(cd server/ident && docker compose up -d)    # :8125 voice/face embeddings
(cd server/memory && docker compose up -d)   # :8124 memory, host networking
(cd server/browser && docker compose up -d)  # :8131 trace browser (read-only)
```

Enrichment needs an [ollama](https://ollama.com) instance with a vision model
(`ollama pull gemma3:4b` by default; configure via `VLM_MODEL`/`LLM_MODEL`).
Identity clustering thresholds are env-tunable (`SPEAKER_SIM_THRESHOLD` /
`FACE_SIM_THRESHOLD` / `VEHICLE_SIM_THRESHOLD`), the reasoning cadence via
`REASON_INTERVAL_S`, and auto-derivation via `AUTO_DERIVE` / `AUTO_ITEMS`.

The memory server's surface beyond ingest: `GET /entities` (the resolved
cross-modal graph), `GET /conclusions`, `POST /reason`, `GET /identities` +
`POST /label`, `POST /roster` (known-contact names the reasoners snap noisy
reads to), `POST /tiles` (meeting-grid geometry per session), and per-session
derivation triggers — `/resolve-names`, `/attribute-speakers`,
`/identify-vehicles`, `/identify-items`, `/reclassify-tracks`,
`/resolve-places`, `/retranscribe`, `/enrich`. See
[docs/querying.md](docs/querying.md) for recall patterns and
`scripts/recall.py` for cross-modal queries over the store.

On the phone, set **Remote ASR URL** to the ASR server and **Sync endpoint
URL** to the memory server (tailnet addresses recommended). Camera, mic, and
notifications are required; location is optional (sessions run without it).
Everything else is automatic: live streaming, periodic bundle backfill, and
post-ACK eviction.

Device builds bundle whisper.cpp for arm64 with `-PwhisperNative` (requires
the `third_party/whisper.cpp` submodule and NDK 26); without the flag no NDK
is needed anywhere.

## Status

Functionally complete and field-validated on the target device (Moto G84 5G)
across meetings (cameras on and off), dashcam drives, a mall stress test,
in-car and walkthrough sessions — see
[docs/validation-report.md](docs/validation-report.md). Measured results
include reference-verified bundles, the ASR RTF progression from 6.6
on-device to 0.05 remote, roaming Wi-Fi→5G sessions, cross-session speaker
and face recurrence (the same meeting participants recognized across
separately recorded sessions), speaker↔name attribution from the
active-speaker glow on a cameras-off call, GPS-anchored recurring places,
and open-vocabulary shed inventory (38 labeled items where the COCO detector
produced only confabulations).

Known limits, deliberately represented rather than hidden: far-field voice
embeddings and the classical vehicle appearance descriptor over-merge, so
their recurrence is down-weighted and flagged until corroborated (a deep
vehicle-reID model and cleaner per-speaker audio are the upgrades — both are
single swap points); small screen-photographed faces and license plates sit
at the capture-resolution ceiling; the CPU-only ollama is the enrichment
throughput bottleneck (a GPU is the fix); and place change detection emits
capped-confidence *candidates*, since single-frame VLM variance is
indistinguishable from real change.
