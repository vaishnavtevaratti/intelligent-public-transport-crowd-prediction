# Final demo checklist — Kolhapur Public Transport & Crowd Prediction System

For the student presenting the project, and for anyone repeating the demonstration on this laptop. All results below come from test runs on 2026-10-24, the day the Kolhapur data was introduced; evidence and SQL output are in `IMPLEMENTATION_STATUS.md`.

## 1. Start and stop (Windows PowerShell)

```powershell
cd "C:\Users\hp\Desktop\mega project"
powershell -ExecutionPolicy Bypass -File scripts\start-demo.ps1      # backup, then all services; opens http://localhost:5173
powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1       # stops everything; MySQL and the data stay
```

`start-demo.ps1` does the following:
1. Checks that MySQL is listening on port 3306 and that ports 8001, 8080 and 5173 are free.
2. Backs up the database to `db-backups\`.
3. Starts each service in its own window.
4. Waits until every service answers. This takes about 30 s.

Each window restarts its service after a crash. Close a window to stop that service on purpose.

| Service | Port | Health check |
|---|---|---|
| ML crowd prediction (Python) | 8001 | `http://127.0.0.1:8001/health` |
| Backend (Spring Boot) | 8080 | `http://localhost:8080/api/health` (public: database, ML, counter agent, tracking mode) |
| Counter agent (Python, this computer only) | 8002 | `http://127.0.0.1:8002/health` |
| Frontend (production build) | 5173 | `http://localhost:5173` |

Options:
- `-NoBackup`: skip the database backup.
- `-NoAgent`: do not start the counter agent.
- `-NoBrowser`: do not open the browser.
- `-Dev`: use the Vite dev server with hot reload.

## 2. Configuration

| Where | Name | Needed for |
|---|---|---|
| `backend\application-local.properties` (git-ignored) | `DB_PASSWORD`, `JWT_SECRET` | always |
| same file or environment | `GOOGLE_ROUTES_API_KEY` | optional road distance from Google |
| same file or environment | `SIMULATION_ENABLED`, `SIMULATE_PASSENGERS` | turn the GPS or passenger simulation off (`false`) |
| `.env` (git-ignored; template `.env.example`) | `VITE_MAP_PROVIDER=google`, `VITE_GOOGLE_MAPS_API_KEY` | Google Maps instead of OpenStreetMap |
| `vision-service\sensor_keys.json` (git-ignored) | sensor keys | passenger counters |

## 3. Google Cloud (only if Google Maps should be shown)

These steps follow the official Google Maps Platform documentation. A **billing-enabled** Cloud project is required. Google also offers a "Maps Demo Key", but it is only for testing and prototyping. Each SKU has a monthly free usage cap, for example 10,000 Dynamic Map loads.

1. Create a project in console.cloud.google.com and **enable billing** for it. Set a budget alert.
2. Enable the **Maps JavaScript API**.
3. Create an **API key** and restrict it:
   - **Application restriction:** Websites, for example `http://localhost:5173/*`.
   - **API restriction:** Maps JavaScript API only.
4. In `.env`, set `VITE_MAP_PROVIDER=google` and `VITE_GOOGLE_MAPS_API_KEY=<key>`, then restart the demo.
5. Check: Live Tracking shows the chip **"Map: Google Maps"**.
6. Optional, road distance: enable the **Routes API** and create a second key, restricted to the Routes API and the server's IP address. Set it as `GOOGLE_ROUTES_API_KEY`.

Without a key, the map uses OpenStreetMap and says so. This fallback is tested.

## 4. Database, demo data and backups

- **Database:** `transport_db` on MySQL 8, `localhost:3306`. Tables are created automatically.
- **Demo data:** loaded automatically, safely and repeatably (repeated starts create no duplicates):
  - **Users:** `admin@transport.com` / `admin123` and `user@transport.com` / `user123`. Demo users are created only in an empty database.
  - **Network:** 8 fictional Kolhapur routes D1–D8 (43 stops) with OpenStreetMap road geometry, and 10 buses.
