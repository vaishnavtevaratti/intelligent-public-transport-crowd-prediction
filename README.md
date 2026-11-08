# OptiTransit — Intelligent Public Transport & Crowd Prediction System

B.Tech CSE Mega Project (D.K.T.E. Society's Textile & Engineering Institute, 2025-26).

The web app shows **live bus positions on a map**, updated over **WebSocket**, and predicts **crowd levels (Low / Medium / High)** with a **machine-learning model**. It also suggests the **least crowded time to travel**, and gives administrators tools to manage buses, routes and users.

**Extensions beyond the SRS** (documented separately in `IMPLEMENTATION_STATUS.md`):
- camera-based passenger counting (YOLO + ByteTrack, `vision-service/`);
- live occupancy and seat availability;
- entry/exit reconciliation;
- a second ML model that forecasts the crowd 15 minutes ahead from live occupancy.

> Bus movement comes from a built-in **GPS simulator**, as the SRS specifies. The UI always labels it "Simulated GPS". The ML model is trained on a **synthetic demonstration dataset** (see `ml-service/README.md`).

## Demo dataset: Kolhapur, Maharashtra

All demo transport data is set in **Kolhapur**. It is an **academic demo network**: the routes D1–D8 are **fictional**. They are not official KMT (Kolhapur Municipal Transport) routes, route numbers or timetables, and the UI and API say so.

| Route | From → To (via) | Road km |
|---|---|---|
| D1 | Kolhapur Central Bus Stand (CBS) → Shivaji University (Rajarampuri, Rajaram College) | 5.6 |
| D2 | CBS → Rankala Lake (Shalini Palace) (Railway Station, Dasara Chowk, Bindu Chowk, Mahalaxmi Temple, Shivaji Peth) | 6.3 |
| D3 | Kolhapur Railway Station → Kolhapur Airport (Airport Road) (CBS, Rajarampuri, Uchgaon, Ujalaiwadi) | 14.8 |
| D4 | Kasaba Bawada → Mahalaxmi Temple (Line Bazar, New Palace, Nagala Park, Town Hall) | 8.4 |
| D5 | Tarabai Park → Kalamba (CBS, Shahupuri, Udyam Nagar, Sambhaji Nagar) | 9.3 |
| D6 | Phulewadi → CBS (Rankala Lake, Shivaji Chowk, CPR Hospital) | 7.7 |
| D7 | Shahu Market Yard → Gokul Shirgaon MIDC (Uchgaon, Ujalaiwadi) | 13.4 |
| D8 | Sane Guruji Vasahat → Kolhapur Railway Station (Sambhaji Nagar, Rajarshi Shahu Stadium, Laxmipuri) | 7.2 |

How the data was made, reproducibly, with `python scripts/build_kolhapur_network.py`:
- **Landmarks** were geocoded with OpenStreetMap **Nominatim**, keeping only hits that resolved inside Kolhapur. Wrong hits, for example a "Station Road" in Jaysingpur, were rejected.
- Each stop was **snapped to the nearest road** with **OSRM**. The snaps are 1–113 m from the landmark. The exception is the airport, whose aerodrome centre is 560 m from the public road, so that stop is named "Kolhapur Airport (Airport Road)".
- The **road geometry** of each route (259–573 points) and its road distance come from OSRM over the OpenStreetMap road network. They are stored in `routes.path_geometry`. The map draws these roads; buses move along them, and the travel planner measures distance along them.
- Map data © OpenStreetMap contributors (ODbL). The result is `backend/src/main/resources/demo/kolhapur-network.json`, used by the seeder, so no internet is needed at run time.

Demo fleet: BUS-101…BUS-110, capacity 40/50/60, configured seats 32/40/44, BUS-110 in maintenance. The webcam demo buses DEMO-WEBCAM and DEMO-WEBDOOR are parked at CBS without a route.

**Seeding and reset:**
- **Empty database:** demo users, routes and buses are created on first start.
- **Existing database with the earlier New York demo network:** it is converted once, in place, on the next start. Route and bus ids are kept, so sensors and history stay linked. Only the simulated GPS points outside Kolhapur of those demo buses are removed.
- **Repeated starts** change nothing: no duplicates.
- **Safe reset** of the demo network to the shipped definition: run `scripts\stop-demo.ps1`, then `powershell -ExecutionPolicy Bypass -File scripts\reset-demo.ps1`. It takes a backup first and never touches users, sensors, predictions, camera counts or admin-created routes and buses.

**Simulated passengers:** buses **without** a passenger-counting sensor get a simulated onboard count every 30 s, following a time-of-day profile and always between 0 and capacity. It is labelled **"Simulated"**, has no confidence value and no seat data. Buses with a sensor (BUS-101, BUS-102 and the webcam demo buses) show only real sensor, test-video, webcam or manual data. Disable with `SIMULATE_PASSENGERS=false`.

## Architecture

```
 Browser (React + Vite, Leaflet/Google map)
    │  REST /api/**  (JWT in Authorization header)
    │  STOMP over SockJS /ws  (JWT in CONNECT frame)  ◀── /topic/location (5 s), /topic/crowd, /topic/occupancy
    ▼
 Spring Boot 3.2 (Java 17) ── JDBC ──▶ MySQL 8 (transport_db)
    │  ▲ POST /api/sensors/observations (X-Sensor-Code / X-Sensor-Key)
    │  └── vision-service (Python, OpenCV, YOLO11n + ByteTrack) on the bus: counts only, no video
    │  schedulers: GPS simulation (5 s) · predictions + live forecasts (5 min) · retention, model registry (hourly)
    │  HTTP JSON
    ▼
 ml-service (Python, Flask, scikit-learn): time-and-route model (/predict) + 15-min live forecast (/forecast)
```

| Folder | Contents |
|---|---|
| `src/` | React frontend: pages, components (`map/`, `charts/`, `ui/`, `layout/`), services (API and WebSocket clients), hooks, context |
| `backend/` | Spring Boot backend: controllers, services, entities, repositories, security, websocket, scheduler, plus JUnit tests |
| `ml-service/` | dataset generators, training scripts, both trained models, Flask prediction API, pytest tests |
| `vision-service/` | camera/test-video passenger counter (`run_counter.py`), counting logic and tests, detection evaluation |
| `docs/database-schema.md` | `CREATE TABLE` statements of every table |
| `e2e/ui-smoke.mjs` | browser end-to-end check (real UI + backend + DB + ML) |
| `scripts/verify-websocket.cjs` | WebSocket live-update and authentication check |
| `IMPLEMENTATION_STATUS.md` | SRS requirement traceability and verification results |

## Prerequisites

- Java 17 or newer (JDK) and Maven 3.9
- Node.js 20 or newer
- Python 3.11 or newer
- MySQL 8 running on `localhost:3306`

## First-time setup

1. **Backend secrets.** Copy `backend/application-local.properties.example` to `backend/application-local.properties` and fill in your MySQL password and a random JWT secret of at least 32 characters. This file is git-ignored. You can set the same names as environment variables instead.
2. **Frontend.** Copy `.env.example` to `.env`, then run `npm install`.
3. **ML service.**
   ```powershell
   cd ml-service
   python -m venv .venv
   .venv\Scripts\python -m pip install -r requirements.txt
   ```

The database `transport_db` is created automatically. Tables are added or updated without deleting data (`ddl-auto=update`). Demo routes, buses and users are inserted only when the tables are empty.

## Starting the whole project (demonstration)

One command starts everything, each service in its own window. Run it from the project folder in PowerShell:

```powershell
cd "C:\Users\hp\Desktop\mega project"
powershell -ExecutionPolicy Bypass -File scripts\start-demo.ps1
```

What it does:
1. Checks that MySQL is listening on 3306 and that ports 8001/8080/5173 are free.
2. Takes a database backup into `db-backups\` (`-NoBackup` skips it).
3. Starts the ML service (:8001), the backend (:8080), the counter agent (:8002, this computer only) and the frontend (:5173, production build). It waits until each one answers.
4. Opens http://localhost:5173. It takes about 30 s.

Every service window is **supervised**: if the service crashes, it is restarted after 5 s (tested: backend back in ~15 s, ML service in ~10 s; the web page reconnects by itself). Close a window, or press Ctrl+C in it, to stop that service on purpose.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1    # stop everything (MySQL and the data stay)
powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1    # backup now; keeps the 10 newest automatic backups
mysql -u root -p transport_db < db-backups\transport_db_auto_<timestamp>.sql   # restore a backup
```

- Options: `-Dev` uses the Vite dev server with hot reload instead of the production build. `-NoAgent` skips the counter agent. `-NoBrowser` does not open the browser.
- After changing backend code: stop the demo first, then run `cd backend; mvn package`, then start the demo again. The supervisor would otherwise restart the backend against a half-written jar.
- **Daily backups (optional):** run this once in an administrator PowerShell to schedule them:
  `schtasks /Create /SC DAILY /ST 13:00 /TN "OptiTransit DB backup" /TR "powershell -ExecutionPolicy Bypass -File 'C:\Users\hp\Desktop\mega project\scripts\backup-db.ps1'"`

## Running the services by hand (development)

```powershell
# 1. ML service  -> http://127.0.0.1:8001
cd ml-service; .venv\Scripts\python app.py

# 2. Backend      -> http://localhost:8080  (Swagger: http://localhost:8080/swagger-ui.html)
#    (stop a running backend before rebuilding with mvn package: a locked jar gets replaced by a thin jar)
cd backend; mvn spring-boot:run

# 3. Frontend     -> http://localhost:5173
npm run dev

# 4. (optional) counter agent -> http://127.0.0.1:8002 (this computer only)
#    then Admin > Occupancy & sensors > Counter control > Start / Stop (test video or webcam)
cd vision-service; .venv\Scripts\python agent.py

#    or run one counter directly from the command line (Ctrl+C or Q stops it) — see vision-service/README.md
cd vision-service; .venv\Scripts\python run_counter.py --source test_videos/vtest.avi --mode door --sensor-code CAM-BUS101-DOOR --loop
```

Vite is fixed to port **5173** (`strictPort`), because the backend's CORS whitelist expects it. If another project is already using 5173, stop that project first, or start the backend with `CORS_ALLOWED_ORIGINS=http://localhost:5174` and run `npx vite --port 5174`.

### Local demo accounts (seeded only into an empty database)

| Role | Email | Password |
|---|---|---|
| Administrator | admin@transport.com | admin123 |
| Passenger | user@transport.com | user123 |

These are for local demonstration only. Change them before deploying anywhere.

## Tests

```powershell
cd backend;    mvn test                        # 50 JUnit + MockMvc tests (in-memory H2)
cd ml-service; .venv\Scripts\python -m pytest -q   # 20 tests
cd vision-service; .venv\Scripts\python -m pytest -q tests   # 22 tests (counting, camera safety, counter agent, live view)
cd vision-service; .venv\Scripts\python evaluate_counts.py   # detection vs. hand-counted frames
npm run lint; npm run build                    # frontend checks
npm run verify:ws                              # WebSocket check (services running)
npm run test:e2e                               # 18 browser checks in your installed Chrome (services + counter agent running)
node e2e/acceptance-api-check.cjs              # 25 SRS checks: security, validation, response time, 50 simultaneous users, 20 WebSocket clients
node e2e/kolhapur-tracking-check.cjs 30        # 11 checks: positions on the road geometry, ~5 s, no teleporting, 2 clients identical, simulated passengers
node e2e/kolhapur-screens.mjs <dir>            # screenshots of the Kolhapur map, a route and a travel search
node e2e/webcam-control-check.mjs --i-have-consent CAM-WEBCAM-CABIN 60   # TURNS THE WEBCAM ON (only with everyone's consent)
node e2e/occupancy-live-check.mjs BUS-101 50    # live passenger counts reach the browser without reload (run a counter first)
node e2e/reconnect-check.mjs 120                 # stop/start the backend meanwhile: UI shows Reconnecting… then Live again
node e2e/counter-control-check.mjs CAM-BUS101-DOOR 45   # Start/Stop a counter from the admin page (test video; agent running)
node e2e/google-provider-stub-check.mjs          # Google provider code against a recording google.maps stub (dev server with VITE_MAP_PROVIDER=google)
# The map-provider checks need a second dev server, e.g. backend with CORS_ALLOWED_ORIGINS=...,http://localhost:5174 and
#   VITE_MAP_PROVIDER=google VITE_GOOGLE_MAPS_API_KEY= npx vite --port 5174   -> APP_URL=http://localhost:5174 node e2e/map-provider-check.mjs out.png
#   VITE_MAP_PROVIDER=google VITE_GOOGLE_MAPS_API_KEY=e2e-stub-placeholder npx vite --port 5174   -> APP_URL=http://localhost:5174 node e2e/google-provider-stub-check.mjs
cd vision-service; .venv\Scripts\python validate_crossings.py --score   # door-crossing counts vs. manual ground truth
```

## Configuration reference

**Backend** (environment variables, or `backend/application-local.properties`):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/transport_db?...` | MySQL connection |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / *(required)* | MySQL credentials |
| `JWT_SECRET` | *(required)* | HS256 signing key, at least 32 characters |
| `JWT_EXPIRATION_MS` | `86400000` (24 h) | token lifetime |
| `ADMIN_REGISTRATION_CODE` | *(empty = disabled)* | code needed to self-register as an administrator |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | allowed browser origins (REST and WebSocket) |
| `ML_SERVICE_URL` | `http://127.0.0.1:8001` | Python ML service |
| `SIMULATION_ENABLED` | `true` | turn the GPS simulator on or off |
| `SIMULATE_PASSENGERS` | `true` | simulated passenger counts for buses without a sensor (labelled "Simulated") |
| `VISION_AGENT_URL` / `VISION_AGENT_TOKEN` | `http://127.0.0.1:8002` / read from `vision-service/agent_token.txt` | local counter agent (start/stop and live view of counters) |
| `GOOGLE_ROUTES_API_KEY` | *(empty = off)* | optional server-side Routes API key |
| `SEED_DEMO_DATA` | `true` | seed demo data into empty tables |

**Frontend** (`.env`): `VITE_API_BASE_URL`, `VITE_WS_URL`, `VITE_MAP_PROVIDER` (`osm` or `google`), `VITE_GOOGLE_MAPS_API_KEY`.

## Maps: OpenStreetMap (default) or Google Maps

The SRS lists the Google Maps API. A free alternative was chosen as the default, so the project runs without a Google account:

| | OpenStreetMap + Leaflet (default) | Google Maps JavaScript API |
|---|---|---|
| Cost / key | free, no key | API key and a **billing-enabled** Google Cloud project; per-SKU monthly free usage |
| Used for | map, bus markers, road-geometry route lines, stops, click-to-add stops | the same features, same road geometry |
| Status in this project | **tested end to end** | code tested against a recording stub of the Google API (12/12); **real Google Maps BLOCKED: no API key** |

Facts from the official Google Maps Platform documentation (checked 2026-10-24):
- **A standard key needs a billing-enabled Cloud project.** Google's get-started guide: "To start using Google Maps Platform, you must create a billing-enabled Cloud project and enable the desired APIs or SDKs."
- The only no-billing option is the **Maps Demo Key**, which is "intended for testing and prototyping purposes only, and is not designed for production use".
- **Free usage:** since March 2025, the monthly $200 credit was replaced by **per-SKU monthly free usage caps**. Dynamic Maps (Maps JavaScript map loads) includes 10,000 free loads per month; Routes "Compute Routes Essentials" includes 10,000 free requests per month. Prices beyond that are on the official pricing page.
- Set a budget alert in Cloud Billing anyway.

To switch to Google Maps:
1. In [Google Cloud Console](https://console.cloud.google.com/), create a project and **enable billing** for it.
2. In *APIs & Services → Library*, enable the **Maps JavaScript API**.
3. In *Google Maps Platform → Credentials*, choose **Create credentials → API key**. Restrict it:
   - **Application restriction:** *Websites* (HTTP referrers), with the whole referrer including the scheme: `http://localhost:5173/*` and your deployed domain;
   - **API restriction:** **Maps JavaScript API** only.
4. In `.env`, set `VITE_MAP_PROVIDER=google` and `VITE_GOOGLE_MAPS_API_KEY=<your key>`, then restart the frontend. The demo launcher rebuilds it on start.
5. Check: open Live Tracking. The chip at the top right says **"Map: Google Maps"**. With a bad key, the page says "Google Maps rejected the API key …" and shows OpenStreetMap.

If the key is missing, or Google rejects it, the map falls back to OpenStreetMap with a notice. The missing-key case was verified again on the Kolhapur data (12 markers); a valid key has **never** been tested.
- The script is loaded once and reused.
- Markers, overlays and listeners are removed when the map unmounts.
- A failed download can be retried.

**Road distance and ride time (Google Routes API, optional, server-side):**
- Set `GOOGLE_ROUTES_API_KEY` (environment variable, or a line in `backend/application-local.properties`).
- Use a **separate server key** restricted to the **Routes API**, never the browser Maps key. Restrict it by **IP address**: the backend server's public address. Enable the **Routes API** in the same billing-enabled project.
- The travel planner then sends the boarding stop, the stops in between and the alighting stop to `computeRoutes` (DRIVE mode, field mask `routes.distanceMeters,routes.duration`, cached 10 minutes), and labels the result **"Road · Google Routes"**.
- Without a key, or when the API fails, it uses the stored road geometry (labelled **"Road distance · OpenStreetMap"**), or straight lines for routes without geometry (labelled **"Straight-line estimate"**).
- Verified with a local fake Routes server (`RoutingIntegrationTest`); **not verified against Google** (no key).

**Places and Geocoding APIs are deliberately not integrated:**
- The SRS asks only for a map ("Google Maps API").
- Origins and destinations are the system's own named stops, so no place search is needed.
- Bus arrival times (ETA of a bus at your stop) come from the GPS simulator's position, which is approximate.

Route lines on the map follow the stored road geometry (solid). A route without geometry, such as one an admin draws or edits, is drawn as **dashed** straight lines between stops and labelled "straight lines between stops (no road geometry)". The travel planner marks the chosen origin (green) and destination (red).

## Switching from simulated to real GPS

A real device, or a feed from a transport authority, can send positions with an admin token:

```
POST /api/location/update   {"busId": 3, "latitude": 16.7033, "longitude": 74.2433}
```

These positions are stored with source `GPS` and broadcast live. The simulator automatically stops moving a bus for 60 seconds after it receives a real fix. Set `SIMULATION_ENABLED=false` to turn the simulator off completely.

## Passenger occupancy and seat availability

Capacity and seats are stored separately: `buses.capacity` is the total passenger capacity (seats + standing), and `buses.seat_count` is the number of seats. All live values are **estimates**:
- available capacity = `max(0, capacity − onboard)`
- available seats = `max(0, seats − occupied seats)`
- standing = `max(0, onboard − occupied seats)`

Occupied seats are **only** taken from a seat sensor or a manual count. The system never infers them from person detection, and shows "Unknown" otherwise.

The crowd category (LOW < 50 %, MEDIUM < 85 %, HIGH < 100 %, FULL ≥ 100 %) can be configured with `app.crowd.thresholds.*`. Over capacity raises a warning. Data older than 2 minutes is shown as STALE. Door counts are reconciled as `onboard = previous + entries − exits`, with:
- duplicate and late report handling;
- gap detection;
- a negative clamp;
- manual reconciliation by an admin (**Admin → Occupancy & Sensors**).

## Main REST API

All endpoints except register and login need `Authorization: Bearer <token>`. The full interactive documentation is on Swagger.

| Method & path | Access | Purpose |
|---|---|---|
| `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/profile` | public / user | authentication |
| `GET /api/buses`, `GET /api/buses/{id}` | user | buses, including latest crowd level |
| `POST`/`PUT`/`DELETE /api/buses/**` | admin | manage buses |
| `GET /api/routes`, `GET /api/routes/{id}` | user | routes and ordered stops |
| `POST`/`PUT`/`DELETE /api/routes/**` | admin | manage routes (deleting a route that still has buses returns 409) |
| `GET /api/location/live`, `GET /api/location/history/{busId}` | user | positions |
| `POST /api/location/update` | admin | submit a real GPS position |
| `POST /api/crowd/predict` | user | ML prediction for a bus at a travel time (stored) |
| `GET /api/crowd/forecast/{routeId}?date=` | user | 24-hour forecast and best hours to travel |
| `GET /api/crowd/history?routeId&busId&level&source&page&size` | user | paged prediction history |
| `GET /api/crowd/alerts`, `GET /api/crowd/model` | user | high-crowd alerts; model status and metrics |
| `GET /api/travel/stops`, `GET /api/travel/search?from&to` | user | travel planner |
| `GET /api/dashboard` | user | dashboard figures |
| `GET /api/admin/analytics`, `GET /api/admin/users`, `PUT /api/admin/users/{id}/role`, `DELETE /api/admin/users/{id}` | admin | analytics and user management |
| `POST /api/sensors/observations` | **device key** (`X-Sensor-Code`, `X-Sensor-Key`) | camera/sensor report: visible count, entries/exits since last report, occupied seats, health, sequence number |
| `GET /api/occupancy`, `GET /api/occupancy/{busId}` | user | live occupancy: onboard estimate, places left, seats (if measured), crowd category, data status, sensors, 15-min forecast |
| `GET /api/occupancy/{busId}/observations`, `PUT /api/occupancy/{busId}/reconcile` | admin | observation/audit log; manual head count |
| `GET`/`POST /api/admin/sensors`, `PUT`/`DELETE /api/admin/sensors/{id}`, `POST /api/admin/sensors/{id}/rotate-key` | admin | sensor registration (key shown once, stored hashed) |
| `GET /api/admin/models`, `GET /api/admin/forecast-performance` | admin | model versions and metrics; live forecast vs. later recorded counts |
| `GET /api/admin/counters`, `POST /api/admin/counters/start`, `POST /api/admin/counters/{sensor}/stop` | admin | start/stop passenger counters through the local counter agent (camera needs `confirmConsent`) |
| `GET /api/admin/counters/{sensor}/live`, `GET …/frame`, `POST …/reset` | admin | live numbers, latest annotated frame (JPEG, memory only, 204 while starting), reset session in/out totals |
| `GET /api/health` | **public** | backend, database, ML service and counter agent status; tracking mode (no versions or hosts) |

Errors always use the same JSON shape: `{status, error, message, path, validationErrors}`. Stack traces are never returned.

WebSocket: connect SockJS to `/ws` with the STOMP header `Authorization: Bearer <token>`, then subscribe to `/topic/location`, `/topic/crowd` and `/topic/occupancy`.

## Known limitations

- **Not tested with real hardware:** no bus camera, GPS device or seat sensor was available. Passenger counting was verified with the OpenCV sample video, a street scene filmed from above, and is reported as `TEST_VIDEO` everywhere.
- Seat-level occupancy cannot be detected from the cameras. Seats are known only from a seat sensor or a manual count.

- GPS positions are simulated. Demo buses move along real road geometry (OpenStreetMap/OSRM), but speeds are an average, not traffic.
- The crowd model is trained on synthetic data, so its metrics do not show real-world accuracy.
- Logout is client-side: the token is deleted in the browser. A stolen token stays valid until it expires (24 h), because there is no server-side token blacklist.
- The demo network is a fictional academic network in Kolhapur, not official KMT data. Routes an admin creates have no road geometry, so they are drawn as dashed straight lines between stops.
- Simulated passenger counts follow a synthetic daily profile; they demonstrate the UI and the live forecast, not real demand.
