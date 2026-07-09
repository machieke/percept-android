"""Percept trace browser — a read-only window into the episodic memory.

Mounts the memory server's data volume read-only and serves a small single-page
app to explore exactly what was traced and how it links: sessions, the
interleaved cross-modal timeline of each session, and per-event detail with the
resolved payload, attached artifacts (keyframes/audio), and the causal graph
(parents / children / root) as clickable links. Nothing here can mutate the
trace — it only reads pointers.jsonl and the content-addressed objects.
"""

import json
import os
from functools import lru_cache
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse, Response

DATA_ROOT = Path(os.environ.get("DATA_ROOT", "/data"))
POINTER_LOG = DATA_ROOT / "pointers.jsonl"
OBJECTS = DATA_ROOT / "da" / "objects"

app = FastAPI(title="percept-trace-browser")

_state: dict = {"mtime": None, "pointers": [], "by_id": {}, "children": {}, "cluster_stats": {}, "item_stats": {}}


def _digest(cid: str) -> str:
    return cid.rsplit(":", 1)[-1]


@lru_cache(maxsize=8192)
def _payload_cached(digest: str) -> str:
    try:
        return (OBJECTS / digest).read_text()
    except Exception:
        return "{}"


def payload_of(pointer: dict) -> dict:
    try:
        return json.loads(_payload_cached(_digest(pointer["payloadCid"])))
    except Exception:
        return {}


def load() -> None:
    """(Re)load pointers if the log changed — cheap live refresh."""
    try:
        mtime = POINTER_LOG.stat().st_mtime
    except FileNotFoundError:
        _state.update(mtime=None, pointers=[], by_id={}, children={})
        return
    if _state["mtime"] == mtime:
        return
    pointers, by_id, children, cluster_stats, item_stats = [], {}, {}, {}, {}
    with POINTER_LOG.open() as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            p = json.loads(line)
            pointers.append(p)
            by_id[p["eventId"]] = p
            for parent in p.get("parentEventIds", []):
                children.setdefault(parent, []).append(p["eventId"])
            vk = p.get("valueKind")
            if vk in ("face-observation", "speaker-observation", "vehicle-observation"):
                pl = json.loads(_payload_cached(_digest(p["payloadCid"])))
                cid = pl.get("clusterId")
                if cid:
                    st = cluster_stats.setdefault(cid, {"modality": cid.split("-")[0], "count": 0, "sessions": set(), "eventIds": []})
                    st["count"] += 1
                    st["sessions"].add(pl.get("sessionId"))
                    if len(st["eventIds"]) < 300:
                        st["eventIds"].append(p["eventId"])
            elif vk == "item-observation":
                pl = json.loads(_payload_cached(_digest(p["payloadCid"])))
                slug = pl.get("itemSlug")
                if slug:
                    st = item_stats.setdefault(slug, {"name": pl.get("itemName", slug), "count": 0, "sessions": set(), "eventIds": []})
                    st["count"] += 1
                    st["name"] = pl.get("itemName", st["name"])
                    st["sessions"].add(pl.get("sessionId"))
                    if len(st["eventIds"]) < 200:
                        st["eventIds"].append(p["eventId"])
    _state.update(mtime=mtime, pointers=pointers, by_id=by_id, children=children,
                  cluster_stats=cluster_stats, item_stats=item_stats)


def session_of(p: dict) -> str:
    cp = p.get("channelPath") or []
    if cp and cp[0] == "perception" and len(cp) >= 2:
        return cp[1]
    if cp and cp[0] == "reasoning":
        return "(reasoning)"
    return "(other)"


def modality_of(p: dict) -> str:
    cp = p.get("channelPath") or []
    if cp and cp[0] == "reasoning":
        return "reasoning"
    return cp[2] if len(cp) >= 3 else "session"


def t_nanos(pl: dict) -> int:
    for k in ("tStartNanos", "tNanos", "tEndNanos"):
        if k in pl and isinstance(pl[k], int):
            return pl[k]
    return 0


