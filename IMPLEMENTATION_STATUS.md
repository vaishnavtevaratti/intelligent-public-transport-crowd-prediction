# Implementation Status — Intelligent Public Transport & Crowd Prediction System

**Source of truth:** `Mega-SRSrawdocxNew2.docx` (SRS, D.K.T.E. CSE 2025-26). The extension modules from the "advanced" brief (camera counting, occupancy and seats, live forecast) go **beyond the SRS** and are recorded separately in Part B.

**Last verified:** 2026-10-24 (latest: "Kolhapur localisation, Google Maps, tracking, counting — implementation and acceptance" below), against the running system:
- frontend `:5173`, backend `:8080`, ML service `:8001`;
- vision-service replaying `vtest.avi`;
- MySQL 8 database `transport_db`.

Status values:
- **Done** — implemented, and the verification listed was executed and passed.
- **Done (not verified)** — implemented, but could not be executed here; the reason is given.
- **Blocked** — needs something only you can provide (a key or hardware).
- **Not done** — out of reach here.

## What is genuinely working, simulated, estimated, predicted or blocked

| Item | Nature |
|---|---|
| Transport data | **Kolhapur academic demo network**: 8 fictional routes (D1–D8) between real Kolhapur landmarks (OpenStreetMap), with OSRM road geometry. Not official KMT routes, numbers or timetables |
| Bus positions | **Simulated GPS** moving along the routes' road geometry, labelled "Simulated" everywhere. The real-GPS API (`POST /api/location/update`) is implemented and tested with API calls only; no GPS device was available |
| Passenger counts | **Estimated** by a real YOLO11n + ByteTrack model on a **recorded test video** ("Test video") or the **laptop webcam** ("Laptop webcam demo"); **simulated** ("Simulated") for buses without a sensor. No camera installed in a bus has been tested |
| Seats available | **Unknown** unless a seat sensor or a manual head count reports occupied seats. Never inferred from person detection |
| Crowd level, time and route | **Predicted** by a trained model (HistGradientBoosting) on **synthetic** data |
| Crowd level and passengers in 15 minutes | **Predicted** from live occupancy by a second trained model on **synthetic** data |
| Google Maps | Implemented as a map provider and tested against a stub of the Google API; **BLOCKED** with a real key (none available). OpenStreetMap is the verified default |

## Kolhapur localisation, Google Maps, tracking, counting — implementation and acceptance (2026-10-24)

### Baseline audit (before changes)

| Area | Found | State |
|---|---|---|
| Demo data | 8 routes, 45 stops, 10 buses on **New York** coordinates (`DataInitializer`), plus 2 webcam demo buses created earlier; ~91,000 simulated GPS points in New York | had to be localised |
| Database | MySQL `transport_db` (name kept); users 4 (incl. `shubham@gmail.com` and a passenger registered at 03:45), sensors 4, predictions 1,794, observations ~1,000 | preserved |
| Map | Leaflet/OSM default, Google provider behind `VITE_MAP_PROVIDER`; centre on New York; routes drawn as **straight lines between stops** | had to be fixed |
| Tracking | simulator every 5 s → MySQL → `/topic/location` → markers; JWT on REST and WebSocket; real-GPS endpoint admin-only | working, but along straight lines |
| Prediction | ML service (HistGradientBoosting, synthetic data), stored history, out-of-range guard | working |
| Counting | `run_counter.py` (YOLO11n + ByteTrack), agent start/stop, local OpenCV window only | no browser view, no reset |
| Seats | only from a seat sensor or manual count | correct |
| Health endpoints | ML and agent yes; backend only implicitly (`/v3/api-docs`) | backend health missing |

Backup before any change: `db-backups/transport_db_before_kolhapur_20261024.sql` (8.2 MB).

### Changes (checklist)

- [x] **Verified Kolhapur dataset** (`scripts/build_kolhapur_network.py` → `backend/src/main/resources/demo/kolhapur-network.json`):
  - 31 landmarks geocoded with Nominatim (wrong hits rejected);
  - stops snapped to roads with OSRM (1–113 m; airport 560 m, named "Kolhapur Airport (Airport Road)");
  - OSRM road geometry and road distance for 8 fictional demo routes.
- [x] **Schema** (nullable columns, safe): `routes.route_code`, `demo_route`, `path_geometry` (MEDIUMTEXT, widened by `SchemaMigrations`), `path_source`.
- [x] **One-time, in-place conversion** of the New York demo network:
  - ids kept, so sensors and history stay linked;
  - only the NYC GPS points of the moved demo buses were removed (as you chose: "Convert in place");
  - idempotent, with no duplicates on later starts.
- [x] **Fresh-install seeding** of the Kolhapur network, and **safe demo reset** (`scripts/reset-demo.ps1`).
- [x] **Maps:**
  - Kolhapur default centre;
  - road-geometry route lines on both providers (straight lines are drawn dashed and labelled);
  - always-visible "Map: …" chip;
  - Google map cleanup on unmount and retry after a failed script load.
- [x] **Simulator** moves buses along the road geometry; stops are placed along it; the travel planner uses road distance ("Road distance · OpenStreetMap").
- [x] **Simulated passenger counts** for buses without a sensor: labelled "Simulated", within 0..capacity, no confidence, no seats, never over real data.
- [x] **Passenger-counting live view** in the web app:
  - annotated picture, held in memory only;
  - people visible now / crossings this session / estimated onboard;
  - **Reset session counts**.
- [x] **Public backend health** `GET /api/health`.
- [x] **ML:** `evaluate_real.py` for future real labelled data; the existing model is kept.
- [x] Test fixtures moved from Pune to Kolhapur coordinates.

### Defects found and fixed in this round

| # | Defect | Fix | Retest |
|---|---|---|---|
| 1 | First migration attempt failed: Hibernate created `path_geometry` as TINYTEXT (255 bytes) → "Data too long"; the whole conversion **rolled back**, no data changed (verified: NYC routes and 45 stops still present) | `@Column(columnDefinition="MEDIUMTEXT")` + idempotent `ALTER` in `SchemaMigrations` | column `mediumtext`; conversion log "Converted 8 legacy New York demo routes … removed 91178 … GPS points outside Kolhapur" |
| 2 | Parsing road geometry on every request (per bus in `/buses`, per route in travel search) cut throughput under 50 users from 62 to 19 req/s | immutable `RoutePath` cached per route (key includes geometry and stops) | 62 req/s again |
| 3 | `/travel/search` (13 req/s) and `/crowd/forecast` (20 req/s) made 1–2 HTTP calls to the Python service per request | cache of model outputs for read-only views (exact outputs for identical inputs, 5 min), health check reused 5 s; stored predictions still always call the model (`ModelClientCacheTest`) | 50 users: **81–213 req/s, p95 0.44–1.18 s, 0 errors** |
| 4 | Live view logged red console errors (503) while the detector was loading | agent/backend answer "STARTING" / 204 No Content during start-up | `counter-control-check` 0 console errors |
| 5 | Test defects: smoke test used New York stop names; stub check hard-coded 10 buses; crossing wait was time-based | Kolhapur names; bus count from API; condition-based wait | 18/18, 12/12, 15/15 |

### SQL evidence (actual results, MySQL `transport_db`)

