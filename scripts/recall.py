#!/usr/bin/env python3
"""Read-only recall over a percept memory store.

The store is an append-only pointer log plus a content-addressed object
directory:

    <data>/pointers.jsonl            one event-pointer-v0.1 per line
    <data>/da/objects/<sha256>       canonical payloads and envelopes, raw artifacts

This tool loads the pointers, resolves payloads on demand, and exposes the
recall patterns the trace is designed for. It is deliberately dependency-free
so it runs anywhere the data volume is reachable (e.g. inside the container:
`docker exec -i percept-memory python3 - < scripts/recall.py ...`, or against a
copied-out `/data`).

Examples:
    recall.py --data /data sessions
    recall.py --data /data timeline --session sess-20260707-123646
    recall.py --data /data kind asr-segment --session sess-...
    recall.py --data /data around --session sess-... --t 34.3 --window 8
    recall.py --data /data speech-where --session sess-...
    recall.py --data /data speakers --session sess-...
"""

from __future__ import annotations

import argparse
import bisect
import json
from pathlib import Path


class Store:
    def __init__(self, data_root: str):
        self.root = Path(data_root)
        self.objects = self.root / "da" / "objects"
        self.pointers: list[dict] = []
        self.by_id: dict[str, dict] = {}
        with (self.root / "pointers.jsonl").open() as log:
            for line in log:
                line = line.strip()
                if not line:
                    continue
                p = json.loads(line)
                self.pointers.append(p)
                self.by_id[p["eventId"]] = p

    def payload(self, pointer: dict) -> dict:
        digest = pointer["payloadCid"].rsplit(":", 1)[-1]
        return json.loads((self.objects / digest).read_bytes())

    def artifact(self, cid: str) -> bytes:
        return (self.objects / cid.rsplit(":", 1)[-1]).read_bytes()

    def session_of(self, pointer: dict) -> str | None:
        return self.payload(pointer).get("sessionId")

    def t_seconds(self, pointer: dict) -> float:
        pl = self.payload(pointer)
        nanos = pl.get("tNanos", pl.get("tStartNanos", 0))
        return nanos / 1e9

    def source(self, pointer: dict) -> str:
        """Which producer emitted this event, from its actorPath."""
        a = pointer.get("actorPath", [])
        if a[:1] == ["server"]:
            return a[2] if len(a) > 2 else "server"
        return "device"

    def rows(self, session: str | None = None, kind: str | None = None) -> list[dict]:
        out = []
        for p in self.pointers:
            if kind and p["valueKind"] != kind:
                continue
            if session and self.session_of(p) != session:
                continue
            out.append(p)
        return out


def cmd_sessions(store: Store, _args) -> None:
    starts = {}
    for p in store.rows(kind="session-start"):
        starts[store.session_of(p)] = p
    for p in store.rows(kind="session-stop"):
        sid = store.session_of(p)
        pl = store.payload(p)
        counts = {}
        for q in store.rows(session=sid):
            counts[q["valueKind"]] = counts.get(q["valueKind"], 0) + 1
        dur = pl["tEndNanos"] / 60e9
        print(f"{sid}  {dur:4.1f} min  {sum(counts.values()):5d} events")


def cmd_timeline(store: Store, args) -> None:
    rows = sorted(store.rows(session=args.session), key=store.t_seconds)
    for p in rows:
        pl = store.payload(p)
        text = pl.get("text") or pl.get("label") or pl.get("state") or pl.get("clusterId") or ""
        print(f"t={store.t_seconds(p):7.1f} {store.source(p):10} {p['valueKind']:20} {str(text)[:60]}")


def cmd_kind(store: Store, args) -> None:
    for p in sorted(store.rows(session=args.session, kind=args.value_kind), key=store.t_seconds):
        print(f"t={store.t_seconds(p):7.1f} {store.source(p):10} {json.dumps(store.payload(p))[:200]}")


def cmd_around(store: Store, args) -> None:
    """Every event within +/- window seconds of t — cross-modal recall."""
    lo, hi = args.t - args.window, args.t + args.window
    for p in sorted(store.rows(session=args.session), key=store.t_seconds):
        ts = store.t_seconds(p)
        if lo <= ts <= hi:
            pl = store.payload(p)
            text = pl.get("text") or pl.get("label") or pl.get("clusterId") or ""
            print(f"t={ts:7.1f} {p['valueKind']:20} {str(text)[:60]}")


def cmd_speech_where(store: Store, args) -> None:
    """Join each utterance to the location fix in effect at that moment."""
    fixes = sorted(store.rows(session=args.session, kind="location-fix"), key=store.t_seconds)
    fix_ts = [store.t_seconds(f) for f in fixes]
    for p in sorted(store.rows(session=args.session, kind="asr-segment"), key=store.t_seconds):
        if store.source(p) != "asr":  # archival transcript is the authoritative one
            continue
        ts = store.t_seconds(p)
        i = bisect.bisect_right(fix_ts, ts) - 1
        where = "unknown"
        if 0 <= i < len(fixes):
            fl = store.payload(fixes[i])
            where = f"{fl['latE7']/1e7:.5f},{fl['lonE7']/1e7:.5f}"
        print(f't={ts:7.1f} @({where})  "{store.payload(p)["text"][:60]}"')


def cmd_speakers(store: Store, args) -> None:
    """Utterances with the speaker cluster attributed to each (via causal parent)."""
    for obs in sorted(store.rows(session=args.session, kind="speaker-observation"), key=store.t_seconds):
        opl = store.payload(obs)
        parent = store.by_id.get(obs["parentEventIds"][0]) if obs.get("parentEventIds") else None
        text = store.payload(parent)["text"][:55] if parent else "?"
        who = opl.get("label") or opl["clusterId"]
        print(f'{who:12} sim={opl["similarityPermille"]:4}  "{text}"')


COMMANDS = {
    "sessions": cmd_sessions,
    "timeline": cmd_timeline,
    "kind": cmd_kind,
    "around": cmd_around,
    "speech-where": cmd_speech_where,
    "speakers": cmd_speakers,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default="/data", help="memory data root (default /data)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("sessions")
    for name in ("timeline", "speech-where", "speakers"):
        s = sub.add_parser(name)
        s.add_argument("--session", required=True)
    k = sub.add_parser("kind")
    k.add_argument("value_kind")
    k.add_argument("--session", required=True)
    a = sub.add_parser("around")
    a.add_argument("--session", required=True)
    a.add_argument("--t", type=float, required=True)
    a.add_argument("--window", type=float, default=5.0)

    args = parser.parse_args()
    store = Store(args.data)
    COMMANDS[args.command](store, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
