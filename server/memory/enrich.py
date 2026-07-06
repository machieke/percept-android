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

CORRECT_PROMPT = """A speech recognizer transcribed a short utterance and may have misheard \
words as similar-sounding ones.

Utterance language: {lang}
Transcript: "{text}"

What the camera saw at that moment:
{context}

If a transcribed word is likely a mishearing of a similar-sounding word that fits \
the visual context better, output the corrected transcript. Change as little as \
possible; keep the language of the transcript. If nothing needs correction, output \
the transcript unchanged. Output ONLY the transcript text, nothing else."""


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


def correct_transcript(text: str, lang: str, captions: list[str], labels: list[str]) -> str | None:
    """Returns the corrected transcript, or None when unchanged."""
    context_lines = [f"- {caption}" for caption in captions]
    if labels:
        context_lines.append(f"- detected objects: {', '.join(sorted(set(labels)))}")
    if not context_lines:
        return None
    prompt = CORRECT_PROMPT.format(lang=lang, text=text, context="\n".join(context_lines))
    corrected = _generate(LLM_MODEL, prompt).strip().strip('"')
    if not corrected or corrected.lower() == text.lower():
        return None
    # Guard against the model rewriting rather than correcting.
    if len(corrected) > len(text) * 2 + 20:
        return None
    return corrected