```sql
SELECT id, route_code, source, destination, distance AS km, path_source, JSON_LENGTH(path_geometry) AS geometry_points FROM routes ORDER BY id;
```
| id | code | source | destination | km | geometry points |
|---|---|---|---|---|---|
| 1 | D1 | Kolhapur Central Bus Stand (CBS) | Shivaji University | 5.6 | 259 |
| 2 | D2 | Kolhapur Central Bus Stand (CBS) | Rankala Lake (Shalini Palace) | 6.3 | 336 |
| 3 | D3 | Kolhapur Railway Station | Kolhapur Airport (Airport Road) | 14.8 | 573 |
| 4 | D4 | Kasaba Bawada | Mahalaxmi Temple | 8.4 | 506 |
| 5 | D5 | Tarabai Park | Kalamba | 9.3 | 382 |
| 6 | D6 | Phulewadi | Kolhapur Central Bus Stand (CBS) | 7.7 | 368 |
| 7 | D7 | Shahu Market Yard | Gokul Shirgaon MIDC | 13.4 | 315 |
| 8 | D8 | Sane Guruji Vasahat | Kolhapur Railway Station | 7.2 | 323 |

All rows: `path_source` = "OSRM / OpenStreetMap road network", `demo_route` = 1.

```sql
SELECT r.route_code, GROUP_CONCAT(rs.name ORDER BY rs.stop_order SEPARATOR ' > ') FROM routes r JOIN route_stops rs ON rs.route_id = r.id GROUP BY r.id;
```
- **D1:** CBS > Rajarampuri > Rajaram College > Shivaji University
- **D2:** CBS > Kolhapur Railway Station > Dasara Chowk > Bindu Chowk > Mahalaxmi Temple > Shivaji Peth > Rankala Lake (Shalini Palace)
- **D3:** Kolhapur Railway Station > CBS > Rajarampuri > Uchgaon > Ujalaiwadi > Kolhapur Airport (Airport Road)
- **D4:** Kasaba Bawada > Line Bazar > New Palace > Nagala Park > Town Hall > Mahalaxmi Temple
- **D5:** Tarabai Park > CBS > Shahupuri > Udyam Nagar > Sambhaji Nagar > Kalamba
- **D6:** Phulewadi > Rankala Lake (Shalini Palace) > Shivaji Chowk > CPR Hospital > CBS
- **D7:** Shahu Market Yard > Uchgaon > Ujalaiwadi > Gokul Shirgaon MIDC
- **D8:** Sane Guruji Vasahat > Sambhaji Nagar > Rajarshi Shahu Stadium > Laxmipuri > Kolhapur Railway Station

```sql
SELECT b.bus_number, b.capacity, b.seat_count, b.status, r.route_code FROM buses b LEFT JOIN routes r ON r.id = b.route_id ORDER BY b.id;
```
Result, after `reset-demo.ps1`:

| Bus | Capacity | Seats | Status | Route |
|---|---|---|---|---|
| BUS-101 | 50 | 40 | ACTIVE | D1 |
| BUS-102 | 50 | 40 | ACTIVE | D2 |
| BUS-103 | 60 | 44 | ACTIVE | D3 |
| BUS-104 | 40 | 32 | ACTIVE | D4 |
| BUS-105 | 50 | 40 | ACTIVE | D5 |
| BUS-106 | 40 | 32 | ACTIVE | D6 |
| BUS-107 | 60 | 44 | ACTIVE | D7 |
| BUS-108 | 40 | 32 | ACTIVE | D8 |
| BUS-109 | 50 | 40 | ACTIVE | D1 |
| BUS-110 | 50 | 40 | MAINTENANCE | D2 |
| DEMO-WEBCAM | 4 | 2 | ACTIVE | none (parked at CBS) |
| DEMO-WEBDOOR | 10 | – | ACTIVE | none (parked at CBS) |

Integrity checks (all 0 unless noted):
- stops outside Kolhapur (16.55–16.85 N, 74.10–74.40 E): 0;
- buses outside Kolhapur: 0;
- location points outside Kolhapur: 0;
- orphan stops: 0;
- buses pointing to a missing route: 0;
- duplicate routes or bus numbers: 0.

Preserved: users 4 of 4, sensors 4, predictions (1,794 before; more since), observations kept.

Fresh install (scratch database `transport_db_seedtest`, dropped afterwards): first start seeded 2 users, 8 routes, 43 stops and 10 buses; the second start left these counts unchanged.

### Test results of this round

| Area | Status | Evidence |
|---|---|---|
| Startup with documented commands | PASS | `start-demo.ps1`; `/api/health` → database, mlService, counterAgent UP, trackingMode SIMULATED_GPS; ML and agent `/health` UP |
| Kolhapur data, coordinates, relationships | PASS | SQL above; `KolhapurNetworkTest` (every stop within 30 m of its road geometry, stops in order, distance = geometry ±0.3 km) |
| Repeated seeding, no duplicates | PASS | second start + fresh-install test |
| OSM map, markers, road lines | PASS | screenshots: Kolhapur, 12 markers, D2 along Station Road → Mahalaxmi → Rankala; 0 dashed lines; 0 console errors |
| Google Maps with a valid key | **BLOCKED** | no key. Stub check **12/12**: markers moved in place, 8 polylines with the OSRM point counts 259–573, no request to Google. Missing-key fallback: notice + OSM, 12 markers |
| Simulated live tracking | PASS | `kolhapur-tracking-check` **11/11**: all positions SIMULATED and in Kolhapur, max offset from road geometry **0.00 m**, largest step 46–50 m, median interval 5.06–5.08 s, 2 clients **50/50 identical** |
| Reconnect after backend crash | PASS | supervisor restart; UI "Reconnecting…" → "Live updates", 0 reloads, 0 errors |
| Real GPS | **BLOCKED** | no device; endpoint admin-only and validated (tests) |
| Real model inference, history, outage | PASS | prediction 2249 (BUS-103 / D3) = direct `/predict` output (MEDIUM 0.9192, same probabilities and version); ML down → **503** with instructions, supervisor restart ~5 s |
| Prediction accuracy on real data | **BLOCKED** | synthetic data only; `evaluate_real.py` + template ready |
| Test-video counting vs ground truth | PASS | door crossings precision **1.00**, recall **0.92** (24/26, 0 false, 0 wrong direction); detection MAE **0.20** people/frame |
| Counter start/stop, live view, reset (test video) | PASS | `counter-control-check` **15/15** |
| Webcam via live view (consented, 45 s) | PASS | `webcam-control-check` **10/10**: consent required, picture shown in the page, 1 person visible, WEBCAM_DEMO label, seats unknown, clean stop. **Open observation:** only 1.8 frames/s during this run. The live view costs ~2 % (250 vs 256 frames per 20 s on video), so the camera itself (e.g. low light) is the likely cause; not verified |
| Webcam door counting | **FAIL** (laptop geometry) | earlier: recall 0–0.5; needs a door-mounted camera |
| Seat availability | PASS (capacity estimate only) | seats only from sensor/manual; UI "Passenger places left (estimate)" vs "Seats available: Unknown" |
| Security | PASS | `acceptance-api-check`: 401 without login, 403 for passengers, tampered JWT 401, injection rejected, CORS foreign origin refused, WebSocket without token refused |
| Secrets in source control | PASS (no repo) | project is not a git repository; `.gitignore` covers `.env`, `application-local.properties`, sensor keys, agent token, backups, outputs; DB password and JWT secret found in 0 other files; 0 Google-style keys |
| Backup and restore | PASS | restored into scratch DB: users 4/4, buses 12/12, routes 8/8, stops 43/43, sensors 4/4, predictions, observations, occupancy, models all equal |
| Performance (50 users) | PASS | `acceptance-api-check` **25/25** |
| Mobile 390 px, browser workflows | PASS | `ui-smoke` **18/18** (Kolhapur travel search with "Road distance · OpenStreetMap"), 0 console errors |
| Automated suites | PASS | backend **51/51**, ML **20/20**, vision **22/22**, ESLint OK, production build OK |

