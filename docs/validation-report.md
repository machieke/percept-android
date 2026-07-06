# Validation report — device sessions, 2026-07-05 → 2026-07-06

Results from validating the full pipeline with real sessions on the target
device (Moto G84 5G), from first APK install through the complete
phone-buffer / server-memory architecture. Every claim below was measured on
real session data; each fix landed as a commit referenced by its subject.

## Integrity (prime directive)

Eight phone-produced bundles verified by the Python reference implementation
with **zero errors, cumulative**: every CID re-derived, every envelope
re-canonicalized byte-exact, all pointers ingested by the reference index.
The memory server re-verifies every event (envelope canonicality, digests,
referenced objects) before accepting it. Cross-implementation parity held
through every architecture change.

## ASR: on-device → remote

| Stage | RTF (5 s window) | Notes |
|---|---|---|
| whisper tiny q8, on-device, initial | **6.6** (~33 s/window) | 4 threads fighting the CPU detector; stop blocked minutes |
| + language pinned, no temp fallback | ~5.9 | autodetect was not the bottleneck |
| + encoder context capped (`audio_ctx`) | **2.56** (~12.8 s) | whisper pads to 30 s; cap ≈ 5× encoder saving |
| Zipformer 20M int8 on-device | < 1 | quality unsatisfying |
| **Parakeet-TDT 0.6B v3 (server, CPU)** | **0.05–0.07** | 686–750 ms/window end-to-end incl. tailnet |

Architecture consequences that shipped along the way: lag-skip to the
freshest window when behind (transcripts sparse-but-current instead of
minutes late), one window per drain tick + abortable whisper (instant stop),
90 s ring buffer (no overruns since).

## Transport and roaming

- Live events reach the memory server **sub-second** (`POST /events`
  measured 5 ms server-side + ~65 ms tailnet DERP hop); bundles are
  idempotent backfill only. Stream-ACKed events are evicted from the phone
  (lean buffer, 256 MB cap).
- Headscale control plane on an IPv6-only VPS; phone↔server WireGuard
  survives Wi-Fi→5G roaming, with per-window fallback to on-device ASR
  covering handoff seconds.
- Session audio ships as Ogg/Opus chunks: 22.4 s in 91 KB (~8× vs PCM),
  encoded by the phone's MediaCodec.

## Transcription quality (Dutch test sessions)

| Utterance (truth) | Live 5 s window | Archival (chunk) |
|---|---|---|
| "…these **shoes**" | "these **choose**" | "these **shoes**" ✅ full context wins |
| "Dit is de **hond**" | "de **hold**" | "de **hond**" ✅ |
| "Dat is **mosterd**" | "mostert" (devoiced [t] — acoustically correct) | "nostert" |
| "…met **kalfsworst**" | "kalzorst" | "kalslost" |

Key findings and fixes:

- **Whole-chunk language ID fails on sparse speech**: a 60 s Dutch chunk was
  transcribed as English gibberish ("Dat is kaas" → "That is gas") because
  silence diluted the language evidence. Fixed by energy-VAD segmentation —
  archival decoding now runs per speech region with per-utterance timing.
  Legacy chunks recoverable via `POST /retranscribe`.
- **Dutch final devoicing makes some errors acoustically unfixable**
  (*mosterd* is pronounced *mostert*): recovery requires world context, which
  motivated the enrichment layer.

## Enrichment (VLM captions + LLM correction)

- **Blurry keyframes were structural**: scene changes fire *because* the
  camera moves, so the gate frame was systematically the blurriest. With
  sharpest-in-window selection (mean *squared* luminance gradient — plain
  |gradient| is provably blur-invariant for edges and was rejected by test),
  captions went from hallucination ("a yellow can with a cactus design" for a
  mustard jar) to accuracy: *"a brown dog is lying on a wooden floor"*,
  *"a portrait of **Albert Einstein**"* — each matching the Dutch utterance
  spoken at the same timestamp.
- **The corrector needs an acoustic leash.** Observed failures: "kalslost" →
  "kaas" (scene-plausible, sound-implausible) and "zonnebril" → "bril"
  (information-dropping). The corrector prompt now makes sound-similarity the
  hard constraint, and a code guard requires each replaced span to score
  ≥ 0.70 similarity (max of raw and phonetically-normalized, covering Dutch
  final devoicing). Measured separation on all real cases: good fixes
  0.71–1.00, bad ones ≤ 0.67. `gpt-oss:20b` was rejected as corrector on
  latency (1.5–15+ min/utterance on CPU); `gemma3:4b` retained.
- Corrections and captions land as **derived events causally parented to
  their sources** with honest `extractionRunId`s — wrong interpretations stay
  in the trace as attributed history, never overwriting the originals.

## Known limits (current state)

- Archival ASR still language-flips on short/code-switched utterances
  ("This is a tekening van Einstein").
- Enrichment latency is ~5 min per caption on the CPU-only server — fine for
  memory, not for live reasoning.
- Correction recall is bounded by caption quality; precision is now
  structurally protected by the acoustic guard.
- The *mosterd* end-to-end recovery (sharp keyframe of the jar → caption
  naming mustard → corrector fixing the devoiced ending) has not yet been
  reproduced in a fresh session — the original session predates sharp
  keyframes.
- Formal M4/M5 performance/thermal soak benchmarks remain open.

## Video pipeline (same sessions)

- Scene gate: 32 scene changes in 48 frames (initial) → 2–9 well-spaced
  scenes per session after hysteresis + cooldown.
- Analysis throughput: ~4.7 fps → ~7.2 fps after removing the JPEG
  round-trip (integer YUV→RGB at half resolution) and freeing CPU from
  whisper; camera HAL timestamps confirmed in the elapsedRealtime domain
  (risk 3 closed, zero fallbacks across all sessions).
