"""Camera-based passenger counter that reports to the Spring Boot backend.

Examples
  # doorway camera replayed from the lawful OpenCV sample video (clearly reported as TEST_VIDEO)
  python run_counter.py --source test_videos/vtest.avi --mode door --sensor-code CAM-BUS101-DOOR

  # LAPTOP WEBCAM demonstration (reported as WEBCAM_DEMO, never as a bus camera); live preview window,
  # press Q or Esc in the window (or Ctrl+C) to stop. Requires explicit consent:
  python run_counter.py --source 0 --mode cabin --sensor-code CAM-WEBCAM-CABIN --confirm-consent

  # a real camera installed in a bus (reported as CAMERA)
  python run_counter.py --source 0 --camera-kind bus --mode door --sensor-code CAM-BUS101-DOOR --confirm-consent

  # no backend: print the reports
  python run_counter.py --source test_videos/vtest.avi --mode door --dry-run --max-seconds 30

The sensor key is read from --sensor-key, the SENSOR_KEY environment variable, or
sensor_keys.json ({"CAM-BUS101-DOOR": "..."}; git-ignored).

Privacy: frames are processed in memory and discarded; no recording is saved or sent. The optional
preview window only shows the live picture on this screen. Nothing is written to disk unless
--save-preview is given (one annotated JPEG, overwritten, for setting up the camera).
A camera is only opened with --confirm-consent, i.e. after everyone in view has agreed to be filmed.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import signal
import time
from collections import deque
from datetime import datetime
from pathlib import Path

import cv2
import requests

from counting import CountingLine, LineCrossingCounter, VisibleCounter, camera_geometry_warning, frame_quality

BASE = Path(__file__).parent
STATE_DIR = BASE / "output"
log = logging.getLogger("passenger-counter")


class Reporter:
    """Sends reports with increasing sequence numbers; keeps unsent reports and retries them.
    Retrying is safe because the backend ignores a sequence number it has already stored."""

    def __init__(self, backend: str, sensor_code: str, sensor_key: str | None, dry_run: bool, max_queue: int = 500):
        self.url = backend.rstrip("/") + "/api/sensors/observations"
        self.sensor_code = sensor_code
        self.sensor_key = sensor_key
        self.dry_run = dry_run
        self.queue: deque = deque(maxlen=max_queue)
        STATE_DIR.mkdir(exist_ok=True)
        self.state_file = STATE_DIR / f"sequence_{sensor_code}.json"
        self.sequence = json.loads(self.state_file.read_text())["last"] if self.state_file.exists() else 0
        # Reports that could not be delivered before the last exit are re-sent first.
        self.queue_file = STATE_DIR / f"unsent_{sensor_code}.json"
        if self.queue_file.exists():
            self.queue.extend(json.loads(self.queue_file.read_text()))
            self.queue_file.unlink()
            log.info("Loaded %d unsent report(s) from the previous run", len(self.queue))
        self.sent = 0
        self.last_response = None

    def next_sequence(self) -> int:
        self.sequence += 1
        self.state_file.write_text(json.dumps({"last": self.sequence}))  # survives restarts: no reused numbers
        return self.sequence

    def report(self, payload: dict):
        payload["sequenceNumber"] = self.next_sequence()
        self.queue.append(payload)
        self.flush()

    def flush(self):
        while self.queue:
            payload = self.queue[0]
            if self.dry_run:
                print(json.dumps(payload))
                self.queue.popleft()
                continue
            try:
                res = requests.post(self.url, json=payload, timeout=5,
                                    headers={"X-Sensor-Code": self.sensor_code, "X-Sensor-Key": self.sensor_key or ""})
            except requests.RequestException as exc:
                log.warning("Backend unreachable (%s); %d report(s) queued", exc.__class__.__name__, len(self.queue))
                return
            if res.status_code == 401:
                raise SystemExit("Backend rejected the sensor key (401). Check --sensor-code / --sensor-key.")
            if res.status_code >= 500:
                log.warning("Backend error %s; will retry", res.status_code)
                return
            if res.status_code >= 400:
                log.error("Report %s rejected (%s): %s", payload["sequenceNumber"], res.status_code, res.text[:200])
            else:
                self.last_response = res.json()
                occ = self.last_response.get("occupancy", {})
                log.info("report #%s %s -> onboard estimate %s (%s)", payload["sequenceNumber"],
                         self.last_response.get("status"), occ.get("estimatedOnboard"), occ.get("dataStatus"))
            self.sent += 1
            self.queue.popleft()

    def save_unsent(self):
        """Keeps undelivered reports on disk so they are sent on the next start (sequence numbers stay unique)."""
        if self.queue and not self.dry_run:
            self.queue_file.write_text(json.dumps(list(self.queue)))
            log.warning("%d unsent report(s) saved to %s", len(self.queue), self.queue_file)


BLOCKED_QUALITY = 0.05   # below this the picture is black/blank: the camera cannot see anything
DEGRADED_QUALITY = 0.35  # below this detections are unreliable (dark or blurred)


def health_for_quality(avg_quality: float) -> tuple[str, str | None]:
    """Health of one report window. A black picture is NOT an empty bus, so no count is reported for it."""
    if avg_quality < BLOCKED_QUALITY:
        return "CAMERA_UNAVAILABLE", ("camera picture is black or blank (privacy shutter closed, lens covered "
                                      "or no light): no count reported")
    if avg_quality < DEGRADED_QUALITY:
        return "DEGRADED", "low frame quality (dark or blurred)"
    return "OK", None


def open_source(source: str):
    """Opens a video file, or camera index N. Returns (capture or None, error message or None)."""
    if not source.isdigit():
        cap = cv2.VideoCapture(source)
        return (cap, None) if cap.isOpened() else (None, f"cannot open video file {source}")
    index = int(source)
    # DirectShow opens Windows webcams faster and more reliably than the default backend.
    cap = cv2.VideoCapture(index, cv2.CAP_DSHOW) if os.name == "nt" else cv2.VideoCapture(index)
    if not cap.isOpened():
        return None, (f"cannot open camera index {index}: no camera with that index, it is in use by another "
                      "application (Teams, Zoom, browser), or camera access is blocked in Windows Settings > "
                      "Privacy & security > Camera")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    ok, _ = cap.read()
    if not ok:
        cap.release()
        return None, f"camera index {index} opened but delivers no frames (covered, disabled or in use)"
    return cap, None


def draw_overlay(frame, people, line, mode, mode_label, visible, entries, exits, status):
    """Live preview only (never saved): boxes, counting line and the numbers that are being reported."""
    h, w = frame.shape[:2]
    for p in people:
        cv2.rectangle(frame, (int(p.x1), int(p.y1)), (int(p.x2), int(p.y2)), (0, 200, 0), 2)
        cv2.putText(frame, f"#{p.track_id} {p.confidence:.2f}", (int(p.x1), max(12, int(p.y1) - 5)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 200, 0), 1)
    if mode == "door":
        if line.orientation == "vertical":
            x = int(line.position * w)
            cv2.line(frame, (x, 0), (x, h), (0, 0, 255), 2)
        else:
            y = int(line.position * h)
            cv2.line(frame, (0, y), (w, y), (0, 0, 255), 2)
    cv2.rectangle(frame, (0, 0), (w, 54), (0, 0, 0), -1)
    counts = "visible: " + ("-" if visible is None else str(visible))
    if mode == "door":
        counts += f"   in: {entries}   out: {exits} (this session)"
    cv2.putText(frame, counts, (8, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
    label = "LAPTOP WEBCAM DEMO - not a bus camera" if mode_label == "WEBCAM_DEMO" else mode_label
    cv2.putText(frame, f"{label} | not recorded | {status}", (8, 44),
                cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
    return frame


def main():
    ap = argparse.ArgumentParser(description="YOLO + ByteTrack passenger counter")
    ap.add_argument("--source", required=True, help="camera index (e.g. 0) or video file path")
    ap.add_argument("--mode", choices=["door", "cabin"], required=True)
    ap.add_argument("--sensor-code", required=True)
    ap.add_argument("--sensor-key", default=None)
    ap.add_argument("--backend", default=os.environ.get("BACKEND_URL", "http://localhost:8080"))
    ap.add_argument("--report-every", type=float, default=5.0, help="seconds between reports")
    ap.add_argument("--line-orientation", choices=["vertical", "horizontal"], default="vertical")
    ap.add_argument("--line-position", type=float, default=0.5)
    ap.add_argument("--entry-direction", choices=["positive", "negative"], default="positive",
                    help="positive = left-to-right (vertical line) or top-to-bottom (horizontal line) is an ENTRY")
    ap.add_argument("--confidence", type=float, default=0.35)
    ap.add_argument("--stride", type=int, default=1, help="run the detector on every Nth frame")
    ap.add_argument("--loop", action="store_true", help="restart a test video when it ends")
    ap.add_argument("--no-realtime", action="store_true", help="process a video as fast as possible")
    ap.add_argument("--max-seconds", type=float, default=0, help="stop after this many seconds (0 = run forever)")
    ap.add_argument("--dry-run", action="store_true", help="print reports instead of sending them")
    ap.add_argument("--save-preview", action="store_true", help="overwrite output/preview_<sensor>.jpg (setup only)")
    ap.add_argument("--camera-kind", choices=["webcam", "bus"], default="webcam",
                    help="for a camera index: 'webcam' = laptop demonstration (WEBCAM_DEMO, default), "
                         "'bus' = a camera installed in a bus (CAMERA)")
    ap.add_argument("--confirm-consent", action="store_true",
                    help="required to open a camera: confirms everyone in view has agreed to be filmed")
    ap.add_argument("--preview", dest="preview", action="store_true", default=None,
                    help="show a live window with detections (default for cameras)")
    ap.add_argument("--no-preview", dest="preview", action="store_false")
    ap.add_argument("--stop-file", default=None,
                    help="stop gracefully as soon as this file exists (used by agent.py for Start/Stop in the web app)")
    ap.add_argument("--live-port", type=int, default=0,
                    help="serve the live annotated view and status on 127.0.0.1:<port> for the web app (token in LIVE_TOKEN)")
    ap.add_argument("--debug-tracks", action="store_true",
                    help="write output/tracks_<sensor>.csv with NUMBERS ONLY per detection (frame, track id, box, "
                         "confidence) to diagnose counting; no images are written")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    key = args.sensor_key or os.environ.get("SENSOR_KEY")
    keys_file = BASE / "sensor_keys.json"
    if not key and keys_file.exists():
        key = json.loads(keys_file.read_text()).get(args.sensor_code)
    if not key and not args.dry_run:
        raise SystemExit("No sensor key: use --sensor-key, SENSOR_KEY or sensor_keys.json")

    is_file = not args.source.isdigit()
    mode_label = "TEST_VIDEO" if is_file else ("CAMERA" if args.camera_kind == "bus" else "WEBCAM_DEMO")
    if not is_file and not args.confirm_consent:
        raise SystemExit(
            f"Camera index {args.source} would be opened and people in view would be detected and counted.\n"
            "Frames are processed in memory only: nothing is recorded, saved or uploaded; only counts are sent.\n"
            "Make sure everyone in view has agreed, then re-run with --confirm-consent.")
    preview = (not is_file) if args.preview is None else args.preview
    stop_reason = None
    reporter = Reporter(args.backend, args.sensor_code, key, args.dry_run)

    from detector import PersonTracker
    tracker = PersonTracker(confidence=args.confidence)
    line = CountingLine(args.line_orientation, args.line_position, args.entry_direction)
    door_counter = LineCrossingCounter(line)
    visible = VisibleCounter(window=10)
    session = {"entries": 0, "exits": 0}   # cumulative crossings shown in the live view (reset from the web app)
    last_health = {"health": "STARTING", "message": None}
    live = None
    if args.live_port:
        from live_view import LiveView
        live = LiveView(args.live_port, os.environ.get("LIVE_TOKEN", ""))
        if not live.token:
            raise SystemExit("--live-port needs the LIVE_TOKEN environment variable")
        live.start()
        live.publish_status(mode=args.mode, sourceMode=mode_label, visible=None, sessionEntries=0, sessionExits=0,
                            frames=0, fps=None, health="STARTING", message=None, startedAt=datetime.now().replace(microsecond=0).isoformat())
    confidences: deque = deque(maxlen=200)
    qualities: deque = deque(maxlen=50)
    geometry: deque = deque(maxlen=200)  # (width_rel, height_rel, foot_y_rel) of recent detections

    def send(health: str, message: str | None = None):
        payload = {"observedAt": datetime.now().replace(microsecond=0).isoformat(), "health": health,
                   "mode": mode_label, "detectorModel": f"{tracker.name}+bytetrack", "message": message}
        if health in ("OK", "DEGRADED"):
            payload["meanConfidence"] = round(sum(confidences) / len(confidences), 3) if confidences else None
            payload["frameQuality"] = round(sum(qualities) / len(qualities), 3) if qualities else None
            if args.mode == "door":
                payload["entries"], payload["exits"] = door_counter.take_counts()
                payload["visibleCount"] = visible.value()  # stored for reference, not used as onboard
            else:
                payload["visibleCount"] = visible.value() or 0
        confidences.clear()
        qualities.clear()
        last_health.update(health=health, message=message)
        reporter.report(payload)

    cap, open_error = open_source(args.source)
    if cap is None:
        log.error(open_error)
    track_log = None
    if args.debug_tracks:
        STATE_DIR.mkdir(exist_ok=True)
        track_log = open(STATE_DIR / f"tracks_{args.sensor_code}.csv", "w", encoding="utf8")
        track_log.write("frame,track_id,x_center_rel,foot_y_rel,width_rel,height_rel,confidence,frame_quality\n")
    started = time.time()
    last_report = time.time()
    frame_index = 0
    fps = cap.get(cv2.CAP_PROP_FPS) if cap else 0
    frame_interval = 1.0 / fps if (is_file and fps and not args.no_realtime) else 0
    log.info("Counting %s (%s, mode=%s) for sensor %s", args.source, mode_label, args.mode, args.sensor_code)

    interrupted = {"flag": False}

    def on_ctrl_c(*_):
        interrupted["flag"] = True  # finish the current frame, send the final report, release the camera

    signal.signal(signal.SIGINT, on_ctrl_c)
    if hasattr(signal, "SIGBREAK"):  # Windows: Ctrl+Break / CTRL_BREAK_EVENT from a parent process
        signal.signal(signal.SIGBREAK, on_ctrl_c)
    stop_file = Path(args.stop_file) if args.stop_file else None
    if stop_file and stop_file.exists():
        stop_file.unlink()  # a stale request from an earlier run must not stop this one
    while not interrupted["flag"] and (not args.max_seconds or time.time() - started < args.max_seconds):
        if stop_file and stop_file.exists():
            stop_reason = "capture stopped from the web app"
            log.info("Stop requested (%s)", stop_file.name)
            break
        loop_start = time.time()
        if cap is None:
            send("CAMERA_UNAVAILABLE", open_error[:200])
            time.sleep(args.report_every)
            cap, open_error = open_source(args.source)
            if cap is None:
                log.error(open_error)
            continue
        ok, frame = cap.read()
        if not ok:
            if is_file and args.loop:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                tracker.reset()
                door_counter.reset()
                continue
            if is_file:
                break
            send("CAMERA_UNAVAILABLE", "camera stopped delivering frames")
            cap.release()
            cap, open_error = None, "camera stopped delivering frames"
            continue

        frame_index += 1
        if frame_index % args.stride == 0:
            quality = frame_quality(frame)
            qualities.append(quality)
            try:
                people = tracker.track(frame)
            except Exception as exc:  # a model failure is reported, not hidden
                log.exception("Inference failed")
                send("INFERENCE_ERROR", str(exc)[:180])
                time.sleep(1)
                continue
            h, w = frame.shape[:2]
            if track_log:
                for p in people:
                    track_log.write(f"{frame_index},{p.track_id},{(p.x1 + p.x2) / 2 / w:.3f},{p.y2 / h:.3f},"
                                    f"{(p.x2 - p.x1) / w:.3f},{(p.y2 - p.y1) / h:.3f},{p.confidence:.2f},{quality}\n")
            visible.update(len(people))
            geometry.extend(((p.x2 - p.x1) / w, (p.y2 - p.y1) / h, p.y2 / h) for p in people)
            confidences.extend(p.confidence for p in people)
            if args.mode == "door":
                for event in door_counter.update(frame_index, [(p.track_id, *p.foot_point) for p in people], w, h):
                    log.info("%s counted", event)
                    session["entries" if event == "ENTRY" else "exits"] += 1
            if live and live.take_reset():
                session.update(entries=0, exits=0)
                log.info("Session counts reset from the web app")
            if preview or live:
                status = "backend OK" if not reporter.queue else f"backend unreachable ({len(reporter.queue)} queued)"
                shown = draw_overlay(frame.copy(), people, line, args.mode, mode_label, visible.value(),
                                     session["entries"], session["exits"], status)
            if live:
                live.publish_frame(shown)
                elapsed = time.time() - started
                live.publish_status(visible=visible.value(), detectedNow=len(people), sessionEntries=session["entries"],
                                    sessionExits=session["exits"], frames=frame_index,
                                    fps=round(frame_index / elapsed, 1) if elapsed > 0 else None,
                                    health=last_health["health"], message=last_health["message"],
                                    backendQueued=len(reporter.queue))
            if preview:
                cv2.imshow(f"Passenger counter - {args.sensor_code}", shown)
                if cv2.waitKey(1) & 0xFF in (ord("q"), ord("Q"), 27):
                    log.info("Stopped by operator (Q/Esc)")
                    stop_reason = "capture stopped by operator"
                    break
            if args.save_preview:
                preview = frame.copy()
                for p in people:
                    cv2.rectangle(preview, (int(p.x1), int(p.y1)), (int(p.x2), int(p.y2)), (0, 200, 0), 2)
                    cv2.putText(preview, f"#{p.track_id}", (int(p.x1), int(p.y1) - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 200, 0), 1)
                if args.mode == "door":
                    if line.orientation == "vertical":
                        x = int(line.position * w)
                        cv2.line(preview, (x, 0), (x, h), (0, 0, 255), 2)
                    else:
                        y = int(line.position * h)
                        cv2.line(preview, (0, y), (w, y), (0, 0, 255), 2)
                cv2.imwrite(str(STATE_DIR / f"preview_{args.sensor_code}.jpg"), preview)

        if time.time() - last_report >= args.report_every:
            avg_quality = sum(qualities) / len(qualities) if qualities else 1.0
            health, message = health_for_quality(avg_quality)
            if health == "OK" and args.mode == "door":
                too_close = camera_geometry_warning(list(geometry))
                if too_close:
                    health, message = "DEGRADED", too_close
            send(health, message)
            geometry.clear()
            last_report = time.time()
        if frame_interval:
            time.sleep(max(0.0, frame_interval - (time.time() - loop_start)))

    if interrupted["flag"]:
        stop_reason = "capture stopped by operator (Ctrl+C)"
        log.info("Stopped by operator (Ctrl+C)")
    if cap is not None and frame_index > 0:
        # final partial window (only when frames were really processed); same black-picture rule as every report
        health, message = health_for_quality(sum(qualities) / len(qualities) if qualities else 1.0)
        send(health, "; ".join(m for m in (message, stop_reason) if m) or None)
    if cap is not None:
        cap.release()  # the camera light turns off here
    if stop_file and stop_file.exists():
        stop_file.unlink()
    if preview:
        cv2.destroyAllWindows()
    if live:
        live.stop()
    if track_log:
        track_log.close()
    reporter.save_unsent()
    log.info("Stopped after %d frames; %d reports sent, %d still queued", frame_index, reporter.sent, len(reporter.queue))


if __name__ == "__main__":
    main()
