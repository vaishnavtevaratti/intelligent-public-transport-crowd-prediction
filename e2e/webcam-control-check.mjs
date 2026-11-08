/*
 * Laptop-webcam counting through the web app: Admin > Occupancy & sensors > Counter control > Start
 * with "Camera 0 (laptop webcam)", consent ticked, cabin mode; samples the page and the occupancy API,
 * then presses Stop. TURNS THE CAMERA ON, so it only runs with the explicit flag:
 *   node e2e/webcam-control-check.mjs --i-have-consent [sensorCode] [seconds]
 * Use only when everyone in view has agreed to be counted. Nothing is recorded; only counts are stored.
 */
import { chromium } from 'playwright-core';

const args = process.argv.slice(2);
if (!args.includes('--i-have-consent')) {
  console.error('Refusing to turn on the camera: pass --i-have-consent once everyone in view has agreed.');
  process.exit(2);
}
const [sensor = 'CAM-WEBCAM-CABIN', secondsArg = '60'] = args.filter((a) => !a.startsWith('--'));
const seconds = Number(secondsArg);
const APP = process.env.APP_URL || 'http://localhost:5173';
const API = process.env.API_URL || 'http://localhost:8080/api';
const ADMIN = { email: process.env.ADMIN_EMAIL || 'admin@transport.com', password: process.env.ADMIN_PASSWORD || 'admin123' };

const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`); };
const token = (await (await fetch(`${API}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ADMIN) })).json()).token;
const api = async (p) => (await fetch(`${API}${p}`, { headers: { Authorization: `Bearer ${token}` } })).json();
const sensorInfo = async () => (await api('/admin/sensors')).find((s) => s.sensorCode === sensor);
const busId = (await sensorInfo()).busId;

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(20000);
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(`${APP}/login`);
await page.fill('#email', ADMIN.email);
await page.fill('#password', ADMIN.password);
await page.click('button[type=submit]');
await page.waitForURL(/admin|dashboard/);
await page.goto(`${APP}/admin/occupancy`);
const control = page.locator('.card', { has: page.getByText('Counter control', { exact: true }) });
const row = control.locator('tr', { has: page.locator(`strong:text-is("${sensor}")`) });
await row.getByRole('button', { name: `Start counter ${sensor}` }).click();
await page.selectOption('#ctrSource', '0');
await page.selectOption('#ctrMode', 'cabin');
check('Start is disabled until consent is ticked', await page.locator('button[form=counter-form]').isDisabled());
await page.check('#ctrConsent');
await page.click('button[form=counter-form]');
await page.waitForFunction((el) => el.innerText.includes('running'), await row.elementHandle());
const startedAt = Date.now();
check('webcam counter RUNNING after Start', true);
// in-page live view (numbers only are read; this script never takes a screenshot of the webcam)
const view = control.locator('.live-view');
await view.locator('.live-frame img').waitFor({ timeout: 45000 });
check('live annotated webcam picture shown in the admin page', (await view.locator('.live-frame img').getAttribute('src'))?.startsWith('blob:'));
const visibleText = (await view.locator('.metric', { hasText: 'People visible now' }).locator('.value').innerText()).trim();
check('people visible now shown', /^\d+$/.test(visibleText) || visibleText === '—', visibleText);
check('no door crossings panel in cabin mode', (await view.locator('.metric', { hasText: 'Crossings this session' }).count()) === 0);

const samples = [];
while (Date.now() - startedAt < seconds * 1000) {
  await page.waitForTimeout(5000);
  const s = await sensorInfo();
  const o = await api(`/occupancy/${busId}`);
  const sample = { t: Math.round((Date.now() - startedAt) / 1000), health: s.online ? s.lastHealth : 'OFFLINE', msg: s.lastMessage, mode: s.lastMode,
    visible: o.lastVisibleCount, onboard: o.estimatedOnboard, lower: o.onboardIsLowerBound, seats: o.availableSeats, status: o.dataStatus };
  samples.push(sample);
  console.log(`  ${String(sample.t).padStart(3)}s  health ${sample.health}${sample.msg ? ` (${sample.msg})` : ''} | visible ${sample.visible ?? '—'} | onboard ${sample.onboard ?? '—'}${sample.lower ? ' (lower bound)' : ''} | seats ${sample.seats ?? 'unknown'} | ${sample.status} ${sample.mode ?? ''}`);
}
const live = samples.filter((s) => s.health !== 'OFFLINE');
check('reports arrived from the webcam (sensor online)', live.length > 0, `${live.length}/${samples.length} samples online`);
check('reports labelled WEBCAM_DEMO (not a bus camera)', live.some((s) => s.mode === 'WEBCAM_DEMO'));
check('seats stay unknown (a camera cannot measure seats)', samples.every((s) => s.seats == null));

await row.getByRole('button', { name: `Stop counter ${sensor}` }).click();
await page.waitForFunction((el) => el.innerText.includes('stopped'), await row.elementHandle(), { timeout: 25000 });
const end = (await row.innerText()).replace(/\s+/g, ' ');
check('Stop -> STOPPED, camera released, clean exit', !/exit code/.test(end), end.slice(0, 170));
check('no page errors', errors.length === 0, errors.slice(0, 2).join(' | '));
const counts = live.map((s) => s.visible).filter((v) => v != null);
console.log(`visible-person counts reported: ${counts.join(', ') || 'none'}`);
await browser.close();
console.log(`${results.filter(Boolean).length}/${results.length} checks passed`);
process.exit(results.every(Boolean) ? 0 : 1);