def preview_of(p: dict, pl: dict) -> str:
    for k in ("text", "statement", "attributedName", "resolvedName", "label",
              "clusterId", "vehicleType", "ssid", "caption"):
        v = pl.get(k)
        if v:
            return f"{v}"
    kind = p.get("valueKind", "?")
    if "latE7" in pl and "lonE7" in pl:
        return f"({pl['latE7'] / 1e7:.5f}, {pl['lonE7'] / 1e7:.5f})"
    return kind


def actor_short(p: dict) -> str:
    ap = p.get("actorPath") or []
    if not ap:
        return "?"
    if ap[0] == "device":
        return ap[2] if len(ap) > 2 else "device"  # camera / microphone / app / sensors
    return ap[-1]  # server producers: asr / llm / vlm / speaker-id / reasoner / ...


@app.get("/api/sessions")
def api_sessions() -> JSONResponse:
    load()
    agg: dict = {}
    for p in _state["pointers"]:
        sid = session_of(p)
        a = agg.setdefault(sid, {"sid": sid, "events": 0, "kinds": {}, "t0": None, "t1": None})
        a["events"] += 1
        a["kinds"][p.get("valueKind", "?")] = a["kinds"].get(p.get("valueKind", "?"), 0) + 1
        tp = p.get("timePath")
        if tp:
            key = tuple(tp)
            a["t0"] = min(a["t0"], key) if a["t0"] else key
            a["t1"] = max(a["t1"], key) if a["t1"] else key
    out = []
    for a in agg.values():
        a["t0"] = "-".join(str(x) for x in a["t0"]) if a["t0"] else ""
        a.pop("t1", None)
        out.append(a)
    out.sort(key=lambda a: a["sid"])
    return JSONResponse(out)


@app.get("/api/session/{sid}")
def api_session(sid: str) -> JSONResponse:
    load()
    rows = []
    for p in _state["pointers"]:
        if session_of(p) != sid:
            continue
        pl = payload_of(p)
        rows.append({
            "id": p["eventId"],
            "kind": p.get("valueKind", "?"),
            "modality": modality_of(p),
            "actor": actor_short(p),
            "t": t_nanos(pl),
            "preview": preview_of(p, pl)[:120],
            "artifacts": len(p.get("outputArtifactIds") or []),
            "nParents": len(p.get("parentEventIds") or []),
            "nChildren": len(_state["children"].get(p["eventId"], [])),
        })
    t0 = min((r["t"] for r in rows if r["t"]), default=0)
    for r in rows:
        r["rel"] = round((r["t"] - t0) / 1e9, 1) if r["t"] else None
    rows.sort(key=lambda r: (r["t"] == 0, r["t"]))
    return JSONResponse({"sid": sid, "events": rows})


def _brief(event_id: str) -> dict:
    p = _state["by_id"].get(event_id)
    if not p:
        return {"id": event_id, "kind": "(missing)", "preview": ""}
    pl = payload_of(p)
    return {"id": event_id, "kind": p.get("valueKind", "?"), "actor": actor_short(p),
            "preview": preview_of(p, pl)[:100]}


@app.get("/api/event/{event_id}")
def api_event(event_id: str) -> JSONResponse:
    load()
    p = _state["by_id"].get(event_id)
    if not p:
        raise HTTPException(status_code=404, detail="unknown event")
    return JSONResponse({
        "id": event_id,
        "kind": p.get("valueKind", "?"),
        "session": session_of(p),
        "actorPath": p.get("actorPath"),
        "channelPath": p.get("channelPath"),
        "schema": payload_of(p).get("schema"),
        "payload": payload_of(p),
        "artifacts": p.get("outputArtifactIds") or [],
        "root": p.get("rootEventId"),
        "parents": [_brief(x) for x in (p.get("parentEventIds") or [])],
        "children": [_brief(x) for x in _state["children"].get(event_id, [])],
    })


