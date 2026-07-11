"""Reasoning layer: conclusions deduced over the accumulated trace.

Understanding accumulates like perception — as content-addressed events.
A reasoner scans the evidence (observations, resolutions, transcripts) and
emits `conclusion` events (reasoning-conclusion-v0.1) carrying a
Non-Axiomatic-Logic truth value: a {frequency, confidence} pair.

- frequency = fraction of evidence that supports the conclusion
- confidence = certainty that grows with the amount of evidence,
  confidence = N / (N + k) for total evidence N and horizon k

Both are integer per-mille (no floats reach a canonical payload). Nothing
is hand-labeled: a name, a recurring identity, a habitual place are all
conclusions the reasoner became more or less confident about. Re-running
produces fresh conclusion events (new truth => new bytes => new address);
prior conclusions remain as revisable history, parented to their evidence.
"""

import collections
import os
import re
import statistics

EVIDENTIAL_HORIZON = 1  # NAL k: one more counterexample would halve certainty growth

# Modalities whose embeddings are proven to over-merge on this capture (phone-mic
# far-field voices ~0.03 separation; classical vehicle appearance tail overlap).
# Their recurrence needs far more evidence before it is believed, and an entity
# built only from them stays low-confidence unless a reliable modality (a face)
# corroborates it.
WEAK_MODALITIES = {"speaker", "vehicle", "animal"}
WEAK_HORIZON = 6

# Below this bigram-cosine, a read is a total misread (wrong first name) and is
# dropped rather than snapped — keeps 'Mr Kennedy' from becoming 'Kyle Klemmer'.
ROSTER_SNAP_THRESHOLD = int(os.environ.get("ROSTER_SNAP_THRESHOLD_PERMILLE", "300")) / 1000


def nal_truth(positive: int, total: int, horizon: int = EVIDENTIAL_HORIZON) -> tuple[int, int]:
    """Return (frequencyPerMille, confidencePerMille). A larger horizon demands
    more evidence for the same confidence — used to distrust weak modalities."""
    if total <= 0:
        return 0, 0
    frequency = round(1000 * positive / total)
    confidence = round(1000 * total / (total + horizon))
    return frequency, confidence


def _first_name(name: str) -> str:
    return name.strip().split()[0] if name.strip() else ""


def _name_bigrams(s: str) -> set:
    s = "^" + "".join(c if (c.isalnum() or c == " ") else " " for c in s.lower()).strip() + "$"
    return {s[i : i + 2] for i in range(len(s) - 1)}


def bigram_cosine(a: str, b: str) -> float:
    A, B = _name_bigrams(a), _name_bigrams(b)
    if not A or not B:
        return 0.0
    return len(A & B) / ((len(A) * len(B)) ** 0.5)


def snap_to_roster(name: str, roster: list, threshold: float = ROSTER_SNAP_THRESHOLD) -> tuple:
    """Snap a noisy read to the nearest known identity by character-bigram
    cosine. The VLM reads first names reliably but hallucinates surnames off
    low-res on-screen labels, so a roster of known contacts (prior vocabulary,
    not a hand label on this cluster) repairs 'Kyle Anderson' -> 'Kyle Klemmer'
    while a total misread ('Mr Kennedy') stays below threshold and is dropped.
    A predictive-coding / Storkey-Hopfield associative memory was evaluated for
    this and collapsed on the sparse, correlated name codes into one spurious
    attractor; nearest-neighbour is both simpler and correct here. Returns
    (snapped_name, similarity) or (None, 0.0)."""
    best, best_sim = None, 0.0
    for cand in roster:
        sim = bigram_cosine(name, cand)
        if sim > best_sim:
            best, best_sim = cand, sim
    return (best, best_sim) if best_sim >= threshold else (None, 0.0)


