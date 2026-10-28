# Web UI: developer notes and module contract

Spec: `docs/instancelabs/UI_DESIGN.md` (binding; § numbers below refer to it). API: `docs/instancelabs/WEB_API.md`.
Design system source: `ui-revamp/final/src/final.css` (tokens and components, ported to `app.css`).

The foundation (frame, store, poll, router, readiness, strip, STOP, panel host, dialogs, two-press) lives in
`core.js`, `app.css` and `index.html`. The workspaces are built in parallel **without touching those three
files**: each workspace module registers itself with `IL.workspace()` and fills its own `<section>`. If you need
something new from core, ask for it (or add it to your module and propose it for core afterwards).

## 1. Files and owners

| File | Owner | Content |
|---|---|---|
| `index.html` | core | Frame only: SVG icon sprite, status strip (STOP first in the DOM), nav, `#main` with `#msg-area` and five **empty** `<section data-workspace>`, `#panel` host, `#stop-unconfirmed`, `#toast`. Script/link tags. |
| `app.css` | core | Tokens (light + `[data-theme=dark]`), frame, strip, STOP, nav, panel, shared components (§11). |
| `core.js` | core | The `IL` namespace (this document). Loaded **synchronously** in `<head>`: sets the theme before first paint. |
| `app.js` | core | `IL.boot()`. Loaded last. Kept because `ApiServerSimTest` fetches `/static/app.js`. |
| `boardview.js` | Run stage | `IL.boardview`: the to-scale board SVG (hero, probe overlay, Records done-dot map). Stub now. |
| `run.js` + `run.css` | Run stage | `#ws-run`, Run panel steps (board, probe, checks, run, result, recovery override), run confirm `#dlg-run`. |
| `setup.js` + `setup.css` | Setup stage | `#ws-setup` (cards §8.2), default panel `head`. `calibrate.js` may reuse `setup.css`. |
| `profile.js` + `profile.css` | Profile stage | `#ws-profile` (§8.3), `#dlg-padtype`, `#dlg-history`, panel `pt`. |
| `calibrate.js` | Calibrate stage | `#ws-calibrate` (§8.4), panel `cal`. |
| `records.js` | Records stage | `#ws-records` (§8.5), panel `record`. |
| `favicon.svg` | core | Idle favicon (live / stopped variants are `data:` URLs in core). |

New static files: flat names `[A-Za-z0-9_-]+\.(html|js|css|svg|png)`, served from `/static/<name>`; add them to
`ApiServerSimTest.servesTheUiAndStatus`. CSP is `script-src 'self'`: no inline scripts, no `on*=` attributes, no
`eval`, no external URLs or fonts. **Never** `confirm()`, `alert()`, `prompt()` or `dialog.showModal()` (they block
STOP): use `IL.press2`, `IL.dialog.open` / `IL.dialog.showNative`.

## 2. Load order and lifecycle

```
<head>  core.js (sync)  → IL defined, theme applied, F9 / Space / Esc key handlers installed
        boardview.js, run.js, setup.js, profile.js, calibrate.js, records.js (defer, in this order)
        app.js (defer)  → IL.boot()
boot:   mount(root) of every registered workspace → route applied → show() of the active one
        → poll /api/status (0.7 s chain) → render loop
```

Per workspace (`IL.workspace(name, spec)`; name ∈ `run setup profile calibrate records`):

