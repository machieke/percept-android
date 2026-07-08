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

EVIDENTIAL_HORIZON = 1  # NAL k: one more counterexample would halve certainty growth


def nal_truth(positive: int, total: int) -> tuple[int, int]:
    """Return (frequencyPerMille, confidencePerMille)."""
    if total <= 0:
        return 0, 0
    frequency = round(1000 * positive / total)
    confidence = round(1000 * total / (total + EVIDENTIAL_HORIZON))
    return frequency, confidence


def _first_name(name: str) -> str:
    return name.strip().split()[0] if name.strip() else ""


def name_conclusions(evidence: dict) -> list[dict]:
    """Deduce a name per identity cluster by aggregating the on-screen-label
    reads (identity-resolution events). Votes on the first-name token, since
    the VLM reads first names stably but surnames noisily; the full name is
    the best-corroborated reading sharing the winning first name."""
    out = []
    for cluster_id, reads in evidence["resolutions"].items():
        names = [r["name"] for r in reads if r["name"]]
        total = len(names)
        if total == 0:
            continue
        first_votes = collections.Counter(_first_name(n) for n in names)
        top_first, positive = first_votes.most_common(1)[0]
        candidates = [n for n in names if _first_name(n) == top_first]
        best_full = collections.Counter(candidates).most_common(1)[0][0]
        frequency, confidence = nal_truth(positive, total)
        out.append(
            {
                "subjectKind": cluster_id.split("-")[0],
                "subjectId": cluster_id,
                "predicate": "has-name",
                "object": best_full,
                "frequencyPerMille": frequency,
                "confidencePerMille": confidence,
                "positiveEvidence": positive,
                "totalEvidence": total,
                "statement": f"{cluster_id} has-name '{best_full}' ({frequency/10:.0f}% of {total} reads)",
                "evidenceEventIds": [r["eventId"] for r in reads],
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
