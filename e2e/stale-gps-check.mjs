/*
 * Stale-location check. Opens Live Tracking with BUS-101 selected and samples every 3 s:
 * connection badge, number of faded (stale) markers, the per-bus "older than 30 seconds" warning and the
 * "No position updates received" banner. Stop the backend for > 30 s while it runs, then start it again.
 *   node e2e/stale-gps-check.mjs [seconds]
 */
import { chromium } from 'playwright-core';

const APP = process.env.APP_URL || 'http://localhost:5173';
const seconds = Number(process.argv[2] || 120);
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
page.setDefaultTimeout(15000);
await page.goto(`${APP}/login`);
await page.fill('#email', 'user@transport.com');
await page.fill('#password', 'user123');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/);
await page.goto(`${APP}/tracking`);
await page.waitForSelector('.bus-pin');
await page.selectOption('select[aria-label="Filter by status"]', { label: 'Active' });
await page.click('.list-item >> nth=0');
await page.waitForSelector('text=Last location');
let sawStale = false, sawWarning = false, sawBanner = false, recovered = false;
// Buses that never report (e.g. demo buses without a route) are legitimately stale from the start.
const baseline = await page.locator('.bus-pin.stale').count();
for (let t = 0; t < seconds; t += 3) {
  const badge = await page.locator('[role=status].badge').first().innerText().catch(() => '?');
  const total = await page.locator('.bus-pin').count();
  const stale = await page.locator('.bus-pin.stale').count();
  const warning = await page.locator('text=more than 30 seconds old').count();
  const banner = await page.locator('text=No position updates received').count();
  if (stale > baseline) sawStale = true;
  if (warning > 0) sawWarning = true;
  if (banner > 0) sawBanner = true;
  if (sawStale && stale <= baseline && badge.startsWith('Live')) recovered = true;
  console.log(`${String(t).padStart(3)}s  ${badge.padEnd(16)} stale markers ${stale}/${total}  bus warning ${warning ? 'yes' : 'no '}  banner ${banner ? 'yes' : 'no'}`);
  await page.waitForTimeout(3000);
}
console.log(`already stale at start: ${baseline}; faded markers: ${sawStale}; bus warning: ${sawWarning}; banner: ${sawBanner}; recovered: ${recovered}`);
await browser.close();
process.exit(sawStale && sawWarning && recovered ? 0 : 1);
