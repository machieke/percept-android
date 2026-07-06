# percept-android

A phone that perceives, and a server that remembers.

The Android app runs local perception over live camera and microphone input —
object tracking, scene detection, audio tagging, speech recognition — and
records everything as content-addressed, cryptographically verifiable
`event-trace-v0.1` events. Events stream to a self-hosted memory server
within a second of happening; the full session audio follows as compressed
artifacts. The server accumulates the trace continuously, transcribes the
archival audio at server-model quality, and enriches it with VLM scene
captions and context-corrected transcripts. The phone is a lean buffer: once
the server acknowledges an event, the phone sheds its artifacts.

## Why it matters

**A perception log you can trust byte-for-byte.** Every payload and envelope is
serialized as canonical JSON and addressed by its sha256 (`eventId`,
`eventCid`, `payloadCid`). The Kotlin implementation reproduces the Python
reference implementation ([`machieke/event-trace-memory`](https://github.com/machieke/event-trace-memory))
exactly — same bytes, same digests — enforced by golden-vector tests and
harnesses that re-verify every exported bundle with the reference code. The
memory server verifies each event against its content address before
accepting it. Cross-implementation CID parity is the prime directive; one
consequence is **no floats anywhere** — confidences are integer per-mille,
timestamps integer nanoseconds, and the serializer throws on `Float`/`Double`.

**Events, not frames.** The pipeline never emits per-frame records. Object
tracks, audio-tag run-lengths, ASR segments, and scene changes are the atomic
granularity, each carrying monotonic nanosecond timing and causal parent
links back to the session root. Because all modalities share one timeline in
one causal graph, cross-modal queries are lookups — "what was visible while
this was said" needs no fusion step. The enrichment layer exploits exactly
that: some transcription errors are acoustically unfixable (Dutch final
devoicing makes *mosterd* sound like *mostert*), and only temporally-aligned
visual context can recover them.

**Self-hosted end to end.** Audio and transcripts leave the device only to
infrastructure you run: the ASR and memory services are docker compose
deployments, models are sha256-pinned downloads, and the recommended
transport is your own WireGuard tailnet (works with headscale), so the phone
reaches the server from any network — Wi-Fi to 5G mid-sentence — without
anything becoming public. Recording only ever runs behind a visible
camera+microphone foreground-service notification.

## How it works

```
PHONE (lean buffer)
camera ─► CameraX ─► EfficientDet (MediaPipe, GPU→CPU probe) ─► IoU tracker ─► track-segment
                └──► luminance histogram ─► debounced scene gate ─► scene-change (+JPEG keyframe)
mic ────► AudioRecord ─► ring buffer ─┬► VAD ─► ASR windows ──────► asr-segment (live)
                                      ├► YAMNet (TFLite) ─► RLE ──► audio-tag-segment
                                      └► 60 s chunks ─► Opus ─────► audio-chunk (full session audio)

all events ─► canonical envelopes ─► content-addressed DA store + Room index
          ─► LIVE: POST /events to the memory server (sub-second, ACK ⇒ evict)
          ─► BACKFILL: idempotent zip bundles (in-session, at stop, hourly sweeper)

SERVER (accumulating memory)                      SERVER (ASR)
percept-memory :8124                              percept-asr :8123
  verify CIDs ─► FileDA + pointer log               parakeet-tdt-0.6b-v3 int8 (sherpa-onnx)
  audio-chunk ─► archival ASR per speech region     RTF ~0.05 on CPU, multilingual
  keyframes ──► VLM captions (ollama)               /transcribe (live PCM windows)
  transcripts ─► LLM correction w/ visual context   /transcribe-file (Opus chunks)
```

Live speech recognition is remote-first: the phone POSTs VAD-gated PCM
windows to the ASR server (~250 ms per 5 s window) and falls back per-window
to an on-device Zipformer (sherpa-onnx) — and whisper.cpp as last resort —
when the network drops. Every model, local or remote, is pinned by sha256 and
recorded per-event as an `extractionRunId`, so provenance survives any swap.
Server-side derived events (archival `asr-segment`s, `scene-caption`s,
corrected transcripts) are causally parented to the events they derive from;
competing interpretations coexist in the trace with honest provenance.

## Layout

| Component | Contents |
|---|---|
| `:core:canonical` | Pure JVM. Canonical JSON serializer (byte-compatible with the Python reference), sha256/CID helpers, path/time prefix keys. |
| `:core:da` | Pure JVM. Content-addressed file store, idempotent puts, digest-verified gets. |
| `:core:trace` | Pure JVM. Envelope builder, ingestion funnel, event taxonomy, session time base, model registry. |
| `:core:index` | Room database mirroring `event-pointer-v0.1` rows with prefix-key tables and dispatch state. |
| `:perception:video` | Detector interface + MediaPipe adapter, IoU tracker, debounced scene gate, thermal governor, CameraX analyzer. |
| `:perception:audio` | Ring buffer, VAD, ASR window/lag-skip engine, tag RLE, chunk recorder, Opus encoder, remote/Zipformer/whisper ASR adapters. |
| `:dispatch` | Bundle exporter/uploader, live event streamer, WorkManager workers, post-ACK retention. |
| `:app` | Foreground service, session controller, Compose UI (ticker, stats, endpoints). |
| `server/asr` | Docker compose: Parakeet via sherpa-onnx; PCM window + compressed-file transcription with speech-region segmentation. |
| `server/memory` | Docker compose: bundle/event ingest with CID verification, persistent DA + replayed index, archival transcription, VLM/LLM enrichment (ollama). |

## Building and verifying

Host-side (no device or emulator needed):

```bash
./gradlew test assembleDebug             # full test suite + APK
python3 scripts/run_parity_harness.py    # 50-event fixture vs Python reference
python3 scripts/run_synthetic_m6_harness.py
python3 scripts/run_live_engine_harness.py   # real engines end to end, reference-verified
```

Servers (any docker host; models are downloaded and sha256-verified on first start):

```bash
(cd server/asr && docker compose up -d)      # :8123
(cd server/memory && docker compose up -d)   # :8124, host networking
```

Enrichment needs an [ollama](https://ollama.com) instance with a vision model
(`ollama pull gemma3:4b` by default; configure via `VLM_MODEL`/`LLM_MODEL`).

On the phone, set **Remote ASR URL** to the ASR server and **Sync endpoint
URL** to the memory server (tailnet addresses recommended). Everything else
is automatic: live streaming, periodic bundle backfill, and post-ACK
eviction.

Device builds bundle whisper.cpp for arm64 with `-PwhisperNative` (requires
the `third_party/whisper.cpp` submodule and NDK 26); without the flag no NDK
is needed anywhere.

## Status

Functionally complete end to end, validated with real device sessions
(Moto G84 5G): phone-produced bundles pass full reference verification, live
events reach the server sub-second over a roaming tailnet, archival Dutch
audio transcribes with per-utterance timing, and enrichment produces scene
captions and context-corrected transcripts from real keyframes. Remaining
work per the [implementation plan](percept-android-implementation-plan.md):
formal performance/thermal soak benchmarks on device, and enrichment quality
tuning (stronger corrector models, sharper keyframe selection).
