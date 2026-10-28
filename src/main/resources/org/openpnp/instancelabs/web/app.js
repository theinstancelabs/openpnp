/* Instance Labs paste dispenser web UI. Vanilla JS, no build step; talks to the local API. */
'use strict';

const $ = (id) => document.getElementById(id);
const S = {
  status: null,
  plan: null,
  boards: null,
  padTypes: null,
  packages: null,
  editingKey: null,
  placesKey: null,
  settingsLoaded: false,
  defaultsFilled: false,
  testDotDefault: null,
  camTimer: null,
  lastRunId: null,
  online: false,
  pfKey: null,
  pfDirty: false,
  prKey: null,
  prDirty: false,
  trimKey: null,
  cleanCheck: null,
  cleanPolling: false,
};
const FAMILIES = ['Chip (2-terminal SMD)', 'Small-outline discretes', 'Leaded ICs', 'No-lead ICs', 'Custom'];
const LAYOUT_LABELS = {
  'single': 'one pad at the part centre',
  'two-terminal': 'two pads (chip, SOD)',
  'sot23-3': 'SOT-23 (three pads)',
  'footprint-only': 'from the package footprint only',
};
const STATUS_LABELS = {
  'not-started': 'not started',
  'provisional': 'provisional',
  'characterized': 'characterized',
  'validated': 'validated on a board',
};

// ------------------------------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------------------------------

class ApiError extends Error {
  constructor(status, data, fallback) {
    super((data && data.reason) || fallback);
    this.status = status;
    this.data = data;
  }
}

async function api(method, path, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) {
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(path, opt);
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!r.ok) throw new ApiError(r.status, data, r.status + ' ' + r.statusText);
  return data;
}

function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'text') e.textContent = v;
    else if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    e.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
  return e;
}

function svgEl(tag, attrs, title) {
  const e = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
  if (title) {
    const t = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    t.textContent = title;
    e.appendChild(t);
  }
  return e;
}

function fmt(n, d = 2) {
  if (n === null || n === undefined || Number.isNaN(n)) return '—';
  return Number(n).toFixed(d);
}

function fmtInt(n) {
  return n === null || n === undefined ? '—' : Math.round(n).toLocaleString();
}

function fmtTime(ms) {
  return ms ? new Date(ms).toLocaleString() : '—';
}

function fmtDuration(s) {
  if (s === null || s === undefined) return '—';
  if (s < 90) return s.toFixed(1) + ' s';
  return Math.floor(s / 60) + ' min ' + Math.round(s % 60) + ' s';
}

function num(id) {
  const v = $(id).value.trim();
  return v === '' ? undefined : Number(v);
}

function str(id) {
  const v = $(id).value.trim();
  return v === '' ? undefined : v;
}

function fillKv(dl, pairs) {
  dl.replaceChildren();
  for (const [k, v, title] of pairs) {
    dl.appendChild(el('dt', { text: k, title }));
    dl.appendChild(el('dd', { text: v === undefined || v === null ? '—' : String(v) }));
  }
}

let toastTimer = null;
function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast' + (bad ? ' bad' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), bad ? 9000 : 5000);
}

function banner(msg) {
  $('banner-text').textContent = msg;
  $('banner').classList.remove('hidden');
}

function errorText(e) {
  let msg = e.message || String(e);
  const il = e.data && e.data.interlocks;
  if (il && il.violations && il.violations.length) {
    msg = (e.data.reason || 'Refused by the interlocks').split(':')[0] + ':\n'
      + il.violations.map((v) => '• ' + v.code + ': ' + v.message).join('\n');
  }
  return msg;
}

/** Runs an action from a button: disables it meanwhile, shows refusals in the banner. */
async function act(btn, fn) {
  if (btn) btn.disabled = true;
  try {
    return await fn();
  } catch (e) {
    banner(errorText(e));
    return undefined;
  } finally {
    if (btn) btn.disabled = false;
    refreshButtons();
  }
}

// ------------------------------------------------------------------------------------------
// Tabs
// ------------------------------------------------------------------------------------------

function showTab(name) {
  for (const b of document.querySelectorAll('.tabs [role=tab]')) {
    b.setAttribute('aria-selected', String(b.dataset.tab === name));
  }
  for (const s of document.querySelectorAll('.tab')) {
    s.classList.toggle('active', s.id === 'tab-' + name);
  }
  if (location.hash !== '#' + name) history.replaceState(null, '', '#' + name);
  if (name === 'profiles') loadPadTypes();
  if (name === 'calibrate') loadCalibrations();
  if (name === 'run' && !S.boards) loadBoards();
}

// ------------------------------------------------------------------------------------------
// Status polling
// ------------------------------------------------------------------------------------------

async function poll() {
  try {
    const s = await api('GET', '/api/status');
    S.status = s;
    S.online = true;
    renderStatus(s);
  } catch (e) {
    S.online = false;
    setPill('pill-conn', 'OpenPnP not reachable', 'bad');
  } finally {
    setTimeout(poll, 700);
  }
}

function setPill(id, text, cls) {
  const p = $(id);
  p.textContent = text;
  p.className = 'pill' + (cls ? ' ' + cls : '');
}

function renderStatus(s) {
  const m = s.machine || {};
  const d = s.dispense || {};
  const pr = s.pressure || {};
  const run = s.run;
  setPill('pill-conn', m.enabled ? 'Connected' : 'Not started', m.enabled ? 'ok' : '');
  setPill('pill-homed', m.homed ? 'Homed' : 'Not homed', m.homed ? 'ok' : (m.enabled ? 'warn' : ''));
  if (pr.state && pr.state !== 'off') {
    setPill('pill-pressure', 'Pressure ' + pr.state.toUpperCase() + ' (held)', 'warn');
  } else if (pr.engineRunActive) {
    setPill('pill-pressure', 'Pressure: run in control', 'warn');
  } else {
    setPill('pill-pressure', 'Pressure off', 'ok');
  }
  if (!d.running) setPill('pill-engine', 'Dispenser engine not running', 'bad');
  else if (!d.available) setPill('pill-engine', 'Dispenser unavailable: ' + (d.unavailableReason || ''), 'bad');
  else if (d.busy) setPill('pill-engine', 'Busy: ' + ((run && run.label) || 'dispensing'), 'warn');
  else setPill('pill-engine', 'Dispenser ready', 'ok');

  renderPressure(pr);
  renderRun(run, s.lastRun);
  renderInterlocks(s.interlocks || []);
  renderNeedle(d);
  renderPlaces(d);
  renderProfile(d);
  renderPurgeRow(d);
  renderPlanStale(d);
  if (!S.defaultsFilled && d.running) {
    if (!$('prime-rot').value) $('prime-rot').value = d.primeRotationDeg;
    S.defaultsFilled = true;
  }
  // the test dot is one profile dot: follow the profile unless the operator typed another value
  const tdDef = d.testDot ? d.testDot.defaultRotationDeg : null;
  if (tdDef && tdDef !== S.testDotDefault) {
    const v = $('testdot-rot').value;
    if (!v || Number(v) === S.testDotDefault) $('testdot-rot').value = tdDef;
    S.testDotDefault = tdDef;
  }
  if (!S.settingsLoaded && d.running) loadSettings();
  refreshButtons();
}

function refreshButtons() {
  const s = S.status;
  if (!s) return;
  const m = s.machine || {};
  const d = s.dispense || {};
  const holding = s.pressure && s.pressure.state !== 'off';
  $('btn-start').disabled = !!m.enabled;
  $('btn-home').disabled = !m.enabled || d.busy || holding;
  $('btn-pressure-on').disabled = holding || d.busy || !d.available;
  $('btn-pressure-off').disabled = !holding;
  for (const id of ['btn-prime', 'btn-testdot', 'btn-shots-run', 'btn-dl-run', 'btn-to-run']) {
    $(id).disabled = d.busy || holding || !d.available;
    $(id).title = holding ? 'Switch the held pressure off first: this switches the pressure on and off by itself' : '';
  }
  $('btn-clean-check').disabled = !S.plan || !S.plan.dots.length || d.busy || holding || !d.available || S.cleanPolling;
  $('btn-run').disabled = !S.plan || !S.plan.dots.length || d.busy || holding || planIsStale(d)
    || !(S.plan.interlocks && S.plan.interlocks.ok);
}

/** The dispense profile changed since the shown plan was made (a run would be refused). */
function planIsStale(d) {
  return !!(S.plan && d.profile && S.plan.profileFingerprint !== d.profile.fingerprint);
}

function renderPlanStale(d) {
  const stale = planIsStale(d);
  $('plan-stale').classList.toggle('hidden', !stale);
  $('plan-stale').textContent = stale ? 'The dispense profile changed after this plan was made: plan the board again.' : '';
}

function renderPressure(pr) {
  let t;
  if (pr.state === 'on') {
    t = 'ON (held). Switches off by itself in ' + fmtDuration(pr.remainingS) + '.';
    if (pr.keepaliveArmed) t += ' Host watchdog armed.';
  } else if (pr.state === 'starting') t = 'Switching on…';
  else if (pr.state === 'stopping') t = 'Switching off…';
  else t = pr.engineRunActive ? 'Controlled by the running task' : 'Off';
  $('pressure-state').textContent = t;
  $('pressure-state').title = pr.lastEvent ? 'Last: ' + pr.lastEvent : '';
}

