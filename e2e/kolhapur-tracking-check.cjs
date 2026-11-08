/*
 * Live tracking on the Kolhapur demo network (backend running): two WebSocket clients listen for N seconds.
 * Checks that every simulated position lies in Kolhapur ON its route's road geometry, buses move in small steps
 * (no teleporting), updates arrive every ~5 s, both clients receive the same positions, and passenger counts of
 * buses without sensors are labelled SIMULATED and stay within capacity.
 *   node e2e/kolhapur-tracking-check.cjs [seconds]
 */
const { Client } = require('@stomp/stompjs');
const SockJS = require('sockjs-client');

const API = process.env.API_URL || 'http://localhost:8080/api';
const WS = process.env.WS_URL || 'http://localhost:8080/ws';
const seconds = Number(process.argv[2] || 30);
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`); };

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;
function dist(a, b) {
  const dLat = rad(b[0] - a[0]); const dLng = rad(b[1] - a[1]);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function offsetFromLine(p, line) {   // metres from point to polyline (flat projection per segment)
  let best = Infinity;
  for (let i = 0; i < line.length - 1; i++) {
    const a = line[i]; const b = line[i + 1]; const cos = Math.cos(rad(a[0]));
    const ax = a[1] * cos; const ay = a[0]; const bx = b[1] * cos; const by = b[0]; const px = p[1] * cos; const py = p[0];
    const dx = bx - ax; const dy = by - ay; const len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
    best = Math.min(best, dist(p, [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]));
  }
  return best;
}
const inKolhapur = ([lat, lng]) => lat >= 16.55 && lat <= 16.85 && lng >= 74.10 && lng <= 74.40;

function listen(token) {
  return new Promise((resolve) => {
    const msgs = [];
    const c = new Client({
      webSocketFactory: () => new SockJS(WS), connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 0, debug: () => {},
      onConnect: () => {
        c.subscribe('/topic/location', (m) => msgs.push({ at: Date.now(), ...JSON.parse(m.body) }));
        setTimeout(() => { c.deactivate(); resolve(msgs); }, seconds * 1000);
      },
      onStompError: () => resolve(msgs),
    });
    c.activate();
  });
}

(async () => {
  const login = await (await fetch(`${API}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'user@transport.com', password: 'user123' }) })).json();
  const h = { Authorization: `Bearer ${login.token}` };
  const routes = await (await fetch(`${API}/routes`, { headers: h })).json();
  const byId = Object.fromEntries(routes.map((r) => [r.id, r]));
  check('every route is a Kolhapur demo route with road geometry',
    routes.length > 0 && routes.every((r) => r.demoRoute && r.pathGeometry?.length > 2 && r.pathGeometry.every(inKolhapur)),
    routes.map((r) => `${r.routeCode}:${r.pathGeometry?.length ?? 0}pts`).join(' '));

  const [a, b] = await Promise.all([listen(login.token), listen(login.token)]);
  check('positions received', a.length > 0 && b.length > 0, `${a.length} and ${b.length} messages on two clients`);
  check('every position is labelled SIMULATED', a.every((m) => m.source === 'SIMULATED'));
  check('every position lies in Kolhapur', a.every((m) => inKolhapur([m.latitude, m.longitude])));
  const offsets = a.map((m) => offsetFromLine([m.latitude, m.longitude], byId[m.routeId].pathGeometry));
  check('buses stay on their route road geometry', Math.max(...offsets) < 5, `max offset ${Math.max(...offsets).toFixed(2)} m`);

  const byBus = {};
  a.forEach((m) => (byBus[m.busNumber] ||= []).push(m));
  const steps = []; const gaps = [];
  Object.values(byBus).forEach((list) => list.forEach((m, i) => {
    if (i === 0) return;
    steps.push(dist([list[i - 1].latitude, list[i - 1].longitude], [m.latitude, m.longitude]));
    gaps.push((m.at - list[i - 1].at) / 1000);
  }));
  check('no teleporting (largest step under 100 m per update)', Math.max(...steps) < 100, `largest step ${Math.max(...steps).toFixed(1)} m`);
  const sortedGaps = [...gaps].sort((x, y) => x - y);
  const median = sortedGaps[Math.floor(sortedGaps.length / 2)];
  check('updates about every 5 s', median > 4 && median < 6.5, `median ${median?.toFixed(2)} s`);
  check('buses actually move', steps.filter((s) => s > 1).length > steps.length / 3, `${steps.filter((s) => s > 1).length}/${steps.length} updates moved > 1 m`);
  const key = (m) => `${m.busId}|${m.timestamp}|${m.latitude}|${m.longitude}`;
  const setB = new Set(b.map(key));
  const shared = a.filter((m) => setB.has(key(m))).length;
  check('both clients receive the same positions', shared / a.length > 0.9, `${shared}/${a.length} identical`);

  const occ = await (await fetch(`${API}/occupancy`, { headers: h })).json();
  const sim = occ.filter((o) => o.dataMode === 'SIMULATED');
  check('buses without sensors show SIMULATED counts within capacity',
    sim.length > 0 && sim.every((o) => o.estimatedOnboard >= 0 && o.estimatedOnboard <= o.capacity && o.confidence == null && o.availableSeats == null),
    sim.map((o) => `${o.busNumber} ${o.estimatedOnboard}/${o.capacity}`).join(', '));
  const sensorBuses = occ.filter((o) => (o.sensors || []).length > 0);
  check('buses with a sensor are never simulated', sensorBuses.every((o) => o.dataMode !== 'SIMULATED'),
    sensorBuses.map((o) => `${o.busNumber}:${o.dataMode ?? o.dataStatus}`).join(', '));

  console.log(`${results.filter(Boolean).length}/${results.length} tracking checks passed`);
  process.exit(results.every(Boolean) ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
