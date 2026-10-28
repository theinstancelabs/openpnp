/* records.js: the Records workspace (spec docs/instancelabs/UI_DESIGN.md §8.5). STUB from the foundation stage: it only
   registers an empty workspace so the frame works. The workspace stage replaces this file; the contract is in
   DEV_NOTES.md (IL.workspace, IL.panel, IL.readiness, IL.press2, ...). Styles: app.css classes (.tbl, .seg). */
'use strict';
(function () {
  const { el } = IL;
  IL.workspace('records', {
    mount(root) {
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Records · boards, activity, calibrations' }), el('h1', { text: 'Records' }))),
        el('div', { class: 'card ws-empty' }, IL.icon('i-records'),
          el('h2', { text: 'Not built yet' }),
          el('p', { text: 'This workspace is rebuilt in the next stage. The status strip, STOP and the views behind the strip cells work now.' })));
    },
    panel(parts, status, first) {
      if (!first) return;
      parts.body.append(
        el('p', { class: 'panel__lead', text: 'The context panel for Records comes with the workspace. Shared views:' }),
        IL.fixRow([{ view: 'interlocks', label: 'Interlocks' }, { view: 'machine', label: 'Machine' },
          { view: 'pressure', label: 'Pressure' }, { view: 'activity', label: 'Activity' }], 'btn-row'));
    },
  });
})();
