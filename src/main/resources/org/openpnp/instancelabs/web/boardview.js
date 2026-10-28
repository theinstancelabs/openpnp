/* boardview.js: the to-scale board preview (SVG in machine mm, board +Y up) shared by Run (hero, probe overlay) and
   Records (done-dot map). STUB from the foundation stage: it only reserves the namespace IL.boardview. The Run stage
   fills it (spec §8.1 hero, §15 "Board SVG": built once per plan, polls only toggle classes; test classes rect.pad,
   rect.pad.dirty, circle.dot, circle.dot.done). Styles: run.css (.pv ...). */
'use strict';
(function () {
  IL.boardview = IL.boardview || {};
})();
