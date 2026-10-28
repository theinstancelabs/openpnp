/*
 * Browser test of the Instance Labs web UI served by an OpenPnP *simulator* instance
 * (launched by run-webui-sim-test.sh through openpnp-uinav: config copy, simulated controller,
 * simulated cameras, Xvfb). Headless Chromium via Playwright.
 *
 * Checks: every tab renders, STOP is visible on every tab, the Profiles families, adding a pad
 * type from the UI, Start + Home from the UI, the dispense profile card (save, rpm warning, start
 * angle confirm), the purge row card (save, reset), the needle frame from the bench relation (K),
 * the clean-pad check of a plan, a 20-dot board run (purge row + work in one pressure session),
 * prime and test dot, STOP mid-run (bench F9 sequence), pressure on (G-code order), STOP (G-code
 * order, machine stopped, pressure off), the top-camera snapshot, no JS errors.
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
/** The bench F9 Stop sequence (engine abort and machine.xml DISABLE_COMMAND). */
const F9 = ['M410', 'M107 P3', 'M107 P2', 'M107 P1', 'M107 P0', 'M18 B'];
/** Index of seq as one contiguous block in l, or -1. */
function block(l, seq) {
  for (let i = 0; i + seq.length <= l.length; i++) if (seq.every((x, k) => l[i + k] === x)) return i;
  return -1;
}
/** True if seq appears in l in this order (other lines may come in between). */
function inOrder(l, seq) {
  let i = 0;
  for (const x of l) if (i < seq.length && x === seq[i]) i++;
  return i === seq.length;
}
/** True if l holds seq twice, each copy in order (two senders whose lines may interleave). */
function twiceInOrder(l, seq) {
  const rest = l.slice();
  let from = 0;
  for (const x of seq) {
    const k = rest.indexOf(x, from);
    if (k < 0) return false;
    rest.splice(k, 1);
    from = k;
  }
  return inOrder(rest, seq);
}

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
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.accept(); });
  try {
    const resp = await page.goto(BASE);
    check('UI served by OpenPnP', resp.ok(), resp.status());
    await waitText(page, 'pill-engine', /Dispenser/, 20000);
    check('engine pill', /Dispenser ready|Dispenser unavailable/.test(await textOf(page, 'pill-engine')), await textOf(page, 'pill-engine'));

    // ---- tabs render, STOP always visible -------------------------------------------------
    const tabs = [['Run', '#btn-plan'], ['Dispense', '#pr-state'], ['Calibrate', '#cal-history-note'], ['Profiles', '#pf-card'],
      ['Profiles', '#profiles .family']];
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

    // ---- dispense profile card: save, rpm warning, start angle for this controller connection ---
    await page.click('.tabs [role=tab][data-tab=profiles]');
    await page.waitForFunction(() => document.getElementById('pf-turn').options.length > 0, null, { timeout: 10000 });
    const turns = await page.$$eval('#pf-turn option', (os) => os.map((o) => o.value));
    check('profile: screw turn offers whole fractions of a revolution', ['400', '800', '1600', '3200'].every((v) => turns.includes(v)), turns);
    await page.fill('#pf-rpm', '200');
    check('profile: rpm above 180 warns', await page.isVisible('#pf-rpm-warn'));
    await page.fill('#pf-rpm', '180');
    check('profile: rpm 180 does not warn', !(await page.isVisible('#pf-rpm-warn')));
    await page.fill('#pf-psi', '18');
    await page.selectOption('#pf-turn', '800');
    await page.fill('#pf-suck', '10');
    await page.fill('#pf-dwell', '260');
    await page.fill('#pf-gap', '0.10');
    await page.fill('#pf-accel', '20000');
    await page.fill('#pf-purge', '8');
    await page.click('#btn-pf-save');
    const sp = await waitStatus(page, (s) => s.dispense.profile.dwellMs === 260, 10000);
    check('profile card saves a partial profile (dwell 260 ms, the rest kept)', sp.dispense.profile.netStepsPerDot === 800
      && sp.dispense.profile.purgeDots === 8 && sp.dispense.profile.screwRpm === 180 && sp.dispense.profile.problems.length === 0,
      sp.dispense.profile);
    await waitText(page, 'pf-turn-info', /net 180\.18° per dot/, 10000);
    check('profile: forward/net turn shown', /forward 190\.09°/.test(await textOf(page, 'pf-turn-info')), await textOf(page, 'pf-turn-info'));
    await page.click('#btn-pf-angle-confirm');
    const sa = await waitStatus(page, (s) => !s.dispense.profile.startAngleNeedsConfirm, 10000);
    check('start angle confirmed after Start (I11)', sa.dispense.profile.startAngleSetAtMs >= sa.dispense.profile.controllerConnectedAtMs,
      sa.dispense.profile);
    // the page shows it at its next status poll
    check('start angle warning hidden once confirmed',
      await page.waitForSelector('#pf-angle-warn', { state: 'hidden', timeout: 5000 }).then(() => true, () => false));
    await page.screenshot({ path: path.join(OUT, 'profiles-dispense-profile.png'), fullPage: true });

    // ---- set up a board run: waste place, needle frame, surfaces, purge row, settings, 0603 -----
    await page.click('.tabs [role=tab][data-tab=dispense]');
    await page.waitForSelector('tr[data-place="waste"] input[data-axis=x]');
    const waste = page.locator('tr[data-place="waste"]');
    await waste.locator('input[data-axis=x]').fill('100');
    await waste.locator('input[data-axis=y]').fill('120');
    await waste.locator('input[data-axis=z]').fill('10');
    await waste.locator('button[data-action=save]').click();
    await waitStatus(page, (s) => s.dispense.locations.waste && s.dispense.locations.waste.x === 100, 10000);
    check('waste place saved from the Places table', true);
    // I4 needle Z frame from the bench relation: needle contact raw Z = K - N1 contact Z
    await page.fill('#anchor-n1', '10');
    await page.fill('#anchor-k', '65.59');
    await page.click('#btn-anchor-k');
    const an = await waitStatus(page, (s) => s.dispense.needle.anchored && s.dispense.needle.anchor.rawSumMm === 65.59, 10000);
    check('needle Z frame from K: offset -2.59 mm', Math.abs(an.dispense.needle.anchorOffsetMm + 2.59) < 1e-6, an.dispense.needle);
    await page.fill('#needle-offset', '-2.59');
    await page.click('#btn-needle-offset');
    await waitText(page, 'needle-state', /^Touched off/, 10000);
    check('manual needle offset (after the frame) clears I4', true);
    // probed board surface (sim: flat at Z 10 around the board at 120, 120)
    await page.selectOption('#surf-name', 'board');
    await page.fill('#surf-points', '[[110,110,10],[205,110,10],[110,205,10],[205,205,10.01]]');
    await page.click('#btn-surf-save');
    await waitStatus(page, (s) => s.dispense.surfaces && s.dispense.surfaces.board && s.dispense.surfaces.board.plane, 10000);
    check('board surface saved from the Surfaces card', true);
    // the blank carries the purge row
    await page.selectOption('#surf-name', 'blank');
    await page.fill('#surf-points', '[[60,30,10],[220,30,10],[60,60,10],[220,60,10.01]]');
    await page.click('#btn-surf-save');
    await waitStatus(page, (s) => s.dispense.surfaces.blank && s.dispense.surfaces.blank.plane, 10000);
    check('blank surface saved from the Surfaces card', true);
    // purge row card: 48 spots at 2.5 mm on the blank, then "wiped: reset" (confirm accepted)
    await page.waitForFunction(() => [...document.getElementById('pr-surface').options].some((o) => o.value === 'blank'), null,
      { timeout: 10000 });
    await page.selectOption('#pr-surface', 'blank');
    await page.fill('#pr-x0', '70');
    await page.fill('#pr-y0', '45');
    await page.fill('#pr-pitchx', '2.5');
    await page.fill('#pr-pitchy', '0');
    await page.fill('#pr-spots', '48');
    await page.click('#btn-pr-save');
    const pr = await waitStatus(page, (s) => s.dispense.purgeRow.defined && s.dispense.purgeRow.x0Mm === 70, 10000);
    check('purge row saved from its card', pr.dispense.purgeRow.spots === 48 && pr.dispense.purgeRow.surfaceProbed, pr.dispense.purgeRow);
    await page.click('#btn-pr-reset');
    const prr = await waitStatus(page, (s) => s.dispense.purgeRow.usedSpots === 0 && s.dispense.purgeRow.resetAtMs, 10000);
    check('purge row reset: 6 boards left', prr.dispense.purgeRow.boardsLeft === 6, prr.dispense.purgeRow);
    check('purge row reset asked for a confirm', dialogs.some((m) => /wiped/.test(m)), dialogs);
    // simulated camera: a static image, so the run's clean-pad check (I10) would judge that image
    await page.uncheck('#set-clean');
    await page.click('#btn-settings');
    await waitText(page, 'toast', /Settings saved/, 10000);
    const sc = await (await page.request.get(BASE + 'api/config')).json();
    check('clean-pad check switched off from Settings', sc.cleanPadCheck.enabled === false, sc.cleanPadCheck);
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
    const summary = await page.textContent('#plan-summary');
    check('plan shows a DPH estimate (no upper-bound sign)', /DPH \(estimate\)[0-9]/.test(summary), summary);
    check('plan shows the profile row', /Profile.*18\.0 psi · 1\/2 turn · dwell 260 ms · gap 0\.10 mm · purge 8/.test(summary), summary);
    check('no calibration row any more', !/Calibration/.test(summary), summary);
    await page.screenshot({ path: path.join(OUT, 'run-plan.png'), fullPage: true });

    // ---- clean-pad check of the plan (top camera only; the sim camera is a static image) ------
    let g0 = gcode().next;
    await page.click('#btn-clean-check');
    await waitText(page, 'clean-result', /pads are clean|pads clean|No pad was checked/, 600000);
    const cleanText = await textOf(page, 'clean-result');
    check('clean-pad check result shown', /pads are clean|pads clean/.test(cleanText), cleanText);
    const planNow = await (await page.request.get(BASE + 'api/plan/' + (await page.evaluate(() => S.plan.planId)))).json();
    check('clean-pad check kept with the plan (160 targets)', planNow.cleanCheck && planNow.cleanCheck.targets === 160,
      planNow.cleanCheck && { targets: planNow.cleanCheck.targets, ok: planNow.cleanCheck.ok });
    const nDirty = await page.$$eval('#plan-svg rect.pad.dirty', (e) => e.length);
    check('dirty pads marked in the preview', nDirty === planNow.cleanCheck.dirtyPadIds.length, nDirty);
    let l = lines(gcode(g0));
    check('clean-pad check: no pressure, no screw', !l.some((x) => /^M106|^G1 B/.test(x)), l.slice(0, 30));
    await page.screenshot({ path: path.join(OUT, 'run-clean-check.png'), fullPage: true });

    // ---- run the first 20 dots ---------------------------------------------------------------
    g0 = gcode().next;
    await page.fill('#run-maxdots', '20');
    await page.click('#btn-run');
    // the clean-pad check before was a task too: wait for this board run to end, then for the page to show it
    await waitStatus(page, (s) => s.run && s.run.label === 'board' && !s.run.running, 240000);
    await page.waitForFunction(() => /Task\s*board[\s\S]*finished: (OK|ERROR|ABORTED|REFUSED)/
      .test(document.getElementById('run-live').textContent), null, { timeout: 10000 });
    const live = await page.textContent('#run-live');
    check('20-dot run finished OK', /finished: OK/.test(live), live);
    const doneDots = await page.$$eval('#plan-svg circle.dot.done', (e) => e.length);
    check('20 dots marked done in the preview', doneDots === 20, doneDots);
    const st1 = await (await page.request.get(BASE + 'api/status')).json();
    check('last run: 20 of 20 dots, DPH reported', st1.lastRun.dotsDone === 20 && st1.lastRun.dotsPlanned === 20
      && st1.lastRun.dphMotion > 0 && st1.lastRun.dphOverall > 0, st1.lastRun);
    check('run confirm names the regulator pressure and the profile',
      dialogs.some((m) => /Set the regulator to 18\.0 psi \(profile .+\)/.test(m)), dialogs[dialogs.length - 1]);
    l = lines(gcode(g0));
    const firstB = l.findIndex((x) => /^G1 B/.test(x));
    check('run: G92 B0 before the first auger move (I2)', l.indexOf('G92 B0') >= 0 && l.indexOf('G92 B0') < firstB, l.slice(0, 20));
    check('run: pump, then valve; valve off, then pump off',
      l.indexOf('M106 P2 S255') < l.indexOf('M106 P3 S255') && l.lastIndexOf('M107 P3') < l.lastIndexOf('M107 P2'));
    check('run: 56 auger moves = 28 dots (8 purge + 20) x (forward + suck-back)', l.filter((x) => /^G1 B/.test(x)).length === 56,
      l.filter((x) => /^G1 B/.test(x)).length);
    check('run: one pressure session (the valve opens once)', l.filter((x) => x === 'M106 P3 S255').length === 1, l.length);
    check('run: screw moves at 180 rpm (F64800) under M204 S20000', l.filter((x) => /^G1 B/.test(x)).every((x) => / F64800$/.test(x))
      && l.includes('M204 S20000'), l.filter((x) => /^G1 B|^M204/.test(x)).slice(0, 8));
    check('run: purge spots used', st1.dispense.purgeRow.usedSpots === 8, st1.dispense.purgeRow);
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
    // with the F9 DISABLE_COMMAND the web STOP does not wait for the engine: its abort lines and
    // OpenPnP's DISABLE_COMMAND go out at the same time and may interleave, each copy in order
    check('STOP mid-run: the bench F9 sequence (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B) from the engine and '
      + 'from OpenPnP\'s Stop', twiceInOrder(l, F9), l);
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
    check('I1 ok with the F9 DISABLE_COMMAND', await page.isVisible('#interlock-list li.ok:has-text("I1")')
      && i1.includes('M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B'), i1);
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
    check('STOP: OpenPnP Stop sent the DISABLE_COMMAND (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B)', block(l, F9) >= 0, l);
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