### What you must still supply

1. **Google Maps:** a billing-enabled project with the Maps JavaScript API and a referrer-restricted key in `.env`. Then check "Map: Google Maps" on Live Tracking and rerun `node e2e/ui-smoke.mjs`.
2. **Optional:** a Routes API server key (`GOOGLE_ROUTES_API_KEY`).
3. **Hardware:** a GPS device or feed, and a door-mounted bus camera.
4. **Real labelled counts** for `evaluate_real.py`.
5. **Deployment:** a host with TLS, and a Windows service for start at boot.

## Final acceptance test 2026-10-24 (against the SRS)

The whole system was started with `scripts\start-demo.ps1` only (the documented demo command) and tested end to end.

### Defects found and fixed during acceptance

| # | Defect | Severity | Fix | Retest |
|---|---|---|---|---|
| 1 | **SRS 3.6.2 not met:** no automatic restart and no backup mechanism | Critical (SRS requirement) | `scripts/start-demo.ps1` runs every service under a restart loop; `scripts/backup-db.ps1` backs up (consistent snapshot, rotation) at every start; `scripts/stop-demo.ps1` | Backend killed twice → serving again in ~15 s and ~17 s; the UI went "Reconnecting…" → "Live updates" with **0 page reloads**. ML killed → **503** with a clear message, back in ~10 s. Backup restored into a scratch database: **all 9 tables match** (users 3/3, buses 12/12, routes 8/8, stops 45/45, predictions 1194/1194, observations 1014/1014 …); the scratch DB was then dropped |
| 2 | Slow "latest prediction per bus" query (correlated subquery, ~130 ms, growing with history), used by `/buses`, `/dashboard`, `/travel/search`. Under 50 users p95 was 2.94 s, at the SRS limit | Major (performance) | grouped `IN (bus, max(timestamp))` subquery using the `(bus_id, timestamp)` index | MySQL `EXPLAIN ANALYZE` **141 ms → 10 ms**, same 10 rows. 50 users: **32 → 60 req/s**, median **1465 → 741 ms**, p95 **2943 → 1817 ms**. New test `busListShowsTheNewestPredictionOfEachBus` |
| 3 | First page load on a freshly started dev server took more than 15 s (on-the-fly compilation) | Major for a live demo | the demo serves the production build (`vite build` + `vite preview`); `-Dev` keeps the dev server | First load of the login page **200 ms**; login → dashboard 774 ms; tracking with markers 959 ms |
| 4 | Test defects, not product defects: the smoke test assumed a counter was already running; `browser.close()` hung once for ~9 min; the acceptance script expected 200 instead of 201 Created, and 401 instead of the 400 validation answer for the injection attempt | — | the smoke test now starts and stops its own test-video counter; close is time-limited; expectations corrected | `ui-smoke` 18/18 in 35 s; API acceptance 25/25 |

### Acceptance checklist

| Requirement (SRS section) | Result | Evidence (this session) |
|---|---|---|
| FR1 register / log in securely | **PASS** | ui-smoke: empty-field validation, wrong password rejected, registration stored in MySQL, duplicate → clear error; API: wrong password 401, invalid registration 400; 4/4 passwords BCrypt-hashed |
| FR2 bus locations on a map | **PASS** (OpenStreetMap) | ui-smoke tracking step; 12 markers for 12 buses |
| FR2 Google Maps | **BLOCKED** (no API key) | Missing-key fallback and stubbed Google provider 11/11 verified earlier today; a valid key was never available |
| FR3 simulated movement, ~5 s | **PASS** | 20 WebSocket clients: median gap **5.09 s** over 40 intervals; every message `source = SIMULATED` |
| FR4 real-time WebSocket updates | **PASS** | 20/20 simultaneous clients received positions; tokenless connection refused; reconnect after the backend restart without a reload |
| FR5 live tracking information | **PASS** | ui-smoke: markers move without reload, bus details, occupancy panel (test video, seats unknown) |
| FR6 ML crowd prediction | **PASS** (model trained on synthetic data) | Prediction id 1354 = direct model output (MEDIUM 0.7409; LOW 0.0066 / HIGH 0.2524; same model version); 69 ms; 20 simultaneous predictions all 201; ML down → 503, never an invented value |
| FR7 display predictions | **PASS** | ui-smoke: prediction shown and stored, prediction history paged |
| FR8 store bus / route / crowd data | **PASS** | 9 foreign keys, unique keys on bus number / email / sensor; data persists across restarts; backup restore matches |
| FR9 admin manages buses | **PASS** | ui-smoke create → refresh → delete; capacity −5 → 400, seats > capacity → 400, duplicate BUS-101 → 409 |
| FR10 admin manages routes | **PASS** | ui-smoke route created by clicking stops on the map, then deleted |
| FR11 admin views system data | **PASS** | ui-smoke analytics, users, history, occupancy and sensors pages; passenger redirected from admin pages and 403 on admin APIs |
| FR12 travel information from crowd data | **PASS** | ui-smoke travel planner: route, ETA, crowd and places left |
| Static: multiple simultaneous users | **PASS** | 50 users × 30 s: 1,793 requests, **0 errors** |
| Static: desktop browser, efficient storage, simulated data | **PASS** | Chrome on Windows 11; 87,678 location rows with responses under 100 ms |
| Dynamic: response within 2–3 s | **PASS** | Single user: every page API p95 ≤ 265 ms (first forecast call after a restart 1.9 s). 50 users with no think time (heavier than "normal"): p95 1.82 s; slowest single request 4.1 s |
| Dynamic: quick prediction, admin operations without delay | **PASS** | prediction 69 ms; analytics 66 ms; CRUD in ui-smoke |
| Design constraints (React, Spring Boot, MySQL, Python ML, Windows, simulated GPS) | **PASS** | the running system |
| Database: types, integrity, validation, retention | **PASS** | FKs and unique keys listed above; validation tests; location history kept 24 h (oldest row 18 h old), crowd history kept for analysis |
| 3.6.1 Reliability (error handling, consistent predictions) | **PASS** | 404 / 400 / 503 JSON errors with no crash; the same input gives the same prediction; ML outage recovered automatically |
| 3.6.2 Availability (backups, automatic restart) | **PASS (basic, demo level)** | defect 1 above. Limits: the supervisor is a console window, not a Windows service, so it does not survive a reboot; scheduled daily backups are optional (`schtasks` command in the README) |
| 3.6.3 Security: login required, hashed passwords, roles, no unauthorised changes | **PASS** | 401 without login, 403 for passengers on admin data / bus creation / route deletion, tampered JWT 401, SQL-injection attempt rejected (400, no token), foreign CORS origin refused |
| 3.6.3 "safe communication between frontend and backend" (HTTPS) | **BLOCKED** | needs a deployed host and a TLS certificate. On the localhost demo the traffic never leaves the computer; every request carries a JWT |
| 3.6.4 Maintainability | **PASS** | separate frontend / backend / ML / vision modules; 43 documented API operations (Swagger); 42 + 17 + 17 automated tests |
| 3.6.5 Portability (phones, tablets, desktops; easy deployment) | **PASS** | 390 px mobile layout in ui-smoke (emulated, no real phone tested); one-command start. The SRS deployment diagram's Nginx server is not used in the demo set-up |
| Extension: camera counting on the test video | **PASS** | door crossings precision **1.00**, recall **0.92** (24 of 26); detection MAE **0.20** people/frame; start/stop from the web app 10/10, reports in MySQL |
| Extension: laptop webcam, cabin mode, through the web app | **PASS** (laptop demonstration, with consent) | `webcam-control-check` 7/7: consent required, reports labelled `WEBCAM_DEMO`, rows #28–#36 in MySQL, counts 1 (×7) then 2, seats kept unknown, clean stop, nothing saved |
| Extension: webcam door counting | **FAIL** (laptop geometry) | measured earlier: recall 0–0.5; needs a camera mounted above the door |
| Extension: real bus camera, real GPS, Google Routes | **BLOCKED** | no hardware or key |