function renderRun(run, last) {
  const live = $('run-live');
  if (run) {
    const pct = run.dotsPlanned ? Math.min(100, 100 * run.dotsDone / run.dotsPlanned) : 0;
    $('run-bar').style.width = pct + '%';
    fillKv(live, [
      ['Task', run.label + (run.boardName && run.label === 'board' ? ' ' + run.boardName : '')],
      ['State', run.running ? 'running' : run.status],
      ['Step', run.phase],
      ['Dots', run.dotsDone + ' of ' + run.dotsPlanned],
      ['Time', fmtDuration(run.elapsedS)],
      ['DPH so far', fmtInt(run.liveDph), 'Dots per hour since the first dot'],
      ['Message', run.message || ''],
    ]);
    markDoneDots(run);
    if (!run.running && run.runId !== S.lastRunId) {
      S.lastRunId = run.runId;
      if (run.status !== 'OK') banner(run.label + ' ended: ' + run.status + (run.message ? ' — ' + run.message : ''));
      else if (run.label === 'clean-pad check') toast(run.message || 'clean-pad check finished');
      else toast(run.label + (run.label === 'board' && run.boardName ? ' ' + run.boardName : '') + ' finished: ' + run.dotsDone + ' dot(s)');
      loadCalibrations();
      refreshSettingsJson(); // a board run used purge spots
      refreshPlan();
    }
  } else {
    fillKv(live, [['Task', 'none yet']]);
  }
  if (last) {
    const rows = [
      ['Task', last.kind + (last.boardName ? ' (' + last.boardName + ')' : '')],
      ['Result', last.status + (last.message ? ': ' + last.message : '')],
      ['Dots', last.dotsDone + ' of ' + last.dotsPlanned + (last.purgeDots ? ' (+ ' + last.purgeDots + ' purge dots)' : '')],
      ['DPH (dots only)', fmtInt(last.dphMotion), 'Dots divided by the time from the first dot to the last dot'],
      ['DPH (everything)', fmtInt(last.dphOverall), 'Dots divided by the whole task time: pressure, purge row and travel included'],
      ['Total time', fmtDuration(last.totalSeconds)],
    ];
    if (last.profile) rows.push(['Profile', profileText(last.profile)]);
    const cc = last.cleanCheck;
    if (cc && !cc.ok) {
      rows.push(['Pads with paste left', dirtyText(cc), 'Clean-pad check (I10) of this run: wipe these pads (or re-register the board) and check again']);
      adoptCleanCheck(cc, last.planId);
    }
    fillKv($('run-last'), rows);
  } else {
    fillKv($('run-last'), [['Task', 'none since OpenPnP started']]);
  }
}

function renderInterlocks(list) {
  const ul = $('interlock-list');
  ul.replaceChildren(...list.map((i) => el('li', { class: i.state, title: i.state },
    el('b', { text: (i.id.startsWith('I') ? i.id + ' ' : '') + i.name }), i.text)));
}

function renderNeedle(d) {
  const n = d.needle;
  if (!n) return;
  $('needle-state').textContent = (n.touchedOff ? 'Touched off ' + fmtTime(n.setAtMs) : 'NOT touched off since the last needle change / frame')
    + ' · offset ' + fmt(n.zOffsetMm, 3) + ' mm · gap ' + fmt(n.gapMm, 3) + ' mm (profile)';
  const a = n.anchor;
  $('anchor-state').textContent = !a ? 'Not set: nothing can be dispensed until N1 and the needle have touched the same surface.'
    : (n.anchored ? 'Set ' : 'OUT OF DATE (needle changed) · set ') + fmtTime(a.setAtMs) + ': N1 ' + fmt(a.surfaceZMm, 3)
      + ', needle ' + fmt(a.needleContactZMm, 3) + (a.rawSumMm !== null && a.rawSumMm !== undefined ? ' (from K = ' + fmt(a.rawSumMm, 2) + ')' : '')
      + ' → frame offset ' + fmt(n.anchorOffsetMm, 3) + ' mm';
  renderSurfaces(d);
  if (document.activeElement !== $('needle-offset') && $('needle-offset').value === '') {
    $('needle-offset').value = n.zOffsetMm;
  }
  renderTrim(n.xyTrim);
}

function renderTrim(t) {
  if (!t) return;
  $('trim-state').textContent = t.setAtMs ? 'Set ' + fmtTime(t.setAtMs) + ': dx ' + fmt(t.dxMm, 3) + ' mm, dy ' + fmt(t.dyMm, 3) + ' mm'
    + (t.note ? ' · ' + t.note : '') : 'Not set (0, 0): the needle lands at the N2 head offset.';
  const key = JSON.stringify(t);
  const ids = ['trim-dx', 'trim-dy', 'trim-note'];
  if (key === S.trimKey || ids.includes(document.activeElement && document.activeElement.id)) return;
  S.trimKey = key;
  $('trim-dx').value = t.dxMm;
  $('trim-dy').value = t.dyMm;
  $('trim-note').value = t.note || '';
}

// ------------------------------------------------------------------------------------------
// Header actions
// ------------------------------------------------------------------------------------------

async function stopAll() {
  try {
    const r = await api('POST', '/api/stop');
    toast('STOPPED\n' + r.actions.join('\n'));
  } catch (e) {
    banner('STOP request failed: ' + errorText(e) + '\nUse OpenPnP\'s Stop button or switch the machine off.');
  }
}

// ------------------------------------------------------------------------------------------
// Run tab
// ------------------------------------------------------------------------------------------

async function loadBoards() {
  try {
    S.boards = await api('GET', '/api/boards');
  } catch (e) {
    $('board-hint').textContent = 'Could not list boards: ' + errorText(e);
    return;
  }
  const sel = $('board-select');
  const keep = sel.value;
  sel.replaceChildren();
  if (S.boards.job.length) {
    sel.appendChild(el('optgroup', { label: 'Open job in OpenPnP' },
      S.boards.job.map((b) => el('option', { value: 'job:' + b.boardLocationId, text: b.name + ' (job)' }))));
  }
  if (S.boards.files.length) {
    sel.appendChild(el('optgroup', { label: 'Board files' },
      S.boards.files.filter((b) => b.file).map((b) => el('option', { value: 'file:' + b.file, text: b.name + ' — ' + b.file }))));
  }
  sel.appendChild(el('option', { value: 'other', text: 'Other board file…' }));
  if (keep && [...sel.options].some((o) => o.value === keep)) sel.value = keep;
  onBoardChange();
}

function boardChoice() {
  const v = $('board-select').value || 'other';
  if (v.startsWith('job:')) return { kind: 'job', id: v.slice(4) };
  if (v.startsWith('file:')) return { kind: 'file', file: v.slice(5) };
  return { kind: 'other', file: str('board-file') };
}

function onBoardChange() {
  const c = boardChoice();
  $('board-file-row').classList.toggle('hidden', c.kind !== 'other');
  const inputs = ['board-x', 'board-y', 'board-z', 'board-rot'];
  if (c.kind === 'job') {
    const b = S.boards.job.find((x) => x.boardLocationId === c.id);
    $('board-x').value = b.x; $('board-y').value = b.y; $('board-z').value = b.z; $('board-rot').value = b.rotation;
    inputs.forEach((i) => { $(i).disabled = true; });
    $('board-hint').textContent = 'Position from the OpenPnP job' + (b.fiducialTransform !== 'NotSet'
      ? ' (fiducial-corrected).' : '. Run a fiducial check in OpenPnP for the best accuracy.');
  } else {
    inputs.forEach((i) => { $(i).disabled = false; });
    const saved = c.file && localStorage.getItem('il.boardloc.' + c.file);
    if (saved) {
      const l = JSON.parse(saved);
      $('board-x').value = l.x; $('board-y').value = l.y; $('board-z').value = l.z; $('board-rot').value = l.rotation;
    }
    $('board-hint').textContent = 'Enter where the board sits on the machine. No fiducial correction is applied to a board placed by hand.';
  }
}

async function planBoard() {
  const c = boardChoice();
  let body;
  if (c.kind === 'job') {
    body = { source: 'job', boardLocationId: c.id };
  } else {
    if (!c.file) { banner('Choose a board file.'); return; }
    body = { source: 'file', file: c.file, x: num('board-x'), y: num('board-y'), z: num('board-z'), rotation: num('board-rot') };
    if (body.x === undefined || body.y === undefined || body.z === undefined) {
      banner('Enter the board position X, Y and Z (Z: the N1 nozzle Z touching the board top).'); return;
    }
    localStorage.setItem('il.boardloc.' + c.file, JSON.stringify({ x: body.x, y: body.y, z: body.z, rotation: body.rotation }));
  }
  const p = await api('POST', '/api/plan', body);
  S.plan = p;
  S.cleanCheck = null;
  renderPlan(p);
  toast('Planned ' + p.summary.dots + ' dots on ' + p.summary.pads + ' pads');
}

/** "1/2 turn" for a number of motor steps (1 revolution = stepsPerRev steps). */
function turnLabel(steps, stepsPerRev) {
  const rev = stepsPerRev || 1600;
  if (steps % rev === 0) return steps / rev === 1 ? '1 turn' : (steps / rev) + ' turns';
  if (rev % steps === 0) return '1/' + (rev / steps) + ' turn';
  return steps + ' steps';
}

/** One line for a dispense profile: name, pressure, turn, dwell, gap, purge. */
function profileText(f) {
  return (f.name || 'profile') + ' · ' + fmt(f.pressurePsi, 1) + ' psi · ' + turnLabel(f.netStepsPerDot, f.stepsPerRev)
    + ' · dwell ' + f.dwellMs + ' ms · gap ' + fmt(f.gapMm, 2) + ' mm · purge ' + f.purgeDots;
}

/** "R3-1 (0.42), R7-2 (0.55)" for the dirty pads of a clean-pad check. */
function dirtyText(cc) {
  if (cc.complete === false) return 'the check did not finish (' + cc.targets + ' pad(s) looked at)';
  const dirty = cc.pads.filter((x) => !x.clean);
  if (!dirty.length) return cc.targets ? 'none' : 'no pad was checked';
  return dirty.slice(0, 12).map((x) => x.padId + ' (' + (x.brightFraction === null ? x.error : fmt(x.brightFraction, 2)) + ')').join(', ')
    + (dirty.length > 12 ? ' and ' + (dirty.length - 12) + ' more' : '');
}

