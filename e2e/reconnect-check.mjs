/*
 * WebSocket disconnect / reconnect check. Opens Live Tracking and samples, every 2 s, the connection
 * badge and the position of the first bus marker. Stop the backend while it runs, then start it again.
 *   node e2e/reconnect-check.mjs [seconds]
 * Expected: "Live updates" -> "Reconnecting…" while the backend is down -> "Live updates" again,
 * markers moving again, and no page reload or new login.
 */
import { chromium } from 'playwright-core';

const APP = process.env.APP_URL || 'http://localhost:5173';
const seconds = Number(process.argv[2] || 100);
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1366, height: 860 } });
page.setDefaultTimeout(15000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
let navigations = 0;
page.on('framenavigated', (f) => { if (f === page.mainFrame()) navigations++; });
await page.goto(`${APP}/login`);
await page.fill('#email', 'user@transport.com');
await page.fill('#password', 'user123');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/);
await page.goto(`${APP}/tracking`);
await page.waitForSelector('.bus-pin');
const navBefore = navigations;
const statuses = [];
let lastPos = null;
for (let t = 0; t < seconds; t += 2) {
  const status = await page.locator('[role=status].badge').first().innerText().catch(() => '?');
  const pos = await page.locator('.leaflet-marker-icon.bus-marker').first().evaluate((e) => e.style.transform).catch(() => null);
  const moved = lastPos !== null && pos !== lastPos;
  lastPos = pos;
  statuses.push(status);
  console.log(`${String(t).padStart(3)}s  ${status.padEnd(24)} marker ${moved ? 'moved' : 'still'}`);
  await page.waitForTimeout(2000);
}
const sawReconnecting = statuses.some((s) => s.startsWith('Reconnecting'));
const endsLive = statuses.slice(-5).some((s) => s.startsWith('Live'));
console.log(`reconnecting shown: ${sawReconnecting}; live again at the end: ${endsLive}; page reloads: ${navigations - navBefore}; page errors: ${errors.length}`);
await browser.close();
process.exit(sawReconnecting && endsLive && navigations === navBefore && !errors.length ? 0 : 1);