| Hook | When | Rules |
|---|---|---|
| `mount(root)` | once at boot (`root` = `section[data-workspace=name]`, id `ws-<name>`) | Build the static DOM here (cards, fields, buttons with their ids from §16). Wire events. |
| `show(route)` / `hide()` | workspace switch | Load on demand (e.g. `IL.loadConfig()`), start/stop timers. `#main` scroll position is remembered per workspace. |
| `render(status)` | every poll (0.7 s), every second (`tick`), and after `IL.render()`; **only for the visible workspace** and only once a status exists | Patch, never rebuild regions with inputs (§15). Use `IL.setText/setAttr/patch/keyed/gate`. |
| `panel(parts, status, first)` | the default context-panel view of this workspace, same cadence as render | `first === true`: parts were just cleared, build. Otherwise patch. `parts = {root, top, body, foot, scroll}`. |
| `route(route)` | every hash change while this workspace is active (also right after `show`) | Handle `route.section` / `route.id` (e.g. `IL.flash(card)` for `#setup/purge`). |
| `nav(status)` | every render | Return `{sub, kind, badge, badgeKind, badgeLabel, dirty, title}` to override the nav item (kind / badgeKind: `need`, `block`, `live`, `ok`). Core has defaults for Run (from readiness / task / recovery), Setup and Calibrate (`Locked · running`), Profile (name / `Confirm angle`). |
| `defaultView` | string | `#panel[data-view]` for the default panel (else the workspace name). Run changes it per step with `IL.panel.setView('checks')`. |
| `ownsRecovery` | boolean | Run sets `true`: then core does **not** push its Recovery view on Run, and Run shows Recovery as its own step. On other workspaces core pushes the `recovery` view while a recovery is open. |

Registering after boot works (mounted at once).

## 3. State: `IL.S` (single store; read it, change it only through the IL functions)

