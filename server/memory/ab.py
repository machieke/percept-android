"""A/B harness: measure model variants against labeled eval sets.

Every model swap in this project that was measured paid off (titanet vs CAM++,
gemma4 vs gemma3 for verification) and every unmeasured assumption cost a
purge. This makes measurement standing infrastructure: eval sets live on the
data volume (/data/ab-eval/<suite>/ with a manifest of {image, expect}),
suites reuse the PRODUCTION prompts and parsers so a win on the suite is a win
in the pipeline, and results persist to /data/ab-results/.

Sets grow from real mistakes: any observation event can be labeled into a
suite via POST /ab-eval/add (the browser shows the crop; you say what it
actually was). 'none' as the expected value means the model should reject.
"""

import json
import time
from pathlib import Path

import enrich as enrichment
from reason import bigram_cosine


def _parse_verify_animal(raw: str):
    word = raw.strip().strip('".').splitlines()[0].strip().lower() if raw else ""
    if not word or "none" in word or len(word.split()) > 2 or not word.replace(" ", "").isalpha():
        return None
    return word


def _parse_verify_vehicle(raw: str):
    desc = raw.strip().strip('".').splitlines()[0].strip().lower() if raw else ""
    if not desc or "none" in desc.split() or len(desc) > 40 or not any(c.isalpha() for c in desc):
        return None
    return desc


SUITES = {
    "verify-animal": {"prompt": enrichment.ANIMAL_VERIFY_PROMPT, "parse": _parse_verify_animal},
    "verify-vehicle": {"prompt": enrichment.VEHICLE_VERIFY_PROMPT, "parse": _parse_verify_vehicle},
    "caption-name": {"prompt": enrichment.NAME_PROMPT, "parse": lambda raw: enrichment._clean_name(raw)},
}


def _correct(expect: str, got) -> bool:
    if expect == "none":
        return got is None
    if got is None:
        return False
    e, g = expect.lower(), str(got).lower()
    return e in g or g in e or bigram_cosine(e, g) >= 0.5


def run_suite(data_root: Path, suite: str, models: list) -> dict:
    """Run each model over the suite's labeled set with the production prompt
    and parser; score junk rejection and positive accuracy separately."""
    task = SUITES[suite]
    suite_dir = data_root / "ab-eval" / suite
    manifest_path = suite_dir / "manifest.json"
    if not manifest_path.exists():
        return {"ok": False, "reason": f"no eval set at {suite_dir}"}
    items = json.loads(manifest_path.read_text())
    if not items:
        return {"ok": False, "reason": "empty eval set"}

    import base64

    result = {"suite": suite, "n": len(items), "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "models": {}}
    for model in models:
        rows = []
        for item in items:
            img = (suite_dir / item["image"]).read_bytes()
            try:
                raw = enrichment._generate(model, task["prompt"], images=[base64.b64encode(img).decode("ascii")])
                got = task["parse"](raw)
            except Exception as exc:  # noqa: BLE001 - a dead model scores, not crashes
                rows.append({"image": item["image"], "expect": item["expect"], "got": f"ERROR {exc}"[:80], "correct": False})
                continue
            rows.append({"image": item["image"], "expect": item["expect"],
                         "got": got, "correct": _correct(item["expect"], got)})
        junk = [r for r in rows if r["expect"] == "none"]
        pos = [r for r in rows if r["expect"] != "none"]
        result["models"][model] = {
            "accuracy": round(sum(r["correct"] for r in rows) / len(rows), 3),
            "junkRejected": f"{sum(r['correct'] for r in junk)}/{len(junk)}",
            "positivesCorrect": f"{sum(r['correct'] for r in pos)}/{len(pos)}",
            "rows": rows,
        }
    result["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    out_dir = data_root / "ab-results"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = result["finishedAt"].replace(":", "").replace("-", "")
    (out_dir / f"{suite}-{stamp}.json").write_text(json.dumps(result, indent=1))
    return result
