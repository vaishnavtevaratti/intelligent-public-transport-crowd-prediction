/*
 * Screenshots of the Kolhapur demo for documentation/visual review (all services running):
 *   node e2e/kolhapur-screens.mjs [outDir]
 * tracking map (all routes), one highlighted route, a travel search; prints what the page shows.
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const APP = process.env.APP_URL || 'http://localhost:5173';
const out = process.argv[2] || 'e2e/screenshots';
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(`${APP}/login`);
await page.fill('#email', 'user@transport.com');
await page.fill('#password', 'user123');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/);
await page.goto(`${APP}/tracking`);
await page.waitForSelector('.bus-pin');
await page.waitForTimeout(2500);
console.log('provider chip:', await page.locator('.map-overlay.top-right').innerText());
console.log('header:', (await page.locator('.page-header p, header p').first().innerText()).slice(0, 140));
console.log('bus markers:', await page.locator('.bus-pin').count(), '| solid route lines:',
  await page.locator('path.leaflet-interactive:not([stroke-dasharray])').count(), '| dashed:', await page.locator('path.leaflet-interactive[stroke-dasharray]').count());
await page.screenshot({ path: path.join(out, 'k1-tracking-kolhapur.png') });
await page.selectOption('select[aria-label="Filter by bus"]', { label: 'BUS-102' });
await page.click('.list-item >> nth=0');
await page.waitForTimeout(1500);
await page.screenshot({ path: path.join(out, 'k2-route-d2.png') });
console.log('selected bus card:', (await page.locator('.card', { hasText: 'Bus BUS-102' }).first().innerText()).replace(/\s+/g, ' ').slice(0, 260));
await page.goto(`${APP}/travel`);
await page.waitForSelector('#from, select, input');
const fromSel = page.locator('#from');
if (await fromSel.count()) {
  await page.selectOption('#from', { label: 'Kolhapur Central Bus Stand (CBS)' }).catch(async () => page.fill('#from', 'Kolhapur Central Bus Stand (CBS)'));
  await page.selectOption('#to', { label: 'Mahalaxmi Temple' }).catch(async () => page.fill('#to', 'Mahalaxmi Temple'));
  await page.click('button[type=submit]');
  await page.waitForSelector('text=Trip distance', { timeout: 15000 });
  await page.waitForTimeout(1500);
  console.log('travel result:', (await page.locator('.card', { hasText: 'Trip distance' }).first().innerText()).replace(/\s+/g, ' ').slice(0, 300));
  await page.screenshot({ path: path.join(out, 'k3-travel.png') });
}
console.log('console errors:', errors.length, errors.slice(0, 3).join(' | '));
await browser.close();
