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
  camTimer: null,
  lastRunId: null,
  online: false,
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
  if (!S.defaultsFilled && d.running) {
    if (!$('prime-rot').value) $('prime-rot').value = d.primeRotationDeg;
    if (!$('testdot-rot').value && d.testDot && d.testDot.defaultRotationDeg) $('testdot-rot').value = d.testDot.defaultRotationDeg;
    S.defaultsFilled = true;
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
  $('btn-run').disabled = !S.plan || !S.plan.dots.length || d.busy || holding
    || !(S.plan.interlocks && S.plan.interlocks.ok);
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
      else toast(run.label + (run.label === 'board' && run.boardName ? ' ' + run.boardName : '') + ' finished: ' + run.dotsDone + ' dot(s)');
      loadCalibrations();
    }
  } else {
    fillKv(live, [['Task', 'none yet']]);
  }
  if (last) {
    fillKv($('run-last'), [
      ['Task', last.kind + (last.boardName ? ' (' + last.boardName + ')' : '')],
      ['Result', last.status + (last.message ? ': ' + last.message : '')],
      ['Dots', last.dotsDone + ' of ' + last.dotsPlanned],
      ['DPH (dots only)', fmtInt(last.dphMotion), 'Dots divided by the time from the first dot to the last dot'],
      ['DPH (everything)', fmtInt(last.dphOverall), 'Dots divided by the whole task time: pressure, prime, test dot and travel included'],
      ['Total time', fmtDuration(last.totalSeconds)],
    ]);
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
  $('needle-state').textContent = (n.touchedOff ? 'Touched off ' + fmtTime(n.setAtMs) : 'NOT touched off since the last needle change')
    + ' · offset ' + fmt(n.zOffsetMm, 3) + ' mm · gap ' + fmt(n.gapMm, 3) + ' mm';
  if (document.activeElement !== $('needle-offset') && $('needle-offset').value === '') {
    $('needle-offset').value = n.zOffsetMm;
  }
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
    if (body.x === undefined || body.y === undefined) { banner('Enter the board position X and Y.'); return; }
    localStorage.setItem('il.boardloc.' + c.file, JSON.stringify({ x: body.x, y: body.y, z: body.z, rotation: body.rotation }));
  }
  const p = await api('POST', '/api/plan', body);
  S.plan = p;
  renderPlan(p);
  toast('Planned ' + p.summary.dots + ' dots on ' + p.summary.pads + ' pads');
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
      + fmt(q.w) + ' × ' + fmt(q.h) + ' mm · ' + fmt(q.targetMassMg, 3) + ' mg · ' + q.dotCount + ' dot(s)'
      + (q.rotationPerDotDeg ? ' × ' + fmt(q.rotationPerDotDeg, 1) + '°' : '')
      + (q.flags.length ? '\n' + q.flags.join(', ') : '')));
  }
  if (p.dots.length > 1) {
    g.appendChild(svgEl('polyline', { class: 'path', points: p.dots.map((d) => d.x + ',' + d.y).join(' ') }));
  }
  S.dotEls = [];
  for (const d of p.dots) {
    const r = Math.max(0.12, (d.expectedDiameterMm || 0.5) / 2);
    const c = svgEl('circle', { class: 'dot', cx: d.x, cy: d.y, r, 'data-index': d.index },
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
    ['Paste per board', fmt(s.totalRotationDeg / 360, 2) + ' auger turns', 'Total auger rotation for all dots'],
    ['Path', fmt(s.pathLengthMm, 1) + ' mm'],
    ['Time (estimate)', s.estimatedSeconds ? fmtDuration(s.estimatedSeconds) : (s.estimateError || '—'),
      'Lower bound: ignores acceleration'],
    ['DPH (estimate)', s.estimatedDphUpperBound ? '≤ ' + fmtInt(s.estimatedDphUpperBound) : '—',
      'Upper bound from the time estimate'],
    ['Calibration', '#' + p.calibration.id + ' q=' + fmt(p.calibration.qMm3PerRev, 3) + ' mm³/turn (' + p.calibration.status + ')'],
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
  if (!confirm('Dispense ' + n + ' dot(s) on ' + p.boardName + '?\nThe machine moves and the pressure switches on.')) return;
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
    sel.replaceChildren(...Object.keys(locs).map((n) => el('option', { value: n, text: n })));
    if (keep && locs[keep]) sel.value = keep;
    else if (id !== 'shots-place' && locs[d.blankLocationName]) sel.value = d.blankLocationName;
  }
}

