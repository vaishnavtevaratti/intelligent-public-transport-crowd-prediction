/*
 * Exercises GoogleTransitMap.jsx WITHOUT contacting Google: a recording stub of the google.maps API is
 * installed before the page loads, so the app uses it instead of downloading the real script.
 * This verifies OUR integration code (markers, routes, stops, live position updates, click selection,
 * tooltips). It does NOT verify Google's servers, the API key, billing or tile rendering.
 *
 * Start the frontend with VITE_MAP_PROVIDER=google and any non-empty VITE_GOOGLE_MAPS_API_KEY, then:
 *   node e2e/google-provider-stub-check.mjs
 */
import { chromium } from 'playwright-core';

const APP = process.env.APP_URL || 'http://localhost:5173';

function installStub() {
  const log = { maps: 0, markers: [], polylines: 0, paths: [], setPosition: 0, setTitle: [], fitBounds: 0 };
  window.__gm = log;
  class Listenable {
    constructor() { this.handlers = {}; }
    addListener(name, fn) { this.handlers[name] = fn; return { remove() {} }; }
  }
  class Map extends Listenable { constructor() { super(); log.maps++; } fitBounds() { log.fitBounds++; } }
  class Marker extends Listenable {
    constructor(opts) { super(); this.opts = opts; this.position = opts.position; this.title = opts.title; log.markers.push(this); }
    setPosition(p) { this.position = p; log.setPosition++; }
    setIcon(i) { this.opts.icon = i; }
    setTitle(t) { this.title = t; log.setTitle.push(t); }
    setMap(m) { this.removed = m === null; }
  }
  class Polyline { constructor(opts) { this.opts = opts; log.polylines++; log.paths.push(opts.path.length); } setMap() {} }
  class LatLngBounds { extend() {} }
  window.google = { maps: { Map, Marker, Polyline, LatLngBounds, SymbolPath: { CIRCLE: 0 } } };
}

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1366, height: 860 } });
page.setDefaultTimeout(15000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
const googleRequests = [];
page.on('request', (r) => { if (r.url().includes('maps.googleapis.com')) googleRequests.push(r.url()); });
await page.addInitScript(installStub);
await page.goto(`${APP}/login`);
await page.fill('#email', 'user@transport.com');
await page.fill('#password', 'user123');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/);
await page.goto(`${APP}/tracking`);
// expected marker count = buses in the database (not a fixed number; admins add buses)
const busCount = await page.evaluate(async (api) => {
  const token = localStorage.getItem('token');
  const r = await fetch(`${api}/buses`, { headers: { Authorization: `Bearer ${token}` } });
  return (await r.json()).length;
}, process.env.API_URL || 'http://localhost:8080/api');
await page.waitForFunction(() => window.__gm && window.__gm.markers.length >= 10);

const results = [];
const check = (name, ok, detail) => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` (${detail})` : ''}`); };
const first = await page.evaluate(() => {
  const g = window.__gm;
  const live = g.markers.filter((m) => !m.removed);
  const isBus = (m) => m.opts.icon && m.opts.icon.scale && m.opts.icon.fillColor !== '#fff'; // stops are white
  const buses = live.filter(isBus);
  return { maps: g.maps, polylines: g.polylines, stops: live.filter((m) => !isBus(m)).length, buses: buses.length,
    titles: buses.map((m) => m.title), leaflet: !!document.querySelector('.leaflet-container') };
});
check('Google map created instead of Leaflet', first.maps === 1 && !first.leaflet);
const paths = await page.evaluate(() => window.__gm.paths);
check('route lines follow the road geometry (hundreds of points, not stop-to-stop lines)', paths.length > 0 && paths.every((n) => n > 50),
  `points per line: ${paths.join(', ')}`);
check('route lines drawn', first.polylines >= 8, `${first.polylines} polylines`);
check('stop markers drawn', first.stops >= 40, `${first.stops} stops`);
check('one marker per bus', first.buses === busCount, `${first.buses} bus markers for ${busCount} buses`);
check('bus marker title has bus number and occupancy text', first.titles.some((t) => /BUS-\d+ .*(onboard|sensor|data)/.test(t)), first.titles[0]);

await page.waitForTimeout(12000);
const moved = await page.evaluate(() => window.__gm.setPosition);
check('live WebSocket updates move existing markers (setPosition, no new markers)', moved >= busCount, `${moved} position updates`);
const after = await page.evaluate(() => ({ created: window.__gm.markers.length,
  bus: window.__gm.markers.filter((m) => !m.removed && m.opts.icon && m.opts.icon.scale && m.opts.icon.fillColor !== '#fff').length }));
check('no duplicate bus markers after updates', after.bus === busCount, `${after.bus} bus markers for ${busCount} buses`);
check('overlays not recreated on live updates', after.created === first.buses + first.stops, `${after.created} markers ever created`);

await page.evaluate(() => window.__gm.markers.find((m) => !m.removed && m.title && m.title.startsWith('BUS-101')).handlers.click());
await page.waitForSelector('text=Bus BUS-101');
check('clicking a Google marker opens that bus', true);
check('no request was sent to Google', googleRequests.length === 0, `${googleRequests.length}`);
check('no page errors', errors.length === 0, errors[0]);
await browser.close();
const failed = results.filter((r) => !r).length;
console.log(`${results.length - failed}/${results.length} Google-provider checks passed (stubbed API)`);
process.exit(failed ? 1 : 0);
