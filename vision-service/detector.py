"""Person detection + multi-object tracking with a pretrained YOLO model (Ultralytics).

Only the COCO class 0 ("person") is used. No faces are recognised and no identities are kept:
a track ID is just a temporary number that lets us follow the same box from frame to frame.

Model: YOLO11n (smallest Ultralytics model, runs on a laptop CPU). License: AGPL-3.0 (Ultralytics);
acceptable for this open academic project; a commercial deployment would need an Ultralytics licence
or a differently licensed detector.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

DEFAULT_MODEL = Path(__file__).parent / "models" / "yolo11n.pt"


@dataclass
class TrackedPerson:
    track_id: int
    confidence: float
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def foot_point(self) -> tuple[float, float]:
        """Bottom-centre of the box: the point that crosses a floor/door line."""
        return (self.x1 + self.x2) / 2, self.y2


class PersonTracker:
    def __init__(self, model_path: str | Path = DEFAULT_MODEL, confidence: float = 0.35, image_size: int = 640):
        from ultralytics import YOLO  # imported lazily so the counting logic can be tested without torch

        self.model = YOLO(str(model_path))
        self.confidence = confidence
        self.image_size = image_size
        self.name = Path(model_path).stem

    def track(self, frame) -> list[TrackedPerson]:
        """Detect people in one frame and continue their tracks (ByteTrack)."""
        results = self.model.track(frame, persist=True, classes=[0], conf=self.confidence, imgsz=self.image_size,
                                   tracker="bytetrack.yaml", verbose=False)
        boxes = results[0].boxes
        if boxes is None or boxes.id is None:
            return []
        people = []
        for (x1, y1, x2, y2), conf, tid in zip(boxes.xyxy.tolist(), boxes.conf.tolist(), boxes.id.int().tolist()):
            people.append(TrackedPerson(int(tid), float(conf), x1, y1, x2, y2))
        return people

    def reset(self):
        """Forget all tracks (used when a test video restarts)."""
        predictor = getattr(self.model, "predictor", None)
        if predictor is not None and getattr(predictor, "trackers", None):
            for tracker in predictor.trackers:
                tracker.reset()
