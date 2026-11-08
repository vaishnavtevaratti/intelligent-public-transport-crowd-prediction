/*
 * Start / stop a passenger counter from the web app (Admin > Occupancy & sensors > Counter control).
 * Needs the backend, the frontend and the local counter agent (python vision-service/agent.py).
 * Uses a TEST VIDEO only — it never selects a camera.
 *   node e2e/counter-control-check.mjs [sensorCode] [seconds]
 * Prints what the page showed while the counter ran; exit code 0 when start, live reports and stop all worked.
 */
import { chromium } from 'playwright-core';

const APP = process.env.APP_URL || 'http://localhost:5173';
const ADMIN = { email: process.env.ADMIN_EMAIL || 'admin@transport.com', password: process.env.ADMIN_PASSWORD || 'admin123' };
const sensor = process.argv[2] || 'CAM-BUS101-DOOR';
const seconds = Number(process.argv[3] || 40);
const VIDEO = 'test_videos/vtest.avi';

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(15000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`); };

await page.goto(`${APP}/login`);
await page.fill('#email', ADMIN.email);
await page.fill('#password', ADMIN.password);
await page.click('button[type=submit]');
await page.waitForURL(/admin|dashboard/);
await page.goto(`${APP}/admin/occupancy`);

const control = page.locator('.card', { has: page.getByText('Counter control', { exact: true }) });
const row = control.locator('tr', { has: page.locator(`strong:text-is("${sensor}")`) });
const sensorRow = page.locator('.card', { has: page.getByText('Cameras & sensors', { exact: true }) })
  .locator('tr', { has: page.locator(`strong:text-is("${sensor}")`) });
await row.waitFor();
const state = () => row.locator('.badge').first().innerText();
check('agent reachable, sensor listed', true, `state ${await state()}`);

const reportBefore = (await sensorRow.innerText()).match(/report #(\d+)/)?.[1];
await row.getByRole('button', { name: `Start counter ${sensor}` }).click();
await page.selectOption('#ctrSource', VIDEO);
await page.selectOption('#ctrMode', 'door');
const consentShown = await page.locator('#ctrConsent').count();
check('no consent checkbox for a test video', consentShown === 0);
await page.selectOption('#ctrSource', '0');
check('camera source requires consent (Start disabled)', await page.locator('button[form=counter-form]').isDisabled());
await page.selectOption('#ctrSource', VIDEO);   // back to the video: the camera is never started by this test
await page.click('button[form=counter-form]');
await page.waitForFunction((el) => el.innerText.includes('running'), await row.elementHandle());
check('state RUNNING after Start', (await state()) === 'running');
check('Start button replaced by Stop', await row.getByRole('button', { name: `Stop counter ${sensor}` }).isVisible());

// Live view (opens automatically after Start): annotated picture + numbers, polled through the backend
const view = control.locator('.live-view');
await view.locator('.live-frame img').waitFor({ timeout: 40000 });
const src = await view.locator('.live-frame img').getAttribute('src');
check('live annotated picture shown in the page', src?.startsWith('blob:'), 'frames fetched through the backend, kept in memory');
await page.waitForFunction(() => /\d+ in · \d+ out/.test(document.querySelector('.live-view')?.innerText || ''), null, { timeout: 30000 });
const metricText = async (label) => (await view.locator('.metric', { hasText: label }).locator('.value').innerText()).trim();
const visibleNow = await metricText('People visible now');
check('people visible now shown as a number', /^\d+$/.test(visibleNow), visibleNow);
// wait for the first crossing in the video (time depends on how fast this computer runs the detector)
await page.waitForFunction(() => {
  const m = (document.querySelector('.live-view')?.innerText || '').match(/(\d+) in · (\d+) out/);
  return m && Number(m[1]) + Number(m[2]) > 0;
}, null, { timeout: 60000 }).catch(() => {});
const before = await metricText('Crossings this session');
const [inBefore, outBefore] = before.match(/\d+/g).map(Number);
check('crossings this session counted (door line)', inBefore + outBefore > 0, before);
await control.getByRole('button', { name: 'Reset session counts' }).click();
await page.waitForSelector('text=Session counts reset to zero');
await page.waitForTimeout(2000);
const after = await metricText('Crossings this session');
const [inAfter, outAfter] = after.match(/\d+/g).map(Number);
check('Reset sets the session counts back (then counting continues)', inAfter + outAfter < inBefore + outBefore, `${before} -> ${after}`);
const onboardText = await metricText('Estimated onboard (backend)');
check('estimated onboard shown separately from visible people and crossings', /\d/.test(onboardText), onboardText);

const samples = [];
for (let t = 0; t <= seconds; t += 5) {
  const health = (await sensorRow.locator('td').nth(3).innerText()).replace(/\s+/g, ' ');
  const report = (await sensorRow.innerText()).match(/report #(\d+)/)?.[1];
  const log = (await row.locator('td').nth(3).innerText()).slice(0, 110);
  samples.push({ t, health, report });
  console.log(`  ${t}s  health: ${health} | report #${report} | agent log: ${log}`);
  if (t < seconds) await page.waitForTimeout(5000);
}
const reports = samples.map((s) => Number(s.report)).filter(Number.isFinite);
check('new reports arrive while running (no page reload)', reports.length > 1 && Math.max(...reports) > Number(reportBefore || 0),
  `report #${reportBefore} -> #${Math.max(...reports)}`);
check('sensor shown online while running', samples.slice(2).some((s) => /online|ok/i.test(s.health)), samples.at(-1).health);

await row.getByRole('button', { name: `Stop counter ${sensor}` }).click();
const stopStarted = Date.now();
await page.waitForFunction((el) => el.innerText.includes('stopped'), await row.elementHandle(), { timeout: 25000 });
check('state STOPPED after Stop', (await state()) === 'stopped', `${((Date.now() - stopStarted) / 1000).toFixed(1)} s`);
const exitInfo = await row.innerText();
check('clean exit (no exit-code warning)', !/exit code/.test(exitInfo), exitInfo.replace(/\s+/g, ' ').slice(0, 160));
check('no browser console errors', errors.length === 0, errors.slice(0, 3).join(' | '));

await browser.close();
console.log(`${results.filter(Boolean).length}/${results.length} checks passed`);
process.exit(results.every(Boolean) ? 0 : 1);
