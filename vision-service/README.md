# Camera-Based Passenger Counting (vision-service)

This service runs **on (or next to) the bus**. It reads a camera or a test video, finds **people** with a pretrained YOLO detector, follows them from frame to frame with the ByteTrack tracker, and sends **counts only** to the Spring Boot backend every few seconds.

```
camera / test video ─▶ YOLO11n person detection ─▶ ByteTrack track IDs ─┬─ door mode: line-crossing entries/exits
                                                                         └─ cabin mode: visible people (median)
        ─▶ POST /api/sensors/observations (X-Sensor-Code / X-Sensor-Key) ─▶ MySQL ─▶ WebSocket /topic/occupancy ─▶ UI
```

## What is real and what is not (important)

| Part | Status |
|---|---|
| Person detection and tracking | **Real model** (YOLO11n + ByteTrack), run and measured on a recorded video |
| Counting logic (line crossing, no repeated counting) | Real; 8 unit tests |
| Backend integration, storage and live UI | Real; verified end to end with the test video |
| **Camera installed in a bus** | **Not tested.** No bus camera was available. All runs used `test_videos/vtest.avi` and were reported as `TEST_VIDEO` |
| Laptop webcam (`--source 0`) | Supported in code, but **not run**: it would film people without their consent |
| Seat-level occupancy from cameras | **Not implemented.** Person detection cannot tell reliably who is sitting on which seat. Seats come only from a seat sensor or a manual count |

