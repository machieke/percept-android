#!/usr/bin/env python3
"""Run the Kotlin parity exporter and verify its bundle with the Python reference."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "reference" / "event-trace-memory"
BUNDLE = ROOT / "core" / "trace" / "build" / "parity-fixture-bundle"


def main() -> int:
    ensure_reference_repo()
    sys.path.insert(0, str(REFERENCE))

    from event_trace_memory.canonical import canonical_json_bytes, cid_for_bytes, digest_from_cid
    from event_trace_memory.da import FileDA
    from event_trace_memory.indexes import EventTraceIndex

    subprocess.run(
        [str(ROOT / "gradlew"), ":core:trace:exportParityFixture"],
        cwd=ROOT,
        check=True,
    )

    event_schema = load_schema("event-trace-v0.1.schema.json")
    pointer_schema = load_schema("event-pointer-v0.1.schema.json")
    event_validator = Draft202012Validator(event_schema)
    pointer_validator = Draft202012Validator(pointer_schema)

    pointers = read_jsonl(BUNDLE / "pointers.jsonl")
    require(len(pointers) == 50, f"expected 50 pointers, found {len(pointers)}")

    da = FileDA(BUNDLE)
    index = EventTraceIndex()
    seen_cids: set[str] = set()

    for pointer in pointers:
        pointer_validator.validate(pointer)
        assert_canonical_json(pointer)
        ack = index.put_event(pointer)
        require(ack["ok"], f"reference index rejected pointer: {ack}")

        for cid in (pointer["eventCid"], pointer["payloadCid"]):
            seen_cids.add(cid)
            verify = da.verify(cid)
            require(verify["ok"], f"reference DA verify failed for {cid}: {verify}")
            data = object_bytes_for_cid(cid, digest_from_cid)
            require(cid_for_bytes(data) == cid, f"CID mismatch for {cid}")

        envelope_bytes = object_bytes_for_cid(pointer["eventCid"], digest_from_cid)
        envelope = json.loads(envelope_bytes.decode("utf-8"))
        event_validator.validate(envelope)
        require(canonical_json_bytes(envelope) == envelope_bytes, f"event bytes are not canonical: {pointer['eventId']}")
        event_digest = hashlib.sha256(envelope_bytes).hexdigest()
        require(pointer["eventId"] == f"event:{event_digest}", f"eventId mismatch: {pointer['eventId']}")

        payload_bytes = object_bytes_for_cid(pointer["payloadCid"], digest_from_cid)
        payload = json.loads(payload_bytes.decode("utf-8"))
        assert_no_float(payload)
        require(canonical_json_bytes(payload) == payload_bytes, f"payload bytes are not canonical: {pointer['payloadCid']}")

    for object_path in sorted((BUNDLE / "objects").iterdir()):
        data = object_path.read_bytes()
        require(hashlib.sha256(data).hexdigest() == object_path.name, f"object filename digest mismatch: {object_path}")
        obj = json.loads(data.decode("utf-8"))
        assert_no_float(obj)
        require(canonical_json_bytes(obj) == data, f"object is not canonical JSON: {object_path.name}")
        if obj.get("kind") == "event-trace":
            event_validator.validate(obj)

    stats = index.state_stats()
    require(stats["events"] == 50, f"reference index expected 50 events, got {stats['events']}")
    print(
        "parity harness ok: "
        f"{len(pointers)} pointers, {len(seen_cids)} referenced CIDs, {stats['events']} indexed events"
    )
    return 0


def ensure_reference_repo() -> None:
    if (REFERENCE / "event_trace_memory").is_dir():
        return
    REFERENCE.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "clone", "https://github.com/machieke/event-trace-memory", str(REFERENCE)],
        cwd=ROOT,
        check=True,
    )


def load_schema(name: str) -> dict[str, Any]:
    return json.loads((REFERENCE / "schemas" / name).read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def object_bytes_for_cid(cid: str, digest_from_cid: Any) -> bytes:
    return (BUNDLE / "objects" / digest_from_cid(cid)).read_bytes()


def assert_canonical_json(value: Any) -> None:
    assert_no_float(value)


def assert_no_float(value: Any) -> None:
    if isinstance(value, float):
        raise AssertionError(f"float value found: {value}")
    if isinstance(value, dict):
        for key, child in value.items():
            require(isinstance(key, str), f"non-string key found: {key!r}")
            assert_no_float(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_float(child)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