@app.get("/api/entities")
def api_entities() -> JSONResponse:
    """The resolved entity graph, assembled from the conclusion events already
    in the trace (has-name / usually-at / recurs-across-sessions / same-as) plus
    each cluster's observation stats — so the browser stays a pure trace reader
    with no dependency on the memory server."""
    load()
    import collections

    has_name: dict = collections.defaultdict(dict)
    usually_at: dict = {}
    recurs: dict = {}
    same_as: dict = collections.defaultdict(set)
    for p in _state["pointers"]:
        if p.get("valueKind") != "conclusion":
            continue
        pl = payload_of(p)
        pred, subj, obj = pl.get("predicate"), pl.get("subjectId"), pl.get("object")
        if pred == "has-name":
            has_name[subj][obj] = max(has_name[subj].get(obj, 0), pl.get("confidencePerMille", 0))
        elif pred == "usually-at":
            usually_at[subj] = {"loc": obj, "fixes": pl.get("positiveEvidence")}
        elif pred == "recurs-across-sessions":
            try:
                recurs[subj] = int(obj)
            except (TypeError, ValueError):
                pass
        elif pred == "same-as":
            same_as[subj].add(obj)
            same_as[obj].add(subj)

    stats = _state["cluster_stats"]

    def member(cid: str) -> dict:
        st = stats.get(cid, {})
        return {
            "cluster": cid,
            "modality": cid.split("-")[0],
            "observations": st.get("count", 0),
            "sessions": sorted(s for s in st.get("sessions", set()) if s),
            "names": has_name.get(cid, {}),
            "usuallyAt": usually_at.get(cid),
        }

    best_name = {c: max(v, key=v.get) for c, v in has_name.items() if v}
    by_name: dict = collections.defaultdict(list)
    for c, nm in best_name.items():
        by_name[nm].append(c)

    entities = []
    for name, members in by_name.items():
        members = sorted(set(members) | {x for m in members for x in same_as.get(m, set())})
        ms = [member(m) for m in members]
        sessions = sorted({s for m in ms for s in m["sessions"]})
        entities.append({
            "name": name,
            "members": ms,
            "modalities": sorted({m["modality"] for m in ms}),
            "sessions": sessions,
            "sessionCount": len(sessions),
            "usuallyAt": next((m["usuallyAt"] for m in ms if m["usuallyAt"]), None),
        })
    # recurring but still-unnamed clusters become pseudonymous entities
    for cid, _ in recurs.items():
        if cid in best_name:
            continue
        m = member(cid)
        entities.append({
            "name": None, "pseudonym": cid, "members": [m], "modalities": [m["modality"]],
            "sessions": m["sessions"], "sessionCount": len(m["sessions"]), "usuallyAt": m["usuallyAt"],
        })
    entities.sort(key=lambda e: -e["sessionCount"])
    return JSONResponse(entities)


@app.get("/api/items")
def api_items() -> JSONResponse:
    """Open-vocabulary items across the trace: each distinct item with its
    observation count, the sessions it appears in, and whether it recurs."""
    load()
    items = []
    for slug, st in _state["item_stats"].items():
        sessions = sorted(s for s in st["sessions"] if s)
        items.append({
            "slug": slug, "name": st["name"], "count": st["count"],
            "sessions": sessions, "sessionCount": len(sessions),
            "recurring": len(sessions) >= 2,
        })
    items.sort(key=lambda i: (-i["sessionCount"], -i["count"], i["name"].lower()))
    by_session: dict = {}
    for slug, st in _state["item_stats"].items():
        for s in st["sessions"]:
            if s:
                by_session.setdefault(s, 0)
                by_session[s] += 1
    return JSONResponse({"items": items, "sessionCounts": by_session})


@app.get("/api/item/{slug}")
def api_item(slug: str) -> JSONResponse:
    load()
    st = _state["item_stats"].get(slug, {})
    rows = []
    for eid in st.get("eventIds", []):
        p = _state["by_id"].get(eid)
        if not p:
            continue
        rows.append({"id": eid, "kind": p.get("valueKind"), "session": session_of(p),
                     "preview": preview_of(p, payload_of(p))[:90]})
    return JSONResponse({"slug": slug, "name": st.get("name", slug), "events": rows})


@app.get("/api/cluster/{cid}")
def api_cluster(cid: str) -> JSONResponse:
    load()
    st = _state["cluster_stats"].get(cid, {})
    rows = []
    for eid in st.get("eventIds", []):
        p = _state["by_id"].get(eid)
        if not p:
            continue
        pl = payload_of(p)
        rows.append({"id": eid, "kind": p.get("valueKind"), "session": session_of(p), "preview": preview_of(p, pl)[:90]})
    return JSONResponse({"cluster": cid, "events": rows})


