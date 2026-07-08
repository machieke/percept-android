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

EVIDENTIAL_HORIZON = 1  # NAL k: one more counterexample would halve certainty growth

# Below this bigram-cosine, a read is a total misread (wrong first name) and is
# dropped rather than snapped — keeps 'Mr Kennedy' from becoming 'Kyle Klemmer'.
ROSTER_SNAP_THRESHOLD = int(os.environ.get("ROSTER_SNAP_THRESHOLD_PERMILLE", "300")) / 1000


def nal_truth(positive: int, total: int) -> tuple[int, int]:
    """Return (frequencyPerMille, confidencePerMille)."""
    if total <= 0:
        return 0, 0
    frequency = round(1000 * positive / total)
    confidence = round(1000 * total / (total + EVIDENTIAL_HORIZON))
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
        # Treat every session appearance as positive evidence of recurrence.
        frequency, confidence = nal_truth(n_sessions, n_sessions)
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
                "statement": f"{cluster_id} is a recurring entity, seen in {n_sessions} sessions",
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


REASONERS = {
    "identity-namer-v0": name_conclusions,
    "recurrence-v0": recurrence_conclusions,
    "language-v0": language_conclusions,
}


def run_all(evidence: dict) -> list[tuple[str, dict]]:
    """Returns (reasonerId, conclusion) pairs for every reasoner."""
    results = []
    for reasoner_id, fn in REASONERS.items():
        for conclusion in fn(evidence):
            results.append((reasoner_id, conclusion))
    return results