function placeRow(name, loc, d) {
  const derived = name === 'waste-test-dot';
  const hint = name === d.wasteLocationName ? 'Waste pad: prime and test dots'
    : name === d.blankLocationName ? 'Blank: calibration dots' : derived ? 'Test dot spot (follows the waste pad)' : '';
  const inp = (v, axis) => el('input', { type: 'number', step: '0.01', value: v === undefined ? '' : v, disabled: derived,
    'data-axis': axis, 'aria-label': name + ' ' + axis.toUpperCase() });
  const x = inp(loc && loc.x, 'x'), y = inp(loc && loc.y, 'y'), z = inp(loc && loc.z, 'z');
  const useXY = el('button', { type: 'button', class: 'small', text: 'Use N2 X/Y', disabled: derived,
    title: 'Copy the dispenser needle\'s current X and Y',
    onclick: () => { const n = nozzlePos(); if (n) { x.value = n.x.toFixed(3); y.value = n.y.toFixed(3); } } });
  const useZ = el('button', { type: 'button', class: 'small', text: 'Use N2 Z (tip touching)', disabled: derived,
    title: 'If the needle tip is resting on the surface now: surface Z = N2 Z − needle offset',
    onclick: () => { const n = nozzlePos(); if (n) z.value = (n.z - S.status.dispense.needle.zOffsetMm).toFixed(3); } });
  const save = el('button', { type: 'button', class: 'small primary', text: 'Save', disabled: derived, 'data-action': 'save',
    onclick: (ev) => act(ev.target, async () => {
      if (x.value === '' || y.value === '' || z.value === '') throw new Error('Enter X, Y and Z for ' + name);
      await api('PUT', '/api/locations/' + encodeURIComponent(name), { x: Number(x.value), y: Number(y.value), z: Number(z.value) });
      S.placesKey = null;
      toast('Saved place ' + name);
    }) });
  return el('tr', { 'data-place': name }, el('td', { title: hint }, el('b', { text: name }), hint ? el('div', { class: 'hint', text: hint }) : null),
    el('td', {}, x), el('td', {}, y), el('td', {}, z), el('td', {}, el('div', { class: 'btns' }, useXY, useZ, save)));
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
  $('set-gap').value = c.needleGapMm;
  $('set-maxon').value = c.maxContinuousOnTimeS;
  const sel = $('set-minstatus');
  sel.replaceChildren(...Object.keys(STATUS_LABELS).map((k) => el('option', { value: k, text: STATUS_LABELS[k] })));
  sel.value = c.minPadTypeStatus;
  $('set-prime').checked = c.primeAndTestDot;
  $('set-optical').checked = c.testDotOpticalCheck;
  $('set-json').value = JSON.stringify(c, null, 2);
}

