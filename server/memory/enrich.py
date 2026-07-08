"""VLM/LLM enrichment for the episodic trace.

Some transcription errors are acoustically unfixable — Dutch final devoicing
makes "mosterd" sound exactly like "mostert" — and only world context can
recover them. Keyframes in the trace are temporally aligned with the audio,
so: caption keyframes with a local VLM (scene-caption events), then let an
LLM correct each archival asr-segment using the captions and detector labels
from the same moments (corrected asr-segment events, causally parented to
the original so both interpretations stay in the trace with provenance).
"""

import base64
import json
import os
import urllib.request

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://host.docker.internal:11434")
VLM_MODEL = os.environ.get("VLM_MODEL", "gemma3:4b")
LLM_MODEL = os.environ.get("LLM_MODEL", "gemma3:4b")

CAPTION_PROMPT = (
    "Describe what is visible in this image in one short factual sentence, "
    "naming only the concrete objects you can clearly identify (including "
    "food items). The frame may be motion-blurred: if something is too "
    "blurry to identify, omit it, and never guess at text or product labels "
    "you cannot read clearly. Reply with only that sentence."
)

CORRECT_PROMPT = """You fix speech-recognition errors. The recognizer heard real sounds and wrote \
the closest words it knew, so any correction MUST sound nearly the same as what \
was written: similar syllable count, similar consonants and vowels, in the same \
language. The visual scene may only help you choose BETWEEN sound-alike \
candidates — it is never a reason to substitute a differently-sounding word.

Utterance language: {lang}
ASR transcript: "{text}"

What the camera saw at that moment:
{context}

Rules:
- Replace a word only if a word exists that sounds almost identical AND fits the \
scene better (example of a good fix: a garbled non-word replaced by a real word \
with nearly the same sounds).
- Replacing a word with the name of a visible object that does NOT sound like the \
transcribed word is wrong. When the scene shows several things, prefer the \
candidate that sounds closest, not the one most visible.
- Keep everything else, including punctuation, identical. If no sound-alike \
improvement exists, keep the transcript unchanged.

Answer with exactly two lines:
Reasoning: <one short sentence>
Corrected: <the final transcript>"""


def _generate(model: str, prompt: str, images: list[str] | None = None, timeout: int = 900) -> str:
    body: dict = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0},
    }
    if images:
        body["images"] = images
    request = urllib.request.Request(
        f"{OLLAMA_URL}/api/generate",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)["response"].strip()


def caption_keyframe(jpeg: bytes) -> str:
    return _generate(VLM_MODEL, CAPTION_PROMPT, images=[base64.b64encode(jpeg).decode("ascii")])


NAME_PROMPT = (
    "This is a crop from a video-call grid or a scene with a visible name "
    "label (e.g. a video-call participant tile, a name tag, or a badge). "
    "Read the person's name printed near the face. Reply with ONLY the name "
    "exactly as written, or the single word NONE if no name text is legible."
)

_NAME_STOPWORDS = {"none", "name", "unknown", "n/a", "the", "person", "unclear"}


def read_name_label(jpeg: bytes, box: list[int]) -> str | None:
    """Crop the region around a detected face — widened and extended downward
    to capture the name label that video-call tiles / name tags place beneath
    the face — and read it with the VLM. Returns a cleaned name or None."""
    import io

    from PIL import Image

    image = Image.open(io.BytesIO(jpeg))
    width, height = image.size
    x1, y1, x2, y2 = box
    w, h = max(1, x2 - x1), max(1, y2 - y1)
    crop = image.crop(
        (
            max(0, x1 - int(w * 2.5)),
            max(0, y1 - int(h * 0.3)),
            min(width, x2 + int(w * 2.5)),
            min(height, y2 + int(h * 2.2)),
        )
    )
    buffer = io.BytesIO()
    crop.convert("RGB").save(buffer, format="JPEG")
    raw = _generate(VLM_MODEL, NAME_PROMPT, images=[base64.b64encode(buffer.getvalue()).decode("ascii")])
    return _clean_name(raw)