/** Shows a clean-pad check of the current plan (or one of its subsets) if it is newer than the one shown. */
function adoptCleanCheck(cc, planId) {
  const p = S.plan;
  if (!p || !cc || !planId || (planId !== p.planId && !planId.startsWith(p.planId + '-subset-'))) return;
  if (S.cleanCheck && S.cleanCheck.checkedAtMs >= cc.checkedAtMs) return;
  S.cleanCheck = cc;
  renderCleanCheck();
}

function renderCleanCheck() {
  const cc = S.cleanCheck;
  const dirtyIds = new Set(cc ? cc.dirtyPadIds : []);
  for (const r of document.querySelectorAll('#plan-svg rect.pad')) r.classList.toggle('dirty', dirtyIds.has(r.dataset.pad));
  const box = $('clean-result');
  if (!cc) { box.replaceChildren(); box.className = 'result'; return; }
  const clean = cc.pads.filter((x) => x.clean).length;
  if (!cc.targets) {
    box.className = 'result bad';
    box.replaceChildren('No pad was checked (no camera picture): see the message of the clean-pad check above.');
    return;
  }
  if (cc.complete === false) {
    box.className = 'result bad';
    box.replaceChildren('The clean-pad check did not finish (stopped or failed after ' + cc.targets
      + ' pad(s)): see the message above, then check again.');
    return;
  }
  box.className = 'result ' + (cc.ok ? 'ok' : 'bad');
  const head = (cc.ok ? 'All ' + cc.targets + ' pads are clean' : clean + ' of ' + cc.targets + ' pads clean, '
    + (cc.targets - clean) + ' with paste left (or not under the camera)') + ' · bright share needed ≥ ' + fmt(cc.minBrightFraction, 2)
    + ' · checked ' + fmtTime(cc.checkedAtMs);
  const dirty = cc.pads.filter((x) => !x.clean);
  box.replaceChildren(el('div', { text: head }),
    cc.ok ? null : el('ul', {}, dirty.slice(0, 20).map((x) => el('li', {
      text: x.padId + ': bright share ' + (x.brightFraction === null ? '— (' + x.error + ')' : fmt(x.brightFraction, 2)),
    })), dirty.length > 20 ? el('li', { text: 'and ' + (dirty.length - 20) + ' more (red in the preview)' }) : null));
}

/** Camera-only check of every pad of the plan (pressure off), then polls the plan until the result is there. */
async function checkClean() {
  const p = S.plan;
  await api('POST', '/api/plan/' + encodeURIComponent(p.planId) + '/clean-check', {});
  S.cleanPolling = true;
  $('clean-result').className = 'result';
  $('clean-result').textContent = 'Checking: the top camera visits every pad…';
  try {
    const t0 = Date.now();
    for (;;) {
      await new Promise((r) => setTimeout(r, 600));
      const q = await api('GET', '/api/plan/' + encodeURIComponent(p.planId));
      const cc = q.cleanCheck;
      if (cc && !q.cleanCheckRunning) {
        if (S.plan === p) { S.plan.cleanCheck = cc; S.cleanCheck = cc; renderCleanCheck(); }
        toast(!cc.targets ? 'Clean-pad check: no pad was checked' : cc.complete === false ? 'The clean-pad check did not finish'
          : cc.ok ? 'All ' + cc.targets + ' pads are clean'
            : 'Paste left on ' + cc.dirtyPadIds.length + ' pad(s): wipe them and check again', !cc.ok);
        return;
      }
      if (!q.cleanCheckRunning || Date.now() - t0 > 15 * 60000) {
        $('clean-result').className = 'result bad';
        $('clean-result').textContent = 'The clean-pad check did not finish: see the message above.';
        return;
      }
    }
  } finally {
    S.cleanPolling = false;
  }
}

function padClass(p) {
  if (p.blocked) return 'blocked';
  return p.flags && p.flags.length ? 'warn' : 'ok';
}

function renderPlan(p) {
  const svg = $('plan-svg');
  svg.replaceChildren();
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const grow = (x, y, r) => {
    minX = Math.min(minX, x - r); maxX = Math.max(maxX, x + r);
    minY = Math.min(minY, y - r); maxY = Math.max(maxY, y + r);
  };
  p.pads.forEach((q) => grow(q.x, q.y, Math.max(q.w, q.h)));
  p.dots.forEach((d) => grow(d.x, d.y, 0.5));
  if (!Number.isFinite(minX)) { minX = 0; minY = 0; maxX = 10; maxY = 10; }
  const pad = Math.max(1, 0.04 * Math.max(maxX - minX, maxY - minY));
  minX -= pad; minY -= pad; maxX += pad; maxY += pad;
  // machine Y points up; SVG y points down: flip inside the group
  svg.setAttribute('viewBox', [minX, -maxY, maxX - minX, maxY - minY].join(' '));
  const g = svgEl('g', { transform: 'scale(1,-1)' });
  svg.appendChild(g);
  for (const q of p.pads) {
    g.appendChild(svgEl('rect', {
      class: 'pad ' + padClass(q), x: -q.w / 2, y: -q.h / 2, width: q.w, height: q.h,
      transform: 'translate(' + q.x + ' ' + q.y + ') rotate(' + q.rotation + ')',
      'data-pad': q.padId,
    }, q.padId + ' · ' + (q.packageId || '') + ' · ' + (q.typeKey || 'no pad type') + ' (' + (q.typeStatus || '') + ')\n'
      + fmt(q.w) + ' × ' + fmt(q.h) + ' mm · ' + q.dotCount + ' dot(s)'
      + (q.rotationPerDotDeg ? ' × ' + fmt(q.rotationPerDotDeg, 1) + '°' : '')
      + (q.flags.length ? '\n' + q.flags.join(', ') : '')));
  }
  if (p.dots.length > 1) {
    g.appendChild(svgEl('polyline', { class: 'path', points: p.dots.map((d) => d.x + ',' + d.y).join(' ') }));
  }
  S.dotEls = [];
  for (const d of p.dots) {
    const c = svgEl('circle', { class: 'dot', cx: d.x, cy: d.y, r: 0.25, 'data-index': d.index },
      'dot ' + (d.index + 1) + ' · ' + d.padId + ' · ' + fmt(d.rotationDeg, 1) + '° · Z ' + fmt(d.dispenseZ, 3));
    S.dotEls[d.index] = c;
    g.appendChild(c);
  }
  if (p.dots.length) {
    g.appendChild(svgEl('circle', { class: 'start', cx: p.dots[0].x, cy: p.dots[0].y, r: 0.25 }, 'first dot'));
  }
  const s = p.summary;
  fillKv($('plan-summary'), [
    ['Board', p.boardName],
    ['Pads', s.pads + (s.blockedPads ? ' (' + s.blockedPads + ' blocked)' : '')],
    ['Dots', s.dots],
    ['Paste per board', fmt(s.totalRotationDeg / 360, 2) + ' auger turns', 'Total net auger rotation of the board dots (purge dots not included)'],
    ['Path', fmt(s.pathLengthMm, 1) + ' mm'],
    ['Time (estimate)', s.estimatedSeconds ? fmtDuration(s.estimatedSeconds) : (s.estimateError || '—'),
      'The whole program as it will be streamed: settle, purge row, crossing, every dot and vent, with acceleration and dwells (no host delays)'],
    ['DPH (estimate)', s.estimatedWorkDph ? fmtInt(s.estimatedWorkDph) + ' dots only, ' + fmtInt(s.estimatedDph) + ' overall' : '—',
      'Dots only: from the crossing after the purge to the last dot, as the bench counts it (18 psi recipe: 5,490 measured). '
      + 'Overall: the whole program with settle, purge and vent. Both include acceleration and dwells, no host delays (about 3 % slow against the bench)'],
    ['Profile', p.profile ? profileText(p.profile) : '—', 'The dispense profile this plan was made with (Profiles tab); '
      + s.purgeDots + ' purge dots go on the purge row first'],
  ]);
  const il = p.interlocks;
  const box = $('plan-interlocks');
  box.replaceChildren();
  if (il && !il.ok) {
    box.appendChild(el('div', { class: 'banner', role: 'note' },
      el('div', {}, el('b', { text: 'This board cannot run yet:' }),
        el('ul', {}, il.violations.map((v) => el('li', { text: v.code + ': ' + v.message }))))));
  } else if (il) {
    box.appendChild(el('p', { class: 'result ok', text: 'All interlocks pass for this plan.' }));
  }
  const flagged = p.pads.filter((q) => q.flags.length);
  $('plan-flags-wrap').classList.toggle('hidden', !flagged.length && !p.warnings.length);
  $('plan-flags-title').textContent = flagged.length + ' pad(s) flagged' + (p.warnings.length ? ', ' + p.warnings.length + ' warning(s)' : '');
  $('plan-flags').replaceChildren(
    el('ul', {}, p.warnings.map((w) => el('li', { text: w }))),
    el('table', { class: 'tbl' }, el('thead', {}, el('tr', {}, ['Pad', 'Package', 'Type', 'Status', 'Flags'].map((h) => el('th', { text: h })))),
      el('tbody', {}, flagged.slice(0, 300).map((q) => el('tr', {},
        el('td', { text: q.padId }), el('td', { text: q.packageId || '' }), el('td', { text: q.typeKey || '—' }),
        el('td', { text: q.typeStatus || '' }), el('td', { text: q.flags.join(', ') })))))
  );
  if (p.cleanCheck && (!S.cleanCheck || S.cleanCheck.checkedAtMs < p.cleanCheck.checkedAtMs)) S.cleanCheck = p.cleanCheck;
  renderCleanCheck();
  if (S.status) renderPlanStale(S.status.dispense || {});
  refreshButtons();
}

