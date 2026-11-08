"""Replay a numbers-only track log (written by run_counter.py --debug-tracks) through the line counter.

Lets counting settings be tuned and compared with a known ground truth WITHOUT re-recording anybody:
  python replay_tracks.py output/tracks_CAM-WEBCAM-DOOR.csv --truth-entries 3 --truth-exits 3
"""

import argparse
import csv
from collections import defaultdict

from counting import CountingLine, LineCrossingCounter


def replay(rows, line_position=0.5, dead_band=0.03):
    counter = LineCrossingCounter(CountingLine("vertical", line_position, "positive", dead_band=dead_band))
    by_frame = defaultdict(list)
    for r in rows:
        by_frame[int(r["frame"])].append((int(r["track_id"]), float(r["x_center_rel"]) * 1000, float(r["foot_y_rel"]) * 1000))
    for frame in sorted(by_frame):
        counter.update(frame, by_frame[frame], 1000, 1000)
    return counter.take_counts()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--truth-entries", type=int, required=True)
    ap.add_argument("--truth-exits", type=int, required=True)
    ap.add_argument("--min-confidence", type=float, default=0.0)
    args = ap.parse_args()
    rows = [r for r in csv.DictReader(open(args.csv)) if float(r["confidence"]) >= args.min_confidence]
    truth = args.truth_entries + args.truth_exits
    print(f"ground truth: {args.truth_entries} entries, {args.truth_exits} exits")
    for band in (0.03, 0.01):
        e, x = replay(rows, dead_band=band)
        print(f"dead band {band:.2f}: entries {e}, exits {x}; counted {e + x} of {truth} (error {e + x - truth:+d})")


if __name__ == "__main__":
    main()
