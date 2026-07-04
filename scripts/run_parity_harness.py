#!/usr/bin/env python3
"""Run the Kotlin parity exporter and verify its bundle with the Python reference."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "core" / "trace" / "build" / "parity-fixture-bundle"


def main() -> int:
    subprocess.run(
        [str(ROOT / "gradlew"), ":core:trace:exportParityFixture"],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts" / "verify_bundle.py"),
            str(BUNDLE),
            "--expected-events",
            "50",
        ],
        cwd=ROOT,
        check=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
