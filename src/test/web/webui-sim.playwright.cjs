/*
 * Browser test of the Instance Labs web UI served by an OpenPnP *simulator* instance
 * (launched by run-webui-sim-test.sh through openpnp-uinav: config copy, simulated controller,
 * simulated cameras, Xvfb). Headless Chromium via Playwright.
 *
 * Checks (UI of docs/instancelabs/UI_DESIGN.md, ids of its §16): every workspace renders, STOP is
 * visible on every workspace and scroll position, the Profile families, adding a pad type from the
 * UI, Connect + Home from the strip (Home is two-press), the dispense profile card (save, rpm
 * warning, start angle confirm), places, the needle frame from the bench relation (K), surfaces
 * (advanced editor; the board surface tied to the plan, then plan again), the purge row card (save,
 * two-press reset), the clean-pad check of a plan (two-press), a 20-dot board run through the
 * in-page run confirm (regulator line, three ticks; purge row + work in one pressure session),
 * prime and test dot (two-press), STOP mid-run (bench F9 sequence, Recovery), pressure on (G-code
 * order), STOP (G-code order, machine stopped, pressure off), the top-camera snapshot, no native
 * dialogs, no JS errors.
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
  await page.waitForFunction(([i, src]) => { const n = document.getElementById(i); return !!n && new RegExp(src).test(n.textContent); },
    [id, re.source], { timeout });
}

/** Opens a <details> disclosure by its summary (a click on an open one would close it). */
async function openDetails(page, id) {
  if (!(await page.evaluate((i) => document.getElementById(i).open, id))) await page.click('#' + id + ' > summary');
}

/** Opens a workspace from the left navigation. */
async function nav(page, ws) {
  await page.click('.tabs [role=tab][data-tab=' + ws + ']');
}

/**
 * A two-press button (§11): the first press arms it ("Press again: <verb>", .btn--press) and sends nothing; the second,
 * at least 300 ms later, runs it. Returns the armed label. `maybe`: a button that is single-press in some states
 * (Pressure on with the needle over the waste).
 */
async function press2(page, sel, maybe) {
  await page.waitForSelector(sel + ':enabled', { timeout: 15000 });
  await page.click(sel);
  const armed = await page.waitForSelector(sel + '.btn--press', { timeout: maybe ? 800 : 3000 }).then(() => true, () => false);
  if (!armed) {
    if (maybe) return null;
    throw new Error(sel + ' did not ask for a second press');
  }
  const label = (await page.textContent(sel)).trim();
  await page.waitForTimeout(450);
  await page.click(sel);
  return label;
}