@app.get("/api/crop/{event_id}")
def api_crop(event_id: str) -> Response:
    """The image fragment an observation refers to: the box cropped from the
    keyframe it was seen in (face / vehicle), or the whole keyframe when the
    observation carries no box (open-vocab items). The keyframe is the event's
    artifact-bearing parent, or the nearest scene-change in the same session."""
    load()
    p = _state["by_id"].get(event_id)
    if not p:
        raise HTTPException(status_code=404, detail="unknown event")
    pl = payload_of(p)
    box = pl.get("box")
    t = pl.get("tNanos") or pl.get("tStartNanos") or 0
    session = pl.get("sessionId")

    artifact_cid = None
    for parent in p.get("parentEventIds", []):
        pp = _state["by_id"].get(parent)
        if pp and pp.get("outputArtifactIds"):
            artifact_cid = pp["outputArtifactIds"][0]
            break
    if not artifact_cid:  # e.g. vehicle-observation parents a track, not a keyframe
        best, best_dt = None, None
        for q in _state["pointers"]:
            if q.get("valueKind") != "scene-change" or not q.get("outputArtifactIds"):
                continue
            qpl = payload_of(q)
            if qpl.get("sessionId") != session:
                continue
            dt = abs(qpl.get("tNanos", 0) - t)
            if best_dt is None or dt < best_dt:
                best, best_dt = q, dt
        if best:
            artifact_cid = best["outputArtifactIds"][0]
    if not artifact_cid:
        raise HTTPException(status_code=404, detail="no keyframe for event")

    data = (OBJECTS / _digest(artifact_cid)).read_bytes()
    if not box:
        return Response(content=data, media_type="image/jpeg")  # whole scene (items)

    import io

    from PIL import Image

    im = Image.open(io.BytesIO(data)).convert("RGB")
    W, H = im.size
    # All observation boxes are stored in keyframe pixels (the server scales the
    # detector's 640x480 track boxes before recording them).
    x1, y1, x2, y2 = (int(v) for v in box)
    pw, ph = int((x2 - x1) * 0.2), int((y2 - y1) * 0.2)   # a little context around the box
    crop = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(W, x2 + pw), min(H, y2 + ph)))
    if crop.width and crop.width < 240:                   # upscale tiny crops for visibility
        scale = 240 / crop.width
        crop = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    crop.save(buf, format="JPEG", quality=90)
    return Response(content=buf.getvalue(), media_type="image/jpeg")


@app.get("/api/artifact/{cid:path}")
def api_artifact(cid: str) -> Response:
    path = OBJECTS / _digest(cid)
    if not path.exists():
        raise HTTPException(status_code=404, detail="unknown artifact")
    data = path.read_bytes()
    ctype = "application/octet-stream"
    if data[:3] == b"\xff\xd8\xff":
        ctype = "image/jpeg"
    elif data[:4] == b"OggS":
        ctype = "audio/ogg"
    elif data[:4] == b"\x89PNG":
        ctype = "image/png"
    return Response(content=data, media_type=ctype)


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    return INDEX_HTML


