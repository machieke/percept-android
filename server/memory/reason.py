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

EVIDENTIAL_HORIZON = 1  # NAL k: one more counterexample would halve certainty growth

# Modalities whose embeddings are proven to over-merge on this capture (phone-mic
# far-field voices ~0.03 separation; classical vehicle appearance tail overlap).
# Their recurrence needs far more evidence before it is believed, and an entity
# built only from them stays low-confidence unless a reliable modality (a face)
# corroborates it.
WEAK_MODALITIES = {"speaker", "vehicle"}
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


REASONERS = {
    "identity-namer-v0": name_conclusions,
    "recurrence-v0": recurrence_conclusions,
    "language-v0": language_conclusions,
    "speaker-namer-v0": speaker_name_conclusions,
    "entity-resolver-v0": entity_conclusions,
    "location-binder-v0": location_conclusions,
    "cooccurrence-binder-v0": cooccurrence_conclusions,
}


def run_all(evidence: dict) -> list[tuple[str, dict]]:
    """Returns (reasonerId, conclusion) pairs for every reasoner."""
    results = []
    for reasoner_id, fn in REASONERS.items():
        for conclusion in fn(evidence):
            results.append((reasoner_id, conclusion))
    return results