def name_conclusions(evidence: dict) -> list[dict]:
    """Deduce a name per identity cluster from the on-screen-label reads
    (identity-resolution events).

    With a roster of known contacts, each noisy read is snapped to the nearest
    known identity (nearest-neighbour repairs the hallucinated surname); the
    cluster's name is the roster entry the most reads agree on. Frequency is
    kept honest by dividing by ALL named reads, so misreads that failed to snap
    lower it. Without a roster, fall back to first-name-anchored voting: the VLM
    reads first names stably but surnames noisily, so pick the best-corroborated
    full name sharing the winning first name."""
    roster = evidence.get("roster") or []
    out = []
    for cluster_id, reads in evidence["resolutions"].items():
        names = [r["name"] for r in reads if r["name"]]
        total = len(names)
        if total == 0:
            continue
        evidence_ids = [r["eventId"] for r in reads]

        snapped = [snap_to_roster(n, roster)[0] for n in names] if roster else []
        hits = [m for m in snapped if m]
        if hits:
            top, positive = collections.Counter(hits).most_common(1)[0]
            frequency, confidence = nal_truth(positive, total)
            statement = (
                f"{cluster_id} has-name '{top}' (roster-resolved, "
                f"{frequency / 10:.0f}% of {total} reads)"
            )
        else:
            first_votes = collections.Counter(_first_name(n) for n in names)
            top_first, positive = first_votes.most_common(1)[0]
            candidates = [n for n in names if _first_name(n) == top_first]
            top = collections.Counter(candidates).most_common(1)[0][0]
            frequency, confidence = nal_truth(positive, total)
            statement = f"{cluster_id} has-name '{top}' ({frequency / 10:.0f}% of {total} reads)"

        out.append(
            {
                "subjectKind": cluster_id.split("-")[0],
                "subjectId": cluster_id,
                "predicate": "has-name",
                "object": top,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": total,
                "statement": statement,
                "evidenceEventIds": evidence_ids,
            }
        )
    return out


def recurrence_conclusions(evidence: dict) -> list[dict]:
    """An identity cluster observed across many sessions is a recurring
    entity; support (and confidence) grows with the session count."""
    out = []
    for cluster_id, sessions in evidence["cluster_sessions"].items():
        n_sessions = len(sessions)
        if n_sessions < 2:
            continue
        # Weak-embedding modalities over-merge, so their apparent recurrence is
        # untrustworthy — demand far more sessions before it counts as confident.
        weak = cluster_id.split("-")[0] in WEAK_MODALITIES
        frequency, confidence = nal_truth(n_sessions, n_sessions, WEAK_HORIZON if weak else EVIDENTIAL_HORIZON)
        note = " (unverified — weak embedding clusters over-merge)" if weak else ""
        out.append(
            {
                "subjectKind": cluster_id.split("-")[0],
                "subjectId": cluster_id,
                "predicate": "recurs-across-sessions",
                "object": str(n_sessions),
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": n_sessions,
                "totalEvidence": n_sessions,
                "statement": f"{cluster_id} is a recurring entity, seen in {n_sessions} sessions{note}",
                "evidenceEventIds": evidence["cluster_event_ids"].get(cluster_id, []),
            }
        )
    return out


def language_conclusions(evidence: dict) -> list[dict]:
    """Which language a speaker cluster tends to speak, from the langHint of
    the utterances attributed to it."""
    out = []
    for cluster_id, langs in evidence["speaker_langs"].items():
        total = len(langs)
        if total < 2:
            continue
        top_lang, positive = collections.Counter(langs).most_common(1)[0]
        if top_lang in ("", "auto"):
            continue
        frequency, confidence = nal_truth(positive, total)
        out.append(
            {
                "subjectKind": "speaker",
                "subjectId": cluster_id,
                "predicate": "speaks",
                "object": top_lang,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": total,
                "statement": f"{cluster_id} speaks {top_lang} ({frequency/10:.0f}% of {total} utterances)",
                "evidenceEventIds": evidence["speaker_utterance_ids"].get(cluster_id, []),
            }
        )
    return out


def speaker_name_conclusions(evidence: dict) -> list[dict]:
    """Match an audio speaker cluster to a name using the meeting-screen glow:
    each of the cluster's utterances was attributed (active-speaker highlight)
    to a named tile, so the cluster's name is the one its utterances most agree
    on. Confidence grows with the number of attributed utterances; frequency is
    the share agreeing (a cluster that spans several on-screen people stays
    honestly low)."""
    out = []
    for cluster_id, attributions in evidence.get("speaker_names", {}).items():
        names = [a["name"] for a in attributions if a["name"]]
        total = len(names)
        if total == 0:
            continue
        top, positive = collections.Counter(names).most_common(1)[0]
        frequency, confidence = nal_truth(positive, total)
        out.append(
            {
                "subjectKind": "speaker",
                "subjectId": cluster_id,
                "predicate": "has-name",
                "object": top,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": total,
                "statement": (
                    f"{cluster_id} has-name '{top}' (screen-glow attribution, "
                    f"{frequency / 10:.0f}% of {total} utterances)"
                ),
                "evidenceEventIds": [a["eventId"] for a in attributions],
            }
        )
    return out


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "unknown"