**Overall:** every SRS functional requirement passes. The only SRS item not met is HTTPS, which is **BLOCKED** because it needs a deployment host; Google Maps with a real key is also **BLOCKED**. Webcam door counting fails on a laptop; it is an extension, not an SRS requirement.

## Verification session 2026-10-24 (counter start/stop from the web app, end-to-end re-verification)

The brief asked for Google Maps verification, a passenger counter that starts and stops reliably (webcam or test video), and proof that counts reach Spring Boot, MySQL and the UI. It also asked for re-checks of occupancy, ML, auth/CRUD/WebSocket, bug fixes, and evidence.

**New: start/stop from the web app.**
- `vision-service/agent.py` is a local counter agent on 127.0.0.1:8002. It requires a token and starts `run_counter.py` with `--stop-file`, giving a graceful stop that sends the final report and releases the camera, with a forced stop after 15 s.
- The backend gains `GET /api/admin/counters`, `POST /api/admin/counters/start` and `POST /api/admin/counters/{sensor}/stop` (ADMIN only; `VisionAgentClient`).
- The admin page gains a **Counter control** card: state, input, last log line, Start/Stop, and a consent checkbox for cameras.
- `run_counter.py` now also stops on Ctrl+Break (SIGBREAK).

| Item | Test | Actual result |
|---|---|---|
| Start/stop with a test video **through the UI** | `node e2e/counter-control-check.mjs CAM-BUS101-DOOR 45` | **10/10**: Start → RUNNING; sensor Offline → **Online** after ~12 s (YOLO load); reports **#827 → #834** arrived on the page without reload; Stop → **STOPPED in 2.8 s**; log "Stopped after 350 frames; 8 reports sent, 0 still queued"; exit code 0; 0 console errors |
| Counts persisted in MySQL | `passenger_count_observations` for CAM-BUS101-DOOR, sequence ≥ 827 | Rows **#828–#835** stored, all `TEST_VIDEO`, `APPLIED`, health OK. The onboard chain is continuous (11 → 12 → 11 → 10 → 8 → 9); #835 is the final report sent on Stop. `bus_occupancy` BUS-101 = **9**, the same as the UI |
| Start/stop of a cabin counter through the backend API | admin `curl` to `/api/admin/counters` | Started; **duplicate start → 409**; **webcam without consent → 400** "starting a camera requires confirmConsent = true"; BUS-102 went LIVE with `lastVisibleCount` 4→8; Stop → exit code 0, "6 reports sent, 0 still queued" |
| Agent not running | agent stopped, `GET /api/admin/counters` | **503** "Counter agent is not running on this computer (start it with: python vision-service/agent.py)"; the page shows this as an info notice |
| No leftovers after stop | process list, `output/` | no `run_counter.py` process and no `stop_*` file left |
| Laptop webcam through the new controls | — | **Not run in this session**: no fresh consent was given for a new camera run. The consent rule is enforced by the UI, the backend and the agent (all tested). Earlier consented webcam runs, using the same `run_counter.py`, are recorded below |

**Defect found and fixed: Start from the web app did nothing.**
- **Symptom:** the first UI run timed out; the agent never logged the POST.
- **Cause:** Spring's `RestClient` (`SimpleClientHttpRequestFactory`) sent the JSON body **chunked, without `Content-Length`**, and the Python agent read an empty body.
- **Fix, in both places:**
  - The backend now buffers the request (`BufferingClientHttpRequestFactory`), so it sends a `Content-Length`.
  - The agent also decodes chunked bodies.
  - `CounterControlTest` now asserts that a `Content-Length` is sent.
- **Why the first test missed it:** the JDK fake server decodes chunked bodies transparently.

**Re-verification of the other priorities (this session)**

| Priority | Evidence |
|---|---|
| 1. Google Maps / live markers | No key is configured (`.env` has neither `VITE_MAP_PROVIDER=google` nor a key), so **OpenStreetMap is the working map**. `ui-smoke` 18/18 includes live markers. **Missing-key fallback:** a dev server with `VITE_MAP_PROVIDER=google` and an empty key showed "Google Maps selected but VITE_GOOGLE_MAPS_API_KEY is not set - showing OpenStreetMap" and 12 bus markers. **Google provider code (stubbed API, Google never contacted): 11/11**: 8 route lines, 45 stops, 12 markers for 12 buses, 216 live `setPosition` updates without new markers, click opens the bus, title with occupancy. A **valid key is still unverified**. *Test defect fixed:* the stub check hard-coded 10 buses; it now compares with the bus count from the API (there are 12 since the two demo webcam buses were added) |
| 3. Capacity / occupancy | API values kept separate. **BUS-102 (cabin camera):** `lastVisibleCount` 8, `estimatedOnboard` 8 flagged **lower bound**, seats **UNKNOWN** (no seat count configured). **BUS-101:** a manual count of 20 (not a lower bound) and 40 seats configured; the earlier seat count expired (older than 300 s), so seats show **UNKNOWN** rather than an old number. Places left = capacity − onboard (50 − 20 = 30; 50 − 8 = 42). The BUS-101 value of 20 comes from the `ui-smoke` reconciliation step ("E2E test head count", 9 → 20, stored as `MANUAL_RECONCILIATION`) |
| 4. ML prediction | Backend `POST /api/crowd/predict` (BUS-101, Mon 08:30) returned **MEDIUM 0.7868**, saved as `crowd_predictions` id 1204 (TIME_ROUTE, probabilities 0.0069 / 0.7868 / 0.2063). A direct call to the ML service `/predict` with the same features (hour 8, Monday, 12.5 km, 6 stops, capacity 50) returned **the identical output**, same model version `hist_gradient_boosting-20261023072647`, `out_of_training_range` empty |
| 5. Auth, CRUD, persistence, WebSocket, workflows | backend **41/41** (incl. auth/roles, bus and route CRUD, occupancy reconciliation, counter control); `ui-smoke` **18/18** (login, admin CRUD create → refresh → delete, tracking, prediction, travel, occupancy pages; 0 console errors); `verify:ws` **passed** (live message received; connection without token refused) |

**Regression after these changes:** backend **41/41**, ML **17/17**, vision **17/17**, lint and production build OK, `ui-smoke` 18/18, `counter-control-check` 10/10, Google stub 11/11, missing-key fallback OK, WebSocket passed.