/** Run › Checks: the first n dots through the in-page run confirm (§8.1.5). Returns what the confirm showed. */
async function runFirstDots(page, n) {
  await nav(page, 'run');
  await page.evaluate(() => IL.go('#run/checks'));
  await page.waitForSelector('#btn-run', { timeout: 15000 });
  if (!(await page.isVisible('#run-maxdots'))) await page.click('#btn-run-test');
  await page.fill('#run-maxdots', String(n));
  await page.waitForSelector('#btn-run.btn--test:enabled', { timeout: 15000 });
  await page.click('#btn-run');
  await page.waitForSelector('#dlg-run', { timeout: 10000 });
  const shown = { psi: await textOf(page, 'run-confirm-psi'), test: await textOf(page, 'run-test-line'),
    goBefore: await textOf(page, 'btn-run-go'), goDisabled: await page.isDisabled('#btn-run-go') };
  for (const t of ['#run-tick-gauge', '#run-tick-paste', '#run-tick-hands']) if (!(await page.isChecked(t))) await page.check(t);
  await page.waitForSelector('#btn-run-go:enabled', { timeout: 10000 });
  shown.go = await textOf(page, 'btn-run-go');
  await page.click('#btn-run-go');
  await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 15000 });
  return shown;
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
  // native confirm / alert / prompt block STOP: the UI must never open one (§1)
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  try {
    const resp = await page.goto(BASE);
    check('UI served by OpenPnP', resp.ok(), resp.status());
    await waitText(page, 'pill-engine', /Ready to run|ready|Blocked|unavailable|Stopped|No plan|of 9/, 20000);
    check('board run verdict in the strip', /Ready to run|ready|Blocked|unavailable|Stopped|No plan|of 9/.test(await textOf(page, 'pill-engine')),
      await textOf(page, 'pill-engine'));
    await page.evaluate(() => { if (document.activeElement) document.activeElement.blur(); });
    await page.keyboard.press('Tab');
    const firstTab = await page.evaluate(() => document.activeElement && document.activeElement.id);
    check('STOP is the first Tab stop', firstTab === 'btn-stop', firstTab);

    // ---- workspaces render, STOP always visible -----------------------------------------------
    const tabs = [['run', '#btn-plan'], ['setup', '#pr-state'], ['calibrate', '#cal-history-note'], ['profile', '#pf-card'],
      ['profile', '#profiles .family'], ['records', '#records-runs']];
    for (const [tab, sel] of tabs) {
      await nav(page, tab);
      await page.waitForSelector(sel, { state: 'visible', timeout: 15000 });
      check('workspace ' + tab + ' renders ' + sel, await page.isVisible(sel));
      check('STOP visible on ' + tab, await page.isVisible('[data-testid=stop]'));
      await page.screenshot({ path: path.join(OUT, 'tab-' + tab + '.png') });
    }
    // the page never scrolls: #main scrolls by itself and the strip with STOP stays put
    await page.evaluate(() => { document.getElementById('main').scrollTop = 3000; });
    await page.mouse.wheel(0, 3000);
    const box = await page.locator('[data-testid=stop]').boundingBox();
    check('STOP stays on screen after scrolling', box && box.y >= 0 && box.y < 120 && box.height >= 79, box);

    await nav(page, 'profile');
    await page.waitForSelector('#profiles .family', { state: 'visible', timeout: 15000 });
    const fams = await page.$$eval('#profiles .family h2', (els) => els.map((e) => e.textContent));
    const want = ['Chip (2-terminal SMD)', 'Small-outline discretes', 'Leaded ICs', 'No-lead ICs', 'Custom'];
    check('Profiles shows the five families in order', JSON.stringify(fams.slice(0, 5)) === JSON.stringify(want), fams);
    check('0603 row with status', /not started|provisional|characterized|validated/.test(await page.textContent('tr[data-key="chip-0603"]')),
      await page.textContent('tr[data-key="chip-0603"]'));

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

    // ---- Connect + Home from the strip ---------------------------------------------------------
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    check('Connect connects (sim)', true);
    const homeLabel = await press2(page, '#btn-home');
    check('Home asks for a second press', /Press again: home/i.test(homeLabel), homeLabel);
    await waitText(page, 'pill-homed', /^Homed$/, 120000);
    check('Home homes (sim)', true);

    // ---- dispense profile card: save, rpm warning, start angle for this controller connection ---
    await nav(page, 'profile');
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
    await page.waitForSelector('#btn-pf-save:enabled', { timeout: 5000 }).catch(() => {});
    if (await page.isEnabled('#btn-pf-save')) await page.click('#btn-pf-save');   // nothing to save when it already matched
    const sp = await waitStatus(page, (s) => s.dispense.profile.dwellMs === 260, 10000);
    check('profile card saves a partial profile (dwell 260 ms, the rest kept)', sp.dispense.profile.netStepsPerDot === 800
      && sp.dispense.profile.purgeDots === 8 && sp.dispense.profile.screwRpm === 180 && sp.dispense.profile.problems.length === 0,
      sp.dispense.profile);
    await waitText(page, 'pf-turn-info', /net 180\.18° per dot/, 10000);
    check('profile: forward/net turn shown', /forward 190\.09°/.test(await textOf(page, 'pf-turn-info')), await textOf(page, 'pf-turn-info'));
    // I11: "Controller stayed on" when an angle was set before; an angle never set is measured and set in Calibrate
    if (await page.isVisible('#btn-pf-angle-confirm')) {
      await page.click('#btn-pf-angle-confirm');
    } else {
      check('start angle never set: Profile offers only "Measure the angle"', /Measure the angle/.test(await textOf(page, 'btn-pf-angle-measure')));
      await page.click('#btn-pf-angle-measure');
      await page.waitForSelector('#pf-angle', { state: 'visible', timeout: 10000 });
      await page.fill('#pf-angle', '298');
      await page.click('#btn-pf-angle-set');
      await nav(page, 'profile');
    }
    const sa = await waitStatus(page, (s) => !s.dispense.profile.startAngleNeedsConfirm && s.dispense.profile.startAngleSetAtMs, 10000);
    check('start angle confirmed after Start (I11)', sa.dispense.profile.startAngleSetAtMs >= sa.dispense.profile.controllerConnectedAtMs,
      sa.dispense.profile);
    // the page shows it at its next status poll
    check('start angle warning hidden once confirmed',
      await page.waitForSelector('#pf-angle-warn', { state: 'hidden', timeout: 5000 }).then(() => true, () => false));
    await page.screenshot({ path: path.join(OUT, 'profiles-dispense-profile.png'), fullPage: true });

    // ---- set up a board run: waste place, needle frame, blank surface, purge row, settings, 0603 --
    await nav(page, 'setup');
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
    await waitText(page, 'btn-needle-offset', /Confirm\s+−2\.590 mm/, 5000).catch(() => {});
    check('needle confirm names the frame offset', /Confirm\s+−2\.590 mm/.test(await textOf(page, 'btn-needle-offset')),
      await textOf(page, 'btn-needle-offset'));
    // the manual offset sits behind "Other offset…" (§8.2)
    await openDetails(page, 'needle-other');
    await page.fill('#needle-offset', '-2.59');
    await page.click('#btn-needle-offset');
    await waitText(page, 'needle-state', /^Touched off/, 10000);
    check('manual needle offset (after the frame) clears I4', true);
    // the blank carries the purge row (advanced editor: paste points)
    await openDetails(page, 'surf-advanced');
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
    // a row that moved while spots were used asks in-page whether the new row is clean
    if (await page.waitForSelector('#dlg-purge-moved', { timeout: 1500 }).then(() => true, () => false)) {
      await page.click('#dlg-purge-moved button:has-text("No, keep the count")');
    }
    const resetLabel = await press2(page, '#btn-pr-reset');
    check('purge row reset asks for a second press', /Press again: reset row/.test(resetLabel), resetLabel);
    const prr = await waitStatus(page, (s) => s.dispense.purgeRow.usedSpots === 0 && s.dispense.purgeRow.resetAtMs, 10000);
    check('purge row reset: 6 boards left', prr.dispense.purgeRow.boardsLeft === 6, prr.dispense.purgeRow);
    // simulated camera: a static image, so the run's clean-pad check (I10) would judge that image
    if (await page.isChecked('#set-clean')) {
      await page.uncheck('#set-clean');
      await page.click('#btn-settings');
      await waitText(page, 'toast', /Settings saved/, 10000);
    }
    const sc = await (await page.request.get(BASE + 'api/config')).json();
    check('clean-pad check switched off from Settings', sc.cleanPadCheck.enabled === false, sc.cleanPadCheck);
    await nav(page, 'profile');
    await page.click('tr[data-key="chip-0603"] button:text-is("Edit")');
    await page.waitForSelector('#dlg-padtype[open]');
    await page.selectOption('#pt-status', 'characterized');
    await page.click('#pt-save');
    await page.waitForSelector('tr[data-key="chip-0603"] .badge.characterized', { timeout: 10000 });
    check('0603 set to characterized from Profiles > Edit (version 2)',
      (await page.textContent('tr[data-key="chip-0603"]')).includes('v2'));

    // ---- plan the real test board (copied into the sim config) --------------------------------
    await nav(page, 'run');
    await page.evaluate(() => IL.go('#run/board'));
    await page.waitForFunction(() => [...document.querySelectorAll('#board-select option')].some((o) => /^file:/.test(o.value)), null,
      { timeout: 15000 });
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
    // I4 refuses an untied board surface: Run opens Probe; save the surface with the plan shown, then plan again (§16.8)
    await waitText(page, 'plan-chip', /Plan [0-9a-f]{4}/, 10000);
    const step = await page.evaluate(() => [document.getElementById('panel').dataset.view, IL.ready().items.find((x) => x.key === 'surface').ready]);
    check('a plan without this seat\'s surface opens Probe (else Checks)', step[0] === (step[1] ? 'checks' : 'probe'), step);
    const planBefore = await page.evaluate(() => IL.S.plan.planId);
    await nav(page, 'setup');
    await openDetails(page, 'surf-advanced');
    await page.waitForSelector('#surf-name', { state: 'visible' });
    await page.selectOption('#surf-name', 'board');
    await page.fill('#surf-points', '[[110,110,10],[205,110,10],[110,205,10],[205,205,10.01]]');
    await page.click('#btn-surf-save');
    const bs = await waitStatus(page, (s) => s.dispense.surfaces && s.dispense.surfaces.board && s.dispense.surfaces.board.plane
      && s.dispense.surfaces.board.boundToBoard, 10000);
    check('board surface saved with the plan shown (tied)', !!bs.dispense.surfaces.board.boundToBoard, bs.dispense.surfaces.board);
    await nav(page, 'run');
    await page.waitForSelector('#btn-plan-again:enabled', { timeout: 10000 });
    await page.click('#btn-plan-again');
    await page.waitForFunction((id) => IL.S.plan && IL.S.plan.planId !== id, planBefore, { timeout: 30000 });
    await page.waitForSelector('#plan-interlocks', { state: 'visible', timeout: 15000 });
    await waitText(page, 'plan-interlocks', /All interlocks pass|interlocks:/, 15000);
    check('plan passes the interlocks', /All interlocks pass/.test(await textOf(page, 'plan-interlocks')), await textOf(page, 'plan-interlocks'));
    check('plan again shows the Checks step', await page.evaluate(() => document.getElementById('panel').dataset.view) === 'checks');
    const summary = await page.textContent('#plan-summary');
    check('plan shows a DPH estimate (dots only)', /DPH estimate[\s\S]*?[0-9][0-9,]*[\s\S]*?dots only/.test(summary), summary);
    const recipe = await page.getAttribute('#ws-run .recipe', 'aria-label');
    check('plan shows the profile token', recipe === '18 psi · ½ turn · dwell 260 ms · gap 0.10 mm · purge 8', recipe);
    check('no calibration row any more', !/Calibration/.test(summary), summary);
    await page.screenshot({ path: path.join(OUT, 'run-plan.png') });

    // ---- clean-pad check of the plan (top camera only; the sim camera is a static image) ------
    let g0 = gcode().next;
    const ccLabel = await press2(page, '#btn-clean-check');
    check('clean-pad check asks for a second press', /Press again: check pads/.test(ccLabel), ccLabel);
    await waitStatus(page, (s) => s.run && s.run.label === 'clean-pad check' && !s.run.running, 600000);
    await waitText(page, 'clean-result', /pads are clean|pads clean|No pad was checked/, 20000);
    const cleanText = await textOf(page, 'clean-result');
    check('clean-pad check result shown', /pads are clean|pads clean/.test(cleanText), cleanText);
    const planNow = await (await page.request.get(BASE + 'api/plan/' + (await page.evaluate(() => IL.S.plan.planId)))).json();
    check('clean-pad check kept with the plan (160 targets)', planNow.cleanCheck && planNow.cleanCheck.targets === 160,
      planNow.cleanCheck && { targets: planNow.cleanCheck.targets, ok: planNow.cleanCheck.ok });
    const nDirty = await page.$$eval('#plan-svg rect.pad.dirty', (e) => e.length);
    check('dirty pads marked in the preview', nDirty === planNow.cleanCheck.dirtyPadIds.length, nDirty);
    let l = lines(gcode(g0));
    check('clean-pad check: no pressure, no screw', !l.some((x) => /^M106|^G1 B/.test(x)), l.slice(0, 30));
    await page.screenshot({ path: path.join(OUT, 'run-clean-check.png') });

    // ---- run the first 20 dots through the in-page run confirm --------------------------------
    g0 = gcode().next;
    const conf = await runFirstDots(page, 20);
    check('run confirm names the regulator pressure and the profile', /^Set the regulator to 18 psi \(profile .+\)$/.test(conf.psi), conf.psi);
    check('run confirm repeats the test limit', /TEST: only the first 20 of 160 dots/.test(conf.test), conf.test);
    check('run confirm: Run is locked until every tick is set', conf.goDisabled && /Tick \d more to run/.test(conf.goBefore), conf);
    check('run confirm: Run names the dots', /^Run 20 dots/.test(conf.go), conf.go);
    // the clean-pad check before was a task too: wait for this board run to end, then for the page to show it
    await waitStatus(page, (s) => s.run && s.run.label === 'board' && !s.run.running, 240000);
    await page.waitForSelector('#run-live[data-label=board][data-status]:not([data-status=RUNNING])', { state: 'attached', timeout: 10000 });
    const liveStatus = await page.getAttribute('#run-live', 'data-status');
    check('20-dot run finished OK', liveStatus === 'OK', liveStatus);
    const doneDots = await page.$$eval('#plan-svg circle.dot.done', (e) => e.length);
    check('20 dots marked done in the preview', doneDots === 20, doneDots);
    const st1 = await (await page.request.get(BASE + 'api/status')).json();
    check('last run: 20 of 20 dots, DPH reported', st1.lastRun.dotsDone === 20 && st1.lastRun.dotsPlanned === 20
      && st1.lastRun.dphMotion > 0 && st1.lastRun.dphOverall > 0, st1.lastRun);
    check('the test limit is cleared after the run', await page.evaluate(() => !IL.run.test && String(IL.run.maxDots) === ''),
      await page.evaluate(() => [IL.run.test, IL.run.maxDots]));
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
    await page.screenshot({ path: path.join(OUT, 'run-after-20-dots.png') });
    console.log('     sim run: ' + JSON.stringify({ dphMotion: st1.lastRun.dphMotion, dphOverall: st1.lastRun.dphOverall,
      totalSeconds: st1.lastRun.totalSeconds }) + ' (simulated controller: not a physical DPH)');

    // ---- prime and test dot from Setup › Pressure and paste flow (two-press) -------------------
    await nav(page, 'setup');
    for (const [btn, label] of [['#btn-prime', 'prime'], ['#btn-testdot', 'test dot']]) {
      const armed = await press2(page, btn);
      check(label + ' asks for a second press', /^Press again/.test(armed), armed);
      const s = await waitStatus(page, (x) => x.run && x.run.label === label && !x.run.running, 60000);
      check(label + ' from Setup: OK', s.run.status === 'OK', s.run);
    }
    await waitText(page, 'dispense-result', /\S/, 10000);
    check('prime / test dot result shown in the card', (await textOf(page, 'dispense-result')).length > 0, await textOf(page, 'dispense-result'));
    const lastTile = await textOf(page, 'run-last').catch(() => '');
    check('Last board tile keeps the board run after a prime and a test dot', !/prime|test dot/i.test(lastTile), lastTile);

    // ---- STOP while a board run is dispensing ------------------------------------------------
    await runFirstDots(page, 60);
    await waitStatus(page, (x) => x.run && x.run.label === 'board' && x.run.running && x.run.dotsDone >= 3, 300000);
    g0 = gcode().next;
    await page.click('[data-testid=stop]');
    // the engine ends the run (ABORTED) after its abort lines went out; a loaded simulator can take a while
    const stopped = await waitStatus(page, (x) => x.run && !x.run.running && !x.machine.enabled, 90000);
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
    // Recovery (§8.1.8): Run's panel, Not connected, the board decision locked until the steps are done
    await waitText(page, 'pill-conn', /^Not connected$/, 15000);
    await page.waitForFunction(() => document.getElementById('panel').dataset.view === 'recovery', null, { timeout: 10000 });
    check('after STOP: Recovery panel on Run', true);
    // the page may still hold a poll from between "machine off" and "run ABORTED": wait for the verdict
    await page.waitForFunction(() => /^Stopped \d\d:\d\d/.test(document.querySelector('#pill-engine .cell__v').textContent), null,
      { timeout: 10000 }).catch(() => {});
    check('after STOP: verdict "Stopped hh:mm"', /^Stopped \d\d:\d\d/.test(await page.textContent('#pill-engine .cell__v')),
      await page.textContent('#pill-engine .cell__v'));
    check('after STOP: the plan never says "All interlocks pass"', !/All interlocks pass/.test(await page.textContent('#ws-run')));
    await page.waitForSelector('#btn-run-rest', { state: 'visible', timeout: 10000 }).catch(() => {});
    const locked = await page.evaluate(() => ['btn-run-rest', 'btn-run-all'].map((id) => {
      const b = document.getElementById(id);
      return b ? { id, disabled: b.disabled, title: b.title, text: b.textContent.trim() } : { id, missing: true };
    }));
    check('after STOP: board decision locked until the recovery steps are done', locked.every((b) => b.disabled), locked);
    await page.screenshot({ path: path.join(OUT, 'run-stopped.png') });
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    await press2(page, '#btn-home');
    await waitText(page, 'pill-homed', /^Homed$/, 120000);

    // ---- interlocks view and pressure on from Setup ------------------------------------------
    await nav(page, 'setup');
    await page.evaluate(() => IL.go('#setup?panel=interlocks'));
    await page.waitForSelector('#interlock-list li');
    const i1 = await page.textContent('#interlock-list li.ok:has-text("I1"), #interlock-list li.warning:has-text("I1")');
    check('I1 shows the DISABLE_COMMAND check', /Stop/.test(i1), i1);
    check('I1 ok with the F9 DISABLE_COMMAND', await page.isVisible('#interlock-list li.ok:has-text("I1")')
      && i1.includes('M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B'), i1);
    await page.evaluate(() => IL.panel.close());
    g0 = gcode().next;
    await press2(page, '#btn-pressure-on', true);   // two presses unless the needle is over the waste
    await waitText(page, 'pressure-state', /^ON/, 30000);
    l = lines(gcode(g0));
    const pump = l.indexOf('M106 P2 S255'), valve = l.indexOf('M106 P3 S255');
    check('pressure on: pump, then valve', pump >= 0 && valve > pump, l);
    await page.waitForFunction(() => /^ON /.test(document.querySelector('#pill-pressure .cell__v').textContent), null, { timeout: 5000 })
      .catch(() => {});
    check('pressure cell shows held pressure', /^ON /.test(await page.textContent('#pill-pressure .cell__v')),
      await page.textContent('#pill-pressure .cell__v'));
    check('second STOP in the panel while energised', await page.isVisible('#panel [data-stop-copy]'));
    await page.screenshot({ path: path.join(OUT, 'setup-pressure-on.png') });

    // prime is refused while the pressure is held, with the reason in the banner
    check('Prime disabled while pressure is held', await page.isDisabled('#btn-prime'));

    // ---- STOP (F9 from a text field) ---------------------------------------------------------
    g0 = gcode().next;
    await page.focus('#pr-x0');
    await page.keyboard.press('F9');
    await waitText(page, 'toast', /STOPPED/, 10000);
    check('STOP toast', /^STOPPED \d\d:\d\d:\d\d: /.test(await textOf(page, 'toast')), await textOf(page, 'toast'));
    check('F9 kept the focus in the field', await page.evaluate(() => document.activeElement && document.activeElement.id) === 'pr-x0');
    await waitText(page, 'pill-conn', /^Not connected$/, 30000);
    await waitText(page, 'pressure-state', /^Off/, 10000);
    l = lines(gcode(g0));
    const vOff = l.indexOf('M107 P3'), pOff = l.indexOf('M107 P2');
    check('STOP: valve off, then pump off', vOff >= 0 && pOff > vOff, l);
    check('STOP: OpenPnP Stop sent the DISABLE_COMMAND (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B)', block(l, F9) >= 0, l);
    const st = await (await page.request.get(BASE + 'api/status')).json();
    check('after STOP: machine stopped, pressure off', !st.machine.enabled && st.pressure.state === 'off',
      { enabled: st.machine.enabled, pressure: st.pressure.state });
    await page.screenshot({ path: path.join(OUT, 'after-stop.png') });

    // ---- top camera snapshot (simulated ImageCamera) ------------------------------------------
    const cam = await page.request.get(BASE + 'api/camera/top.jpg?w=640');
    check('top camera JPEG', cam.ok() && cam.headers()['content-type'] === 'image/jpeg', cam.status());
    if (cam.ok()) fs.writeFileSync(path.join(OUT, 'camera.jpg'), await cam.body());
    await page.click('#btn-cam');
    await page.waitForFunction(() => document.getElementById('cam-img').naturalWidth > 0, null, { timeout: 15000 });
    check('camera snapshot shown in the UI', true);
    await page.screenshot({ path: path.join(OUT, 'setup-camera.png') });

    check('no native dialogs (confirm / alert / prompt)', dialogs.length === 0, dialogs);
    // refusals answer 4xx on purpose (e.g. none expected here); the browser logs those as resource errors
    const real = jsErrors.filter((e) => !/Failed to load resource: the server responded with a status of 4\d\d/.test(e));
    check('no JavaScript errors', real.length === 0, jsErrors);
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