def entity_conclusions(evidence: dict) -> list[dict]:
    """Cross-modal entity resolution: unify the per-modality clusters (face /
    voice / vehicle) that resolve to the same name into one entity, and bind the
    cross-modal pairs with same-as. A name is the strongest bridge available —
    a face read off a caption and a voice named by the active-speaker glow that
    share a name are the same person. Confidence grows with how many sessions
    the entity recurs across."""
    cluster_name = evidence.get("cluster_name") or {}
    cluster_sessions = evidence.get("cluster_sessions") or {}
    cluster_event_ids = evidence.get("cluster_event_ids") or {}
    out = []
    by_name = collections.defaultdict(list)
    for cid, nm in cluster_name.items():
        by_name[nm].append(cid)
    for name, members in by_name.items():
        members = sorted(members)
        modalities = sorted({m.split("-")[0] for m in members})
        sessions = set().union(*[cluster_sessions.get(m, set()) for m in members]) if members else set()
        n_sessions = max(1, len(sessions))
        # Corroboration: an entity resting only on weak modalities (voice/vehicle)
        # stays low-confidence; one a reliable modality (a face) also supports is
        # trusted — a cross-modal link is what makes recurrence believable.
        corroborated = any(m not in WEAK_MODALITIES for m in modalities)
        frequency, confidence = nal_truth(
            n_sessions, n_sessions, EVIDENTIAL_HORIZON if corroborated else WEAK_HORIZON
        )
        note = "" if corroborated else " — uncorroborated (weak-modality only)"
        evidence_ids = [e for m in members for e in cluster_event_ids.get(m, [])[:3]]
        out.append(
            {
                "subjectKind": "entity",
                "subjectId": _slug(name),
                "predicate": "unifies",
                "object": name,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": len(members),
                "totalEvidence": len(members),
                "statement": (
                    f"entity '{name}' = {', '.join(members)} "
                    f"({'+'.join(modalities)}; {len(sessions)} sessions){note}"
                ),
                "evidenceEventIds": evidence_ids or [f"cluster:{m}" for m in members],
            }
        )
        # Explicit cross-modal bindings.
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                if members[i].split("-")[0] == members[j].split("-")[0]:
                    continue
                out.append(
                    {
                        "subjectKind": members[i].split("-")[0],
                        "subjectId": members[i],
                        "predicate": "same-as",
                        "object": members[j],
                        "frequencyPerMille": frequency,
                        "confidencePerMille": confidence,
                        "positiveEvidence": len(sessions),
                        "totalEvidence": len(sessions),
                        "statement": f"{members[i]} same-as {members[j]} (both resolve to '{name}')",
                        "evidenceEventIds": evidence_ids or [f"cluster:{members[i]}", f"cluster:{members[j]}"],
                    }
                )
    return out