**Note on how the services are running now:**
- The backend was restarted with `CORS_ALLOWED_ORIGINS` that also allow `http://localhost:5174`, for the map-provider checks.
- A normal restart (`mvn spring-boot:run`) returns to the default origins.

## Final verification session 2026-10-24 (handover)

| Brief item still open before this session | What was done | Actual result |
|---|---|---|
| ETA and route distance "using an appropriate routing API where available" | `GoogleRoutesClient` (Routes API `computeRoutes`, server-side key `GOOGLE_ROUTES_API_KEY`, field mask, DRIVE mode, 10-min cache) wired into the travel planner; each option carries `routingSource`; the UI badge shows the source | `RoutingIntegrationTest` against a local fake Routes server checks the request (key header, field mask, origin/intermediate/destination in travel order), the result (4.3 km, 12 min), the cache (no second call) and the fallback on 403. Live without a key: `STRAIGHT_LINE_ESTIMATE` (7.9 km, 20 min). **Not verified against Google** (no key) |
| Stale GPS warnings | `node e2e/stale-gps-check.mjs` while the backend was down 45 s | ~25 s after the last update **all markers faded** and the selected bus showed "**more than 30 seconds old**"; after the restart they recovered. The 2 demo webcam buses (no route) stay faded because they never move, which is correct |
| Webcam vs. a manual ground-truth count | 2 consented webcam door runs, ground truth from the person filmed, `--debug-tracks` numbers-only log, offline replay | Cabin: 1 person counted correctly. **Door: 0 of ≈10, then 3 of 6 crossings.** Cause measured: person too close (boxes 58–75 % of the frame, feet cut off) plus ID switches. Stitching/dead-band tweaks did not help offline, so they were not kept. Added a **"too close to the camera" DEGRADED warning** (fires on the webcam log, not on the test video) |

**Defect found and fixed during deployment:** rebuilding the jar while the backend was running replaced it with a 324 KB "thin" jar. The running backend then failed on login, when it lazily loaded a class. Lesson, now in the README: always **stop the backend before `mvn package`**.

**Regression after these changes:** backend **38/38**, ML **17/17**, vision **13/13**, lint and build OK, browser E2E **18/18** (0 console errors), WebSocket passed, Swagger 40 operations. Test-video crossing validation unchanged (precision 1.00, recall 0.92).

### Final SRS / brief checklist (status as verified)

| Area | Status | Evidence |
|---|---|---|
| Auth, JWT, roles, registration | **Done** | backend tests; E2E |
| Bus and route CRUD, persistence after refresh/restart | **Done** | E2E create → refresh → delete; MySQL checks; services restarted many times |
| Map with live buses, routes, stops, selection, filters, stale warnings | **Done** (OpenStreetMap) | E2E; stale-GPS check |
| Google Maps JS provider | **Done, valid key unverified** | stubbed-API check 11/11; missing/invalid key fallbacks verified |
| Road ETA / distance (Google Routes) | **Done, valid key unverified** | fake-server test; live fallback |
| WebSocket delivery and reconnection | **Done** | `verify:ws`; reconnect check |
| Real GPS device | **Blocked (no device)** | API accepts GPS fixes (tested by API); simulated GPS labelled |
| Person detection + tracking (test video) | **Done** | MAE 0.20/frame; door crossings precision 1.00 / recall 0.92 |
| Webcam input, consent, start/stop, health, persistence, UI | **Done** | webcam runs; counts in MySQL and UI; labelled "Laptop webcam demo" |
| Start/stop counters from the web app (agent) | **Done (test video verified; webcam via the web app not run)** | `counter-control-check` 10/10; MySQL rows #828–#835; 409 duplicate, 400 no consent, 503 agent down; `CounterControlTest` |
| Webcam door counting accuracy | **Fails on a laptop setup** (recall 0–0.5) | measured; cause documented; warning added |
| Real bus camera | **Blocked (no hardware)** | — |
| Capacity, seats, standing, occupancy %, LOW/MEDIUM/HIGH/FULL, freshness, confidence | **Done** | tests; UI checks with measured seats (2/40) and unknown seats |
| Seat-level detection from cameras | **Not done (justified)** | seats only from a seat sensor or manual count; otherwise "Unknown" |
| Entry/exit reconciliation (duplicates, gaps, late, negative, manual) | **Done** | `OccupancyTest`; observation log |
| AI crowd prediction (time-route + 15-min live forecast) | **Done (synthetic data)** | test metrics; backend output = model output; out-of-training-range guard |
| Failure handling (ML down, camera missing or black, backend down, DB down, invalid input) | **Done** | failure checks recorded in the sessions below |
| Auto-restart / scheduled backups (SRS availability) | **Not done** | needs a deployment environment |

### Next tasks
1. **You:** a browser key (`VITE_GOOGLE_MAPS_API_KEY`, Maps JavaScript API) and optionally a server key (`GOOGLE_ROUTES_API_KEY`, Routes API). Then re-run the E2E suite and a travel search to confirm "Road · Google Routes".
2. **You:** a door camera mounted above a doorway (or a consented top-down doorway recording). Then `validate_crossings.py` / `replay_tracks.py` against a hand count.
3. **You:** a GPS device or feed posting to `POST /api/location/update`.
4. Deployment hardening: auto-restart service, scheduled `mysqldump`, HTTPS.
5. **You (optional):** with everyone in view consenting, start `CAM-WEBCAM-CABIN` on "Camera 0 (laptop webcam)" from **Counter control**, to confirm the webcam path through the web app (the agent opens the preview window on this computer).
6. Remote bus PCs: the counter agent only controls counters on the computer it runs on. Reaching an agent on a bus needs a secure network path (VPN) and has not been built or tested.
7. Known model weakness: the 15-minute forecast's HIGH class is weak (see the ML README), and both models are trained on synthetic data. Retrain them on recorded real counts once these exist.

## Verification session 2026-10-24 (laptop webcam demonstration)

The webcam was used **only after your explicit confirmation**, for one 2-minute run in cabin mode. Door mode on the webcam was **not** run (you chose cabin only). No frame was saved or uploaded.

| What | How | Actual result |
|---|---|---|
| Webcam input in the existing counter (no new service) | `run_counter.py --source <index>` (`cv2.VideoCapture(index, CAP_DSHOW)`, 640x480), configurable index | **Works** on the built-in "HP Wide Vision HD Camera" (index 0) |
| Consent before opening the camera | `--confirm-consent` required; `tests/test_camera_safety.py` replaces `VideoCapture` so any opening fails the test | without the flag the program only explains and exits; test passes (the camera is never opened) |
| Explicit start/stop | start = the command; stop = Q/Esc in the preview window, or Ctrl+C | run stopped normally; camera released; sensor shown **Offline** with last report #27 |
| Live detection on webcam frames | YOLO11n + ByteTrack on 1,439 frames in 120 s | **~12 frames/s on the laptop CPU** |
| Counts reach backend, database and frontend | 24 reports, all stored as `WEBCAM_DEMO`; browser watched DEMO-WEBCAM with `e2e/occupancy-live-check.mjs` | the browser updated live with **0 reloads**: 0, then 1 visible person |
| Utilisation and crowd level from real inference | DEMO-WEBCAM (capacity 4, seats 2) | **≥ 1 of 4 = 25 % → LOW**, 3 places left, seats "Unknown (2 seats; no seat sensor)", confidence 74 % |
| Labels | Live Tracking panel and admin page | passenger count labelled **"Laptop webcam demo"**; GPS position separately labelled **"Simulated"**; sensor description "LAPTOP WEBCAM demonstration - not a bus camera" |
| Webcam unavailable | `--source 5` (no such camera; the real webcam was not used) | clear error ("in use by another application ... or blocked in Windows Settings > Privacy & security > Camera"); `CAMERA_UNAVAILABLE` shown on the admin page |

