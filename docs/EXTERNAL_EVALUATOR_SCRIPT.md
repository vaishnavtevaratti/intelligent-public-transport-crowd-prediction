# External evaluator demonstration (8–12 minutes)

A script for the student presenting the project to external evaluators. Start everything about 5 minutes before (`scripts\start-demo.ps1`), and open the app in a fresh browser window at `http://localhost:5173`. Say clearly what is real, simulated or synthetic at each step; the UI labels it too.

## 0:00 — Introduction and architecture (1.5 min)

- **The problem:** passengers in Kolhapur cannot see where a bus is or how crowded it will be.
- **The system:** live bus tracking on a map, crowd prediction with machine learning, travel advice, and passenger counting with a camera.
- **Architecture:**
  - React frontend;
  - Spring Boot backend with JWT security and WebSocket push;
  - MySQL;
  - Python ML service (scikit-learn);
  - Python vision service (YOLO11 + ByteTrack).
- **Honesty statement:** the routes are fictional academic demo routes between real Kolhapur landmarks. They are not KMT routes. Bus movement is simulated GPS along real roads. The model is trained on synthetic data. Counting is shown on a recorded video and the laptop webcam, not a real bus camera.

## 1:30 — Login and role-based access (1 min)

1. Log in as the passenger (`user@transport.com`).
2. Type `/admin` in the address bar: you are sent back, because passengers cannot open admin pages. The backend also answers 403 to passengers.
3. Mention: passwords are stored as BCrypt hashes, sessions use JWT, and an invalid or edited token is rejected.

## 2:30 — Kolhapur dashboard and map (1.5 min)

1. Dashboard: the bus network overview and the crowd summary.
2. Live Tracking: 12 buses on the Kolhapur map (OpenStreetMap). The chips read "Simulated GPS", "Live updates" and "Map: OpenStreetMap".
3. Choose route **D2** in the route filter. The map zooms to the road from the Central Bus Stand, past the railway station and Mahalaxmi Temple, to Rankala Lake. The line follows real roads (OpenStreetMap road geometry).

## 4:00 — Live movement (1 min)

1. Watch a marker move every ~5 s along the road, with no page reload. That is the WebSocket.
2. Click a bus. Show the route code, next stop, direction, speed, last update time, and the source "Simulated".
3. Optional: other browser windows show the same movement.

## 5:00 — Route and capacity information (1 min)

In the bus panel:
- "~N of capacity" for the passengers onboard;
- "Passenger places left (estimate)";
- seats "Unknown (no seat sensor)".

Say that the system never guesses seats from a camera, and that each value is labelled with its source: Simulated, Test video, Laptop webcam demo, or Manual count.

## 6:00 — Crowd prediction and history (1.5 min)

1. Crowd Prediction: choose a bus and Monday 08:30, then Predict. The model returns LOW / MEDIUM / HIGH with probabilities, and lists the features it used: hour, day, route length, stops, capacity.
2. Point out the note "trained on a synthetic demonstration dataset".
3. Travel Planner: Kolhapur Central Bus Stand → Shivaji University. Show route D1, "Road distance · OpenStreetMap", the ride time, the crowd now, and the least crowded hours.
4. As admin: Prediction History, with the stored predictions paged.

## 7:30 — Passenger counting (2 min)

1. Log in as admin (`admin@transport.com`), then go to Occupancy & sensors → **Counter control**.
2. **CAM-BUS101-DOOR → Start → Test video → Start.** After the detector loads, the annotated picture appears: boxes around people and the red counting line.
3. Explain the three numbers:
   - **people visible now**;
   - **crossings this session** (each crossing counts once);
   - **estimated onboard** = start + entries − exits.
4. Press **Reset session counts**, then **Stop**: the counter sends its last report and closes cleanly.
5. Optional, only if everyone in view agrees: **CAM-WEBCAM-CABIN → Camera 0 → tick the consent box → Start**. The laptop webcam counts the people visible, labelled "Laptop webcam demo". Nothing is recorded. Press Stop.
6. Say the measured accuracy on the test video: door crossings precision 1.00, recall 0.92. Also say the limit: a laptop webcam is not a bus camera, and door counting on it was poor.

## 9:30 — Admin bus and route management (1 min)

1. Manage Buses: add a bus with capacity 50 and 40 seats. Refresh: it is still there. Delete it.
2. Show that 60 seats with capacity 40 is rejected.
3. Manage Routes: the badges "Demo route" and "Road geometry". A route drawn by clicking on the map is shown dashed, because it has no road geometry.

## 10:30 — Database evidence and conclusion (1 min)

1. In MySQL:
   `SELECT route_code, source, destination, distance FROM routes;`
   `SELECT bus_number, capacity, seat_count, status FROM buses;`
2. **Conclusion:** the app is working end to end with simulated GPS and recorded video.
3. **Next steps:** a Google Maps key, real GPS devices, a door-mounted bus camera, and training on real counts.

**If something fails during the demo:**
- If a service window shows an error, it restarts automatically within seconds.
- If the map shows a Google notice, it is using OpenStreetMap instead, which is expected without a key.