| Key | Content |
|---|---|
| `status`, `prevStatus` | last good `GET /api/status` and the one before (null before the first answer) |
| `statusAt`, `lastOkAt` | client ms of the last good status |
| `online` | `null` before the first answer, `true`, or `false` after 3 s without one (offline mode) |
| `skewMs` | server clock − client clock; server stamps (`…AtMs` in status) → client time: `ms - IL.S.skewMs`; `IL.serverNow()` |
| `plan`, `planId` | the plan shown (POST/GET plan response) |
| `planPending` | a change-triggered re-read is in flight (readiness item 7 reads `checking`) |
| `planForgotten` | `GET /api/plan/{id}` answered 404 (OpenPnP restarted) → plan chip `Plan forgotten (OpenPnP restarted): plan again` |
| `planAt` | client ms of the last plan read |
| `cleanCheck` | newest clean-pad report of the plan shown (from the plan, a subset run, or `lastRun.cleanCheck`) |
| `newSeatAtMs` | operator answered "re-seated / new board" (readiness item 6 needs a probe after it) |
| `route` | `{ws, section, id, panel, params}` |
| `recovery` | open recovery or null: `{cause: 'stop'|'aborted'|'error'|'openpnp', atMs, actions (server STOP answer), source ('F9'|'button'|'key'), run: {runId, label, boardName, planId, status, message, phase, dotsDone, dotsPlanned, doneDots, pressureOnS}, message, seenDown, purgeShort}` |
| `lastStop` | the last recovery after it cleared (Run's board decision: finish the pads left / wipe all) |
| `activity` | this page's log, newest first `[{atMs, text, kind}]` (toasts and refusals land here too) |
| `runs`, `config`, `events` | caches filled by `IL.loadRuns()`, `IL.loadConfig()`, `IL.loadEvents()` |
| `connectedAt`, `homedAt` | client-observed transition times |
| `runPressureSince` | client stamp of the first poll of a run's pressure session (phase `dispensing` / `shots` / `dispensing calibration dots`) |

Browser storage: `localStorage['il.theme']`, `['il.planId']`, `['il.attest']`, `['il.newSeatAtMs']`, (Run:
`['il.boardloc.<file>']`); `sessionStorage['il.recovery']`.

## 4. Events (`IL.on(name, fn)` returns the unsubscribe function)

| Event | Args | When |
|---|---|---|
| `status` | `(status, prev)` | every good poll, before rendering |
| `plan` | `(plan, prev)` | `setPlan`, `loadPlan`, `planRefresh`, 404 (plan null + `S.planForgotten`) |
| `cleancheck` | `(report)` | a newer clean-pad report of the plan shown was adopted |
| `taskend` | `(run)` | a task (board, clean-pad check, prime, test dot, touch-off, ladder, shots) ended; core already re-read the plan and runs |
| `stop` | `(answer)` | our STOP was confirmed (`answer.actions`) |
| `recovery` | `(rec or null)` | a recovery opened / cleared |
| `online` | `(bool)` | offline after 3 s without an answer / back |
| `route` | `(route, prev)` | after every hash change |
| `tick` | — | every second (countdowns) |
| `theme`, `attest`, `activity`, `runs`, `config`, `events`, `boot` | | as named |

## 5. API reference (`core.js`)

### Network and data
- `IL.api(method, path, body?, {timeoutMs?})` → parsed JSON; throws `IL.ApiError {status, data, code, message}`.
  `status 0` = no answer (`code` `timeout` / `network`): show `Outcome unknown: look at the machine.`, never re-send.
  Default timeout 10 s (status 2.5 s, STOP 2 s are internal).
- `IL.errorText(e)` one line incl. interlock violations.
- `IL.pollNow()` poll at once (after an action). `IL.render()` schedule one render (coalesced per frame).
- `IL.setPlan(plan)` (after `POST /api/plan`; remembers `il.planId`), `IL.clearPlan()`, `IL.loadPlan(id)`,
  `IL.planRefresh({quiet})`, `IL.planRefreshSoon()` (300 ms debounce; marks `planPending`). Core re-reads the plan by
  itself when any freshness input of §9.2 changes, every 10 s while visible, and after every task.
  Plan restore on load: the running task's plan, else `localStorage['il.planId']`, else the last task's plan.
- `IL.basePlanId(id)` (strips `-subset-…`), `IL.planMatches(planId)`, `IL.adoptCleanCheck(cc, planId)`,
  `IL.dirtyPads(cc)` → `[{padId, share, error}]` worst first, `IL.cleanSummary(cc)` → the machine-readable first
  sentence of `#clean-result` (§8.1.4).
- `IL.loadRuns()` (`/api/runs?limit=50`, also at load and after each task), `IL.loadConfig()`, `IL.loadEvents(limit)`.
- `IL.attest.{all(), get(key), set(key, value), clear(key), valid(key, status)}` keys `regulator` (psi),
  `paste-life`, `prime-seen`, `test-dot-seen` (§9.4; regulator invalid after a psi change, a needle change or 8 h).
  `hands-clear` is never stored. `IL.setNewSeat(atMs | null)`.
- Machine helpers: `IL.positions(status)` → `{n1, n2, cam}`, `IL.wasteDistance(status)` (mm or null),
  `IL.isSim(status)`, `IL.taskRunning(status)`, `IL.energised(status)`, `IL.interlock(status, id)`,
  `IL.taskLabel(run)` (`96/160 · 0:44 left`, `Pad check · 1:52`, `Prime`).

### Readiness (§9) — the only place that computes "ready"
- `IL.readiness(status, plan, ctx)` — **pure**. `ctx = {nowMs, attest, planPending, planForgotten, cleanCheck,
  newSeatAtMs, homedAt}`. Returns:
  ```
  { items: [9 × Item] in IL.READY_ORDER = machine, angle, needle, regulator, padtypes, surface, plan, purge, clean,
    extras: [Item]   // other blockers shown as extra cards on top: CONFIG, I1 warning, I3, I7, place Z, blank surface,
                     // pressure held (need) / vent_failed (block), engine unavailable
    count, total: 9, next (first not-ready card; extras first), needs: [extras + not-ready items, fixed order],
    blocked: [...], verdict: {state: ok|need|block, text: 'Ready to run' | '8 of 9 · wipe 2 pads' | 'Blocked · start angle' | 'No plan yet', title},
    canRun, runStyle: 'go' (filled teal) | 'hold' (outlined; items 4 / 9 not ready) | 'blocked', runReason (the line above the Run button),
    planLabel }
  Item = { key, n, code ('MACHINE','I11','I4','I6','PLAN','I9','I10',...), title, state, ready, value (short, right-aligned),
           short (for the verdict / nav), detail (plain sentence with numbers), tip, fixes: [Fix], you?, stale?, dirty? }
  state: ok | you (operator attestation, blue ring) | off (counted ready) | need | block | live | checking | wait (needs a plan)
  Fix = {fix: '<id>', label, arg?, primary?} | {href: '#setup/needle', label} | {view: 'pressure', label}
  ```
- `IL.ready()` the same for the store now (memoised per status / plan / attestation change). Use it for the Checks
  header, segment bar, needs-you cards, ready tiles, Run button and run confirm.
- `IL.verdict()` the strip verdict now (task / recovery / offline / engine override the readiness verdict).

### STOP and recovery (§5, §8.1.8)
- `IL.stop(source)` = the STOP press (all `#btn-stop` / `[data-stop-copy]` clicks are wired by delegation; F9 is
  global in the capture phase, also in fields and dialogs; Space on STOP is suppressed; Esc never stops). Shows
  `STOPPING…`, posts `/api/stop` (2 s), on no answer shows `#stop-unconfirmed` and re-sends every second until one
  answer; toast `STOPPED hh:mm:ss: <actions joined "; ">`; opens the recovery.
- Second STOP: core shows the copy at the top of the panel scroll area whenever `IL.energised(status)`. Do not add
  your own; never place a Run / commit button where it sits (top of the panel).
- `IL.recoveryActive()`, `IL.recoverySteps(status, rec)` → `[{key: vent|connect|angle|home|purge|decide, n, title,
  detail, done, wait, now, state, fixes}]` (`purge` only when the row is / was short, `decide` only for a board),
  `IL.recoveryHeadline(rec)`. Recovery clears itself when vent, connect, angle, home and purge are done
  (`S.lastStop` keeps it). Core's `recovery` view renders the red card, Back to ready and What STOP did; Run may
  replace it (`IL.view('recovery', …)`) and should own it on Run (`ownsRecovery`).

### Actions, two-press, fix-its, refusals
- `IL.act(btn, async fn, {ok, refusalIn, what})` runs an action: button disabled + `aria-busy` meanwhile; errors →
  `IL.refuse`; then `pollNow` + `render`. `ok`: toast text or `fn(result)`.
- `IL.refuse(err, {host, what})` toast + activity + an inline `.refusal` box in `host` (default: the nearest
  `[data-refusals]` ancestor of the button) listing violations with their I-code and fix-it buttons.
- `IL.press2(btn, verb, action, opts)` makes a **two-press** button (§11): first press → `.btn--press`, label
  `Press again: <verb>`, 4 s bar; second press ≥ 300 ms later (no key auto-repeat) runs `IL.act(btn, action, opts)`;
  Esc, blur, a press elsewhere or 4 s cancel. `verb` may be `fn(status) → verb | null` (null = single press now).
  Use it for Home, clean check, prime, test dot, touch-off, ladder, shots, purge reset, delete surface, needle
  changed, retire, discard, pressure on away from the waste. `IL.isArmed(btn)`: do not patch an armed button's label.
- Fix-its: `IL.fix(id, {run(btn, arg, status), press2, ok, href})` registers; `IL.fixButton(fix, {cls})` builds a
  `<button data-fix=id>` (handler looked up at click time); `IL.fixRow(fixes, cls)`; `IL.runFix(id, btn, arg)`;
  `IL.fixesForCode(code)` for refusals. Core registers: `connect`, `home` (two-press), `I11-confirm`, `needle-confirm`
  (posts the frame offset), `purge-reset` (two-press), `pressure-on` (two-press unless N2 ≤ 3 mm from the waste),
  `pressure-off`, `attest-regulator`, `attest-paste`, `same-seat`. **Run must register** `plan-again`, `clean-check`
  (two-press), `probe`; until then they fall back to `#run/board`, `#run/checks`, `#run/probe`.
- `IL.lockReason(status, {connected, homed, idle = true, pressureOff = true, engine})` → the spec's locked-control
  text (`Locked while the board runs`, `Pressure is held: switch it off first`, `Not connected: connect first`, offline)
  or null. `IL.gate(btn, reason, reasonEl?)` disables with the reason (title + optional visible `.reason` line).

### Router, panel, dialogs, messages
- Hash grammar `#<ws>[/<section>[/<id>]][?panel=<view>]`; `#dispense` → `#setup`, `#profiles` → `#profile`.
  `IL.go('#setup/purge')` or `IL.go({section: 'checks'})` (workspace changes push history, in-page changes replace),
  `IL.openView(name)` (toggles a pushed view), `IL.parseHash`, `IL.hashFor`, `IL.active()`.
- Panel host: `#panel[data-view]` > `#panel-bar` (back + title, core) · `#panel-top` (pinned head, e.g. the Run
  stepper) · `#panel-scroll` (`#panel-stop` STOP copy, core; `#panel-body`) · `#panel-foot` (pinned; hidden when
  empty). `IL.panel.show(view, fn(parts, status, first) | {title, render, unmount}, {title})` pushes a view with a back
  arrow; `IL.panel.close()`, `.reset()` (re-run with `first`), `.setView(name)`, `.current()`, `.isPushed(view)`.
  `IL.view(name, spec)` registers a routable view (`?panel=name`). Core views: `interlocks` (`#interlock-list`
  `li.<engine state>`), `machine`, `pressure`, `angle` (I11 question), `purge`, `activity`, `camera` (simple
  snapshot; Setup may override with overlays), `recovery`. A module may re-register any of them.
  Esc closes the top-most thing: pending two-press → dialog → pushed view.
- `IL.dialog.open({id, eyebrow, title, keys, body, foot, size: 'sm', initialFocus, onClose})` → `{el, body, foot,
  close(reason)}`: in-page `.scrim` (starts under the strip; STOP and the strip stay live) + `.dialog[role=dialog]
  [aria-modal=false]`; Tab cycles the dialog and STOP; Esc = close. `IL.dialog.showNative(<dialog>, opts)` for
  `#dlg-padtype` / `#dlg-history` (`dialog.show()` + scrim; `[open]` still marks them). `IL.dialog.top()`.
  `IL.onEsc(fn → true if handled)` for your popovers / menus.
- `IL.toast(text, kind)` (≤ 4 s, bottom-left of the main column, also logged), `IL.msg(key, {kind, text, sub,
  actions} | null)` page band in `#msg-area` (core uses keys `offline`, `vent`), `IL.log(text, kind)`.
- Keyboard: `Alt+1…5` workspaces, `?` key sheet, `Enter` in a field inside `[data-enter-submits="<button id>"]`
  clicks that (Save) button only. Never bind Space or Esc to a machine action.

### DOM and format helpers
`IL.$(id)`, `IL.$$(sel, root)`, `IL.el(tag, {class, text, title, dataset, onclick, 'data-x'}, ...children)`,
`IL.append`, `IL.svgEl(tag, attrs, title)`, `IL.icon('i-gauge', cls)` (sprite ids below), `IL.glyph(kind, size,
label, text)` / `IL.setGlyph(span, …)` (status glyph: shape + colour + word), `IL.setText`, `IL.setAttr`,
`IL.setClass`, `IL.show(node, on)` (hidden attribute; SVG too), `IL.patch(node, {text, class, title, hidden,
disabled, attrs, dataset})`, `IL.keyed(container, key, build)` (rebuild only when `key` changes; keeps focus by
id / `data-focus-key` / `data-fix`; never while a two-press inside is pending), `IL.kv(dl, [[k, v, title]])`,
`IL.timeEl(ms, secs)`, `IL.flash(node)` (scroll + 1.5 s outline), `IL.sectionK(text, count)`.
Format (display only; real minus `−`): `IL.fmt(n, d=2)`, `fmtSigned`, `fmtInt` (`5,410`), `fmtPsi` (`18`, `18.5`),
`fmtMm`, `fmtDeg` (`298.0°`), `fmtClock` (`1:15`), `fmtDuration` (`4.0 s`, `5 min 16 s`), `fmtTime` (`13:06`),
`fmtTimeS` (`14:19:42`), `fmtDateTime`, `fmtAgo`, `plural(n, 'dot')`, `turnLabel(800, 1600)` (`½ turn`),
`recipeParts(profile)`, `recipeEl(profile, {changed, href})` (the `.recipe` token; `aria-label` = parts without the
name joined by ` · `), `profileText`. Inputs: `IL.inputNum(n, d)` (ASCII), `IL.num(idOrEl)`, `IL.str(idOrEl)`.
Constants: `IL.STOP_CODES`, `IL.STOP_TITLE`, `IL.STOP_LINE` (the exact §13 texts), `IL.STOP_DID`, `IL.LIMITS`,
`IL.PRESSURE_PHASES`, `IL.WORD`.

## 6. Render rules (§15)

- Build once (mount / `first`), patch on every render. Never `replaceChildren` a region that contains inputs on a
  poll; never move focus or the caret; never overwrite a focused or dirty field (track dirty per field against the
  server value; dirty fields get `.input--dirty`; the nav item shows `dirty: true`).
- Lists that change rarely: `IL.keyed(container, JSON key, build)`.
- Gate every action button each render with `IL.gate(btn, IL.lockReason(status, {...}) || ownReason, reasonEl)`.
  Every greyed control says why (`.reason` line or the button title).
- Countdowns interpolate from `IL.S.statusAt` and tick on the `tick` event; numbers use tabular figures already.
- Offline: core sets `[inert]` on every `.ws`, `#panel-top/body/foot` and the strip actions, shows the band and
  dims; you do not need to handle it, but do not cache "ready" from before.
- A render may run every 700 ms and every second: keep it cheap (no layout reads in loops, no network).

## 7. CSS vocabulary (`app.css`; use these, do not redefine them; workspace CSS goes in run/setup/profile.css)

Tokens (`:root`, `[data-theme=dark]`): surfaces `--canvas --surface --tile --sunken --line --line-2`, ink `--ink
--ink-2 --ink-3 --ink-disabled --disabled-bg`, actions `--accent --accent-hover --accent-tint --accent-ink
--on-accent`, data (paste and dots only) `--data --data-tint`, states `--ok|need|block|off|info` + `-bg -line -dot`,
energised `--live --on-live --live-tint --live-line`, two-press `--press-a --press-b --press-line --press-ink`, STOP
`--stop --stop-hover --stop-edge --on-stop`, board preview `--pv-*`, radii `--r-xs…--r-xl --r-pill`, elevation
`--e1 --e2 --e3 --scrim`, frame `--strip-h --nav-w --panel-w --stop-w`, fonts `--font-ui --font-code`.
Colour means state only; never green for "off".

| Area | Classes |
|---|---|
| Frame | `.app .strip .brand .cells .cell(--ok/need/block/off/info/plain/live/verdict) .cell__k .cell__v .long .cell-act .stop-bay .stop .stop--panel .nav .nav__item .main .ws .panel .panel__bar/__top/__scroll/__body/__foot/__lead .msg-area .msg(--block/need/info/live) .stop-band .toast` |
| Page | `.page-head .page-head__text .page-head__actions .eyebrow(--block/live/need) h1 .h1-sub h2 h3 .meta .lock-note .ws-empty` |
| Cards | `.card(--need/block) .card__head .card__title .card__sub .card__state(--ok/need/block/off/live) .card__body .card__foot .card__banner(--need/info) .grid-cards .span2 .stack .flash` |
| Glyph | `.st .st--ok/need/block/off/info/you/num/now/live/wait/checking .st--sm/xs .pulse` (via `IL.glyph`) |
| Buttons | `.btn .btn--primary .btn--hold .btn--test .btn--quiet .btn--lg .btn--sm .btn--block .btn--icon .btn--danger-ghost .btn--stack .btn__main .btn__sub .btn--press .btn-row .reason(--need/block) .refusal .link .capture .seg(--block)` |
| Tags, chips | `.tag(--next/you/now/test/off) .icode .pill-tie .chip(--ok/need/block/info/live) .plan-chip(--need/block/live/off) .recipe(--changed)` |
| Tiles | `.tiles .tile(--big) .tile__k .tile__v .n .u .tile__s .pair .bar .progress .minis .mini .mini__k/__v/__s` |
| Readiness | `.ready-head .ready-head__q .ready-head__n .segbar > i.ok/you/need/block/live/wait .ready-sub .ri(--need/block/live/checking) .ri__t .ri__d .ri__aside .ri__act .ri__note .rtiles .rt .rt--one .rt--wide .rt__t .rt__v .ilk-line .section-k .count .rule` |
| Panel parts | `.steps .step(--done/need/live/block) .step__t .step__s [aria-current=step]`, `.ilist li.<state> .ilist__t/__d/__e/__act`, `.purge(--lg) > i.used/.next`, `.notebox(--need/block/info) .gcode .stopcard .did .rsteps .rstep(--now/done/wait)` |
| Forms | `.field .field__l .unit .field__h .field__err .input(--dirty/--invalid) .row .fgrid .check .capture details.disclose` |
| Tables | `.kv(--wrap) .tbl (td.r) .ptable .headtable (td.hl)` |
| Dialog | `.scrim .dialog(--sm) .dialog__head/__keys/__body/__foot/__sum .grid2 .tick .tick__t/__d .who .keysheet` |
| Utility | `.sr .spacer .muted .dim .nowrap .mono .hide-narrow .show-narrow` |

Breakpoint ≤ 1360 px: nav rail 84 px, panel 388 px, STOP 216 px. The strip drops `.long` value parts by itself
when it would run under STOP (`.cells--t1` / `--t2`).

Icon sprite ids: `i-run i-setup i-profile i-cal i-records i-gauge i-chev i-plus i-minus i-camera i-refresh i-swap
i-play i-capture i-mark i-lock i-plug i-home i-wipe i-hand i-paste i-back i-needle i-close i-sun i-moon i-shield
i-list i-angle i-purge i-keyboard`; glyphs `g-check g-x g-bang g-dash g-info`.

## 8. Ids owned by core (do not create them again)

`btn-stop` (`data-testid=stop`), `skip-main`, `brand`, `heartbeat`, `heartbeat-short`, `sim-tag`, `cells`,
`cell-machine`, `pill-conn`, `pill-homed`, `btn-start`, `btn-home`, `pill-pressure`, `chip-angle`, `chip-purge`,
`pill-engine`, `nav`, `tab-btn-<ws>`, `btn-theme`, `main`, `msg-area`, `ws-<ws>`, `panel`, `panel-bar`,
`panel-back`, `panel-bar-title`, `panel-top`, `panel-scroll`, `panel-stop`, `panel-body`, `panel-foot`,
`stop-unconfirmed`, `toast`, `favicon`, `interlock-list`, `dlg-keys`, and the `pv-*` ids inside core's views.
Fix-it copies elsewhere use `data-fix="<id>"`, never a duplicate id (§16).

## 9. Working on it (SIM only)

```
python3 src/test/sim/il_ui_session.py start --jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --name <you>
python3 src/test/web/devproxy.py --web src/main/resources/org/openpnp/instancelabs/web --api http://127.0.0.1:<port> --port <189xx> &
open http://127.0.0.1:<189xx>/        (files are served from disk: edit, reload; no jar rebuild)
WAIT_MS=2500 node /tmp/shots-tool/shot-live.cjs http://127.0.0.1:<189xx>/#setup out.png 1440 900
python3 src/test/sim/il_ui_session.py stop --name <you>
```
Console helpers: `IL.S`, `IL.ready()`, `IL.readiness(IL.S.status, IL.S.plan, IL.readyCtx())`, `IL.go('#run/checks')`.
Never `/dev/ttyACM*`, `/dev/serial/*`, `/dev/video*` or a real-mode OpenPnP.

## 10. Open items for the workspace stages

- `webui-sim.playwright.cjs` still drives the old UI: update it with the changes listed in §16 when the workspaces
  land (tabs `run|setup|profile|calibrate|records`, `press2` helper, `#pill-pressure .cell__v` `/^ON /`, `Not
  connected`, interlocks via `?panel=interlocks`).
- New static files (`core.js`, `boardview.js`, `run.js`, `setup.js`, `profile.js`, `calibrate.js`, `records.js`,
  `run.css`, `setup.css`, `profile.css`) must be added to `ApiServerSimTest.servesTheUiAndStatus`.
- Optional API additions of §17 (`GET /api/plans`, clean-check progress, `run.pressureOnAtMs`, `server.simulated`)
  are read by core when present (`server.simulated`); the others are for Run.