**What the run showed:**
- For the first ~80 s every frame was **completely black** (frame quality 0.00): privacy shutter closed, lens covered or no light. Those reports were marked DEGRADED.
- After that, quality was 0.54–0.74, and **one person was detected in every report** (confidence 0.69–0.90).
- No ground-truth count was taken during the webcam run, so **webcam accuracy is not measured**. The measured accuracy figures remain those of the test video (MAE 0.20 per frame; door crossings precision 1.00 / recall 0.92).

**Bugs found by the webcam test and fixed:**
1. **A black picture was reported as "0 people visible"**, making the demo bus look live and empty. It is now reported as `CAMERA_UNAVAILABLE` with **no count**, including in the final report on stop. Verified with a generated black video and a unit test.
2. **Predictions outside the training data were shown as if valid.** For the 4-passenger demo bus the live forecast said "~4 onboard · Low · 100 %", which is self-contradictory. Both models were trained on capacities 30–70 / 40–70. Now:
   - the ML service flags such inputs (`out_of_training_range`);
   - the backend refuses on-demand predictions for them (400 with an explanation) and skips scheduled predictions and live forecasts;
   - the travel planner shows a note.
   - Covered by an ML test and a backend test. The 10 extrapolated rows stored for the demo buses were deleted.
3. My own setup had put the demo buses on route 3, which lowered that route's average capacity (50 → 21) used by its best-time forecast. The demo buses were moved off the route; route 3 uses 50 again.

**Data changes:**
- Created buses DEMO-WEBCAM (id 21) and DEMO-WEBDOOR (id 22), both without a route.
- Registered sensors CAM-WEBCAM-CABIN and CAM-WEBCAM-DOOR (keys in the git-ignored `sensor_keys.json`).

**Regression after the changes:** backend 37/37, ML 17/17, vision 12/12, lint and build OK, browser 18/18 (0 console errors), WebSocket passed.

## Verification session 2026-10-24 (second session)

| Task | How it was verified | Actual result |
|---|---|---|
| **Door-crossing counts vs. a manual count** (previously unvalidated) | `python validate_crossings.py`: every counted event checked on before/after crops of the tracked person; missed crossings searched with a slit-scan of the line plus frame strips; ground truth in `test_videos/vtest_crossings_ground_truth.json`; `--score` | **24 counted, 24 correct (precision 1.00)**, 0 false counts, 0 wrong directions. **26 real crossings** (1 definite + 1 probable miss, both right-to-left), so **recall 0.92** (0.96 without the probable miss). L→R 11 counted / 11 real; R→L 13 counted / 15 real. Correctly not counted: a woman who turned back at the line, and a group standing on the line |
| WebSocket **disconnect / reconnect** (never tested before) | `node e2e/reconnect-check.mjs 120` while the backend was stopped for 26 s and restarted | badge "Live updates" → "Reconnecting…" within ~2 s → "Live updates" ~13 s after restart; markers moved again; **0 page reloads, 0 page errors, no new login** |
| Starting a second backend by mistake | a duplicate start while one was running | refused: "Port 8080 was already in use"; the running backend was unaffected |
| **Google Maps provider code** (no key available) | `node e2e/google-provider-stub-check.mjs` with a recording stub of `google.maps` (no request reaches Google) | **11/11**: Google map used instead of Leaflet; 8 route lines, 45 stops, 10 bus markers; 180 live position updates moved existing markers; marker titles carry crowd and occupancy text; clicking a marker opens the bus |
| Full regression | all suites | backend **35/35**, ML **16/16**, vision **8/8**, lint and build OK, browser **18/18** (0 console errors), WebSocket passed |

**Bugs found and fixed in this session:**
1. **Google provider recreated every route and stop overlay on every render**, i.e. every 5-second live update: 1,000 markers after 12 s. With a real key this would cause flicker and growing CPU and memory use. Overlays are now redrawn only when their content changes. Verified: 55 markers ever created over 180 updates.
2. **Google markers lacked parity with OpenStreetMap**: no crowd/occupancy tooltip, and stale markers were not faded. Both added and verified (title text checked).

**Insight:** the downward drift of BUS-101's door total seen on 2026-10-23 is mostly real. The video contains more right-to-left crossings (15) than left-to-right (11), plus a slight under-count of exits.

**Still not verifiable here:** Google's servers, a valid key, billing, map tiles, a real bus camera, a GPS device.

## Verification session 2026-10-24 (first session)

Services were checked first (backend `:8080`, ML `:8001` with both models loaded, frontend `:5173`). Only the backend was restarted, to deploy one fix.

| What was verified | How | Actual result |
|---|---|---|
| All automated tests | `mvn test`, pytest (ML, vision), `npm run lint`, `npm run build`, `npm run test:e2e`, `npm run verify:ws` | backend **35/35**, ML **16/16**, vision **8/8**, lint 0 problems, build OK, browser **18/18** (0 console errors), WebSocket passed |
| Passenger counts are **persisted** | 70 s door-counter run on the test video, then MySQL queried | 14 new rows (sequence 512–525, no gaps); entries 7, exits 9; stored onboard 14 + 7 − 9 = **12**, as expected |
| Passenger counts **update in the browser** | `node e2e/occupancy-live-check.mjs BUS-101 55` during a counter run | 5 different onboard values over 55 s, **0 page reloads**; each value matched the counter report at the same timestamp (for example 13 → 11 → 10 at :18/:23/:28) |
| The **ML model is actually used** | backend `/api/crowd/predict` compared with a direct `POST /predict` to the ML service using the same features | Monday 08:00 → MEDIUM (0.0069/0.7868/0.2063) and 02:00 → LOW (1.0): **identical** to the model output; the stored model version matches the ML service's `/health` |
| Live 15-min forecast in use | `/api/crowd/history?busId=…` | 5 recent `LIVE_FORECAST` rows, made with model `forecast15-…-20261023141203` from the live onboard values (for example 11 now → 6.1 predicted) |
| **Measured seat availability** | admin head count of 44 onboard / 38 seats on BUS-101 (50 capacity / 40 seats); then browser check | API and UI: places left **6**, seats available **2 / 40**, standing **6**, labelled "seats counted manually". 41 seats is rejected with 400. Seats return to "Unknown" after 5 minutes without a new count |
| Google Maps configuration | `.env` inspected | **No `VITE_MAP_PROVIDER` / `VITE_GOOGLE_MAPS_API_KEY`**: the app runs on OpenStreetMap. The fallback to OSM with a notice (no key / invalid key) was verified on 2026-10-23 |
| Hardware present | `Get-PnpDevice` (device list only; no camera was opened) | Camera: only the built-in **laptop webcam** ("HP Wide Vision HD Camera"), not a bus camera, and deliberately **not used** (consent). **No GPS/GNSS receiver, location sensor or serial port** |

**Defect fixed in this session:** Live Tracking showed Direction, Next stop and Speed as "—" for up to 5 s after selecting a bus. These values only came with the next WebSocket message. `GET /api/buses` now includes them from the simulator state (`BusSimulationService.liveInfo`). This is covered by a backend test assertion and an E2E assertion (direction shown immediately). The backend was restarted to deploy it.

