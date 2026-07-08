"""Persistent identity clustering for voices and faces.

Embeddings from percept-ident are matched against per-kind cluster
centroids by cosine similarity: match -> assign and update the running
centroid, no match -> new pseudonymous cluster (speaker-1, face-2, ...).
Clusters can be labeled with names later (POST /label); until then the
trace only ever carries the pseudonymous ids. The registry (the only
place biometric templates live) is one JSON file on the data volume.
"""

import json
import os
import threading
from pathlib import Path

import numpy as np

SPEAKER_SIM_THRESHOLD = float(os.environ.get("SPEAKER_SIM_THRESHOLD", "0.50"))
FACE_SIM_THRESHOLD = float(os.environ.get("FACE_SIM_THRESHOLD", "0.45"))
VEHICLE_SIM_THRESHOLD = float(os.environ.get("VEHICLE_SIM_THRESHOLD", "0.985"))

_THRESHOLDS = {
    "speaker": SPEAKER_SIM_THRESHOLD,
    "face": FACE_SIM_THRESHOLD,
    "vehicle": VEHICLE_SIM_THRESHOLD,
}


class IdentityRegistry:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        self.data: dict = {"speaker": {}, "face": {}, "vehicle": {}}
        if path.exists():
            self.data = json.loads(path.read_text())
            for kind in ("speaker", "face", "vehicle"):
                self.data.setdefault(kind, {})

    def _threshold(self, kind: str) -> float:
        return _THRESHOLDS.get(kind, FACE_SIM_THRESHOLD)

    def assign(self, kind: str, embedding: list[float]) -> tuple[str, int]:
        """Returns (clusterId, similarityPermille vs its centroid)."""
        vector = np.asarray(embedding, dtype=np.float32)
        norm = float(np.linalg.norm(vector))
        if norm > 0:
            vector = vector / norm
        with self.lock:
            clusters = self.data.setdefault(kind, {})
            best_id, best_sim = None, -1.0
            for cluster_id, cluster in clusters.items():
                centroid = np.asarray(cluster["centroid"], dtype=np.float32)
                similarity = float(np.dot(vector, centroid) / (np.linalg.norm(centroid) or 1.0))
                if similarity > best_sim:
                    best_id, best_sim = cluster_id, similarity
            if best_id is not None and best_sim >= self._threshold(kind):
                cluster = clusters[best_id]
                count = cluster["count"]
                centroid = np.asarray(cluster["centroid"], dtype=np.float32)
                cluster["centroid"] = ((centroid * count + vector) / (count + 1)).tolist()
                cluster["count"] = count + 1
                self._save()
                return best_id, int(best_sim * 1000)
            new_id = f"{kind}-{len(clusters) + 1}"
            clusters[new_id] = {"centroid": vector.tolist(), "count": 1}
            self._save()
            return new_id, 1000

    def label(self, cluster_id: str, name: str, method: str = "human", confidence: int = 1000) -> bool:
        """Set a cluster's label. Human labels always win; a model resolution
        only sets/replaces a label that is absent or itself model-derived, and
        only when at least as confident."""
        with self.lock:
            for kind in self.data:
                cluster = self.data[kind].get(cluster_id)
                if cluster is None:
                    continue
                existing_method = cluster.get("labelMethod")
                if existing_method == "human" and method != "human":
                    return False
                if (
                    method != "human"
                    and cluster.get("label")
                    and confidence < cluster.get("labelConfidence", 0)
                ):
                    return False
                cluster["label"] = name
                cluster["labelMethod"] = method
                cluster["labelConfidence"] = confidence
                self._save()
                return True
        return False

    def label_of(self, cluster_id: str) -> str | None:
        for kind in self.data:
            cluster = self.data[kind].get(cluster_id)
            if cluster:
                return cluster.get("label")
        return None

    def summary(self) -> dict:
        return {
            kind: {
                cluster_id: {
                    "count": c["count"],
                    "label": c.get("label"),
                    "labelMethod": c.get("labelMethod"),
                }
                for cluster_id, c in clusters.items()
            }
            for kind, clusters in self.data.items()
        }

    def _save(self) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data))
        tmp.replace(self.path)
