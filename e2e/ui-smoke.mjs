/*
 * Browser end-to-end check of the real application (frontend + backend + MySQL + ML service).
 * Uses the locally installed Google Chrome through playwright-core.
 *
 * Usage (all services running):  node e2e/ui-smoke.mjs [screenshotDir]
 * Env: APP_URL (default http://localhost:5173), USER_EMAIL/USER_PASSWORD, ADMIN_EMAIL/ADMIN_PASSWORD
 *
 * It creates a temporary passenger account and a temporary bus, and deletes both at the end.
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const APP = process.env.APP_URL || 'http://localhost:5173';
const ADMIN = { email: process.env.ADMIN_EMAIL || 'admin@transport.com', password: process.env.ADMIN_PASSWORD || 'admin123' };
const shotDir = process.argv[2] || 'e2e/screenshots';
fs.mkdirSync(shotDir, { recursive: true });

const results = [];
const consoleErrors = [];
const stamp = Date.now().toString().slice(-6);
const tempUser = { name: 'E2E Passenger', email: `e2e${stamp}@example.com`, password: 'e2epass123' };
const tempBus = `E2E-${stamp}`;

async function step(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`PASS  ${name}`);
  } catch (err) {
    results.push({ name, ok: false, error: err.message.split('\n')[0] });
    console.log(`FAIL  ${name}: ${err.message.split('\n')[0]}`);
  }
}

function expect(condition, message) {
  if (!condition) throw new Error(message);
}

async function login(page, { email, password }) {
  await page.goto(`${APP}/login`);
  await page.fill('#email', email);
  await page.fill('#password', password);
  await page.click('button[type=submit]');
  await page.waitForURL(/\/(dashboard|admin)/, { timeout: 15000 });
}

async function logout(page) {
  await page.click('button[aria-label="Log out"]');
  await page.waitForURL(/\/login/);
}

// The occupancy steps need live camera counts: start the TEST-VIDEO counter for BUS-101 through the
// counter agent (python vision-service/agent.py) unless one is already running. It is stopped at the end.
const API = process.env.API_URL || 'http://localhost:8080/api';
const apiAdmin = await (await fetch(`${API}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ADMIN) })).json();
const adminHeaders = { Authorization: `Bearer ${apiAdmin.token}`, 'Content-Type': 'application/json' };
const sensorOnline = async () => (await (await fetch(`${API}/admin/sensors`, { headers: adminHeaders })).json())
  .some((x) => x.sensorCode === 'CAM-BUS101-DOOR' && x.online && x.lastHealth === 'OK');
const counterRunning = async () => ((await (await fetch(`${API}/admin/counters`, { headers: adminHeaders })).json()).counters || [])
  .some((c) => c.sensorCode === 'CAM-BUS101-DOOR' && c.state === 'RUNNING');
let startedCounter = false;
if (!(await counterRunning().catch(() => false))) {
  const r = await fetch(`${API}/admin/counters/start`, { method: 'POST', headers: adminHeaders,
    body: JSON.stringify({ sensorCode: 'CAM-BUS101-DOOR', source: 'test_videos/vtest.avi', mode: 'door', loop: true }) });
  startedCounter = r.ok;
  console.log(r.ok ? 'Started the test-video counter CAM-BUS101-DOOR for the occupancy steps'
    : `Could not start the test-video counter (${r.status}); the occupancy steps need it`);
  for (let i = 0; i < 40 && r.ok && !(await sensorOnline()); i++) await new Promise((res) => setTimeout(res, 1000));
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1366, height: 860 } });
const page = await context.newPage();
page.on('console', (msg) => {
  // 401/409 responses are produced on purpose by the negative login/registration checks.
  if (msg.type() === 'error' && !/tile\.openstreetmap|ERR_NETWORK_CHANGED|favicon|status of (401|409)/.test(msg.text())) consoleErrors.push(`${page.url()} :: ${msg.text()}`);
});
page.on('pageerror', (err) => consoleErrors.push(`${page.url()} :: PAGE ERROR ${err.message}`));

await step('Login form validates empty fields', async () => {
  await page.goto(`${APP}/login`);
  await page.click('button[type=submit]');
  await page.waitForSelector('text=Enter a valid email address');
  await page.screenshot({ path: path.join(shotDir, '01-login.png') });
});

await step('Invalid credentials are rejected', async () => {
  await page.fill('#email', ADMIN.email);
  await page.fill('#password', 'definitely-wrong');
  await page.click('button[type=submit]');
  await page.waitForSelector('text=Invalid email or password', { timeout: 10000 });
  expect(page.url().includes('/login'), 'should stay on login page');
});

await step('Register a new passenger (stored in MySQL) and land on dashboard', async () => {
  await page.goto(`${APP}/register`);
  await page.fill('#name', tempUser.name);
  await page.fill('#email', tempUser.email);
  await page.fill('#password', tempUser.password);
  await page.fill('#confirm', tempUser.password);
  await page.click('button[type=submit]');
  await page.waitForURL(/\/dashboard/, { timeout: 15000 });
  await page.waitForSelector('text=Total buses');
  await page.waitForSelector('.stat-value');
  await page.screenshot({ path: path.join(shotDir, '02-dashboard.png'), fullPage: true });
});

await step('Duplicate registration shows a clear error', async () => {
  await logout(page);
  await page.goto(`${APP}/register`);
  await page.fill('#name', tempUser.name);
  await page.fill('#email', tempUser.email);
  await page.fill('#password', tempUser.password);
  await page.fill('#confirm', tempUser.password);
  await page.click('button[type=submit]');
  await page.waitForSelector('text=An account with this email already exists');
  await login(page, tempUser);
});

await step('Passenger cannot open admin pages (redirected)', async () => {
  await page.goto(`${APP}/admin/buses`);
  await page.waitForURL(/\/dashboard/);
  expect(await page.locator('text=Administration').count() === 0, 'admin nav should be hidden');
});

await step('Live tracking: markers on the map move via WebSocket', async () => {
  await page.goto(`${APP}/tracking`);
  await page.waitForSelector('.leaflet-container');
  await page.waitForSelector('.bus-pin', { timeout: 15000 });
  await page.waitForSelector('text=Live updates', { timeout: 15000 });
  const positions = () => page.$$eval('.leaflet-marker-icon.bus-marker', (els) => els.map((e) => e.style.transform));
  const before = await positions();
  expect(before.length > 0, 'no bus markers rendered');
  await page.waitForTimeout(11000);
  const after = await positions();
  const moved = after.filter((p, i) => p !== before[i]).length;
  expect(moved > 0, `no marker moved in 11 s (${before.length} markers)`);
  console.log(`      ${before.length} markers, ${moved} moved`);
  await page.click('.list-item >> nth=0');
  await page.waitForSelector('text=Last location');
  // direction comes with the REST data, so it is shown at once (not only after the next WebSocket message)
  const direction = await page.locator('dt:has-text("Direction") + dd').innerText();
  expect(direction.startsWith('towards '), `direction not shown on selection (got "${direction}")`);
  await page.screenshot({ path: path.join(shotDir, '03-tracking.png') });
});

await step('Live occupancy: camera estimate (test video) shown with seats unknown', async () => {
  await page.goto(`${APP}/tracking`);
  await page.waitForSelector('.bus-pin', { timeout: 15000 });
  await page.selectOption('select[aria-label="Filter by bus"]', { label: 'BUS-101' });
  await page.click('.list-item >> nth=0');
  await page.waitForSelector('text=Passengers onboard');
  await page.waitForSelector('.card .badge-test', { timeout: 15000 });   // "Test video" label
  await page.waitForSelector('text=Passenger places left (estimate)');
  // seats are either honestly unknown (no seat sensor) or a measured / manually counted "N / 40"
  const seats = (await page.locator('.metric', { hasText: 'Seats available' }).first().innerText()).replace(/\s+/g, ' ');
  expect(/Unknown \(40 seats; no seat sensor\)|\d+ \/ 40/.test(seats), `unexpected seat display: ${seats}`);
  await page.screenshot({ path: path.join(shotDir, '03b-occupancy.png') });
});

await step('Crowd prediction from the ML model is shown and stored', async () => {
  await page.goto(`${APP}/crowd`);
  await page.waitForSelector('#bus option', { state: 'attached' });
  await page.waitForSelector('text=Least crowded', { timeout: 15000 });
  await page.click('button:has-text("Predict crowd level")');
  await page.waitForSelector('text=Prediction result', { timeout: 15000 });
  await page.waitForSelector('text=Model probabilities');
  await page.waitForSelector('text=Test macro-F1');
  await page.screenshot({ path: path.join(shotDir, '04-crowd.png'), fullPage: true });
});

await step('Travel planner finds a route with ETA and crowd info', async () => {
  await page.goto(`${APP}/travel`);
  await page.waitForSelector('#from option:nth-child(3)', { state: 'attached' });
  // Kolhapur academic demo network (route D1)
  await page.selectOption('#from', 'Kolhapur Central Bus Stand (CBS)');
  await page.selectOption('#to', 'Shivaji University');
  await page.click('button:has-text("Find buses")');
  await page.waitForSelector('text=Buses on this route', { timeout: 15000 });
  await page.waitForSelector('text=Estimated ride time');
  await page.waitForSelector('text=Road distance · OpenStreetMap');   // distance along the road geometry, not straight lines
  await page.waitForSelector('.leaflet-container path.leaflet-interactive');
  await page.screenshot({ path: path.join(shotDir, '05-travel.png'), fullPage: true });
});

await step('Admin login and analytics overview', async () => {
  await logout(page);
  await login(page, ADMIN);
  await page.goto(`${APP}/admin`);
  await page.waitForSelector('text=System status');
  await page.waitForSelector('text=Online', { timeout: 15000 });
  await page.screenshot({ path: path.join(shotDir, '06-admin.png'), fullPage: true });
});

await step('Admin creates a bus, it persists after refresh, then deletes it', async () => {
  await page.goto(`${APP}/admin/buses`);
  await page.waitForSelector('table');
  await page.click('button:has-text("Add bus")');
  await page.fill('#busNumber', tempBus);
  await page.fill('#capacity', '45');
  await page.fill('#seatCount', '60');
  await page.click('button:has-text("Save bus")');
  await page.waitForSelector('text=Cannot exceed total passenger capacity');   // seats > capacity rejected
  await page.fill('#seatCount', '36');
  await page.selectOption('#routeId', { index: 1 });
  await page.screenshot({ path: path.join(shotDir, '07-bus-form.png') });
  await page.click('button:has-text("Save bus")');
  await page.waitForSelector(`text=Bus ${tempBus} created`);
  await page.reload();
  await page.fill('input[aria-label="Search buses"]', tempBus);
  await page.waitForSelector(`td:has-text("${tempBus}")`);
  await page.click(`button[aria-label="Delete ${tempBus}"]`);
  await page.click('.modal button:has-text("Delete")');
  await page.waitForSelector(`text=Bus ${tempBus} deleted`);
  await page.reload();
  await page.fill('input[aria-label="Search buses"]', tempBus);
  await page.waitForSelector('text=No buses match');
});

await step('Admin occupancy page: sensors online, manual reconciliation, observation log', async () => {
  await page.goto(`${APP}/admin/occupancy`);
  await page.waitForSelector('text=CAM-BUS101-DOOR');
  await page.waitForSelector('text=Online', { timeout: 15000 });
  const row = page.locator('tr', { hasText: 'BUS-101' }).first();
  await row.locator('button:has-text("Reconcile")').click();
  await page.fill('#rcOnboard', '20');
  await page.click('.modal button:has-text("Save count")');
  await page.waitForSelector('text=Explain the correction');            // note is required
  await page.fill('#rcNote', 'E2E test head count');
  await page.click('.modal button:has-text("Save count")');
  await page.waitForSelector('text=BUS-101: onboard set to 20');
  await page.click('button[aria-label="Observation log of BUS-101"]');
  await page.waitForSelector('.modal >> text=manual reconciliation');
  await page.screenshot({ path: path.join(shotDir, '07b-occupancy-admin.png') });
  await page.click('.modal button[aria-label="Close"]');
});

await step('Admin route editor opens with map and stops', async () => {
  await page.goto(`${APP}/admin/routes`);
  await page.waitForSelector('table');
  await page.click('button[aria-label^="Edit "] >> nth=0');
  await page.waitForSelector('.modal .leaflet-container');
  await page.waitForSelector('text=Click the map to add the next stop');
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(shotDir, '08-route-editor.png') });
  await page.click('.modal button:has-text("Cancel")');
});

await step('Admin creates a route by clicking stops on the map, then deletes it', async () => {
  const source = `E2E Source ${stamp}`;
  await page.click('button:has-text("Add route")');
  await page.fill('#source', source);
  await page.fill('#destination', `E2E Destination ${stamp}`);
  const map = page.locator('.modal .leaflet-container');
  await map.waitFor();
  await page.waitForTimeout(500);
  const box = await map.boundingBox();
  await page.mouse.click(box.x + box.width * 0.3, box.y + box.height * 0.5);
  await page.mouse.click(box.x + box.width * 0.6, box.y + box.height * 0.4);
  await page.waitForSelector('text=Stops (2)');
  await page.click('button:has-text("Save route")');
  await page.waitForSelector(`text=E2E Source ${stamp} → E2E Destination ${stamp}`, { timeout: 10000 });
  await page.fill('input[aria-label="Search routes"]', source);
  await page.waitForSelector('td:has-text("km")');
  await page.click('button[aria-label^="Delete E2E Source"]');
  await page.click('.modal button:has-text("Delete")');
  await page.waitForSelector('text=deleted');
});

await step('Admin deletes the temporary passenger account', async () => {
  await page.goto(`${APP}/admin/users`);
  await page.fill('input[aria-label="Search users"]', tempUser.email);
  await page.click(`button[aria-label="Delete ${tempUser.name}"]`);
  await page.click('.modal button:has-text("Delete account")');
  await page.waitForSelector(`text=${tempUser.name} deleted`);
});

await step('Prediction history page shows paged records', async () => {
  await page.goto(`${APP}/admin/predictions`);
  await page.waitForSelector('text=predictions · page 1', { timeout: 15000 });
});

await step('Mobile layout (390px): no horizontal scrolling, menu works', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const route of ['/dashboard', '/tracking', '/crowd', '/travel', '/admin/buses']) {
    await page.goto(`${APP}${route}`);
    await page.waitForTimeout(1500);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow <= 1, `${route} overflows horizontally by ${overflow}px`);
  }
  await page.goto(`${APP}/tracking`);
  await page.waitForSelector('.bus-pin');
  await page.screenshot({ path: path.join(shotDir, '09-mobile-tracking.png'), fullPage: true });
  await page.click('button[aria-label="Open menu"]');
  await page.waitForSelector('.sidebar.open');
  await page.screenshot({ path: path.join(shotDir, '10-mobile-menu.png') });
});

await step('Dark mode toggle applies the dark theme', async () => {
  await page.setViewportSize({ width: 1366, height: 860 });
  await page.goto(`${APP}/dashboard`);
  await page.click('button[aria-label="Switch to dark mode"]');
  expect(await page.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'theme not dark');
  await page.waitForSelector('.stat-value');
  await page.screenshot({ path: path.join(shotDir, '11-dark.png') });
  await page.click('button[aria-label="Switch to light mode"]');
});

if (startedCounter) {
  await fetch(`${API}/admin/counters/CAM-BUS101-DOOR/stop`, { method: 'POST', headers: adminHeaders });
  console.log('Stopped the test-video counter');
}
// Chrome occasionally hangs on close; never let that block the result
const closed = await Promise.race([browser.close().then(() => true), new Promise((res) => setTimeout(() => res(false), 15000))]);
if (!closed) console.log('(browser did not close within 15 s; continuing)');

console.log('\nConsole errors captured:', consoleErrors.length);
consoleErrors.slice(0, 20).forEach((e) => console.log('  ', e));
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} UI checks passed`);
process.exit(failed.length || consoleErrors.length ? 1 : 0);
