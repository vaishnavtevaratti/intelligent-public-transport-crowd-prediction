/*
 * API-level acceptance checks against the SRS (backend running): security, validation, response times,
 * simultaneous users and WebSocket update interval.
 *   node e2e/acceptance-api-check.cjs
 * Creates no records except 20 on-demand crowd predictions (normal history rows, source ON_DEMAND).
 */
const { Client } = require('@stomp/stompjs');
const SockJS = require('sockjs-client');

const API = process.env.API_URL || 'http://localhost:8080/api';
const WS = process.env.WS_URL || 'http://localhost:8080/ws';
const USER = { email: 'user@transport.com', password: 'user123' };
const ADMIN = { email: 'admin@transport.com', password: 'admin123' };

const results = [];
function check(id, name, ok, detail = '') {
  results.push({ id, name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${id}] ${name}${detail ? ` — ${detail}` : ''}`);
}

async function call(method, path, { token, body, headers = {} } = {}) {
  const started = performance.now();
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, json, ms: performance.now() - started, headers: res.headers };
}

const login = async (u) => (await call('POST', '/auth/login', { body: u })).json.token;
const pct = (arr, p) => { const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))]; };

function wsClient(token, seconds) {
  return new Promise((resolve) => {
    const msgs = [];
    const client = new Client({
      webSocketFactory: () => new SockJS(WS),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 0,
      debug: () => {},
      onConnect: () => {
        client.subscribe('/topic/location', (m) => msgs.push({ at: Date.now(), body: JSON.parse(m.body) }));
        setTimeout(() => { client.deactivate(); resolve({ connected: true, msgs }); }, seconds * 1000);
      },
      onStompError: () => resolve({ connected: false, msgs }),
      onWebSocketClose: () => resolve({ connected: msgs.length > 0, msgs }),
    });
    client.activate();
  });
}

(async () => {
  const user = await login(USER);
  const admin = await login(ADMIN);
  check('FR1', 'login returns a JWT for passenger and admin', !!user && !!admin);

  // ---------- security ----------
  console.log('\n== Security (SRS 3.6.3)');
  check('SEC', 'protected data without login -> 401', (await call('GET', '/buses')).status === 401);
  check('SEC', 'passenger cannot read admin data -> 403', (await call('GET', '/admin/users', { token: user })).status === 403);
  check('SEC', 'passenger cannot add a bus -> 403', (await call('POST', '/buses', { token: user, body: { busNumber: 'X-1', capacity: 40 } })).status === 403);
  check('SEC', 'passenger cannot delete a route -> 403', (await call('DELETE', '/routes/1', { token: user })).status === 403);
  const tampered = user.slice(0, -4) + (user.endsWith('AAAA') ? 'BBBB' : 'AAAA');
  check('SEC', 'tampered JWT -> 401', (await call('GET', '/buses', { token: tampered })).status === 401);
  const inj = await call('POST', '/auth/login', { body: { email: "admin@transport.com' OR '1'='1", password: "' OR '1'='1" } });
  check('SEC', "SQL-injection login (' OR '1'='1) rejected, no token", [400, 401].includes(inj.status) && !inj.json?.token, `${inj.status} ${inj.json?.message}`);
  const wrong = await call('POST', '/auth/login', { body: { email: USER.email, password: 'wrong-password' } });
  check('SEC', 'wrong password -> 401 without details', wrong.status === 401 && !/stack|exception/i.test(JSON.stringify(wrong.json)), wrong.json?.message);
  const evil = await fetch(`${API}/buses`, { method: 'OPTIONS', headers: { Origin: 'http://evil.example', 'Access-Control-Request-Method': 'GET' } });
  check('SEC', 'CORS: foreign web origin is not allowed', !evil.headers.get('access-control-allow-origin'), `status ${evil.status}`);
  const good = await fetch(`${API}/buses`, { method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'GET' } });
  check('SEC', 'CORS: the app origin is allowed', good.headers.get('access-control-allow-origin') === 'http://localhost:5173');

  // ---------- validation / integrity (nothing is created) ----------
  console.log('\n== Validation and integrity (SRS 3.5 d, 3.6.1)');
  const neg = await call('POST', '/buses', { token: admin, body: { busNumber: 'ACC-TEST', capacity: -5 } });
  check('DB', 'invalid capacity rejected -> 400', neg.status === 400, neg.json?.message);
  const seats = await call('POST', '/buses', { token: admin, body: { busNumber: 'ACC-TEST', capacity: 40, seatCount: 60 } });
  check('DB', 'seat count above capacity rejected -> 400', seats.status === 400, seats.json?.message);
  const dup = await call('POST', '/buses', { token: admin, body: { busNumber: 'BUS-101', capacity: 50, status: 'ACTIVE' } });
  check('DB', 'duplicate bus number rejected -> 409', dup.status === 409, dup.json?.message);
  const reg = await call('POST', '/auth/register', { body: { name: 'A', email: 'not-an-email', password: '1' } });
  check('DB', 'invalid registration rejected -> 400', reg.status === 400, Object.keys(reg.json?.validationErrors || {}).join(', '));
  const missing = await call('GET', '/buses/999999', { token: user });
  check('REL', 'unknown id -> 404 JSON error (no crash)', missing.status === 404 && !!missing.json?.message);
  const badJson = await fetch(`${API}/crowd/predict`, { method: 'POST', headers: { Authorization: `Bearer ${user}`, 'Content-Type': 'application/json' }, body: '{bad json' });
  check('REL', 'malformed JSON -> 400', badJson.status === 400);

  // ---------- response times (single user) ----------
  console.log('\n== Response time, single user (SRS dynamic: 2-3 s)');
  const routes = (await call('GET', '/routes', { token: user })).json;
  const route = routes.find((r) => (r.stops || []).length >= 2) || routes[0];
  const stops = route.stops || [];
  const from = encodeURIComponent(stops[0]?.name || route.source);
  const to = encodeURIComponent(stops[stops.length - 1]?.name || route.destination);
  const endpoints = [
    ['GET', '/buses', user], ['GET', '/routes', user], ['GET', '/location/live', user], ['GET', '/occupancy', user],
    ['GET', '/dashboard', user], ['GET', `/crowd/forecast/${route.id}`, user], ['GET', `/travel/search?from=${from}&to=${to}`, user],
    ['GET', '/crowd/history?size=20', admin], ['GET', '/admin/analytics', admin],
  ];
  let worst = 0;
  for (const [m, p, t] of endpoints) {
    const times = [];
    let status = 0;
    for (let i = 0; i < 10; i++) { const r = await call(m, p, { token: t }); times.push(r.ms); status = r.status; }
    worst = Math.max(worst, pct(times, 95));
    console.log(`  ${m} ${p.split('?')[0].padEnd(28)} status ${status}  median ${pct(times, 50).toFixed(0)} ms  p95 ${pct(times, 95).toFixed(0)} ms`);
    if (status !== 200) check('PERF', `${p} answers 200`, false, `status ${status}`);
  }
  check('PERF', 'every page API p95 under 2 s (single user)', worst < 2000, `worst p95 ${worst.toFixed(0)} ms`);
  const pred = await call('POST', '/crowd/predict', { token: user, body: { busId: 1, travelTime: '2026-10-26T08:30:00' } });
  check('FR6', 'ML crowd prediction answered quickly', pred.status === 201 && pred.ms < 2000, `${pred.json?.crowdLevel} in ${pred.ms.toFixed(0)} ms`);

  // ---------- simultaneous users ----------
  console.log('\n== Simultaneous users (SRS static: multiple users; dynamic: normal load without failure)');
  const USERS = 50;
  const SECONDS = 30;
  const deadline = Date.now() + SECONDS * 1000;
  const lat = [];
  let errors = 0;
  const mix = endpoints.filter(([, , t]) => t === user);
  await Promise.all(Array.from({ length: USERS }, async (_, i) => {
    let k = i;
    while (Date.now() < deadline) {
      const [m, p] = mix[k++ % mix.length];
      try { const r = await call(m, p, { token: user }); lat.push(r.ms); if (r.status !== 200) errors++; } catch { errors++; }
    }
  }));
  check('PERF', `${USERS} simultaneous users for ${SECONDS} s without errors`, errors === 0, `${lat.length} requests, ${errors} errors`);
  check('PERF', 'p95 response time under load within 3 s', pct(lat, 95) < 3000,
    `median ${pct(lat, 50).toFixed(0)} ms, p95 ${pct(lat, 95).toFixed(0)} ms, max ${Math.max(...lat).toFixed(0)} ms, ${(lat.length / SECONDS).toFixed(0)} req/s`);
  const burst = await Promise.all(Array.from({ length: 20 }, (_, i) =>
    call('POST', '/crowd/predict', { token: user, body: { busId: 1 + (i % 4), travelTime: `2026-10-26T${String(6 + (i % 14)).padStart(2, '0')}:00:00` } })));
  check('FR6', '20 simultaneous ML predictions succeed (201 Created)', burst.every((r) => r.status === 201),
    `p95 ${pct(burst.map((r) => r.ms), 95).toFixed(0)} ms; statuses ${[...new Set(burst.map((r) => r.status))].join(',')}`);

  // ---------- WebSocket: many clients, update interval ----------
  console.log('\n== Real-time updates (FR3, FR4; ~5 s interval)');
  const clients = await Promise.all(Array.from({ length: 20 }, () => wsClient(user, 22)));
  const connected = clients.filter((c) => c.connected && c.msgs.length > 0).length;
  check('FR4', '20 simultaneous WebSocket clients all receive live positions', connected === 20, `${connected}/20 received messages`);
  const gaps = [];
  for (const c of clients.slice(0, 1)) {
    const byBus = {};
    for (const m of c.msgs) (byBus[m.body.busId] ||= []).push(m.at);
    for (const times of Object.values(byBus)) for (let i = 1; i < times.length; i++) gaps.push((times[i] - times[i - 1]) / 1000);
  }
  const medGap = pct(gaps, 50);
  check('FR3', 'each bus is updated about every 5 seconds', medGap >= 4 && medGap <= 6.5, `median gap ${medGap?.toFixed(2)} s over ${gaps.length} intervals`);
  const sample = clients[0].msgs.at(-1)?.body;
  check('FR3', 'positions are labelled as simulated GPS', sample?.source === 'SIMULATED', `source ${sample?.source}`);
  const noTok = await new Promise((resolve) => {
    const c = new Client({ webSocketFactory: () => new SockJS(WS), reconnectDelay: 0, debug: () => {},
      onConnect: () => { c.deactivate(); resolve(true); }, onStompError: () => resolve(false), onWebSocketClose: () => resolve(false) });
    c.activate();
  });
  check('SEC', 'WebSocket without a token is refused', noTok === false);

  const passed = results.filter((r) => r.ok).length;
  console.log(`\n${passed}/${results.length} API acceptance checks passed`);
  process.exit(passed === results.length ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
