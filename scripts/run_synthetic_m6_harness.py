#!/usr/bin/env python3
"""Export and verify a deterministic 5-minute synthetic M6 session bundle."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "core" / "trace" / "build" / "synthetic-m6-bundle"
SESSION_ID = "sess-synthetic-m6"
EXPECTED_EVENTS = "72"


def main() -> int:
    subprocess.run(
        [str(ROOT / "gradlew"), ":core:trace:exportSyntheticM6Bundle"],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "verify_bundle.py"),
            str(BUNDLE),
            "--expected-events",
            EXPECTED_EVENTS,
            "--expect-time-prefix",
            "/2026/07/04/12=72",
            "--expect-channel-prefix",
            f"/perception/{SESSION_ID}=72",
            "--expect-channel-prefix",
            f"/perception/{SESSION_ID}/session=2",
            "--expect-channel-prefix",
            f"/perception/{SESSION_ID}/video=40",
            "--expect-channel-prefix",
            f"/perception/{SESSION_ID}/audio=30",
        ],
        cwd=ROOT,
        check=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