def _clean_name(raw: str) -> str | None:
    name = raw.strip().strip('".').splitlines()[0].strip() if raw else ""
    if not name or name.lower() in _NAME_STOPWORDS:
        return None
    # A plausible on-screen name: 1-4 words, mostly letters, not a sentence.
    words = name.split()
    if not (1 <= len(words) <= 4):
        return None
    letters = sum(c.isalpha() for c in name)
    if letters < max(2, len(name) - 3):
        return None
    return " ".join(w.capitalize() for w in words)


def _replaced_word_pairs(original: str, corrected: str) -> list[tuple[str, str]]:
    import difflib

    a = original.lower().split()
    b = corrected.lower().split()
    pairs: list[tuple[str, str]] = []
    for op, a0, a1, b0, b1 in difflib.SequenceMatcher(None, a, b).get_opcodes():
        if op == "replace":
            pairs.append((" ".join(a[a0:a1]), " ".join(b[b0:b1])))
        elif op in ("insert", "delete"):
            pairs.append((" ".join(a[a0:a1]), " ".join(b[b0:b1])))
    return pairs


_PUNCT = str.maketrans("", "", ".,;:!?\"'")


def _phonetic_norm(word: str) -> str:
    """Light sound-alike normalization (Dutch final devoicing d/t, s/z, f/v;
    hard c/k; ch/sh) plus doubled-letter collapse, so 'mostert'≈'mosterd'
    and 'choose'≈'shoes' score as the near-homophones they are."""
    word = word.lower().translate(_PUNCT)
    for a, b in (("ch", "sh"), ("c", "k"), ("z", "s"), ("v", "f"), ("d", "t"), ("y", "i")):
        word = word.replace(a, b)
    collapsed = []
    for ch in word:
        if not collapsed or collapsed[-1] != ch:
            collapsed.append(ch)
    return "".join(collapsed)


def _acoustically_plausible(original: str, corrected: str, min_ratio: float = 0.70) -> bool:
    """Each replaced span must sound like what the recognizer heard. Measured
    on real cases (max of raw and phonetically-normalized similarity): good
    fixes score 0.71-1.00; bad swaps score <= 0.67 — "kalslost"->"kaas"
    (scene-plausible, 0.55) and "zonnebril"->"bril" (information-dropping,
    0.67) both occurred in real sessions and are blocked."""
    import difflib

    for heard, proposed in _replaced_word_pairs(original, corrected):
        if not heard or not proposed:
            return False  # insertions/deletions are rewrites, not corrections
        raw = difflib.SequenceMatcher(
            None, heard.translate(_PUNCT), proposed.translate(_PUNCT)
        ).ratio()
        normalized = difflib.SequenceMatcher(
            None, _phonetic_norm(heard), _phonetic_norm(proposed)
        ).ratio()
        if max(raw, normalized) < min_ratio:
            return False
    return True


def correct_transcript(text: str, lang: str, captions: list[str], labels: list[str]) -> str | None:
    """Returns the corrected transcript, or None when unchanged/implausible."""
    context_lines = [f"- {caption}" for caption in captions]
    if labels:
        context_lines.append(f"- detected objects: {', '.join(sorted(set(labels)))}")
    if not context_lines:
        return None
    prompt = CORRECT_PROMPT.format(lang=lang, text=text, context="\n".join(context_lines))
    response = _generate(LLM_MODEL, prompt)
    corrected = None
    for line in response.splitlines():
        if line.strip().lower().startswith("corrected:"):
            corrected = line.split(":", 1)[1].strip().strip('"')
    if not corrected or corrected.lower() == text.lower():
        return None
    # Guards: no wholesale rewrites, and every replaced word must plausibly
    # sound like what the recognizer heard.
    if len(corrected) > len(text) * 2 + 20:
        return None
    if not _acoustically_plausible(text, corrected):
        return None
    return corrected
