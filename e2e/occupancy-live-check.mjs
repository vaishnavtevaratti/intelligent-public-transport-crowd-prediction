/*
 * Verifies that live passenger counts reach the browser WITHOUT a page reload:
 * opens Live Tracking, selects a bus, and samples the displayed onboard estimate for N seconds.
 * Run while vision-service/run_counter.py is reporting for that bus.
 *   node e2e/occupancy-live-check.mjs [busNumber] [seconds]
 */
import { chromium } from 'playwright-core';

const APP = process.env.APP_URL || 'http://localhost:5173';
const bus = process.argv[2] || 'BUS-101';
const seconds = Number(process.argv[3] || 50);
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1366, height: 860 } });
page.setDefaultTimeout(10000);
let navigations = 0;
page.on('framenavigated', (f) => { if (f === page.mainFrame()) navigations++; });
await page.goto(`${APP}/login`);
await page.fill('#email', 'user@transport.com');
await page.fill('#password', 'user123');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/);
await page.goto(`${APP}/tracking`);
await page.waitForSelector('.bus-pin');
await page.selectOption('select[aria-label="Filter by bus"]', { label: bus });
await page.click('.list-item >> nth=0');
await page.waitForSelector('text=Passengers onboard');
const navBefore = navigations;
const samples = [];
for (let t = 0; t < seconds; t += 5) {
  const text = await page.locator('span:has-text("total capacity")').first().innerText({ timeout: 2000 }).catch(() => 'n/a');
  const updated = await page.locator('div.small:has-text("· updated")').first().innerText({ timeout: 2000 }).catch(() => '');
  samples.push(`${t}s: ${text.replace(/\s+/g, ' ')} | ${updated.split('·').pop().trim()}`);
  await page.waitForTimeout(5000);
}
samples.forEach((s) => console.log(s));
const distinct = new Set(samples.map((s) => s.split('|')[0].split(': ')[1])).size;
console.log(`page reloads during sampling: ${navigations - navBefore}; distinct onboard values shown: ${distinct}`);
await browser.close();
process.exit(navigations === navBefore && distinct > 1 ? 0 : 1);
