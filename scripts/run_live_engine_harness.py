#!/usr/bin/env python3
"""Run the live-engine Robolectric harness, then verify its bundle with the
Python reference implementation (real engines + fake models, end to end)."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "build" / "live-engine-harness"


def main() -> int:
    subprocess.run(
        [
            str(ROOT / "gradlew"),
            ":app:testDebugUnitTest",
            "--tests",
            "org.takopi.percept.app.LiveEngineBundleHarnessTest",
            "--rerun",
        ],
        cwd=ROOT,
        check=True,
    )
    expected_events = (OUTPUT / "expected-events.txt").read_text().strip()
    session_id = (OUTPUT / "session-id.txt").read_text().strip()
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "verify_bundle.py"),
            str(OUTPUT / "bundle.zip"),
            "--expected-events",
            expected_events,
            "--expect-channel-prefix",
            f"/perception/{session_id}={expected_events}",
            "--expect-channel-prefix",
            f"/perception/{session_id}/video=4",
            "--expect-channel-prefix",
            f"/perception/{session_id}/audio=2",
            "--expect-channel-prefix",
            f"/perception/{session_id}/session=2",
        ],
        cwd=ROOT,
        check=True,
    )
    print("live engine harness ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
