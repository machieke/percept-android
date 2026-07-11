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


ITEM_PROMPT = (
    "You are inventorying what is visible in this photo. Reply with EXACTLY two lines:\n"
    "Scene: <one short factual sentence>\n"
    "Items: <comma-separated list of the distinct physical items you can clearly "
    "identify, including any readable brand or label text; omit anything too "
    "blurry to identify and do not guess>"
)


ANIMAL_VERIFY_PROMPT = (
    "Does this image clearly show a real, live animal? If yes, reply with ONLY "
    "the animal type in one or two words (e.g. cat, dog, bird, cow, horse). If "
    "no animal is clearly visible — trees, people, vehicles, objects, or "
    "anything too blurry do NOT count — reply NONE."
)


def verify_animal(jpeg: bytes) -> str | None:
    """Confirm a detector 'animal' track actually shows an animal and return the
    VLM's species, or None. The COCO detector confabulates freely outside its
    training distribution (trees -> 'elephant'), and its confidence score does
    not separate real animals from junk — verification has to look again."""
    raw = _generate(VLM_MODEL, ANIMAL_VERIFY_PROMPT, images=[base64.b64encode(jpeg).decode("ascii")])
    word = raw.strip().strip('".').splitlines()[0].strip().lower() if raw else ""
    if not word or "none" in word or len(word.split()) > 2 or not word.replace(" ", "").isalpha():
        return None
    return word


VEHICLE_VERIFY_PROMPT = (
    "Does this image clearly show a road vehicle (car, van, truck, bus, "
    "motorcycle)? If yes, reply with ONLY its colour and type in a few words "
    "(e.g. 'silver SUV', 'white delivery van', 'red hatchback'). If no vehicle "
    "is clearly visible — buildings, vegetation, road surface, or anything too "
    "blurry do NOT count — reply NONE."
)


def verify_vehicle(jpeg: bytes) -> str | None:
    """Confirm a detector 'vehicle' track actually shows a vehicle and return
    the VLM's colour+type description, or None. Same rationale as animals: the
    COCO detector confabulates outside its distribution and its confidence
    score does not separate real vehicles from junk."""
    raw = _generate(VLM_MODEL, VEHICLE_VERIFY_PROMPT, images=[base64.b64encode(jpeg).decode("ascii")])
    desc = raw.strip().strip('".').splitlines()[0].strip().lower() if raw else ""
    if not desc or "none" in desc.split() or len(desc) > 40 or not any(c.isalpha() for c in desc):
        return None
    return desc


OBJECT_PROMPT = (
    "Name the single main object shown in this cropped image in 1-4 words. Be "
    "specific and include a brand or type if legible (e.g. 'potting soil bag', "
    "'terracotta pot', 'delivery van'). Reply with only the name, or NONE if "
    "unclear."
)


def classify_crop(jpeg: bytes) -> str | None:
    """Open-vocabulary label for a single cropped object — used to re-classify
    the on-device COCO track detections, which mislabel anything outside their
    80 classes ('soil bag' -> 'suitcase')."""
    raw = _generate(VLM_MODEL, OBJECT_PROMPT, images=[base64.b64encode(jpeg).decode("ascii")])
    name = raw.strip().strip('".').splitlines()[0].strip() if raw else ""
    if not name or name.lower() in _NAME_STOPWORDS or len(name) > 40:
        return None
    return name


PLACE_PROMPT = (
    "This photo was taken while passing a location. Describe the PLACE itself, "
    "ignoring the vehicle interior (dashboard, mirrors, windshield) and "
    "transient things (sky, clouds, passing traffic). Reply with EXACTLY two "
    "lines:\n"
    "Scene: <one short sentence naming the kind of place — e.g. 'a Volkswagen "
    "dealership on a main road', 'a wooded rural road', 'a brick row house'>\n"
    "Items: <comma-separated list of the STABLE features you can identify — "
    "buildings, businesses, signs (read their text), structures, landmarks; "
    "omit anything too blurry and do not guess>"
)