async function saveSettings() {
  const c = await api('PUT', '/api/config', {
    needleGapMm: num('set-gap'), maxContinuousOnTimeS: num('set-maxon'), minPadTypeStatus: $('set-minstatus').value,
    primeAndTestDot: $('set-prime').checked, testDotOpticalCheck: $('set-optical').checked,
  });
  fillSettings(c);
  toast('Settings saved');
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
    + cur.status + ', ' + cur.method + ')' : 'No calibration';
  if (cur && !$('sd-cal').value) $('sd-cal').value = cur.id;
  $('cal-table').tBodies[0].replaceChildren(...c.calibrations.slice().reverse().map((k) => el('tr', {},
    el('td', { text: k.id }), el('td', { text: k.method }), el('td', {}, el('span', { class: 'badge ' + (k.status === 'characterized' ? 'characterized' : 'provisional'), text: k.status })),
    el('td', { class: 'num', text: fmt(k.qMm3PerRev, 4) }), el('td', { class: 'num', text: k.qCv === null ? '—' : fmt(100 * k.qCv, 1) + ' %' }),
    el('td', { class: 'num', text: k.qN === null ? '—' : k.qN }), el('td', { text: fmtTime(k.createdAtMs) }), el('td', { text: k.notes || '' }))));
  $('ladder-table').tBodies[0].replaceChildren(...c.ladder.map((r) => el('tr', {},
    el('td', { class: 'num', text: fmt(r.rotationDeg, 1) }), el('td', { class: 'num', text: r.n }),
    el('td', { class: 'num', text: fmt(r.diameterMeanMm, 3) }), el('td', { class: 'num', text: r.diameterCv === null ? '—' : fmt(100 * r.diameterCv, 1) + ' %' }),
    el('td', { class: 'num', text: fmt(r.mgPerDotMean, 3) }), el('td', { class: 'num', text: fmt(r.heightMm, 3) }), el('td', { text: r.method || '' }))));
  if (!c.ladder.length) $('ladder-table').tBodies[0].appendChild(el('tr', {}, el('td', { colspan: 7, class: 'hint', text: 'No dot ladder yet: plans use one dot per pad from q (flag NO_LADDER).' })));
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
  $('profiles-hint').textContent = 'A board refuses pads whose type is below "' + (STATUS_LABELS[d.minPadTypeStatus] || d.minPadTypeStatus)
    + '". Dot plan uses calibration #' + (d.calibration ? d.calibration.id + ' (' + d.calibration.status + ')' : '—') + '.';
  const out = [];
  for (const fam of d.families) {
    if (fam.retired && !showRetired) continue;
    out.push(el('div', { class: 'family card', 'data-family': fam.family },
      el('h2', { text: fam.family }),
      el('table', { class: 'tbl' },
        el('thead', {}, el('tr', {}, [
          ['Name'], ['Status', 'not started → provisional → characterized → validated on a board'],
          ['Pad (mm)'], ['t', 'Paste thickness (mm)'], ['k', 'Fraction of the pad area covered'],
          ['Dot plan', 'One dot per pad: auger rotation from the current calibration (the dot ladder may split a pad into several dots)'],
          ['Calibration'], ['Last validated'], ['Notes'], [''],
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
    el('td', { title: fmt(t.targetMassMg, 3) + ' mg per pad' }, t.singleDotRotationDeg ? '1 dot × ' + fmt(t.singleDotRotationDeg, 1) + '°' : '—',
      t.rule && t.rule.maxDots ? el('div', { class: 'hint', text: 'up to ' + t.rule.maxDots + ' dots' }) : null),
    el('td', { text: t.calibrationId ? '#' + t.calibrationId + ' (' + t.calibrationStatus + ')' : '—' }),
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

/** Set up / Validate: open Calibrate with this type's dot size filled in, plus the steps. */
function setUp(t, validate) {
  const r = t.singleDotRotationDeg;
  showTab('calibrate');
  if (r) {
    $('dl-rots').value = [0.6, 0.8, 1, 1.2, 1.4].map((f) => (r * f).toFixed(1)).join(', ');
    $('sd-rot').value = r.toFixed(1);
    $('to-rot').value = r.toFixed(1);
  }
  const g = $('setup-guide');
  g.replaceChildren(el('b', { text: (validate ? 'Validating ' : 'Setting up ') + t.name + ' (' + t.typeKey + ')' }),
    el('ol', {}, validate ? [
      el('li', { text: 'Dispense a coupon of dots at ' + fmt(r, 1) + '° on a weighed slide (Scale: shots with the slide\'s place, or a board run).' }),
      el('li', { text: 'Weigh it and enter the batch under "Scale: dot batch". Predicted: ' + fmt(t.targetMassMg, 3) + ' mg per pad.' }),
      el('li', { text: 'If the result matches your tolerance, edit the type in Profiles and set its status to "validated on a board".' }),
    ] : [
      el('li', { text: 'Needle touched off and the blank place set (Dispense tab).' }),
      el('li', { text: 'Run the dot ladder (rotations filled in around ' + fmt(r, 1) + '°) and/or weigh a dot batch.' }),
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

  $('btn-pressure-on').addEventListener('click', (ev) => act(ev.target, () => pressure(true)));
  $('btn-pressure-off').addEventListener('click', (ev) => act(ev.target, () => pressure(false)));
  $('btn-prime').addEventListener('click', (ev) => act(ev.target, prime));
  $('btn-testdot').addEventListener('click', (ev) => act(ev.target, testDot));
  $('btn-cam').addEventListener('click', snapshot);
  $('cam-auto').addEventListener('change', toggleCam);
  $('btn-place-add').addEventListener('click', addPlace);
  $('btn-needle-changed').addEventListener('click', (ev) => act(ev.target, async () => {
    if (!confirm('Mark the needle as changed? Dispensing is blocked until it is touched off again.')) return;
    await api('POST', '/api/needle/changed');
    toast('Needle marked as changed');
  }));
  $('btn-needle-offset').addEventListener('click', (ev) => act(ev.target, async () => {
    const off = num('needle-offset');
    if (off === undefined) throw new Error('Enter the offset in mm');
    await api('POST', '/api/needle/offset', { offsetMm: off });
    toast('Needle offset saved');
  }));
  $('btn-settings').addEventListener('click', (ev) => act(ev.target, saveSettings));
  $('btn-settings-json').addEventListener('click', (ev) => act(ev.target, saveSettingsJson));

  $('btn-shots-run').addEventListener('click', (ev) => act(ev.target, shotsRun));
  $('btn-ss-save').addEventListener('click', (ev) => act(ev.target, saveScaleShots));
  $('btn-sd-save').addEventListener('click', (ev) => act(ev.target, saveScaleDots));
  $('btn-dl-run').addEventListener('click', (ev) => act(ev.target, dotLadder));
  $('btn-to-run').addEventListener('click', (ev) => act(ev.target, touchOff));

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