function markDoneDots(run) {
  if (!S.plan || !S.dotEls || !run.planId) return;
  const pid = S.plan.planId;
  if (run.planId !== pid && !run.planId.startsWith(pid + '-subset-')) return;
  for (const i of run.doneDots) {
    const c = S.dotEls[i];
    if (c) c.classList.add('done');
  }
}

async function runBoard() {
  const p = S.plan;
  const maxDots = num('run-maxdots');
  const n = maxDots ? Math.min(maxDots, p.dots.length) : p.dots.length;
  const f = p.profile || {};
  if (!confirm('Dispense ' + n + ' dot(s) on ' + p.boardName + '?\n\nSet the regulator to ' + fmt(f.pressurePsi, 1) + ' psi (profile '
    + f.name + ') before you continue: there is no pressure sensor.\n\nThe machine moves and the pressure switches on: '
    + f.purgeDots + ' purge dots on the purge row, then the board without stopping.'
    + (S.cleanCheck && !S.cleanCheck.ok ? '\n\nThe last clean-pad check found paste on ' + S.cleanCheck.dirtyPadIds.length
      + ' pad(s): the run will be refused until they are clean.' : ''))) return;
  for (const c of S.dotEls || []) if (c) c.classList.remove('done');
  const r = await api('POST', '/api/run', { planId: p.planId, maxDots });
  toast('Run started: ' + r.dots + ' dots');
}

// ------------------------------------------------------------------------------------------
// Dispense tab
// ------------------------------------------------------------------------------------------

function showResult(id, text, ok) {
  const e = $(id);
  e.textContent = text;
  e.className = 'result ' + (ok ? 'ok' : 'bad');
}

async function pressure(on) {
  const r = await api('POST', on ? '/api/pressure/start' : '/api/pressure/stop');
  toast(r.note || r.lastEvent || ('Pressure ' + r.state));
}

async function prime() {
  const r = await api('POST', '/api/prime', { rotationDeg: num('prime-rot') });
  showResult('dispense-result', 'Prime started (' + r.rotationDeg + '°). Progress on the Run tab.', true);
}

async function testDot() {
  const r = await api('POST', '/api/test-dot', { rotationDeg: num('testdot-rot') });
  showResult('dispense-result', 'Test dot started (' + fmt(r.rotationDeg, 1) + '°) at X ' + fmt(r.location.x) + ' Y ' + fmt(r.location.y) + '.', true);
}

async function snapshot() {
  const img = $('cam-img');
  const url = '/api/camera/top.jpg?w=800&t=' + Date.now();
  try {
    const r = await fetch(url);
    if (!r.ok) {
      let reason = r.statusText;
      try { reason = (await r.json()).reason; } catch (e) { /* not JSON */ }
      throw new Error(reason);
    }
    const blob = await r.blob();
    if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
    img.dataset.url = URL.createObjectURL(blob);
    img.src = img.dataset.url;
    img.classList.remove('hidden');
    $('cam-empty').classList.add('hidden');
  } catch (e) {
    img.removeAttribute('src');
    img.classList.add('hidden');
    $('cam-empty').textContent = 'No picture: ' + e.message;
    $('cam-empty').classList.remove('hidden');
    if ($('cam-auto').checked) { $('cam-auto').checked = false; toggleCam(); }
    toast('Camera: ' + e.message, true);
  }
}

function toggleCam() {
  clearInterval(S.camTimer);
  S.camTimer = $('cam-auto').checked ? setInterval(snapshot, 2000) : null;
  if ($('cam-auto').checked) snapshot();
}

function nozzlePos() {
  const s = S.status;
  if (!s) return null;
  const n = (s.machine.positions || []).find((t) => t.kind === 'nozzle' && t.name === s.dispense.nozzle);
  return n || null;
}

/** The pick nozzle (N1): the nozzle that is not the dispenser. */
function n1Pos() {
  const s = S.status;
  if (!s) return null;
  const all = (s.machine.positions || []).filter((t) => t.kind === 'nozzle' && t.name !== s.dispense.nozzle);
  return all.find((t) => t.name === 'N1') || all[0] || null;
}

function renderSurfaces(d) {
  const all = d.surfaces || {};
  const name = $('surf-name').value || 'board';
  const sf = all[name];
  const key = name + JSON.stringify(sf || null);
  if (key === S.surfKey) return;
  S.surfKey = key;
  if (document.activeElement !== $('surf-points')) {
    $('surf-points').value = sf ? JSON.stringify(sf.points.map((p) => [p.x, p.y, p.z])) : '';
  }
  $('surf-state').textContent = !sf ? 'No ' + name + ' surface yet.'
    : sf.plane ? sf.points.length + ' points, Z ' + fmt(sf.plane.minZ, 3) + '…' + fmt(sf.plane.maxZ, 3)
      + ', slope ' + fmt(sf.plane.slopeMmPerMm * 1000, 2) + ' µm/mm, largest residual ' + fmt(sf.plane.maxResidualMm, 3)
      + ' mm · saved ' + fmtTime(sf.setAtMs)
      + (name === d.boardSurfaceName ? (sf.boundToBoard ? ' · tied to a board registration'
        : ' · NOT tied to a board: plan the board, then save again (runs are refused until then)') : '')
      : 'Cannot fit a plane: ' + sf.error;
}

function surfPoints() {
  const txt = $('surf-points').value.trim();
  if (!txt) return [];
  let v;
  try { v = JSON.parse(txt); } catch (e) { throw new Error('The points are not valid JSON: ' + e.message); }
  if (v && !Array.isArray(v) && Array.isArray(v.points)) v = v.points;
  if (!Array.isArray(v)) throw new Error('Points must be a list');
  return v.map((p) => (Array.isArray(p) ? { x: p[0], y: p[1], z: p[2] } : { x: p.x, y: p.y, z: p.z }));
}

function renderPlaces(d) {
  const locs = d.locations || {};
  const key = JSON.stringify(locs) + (d.wasteLocationName || '') + (d.blankLocationName || '');
  const tbody = $('places-table').tBodies[0];
  if (key === S.placesKey || tbody.contains(document.activeElement)) return;
  S.placesKey = key;
  const names = Object.keys(locs);
  for (const n of [d.wasteLocationName, d.blankLocationName]) if (n && !names.includes(n)) names.unshift(n);
  tbody.replaceChildren(...names.map((name) => placeRow(name, locs[name], d)));
  for (const id of ['shots-place', 'dl-place', 'to-place']) {
    const sel = $(id);
    const keep = sel.value;
    const opts = Object.keys(locs).filter((n) => n !== 'waste-test-dot');
    sel.replaceChildren(...(opts.length ? opts.map((n) => el('option', { value: n, text: n }))
      : [el('option', { value: '', text: 'no places yet: add them on the Dispense tab' })]));
    if (keep && locs[keep]) sel.value = keep;
    else if (id !== 'shots-place' && locs[d.blankLocationName]) sel.value = d.blankLocationName;
  }
}

function placeRow(name, loc, d) {
  const derived = name === 'waste-test-dot';
  const hint = name === d.wasteLocationName ? 'Waste pad: prime and test dots'
    : name === d.blankLocationName ? 'Blank: purge row (Purge row card), calibration dots' : derived ? 'Test dot spot (follows the waste pad)' : '';
  const inp = (v, axis) => el('input', { type: 'number', step: '0.01', value: v === undefined ? '' : v, disabled: derived,
    'data-axis': axis, 'aria-label': name + ' ' + axis.toUpperCase() });
  const x = inp(loc && loc.x, 'x'), y = inp(loc && loc.y, 'y'), z = inp(loc && loc.z, 'z');
  const useXY = el('button', { type: 'button', class: 'small', text: 'Use N2 X/Y', disabled: derived,
    title: 'Copy the dispenser needle\'s current X and Y',
    onclick: () => { const n = nozzlePos(); if (n) { x.value = n.x.toFixed(3); y.value = n.y.toFixed(3); } } });
  const useZ = el('button', { type: 'button', class: 'small', text: 'Use N2 Z (tip touching)', disabled: derived,
    title: 'If the needle tip is resting on the surface now: surface Z = N2 Z − needle offset (needs the needle Z frame)',
    onclick: () => {
      const nd = S.status.dispense.needle;
      if (!nd.anchored) { banner('Record the needle Z frame first (Needle card): without it, N2 Z cannot be converted to the surface height.'); return; }
      const n = nozzlePos(); if (n) z.value = (n.z - nd.zOffsetMm).toFixed(3);
    } });
  const useZ1 = el('button', { type: 'button', class: 'small', text: 'Use N1 Z (tip touching)', disabled: derived,
    title: 'If the N1 nozzle tip is resting on the surface now: surface Z = N1 Z',
    onclick: () => { const n = n1Pos(); if (n) z.value = n.z.toFixed(3); } });
  const save = el('button', { type: 'button', class: 'small primary', text: 'Save', disabled: derived, 'data-action': 'save',
    onclick: (ev) => act(ev.target, async () => {
      if (x.value === '' || y.value === '' || z.value === '') throw new Error('Enter X, Y and Z for ' + name);
      await api('PUT', '/api/locations/' + encodeURIComponent(name), { x: Number(x.value), y: Number(y.value), z: Number(z.value) });
      S.placesKey = null;
      toast('Saved place ' + name);
    }) });
  return el('tr', { 'data-place': name }, el('td', { title: hint }, el('b', { text: name }), hint ? el('div', { class: 'hint', text: hint }) : null),
    el('td', {}, x), el('td', {}, y), el('td', {}, z), el('td', {}, el('div', { class: 'btns' }, useXY, useZ1, useZ, save)));
}