- **Reset the demo network** to its shipped definition. It takes a backup first. Users, sensors, history and admin-created data are not touched:
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1
  powershell -ExecutionPolicy Bypass -File scripts\reset-demo.ps1
  powershell -ExecutionPolicy Bypass -File scripts\start-demo.ps1
  ```
- **Backup:** `powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1`. It keeps the 10 newest automatic backups.
- **Restore:** `mysql -u root -p transport_db < db-backups\transport_db_auto_<timestamp>.sql`. Restoring into a scratch database was tested: all 9 main tables matched.
- **Backup taken before the Kolhapur change:** `db-backups\transport_db_before_kolhapur_20261024.sql`.

## 5. Demo walkthrough (fresh browser window)

| Step | Where | What to show |
|---|---|---|
| Login and roles | `/login` as the passenger | Opening `/admin` redirects; the API answers 403 |
| Kolhapur map | Live Tracking | 12 bus markers on OpenStreetMap; "Simulated GPS" and "Live updates" chips; the header says the routes are academic demo routes, not KMT |
| Live movement | Live Tracking | Markers move about every 5 s along the roads. Choose route D2 in the filter: the map zooms to Station Road → Mahalaxmi Temple → Rankala Lake |
| Bus details | Click a bus | Route code, next stop, direction, speed, last update, position source "Simulated" |
| Capacity | Bus details | "~N of capacity", "Passenger places left (estimate)", seats "Unknown (no seat sensor)". Buses without a camera are labelled "Simulated" |
| Crowd prediction | Crowd Prediction | Choose a bus and time. The model returns LOW / MEDIUM / HIGH with probabilities, the features it used, and a note that it was trained on synthetic data |
| Prediction history | Admin → Prediction History | Paged stored predictions |
| Travel planner | Travel Planner, CBS → Shivaji University | Route D1, distance labelled "Road distance · OpenStreetMap", ETA, crowd now, best hours |
| Admin CRUD | Admin → Manage Buses / Manage Routes | Create a bus, refresh, delete. Routes show the "Demo route" and "Road geometry" badges |
| Passenger counting, video | Admin → Occupancy & sensors → Counter control → CAM-BUS101-DOOR → Start → test video | The live annotated picture appears, then the people visible, the crossings counted this session, and the estimated onboard. Reset session counts, then Stop |
| Webcam (with consent) | Same → CAM-WEBCAM-CABIN → Camera 0 → tick consent → Start | People visible to the laptop webcam, labelled "Laptop webcam demo". Nothing is recorded. Stop releases the camera |
| Database evidence | MySQL Workbench or `mysql` | Run the queries in `IMPLEMENTATION_STATUS.md` |

## 6. What needs a key, deployment or hardware

| Feature | Needs | Demo alternative |
|---|---|---|
| Google Maps rendering | browser API key, billing-enabled project | OpenStreetMap (default) |
| Google road distance | Routes API server key | OpenStreetMap road geometry from OSRM |
| Real GPS | a GPS device or feed posting to `POST /api/location/update` (admin token) | simulated GPS along the roads, labelled |
| Real bus passenger counting | a camera mounted above a bus door | test video; laptop webcam (cabin mode) |
| Seat-level availability | a seat sensor, or a manual count in Reconcile | shown as "Unknown" |
| HTTPS, start at boot | a deployment server, a TLS certificate, a Windows service | localhost demo; supervisor windows |

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `start-demo` says port 5173/8080/8001 is in use | run `scripts\stop-demo.ps1`, or close the other program using the port |
| "MySQL is not listening on port 3306" | start MySQL: `net start MySQL80` (administrator), or use services.msc |
| Backend window loops with an error | read the window; check `DB_PASSWORD` and `JWT_SECRET` in `backend\application-local.properties` |
| The map says "Google Maps selected but VITE_GOOGLE_MAPS_API_KEY is not set" | add the key to `.env`, or set `VITE_MAP_PROVIDER=osm` |
| The map says "Google Maps rejected the API key" | check billing, the enabled Maps JavaScript API, and the referrer restriction (`http://localhost:5173/*`) |
| Counter control says the counter agent is not running | start the demo again, or run `cd vision-service; .venv\Scripts\python agent.py` |
| The live view stays on "Loading the detector…" | the first start loads YOLO (10–30 s); check `vision-service\output\agent_<sensor>.log` |
| The webcam doesn't open | close other apps using the camera; check the camera privacy settings in Windows |
| Predictions say the service is unavailable | the ML window restarts automatically within about 10 s; check its window |
| After changing backend code | stop the demo, `cd backend; mvn package`, start the demo |

## 8. Test results (2026-10-24)

| Area | Status | Evidence |
|---|---|---|
| Startup with the documented commands | PASS | `start-demo.ps1` started everything in ~30 s; all health endpoints UP |
| Kolhapur data in MySQL | PASS | 8 routes, 43 stops, 12 buses, all inside Kolhapur; 0 orphans; users 4/4 kept |
| Repeated seeding without duplicates | PASS | second start and fresh-install test: same counts |
| Map with OpenStreetMap | PASS | 12 markers, road-geometry lines, 0 console errors |
| Google Maps with a valid key | **BLOCKED** | no key. Our Google code passed 12/12 against a stub; missing-key fallback PASS |
| Simulated live tracking | PASS | `kolhapur-tracking-check` 11/11: on the roads (0.00 m offset), ~5 s updates, no teleporting, 2 clients identical |
| Reconnect after a backend crash | PASS | automatic restart; UI "Reconnecting…" → "Live", 0 reloads |
| Real GPS | **BLOCKED** | no device. The API is tested |
| ML prediction is real model output | PASS | stored prediction = direct model output; outage → 503, recovers |
| ML accuracy in the real world | **BLOCKED** | synthetic training data; `evaluate_real.py` is ready for real labelled data |
| Video counting vs. ground truth | PASS | door crossings precision 1.00, recall 0.92; detection MAE 0.20 people/frame |
| Webcam start/stop and live view (consented) | PASS | 10/10; 1 person visible counted. Low frame rate observed (1.8 fps) |
| Webcam door counting | **FAIL** | recall 0–0.5 earlier on a laptop; needs a camera above the door |
| Real bus camera | **BLOCKED** | no hardware |
| Seat availability | PASS (as unknown) | never inferred from cameras; only from a sensor or manual count |
| Security | PASS | 401/403, tampered token, injection, CORS, WebSocket without token refused; BCrypt |
| HTTPS | **BLOCKED** | needs a deployment |
| Backup and restore | PASS | restored into a scratch database; 9 tables matched |
| Performance, 50 users | PASS | 0 errors; p95 0.44–1.18 s across runs |
| Mobile layout (390 px) | PASS | browser smoke test |
| Browser workflows | PASS | `ui-smoke` 18/18, `counter-control-check` 15/15 |
| Automated tests | PASS | backend 51/51, ML 20/20, vision 22/22, lint and build OK |
