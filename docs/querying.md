# Querying and recall on the memory server

This document explains how to get things back out of the episodic memory the
server accumulates: what the store is, the recall primitives it is built
around, and worked cross-modal queries against real sessions.

## The store

The memory server (`server/memory`) keeps everything in two places on its
data volume:

```
/data/pointers.jsonl          append-only, one event-pointer-v0.1 per line
/data/da/objects/<sha256>     content-addressed: canonical payloads, envelopes, and raw artifacts
/data/identities.json         speaker/face cluster registry
```

Each **pointer** is the index row for one event. Its shape (see
`event-pointer-v0.1`):

| field | meaning |
|---|---|
| `eventId` / `eventCid` | `event:<sha256>` and `cidv0-local-sha256:<sha256>` — same digest |
| `payloadCid` | content address of the raw payload object (the data) |
| `outputArtifactIds` | content addresses of attached blobs (keyframe JPEG, Opus chunk) |
| `valueKind` | the event type (`asr-segment`, `scene-change`, `location-fix`, …) |
| `actorPath` | who produced it — `["device", <id>, "camera", "0"]`, or `["server","percept-memory","llm"]` for derived events |
| `channelPath` | `["perception", <sessionId>, <modality>]` |
| `parentEventIds` / `rootEventId` | causal links; the root is the session-start event |
| `timePrefixKeys` / `channelPrefixKeys` / `actorPrefixKeys` | precomputed range keys (below) |

The **payload** (fetched by `payloadCid`) holds the actual data — `text`,
`latE7`/`lonE7`, `label`, `clusterId`, monotonic `tStartNanos`/`tEndNanos`,
etc. Payloads are integer-only and canonical; the pointer never duplicates
them, so recall is: filter pointers, then resolve the payloads you need.

## Two recall surfaces today

1. **The reference index** (`event_trace_memory.indexes.EventTraceIndex`),
   which the server replays `pointers.jsonl` into at startup. In-process it
   offers `by_time_prefix`, `by_channel_prefix`, `by_actor_prefix`, `by_kind`,
   `by_parent`, `by_root`, `by_payload_cid`, `get_event`.
2. **Direct reads** of `pointers.jsonl` + `da/objects` — dependency-free,
   which is what `scripts/recall.py` and the examples below use.

There is not yet a general HTTP query endpoint; recall runs where the data
is. The HTTP surface is currently ingest + identity (`/healthz`,
`/identities`, `/label`, `/enrich`, `/retranscribe`, `/bundles`, `/events`).

## The recall primitives

Everything the trace is designed for reduces to five lookups.

**By time shard.** `timePrefixKeys` are zero-padded cumulative wall-clock
prefixes: `["/2026", "/2026/07", "/2026/07/06", "/2026/07/06/08"]`. Ask
`by_time_prefix("/2026/07/06/08")` for everything in the 08:00 hour.

**By channel prefix.** `channelPrefixKeys` are
`["/perception", "/perception/<sessionId>", "/perception/<sessionId>/<modality>"]`.
`by_channel_prefix("/perception/<sessionId>")` returns a whole session;
appending `/video`, `/audio`, `/location`, `/identity`, `/pose`,
`/environment`, `/network`, `/power`, `/motion`, `/session` narrows to a
modality.

**By kind.** `by_kind("asr-segment")` — all events of a type, across sessions.

**By causal parent / root.** `by_parent(eventId)` returns derived children
(an archival transcript's speaker-observation, a scene's caption, a track's
correction); `by_root(sessionStartId)` returns the whole session. This is
how server-side derivations attach to their sources without mutating them.

**By content address.** `by_payload_cid` / `by_event_cid` / `get_event` for
exact resolution and dedup.

## Cross-modal recall: the point of the design

Because every modality shares one monotonic timeline under one causal root,
"what was X when Y" is a time-range intersection, never a fusion step. The
`scripts/recall.py` helper wraps these patterns; run it wherever `/data` is
reachable:

```bash
# inside the running container
docker exec percept-memory python3 /tmp/recall.py --data /data sessions
# or against a copied-out volume
python3 scripts/recall.py --data ./data sessions
```

**List sessions:**

```
$ recall.py --data /data sessions
sess-20260707-084850  11.6 min   2156 events      # dashcam
sess-20260707-123646   7.8 min    533 events      # tennis
```

**A session's full multimodal timeline** (`timeline --session …`) interleaves
every kind by time, tagged with its producer (`device`, or `asr`/`llm`/`vlm`/
`speaker-id`/`face-id` for server derivations).

**Everything around a moment** — the core cross-modal query. Give a timestamp
and window; get every modality that overlaps it:

```
$ recall.py --data /data around --session sess-20260707-123646 --t 107 --window 4
t=  104.4 asr-segment          Actually.
t=  106.4 track-segment        airplane          # detector misfire on a lobbed ball
t=  107.4 asr-segment          Thank you.
t=  107.4 speaker-observation  speaker-4          # attributed to a known voice
```

**Speech joined to place** — each utterance stamped with the location fix in
effect at that instant (`speech-where`):

```
$ recall.py --data /data speech-where --session sess-20260707-123646
t=    6.8 @(51.15099,4.67800)  "Do I zegt no da oefen so."
t=   10.5 @(51.15099,4.67800)  "Wow, elke balie."
```

**Who said what** — utterances resolved to their speaker cluster through the
causal parent link (`speakers`):

```
$ recall.py --data /data speakers --session sess-20260707-123646
speaker-4    sim= 813  "And uh"
speaker-3    sim= 901  "Do I zegt no da oefen so."
speaker-2    sim= 757  "Can you mind?"
```

These four lines each implement one primitive combination; read
`scripts/recall.py` as the template for your own (e.g. "keyframes while the
phone was out of pocket" = `by_channel_prefix(.../video)` intersected with
the `proximity=far` intervals; "sessions at home" = `network-context` with a
known `ssid`).

## Identity recall and labeling

Voices and faces are clustered into persistent pseudonymous ids that recur
across sessions. Inspect and name them over HTTP:

```bash
curl -s http://<memory>:8124/identities | jq          # counts + labels per cluster
curl -X POST "http://<memory>:8124/label?clusterId=speaker-2&name=Coach"
```

Labeling is prospective: future `speaker-observation`/`face-observation`
events carry the `label`; past events keep their `clusterId`, which resolves
to the name through the registry. So `speaker-4` recurring across three
different days' sessions is one person recognized before being named — label
the cluster once and every past and future utterance is attributed.

## Provenance and competing interpretations

Derived events never overwrite their sources, so recall often returns several
readings of the same moment. An utterance can appear as:

- the **live** `asr-segment` (`actorPath[...]="microphone"`, the phone's
  low-latency Parakeet result),
- the **archival** one (`"asr"`, server re-transcription of the full audio
  chunk, per speech region — usually better),
- a **corrected** one (`"llm"`, visual-context correction),

each parented to the previous and stamped with its `extractionRunId` in
provenance. Choose by producer: the `speech-where` example filters to `asr`
(archival) as the authoritative transcript; a live dashboard would prefer
`microphone`. The same holds for scene captions (`vlm`) and identity
(`speaker-id`/`face-id`). Nothing is lost, and every reading is attributable
to the exact model that produced it.

## Verifying what you recall

Every object is content-addressed, so any recalled event is checkable:
`sha256(payload bytes)` must equal the `payloadCid` digest, and
`sha256(canonical(envelope))` the `eventId`. The server already enforces this
on ingest (it rejects any event failing verification), and
`scripts/verify_bundle.py` re-checks an exported bundle end to end against the
Python reference. Recall therefore returns data you can prove the phone
produced, unmodified.