INDEX_HTML = r"""<!doctype html><html><head><meta charset=utf-8>
<title>percept trace browser</title>
<style>
 :root{--bg:#0d1117;--panel:#161b22;--bd:#30363d;--fg:#c9d1d9;--mut:#8b949e;--acc:#58a6ff;--warn:#d29922;--good:#3fb950;--der:#bc8cff}
 *{box-sizing:border-box} body{margin:0;font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;background:var(--bg);color:var(--fg);height:100vh;display:flex;flex-direction:column}
 header{padding:8px 12px;border-bottom:1px solid var(--bd);display:flex;gap:12px;align-items:center}
 header b{color:var(--acc)} header .mut{color:var(--mut)}
 main{flex:1;display:grid;grid-template-columns:230px 1fr 1.1fr;min-height:0}
 .col{overflow:auto;border-right:1px solid var(--bd);padding:6px}
 .s{padding:6px 8px;border-radius:6px;cursor:pointer;border:1px solid transparent}
 .s:hover{background:var(--panel)} .s.sel{background:var(--panel);border-color:var(--acc)}
 .s .sid{color:var(--fg)} .s .meta{color:var(--mut);font-size:11px}
 .filters{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:6px}
 .chip{font-size:11px;padding:1px 7px;border:1px solid var(--bd);border-radius:10px;color:var(--mut);cursor:pointer}
 .chip.on{background:var(--acc);color:#0d1117;border-color:var(--acc)}
 .ev{display:grid;grid-template-columns:52px 130px 1fr auto;gap:8px;padding:3px 6px;border-radius:5px;cursor:pointer;align-items:baseline}
 .ev:hover{background:var(--panel)} .ev.sel{background:#1f2630}
 .ev .t{color:var(--mut);text-align:right} .ev .k{color:var(--acc)} .ev .p{color:var(--fg);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
 .ev .b{color:var(--mut);font-size:11px}
 .k.derived{color:var(--der)} .k.reasoning{color:var(--warn)}
 .badge{font-size:10px;color:var(--mut)}
 h3{margin:6px 0 4px;color:var(--acc);font-size:12px;text-transform:uppercase;letter-spacing:.5px}
 .kv{display:grid;grid-template-columns:120px 1fr;gap:2px 10px} .kv .k{color:var(--mut)}
 .lnk{color:var(--acc);cursor:pointer;text-decoration:underline dotted} .lnk:hover{color:#79c0ff}
 pre{background:#0a0e14;border:1px solid var(--bd);border-radius:6px;padding:8px;overflow:auto;max-height:340px}
 img.kf{max-width:100%;border:1px solid var(--bd);border-radius:6px;margin-top:6px}
 .rel{color:var(--mut)} .cnt{color:var(--good)}
 .link-row{padding:3px 6px;border-radius:5px;cursor:pointer} .link-row:hover{background:var(--panel)}
 .empty{color:var(--mut);padding:20px;text-align:center}
 .tab{padding:2px 10px;border:1px solid var(--bd);border-radius:6px;cursor:pointer;color:var(--mut);margin-right:4px}
 .tab.on{background:var(--acc);color:#0d1117;border-color:var(--acc)}
 .ecard{padding:8px;border-radius:8px;cursor:pointer;border:1px solid transparent;margin-bottom:4px}
 .ecard:hover{background:var(--panel)} .ecard.sel{background:var(--panel);border-color:var(--acc)}
 .ename{color:var(--fg);font-size:14px} .emeta{color:var(--mut);font-size:11px}
 .mod{font-size:10px;padding:0 6px;border:1px solid var(--bd);border-radius:8px;color:var(--der);margin-right:3px}
 .member{border:1px solid var(--bd);border-radius:6px;padding:6px;margin:6px 0}
 .bar{height:4px;background:#0a0e14;border-radius:2px;overflow:hidden;margin:2px 0 5px} .bar>i{display:block;height:100%;background:var(--good)}
</style></head><body>
<header><b>percept</b> <span id=tabSessions class="tab on">sessions</span><span id=tabEntities class="tab">entities</span><span id=tabItems class="tab">items</span> <span class=mut id=stat></span>
 <span style="margin-left:auto" class=mut>causal: <span class=lnk>parent</span> · child · <span style="color:var(--der)">derived</span> · <span style="color:var(--warn)">conclusion</span></span>
</header>
<main>
 <div class=col id=sessions></div>
 <div class=col id=events><div class=empty>select a session</div></div>
 <div class=col id=detail><div class=empty>select an event</div></div>
</main>
<script>
const $=(s,e=document)=>e.querySelector(s), el=(h)=>{const d=document.createElement('div');d.innerHTML=h;return d.firstElementChild};
const DERIVED=new Set(['identity-resolution','transcript-correction','speaker-observation','face-observation','vehicle-observation','scene-caption','speaker-attribution']);
let curSession=null, curEvents=[], activeKinds=null, curEvent=null;

async function j(u){const r=await fetch(u);return r.json()}
function kclass(k){return k==='conclusion'?'reasoning':(DERIVED.has(k)?'derived':'')}

async function loadSessions(){
 const ss=await j('/api/sessions'); const box=$('#sessions'); box.innerHTML='';
 let total=0; ss.forEach(s=>total+=s.events);
 $('#stat').textContent=`· ${ss.length} sessions · ${total} events`;
 ss.forEach(s=>{
  const kinds=Object.entries(s.kinds).sort((a,b)=>b[1]-a[1]).slice(0,3).map(x=>x[0]).join(', ');
  const d=el(`<div class=s><div class=sid>${s.sid}</div><div class=meta>${s.events} events · ${s.t0}</div><div class=meta>${kinds}</div></div>`);
  d.onclick=()=>{document.querySelectorAll('.s').forEach(x=>x.classList.remove('sel'));d.classList.add('sel');openSession(s.sid)};
  box.appendChild(d);
 });
}
async function openSession(sid){
 curSession=sid; activeKinds=null;
 const r=await j('/api/session/'+encodeURIComponent(sid)); curEvents=r.events;
 renderFilters(); renderEvents();
}
function renderFilters(){
 const kinds=[...new Set(curEvents.map(e=>e.kind))].sort();
 const f=el('<div class=filters></div>');
 const all=el(`<span class="chip on">all (${curEvents.length})</span>`); all.onclick=()=>{activeKinds=null;renderFilters();renderEvents()};
 f.appendChild(all);
 kinds.forEach(k=>{
  const n=curEvents.filter(e=>e.kind===k).length;
  const c=el(`<span class="chip ${activeKinds&&activeKinds.has(k)?'on':''}">${k} ${n}</span>`);
  c.onclick=()=>{activeKinds=activeKinds||new Set(); activeKinds.has(k)?activeKinds.delete(k):activeKinds.add(k); if(!activeKinds.size)activeKinds=null; renderFilters();renderEvents()};
  f.appendChild(c);
 });
 const box=$('#events'); box.innerHTML=''; box.appendChild(f);
}
function renderEvents(){
 const box=$('#events');
 [...box.querySelectorAll('.ev')].forEach(x=>x.remove());
 const rows=curEvents.filter(e=>!activeKinds||activeKinds.has(e.kind));
 rows.forEach(e=>{
  const rel=e.rel==null?'':(e.rel+'s');
  const badge=(e.artifacts?'🖼 ':'')+(e.nChildren?`<span class=cnt>▸${e.nChildren}</span>`:'');
  const pv=e.kind==='location-fix'?mapsLink(e.preview):escape(e.preview);
  const d=el(`<div class=ev><span class=t>${rel}</span><span class="k ${kclass(e.kind)}">${e.kind}</span><span class=p>${pv}</span><span class=b>${badge}</span></div>`);
  d.onclick=()=>{document.querySelectorAll('.ev').forEach(x=>x.classList.remove('sel'));d.classList.add('sel');openEvent(e.id)};
  box.appendChild(d);
 });
}
function escape(s){return (s||'').replace(/[<>&]/g,c=>({'<':'&lt;','>':'&gt;','&':'&amp;'}[c]))}
function dms(dec,isLat){const dir=isLat?(dec>=0?'N':'S'):(dec>=0?'E':'W');dec=Math.abs(dec);const d=Math.floor(dec);const mf=(dec-d)*60;const m=Math.floor(mf);const s=((mf-m)*60).toFixed(1);return `${d}°${String(m).padStart(2,'0')}'${s}"${dir}`;}
function mapsLink(loc){const p=String(loc).replace(/[()\s]/g,'').split(',').map(parseFloat);if(p.length<2||isNaN(p[0])||isNaN(p[1]))return escape(String(loc));return `<a class=lnk target=_blank rel=noopener href="https://www.google.com/maps?q=${p[0]},${p[1]}">${dms(p[0],true)} ${dms(p[1],false)}</a>`;}
async function openEvent(id){
 curEvent=id; const e=await j('/api/event/'+encodeURIComponent(id)); const D=$('#detail'); D.innerHTML='';
 D.appendChild(el(`<div><span class="k ${kclass(e.kind)}" style="font-size:15px">${e.kind}</span> <span class=mut>${e.schema||''}</span></div>`));
 D.appendChild(el(`<div class=kv>
   <span class=k>actor</span><span>${(e.actorPath||[]).join(' / ')}</span>
   <span class=k>channel</span><span>${(e.channelPath||[]).join(' / ')}</span>
   <span class=k>eventId</span><span class=mut>${e.id}</span></div>`));
 // causal graph
 const g=el('<div></div>'); g.appendChild(el('<h3>causal links</h3>'));
 const root=el(`<div class=link-row>root: <span class=lnk>${e.root||'—'}</span></div>`);
 if(e.root&&e.root!==e.id) root.querySelector('.lnk').onclick=()=>openEvent(e.root); else root.querySelector('.lnk').style.color='var(--mut)';
 g.appendChild(root);
 addLinks(g,'parents ▲',e.parents); addLinks(g,'children ▼',e.children);
 D.appendChild(g);
 // image fragment for observations (the box cropped from its keyframe, or the whole scene for items)
 if(['face-observation','vehicle-observation','item-observation','object-observation'].includes(e.kind)){
  const f=el('<div></div>'); f.appendChild(el('<h3>image fragment</h3>'));
  const img=el(`<img class=kf src="/api/crop/${encodeURIComponent(e.id)}">`);
  img.onerror=()=>img.replaceWith(el('<div class=mut>no image fragment</div>'));
  f.appendChild(img); D.appendChild(f);
 }
 // artifacts
 if(e.artifacts.length){
  const a=el('<div></div>'); a.appendChild(el('<h3>artifacts</h3>'));
  e.artifacts.forEach(cid=>{ a.appendChild(el(`<img class=kf src="/api/artifact/${cid}" onerror="this.replaceWith(el('<div class=mut>'+cid+' (not an image)</div>'))">`)); });
  D.appendChild(a);
 }
 // payload
 D.appendChild(el('<h3>payload</h3>'));
 D.appendChild(el(`<pre>${escape(JSON.stringify(e.payload,null,2))}</pre>`));
}
function addLinks(g,title,list){
 g.appendChild(el(`<h3 style="margin-top:8px">${title} (${list.length})</h3>`));
 if(!list.length){g.appendChild(el('<div class=mut style="padding-left:6px">none</div>'));return}
 list.forEach(x=>{
  const r=el(`<div class=link-row><span class="k ${kclass(x.kind)}">${x.kind}</span> <span class=lnk>${escape(x.preview||x.id.slice(0,18))}</span></div>`);
  r.onclick=()=>openEvent(x.id); g.appendChild(r);
 });
}
// --- entities view ---
let mode='sessions';
$('#tabSessions').onclick=()=>setMode('sessions');
$('#tabEntities').onclick=()=>setMode('entities');
$('#tabItems').onclick=()=>setMode('items');
function setMode(m){
 mode=m;
 $('#tabSessions').classList.toggle('on',m==='sessions');
 $('#tabEntities').classList.toggle('on',m==='entities');
 $('#tabItems').classList.toggle('on',m==='items');
 $('#events').innerHTML='<div class=empty>—</div>'; $('#detail').innerHTML='<div class=empty>—</div>';
 if(m==='sessions') loadSessions(); else if(m==='entities') loadEntities(); else loadItems();
}
async function loadItems(){
 const r=await j('/api/items'); const box=$('#sessions'); box.innerHTML='';
 const rec=r.items.filter(i=>i.recurring).length;
 $('#stat').textContent=`· ${r.items.length} distinct items · ${rec} recurring`;
 r.items.forEach(i=>{
  const badge=i.recurring?`<span class=mod style="color:var(--good);border-color:var(--good)">${i.sessionCount} sessions</span>`:'';
  const d=el(`<div class=ecard><div class=ename>${escape(i.name)}</div><div class=emeta>×${i.count} obs ${badge}</div></div>`);
  d.onclick=()=>{document.querySelectorAll('.ecard').forEach(x=>x.classList.remove('sel'));d.classList.add('sel');openItem(i)};
  box.appendChild(d);
 });
}
function openItem(i){
 const M=$('#events'); M.innerHTML='';
 M.appendChild(el(`<div style="font-size:15px;color:var(--acc)">${escape(i.name)}</div>`));
 M.appendChild(el(`<div class=emeta>${i.count} observations · ${i.sessionCount} session${i.sessionCount==1?'':'s'}${i.recurring?' · recurring':''}</div>`));
 M.appendChild(el('<h3>seen in sessions</h3>'));
 i.sessions.forEach(s=>{const r=el(`<div class=link-row><span class=lnk>${s}</span></div>`);r.onclick=()=>{setMode('sessions');setTimeout(()=>openSession(s),60)};M.appendChild(r);});
 const vo=el('<div class=lnk style="margin-top:8px">view observations ▸</div>'); vo.onclick=()=>openItemObs(i.slug); M.appendChild(vo);
 $('#detail').innerHTML='<div class=empty>select "view observations"</div>';
}
async function openItemObs(slug){
 const r=await j('/api/item/'+encodeURIComponent(slug)); const D=$('#detail'); D.innerHTML='';
 D.appendChild(el(`<h3>${escape(r.name)} — ${r.events.length} observations</h3>`));
 r.events.forEach(e=>{const d=el(`<div class=link-row><span class="k derived">item</span> <span class=lnk>${escape(e.preview)}</span> <span class=b>${e.session}</span></div>`);d.onclick=()=>openEvent(e.id);D.appendChild(d);});
}
async function loadEntities(){
 const es=await j('/api/entities'); const box=$('#sessions'); box.innerHTML='';
 $('#stat').textContent=`· ${es.length} entities`;
 es.forEach(e=>{
  const nm=e.name||('· '+e.pseudonym);
  const mods=e.modalities.map(m=>`<span class=mod>${m}</span>`).join('');
  const d=el(`<div class=ecard><div class=ename>${escape(nm)}</div><div class=emeta>${mods} ${e.sessionCount} sessions${e.usuallyAt?' · 📍':''}</div></div>`);
  d.onclick=()=>{document.querySelectorAll('.ecard').forEach(x=>x.classList.remove('sel'));d.classList.add('sel');openEntity(e)};
  box.appendChild(d);
 });
}
function openEntity(e){
 const M=$('#events'); M.innerHTML='';
 M.appendChild(el(`<div style="font-size:15px;color:var(--acc)">${escape(e.name||e.pseudonym)}</div>`));
 const loc=e.usuallyAt?` · usually-at ${mapsLink(e.usuallyAt.loc)} (${e.usuallyAt.fixes} fixes)`:'';
 M.appendChild(el(`<div class=emeta>${e.modalities.join(' / ')} · ${e.sessionCount} sessions${loc}</div>`));
 M.appendChild(el('<h3>members</h3>'));
 e.members.forEach(m=>{
  const names=Object.entries(m.names).sort((a,b)=>b[1]-a[1]);
  const nh=names.map(([n,c])=>`<div class=emeta>${escape(n)} — conf ${c}<div class=bar><i style="width:${c/10}%"></i></div></div>`).join('');
  const ml=m.usuallyAt?`<div class=emeta>usually-at ${mapsLink(m.usuallyAt.loc)}</div>`:'';
  const mm=el(`<div class=member><span class="k derived">${m.cluster}</span> <span class=emeta>${m.observations} obs · ${m.sessions.length} sessions</span>${nh?'<div style="margin-top:4px">'+nh+'</div>':''}${ml}<div class=lnk style="margin-top:3px">view observations ▸</div></div>`);
  mm.querySelector('.lnk').onclick=()=>openCluster(m.cluster);
  M.appendChild(mm);
 });
 if(e.sessions.length){
  M.appendChild(el('<h3>appears in sessions</h3>'));
  e.sessions.forEach(s=>{const r=el(`<div class=link-row><span class=lnk>${s}</span></div>`);r.onclick=()=>{setMode('sessions');setTimeout(()=>openSession(s),60)};M.appendChild(r);});
 }
 $('#detail').innerHTML='<div class=empty>select a member to see its observations</div>';
}
async function openCluster(cid){
 const r=await j('/api/cluster/'+encodeURIComponent(cid)); const D=$('#detail'); D.innerHTML='';
 D.appendChild(el(`<h3>${cid} — ${r.events.length} observations</h3>`));
 r.events.forEach(e=>{const d=el(`<div class=link-row><span class="k derived">${e.kind}</span> <span class=lnk>${escape(e.preview)}</span> <span class=b>${e.session}</span></div>`);d.onclick=()=>openEvent(e.id);D.appendChild(d);});
}
window.el=el;
loadSessions();
setInterval(()=>{ if(mode==='sessions') loadSessions(); }, 15000);
</script></body></html>"""