def location_conclusions(evidence: dict) -> list[dict]:
    """Where an identity is usually observed: the median of the GPS fixes in
    effect at its observation times."""
    latlon = evidence.get("cluster_latlon") or {}
    cluster_event_ids = evidence.get("cluster_event_ids") or {}
    out = []
    for cid, pts in latlon.items():
        if len(pts) < 3:
            continue
        lat = sorted(p[0] for p in pts)[len(pts) // 2]
        lon = sorted(p[1] for p in pts)[len(pts) // 2]
        frequency, confidence = nal_truth(len(pts), len(pts))
        out.append(
            {
                "subjectKind": cid.split("-")[0],
                "subjectId": cid,
                "predicate": "usually-at",
                "object": f"{lat / 1e7:.5f},{lon / 1e7:.5f}",
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": len(pts),
                "totalEvidence": len(pts),
                "statement": f"{cid} usually-at ({lat / 1e7:.5f}, {lon / 1e7:.5f}) ({len(pts)} fixes)",
                "evidenceEventIds": cluster_event_ids.get(cid, [])[:4] or [f"cluster:{cid}"],
            }
        )
    return out


def cooccurrence_conclusions(evidence: dict) -> list[dict]:
    """Bind a face cluster to a voice cluster when they co-occur in time far
    above chance — the person visible when a voice sounds. A PMI (lift) guard
    plus a specificity floor keeps a video-call grid (every face always on
    screen, so every pair co-occurs) from producing spurious bindings; there the
    lift is ~1 and nothing fires."""
    cooc = evidence.get("cooc") or {}
    face_marg = evidence.get("cooc_face_marg") or {}
    spk_marg = evidence.get("cooc_spk_marg") or {}
    total = evidence.get("cooc_total") or 0
    out = []
    if total < 10:
        return out
    # Group by voice and rank faces by how often they are on screen while it
    # speaks. Bind a voice to its top face ONLY if that face clearly dominates
    # the runner-up (specificity margin) — on a video-call grid every face is
    # present in every window, so top ≈ runner-up and nothing binds. A real
    # in-person scene has one face present when the voice sounds.
    by_spk = collections.defaultdict(list)
    for (face, spk), c in cooc.items():
        by_spk[spk].append((face, c))
    for spk, faces in by_spk.items():
        faces.sort(key=lambda x: -x[1])
        top_face, top_c = faces[0]
        n = spk_marg.get(spk, 0)
        if n < 1:
            continue
        share1 = top_c / n
        share2 = faces[1][1] / n if len(faces) > 1 else 0.0
        lift = (top_c * total) / max(1e-9, face_marg.get(top_face, 0) * n)
        if top_c >= 8 and share1 >= 0.6 and (share1 - share2) >= 0.25 and lift >= 1.5:
            frequency, confidence = nal_truth(top_c, n)
            out.append(
                {
                    "subjectKind": "face",
                    "subjectId": top_face,
                    "predicate": "same-as",
                    "object": spk,
                    "frequencyPerMille": frequency,
                    "confidencePerMille": confidence,
                    "positiveEvidence": top_c,
                    "totalEvidence": n,
                    "statement": (
                        f"{top_face} same-as {spk} (uniquely co-present in "
                        f"{frequency / 10:.0f}% of utterances, lift {lift:.1f})"
                    ),
                    "evidenceEventIds": [f"cluster:{top_face}", f"cluster:{spk}"],
                }
            )
    return out


def item_conclusions(evidence: dict) -> list[dict]:
    """Recurring open-vocabulary items: an item name (VLM-identified) seen across
    several sessions is a persistent object in the space ('the Wagner sprayer is
    in the shed across visits'). Item names are noisy, so recurrence needs a
    couple of sessions and carries a modest horizon."""
    item_sessions = evidence.get("item_sessions") or {}
    item_names = evidence.get("item_names") or {}
    item_events = evidence.get("item_events") or {}
    out = []
    for slug, sessions in item_sessions.items():
        n = len(sessions)
        if n < 2:
            continue
        name = item_names.get(slug, slug)
        frequency, confidence = nal_truth(n, n, 2)
        out.append(
            {
                "subjectKind": "item",
                "subjectId": f"item:{slug}",
                "predicate": "recurs-across-sessions",
                "object": str(n),
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": n,
                "totalEvidence": n,
                "statement": f"item '{name}' recurs in {n} sessions",
                "evidenceEventIds": item_events.get(slug, [])[:8],
            }
        )
    return out


def _norm_item(s: str) -> str:
    s = s.lower()
    s = re.sub(r"^(a|an|the|some|several|two|three|four|many|small|large|blue|red|green|white|black)\s+", "", s)
    return re.sub(r"[^a-z0-9 ]", "", s).strip().rstrip("s")


# Ego-vehicle parts (always present, belong to the observer), transients (sky,
# passing traffic) and omnipresent generics (vegetation, road surface) — none of
# these are evidence of a PLACE changing; they dominated the v0 change output.
_CHANGE_STOPWORDS = {
    "dashboard", "windshield", "windscreen", "mirror", "steering", "car hood",
    "bonnet", "seat", "seatbelt", "car interior", "vehicle interior", "wiper",
    "cloud", "sky", "sun", "sunlight", "shadow", "reflection", "glare",
    "road", "street", "lane", "marking", "asphalt", "pavement", "curb",
    "car", "vehicle", "traffic", "van", "truck", "bicycle", "pedestrian", "person",
    "tree", "foliage", "bush", "hedge", "grass", "field", "vegetation", "plant",
    "power line", "powerline", "utility pole", "pole", "fence", "wall",
}


def _stable_items(items: list) -> set:
    out = set()
    for raw in items:
        n = _norm_item(raw)
        if not n or any(sw in n for sw in _CHANGE_STOPWORDS):
            continue
        out.add(n)
    return out


def _merge_place_groups(evidence: dict) -> list:
    """Union adjacent ~11m cells so GPS jitter does not fragment one place, then
    aggregate each group's sessions (recurrence) and per-pass observations."""
    place_cells = evidence.get("place_cells") or {}
    place_obs = evidence.get("place_obs") or {}
    cells = {}
    for key in set(place_cells) | set(place_obs):
        try:
            la, lo = (round(float(x), 4) for x in key.split(","))
            cells[(la, lo)] = key
        except ValueError:
            continue
    parent = {c: c for c in cells}

    def find(c):
        while parent[c] != c:
            parent[c] = parent[parent[c]]
            c = parent[c]
        return c

    for (la, lo) in list(cells):
        for dla in (-1, 0, 1):
            for dlo in (-1, 0, 1):
                nb = (round(la + dla * 1e-4, 4), round(lo + dlo * 1e-4, 4))
                if nb in cells and nb != (la, lo):
                    parent[find((la, lo))] = find(nb)

    groups = collections.defaultdict(lambda: {"sessions": set(), "obs": {}, "cell": None})
    for c, key in cells.items():
        g = groups[find(c)]
        g["cell"] = g["cell"] or find(c)
        g["sessions"] |= place_cells.get(key, set())
        for sid, d in (place_obs.get(key) or {}).items():
            g["obs"].setdefault(sid, d)
    return list(groups.values())


def place_conclusions(evidence: dict) -> list[dict]:
    """A GPS location revisited across sessions is the same place — a static
    building/landmark re-identified by location (reliable, full confidence,
    unlike appearance embeddings). Adjacent cells are merged so one place is one
    entity; named from the VLM place descriptions when present."""
    out = []
    for g in _merge_place_groups(evidence):
        n = len(g["sessions"])
        if n < 2 or not g["cell"]:
            continue
        la, lo = g["cell"]
        names = [d["name"] for d in g["obs"].values() if d.get("name")]
        name = collections.Counter(names).most_common(1)[0][0] if names else None
        frequency, confidence = nal_truth(n, n)
        label = f"'{name}' " if name else ""
        out.append(
            {
                "subjectKind": "place",
                "subjectId": f"place:{la:.4f},{lo:.4f}",
                "predicate": "recurs-across-sessions",
                "object": str(n),
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": n,
                "totalEvidence": n,
                "statement": f"place {label}({la:.5f},{lo:.5f}) revisited in {n} sessions",
                "evidenceEventIds": [d["eventId"] for d in g["obs"].values() if d.get("eventId")],
            }
        )
    return out


def change_conclusions(evidence: dict) -> list[dict]:
    """What changed at a place between drive-by passes. Guards learned from v0
    (whose output was almost entirely noise): ego-vehicle parts, transients and
    omnipresent generics are excluded; items are matched fuzzily across passes
    (bigram cosine) so rephrasings are not 'changes'; only drive-by passes that
    faced roughly the same way (bearing within 60°) are compared; and a diff is
    only a CANDIDATE change (VLM variance is indistinguishable from real change
    in a single frame), so confidence is capped low."""
    out = []
    for g in _merge_place_groups(evidence):
        passes = {
            s: d for s, d in g["obs"].items()
            if d.get("passKind") == "drive-by" and d.get("bearingCentiDeg", -1) >= 0
        }
        if len(passes) < 2 or not g["cell"]:
            continue
        sessions = sorted(passes)
        for i in range(len(sessions)):
            for j in range(i + 1, len(sessions)):
                a, b = sessions[i], sessions[j]
                dbear = abs(passes[a]["bearingCentiDeg"] - passes[b]["bearingCentiDeg"]) / 100.0
                dbear = min(dbear, 360 - dbear)
                if dbear > 60:
                    continue                      # different heading = different scenery, not change
                ia = _stable_items(passes[a].get("items", []))
                ib = _stable_items(passes[b].get("items", []))
                if not ia and not ib:
                    continue
                only_a = {x for x in ia if all(bigram_cosine(x, y) < 0.55 for y in ib)}
                only_b = {x for x in ib if all(bigram_cosine(x, y) < 0.55 for y in ia)}
                if not (only_a or only_b):
                    continue
                la, lo = g["cell"]
                parts = "; ".join(
                    f"{s[-6:]}: {', '.join(sorted(v)[:4])}"
                    for s, v in ((a, only_a), (b, only_b)) if v
                )
                total = len(ia | ib)
                positive = len(only_a) + len(only_b)
                frequency, confidence = nal_truth(positive, max(1, total), WEAK_HORIZON)
                confidence = min(confidence, 500)  # candidate change, not established fact
                out.append(
                    {
                        "subjectKind": "place",
                        "subjectId": f"place:{la:.4f},{lo:.4f}",
                        "predicate": "changed",
                        "object": parts[:80],
                        "frequencyPerMille": frequency,
                        "confidencePerMille": confidence,
                        "positiveEvidence": positive,
                        "totalEvidence": total,
                        "statement": f"candidate change at ({la:.5f},{lo:.5f}) — {parts}",
                        "evidenceEventIds": [passes[a]["eventId"], passes[b]["eventId"]],
                    }
                )
    return out


# --- Latent-cause reasoners: activities, routines, contexts, associations ----

_HOUR_BANDS = ((5, 11, "morning"), (11, 14, "midday"), (14, 18, "afternoon"), (18, 23, "evening"))


def _hour_band(h: int) -> str:
    for lo, hi, name in _HOUR_BANDS:
        if lo <= h < hi:
            return name
    return "night"


def classify_activity(g: dict) -> str:
    """Rule-based latent activity from a session's observable signature —
    transparent and debuggable; the class is the hidden common cause that
    explains why these features co-occur."""
    if g["driveFrac"] >= 0.3 or g["vehicles"] >= 20:
        return "DRIVING"
    if g["faces"] >= 20 and g["speechFrac"] >= 0.2:
        return "VIDEO-MEETING"
    if g["speechFrac"] >= 0.25:
        return "CONVERSATION"
    if g["speechFrac"] < 0.2 and g["faces"] < 5 and g["vehicles"] < 5:
        return "QUIET"
    return "MIXED"


def activity_conclusions(evidence: dict) -> list[dict]:
    """Infer each session's latent activity from its signature. Confidence
    grows with how much signal the session carried."""
    out = []
    for sid, g in (evidence.get("session_signatures") or {}).items():
        act = classify_activity(g)
        n = max(1, g.get("signals", 1))
        frequency, confidence = nal_truth(n, n, 2)
        detail = (f"drive {g['driveFrac']:.0%}, speech {g['speechFrac']:.0%}, "
                  f"music {g['musicFrac']:.0%}, faces {g['faces']}, vehicles {g['vehicles']}")
        out.append(
            {
                "subjectKind": "session",
                "subjectId": sid,
                "predicate": "is-activity",
                "object": act,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": n,
                "totalEvidence": n,
                "statement": f"{sid} is-activity {act} ({detail})",
                "evidenceEventIds": [g["startEventId"]],
            }
        )
    return out


def routine_conclusions(evidence: dict) -> list[dict]:
    """Recurring (activity, time-of-day) pairs across sessions are routines —
    the latent habit that explains why similar sessions keep appearing at
    similar hours. Confidence grows with repetitions (and starts honestly low
    with only days of data)."""
    sigs = evidence.get("session_signatures") or {}
    groups = collections.defaultdict(list)
    for sid, g in sigs.items():
        groups[(classify_activity(g), _hour_band(g["hour"]))].append((sid, g))
    out = []
    for (act, band), members in groups.items():
        n = len(members)
        if n < 2 or act in ("MIXED", "QUIET"):
            continue
        frequency, confidence = nal_truth(n, n, 2)
        extras = ""
        music = [g["musicFrac"] for _, g in members]
        if act == "DRIVING" and statistics.median(music) > 0.4:
            extras = ", usually with the radio on"
        out.append(
            {
                "subjectKind": "routine",
                "subjectId": f"routine:{act.lower()}:{band}",
                "predicate": "recurs",
                "object": str(n),
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": n,
                "totalEvidence": n,
                "statement": f"routine: {act} in the {band} ({n} sessions{extras})",
                "evidenceEventIds": [g["startEventId"] for _, g in members][:16],
            }
        )
    return out


def context_conclusions(evidence: dict) -> list[dict]:
    """Which latent context an identity belongs to: the activity class of the
    sessions a cluster is observed in ('face-52 occurs in VIDEO-MEETING
    contexts'). Weak-embedding modalities carry the larger horizon."""
    sigs = evidence.get("session_signatures") or {}
    out = []
    for cid, sessions in (evidence.get("cluster_sessions") or {}).items():
        acts = collections.Counter(
            classify_activity(sigs[s]) for s in sessions if s in sigs
        )
        total = sum(acts.values())
        if total < 2:
            continue
        top, positive = acts.most_common(1)[0]
        weak = cid.split("-")[0] in WEAK_MODALITIES
        frequency, confidence = nal_truth(positive, total, WEAK_HORIZON if weak else 2)
        out.append(
            {
                "subjectKind": cid.split("-")[0],
                "subjectId": cid,
                "predicate": "occurs-in",
                "object": top,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": total,
                "statement": f"{cid} occurs in {top} contexts ({positive}/{total} sessions)",
                "evidenceEventIds": (evidence.get("cluster_event_ids") or {}).get(cid, [])[:8],
            }
        )
    return out


def association_conclusions(evidence: dict) -> list[dict]:
    """Temporal-association hypotheses between events: does travel speed differ
    when a given object is being tracked, versus the 'car' baseline? Guards
    learned from the traffic-light probe: a contrast baseline is mandatory
    (naive precedence gave the WRONG causal reading), direction is never
    claimed, and confidence is capped — observational co-occurrence is a
    hypothesis, not an established cause."""
    assoc = evidence.get("speed_assoc") or {}
    base = assoc.get("car") or []
    if len(base) < 30:
        return []
    base_med = statistics.median([a for a, _ in base])
    if base_med <= 100:
        return []
    out = []
    for label, samples in assoc.items():
        if label == "car" or len(samples) < 30:
            continue
        med = statistics.median([a for a, _ in samples])
        after = statistics.median([b for _, b in samples])
        rel = (med - base_med) / base_med
        if abs(rel) < 0.15:
            continue
        slower = rel < 0
        positive = sum(1 for a, _ in samples if (a < base_med) == slower)
        frequency, confidence = nal_truth(positive, len(samples), WEAK_HORIZON)
        confidence = min(confidence, 500)
        direction = "slower" if slower else "faster"
        out.append(
            {
                "subjectKind": "pattern",
                "subjectId": f"pattern:{_slug(label)}-speed",
                "predicate": "associates-with",
                "object": f"{direction}-travel",
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": len(samples),
                "statement": (
                    f"hypothesis: '{label}' detections co-occur with {direction} travel "
                    f"({med / 100:.1f} vs {base_med / 100:.1f} m/s baseline, "
                    f"then {after / 100:.1f}; n={len(samples)})"
                ),
                "evidenceEventIds": [],
            }
        )
    return out


REASONERS = {
    "identity-namer-v0": name_conclusions,
    "recurrence-v0": recurrence_conclusions,
    "language-v0": language_conclusions,
    "speaker-namer-v0": speaker_name_conclusions,
    "entity-resolver-v0": entity_conclusions,
    "location-binder-v0": location_conclusions,
    "cooccurrence-binder-v0": cooccurrence_conclusions,
    "item-recurrence-v0": item_conclusions,
    "place-recurrence-v0": place_conclusions,
    "place-change-v0": change_conclusions,
    "activity-classifier-v0": activity_conclusions,
    "routine-miner-v0": routine_conclusions,
    "context-binder-v0": context_conclusions,
    "association-miner-v0": association_conclusions,
}


def run_all(evidence: dict) -> list[tuple[str, dict]]:
    """Returns (reasonerId, conclusion) pairs for every reasoner."""
    results = []
    for reasoner_id, fn in REASONERS.items():
        for conclusion in fn(evidence):
            results.append((reasoner_id, conclusion))
    return results