function addPlace() {
  const name = str('place-new');
  if (!name || !/^[A-Za-z0-9_-]{1,40}$/.test(name)) { banner('A place name uses letters, digits, - and _ only.'); return; }
  const tbody = $('places-table').tBodies[0];
  tbody.appendChild(placeRow(name, null, S.status.dispense));
  $('place-new').value = '';
}

async function loadSettings() {
  S.settingsLoaded = true;
  try {
    fillSettings(await api('GET', '/api/config'));
  } catch (e) {
    S.settingsLoaded = false;
  }
}

function fillSettings(c) {
  $('set-maxon').value = c.maxContinuousOnTimeS;
  const sel = $('set-minstatus');
  sel.replaceChildren(...Object.keys(STATUS_LABELS).map((k) => el('option', { value: k, text: STATUS_LABELS[k] })));
  sel.value = c.minPadTypeStatus;
  $('set-clean').checked = c.cleanPadCheck.enabled;
  $('set-json').value = JSON.stringify(c, null, 2);
}

async function saveSettings() {
  const c = await api('PUT', '/api/config', {
    maxContinuousOnTimeS: num('set-maxon'), minPadTypeStatus: $('set-minstatus').value,
    cleanPadCheck: { enabled: $('set-clean').checked },
  });
  fillSettings(c);
  toast('Settings saved');
}

// ------------------------------------------------------------------------------------------
// Dispense profile (Profiles tab) and purge row (Dispense tab)
// ------------------------------------------------------------------------------------------

const PF_FIELDS = ['pf-name', 'pf-psi', 'pf-dwell', 'pf-turn', 'pf-suck', 'pf-gap', 'pf-rpm', 'pf-accel', 'pf-purge', 'pf-notes'];

/** Whole fractions of a revolution the screw may turn per dot (1/8 .. 2 turns), plus the current value. */
function fillTurnOptions(p) {
  const rev = p.stepsPerRev;
  const steps = [rev / 8, rev / 4, rev / 2, rev, 2 * rev].filter((n) => Number.isInteger(n));
  if (!steps.includes(p.netStepsPerDot)) steps.push(p.netStepsPerDot);
  steps.sort((a, b) => a - b);
  $('pf-turn').replaceChildren(...steps.map((n) => el('option', { value: n, text: turnLabel(n, rev) + ' (' + n + ' steps)' })));
}

function renderProfile(d) {
  const p = d.profile;
  if (!p) return;
  $('pf-title-name').textContent = '· ' + p.name;
  const key = p.fingerprint + '|' + p.name + '|' + p.notes;
  if (key !== S.pfKey && !S.pfDirty) {
    S.pfKey = key;
    fillTurnOptions(p);
    $('pf-name').value = p.name;
    $('pf-psi').value = p.pressurePsi;
    $('pf-dwell').value = p.dwellMs;
    $('pf-turn').value = p.netStepsPerDot;
    $('pf-suck').value = p.suckBackDeg;
    $('pf-gap').value = p.gapMm;
    $('pf-rpm').value = p.screwRpm;
    $('pf-accel').value = p.screwAccelDegS2;
    $('pf-purge').value = p.purgeDots;
    $('pf-notes').value = p.notes || '';
    $('pf-angle').value = p.startAngleDeg;
  }
  renderTurnInfo(p);
  $('pf-problems').replaceChildren(...(p.problems || []).map((x) => el('li', { text: x })));
  fillKv($('pf-angle-kv'), [
    ['Start angle', fmt(p.startAngleDeg, 1) + '°'],
    ['Dots start at', p.partialTurn ? p.startAngles.map((a) => fmt(a, 1) + '°').join(' / ') : 'any angle (whole turns)',
      'A half turn alternates between two start angles'],
    ['Set', p.startAngleSetAtMs ? fmtTime(p.startAngleSetAtMs) : 'never'],
    ['Controller connected', p.controllerConnectedAtMs ? fmtTime(p.controllerConnectedAtMs) : '—'],
  ]);
  const w = $('pf-angle-warn');
  w.classList.toggle('hidden', !p.startAngleNeedsConfirm);
  w.textContent = !p.startAngleNeedsConfirm ? '' : !p.startAngleSetAtMs
    ? 'The start angle has never been set: set it (or confirm it) before a board run (I11).'
    : 'OpenPnP connected to the controller (' + fmtTime(p.controllerConnectedAtMs) + ') after the angle was set: press "Still valid" '
      + 'if the controller was not switched off, otherwise measure the angle again (I11).';
}

/** Forward / back / net turn of a dot: from the status, or as it will be after saving the edited values. */
function renderTurnInfo(p) {
  const steps = Number($('pf-turn').value) || p.netStepsPerDot;
  const suck = $('pf-suck').value === '' ? p.suckBackDeg : Number($('pf-suck').value);
  const rpm = $('pf-rpm').value === '' ? p.screwRpm : Number($('pf-rpm').value);
  let net = p.netDeg, fwd = p.forwardDeg, back = p.suckBackSteps, feed = p.feedDegPerMin;
  const edited = steps !== p.netStepsPerDot || suck !== p.suckBackDeg || rpm !== p.screwRpm;
  if (edited) {
    back = Math.round(suck * p.stepsPerDeg);
    net = steps / p.stepsPerDeg;
    fwd = (steps + back) / p.stepsPerDeg;
    feed = rpm * 360;
  }
  $('pf-turn-info').textContent = (edited ? 'After saving: ' : '') + 'each dot turns the screw forward ' + fmt(fwd, 2) + '°, then back '
    + fmt(fwd - net, 2) + '° (' + back + ' steps): net ' + fmt(net, 2) + '° per dot (' + turnLabel(steps, p.stepsPerRev) + ', '
    + p.stepsPerDeg + ' steps/°). Screw feed ' + fmtInt(feed) + '°/min (G1 B F' + Math.round(feed) + ').';
  $('pf-rpm-warn').classList.toggle('hidden', !(rpm > 180));
}

async function saveProfile() {
  const body = {
    name: str('pf-name'), pressurePsi: num('pf-psi'), dwellMs: num('pf-dwell'), netStepsPerDot: Number($('pf-turn').value),
    suckBackDeg: num('pf-suck'), gapMm: num('pf-gap'), screwRpm: num('pf-rpm'), screwAccelDegS2: num('pf-accel'),
    purgeDots: num('pf-purge'), notes: $('pf-notes').value,
  };
  if (body.screwRpm > 180 && !confirm('Run the screw at ' + body.screwRpm + ' rpm? The bench runs 180 rpm: never faster than the '
    + 'paste feeds the screw (it pulls in air and needs 30 to 50 turns to recover).')) return;
  await api('PUT', '/api/config', { profile: body });
  S.pfDirty = false;
  S.pfKey = null;
  refreshSettingsJson();
  loadPadTypes(); // their dot plan shows the profile's turn
  toast('Profile saved. Set the regulator to ' + fmt(body.pressurePsi, 1) + ' psi; plan the board again before a run.');
}

async function startAngle(set) {
  const body = {};
  if (set) {
    body.startAngleDeg = num('pf-angle');
    if (body.startAngleDeg === undefined) throw new Error('Enter the start angle in degrees (0 to 360)');
  }
  const p = await api('POST', '/api/screw/start-angle', body);
  $('pf-angle').value = p.startAngleDeg;
  refreshSettingsJson();
  refreshPlan(); // I11 in the plan shown
  toast(set ? 'Start angle set to ' + fmt(p.startAngleDeg, 1) + '°: plan the board again.' : 'Start angle ' + fmt(p.startAngleDeg, 1)
    + '° confirmed for this controller power-up.');
}

const PR_FIELDS = ['pr-surface', 'pr-x0', 'pr-y0', 'pr-pitchx', 'pr-pitchy', 'pr-spots'];

function renderPurgeRow(d) {
  const r = d.purgeRow;
  if (!r) return;
  const per = r.perBoard;
  $('pr-state').textContent = !r.defined ? 'Not set: board runs are refused (I9).'
    : r.spotsLeft + ' of ' + r.spots + ' spots left' + (per ? ' · ' + r.boardsLeft + ' board(s) at ' + per + ' purge dots' : '');
  fillKv($('pr-kv'), [
    ['Used spots', r.usedSpots],
    ['Surface probed', r.surfaceProbed ? 'yes (' + r.surfaceName + ')' : 'NO: probe the ' + r.surfaceName + ' surface (Surfaces card)'],
    ['Last wiped', r.resetAtMs ? fmtTime(r.resetAtMs) : '—'],
  ]);
  $('run-purge').textContent = !per ? 'The profile has no purge dots.' : !r.defined ? 'Purge row not set (Dispense tab): board runs are refused.'
    : 'Purge row: ' + r.spotsLeft + ' spots left = ' + r.boardsLeft + ' board(s); wipe it and reset it on the Dispense tab when it is full.';
  const names = Object.keys(d.surfaces || {});
  for (const n of [r.surfaceName, d.blankLocationName]) if (n && !names.includes(n)) names.push(n);
  const key = JSON.stringify([r.surfaceName, r.x0Mm, r.y0Mm, r.pitchXMm, r.pitchYMm, r.spots, names]);
  if (key === S.prKey || S.prDirty) return;
  S.prKey = key;
  $('pr-surface').replaceChildren(...names.map((n) => el('option', { value: n, text: n })));
  $('pr-surface').value = r.surfaceName;
  $('pr-x0').value = r.x0Mm === null ? '' : r.x0Mm;
  $('pr-y0').value = r.y0Mm === null ? '' : r.y0Mm;
  $('pr-pitchx').value = r.pitchXMm;
  $('pr-pitchy').value = r.pitchYMm;
  $('pr-spots').value = r.spots;
}