**Test fixes (not product defects):**
- the live-occupancy check's text selectors (Playwright regex selectors don't match text split across `<strong>`);
- the E2E seat assertion now accepts both honest states: "Unknown" when no seat data, or "N / 40" when counted.

## Verification summary

| Check | Command / method | Result |
|---|---|---|
| Backend unit and integration tests | `cd backend && mvn test` | **35 / 35 passed** (27 earlier + 8 occupancy tests) |
| ML service tests | `cd ml-service && .venv\Scripts\python -m pytest -q` | **16 / 16 passed** |
| Vision counting-logic tests | `cd vision-service && .venv\Scripts\python -m pytest -q tests` | **8 / 8 passed** |
| Person detection vs. hand counts | `python evaluate_counts.py` (10 frames counted by hand) | **MAE 0.20 people/frame**; 63 detected / 65 counted; both misses occluded |
| Frontend lint and build | `npm run lint`, `npm run build` | 0 problems; build succeeds |
| WebSocket live updates and authentication | `npm run verify:ws` | passed: 10 buses streaming; a connection without a token is rejected |
| Browser end-to-end in Chrome (real UI + backend + MySQL + ML + vision) | `npm run test:e2e` | **18 / 18 passed, 0 console errors** |
| Live vision → backend → UI | `run_counter.py` on `vtest.avi` for BUS-101 (door) and BUS-102 (cabin) | Reports applied every 5 s. BUS-101 onboard changed by line crossings (for example 12 → 10 → 9 → 12 → 13); shown on the tracking page with "Test video" and "Estimated" labels and a 15-minute forecast |
| Vision failure: missing video source | `--source no_such_video.avi` | only `CAMERA_UNAVAILABLE` health reports; sensor health updated |
| Vision failure: backend down | `--backend http://127.0.0.1:9`, then a normal restart | reports queued and saved to disk, then delivered on restart; sequences 1–8 all stored, none lost |
| Google Maps fallbacks | `e2e/map-provider-check.mjs` with no key / an invalid key | notice + OpenStreetMap with 10 buses in both cases |
| Bugs found by these checks and fixed | — | (1) The counter sent a false "OK" final report after a camera failure. (2) Unsent reports were lost when the counter exited. (3) A seat-sensor-only bus lost its seat data. (4) A failed forecast could fail a sensor report. (5) Device payload validation ran before authentication. (6) The Google invalid-key fallback did not trigger |
| ML service stopped | manual | `/api/crowd/predict` → 503 with a clear message; nothing stored; UI shows "offline"; a sensor report still succeeds (forecast skipped) |
| Wrong DB password | jar started with `DB_PASSWORD=wrong` | stops at startup with `Access denied for user 'root'` |
| Schema migration and data preservation | restart with new code (backup taken first) | `crowd_level` enum extended with FULL; new tables created; 3 original users, 10 buses and 8 routes intact |
| Swagger | `/v3/api-docs` | 40 operations documented |

## Part A — SRS requirement traceability

| ID | SRS requirement | Implementation (main files) | Status | Verification |
|---|---|---|---|---|
| FR1 | Users and administrators register and log in securely | `AuthController/AuthService`, `SecurityConfig`, JWT; `Login.jsx`, `Register.jsx` | **Done** | Tests: BCrypt hashing, 409 duplicate, 400 validation, 401 wrong password, admin registration needs a code. E2E checks |
| FR2 | Display bus locations on a map | `TransitMap` (OpenStreetMap/Leaflet default, Google optional), `BusTracking.jsx` | **Done** (OSM). Google: **Blocked** (key) | E2E: markers and routes render |
| FR3 | Simulate movement with periodic updates (~5 s) | `BusSimulationService`, `BusLocationScheduler` | **Done** | Tests; MySQL positions change every 5 s |
| FR4 | Real-time updates over WebSocket | `WebSocketConfig` + JWT interceptor, `websocketService.js` (re-subscribes after reconnect) | **Done** | `verify:ws`; E2E: 10/10 markers moved without a reload |
| FR5 | Users view live tracking information | `BusTracking.jsx`: route number, direction, next stop, speed, last update, stale/unavailable warnings, filters for bus, route, crowd and status | **Done** | E2E |
| FR6 | Predict crowd LOW/MEDIUM/HIGH with an ML model | `ml-service` Model 1, `CrowdModelClient`, `CrowdPredictionService` | **Done** | pytest; backend tests; E2E. Test accuracy 0.921 / macro-F1 0.888 (synthetic) |
| FR7 | Display predicted crowd levels | badges, forecast chart, history, "Predicted" labels | **Done** | E2E |
| FR8 | Store bus, route and crowd data | JPA entities and MySQL; schema in `docs/database-schema.md` | **Done** | MySQL inspected |
| FR9 | Admin manages buses | `ManageBuses.jsx` (now with a separate seat count, validated to not exceed capacity) | **Done** | Tests; E2E create (seat > capacity rejected) → persists → delete |
| FR10 | Admin manages routes | `ManageRoutes.jsx` (stops editor, click on the map to add stops) | **Done** | Tests; E2E route created via the map, then deleted |
| FR11 | Admin views system data | Admin overview (analytics, live occupancy, sensors, models), users, prediction history, Occupancy & Sensors | **Done** | Tests; E2E |
| FR12 | Travel information and best travel time | `TravelService`, 24-hour forecast; planner shows origin/destination pins, ETA, crowd and places left per bus | **Done** | Tests; E2E |
| Perf / retention / security / reliability / portability | see previous phases | **Done** | Tests, failure checks, 390 px mobile E2E |
| Availability: automatic restart and backups | `scripts/start-demo.ps1` (supervised restart), `scripts/backup-db.ps1`, `scripts/stop-demo.ps1` | **Done (basic)** | Final acceptance test: backend and ML killed and restarted automatically; backup restored and verified. Not a Windows service, so it does not survive a reboot |

## Part B — Extension modules (beyond the SRS)

### Module 1 — Live tracking with Google Maps

