/*
 * Browser test of the Instance Labs web UI served by an OpenPnP *simulator* instance
 * (launched by run-webui-sim-test.sh through openpnp-uinav: config copy, simulated controller,
 * simulated cameras, Xvfb). Headless Chromium via Playwright.
 *
 * Checks: every tab renders, STOP is visible on every tab, the Profiles families, adding a pad
 * type from the UI, Start + Home from the UI, pressure on (G-code order), STOP (G-code order,
 * machine stopped, pressure off), the top-camera snapshot, no JS errors.
 *
 * Env: IL_API_URL (http://127.0.0.1:PORT/), PLAYWRIGHT_DIR (dir with node_modules/playwright),
 *      UINAV (uinav CLI), UINAV_SESSION, OUT_DIR (screenshots + results.json).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = process.env.IL_API_URL;
const UINAV = process.env.UINAV;
const SESSION = process.env.UINAV_SESSION;
const OUT = process.env.OUT_DIR;
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail) : ''));
}

/** Simulated-controller G-code since sequence number `since` (all if undefined). */
function gcode(since) {
  const args = ['--session', SESSION, 'gcode', '--json'];
  if (since !== undefined) args.push('--since', String(since));
  return JSON.parse(execFileSync(UINAV, args, { encoding: 'utf8' }));
}
const lines = (g) => g.entries.filter((e) => !e.dir || e.dir === '>').map((e) => e.line.trim());

async function textOf(page, id) {
  return (await page.textContent('#' + id)).trim();
}