async function savePurgeRow() {
  const old = S.status && S.status.dispense ? S.status.dispense.purgeRow : null;
  const row = {
    surfaceName: $('pr-surface').value, x0Mm: num('pr-x0'), y0Mm: num('pr-y0'), pitchXMm: num('pr-pitchx'),
    pitchYMm: num('pr-pitchy'), spots: num('pr-spots'),
  };
  await api('PUT', '/api/config', { purgeRow: row });
  // a row somewhere else starts with all its spots free, but only if it is clean (the used count is the engine's)
  const moved = old && old.usedSpots > 0 && (old.surfaceName !== row.surfaceName || old.x0Mm !== row.x0Mm || old.y0Mm !== row.y0Mm);
  if (moved && confirm('The purge row moved. Is the new row clean? Then all its spots will be used from the first one.')) {
    await api('POST', '/api/purge-row/reset');
  }
  S.prDirty = false;
  S.prKey = null;
  refreshSettingsJson();
  refreshPlan(); // I9 in the plan shown
  toast('Purge row saved');
}

async function resetPurgeRow() {
  if (!confirm('Is the purge row wiped clean? All its spots will be used again.')) return;
  const r = await api('POST', '/api/purge-row/reset');
  S.prKey = null;
  refreshSettingsJson();
  refreshPlan();
  toast('Purge row reset: ' + r.spotsLeft + ' spots free');
}

/** Re-reads the plan shown: its interlocks follow the purge row, start angle, needle and surfaces. */
async function refreshPlan() {
  const p = S.plan;
  if (!p) return;
  try {
    const q = await api('GET', '/api/plan/' + encodeURIComponent(p.planId));
    if (S.plan === p) {
      S.plan = q;
      renderPlan(q);
    }
  } catch (e) { /* the server forgot the plan (OpenPnP restarted): plan the board again */ }
}

/** True if the plan shown is for the board selected on the Run tab, at the position entered there. */
function planMatchesBoard(p) {
  if (!p || !p.source) return false;
  const c = boardChoice();
  if (p.source.source === 'job') return c.kind === 'job' && c.id === p.source.boardLocationId;
  return c.kind !== 'job' && c.file === p.source.file && num('board-x') === p.source.x && num('board-y') === p.source.y
    && (num('board-rot') || 0) === (p.source.rotation || 0);
}

/** Keeps the full-settings editor in step after a card saved part of the settings (stamped fields change). */
async function refreshSettingsJson() {
  if (document.activeElement === $('set-json')) return;
  try { $('set-json').value = JSON.stringify(await api('GET', '/api/config'), null, 2); } catch (e) { /* next save shows it */ }
}

async function saveSettingsJson() {
  let body;
  try { body = JSON.parse($('set-json').value); } catch (e) { throw new Error('The settings are not valid JSON: ' + e.message); }
  fillSettings(await api('PUT', '/api/config', body));
  toast('All settings saved');
}

// ------------------------------------------------------------------------------------------
// Calibrate tab
// ------------------------------------------------------------------------------------------

async function loadCalibrations() {
  let c;
  try { c = await api('GET', '/api/calibrations'); } catch (e) { return; }
  const cur = c.current;
  $('cal-current').textContent = cur ? 'Current: #' + cur.id + ' — q = ' + fmt(cur.qMm3PerRev, 4) + ' mm³ per auger turn ('
    + cur.status + (cur.method !== cur.status ? ', ' + cur.method : '') + ')' : 'No calibration';
  if (cur && !$('sd-cal').value) $('sd-cal').value = cur.id;
  $('cal-table').tBodies[0].replaceChildren(...c.calibrations.slice().reverse().map((k) => el('tr', {},
    el('td', { text: k.id }), el('td', { text: k.method }), el('td', {}, el('span', { class: 'badge ' + (k.status === 'characterized' ? 'characterized' : 'provisional'), text: k.status })),
    el('td', { class: 'num', text: fmt(k.qMm3PerRev, 4) }), el('td', { class: 'num', text: k.qCv === null ? '—' : fmt(100 * k.qCv, 1) + ' %' }),
    el('td', { class: 'num', text: k.qN === null ? '—' : k.qN }), el('td', { text: fmtTime(k.createdAtMs) }), el('td', { text: k.notes || '' }))));
  $('ladder-table').tBodies[0].replaceChildren(...c.ladder.map((r) => el('tr', {},
    el('td', { class: 'num', text: fmt(r.rotationDeg, 1) }), el('td', { class: 'num', text: r.n }),
    el('td', { class: 'num', text: fmt(r.diameterMeanMm, 3) }), el('td', { class: 'num', text: r.diameterCv === null ? '—' : fmt(100 * r.diameterCv, 1) + ' %' }),
    el('td', { class: 'num', text: fmt(r.mgPerDotMean, 3) }), el('td', { class: 'num', text: fmt(r.heightMm, 3) }), el('td', { text: r.method || '' }))));
  if (!c.ladder.length) $('ladder-table').tBodies[0].appendChild(el('tr', {}, el('td', { colspan: 7, class: 'hint', text: 'No dot ladder rows (a history only: boards use the dispense profile).' })));
}

async function shotsRun() {
  const r = await api('POST', '/api/calibrations/shots-run', { locationName: $('shots-place').value, rotationDeg: num('shots-rot'), count: num('shots-count') });
  showResult('cal-result', 'Dispensing ' + r.count + ' shots of ' + r.rotationDeg + '° at ' + r.locationName + '. Weigh the cup when it is done.', true);
  $('ss-rot').value = r.rotationDeg;
  $('ss-count').value = r.count;
}

async function saveScaleShots() {
  const each = str('ss-each');
  const r = await api('POST', '/api/calibrations/scale-shots', {
    rotationDegPerShot: num('ss-rot'), count: num('ss-count'), totalMassMg: num('ss-total'), perShotMassMg: each,
    tip: str('ss-tip'), pasteLot: str('ss-lot'), setPressurePsi: num('ss-psi'), barrelTempC: num('ss-temp'),
    cartridge: str('ss-cart'), operator: str('ss-op'), markCharacterized: $('ss-char').checked,
  });
  showResult('cal-result', 'Saved calibration #' + r.calibration.id + ': q = ' + fmt(r.calibration.qMm3PerRev, 4) + ' mm³/turn'
    + (r.calibration.qCv !== null ? ', CV ' + fmt(100 * r.calibration.qCv, 1) + ' %' : '') + ' (' + r.calibration.status + ').', true);
  loadCalibrations();
}

async function saveScaleDots() {
  const r = await api('POST', '/api/calibrations/scale-dots', {
    calibrationId: num('sd-cal'), rotationDeg: num('sd-rot'), count: num('sd-count'), totalMassMg: num('sd-mass'),
    scaleSdMg: num('sd-sd'), diameterMeanMm: num('sd-dia'),
  });
  showResult('cal-result', 'Saved: ' + fmt(r.row.mgPerDotMean, 4) + ' mg per dot at ' + r.row.rotationDeg + '°'
    + (r.row.mgPerDotSe !== null ? ' ± ' + fmt(r.row.mgPerDotSe, 4) : '') + '.', true);
  loadCalibrations();
}

async function dotLadder() {
  const r = await api('POST', '/api/calibrations/dot-ladder', {
    rotationsDeg: $('dl-rots').value, repeats: num('dl-rep'), pitchMm: num('dl-pitch'), locationName: $('dl-place').value,
    primeAtWaste: $('dl-prime').checked,
  });
  showResult('cal-result', 'Dot ladder started: ' + r.dots + ' dots. Progress on the Run tab; results appear below.', true);
}

async function touchOff() {
  const r = await api('POST', '/api/calibrations/touch-off', {
    startGapMm: num('to-start'), stepMm: num('to-step'), steps: num('to-steps'), rotationDeg: num('to-rot'),
    locationName: $('to-place').value, apply: $('to-apply').checked,
  });
  showResult('cal-result', 'Touch-off started' + (r.apply ? ' (the result will be used)' : ' (result shown only)') + '.', true);
}

// ------------------------------------------------------------------------------------------
// Profiles tab
// ------------------------------------------------------------------------------------------

async function loadPadTypes() {
  try {
    S.padTypes = await api('GET', '/api/pad-types');
  } catch (e) {
    $('profiles').replaceChildren(el('p', { class: 'error', text: 'Could not load pad types: ' + errorText(e) }));
    return;
  }
  renderPadTypes();
}

function renderPadTypes() {
  const d = S.padTypes;
  const showRetired = $('show-retired').checked;
  const prof = S.status && S.status.dispense && S.status.dispense.profile;
  $('profiles-hint').textContent = 'A board refuses pads whose type is below "' + (STATUS_LABELS[d.minPadTypeStatus] || d.minPadTypeStatus)
    + '". Every dot is one dot of the dispense profile' + (prof ? ' "' + prof.name + '"' : '') + ' (above).';
  const out = [];
  for (const fam of d.families) {
    if (fam.retired && !showRetired) continue;
    out.push(el('div', { class: 'family card', 'data-family': fam.family },
      el('h2', { text: fam.family }),
      el('table', { class: 'tbl' },
        el('thead', {}, el('tr', {}, [
          ['Name'], ['Status', 'not started → provisional → characterized → validated on a board'],
          ['Pad (mm)'], ['t', 'Paste thickness (mm)'], ['k', 'Fraction of the pad area covered'],
          ['Dot plan', 'Dots per pad (one, or a grid if the pad rule allows more dots on a large pad), each one dot of the dispense profile'],
          ['Profile', 'The dispense profile the dots use'], ['Last validated'], ['Notes'], [''],
        ].map(([h, t]) => el('th', { text: h, title: t })))),
        el('tbody', {}, fam.types.length ? fam.types.map(padTypeRow)
          : [el('tr', {}, el('td', { colspan: 10, class: 'hint', text: 'No types yet' }))]))));
  }
  $('profiles').replaceChildren(...out);
}

