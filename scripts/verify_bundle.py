#!/usr/bin/env python3
"""Verify an event-trace bundle directory or zip with the Python reference."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "reference" / "event-trace-memory"
CID_PREFIX = "cidv0-local-sha256:"


def main() -> int:
    args = parse_args()
    ensure_reference_repo()
    sys.path.insert(0, str(REFERENCE))

    with prepared_bundle_root(args.bundle) as bundle:
        result = verify_bundle(
            bundle,
            expected_events=args.expected_events,
            time_prefix_counts=parse_expectations(args.expect_time_prefix),
            channel_prefix_counts=parse_expectations(args.expect_channel_prefix),
        )
    print(
        "bundle verify ok: "
        f"{result['events']} pointers, "
        f"{result['referencedCids']} referenced CIDs, "
        f"{result['dagJsonObjects']} dag-json objects, "
        f"{result['rawObjects']} raw objects"
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="Bundle directory or .zip file")
    parser.add_argument("--expected-events", type=int, help="Expected pointer/index event count")
    parser.add_argument(
        "--expect-time-prefix",
        action="append",
        default=[],
        metavar="PREFIX=COUNT",
        help="Assert EventTraceIndex.by_time_prefix(PREFIX) returns COUNT events",
    )
    parser.add_argument(
        "--expect-channel-prefix",
        action="append",
        default=[],
        metavar="PREFIX=COUNT",
        help="Assert EventTraceIndex.by_channel_prefix(PREFIX) returns COUNT events",
    )
    return parser.parse_args()


def ensure_reference_repo() -> None:
    if (REFERENCE / "event_trace_memory").is_dir():
        return
    REFERENCE.parent.mkdir(parents=True, exist_ok=True)
    import subprocess

    subprocess.run(
        ["git", "clone", "https://github.com/machieke/event-trace-memory", str(REFERENCE)],
        cwd=ROOT,
        check=True,
    )


class prepared_bundle_root:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.temp_dir: Path | None = None
        self.bundle_root: Path | None = None

    def __enter__(self) -> Path:
        if self.path.is_dir():
            self.bundle_root = resolve_bundle_root(self.path)
            return self.bundle_root
        if not zipfile.is_zipfile(self.path):
            raise AssertionError(f"bundle is neither a directory nor a zip file: {self.path}")

        self.temp_dir = Path(tempfile.mkdtemp(prefix="percept-bundle-verify-"))
        with zipfile.ZipFile(self.path) as archive:
            safe_extract(archive, self.temp_dir)
        self.bundle_root = resolve_bundle_root(self.temp_dir)
        return self.bundle_root

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        if self.temp_dir is not None:
            shutil.rmtree(self.temp_dir)


def safe_extract(archive: zipfile.ZipFile, destination: Path) -> None:
    root = destination.resolve()
    for member in archive.infolist():
        target = (destination / member.filename).resolve()
        if root != target and root not in target.parents:
            raise AssertionError(f"zip member escapes destination: {member.filename}")
    archive.extractall(destination)


def resolve_bundle_root(path: Path) -> Path:
    if (path / "pointers.jsonl").is_file():
        return path
    candidates = [child for child in path.iterdir() if child.is_dir() and (child / "pointers.jsonl").is_file()]
    if len(candidates) == 1:
        return candidates[0]
    raise AssertionError(f"could not locate pointers.jsonl in bundle root: {path}")


def verify_bundle(
    bundle: Path,
    *,
    expected_events: int | None = None,
    time_prefix_counts: dict[str, int] | None = None,
    channel_prefix_counts: dict[str, int] | None = None,
) -> dict[str, int]:
    from event_trace_memory.canonical import canonical_json_bytes, cid_for_bytes, digest_from_cid
    from event_trace_memory.da import FileDA
    from event_trace_memory.indexes import EventTraceIndex

    event_validator = Draft202012Validator(load_schema("event-trace-v0.1.schema.json"))
    pointer_validator = Draft202012Validator(load_schema("event-pointer-v0.1.schema.json"))

    pointer_lines = read_nonempty_lines(bundle / "pointers.jsonl")
    pointers = [json.loads(line) for line in pointer_lines]
    if expected_events is not None:
        require(len(pointers) == expected_events, f"expected {expected_events} pointers, found {len(pointers)}")

    da = FileDA(bundle)
    index = EventTraceIndex()
    referenced_cids: set[str] = set()

    for line, pointer in zip(pointer_lines, pointers):
        pointer_validator.validate(pointer)
        require(
            canonical_json_bytes(pointer).decode("utf-8") == line,
            f"pointer line is not canonical JSON: {pointer.get('eventId')}",
        )
        ack = index.put_event(pointer)
        require(ack["ok"], f"reference index rejected pointer: {ack}")

        for cid in referenced_da_cids(pointer):
            referenced_cids.add(cid)
            verify = da.verify(cid)
            require(verify["ok"], f"reference DA verify failed for {cid}: {verify}")
            data = object_bytes_for_cid(bundle, cid, digest_from_cid)
            require(cid_for_bytes(data) == cid, f"CID mismatch for {cid}")

        envelope_bytes = object_bytes_for_cid(bundle, pointer["eventCid"], digest_from_cid)
        envelope = json.loads(envelope_bytes.decode("utf-8"))
        event_validator.validate(envelope)
        assert_canonical_json_object(envelope, envelope_bytes, f"event {pointer['eventId']}")
        event_digest = hashlib.sha256(envelope_bytes).hexdigest()
        require(pointer["eventId"] == f"event:{event_digest}", f"eventId mismatch: {pointer['eventId']}")

        payload_manifest = da.stat(pointer["payloadCid"])
        if payload_manifest.get("codec") == "dag-json":
            payload_bytes = object_bytes_for_cid(bundle, pointer["payloadCid"], digest_from_cid)
            payload = json.loads(payload_bytes.decode("utf-8"))
            assert_canonical_json_object(payload, payload_bytes, f"payload {pointer['payloadCid']}")

    dag_json_objects = 0
    raw_objects = 0
    for object_path in sorted((bundle / "objects").iterdir()):
        data = object_path.read_bytes()
        require(hashlib.sha256(data).hexdigest() == object_path.name, f"object filename digest mismatch: {object_path}")
        manifest = read_manifest(bundle, object_path.name)
        codec = manifest.get("codec")
        if codec == "dag-json":
            dag_json_objects += 1
            obj = json.loads(data.decode("utf-8"))
            assert_canonical_json_object(obj, data, f"object {object_path.name}")
            if obj.get("kind") == "event-trace":
                event_validator.validate(obj)
        elif codec == "raw":
            raw_objects += 1
        else:
            raise AssertionError(f"unsupported manifest codec for {object_path.name}: {codec}")

    stats = index.state_stats()
    if expected_events is not None:
        require(stats["events"] == expected_events, f"reference index expected {expected_events} events, got {stats['events']}")

    assert_prefix_counts(index.by_time_prefix, time_prefix_counts or {}, "time")
    assert_prefix_counts(index.by_channel_prefix, channel_prefix_counts or {}, "channel")

    return {
        "events": stats["events"],
        "referencedCids": len(referenced_cids),
        "dagJsonObjects": dag_json_objects,
        "rawObjects": raw_objects,
    }


def load_schema(name: str) -> dict[str, Any]:
    return json.loads((REFERENCE / "schemas" / name).read_text(encoding="utf-8"))


def parse_expectations(values: list[str]) -> dict[str, int]:
    parsed: dict[str, int] = {}
    for value in values:
        if "=" not in value:
            raise AssertionError(f"expectation must be PREFIX=COUNT: {value}")
        prefix, count_text = value.rsplit("=", 1)
        parsed[prefix] = int(count_text)
    return parsed


def read_nonempty_lines(path: Path) -> list[str]:
    return [line for line in path.read_text(encoding="utf-8").splitlines() if line]


def referenced_da_cids(pointer: dict[str, Any]) -> list[str]:
    cids = [pointer["eventCid"], pointer["payloadCid"]]
    cids.extend(cid for cid in pointer.get("outputArtifactIds", []) if isinstance(cid, str) and cid.startswith(CID_PREFIX))
    return cids


def object_bytes_for_cid(bundle: Path, cid: str, digest_from_cid: Any) -> bytes:
    return (bundle / "objects" / digest_from_cid(cid)).read_bytes()


def read_manifest(bundle: Path, digest: str) -> dict[str, Any]:
    return json.loads((bundle / "manifests" / f"{digest}.json").read_text(encoding="utf-8"))


def assert_canonical_json_object(value: Any, original_bytes: bytes, label: str) -> None:
    from event_trace_memory.canonical import canonical_json_bytes

    assert_no_float(value)
    require(canonical_json_bytes(value) == original_bytes, f"{label} is not canonical JSON")


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


def assert_prefix_counts(query: Any, expectations: dict[str, int], label: str) -> None:
    for prefix, expected_count in expectations.items():
        result = query(prefix)
        require(result["ok"], f"reference {label} query failed for {prefix}: {result}")
        actual_count = len(result["eventIds"])
        require(
            actual_count == expected_count,
            f"expected {expected_count} events for {label} prefix {prefix}, got {actual_count}",
        )


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
