"""Validate person detection against manually counted frames of the test video.

  python evaluate_counts.py --extract        # saves sampled frames to output/eval/ for manual counting
  python evaluate_counts.py                  # compares detector counts with test_videos/vtest_ground_truth.json

The ground-truth file holds, for each sampled frame index, the number of people a human counted as
visible (fully or mostly) in the frame. Metrics: per-frame absolute error, MAE, and total bias.
It also replays the whole video through the tracker and reports the number of door-line crossings.
"""

import argparse
import json
from pathlib import Path

import cv2

from counting import CountingLine, LineCrossingCounter
from detector import PersonTracker

BASE = Path(__file__).parent
VIDEO = BASE / "test_videos" / "vtest.avi"
TRUTH = BASE / "test_videos" / "vtest_ground_truth.json"
OUT = BASE / "output" / "eval"
SAMPLE_FRAMES = [40, 120, 200, 280, 360, 440, 520, 600, 680, 760]


def read_frame(cap, index):
    cap.set(cv2.CAP_PROP_POS_FRAMES, index)
    ok, frame = cap.read()
    if not ok:
        raise SystemExit(f"cannot read frame {index}")
    return frame


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--extract", action="store_true")
    ap.add_argument("--confidence", type=float, default=0.35)
    args = ap.parse_args()
    cap = cv2.VideoCapture(str(VIDEO))
    OUT.mkdir(parents=True, exist_ok=True)

    if args.extract:
        for i in SAMPLE_FRAMES:
            cv2.imwrite(str(OUT / f"frame_{i:03d}.jpg"), read_frame(cap, i))
        print(f"Saved {len(SAMPLE_FRAMES)} frames to {OUT}; count people and fill {TRUTH}")
        return

    truth = {int(k): v for k, v in json.loads(TRUTH.read_text())["counts"].items()}
    detector = PersonTracker(confidence=args.confidence)
    rows = []
    for i in SAMPLE_FRAMES:
        frame = read_frame(cap, i)
        detector.reset()
        people = detector.track(frame)
        annotated = frame.copy()
        for p in people:
            cv2.rectangle(annotated, (int(p.x1), int(p.y1)), (int(p.x2), int(p.y2)), (0, 200, 0), 2)
        cv2.imwrite(str(OUT / f"detected_{i:03d}.jpg"), annotated)
        rows.append((i, truth[i], len(people)))

    errors = [abs(d - t) for _, t, d in rows]
    print("frame  manual  detected  error")
    for i, t, d in rows:
        print(f"{i:5d}  {t:6d}  {d:8d}  {d - t:+5d}")
    mae = sum(errors) / len(errors)
    bias = sum(d - t for _, t, d in rows)
    total = sum(t for _, t, _ in rows)
    print(f"MAE {mae:.2f} people/frame; total manual {total}, total detected {total + bias} (bias {bias:+d})")

    # full replay with tracking and a vertical counting line in the middle
    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
    detector.reset()
    counter = LineCrossingCounter(CountingLine("vertical", 0.5, "positive"))
    ids, index = set(), 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        index += 1
        people = detector.track(frame)
        ids.update(p.track_id for p in people)
        counter.update(index, [(p.track_id, *p.foot_point) for p in people], frame.shape[1], frame.shape[0])
    entries, exits = counter.take_counts()
    print(f"Full replay: {index} frames, {len(ids)} distinct track IDs, line crossings: {entries} left-to-right, {exits} right-to-left")
    result = {"sample_frames": [{"frame": i, "manual": t, "detected": d} for i, t, d in rows],
              "mae": round(mae, 3), "bias": bias, "distinct_track_ids": len(ids),
              "crossings_left_to_right": entries, "crossings_right_to_left": exits, "confidence": args.confidence}
    (BASE / "output" / "evaluation.json").write_text(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
