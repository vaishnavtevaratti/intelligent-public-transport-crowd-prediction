"""Manual validation support for DOOR-mode line-crossing counts on the test video.

Produces, in output/crossings/:
  * events.json            every crossing the counter reports (frame, track id, direction, position)
  * panels_XX.jpg          for each event: the person BEFORE and AFTER the crossing (crop, line in red),
                           so a human can check that each counted event is a real crossing (precision)
  * slitscan.jpg           the pixel column on the counting line for every frame, side by side
                           (time runs left to right): every person who crosses appears as one figure,
                           so a human can count all real crossings, including missed ones (recall)
Then fill test_videos/vtest_crossings_ground_truth.json and run:  python validate_crossings.py --score
"""

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from counting import CountingLine, LineCrossingCounter
from detector import PersonTracker

BASE = Path(__file__).parent
VIDEO = BASE / "test_videos" / "vtest.avi"
OUT = BASE / "output" / "crossings"
TRUTH = BASE / "test_videos" / "vtest_crossings_ground_truth.json"
LINE = 0.5
OFFSET = 8  # frames before / after the event shown in a panel


def detect_events():
    cap = cv2.VideoCapture(str(VIDEO))
    tracker = PersonTracker()
    counter = LineCrossingCounter(CountingLine("vertical", LINE, "positive"))
    frames, events, slit = [], [], []
    boxes = {}  # frame -> {track_id: box}
    index = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        index += 1
        frames.append(frame)
        h, w = frame.shape[:2]
        x = int(LINE * w)
        slit.append(frame[:, x - 1:x + 2].copy())  # 3-pixel column on the line
        people = tracker.track(frame)
        boxes[index] = {p.track_id: [round(v) for v in (p.x1, p.y1, p.x2, p.y2)] for p in people}
        for p in people:
            e0, x0 = counter.entries, counter.exits
            counter.update(index, [(p.track_id, *p.foot_point)], w, h)
            if counter.entries > e0 or counter.exits > x0:
                events.append({"frame": index, "track_id": p.track_id,
                               "direction": "L->R" if counter.entries > e0 else "R->L",
                               "box": [round(v) for v in (p.x1, p.y1, p.x2, p.y2)]})
    return frames, events, slit, boxes


def box_near(boxes, track_id, frame, frames_total):
    """The tracked person's box at `frame`, or the closest frame where the track exists."""
    for delta in range(0, 6):
        for f in (frame - delta, frame + delta):
            if 1 <= f <= frames_total and track_id in boxes.get(f, {}):
                return boxes[f][track_id], f
    return None, frame


def panel(frames, event, w, h, boxes):
    x1, y1, x2, y2 = event["box"]
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    half_w, half_h = 110, 130
    crops = []
    for f in (event["frame"] - OFFSET, event["frame"] + OFFSET):
        box, shown = box_near(boxes, event["track_id"], f, len(frames))
        img = frames[max(0, min(len(frames) - 1, shown - 1))].copy()
        cv2.line(img, (int(LINE * w), 0), (int(LINE * w), h), (0, 0, 255), 2)
        if box:  # the counted person, in THIS frame
            bx1, by1, bx2, by2 = box
            cv2.rectangle(img, (bx1, by1), (bx2, by2), (0, 220, 0), 3)
            cv2.circle(img, ((bx1 + bx2) // 2, by2), 5, (0, 255, 255), -1)  # foot point
        xa, xb = max(0, cx - half_w), min(w, cx + half_w)
        ya, yb = max(0, cy - half_h), min(h, cy + half_h)
        crop = cv2.resize(img[ya:yb, xa:xb], (2 * half_w, 2 * half_h))
        crops.append(crop)
    tile = np.hstack(crops)
    label = f"#{event['n']} f{event['frame']} id{event['track_id']} {event['direction']}"
    cv2.rectangle(tile, (0, 0), (tile.shape[1], 22), (0, 0, 0), -1)
    cv2.putText(tile, label, (5, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1)
    return tile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--score", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    if args.score:
        events = json.loads((OUT / "events.json").read_text())
        truth = json.loads(TRUTH.read_text())
        verdicts = truth["event_verdicts"]  # {"1": "correct" | "false" | "wrong_direction"}
        correct = sum(1 for v in verdicts.values() if v == "correct")
        false = sum(1 for v in verdicts.values() if v == "false")
        wrong_dir = sum(1 for v in verdicts.values() if v == "wrong_direction")
        actual = truth["actual_crossings"]
        print(f"counted events {len(events)}: correct {correct}, false {false}, wrong direction {wrong_dir}")
        print(f"precision {correct / len(events):.2f}; recall {correct / actual['total']:.2f} "
              f"({correct} of {actual['total']} real crossings found)")
        print(f"counted L->R {sum(e['direction'] == 'L->R' for e in events)} vs real {actual['L->R']}; "
              f"counted R->L {sum(e['direction'] == 'R->L' for e in events)} vs real {actual['R->L']}")
        return

    frames, events, slit, boxes = detect_events()
    h, w = frames[0].shape[:2]
    for n, e in enumerate(events, 1):
        e["n"] = n
    (OUT / "events.json").write_text(json.dumps(events, indent=1))
    tiles = [panel(frames, e, w, h, boxes) for e in events]
    for page in range(0, len(tiles), 6):
        group = tiles[page:page + 6]
        while len(group) % 2:
            group.append(np.zeros_like(tiles[0]))
        rows = [np.hstack(group[i:i + 2]) for i in range(0, len(group), 2)]
        cv2.imwrite(str(OUT / f"panels_{page // 6 + 1:02d}.jpg"), np.vstack(rows))
    # slit-scan: time left->right; tick marks every 50 frames
    strip = np.hstack(slit)
    strip = cv2.resize(strip, (strip.shape[1], h))
    for f in range(0, strip.shape[1], 3 * 50):
        cv2.line(strip, (f, h - 25), (f, h), (0, 0, 255), 2)
        cv2.putText(strip, str(f // 3), (f + 3, h - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
    for part in range(0, strip.shape[1], 800):
        cv2.imwrite(str(OUT / f"slitscan_{part // 800 + 1}.jpg"), strip[:, part:part + 800])
    print(f"{len(events)} events: {sum(e['direction'] == 'L->R' for e in events)} L->R, "
          f"{sum(e['direction'] == 'R->L' for e in events)} R->L; panels and slit-scan written to {OUT}")


if __name__ == "__main__":
    main()