def describe_and_list_items(jpeg: bytes, prompt: str = None) -> tuple[str, list]:
    """One VLM pass returning (scene sentence, [item names]). Open-vocabulary —
    unlike the fixed COCO detector it names shed/room contents and reads their
    labels ('Hyacinthus', 'Wagner sprayer'). Best-effort parse of the two lines."""
    import re

    raw = _generate(VLM_MODEL, prompt or ITEM_PROMPT, images=[base64.b64encode(jpeg).decode("ascii")])
    scene, items = "", []
    for line in raw.splitlines():
        low = line.strip()
        if low.lower().startswith("scene:"):
            scene = low[6:].strip()
        elif low.lower().startswith("items:"):
            # Split on commas that are NOT inside parentheses, so a bracketed
            # brand list ("plant food (Miracle-Gro, DCM)") stays one item.
            items = [i.strip().strip('."') for i in re.split(r",(?![^(]*\))", low[6:])]
    items = [i for i in items if 1 < len(i) <= 40 and any(c.isalpha() for c in i)]
    if not scene and raw:
        scene = raw.strip().splitlines()[0][:200]
    return scene, items[:20]


NAME_PROMPT = (
    "This is a crop from a video-call grid or a scene with a visible name "
    "label (e.g. a video-call participant tile, a name tag, or a badge). "
    "Read the person's name printed near the face. Reply with ONLY the name "
    "exactly as written, or the single word NONE if no name text is legible."
)

_NAME_STOPWORDS = {"none", "name", "unknown", "n/a", "the", "person", "unclear"}


def _caption_crop(image, box: list[int]):
    """The name-label region for a face box: widened left/right and extended
    downward, where video-call tiles and name tags print the name. Small crops
    are upscaled — labels are only ~12 px tall in a 720 px keyframe, below VLM
    legibility without magnification."""
    from PIL import Image

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
    if crop.width < 320:
        scale = 320 / crop.width
        crop = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
    return crop.convert("RGB")


def caption_sharpness(jpeg: bytes, box: list[int]) -> float:
    """Edge energy of the caption region — high when the on-screen name is
    crisp, low when motion-blurred. A face cluster has hundreds of frames, most
    blurred but a few legible, so the resolver SELECTS the sharpest to read
    rather than averaging them: multi-frame fusion smears a moving on-screen
    subject, and order statistics (pick the sharpest) beat the mean here."""
    import io

    import numpy as np
    from PIL import Image, ImageFilter

    crop = _caption_crop(Image.open(io.BytesIO(jpeg)), box)
    edges = np.asarray(crop.filter(ImageFilter.FIND_EDGES).convert("L"), dtype="float32")
    return float(edges.var())


def read_name_label(jpeg: bytes, box: list[int]) -> str | None:
    """Crop the name-label region around a detected face and read it with the
    VLM. Returns a cleaned name or None. The VLM reads the first name reliably
    but hallucinates surnames off low-res labels; roster-snapping in the
    reasoner repairs that downstream, so this stays a pure raw read."""
    import io

    from PIL import Image

    buffer = io.BytesIO()
    _caption_crop(Image.open(io.BytesIO(jpeg)), box).save(buffer, format="JPEG", quality=95)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    # One retry: a single bad frame 500s ollama and otherwise stalls the pass.
    for attempt in range(2):
        try:
            return _clean_name(_generate(VLM_MODEL, NAME_PROMPT, images=[encoded]))
        except Exception:  # noqa: BLE001
            if attempt == 1:
                return None
    return None


def read_caption_name(jpeg: bytes) -> str | None:
    """Read a name from an already-cropped caption strip (a meeting tile's
    bottom-left label, extracted from the homography-stabilized frame). Upscales
    then reads with the VLM; roster-snapping in the reasoner repairs surnames."""
    import io

    from PIL import Image

    im = Image.open(io.BytesIO(jpeg)).convert("RGB")
    if im.width < 400:
        scale = 400 / im.width
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    buffer = io.BytesIO()
    im.save(buffer, format="JPEG", quality=95)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    for attempt in range(2):
        try:
            return _clean_name(_generate(VLM_MODEL, NAME_PROMPT, images=[encoded]))
        except Exception:  # noqa: BLE001
            if attempt == 1:
                return None
    return None


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