function padTypeRow(t) {
  const btn = (text, title, fn) => el('button', { type: 'button', class: 'small', text, title, onclick: (ev) => act(ev.target, fn) });
  return el('tr', { class: t.family === 'Retired' ? 'retired' : '', 'data-key': t.typeKey },
    el('td', { title: t.typeKey + ' v' + t.version + (t.packagePattern ? '\npackages: ' + t.packagePattern : '') },
      el('b', { text: t.name }), el('div', { class: 'hint', text: t.typeKey + ' · v' + t.version })),
    el('td', {}, el('span', { class: 'badge ' + t.status, text: STATUS_LABELS[t.status] || t.status })),
    el('td', { class: 'num', text: fmt(t.padWMm, 3) + ' × ' + fmt(t.padHMm, 3) }),
    el('td', { class: 'num', text: fmt(t.tMm, 3) }),
    el('td', { class: 'num', text: fmt(t.k, 2) }),
    el('td', { title: 'A stencil of this t and k would print ' + fmt(t.targetMassMg, 3) + ' mg (for information)' },
      t.dotsPerPad ? t.dotsPerPad + ' dot' + (t.dotsPerPad === 1 ? '' : 's') + ' × ' + fmt(t.rotationPerDotDeg, 1) + '°' : '—',
      t.rule && t.rule.maxDots ? el('div', { class: 'hint', text: 'up to ' + t.rule.maxDots + ' dots' }) : null),
    el('td', { text: t.profileName || '—' }),
    el('td', { text: t.lastValidatedAtMs ? fmtTime(t.lastValidatedAtMs) : '—' }),
    el('td', { class: 'hint', text: t.notes || '' }),
    el('td', {}, el('div', { class: 'btns' },
      btn('Edit', 'Change this type (saved as a new version)', () => openPadType(t)),
      btn('Set up', 'Calibrate this pad size: dot ladder and scale around its dot size', () => setUp(t, false)),
      btn('Validate', 'Dispense a coupon at this size and compare with the prediction', () => setUp(t, true)),
      btn('History', 'Every saved version', () => showHistory(t.typeKey)),
      t.family === 'Retired' ? null : btn('Retire', 'Hide it and never use it again (kept in the history)', () => retire(t)))));
}

async function loadPackagesOnce() {
  if (S.packages) return;
  try { S.packages = await api('GET', '/api/packages'); } catch (e) { S.packages = []; }
  const sel = $('pt-footprint');
  sel.replaceChildren(el('option', { value: '', text: '—' }));
  for (const p of S.packages) {
    if (!p.pads.length) continue;
    p.pads.forEach((pad, i) => sel.appendChild(el('option', { value: p.id + '\u0000' + i,
      text: p.id + ' pad ' + pad.name + ' (' + fmt(pad.w, 3) + ' × ' + fmt(pad.h, 3) + ' mm)' })));
  }
  if (sel.options.length === 1) sel.appendChild(el('option', { value: '', text: 'no package in OpenPnP has footprint pads', disabled: true }));
}

function fillFamilySelect(value) {
  const sel = $('pt-family');
  const fams = FAMILIES.slice();
  if (S.padTypes) for (const f of S.padTypes.families) if (!fams.includes(f.family) && !f.retired) fams.push(f.family);
  sel.replaceChildren(...fams.map((f) => el('option', { value: f, text: f })), el('option', { value: '__other', text: 'Other…' }));
  if (value && !fams.includes(value)) { sel.value = '__other'; $('pt-family-other').value = value; }
  else sel.value = value || 'Custom';
  $('pt-family-other-wrap').classList.toggle('hidden', sel.value !== '__other');
}

async function openPadType(t) {
  await loadPackagesOnce();
  S.editingKey = t ? t.typeKey : null;
  $('dlg-padtype-title').textContent = t ? 'Edit ' + t.name + ' (saved as version ' + (t.version + 1) + ')' : 'New pad type';
  fillFamilySelect(t ? t.family : 'Custom');
  $('pt-layout').replaceChildren(...Object.keys(LAYOUT_LABELS).map((k) => el('option', { value: k, text: LAYOUT_LABELS[k] })));
  $('pt-status').replaceChildren(...Object.keys(STATUS_LABELS).map((k) => el('option', { value: k, text: STATUS_LABELS[k] })));
  $('pt-name').value = t ? t.name : '';
  $('pt-key').value = t ? t.typeKey : '';
  $('pt-key').disabled = !!t;
  $('pt-w').value = t ? t.padWMm : '';
  $('pt-h').value = t ? t.padHMm : '';
  $('pt-round').value = t ? t.padRoundness : 0;
  $('pt-layout').value = t ? t.layout : 'single';
  $('pt-pitch').value = t && t.pitchMm !== null ? t.pitchMm : '';
  $('pt-status').value = t ? t.status : 'not-started';
  $('pt-t').value = t ? t.tMm : 0.10;
  $('pt-k').value = t ? t.k : 1.0;
  $('pt-pattern').value = t && t.packagePattern ? t.packagePattern : '';
  $('pt-notes').value = t && t.notes ? t.notes : '';
  $('pt-footprint').value = '';
  $('pt-error').textContent = '';
  $('dlg-padtype').showModal();
  $('pt-name').focus();
}

function pickFootprint() {
  const v = $('pt-footprint').value;
  if (!v) return;
  const [id, i] = v.split('\u0000');
  const pkg = S.packages.find((p) => p.id === id);
  const pad = pkg && pkg.pads[Number(i)];
  if (!pad) return;
  $('pt-w').value = Number(pad.w.toFixed(4));
  $('pt-h').value = Number(pad.h.toFixed(4));
  $('pt-round').value = pad.roundness || 0;
  if (!$('pt-pattern').value) $('pt-pattern').value = '^' + id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
  if (!$('pt-layout').value || $('pt-layout').value === 'single') $('pt-layout').value = 'footprint-only';
}

async function savePadType(ev) {
  ev.preventDefault();
  const fam = $('pt-family').value === '__other' ? str('pt-family-other') : $('pt-family').value;
  const body = {
    family: fam, name: str('pt-name'), padWMm: num('pt-w'), padHMm: num('pt-h'), padRoundness: num('pt-round') || 0,
    layout: $('pt-layout').value, pitchMm: num('pt-pitch') === undefined ? null : num('pt-pitch'), tMm: num('pt-t'), k: num('pt-k'),
    status: $('pt-status').value, packagePattern: str('pt-pattern') || null, notes: str('pt-notes') || null,
  };
  if (!S.editingKey && str('pt-key')) body.typeKey = str('pt-key');
  try {
    const r = S.editingKey ? await api('PUT', '/api/pad-types/' + encodeURIComponent(S.editingKey), body)
      : await api('POST', '/api/pad-types', body);
    $('dlg-padtype').close();
    toast('Saved ' + r.padType.name + ' (' + r.padType.typeKey + ' v' + r.padType.version + ')');
    await loadPadTypes();
  } catch (e) {
    $('pt-error').textContent = errorText(e);
  }
}

async function showHistory(key) {
  const h = await api('GET', '/api/pad-types/' + encodeURIComponent(key));
  $('dlg-history-title').textContent = 'History of ' + h.current.name + ' (' + key + ')';
  $('history-body').replaceChildren(el('table', { class: 'tbl' },
    el('thead', {}, el('tr', {}, ['v', 'Date', 'Family', 'Status', 'Pad (mm)', 't', 'k', 'Notes'].map((x) => el('th', { text: x })))),
    el('tbody', {}, h.history.slice().reverse().map((v) => el('tr', {},
      el('td', { text: v.version }), el('td', { text: fmtTime(v.createdAtMs) }), el('td', { text: v.family }),
      el('td', { text: STATUS_LABELS[v.status] || v.status }), el('td', { text: fmt(v.padWMm, 3) + ' × ' + fmt(v.padHMm, 3) }),
      el('td', { text: fmt(v.tMm, 3) }), el('td', { text: fmt(v.k, 2) }), el('td', { text: v.notes || '' }))))));
  $('dlg-history').showModal();
}

async function retire(t) {
  if (!confirm('Retire "' + t.name + '"? It stays in the history but is never matched or dispensed.')) return;
  await api('DELETE', '/api/pad-types/' + encodeURIComponent(t.typeKey));
  toast('Retired ' + t.name);
  await loadPadTypes();
}

/** Set up / Validate: open Calibrate with this type's dot (one profile dot) filled in, plus the steps. */
function setUp(t, validate) {
  const r = t.rotationPerDotDeg;
  showTab('calibrate');
  if (r) {
    $('dl-rots').value = [0.6, 0.8, 1, 1.2, 1.4].map((f) => (r * f).toFixed(1)).join(', ');
    $('sd-rot').value = r.toFixed(2);
    $('to-rot').value = ''; // empty = one profile dot
  }
  const g = $('setup-guide');
  g.replaceChildren(el('b', { text: (validate ? 'Validating ' : 'Setting up ') + t.name + ' (' + t.typeKey + ')' }),
    el('ol', {}, validate ? [
      el('li', { text: 'Dispense a coupon of profile dots (' + fmt(r, 1) + '°) on a weighed slide (a board run of a coupon board, or Scale: shots with the slide\'s place).' }),
      el('li', { text: 'Weigh it and enter the batch under "Scale: dot batch". A stencil would print ' + fmt(t.targetMassMg, 3) + ' mg per pad.' }),
      el('li', { text: 'If the result matches your tolerance, edit the type in Profiles and set its status to "validated on a board".' }),
    ] : [
      el('li', { text: 'Needle Z frame recorded, blank place and blank surface set, needle touched off (Dispense tab).' }),
      el('li', { text: 'Run the dot ladder (rotations filled in around one profile dot, ' + fmt(r, 1) + '°) and/or weigh a dot batch: a record, the dose stays the profile\'s.' }),
      el('li', { text: 'When the dots are repeatable, edit the type in Profiles and set its status to "characterized".' }),
    ]));
  g.classList.remove('hidden');
}