The test video is `vtest.avi` from the OpenCV sample data (Apache-2.0 licence, https://github.com/opencv/opencv/tree/4.x/samples/data). It shows pedestrians on a campus filmed from above, **not a bus interior**. For the demo, the vertical middle line of the picture plays the role of a doorway.

## Setup

```powershell
cd vision-service
python -m venv .venv
.venv\Scripts\python -m pip install torch==2.14.1 torchvision==0.29.1 --index-url https://download.pytorch.org/whl/cpu
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m pytest -q tests          # 8 counting-logic tests
```

The weights file `models/yolo11n.pt` (5.4 MB) is included. Ultralytics models are AGPL-3.0, which is acceptable for an open academic project; a commercial product would need an Ultralytics licence or a differently licensed detector.

## Laptop webcam demonstration

Use this to try the full pipeline live on a laptop. **It is not bus-camera validation.**
- Webcam data is reported as `WEBCAM_DEMO` and shown as **"Laptop webcam demo"** in the web app.
- Real bus cameras need an explicit `--camera-kind bus`, which reports as `CAMERA`.
- Bus positions stay labelled **"Simulated"**.

1. In **Admin → Occupancy & Sensors**, register a sensor (for example `CAM-WEBCAM-CABIN`, cabin type) on a test bus, and put its key in `sensor_keys.json`. Ready-made demo buses with sensors already exist:

| Demo bus | Capacity | Seats | Sensor |
|---|---|---|---|
| DEMO-WEBCAM | 4 | 2 | CAM-WEBCAM-CABIN (cabin) |
| DEMO-WEBDOOR | 10 | — | CAM-WEBCAM-DOOR (door) |

   With these small capacities, one or two people visibly change the utilisation and crowd level. They have no route: their positions are not simulated, and they don't affect route forecasts.
2. **Make sure everyone in view agrees to be filmed**, then start:

```powershell
.venv\Scripts\python run_counter.py --source 0 --mode cabin --sensor-code CAM-WEBCAM-CABIN --confirm-consent
.venv\Scripts\python run_counter.py --source 0 --mode door  --sensor-code CAM-WEBCAM-DOOR  --confirm-consent   # walk left->right = entry
```

**Start, stop and options:**
- Without `--confirm-consent` the program only explains what would happen and exits; the camera is never opened.
- A live **"Passenger counter"** window shows boxes, the visible count, entries/exits and "not recorded".
- **Stop** with **Q or Esc** in the window, or Ctrl+C. The final counts are sent, the camera is released (its light turns off), and the sensor shows "Offline" after 60 s.
- Use `--source 1` for a second camera, and `--no-preview` to run without the window.

**Errors are reported, never hidden:**
- "cannot open camera index N" lists the usual causes: in use by Teams/Zoom/a browser, or blocked in Windows Settings > Privacy & security > Camera.
- **A black or blank picture** (privacy shutter closed, lens covered, no light) is reported as `CAMERA_UNAVAILABLE` with **no count**, because a black picture does not mean an empty bus.
- Dark or blurred frames are reported as `DEGRADED`.

**Privacy:** nothing is recorded, saved or uploaded. The window only shows the live picture locally, and only counts reach the backend.

**Measured with the webcam (with consent, 2026-10-24):**

| Run | Ground truth (from the person filmed) | Counted | Picture |
|---|---|---|---|
| Cabin, 2 min | 1 person in view after the shutter opened | 1 (every report, confidence 0.69–0.90) | black for the first ~80 s (shutter or lens covered), then OK |
| Door, 2 min | ≈ 10 crossings (5 each way) | **0** | good (0.93–0.98) |
| Door, 60 s, with `--debug-tracks` | 6 crossings (3 each way), person stepped back | **1 entry + 2 exits = 3** (recall 0.5) | good (0.64–0.97) |

- **Why door counting failed on the laptop:** the person filled **58–75 % of the frame width** with the feet cut off, so walking across moved the box centre only slightly around the line. The tracker also re-assigned IDs (1 → 9 → 12) and kept one stationary low-confidence false detection.
- Replaying the numbers-only log (`python replay_tracks.py output/tracks_CAM-WEBCAM-DOOR.csv --truth-entries 3 --truth-exits 3`) reproduces the live result exactly. Track stitching and a narrower dead band were tried offline and did **not** help, so they were not kept.
- Fix: the counter now reports **DEGRADED: "people too close to the camera for line counting"** in door mode when boxes are wider than 45 % of the frame or feet are cut off. It fires on this webcam log and not on the test video.
- **Conclusion:** a desk-height laptop webcam is unsuitable for doorway counting. A door camera must be mounted **above the door, looking down**, so whole bodies pass the line.
- `--debug-tracks` writes `output/tracks_<sensor>.csv` with numbers only (frame, track ID, box position and size, confidence), for diagnosing counting without recording anyone.

## Start / stop from the web app (counter agent)

`agent.py` is a small local service (Python standard library only) that starts and stops `run_counter.py` for the web app:

```powershell
.venv\Scripts\python agent.py      # http://127.0.0.1:8002, Ctrl+C stops it and every counter it started
```

Then open **Admin → Occupancy & sensors → Counter control**. Each sensor whose key is in `sensor_keys.json` has a **Start** button. Choose a test video or a camera, and door or cabin mode. **Stop** ends the counter gracefully: it sends its last report and releases the camera, and is forced after 15 s.

**Live view in the web app (View button):**
- The page shows the **annotated picture**: person boxes and, in door mode, the red counting line.
- Next to it are three different numbers:
  - **People visible now**: people in the picture, median of recent frames.
  - **Crossings this session**: people who crossed the line, each crossing counted once by track ID and side-change with a dead band.
  - **Estimated onboard (backend)**: starting count + entries − exits, or the visible lower bound in cabin mode.
- **Reset session counts** sets the session in/out totals back to zero. It does not change the onboard estimate; use *Reconcile* for that.
- **How frames travel:** `run_counter.py --live-port` serves the latest frame on 127.0.0.1 with a per-run token. The agent and the backend (admin only) pass it on, and the page fetches about 2 pictures per second.
- **Privacy:** frames are only ever held in memory, and the last one is dropped when the counter stops. Nothing is written to disk.
- **Cost:** the live view costs about 2 % throughput (250 vs. 256 frames in 20 s on the test video).

- **Security:** it listens on `127.0.0.1` only, and every request needs the `X-Agent-Token` header. The token is generated on first start in `agent_token.txt` (git-ignored); the backend reads the same file (`app.vision-agent.token-file`) or `VISION_AGENT_TOKEN`. Only ADMIN users can reach `/api/admin/counters`.
- **Inputs are restricted:** a camera index 0–9 or a file listed in `test_videos/`; arbitrary paths are rejected by the backend (400) and the agent.
- **Consent:** a camera is only opened when the request has `confirmConsent: true`. The web app requires the checkbox "Everyone who will be in view of the camera has agreed to be counted". A webcam run opens the local preview window, and nothing is recorded or uploaded.
- **Duplicates are refused:** one counter per sensor, and one counter per camera (409).
- **Logs:** each counter's output goes to `output/agent_<sensor>.log`; the last line is shown in the web app.
- If the agent is not running, the page says so (backend 503: "Counter agent is not running on this computer").
- The agent controls counters **on the computer it runs on**. On a real bus, the agent would run on the bus PC, and the backend would need to reach it (for example through a VPN). That set-up has not been tested.

## Running a counter

1. In the web app, go to **Admin → Occupancy & Sensors → Register sensor**. Choose the bus and the type (door or cabin camera).
2. Copy the **secret key**. It is shown once. Save it in `sensor_keys.json` (git-ignored) as `{"CAM-BUS101-DOOR": "<key>"}`, or pass it with `--sensor-key`.
3. Start the counter:

```powershell
# demo with the test video (reported as TEST_VIDEO)
.venv\Scripts\python run_counter.py --source test_videos/vtest.avi --mode door  --sensor-code CAM-BUS101-DOOR --loop
.venv\Scripts\python run_counter.py --source test_videos/vtest.avi --mode cabin --sensor-code CAM-BUS102-CABIN --loop

# a real camera (USB index 0, or an RTSP URL of an IP camera) on the bus PC
.venv\Scripts\python run_counter.py --source 0 --mode door --sensor-code CAM-BUS101-DOOR --line-orientation horizontal --line-position 0.6 --entry-direction positive

# check the counts without a backend
.venv\Scripts\python run_counter.py --source test_videos/vtest.avi --mode door --sensor-code TEST --dry-run --max-seconds 30
```

Useful options:

| Option | Meaning |
|---|---|
| `--report-every` | seconds between reports (default 5) |
| `--line-orientation`, `--line-position`, `--entry-direction` | door line placement, and which crossing direction counts as an entry |
| `--confidence` | detector confidence threshold (default 0.35) |
| `--stride N` | detect on every Nth frame, for slower computers |
| `--save-preview` | writes one annotated JPEG (`output/preview_<sensor>.jpg`, overwritten) to help aim the camera and line. Use only during setup |

**Reliability features:**
- Each report carries an increasing **sequence number**. It is saved in `output/sequence_<sensor>.json`, so it survives restarts.
- If the backend is unreachable, reports are **queued and retried**. A retry is safe, because the backend ignores sequence numbers it already has.
- If the camera can't be opened or stops delivering frames, the counter sends `CAMERA_UNAVAILABLE` reports.
- A model failure sends `INFERENCE_ERROR`.
- Dark or blurred frames, scored by brightness and sharpness, send `DEGRADED`.
- The admin page shows a sensor as offline after 60 s without reports.

## How counting works

- **Door camera:** a virtual line crosses the doorway. A tracked person is counted when their **foot point** moves from one side to the other, **and** they leave a dead band around the line on the far side. Someone standing in view is never counted again, and jitter on the line is not counted. Each report sends *entries and exits since the previous report*. The backend keeps a running total: `onboard = previous + entries − exits`.
- **Cabin camera:** the number of people tracked in the current frame, smoothed by a 10-frame median. Hidden people are missed, so the value is a **lower bound**. The UI shows it as "≥ N".

A doorway counter cannot know how many people were already on board. The backend assumes 0 and asks for a **manual reconciliation** (a conductor head count). It also flags the estimate when:
- reports are missing (a gap in the sequence numbers);
- the total would go negative (it is clamped to 0);
- the door camera fails;
- a cabin camera sees more people than the door count.

## Measured accuracy (actual results on the test video)

`python evaluate_counts.py` compares the detector with people **counted by hand** on 10 sampled frames (`test_videos/vtest_ground_truth.json`, one annotator):

| Frame | 40 | 120 | 200 | 280 | 360 | 440 | 520 | 600 | 680 | 760 |
|---|---|---|---|---|---|---|---|---|---|---|
| Manual count | 6 | 8 | 7 | 7 | 6 | 3 | 7 | 6 | 7 | 8 |
| Detected | 6 | 7 | 7 | 7 | 6 | 3 | 6 | 6 | 7 | 8 |

- **MAE 0.20 people per frame**; 63 detected against 65 counted (bias −2). Both misses were **occluded people**: one behind a signpost in frame 120, and two overlapping people in frame 520.
- Full replay of 795 frames: **36 distinct track IDs**, which shows some **ID switches** (one person getting a new ID).

**Door crossings vs. a manual count** (`python validate_crossings.py`, then `--score`):
- Every counted event was checked on before/after crops of the tracked person.
- Missed crossings were searched with a slit-scan image of the counting line and checked on frame strips.
- Ground truth is in `test_videos/vtest_crossings_ground_truth.json`.

| | Counted | Real |
|---|---|---|
| Left→right (entries) | 11 | 11 |
| Right→left (exits) | 13 | 15 |
| Total | 24 (all 24 correct) | 26 |

- **Precision 1.00** (no false or wrong-direction counts). **Recall 0.92**: one definite and one probable miss, both while other people were near the line.
- A person who walked up to the line and turned back, and a group standing on the line, were correctly **not** counted.
- One annotator; a street seen from above, not a bus doorway.
- These numbers describe a clear outdoor scene from above. A crowded bus interior has much more occlusion, so accuracy there will be lower and must be measured after installation.

## Camera placement

- **Door camera:** mount it above the doorway, looking down (top-down view minimises occlusion). Put the counting line across the door, parallel to the step. Use one camera per door. Make sure the step and boarding area are fully visible and lit.
- **Cabin camera:** mount it high at the front, looking along the aisle. It will always miss people behind others. Use it as a lower bound or to cross-check the door counts.
- Avoid overlapping views. If two cameras see the same people, their counts must not be added.

## Privacy

- Detection runs locally on the bus device. **No video is stored or transmitted.** Frames are discarded in memory. Raw footage is never saved; `--save-preview` (off by default) only overwrites a single setup image.
- There is no face recognition and no re-identification. Track IDs are temporary numbers that are forgotten after about 3 seconds out of view.
- Only counts, timestamps, confidence, frame quality and sensor health are stored. They are deleted after 30 days (`app.occupancy.retention-days`).
- Before deployment:
  - Put visible notices in the bus saying camera-based counting is in use.
  - Get approval from the transport authority.
  - Follow the applicable data-protection law. In India that is the **Digital Personal Data Protection Act, 2023**: purpose limitation, notice, and data minimisation.
  - Restrict physical access to the device.