/** Polls GET /api/status from Node until pred(status) is true. */
async function waitStatus(page, pred, timeout) {
  const t0 = Date.now();
  let s = null;
  while (Date.now() - t0 < timeout) {
    s = await (await page.request.get(BASE + 'api/status')).json();
    if (pred(s)) return s;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('timeout waiting for the status condition; last: ' + JSON.stringify(s && s.run));
}

async function waitText(page, id, re, timeout) {
  await page.waitForFunction(([i, src]) => new RegExp(src).test(document.getElementById(i).textContent),
    [id, re.source], { timeout });
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    // the page never asks for media; make sure a real camera could not be opened anyway
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(m.text()); });
  page.on('dialog', (d) => d.accept());
  try {
    const resp = await page.goto(BASE);
    check('UI served by OpenPnP', resp.ok(), resp.status());
    await waitText(page, 'pill-engine', /Dispenser/, 20000);
    check('engine pill', /Dispenser ready|Dispenser unavailable/.test(await textOf(page, 'pill-engine')), await textOf(page, 'pill-engine'));

    // ---- tabs render, STOP always visible -------------------------------------------------
    const tabs = [['Run', '#btn-plan'], ['Dispense', '#pressure-state'], ['Calibrate', '#btn-dl-run'], ['Profiles', '#profiles .family']];
    for (const [tab, sel] of tabs) {
      await page.click('.tabs [role=tab][data-tab=' + tab.toLowerCase() + ']');
      await page.waitForSelector(sel, { state: 'visible', timeout: 15000 });
      check('tab ' + tab + ' renders', await page.isVisible(sel));
      check('STOP visible on ' + tab, await page.isVisible('[data-testid=stop]'));
      await page.screenshot({ path: path.join(OUT, 'tab-' + tab.toLowerCase() + '.png'), fullPage: true });
    }
    // the header stays on screen when scrolled
    await page.mouse.wheel(0, 3000);
    const box = await page.locator('[data-testid=stop]').boundingBox();
    check('STOP stays on screen after scrolling', box && box.y >= 0 && box.y < 120, box);

    const fams = await page.$$eval('#profiles .family h2', (els) => els.map((e) => e.textContent));
    const want = ['Chip (2-terminal SMD)', 'Small-outline discretes', 'Leaded ICs', 'No-lead ICs', 'Custom'];
    check('Profiles shows the five families in order', JSON.stringify(fams.slice(0, 5)) === JSON.stringify(want), fams);
    check('0603 row with status', (await page.textContent('tr[data-key="chip-0603"]')).includes('not started'));

    // ---- add a pad type from the UI ---------------------------------------------------------
    await page.click('[data-testid=new-pad-type]');
    await page.waitForSelector('#dlg-padtype[open]');
    await page.selectOption('#pt-family', 'Chip (2-terminal SMD)');
    await page.fill('#pt-name', 'PW 0201 test');
    await page.fill('#pt-w', '0.30');
    await page.fill('#pt-h', '0.35');
    await page.selectOption('#pt-layout', 'two-terminal');
    await page.fill('#pt-pitch', '0.60');
    await page.fill('#pt-notes', 'added by the Playwright sim test');
    await page.click('#pt-save');
    await page.waitForSelector('tr[data-key="chip-pw-0201-test"]', { timeout: 15000 });
    const row = await page.textContent('tr[data-key="chip-pw-0201-test"]');
    check('new pad type appears in Chip family', row.includes('PW 0201 test') && row.includes('not started'), row);
    const api = await page.request.get(BASE + 'api/pad-types/chip-pw-0201-test');
    const pt = await api.json();
    check('new pad type stored as version 1', api.ok() && pt.current.version === 1 && pt.current.padWMm === 0.3, pt.current);
    await page.screenshot({ path: path.join(OUT, 'profiles-after-add.png'), fullPage: true });

    // ---- Start + Home from the UI -----------------------------------------------------------
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    check('Start connects (sim)', true);
    await page.click('#btn-home');
    await waitText(page, 'pill-homed', /^Homed$/, 120000);
    check('Home homes (sim)', true);

    // ---- set up a board run: waste place, needle offset, settings, 0603 profile ---------------
    await page.click('.tabs [role=tab][data-tab=dispense]');
    await page.waitForSelector('tr[data-place="waste"] input[data-axis=x]');
    const waste = page.locator('tr[data-place="waste"]');
    await waste.locator('input[data-axis=x]').fill('100');
    await waste.locator('input[data-axis=y]').fill('120');
    await waste.locator('input[data-axis=z]').fill('10');
    await waste.locator('button[data-action=save]').click();
    await waitStatus(page, (s) => s.dispense.locations.waste && s.dispense.locations.waste.x === 100, 10000);
    check('waste place saved from the Places table', true);
    await page.fill('#needle-offset', '0');
    await page.click('#btn-needle-offset');
    await waitText(page, 'needle-state', /^Touched off/, 10000);
    check('manual needle offset clears I4', true);
    await page.uncheck('#set-optical'); // no dot vision on this branch (contract stub)
    await page.click('#btn-settings');
    await waitText(page, 'toast', /Settings saved/, 10000);
    await page.click('.tabs [role=tab][data-tab=profiles]');
    await page.click('tr[data-key="chip-0603"] button:text-is("Edit")');
    await page.selectOption('#pt-status', 'characterized');
    await page.click('#pt-save');
    await page.waitForSelector('tr[data-key="chip-0603"] .badge.characterized', { timeout: 10000 });
    check('0603 set to characterized from Profiles > Edit (version 2)',
      (await page.textContent('tr[data-key="chip-0603"]')).includes('v2'));

    // ---- plan the real test board (copied into the sim config) --------------------------------
    await page.click('.tabs [role=tab][data-tab=run]');
    const opts = await page.$$eval('#board-select option', (os) => os.map((o) => ({ v: o.value, t: o.textContent })));
    const ftp = opts.find((o) => /\/00-ftp\.board\.xml$/.test(o.v));
    check('test board ftp.board.xml listed', !!ftp, opts);
    await page.selectOption('#board-select', ftp.v);
    await page.fill('#board-x', '120');
    await page.fill('#board-y', '120');
    await page.fill('#board-z', '10');
    await page.fill('#board-rot', '0');
    await page.click('#btn-plan');
    await page.waitForSelector('#plan-svg circle.dot', { timeout: 30000 });
    const nPads = await page.$$eval('#plan-svg rect.pad', (e) => e.length);
    const nDots = await page.$$eval('#plan-svg circle.dot', (e) => e.length);
    check('plan preview: 160 pads drawn', nPads === 160, nPads);
    check('plan preview: 160 dots drawn', nDots === 160, nDots);
    const planText = await page.textContent('#tab-run');
    check('plan passes the interlocks', planText.includes('All interlocks pass'), (await page.textContent('#plan-interlocks')));
    check('plan shows a DPH estimate', /DPH \(estimate\)≤ [0-9]/.test(await page.textContent('#plan-summary')),
      await page.textContent('#plan-summary'));
    await page.screenshot({ path: path.join(OUT, 'run-plan.png'), fullPage: true });

    // ---- run the first 20 dots ---------------------------------------------------------------
    let g0 = gcode().next;
    await page.fill('#run-maxdots', '20');
    await page.click('#btn-run');
    await page.waitForFunction(() => /finished: (OK|ERROR|ABORTED|REFUSED)/.test(document.getElementById('run-live').textContent),
      null, { timeout: 240000 });
    const live = await page.textContent('#run-live');
    check('20-dot run finished OK', /finished: OK/.test(live), live);
    const doneDots = await page.$$eval('#plan-svg circle.dot.done', (e) => e.length);
    check('20 dots marked done in the preview', doneDots === 20, doneDots);
    const st1 = await (await page.request.get(BASE + 'api/status')).json();
    check('last run: 20 of 20 dots, DPH reported', st1.lastRun.dotsDone === 20 && st1.lastRun.dotsPlanned === 20
      && st1.lastRun.dphMotion > 0 && st1.lastRun.dphOverall > 0, st1.lastRun);
    let l = lines(gcode(g0));
    const firstB = l.findIndex((x) => /^G1 B/.test(x));
    check('run: G92 B0 before the first auger move (I2)', l.indexOf('G92 B0') >= 0 && l.indexOf('G92 B0') < firstB, l.slice(0, 20));
    check('run: pump, then valve; valve off, then pump off',
      l.indexOf('M106 P2 S255') < l.indexOf('M106 P3 S255') && l.lastIndexOf('M107 P3') < l.lastIndexOf('M107 P2'));
    check('run: 44 auger moves = 22 dots (prime + test dot + 20) x (feed + suck-back)', l.filter((x) => /^G1 B/.test(x)).length === 44,
      l.filter((x) => /^G1 B/.test(x)).length);
    await page.screenshot({ path: path.join(OUT, 'run-after-20-dots.png'), fullPage: true });
    console.log('     sim run: ' + JSON.stringify({ dphMotion: st1.lastRun.dphMotion, dphOverall: st1.lastRun.dphOverall,
      totalSeconds: st1.lastRun.totalSeconds }) + ' (simulated controller: not a physical DPH)');

    // ---- prime and test dot from the Dispense tab --------------------------------------------
    await page.click('.tabs [role=tab][data-tab=dispense]');
    for (const [btn, label] of [['#btn-prime', 'prime'], ['#btn-testdot', 'test dot']]) {
      await page.waitForSelector(btn + ':enabled', { timeout: 10000 });
      await page.click(btn);
      const s = await waitStatus(page, (x) => x.run && x.run.label === label && !x.run.running, 60000);
      check(label + ' from the Dispense tab: OK', s.run.status === 'OK', s.run);
    }

    // ---- STOP while a board run is dispensing ------------------------------------------------
    await page.click('.tabs [role=tab][data-tab=run]');
    await page.fill('#run-maxdots', '60');
    await page.click('#btn-run');
    await waitStatus(page, (x) => x.run && x.run.label === 'board' && x.run.running && x.run.dotsDone >= 3, 120000);
    g0 = gcode().next;
    await page.click('[data-testid=stop]');
    const stopped = await waitStatus(page, (x) => x.run && !x.run.running && !x.machine.enabled, 30000);
    check('STOP mid-run: run ABORTED before its end', stopped.run.status === 'ABORTED' && stopped.run.dotsDone < 60,
      { status: stopped.run.status, done: stopped.run.dotsDone, message: stopped.run.message });
    l = lines(gcode(g0));
    const iM410 = l.indexOf('M410'), iV = l.indexOf('M107 P3'), iP = l.indexOf('M107 P2'), iB = l.indexOf('M18 B');
    check('STOP mid-run: engine I1 sequence M410, valve off, pump off, M18 B', iM410 >= 0 && iV > iM410 && iP > iV && iB > iP, l);
    check('STOP mid-run: machine stopped and un-homed', !stopped.machine.enabled && !stopped.machine.homed);
    check('STOP mid-run: the engine\'s own I1 went out, then OpenPnP\'s DISABLE_COMMAND (valve off sent twice)',
      l.filter((x) => x === 'M107 P3').length >= 2, l);
    console.log('     STOP mid-run: ' + stopped.run.dotsDone + ' of 60 dots done; G-code after STOP: ' + JSON.stringify(l));
    await page.screenshot({ path: path.join(OUT, 'run-stopped.png'), fullPage: true });
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    await page.click('#btn-home');
    await waitText(page, 'pill-homed', /^Homed$/, 120000);

    // ---- pressure on from the Dispense tab --------------------------------------------------
    await page.click('.tabs [role=tab][data-tab=dispense]');
    await page.waitForSelector('#btn-pressure-on:enabled', { timeout: 10000 });
    await page.waitForSelector('#interlock-list li');
    const i1 = await page.textContent('#interlock-list li.ok:has-text("I1"), #interlock-list li.warning:has-text("I1")');
    check('I1 shows the DISABLE_COMMAND check', /Stop/.test(i1), i1);
    g0 = gcode().next;
    await page.click('#btn-pressure-on');
    await waitText(page, 'pressure-state', /^ON/, 30000);
    l = lines(gcode(g0));
    const pump = l.indexOf('M106 P2 S255'), valve = l.indexOf('M106 P3 S255');
    check('pressure on: pump, then valve', pump >= 0 && valve > pump, l);
    check('pressure pill shows held pressure', /Pressure ON/.test(await textOf(page, 'pill-pressure')));
    await page.screenshot({ path: path.join(OUT, 'dispense-pressure-on.png'), fullPage: true });

    // prime is refused while the pressure is held, with the reason in the banner
    check('Prime disabled while pressure is held', await page.isDisabled('#btn-prime'));

    // ---- STOP ---------------------------------------------------------------------------------
    g0 = gcode().next;
    await page.click('[data-testid=stop]');
    await waitText(page, 'pill-conn', /^Not started$/, 30000);
    await waitText(page, 'pressure-state', /^Off/, 10000);
    l = lines(gcode(g0));
    const vOff = l.indexOf('M107 P3'), pOff = l.indexOf('M107 P2');
    check('STOP: valve off, then pump off', vOff >= 0 && pOff > vOff, l);
    check('STOP: OpenPnP Stop sent the DISABLE_COMMAND (M410 ... M18 B)', l.includes('M410') && l.includes('M18 B'), l);
    check('STOP toast', /STOPPED/.test(await textOf(page, 'toast')), await textOf(page, 'toast'));
    const st = await (await page.request.get(BASE + 'api/status')).json();
    check('after STOP: machine stopped, pressure off', !st.machine.enabled && st.pressure.state === 'off',
      { enabled: st.machine.enabled, pressure: st.pressure.state });
    await page.screenshot({ path: path.join(OUT, 'after-stop.png'), fullPage: true });

    // ---- top camera snapshot (simulated ImageCamera) ------------------------------------------
    const cam = await page.request.get(BASE + 'api/camera/top.jpg?w=640');
    check('top camera JPEG', cam.ok() && cam.headers()['content-type'] === 'image/jpeg', cam.status());
    if (cam.ok()) fs.writeFileSync(path.join(OUT, 'camera.jpg'), await cam.body());
    await page.click('#btn-cam');
    await page.waitForFunction(() => document.getElementById('cam-img').naturalWidth > 0, null, { timeout: 15000 });
    check('camera snapshot shown in the UI', true);
    await page.screenshot({ path: path.join(OUT, 'dispense-camera.png'), fullPage: true });

    check('no JavaScript errors', jsErrors.length === 0, jsErrors);
  } catch (e) {
    check('scenario completed', false, String(e && e.stack || e));
    await page.screenshot({ path: path.join(OUT, 'failure.png'), fullPage: true }).catch(() => {});
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    const failed = results.filter((r) => !r.ok).length;
    console.log(failed ? failed + ' check(s) FAILED' : 'all ' + results.length + ' checks passed');
    process.exit(failed ? 1 : 0);
  }
})();