| Requirement | Status | Notes / evidence |
|---|---|---|
| Interactive map, active bus markers, route and stops, select a bus | **Done** (OSM verified); Google provider **Done (code verified with a stubbed API; valid key not verified)** | `GoogleTransitMap.jsx` draws the same markers, routes, stops and origin/destination pins (`VITE_MAP_PROVIDER=google` + `VITE_GOOGLE_MAPS_API_KEY`). **Fallbacks verified in Chrome** (`e2e/map-provider-check.mjs`): with no key, and with an invalid key (Google's auth-failure callback), the page shows a notice and the OpenStreetMap map with 10 buses. Testing this found and fixed a bug: the invalid-key callback was not reaching the map |
| Bus ID, route number, direction, location, last update, crowd level, available capacity | **Done** | Detail panel and marker tooltip ("~19/50 onboard · 31 places left") |
| Real-time markers via STOMP; connection status; last-update timestamp | **Done** | E2E |
| Stale or unavailable location warning | **Done** | faded marker after 30 s; "location unavailable" message for buses that have never reported |
| ETA and distances | **Done** — straight-line estimates | Routes API **not integrated**, on purpose (not in the SRS; billed; needs a server key). The integration point is documented in README |
| Filters: route, bus, crowd level | **Done** | E2E uses the bus filter |
| Origin and destination on the map | **Done** | green/red pins in the travel planner |
| Places API | **Not done (justified)** | origins are the system's own stops; nothing to search |
| Real GPS device | **Done (not verified with hardware)** | `POST /api/location/update` stores the source `GPS`, and the simulator yields for 60 s. Tested by API calls only |

### Module 2 — Camera-based passenger counting

| Requirement | Status | Evidence |
|---|---|---|
| Real detection model | **Done** | YOLO11n (Ultralytics 8.3.253, PyTorch CPU) |
| Tracking to avoid repeated counting | **Done** | ByteTrack IDs plus a line-crossing counter with a dead band. Unit tests: standing still, jitter and back-and-forth are counted correctly |
| Visible count, and entries/exits by line crossing | **Done** | cabin and door modes |
| Timestamp, camera ID, bus ID, confidence, frame quality | **Done** | stored per observation |
| Missing camera, low-quality frames, network failure, inference errors | **Done** | failure checks above; DEGRADED / CAMERA_UNAVAILABLE / INFERENCE_ERROR health states |
| Test-video mode | **Done** | OpenCV `vtest.avi` (Apache-2.0), reported as `TEST_VIDEO` |
| Accuracy validation | **Done (test video)** | Per-frame people count: MAE 0.20. Door crossings vs. a manual count: precision **1.00**, recall **0.92** (24/26). On a recording of a street, not a bus |
| Real bus camera | **Blocked** | no hardware. **Laptop webcam demonstration done with consent** (one person detected live, counts in the UI); it is labelled "Laptop webcam demo" and is not bus-camera validation |
| Privacy | **Done** | local inference, no footage stored, no face recognition, counts only, 30-day retention. Notice and consent requirements are documented in `vision-service/README.md` |

### Module 3 — Occupancy and seat availability

| Requirement | Status | Evidence |
|---|---|---|
| Capacity separate from seats | **Done** | `buses.capacity` and `buses.seat_count`; BUS-101 configured as 50 / 40 |
| Formulas (available capacity, available seats, standing) | **Done** | `OccupancyMath` + tests (50/40 example) |
| Seat availability only when measured | **Done** | seat sensor or manual count only; otherwise "Unknown (40 seats; no seat sensor)" (seen in E2E) |
| Occupancy %, LOW/MEDIUM/HIGH/FULL with configurable thresholds, over-capacity warning | **Done** | tests (FULL at 52/50); UI alert |
| Last update, sensor health, confidence; estimate and lower-bound labels | **Done** | panel and admin page |
| Seat-level detection by camera | **Not done (justified)** | not reliable from these camera views; no seat-occupancy model or labelled data |

### Module 4 — AI crowd prediction (15-minute horizon)

| Requirement | Status | Evidence |
|---|---|---|
| Dataset validation, documented pipeline, time-based split, baseline, model selection | **Done** | `train_forecast.py`; persistence baseline; GroupKFold by day |
| Metrics | **Done** | MAE **2.12** vs. baseline 2.93; RMSE 2.87 vs. 3.99; macro-F1 **0.789** vs. 0.728. HIGH class is weak (precision 0.44). **Synthetic data** |
| Persistence, inference API, Spring Boot integration | **Done** | `forecast_model.joblib`, `POST /forecast`, `LiveForecastService` |
| DB persistence with horizon and model metadata | **Done** | `crowd_predictions` (model_kind, horizon_minutes, predicted_passengers, input_onboard); `ml_models` |
| Scheduled and event-driven updates; stale or missing inputs | **Done** | at most 1 per minute per bus after a report, plus every 5 minutes; no live forecast when data is older than 2 minutes |
| Real-world monitoring | **Done** | `/api/admin/forecast-performance` — see result below |

### Module 5 — Entry/exit reconciliation

**Done.** `onboard = previous + entries − exits`. Each case below is covered by `OccupancyTest`:
- duplicate sequence numbers are ignored;
- late reports are applied and marked APPLIED_LATE;
- gaps flag the estimate;
- a negative total is clamped to 0 and flagged;
- an unknown starting count is assumed 0 and flagged;
- a failed door camera flags the estimate;
- a cabin count above the door count flags the estimate;
- manual reconciliation is admin-only, requires a note, and is audit-logged with the admin account.

Verified in the UI (E2E) and in the observation log.

### Modules 6–9 — Database, UI, API, testing

All **Done**:
- new tables `sensors`, `passenger_count_observations` (unique sensor + sequence), `bus_occupancy`, `ml_models`;
- a migration step for the FULL enum value;
- the admin Occupancy & Sensors page, user dashboard occupancy, and alerts;
- REST endpoints plus `/topic/occupancy`, documented in Swagger.

Evidence is in the tables above.

## Live forecast real-world check

This is the result of a 30-minute door-counter run on the looping test video (17,889 frames, 353 reports, none lost), measured by `GET /api/admin/forecast-performance`:

| | Live 15-min forecast | Persistence baseline |
|---|---|---|
| Forecasts checked (target time passed, count recorded within ±3 min) | 25 (11 had no recorded value) | same 25 |
| Mean absolute error (passengers) | **6.45** | 7.64 |
| Crowd level correct | 25 / 25 | — |

Caveats:
- The "actual" values are **camera estimates from a looping street video**, not ground truth.
- The bus stayed in the LOW band, so the level match is weak evidence.
- The sample is small.

**Finding from the same run:** in every loop of the video more people cross right-to-left than left-to-right (13 vs. 10), so the running door total **drifts downward**. The negative clamp fired 16 times and the bus was flagged for reconciliation. This is exactly the limitation of door counting that the reconciliation workflow exists for.

## Data changes made during verification

- BUS-101 seat count set to 40 (capacity 50).
- Sensors `CAM-BUS101-DOOR` and `CAM-BUS102-CABIN` registered.
- BUS-101 onboard reconciled (demo head counts).

Temporary test sensors and their rows were deleted. Backups: `db-backups/transport_db_before_occupancy_20261023.sql`.

## What you need to provide (blockers)

1. **Google Maps JavaScript API key** (billing enabled, HTTP-referrer restricted), to verify the Google provider. Setup is in README → Maps.
2. **A camera in a bus** (USB camera on an onboard PC, or an RTSP IP camera), mounted over the door. Run `run_counter.py --source <index or URL>`, then hand-count real boardings to measure door-count accuracy.
3. **A GPS device or tracking feed** that posts to `POST /api/location/update`.
4. *(Optional)* real passenger-count data, to retrain both models.
5. Rotate the MySQL root password (it was in plain text in the old config and in `Desktop\mega project.zip`).

## Next tasks

1. **You:** create a Google Maps JavaScript API key, then add `VITE_MAP_PROVIDER=google` and `VITE_GOOGLE_MAPS_API_KEY=<key>` to `.env` and restart `npm run dev`. Then run the browser suite and check markers, routes, stops and origin/destination pins on the Google map. The fallbacks are already verified.
2. **You (hardware or footage):** a door camera on a bus, or a lawful recording of a **bus doorway** with consenting people (top-down view preferred, 1–5 minutes, a few people boarding and leaving). Put it in `vision-service/test_videos/`, then run `validate_crossings.py` and `evaluate_counts.py` on it. The current 0.92 recall is from a street scene; a crowded doorway will be harder.
3. **You (hardware):** a GPS device or tracking feed posting to `POST /api/location/update` with an admin token.
4. Deployment hardening (SRS availability): an auto-restart service, scheduled `mysqldump` backups, and HTTPS.
