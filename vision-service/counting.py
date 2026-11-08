"""Passenger counting logic, independent of the detection model (so it can be unit-tested).

DOOR mode (doorway-facing camera): a virtual counting line is drawn across the doorway.
Each tracked person has a stable track ID (from the tracker). A crossing is counted only when the
person's foot point moves from one side of the line to the other AND leaves a "dead band" around the
line on the far side (hysteresis). Therefore:
  * a person standing in view is never counted again and again (counts are per crossing, not per frame);
  * jitter on the line does not produce extra counts;
  * crossing in the entry direction = ENTRY, the opposite direction = EXIT.
Limitations: if the tracker loses a person and gives them a new ID while they are exactly on the line,
or several people overlap at the door, a crossing can be missed or duplicated.
The camera must see whole bodies from a distance (ideally from above): if a person fills most of the
picture, walking across moves the box centre only slightly and crossings are missed (see
camera_geometry_warning). That is why the backend
treats the result as an ESTIMATE and supports manual reconciliation.

CABIN mode: the visible count is the number of people tracked in the current frame, smoothed with a
short median. People hidden behind others or outside the view are NOT counted, so it is a lower bound.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from statistics import median

import numpy as np


@dataclass
class CountingLine:
    """A line at `position` (0..1 of the frame) that is vertical (x = const) or horizontal (y = const).
    entry_direction: 'positive' means moving towards larger x (vertical line) / larger y (horizontal line)."""
    orientation: str = "vertical"
    position: float = 0.5
    entry_direction: str = "positive"
    dead_band: float = 0.03  # fraction of the frame on each side of the line

    def coordinate(self, x: float, y: float, width: int, height: int) -> float:
        """Signed normalized distance of a point from the line (+ = positive side)."""
        if self.orientation == "vertical":
            return x / width - self.position
        return y / height - self.position


@dataclass
class _TrackState:
    side: int = 0          # -1, +1, or 0 (inside the dead band / unknown)
    last_seen_frame: int = 0


@dataclass
class LineCrossingCounter:
    line: CountingLine
    forget_after_frames: int = 30
    entries: int = 0
    exits: int = 0
    _tracks: dict = field(default_factory=dict)

    def update(self, frame_index: int, tracks: list[tuple[int, float, float]], width: int, height: int) -> list[str]:
        """tracks = [(track_id, foot_x, foot_y), ...] for one frame. Returns the events of this frame."""
        events = []
        for track_id, x, y in tracks:
            d = self.line.coordinate(x, y, width, height)
            side = 1 if d > self.line.dead_band else (-1 if d < -self.line.dead_band else 0)
            state = self._tracks.get(track_id)
            if state is None:
                self._tracks[track_id] = _TrackState(side=side, last_seen_frame=frame_index)
                continue
            state.last_seen_frame = frame_index
            if side == 0:
                continue  # inside the dead band: wait until the person clearly leaves it
            # A side change (outside the dead band) is one crossing; staying on a side never counts again.
            if state.side != 0 and side != state.side:
                moved_positive = side > state.side
                is_entry = moved_positive == (self.line.entry_direction == "positive")
                if is_entry:
                    self.entries += 1
                    events.append("ENTRY")
                else:
                    self.exits += 1
                    events.append("EXIT")
            state.side = side
        # forget tracks that disappeared long ago (bounded memory)
        stale = [tid for tid, s in self._tracks.items() if frame_index - s.last_seen_frame > self.forget_after_frames]
        for tid in stale:
            del self._tracks[tid]
        return events

    def take_counts(self) -> tuple[int, int]:
        """Entries/exits since the previous call (what is sent in one report)."""
        counts = (self.entries, self.exits)
        self.entries = 0
        self.exits = 0
        return counts

    def reset(self):
        """Called when a test video loops: track IDs restart, so old tracks must be forgotten."""
        self._tracks.clear()


class VisibleCounter:
    """Median of the last `window` per-frame counts, to smooth detector flicker."""

    def __init__(self, window: int = 10):
        self._counts = deque(maxlen=window)

    def update(self, count: int):
        self._counts.append(count)

    def value(self) -> int | None:
        return int(round(median(self._counts))) if self._counts else None


def camera_geometry_warning(boxes: list[tuple[float, float, float]]) -> str | None:
    """boxes = [(width_rel, height_rel, foot_y_rel), ...] of the detections in one report window.
    Line counting needs whole bodies seen from a distance. If people fill most of the picture or their
    feet are cut off, walking across hardly moves the box centre and crossings are missed
    (measured on a laptop webcam: boxes 58-75 % of the frame width, 3 of 6 crossings counted)."""
    if len(boxes) < 5:
        return None
    wide = sum(1 for w, _, _ in boxes if w > 0.45) / len(boxes)
    cut = sum(1 for _, _, foot in boxes if foot >= 0.99) / len(boxes)
    if wide >= 0.5 or cut >= 0.8:
        return ("people too close to the camera for line counting (bodies fill the picture or feet are cut off); "
                "mount the camera higher / further away so whole bodies are visible")
    return None


def frame_quality(frame: np.ndarray) -> float:
    """0..1 score from brightness and sharpness (Laplacian variance). Low = dark or blurred frame."""
    import cv2

    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
    brightness = float(gray.mean()) / 255.0
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    brightness_score = max(0.0, 1.0 - abs(brightness - 0.5) * 2.2)   # best around mid-grey
    sharpness_score = min(1.0, sharpness / 150.0)
    return round(max(0.0, min(1.0, 0.5 * brightness_score + 0.5 * sharpness_score)), 3)