// ------------------------------------------------------------------------------------------
// Wiring
// ------------------------------------------------------------------------------------------

function wire() {
  for (const b of document.querySelectorAll('.tabs [role=tab]')) b.addEventListener('click', () => showTab(b.dataset.tab));
  $('btn-stop').addEventListener('click', stopAll);
  $('banner-close').addEventListener('click', () => $('banner').classList.add('hidden'));
  $('btn-start').addEventListener('click', (ev) => act(ev.target, async () => { await api('POST', '/api/machine/enable'); toast('Machine started'); }));
  $('btn-home').addEventListener('click', (ev) => act(ev.target, async () => { await api('POST', '/api/machine/home'); toast('Homing…'); }));

  $('board-select').addEventListener('change', onBoardChange);
  $('board-file').addEventListener('change', onBoardChange);
  $('btn-plan').addEventListener('click', (ev) => act(ev.target, planBoard));
  $('btn-run').addEventListener('click', (ev) => act(ev.target, runBoard));
  $('btn-clean-check').addEventListener('click', (ev) => act(ev.target, checkClean));

  $('btn-pressure-on').addEventListener('click', (ev) => act(ev.target, () => pressure(true)));
  $('btn-pressure-off').addEventListener('click', (ev) => act(ev.target, () => pressure(false)));
  $('btn-prime').addEventListener('click', (ev) => act(ev.target, prime));
  $('btn-testdot').addEventListener('click', (ev) => act(ev.target, testDot));
  $('btn-cam').addEventListener('click', snapshot);
  $('cam-auto').addEventListener('change', toggleCam);
  $('btn-place-add').addEventListener('click', addPlace);
  $('btn-needle-changed').addEventListener('click', (ev) => act(ev.target, async () => {
    if (!confirm('Mark the needle as changed? Dispensing is blocked until the needle Z frame is recorded again and the needle is touched off.')) return;
    await api('POST', '/api/needle/changed');
    toast('Needle marked as changed');
    refreshSettingsJson();
    refreshPlan();
  }));
  $('btn-needle-offset').addEventListener('click', (ev) => act(ev.target, async () => {
    const off = num('needle-offset');
    if (off === undefined) throw new Error('Enter the offset in mm');
    await api('POST', '/api/needle/offset', { offsetMm: off });
    toast('Needle offset saved');
    refreshSettingsJson();
    refreshPlan();
  }));
  $('btn-anchor-n1').addEventListener('click', () => { const n = n1Pos(); if (n) $('anchor-n1').value = n.z.toFixed(3); });
  $('btn-anchor-n2').addEventListener('click', () => { const n = nozzlePos(); if (n) $('anchor-n2').value = n.z.toFixed(3); });
  $('btn-anchor').addEventListener('click', (ev) => act(ev.target, async () => {
    const n1 = num('anchor-n1'), n2 = num('anchor-n2');
    if (n1 === undefined || n2 === undefined) throw new Error('Enter both contact heights');
    const r = await api('POST', '/api/needle/anchor', { surfaceZ: n1, needleContactZ: n2 });
    toast('Needle frame recorded: offset ' + fmt(r.offsetMm, 3) + ' mm. Touch off the needle next.');
    refreshSettingsJson();
    refreshPlan();
  }));
  $('btn-anchor-k').addEventListener('click', (ev) => act(ev.target, async () => {
    const n1 = num('anchor-n1'), k = num('anchor-k');
    if (n1 === undefined || k === undefined) throw new Error('Enter the N1 contact Z and K');
    const r = await api('POST', '/api/needle/anchor', { surfaceZ: n1, needleContactRawSum: k });
    $('anchor-n2').value = r.anchor.needleContactZMm.toFixed(3);
    toast('Needle frame recorded from K = ' + fmt(k, 2) + ': needle contact Z ' + fmt(r.anchor.needleContactZMm, 3)
      + ', offset ' + fmt(r.offsetMm, 3) + ' mm. Touch off the needle (or confirm the offset) next.');
    refreshSettingsJson();
    refreshPlan();
  }));
  $('btn-trim').addEventListener('click', (ev) => act(ev.target, async () => {
    const dx = num('trim-dx'), dy = num('trim-dy');
    if (dx === undefined || dy === undefined) throw new Error('Enter dx and dy in mm (0 for none)');
    await api('PUT', '/api/config', { needleXyTrim: { dxMm: dx, dyMm: dy, note: str('trim-note') || null } });
    S.trimKey = null;
    refreshSettingsJson();
    toast('Needle XY trim saved: plan the board again so the preview matches');
  }));
  $('surf-name').addEventListener('change', () => { S.surfKey = null; if (S.status) renderSurfaces(S.status.dispense); });
  $('btn-surf-add').addEventListener('click', (ev) => act(ev.target, async () => {
    const n = n1Pos();
    if (!n) throw new Error('No N1 position');
    const pts = surfPoints();
    pts.push({ x: Number(n.x.toFixed(3)), y: Number(n.y.toFixed(3)), z: Number(n.z.toFixed(3)) });
    $('surf-points').value = JSON.stringify(pts.map((p) => [p.x, p.y, p.z]));
  }));
  $('btn-surf-save').addEventListener('click', (ev) => act(ev.target, async () => {
    const name = $('surf-name').value;
    const body = { points: surfPoints() };
    // a board surface belongs to the registration of the board it was probed on (I4: re-probe after a re-seat):
    // tie it to the plan shown, if that plan is for the board selected on the Run tab
    const isBoard = name === S.status.dispense.boardSurfaceName;
    const tie = isBoard && planMatchesBoard(S.plan);
    if (tie) body.planId = S.plan.planId;
    await api('PUT', '/api/surfaces/' + encodeURIComponent(name), body);
    S.surfKey = null;
    toast('Saved surface ' + name + (tie ? ', tied to the planned board ' + S.plan.boardName
      : isBoard ? ': not tied to a board yet (plan the board on the Run tab, then save these points again)' : ''), isBoard && !tie);
    if (tie) await refreshPlan();
  }));
  $('btn-surf-clear').addEventListener('click', (ev) => act(ev.target, async () => {
    const name = $('surf-name').value;
    if (!confirm('Delete the ' + name + ' surface?')) return;
    await api('DELETE', '/api/surfaces/' + encodeURIComponent(name));
    S.surfKey = null;
    $('surf-points').value = '';
    toast('Deleted surface ' + name);
  }));
  $('btn-settings').addEventListener('click', (ev) => act(ev.target, saveSettings));
  $('btn-pr-save').addEventListener('click', (ev) => act(ev.target, savePurgeRow));
  $('btn-pr-reset').addEventListener('click', (ev) => act(ev.target, resetPurgeRow));
  $('btn-pr-usexy').addEventListener('click', () => {
    const n = nozzlePos();
    if (!n) return;
    $('pr-x0').value = n.x.toFixed(3);
    $('pr-y0').value = n.y.toFixed(3);
    S.prDirty = true;
  });
  for (const id of PR_FIELDS) $(id).addEventListener('input', () => { S.prDirty = true; });
  $('btn-settings-json').addEventListener('click', (ev) => act(ev.target, saveSettingsJson));

  $('btn-shots-run').addEventListener('click', (ev) => act(ev.target, shotsRun));
  $('btn-ss-save').addEventListener('click', (ev) => act(ev.target, saveScaleShots));
  $('btn-sd-save').addEventListener('click', (ev) => act(ev.target, saveScaleDots));
  $('btn-dl-run').addEventListener('click', (ev) => act(ev.target, dotLadder));
  $('btn-to-run').addEventListener('click', (ev) => act(ev.target, touchOff));

  $('btn-pf-save').addEventListener('click', (ev) => act(ev.target, saveProfile));
  $('btn-pf-angle-set').addEventListener('click', (ev) => act(ev.target, () => startAngle(true)));
  $('btn-pf-angle-confirm').addEventListener('click', (ev) => act(ev.target, () => startAngle(false)));
  for (const id of PF_FIELDS) {
    $(id).addEventListener('input', () => {
      S.pfDirty = true;
      if (S.status && S.status.dispense.profile) renderTurnInfo(S.status.dispense.profile);
    });
  }

  $('btn-padtype-new').addEventListener('click', () => openPadType(null));
  $('show-retired').addEventListener('change', renderPadTypes);
  $('padtype-form').addEventListener('submit', savePadType);
  $('pt-cancel').addEventListener('click', () => $('dlg-padtype').close());
  $('pt-family').addEventListener('change', () => $('pt-family-other-wrap').classList.toggle('hidden', $('pt-family').value !== '__other'));
  $('pt-footprint').addEventListener('change', pickFootprint);
  $('history-close').addEventListener('click', () => $('dlg-history').close());
}

document.addEventListener('DOMContentLoaded', () => {
  wire();
  const tab = (location.hash || '#run').slice(1);
  showTab(['run', 'dispense', 'calibrate', 'profiles'].includes(tab) ? tab : 'run');
  loadBoards();
  poll();
});
