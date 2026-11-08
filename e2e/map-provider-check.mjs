/*
 * Checks the map provider fallback. Start a dev server with VITE_MAP_PROVIDER=google and either no
 * VITE_GOOGLE_MAPS_API_KEY or an invalid one (backend CORS must allow its origin), then:
 *   node e2e/map-provider-check.mjs <screenshot.png>      (APP_URL defaults to http://localhost:5174)
 * Expected: a notice explaining the problem and the OpenStreetMap map with bus markers.
 */
import { chromium } from 'playwright-core';
const b = await chromium.launch({ channel: 'chrome' });
const p = await b.newPage({ viewport: { width: 1366, height: 860 } });
await p.goto(`${process.env.APP_URL || 'http://localhost:5174'}/login`);
await p.fill('#email', 'user@transport.com'); await p.fill('#password', 'user123'); await p.click('button[type=submit]');
await p.waitForURL(/dashboard/);
await p.goto(`${process.env.APP_URL || 'http://localhost:5174'}/tracking`);
await p.waitForTimeout(9000);
console.log('notice:', (await p.locator('.map-overlay.bottom-left').allInnerTexts()).join(' | ') || '(none)');
console.log('leaflet map rendered:', await p.locator('.leaflet-container').count() > 0, '| bus markers:', await p.locator('.bus-pin').count());
await p.screenshot({ path: process.argv[2] });
await b.close();
