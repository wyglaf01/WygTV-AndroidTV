'use strict';
/*
 * Keyboard, mouse and remote navigation.
 * - Arrow keys move focus to the nearest item in that direction (works for lists, grids and the TV guide).
 * - Enter / OK presses the item; holding it (or right-click) opens the item's options.
 * - Escape / Backspace / the remote's Back key goes back.
 * Columns ([data-col]) remember their last focused item; columns with data-wrap wrap around top ↔ bottom.
 */
(function () {
  const { h } = W;
  const stack = []; // { name, args, focusKey, scrolls }
  W.screens = {};
  let current = null;

  function scope() {
    const layers = W.$$('#layers > .layer');
    if (layers.length) return layers[layers.length - 1];
    const p = W.$('#player');
    if (p && p.classList.contains('full')) return p;
    return W.$('#screen');
  }
  W.scope = scope;

  function visible(el) {
    if (!el.isConnected || el.closest('[hidden]')) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function focusables(root) {
    return W.$$('.f', root).filter((el) => !el.classList.contains('disabled') && visible(el));
  }

  W.focus = function focus(el, opts = {}) {
    if (!el) return;
    el.focus({ preventScroll: true });
    if (!opts.noScroll) el.scrollIntoView({ block: opts.block || 'nearest', inline: 'nearest' });
  };

  /** The first item to focus inside a container: [data-autofocus], the remembered item or the first one. */
  W.focusFirst = function focusFirst(root) {
    root = root || scope();
    const auto = W.$('[data-autofocus]', root);
    if (auto && visible(auto)) return W.focus(auto, { block: 'center' });
    const list = focusables(root);
    if (list.length) W.focus(list[0]);
  };

  function colOf(el) { return el && el.closest('[data-col]'); }

  function move(dir) {
    const root = scope();
    const cur = document.activeElement;
    if (!cur || !root.contains(cur) || !cur.classList.contains('f')) { W.focusFirst(root); return; }
    const r = cur.getBoundingClientRect();
    const rcx = r.left + r.width / 2, rcy = r.top + r.height / 2;
    let best = null, bestScore = Infinity;
    for (const el of focusables(root)) {
      if (el === cur) continue;
      const c = el.getBoundingClientRect();
      let primary, overlap, ortho;
      const ccx = c.left + c.width / 2, ccy = c.top + c.height / 2;
      if (dir === 'right' || dir === 'left') {
        primary = dir === 'right' ? c.left - r.right : r.left - c.right;
        if (primary < -Math.min(r.width, c.width) / 2) continue;
        if ((dir === 'right' ? ccx - rcx : rcx - ccx) <= 1) continue;
        overlap = Math.min(r.bottom, c.bottom) - Math.max(r.top, c.top) > 2;
        ortho = overlap ? Math.abs(ccy - rcy) * 0.3 : Math.max(c.top - r.bottom, r.top - c.bottom, 0) * 2 + 5000;
      } else {
        primary = dir === 'down' ? c.top - r.bottom : r.top - c.bottom;
        if (primary < -Math.min(r.height, c.height) / 2) continue;
        if ((dir === 'down' ? ccy - rcy : rcy - ccy) <= 1) continue;
        overlap = Math.min(r.right, c.right) - Math.max(r.left, c.left) > 2;
        const lead = Math.abs(c.left - r.left) * 0.3; // prefer items lined up on the left edge (guide rows)
        ortho = overlap ? lead : Math.max(c.left - r.right, r.left - c.right, 0) * 2 + 5000;
      }
      const score = Math.max(primary, 0) + ortho;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    const col = colOf(cur);
    if (!best || (col && col.hasAttribute('data-wrap') && colOf(best) !== col && (dir === 'up' || dir === 'down'))) {
      if (col && col.hasAttribute('data-wrap') && (dir === 'up' || dir === 'down')) {
        const items = focusables(col);
        if (items.length > 1) { W.focus(dir === 'down' ? items[0] : items[items.length - 1], { block: 'center' }); return; }
      }
      if (!best) return;
    }
    const bcol = colOf(best);
    if (bcol && bcol !== col && (dir === 'left' || dir === 'right') && bcol._last && bcol._last.isConnected && visible(bcol._last)) best = bcol._last;
    W.focus(best);
  }
  W.move = move;

  function page(dir) {
    const cur = document.activeElement;
    const col = colOf(cur) || scope();
    const items = focusables(col);
    const i = items.indexOf(cur);
    if (i < 0) return;
    const n = Math.max(1, Math.floor(col.clientHeight / Math.max(cur.offsetHeight, 1)) - 1);
    W.focus(items[Math.max(0, Math.min(items.length - 1, i + dir * n))], { block: 'center' });
  }

  document.addEventListener('focusin', (e) => {
    const col = colOf(e.target);
    if (col && e.target.classList.contains('f')) col._last = e.target;
    if (e.target._onfocus) e.target._onfocus(e);
  });

  /*
   * Mouse: hovering focuses (so the keyboard picks up where the mouse is) - but only when the mouse itself
   * moves. Scrolling with the wheel slides items under a still pointer and Chromium then sends "mousemove"
   * events at the same spot; following those made focus jump around (and menus switch categories) mid-scroll.
   */
  W.inputMode = 'key';
  let lastX = -1, lastY = -1, lastWheel = 0;
  document.addEventListener('mousemove', (e) => {
    if (e.clientX === lastX && e.clientY === lastY) return;
    lastX = e.clientX; lastY = e.clientY;
    if (Date.now() - lastWheel < 250) return;
    W.inputMode = 'mouse';
    const el = e.target.closest && e.target.closest('.f');
    if (el && el !== document.activeElement && scope().contains(el) && !(document.activeElement && /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName))) {
      el.focus({ preventScroll: true });
    }
  }, { passive: true });
  document.addEventListener('mousedown', () => { W.inputMode = 'mouse'; }, { passive: true });
  document.addEventListener('wheel', (e) => {
    lastWheel = Date.now();
    W.inputMode = 'mouse';
    // Rows that scroll sideways (home strips): the ordinary wheel scrolls them too.
    const strip = e.target.closest && e.target.closest('.strip');
    if (strip && !e.shiftKey && Math.abs(e.deltaY) > Math.abs(e.deltaX) && strip.scrollWidth > strip.clientWidth) {
      const before = strip.scrollLeft;
      strip.scrollLeft += e.deltaY;
      if (strip.scrollLeft !== before) e.preventDefault();
    }
  }, { passive: false });

  /** Wraps a focus handler so it only runs for keyboard / remote focus, not for the mouse passing over. */
  W.keyOnly = (fn) => (e) => { if (W.inputMode === 'key') fn(e); };

  // ------------------------------------------------------------------ touch screens (Windows, Linux, iPhone/iPad; Mac has none)
  // Tap = click (open / play). Touch and hold = options (same as hold Enter / right-click). Swipe right from the left
  // edge = Back (same as Esc). Double-tap the picture = full screen (see player.js). Lists scroll by dragging.
  let pressTimer = null, pressed = null, longFired = false, sx = 0, sy = 0;
  document.addEventListener('touchstart', (e) => {
    W.inputMode = 'touch';
    W.touchUsed = true;
    document.body.classList.add('touch');
    const t = e.touches[0];
    sx = t.clientX; sy = t.clientY; longFired = false;
    pressed = e.target.closest && e.target.closest('.f');
    clearTimeout(pressTimer);
    if (pressed && pressed._long) pressTimer = setTimeout(() => { longFired = true; pressed.focus({ preventScroll: true }); pressed._long(); }, 600);
  }, { passive: true });
  document.addEventListener('touchmove', (e) => {
    const t = e.touches[0];
    if (Math.abs(t.clientX - sx) > 10 || Math.abs(t.clientY - sy) > 10) clearTimeout(pressTimer);
  }, { passive: true });
  document.addEventListener('touchend', (e) => {
    clearTimeout(pressTimer);
    const t = e.changedTouches[0];
    if (sx < 24 && t.clientX - sx > 90 && Math.abs(t.clientY - sy) < 60 && W.back) W.back();
  }, { passive: true });
  document.addEventListener('touchcancel', () => clearTimeout(pressTimer), { passive: true });
  // The tap that ends a touch-and-hold must not also "click" the item.
  document.addEventListener('click', (e) => { if (longFired) { longFired = false; e.stopPropagation(); e.preventDefault(); } }, true);

  document.addEventListener('contextmenu', (e) => {
    // A touch-and-hold already opened the options (touch screens also send a right-click for it): don't open them twice.
    if (longFired) { e.preventDefault(); return; }
    const el = e.target.closest && e.target.closest('.f');
    if (el && el._long) { e.preventDefault(); el._long(); }
  });

  let enterTimer = null, enterLong = false, enterEl = null;

  document.addEventListener('keydown', (e) => {
    W.inputMode = 'key';
    if (W.keyHook && W.keyHook(e)) { e.preventDefault(); return; }
    const t = document.activeElement;
    const typing = t && (/INPUT|TEXTAREA|SELECT/.test(t.tagName));
    const k = e.key;
    if (k === 'F11') { e.preventDefault(); W.api && W.api.win.fullscreen(); return; }
    if (typing) {
      if (k === 'Escape') { e.preventDefault(); t.blur(); const s = scope(); const n = t.closest('.f') || focusables(s)[0]; if (n) n.focus(); return; }
      if (k === 'ArrowDown' || k === 'ArrowUp' || k === 'Tab') { e.preventDefault(); move(k === 'ArrowUp' || (k === 'Tab' && e.shiftKey) ? 'up' : 'down'); return; }
      if (k === 'Enter' && t.tagName !== 'TEXTAREA') { e.preventDefault(); if (t._submit) t._submit(); else move('down'); return; }
      return;
    }
    switch (k) {
      case 'ArrowUp': e.preventDefault(); move('up'); return;
      case 'ArrowDown': e.preventDefault(); move('down'); return;
      case 'ArrowLeft': e.preventDefault(); move('left'); return;
      case 'ArrowRight': e.preventDefault(); move('right'); return;
      case 'Tab': e.preventDefault(); move(e.shiftKey ? 'up' : 'down'); return;
      case 'PageUp': e.preventDefault(); page(-1); return;
      case 'PageDown': e.preventDefault(); page(1); return;
      case 'Home': case 'End': {
        e.preventDefault();
        const items = focusables(colOf(t) || scope());
        if (items.length) W.focus(k === 'Home' ? items[0] : items[items.length - 1], { block: 'center' });
        return;
      }
      case 'Enter': case ' ': {
        e.preventDefault();
        if (e.repeat || !t || !t.classList.contains('f')) { if (!t || !t.classList.contains('f')) W.focusFirst(); return; }
        enterLong = false; enterEl = t;
        clearTimeout(enterTimer);
        if (t._long) enterTimer = setTimeout(() => { enterLong = true; t._long(); }, 600);
        return;
      }
      case 'Escape': case 'Backspace': case 'BrowserBack': case 'GoBack':
        e.preventDefault(); W.back(); return;
      case 'ContextMenu': case 'm': case 'M':
        if (t && t._long) { e.preventDefault(); t._long(); }
        return;
      case '/': case 's': case 'S':
        if (!document.querySelector('#layers > .layer')) { e.preventDefault(); W.go('search'); }
        return;
    }
  });
  document.addEventListener('keyup', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && enterEl) {
      clearTimeout(enterTimer);
      const el = enterEl; enterEl = null;
      if (!enterLong && el === document.activeElement) el.click();
    }
  });
  document.addEventListener('mouseup', (e) => { if (e.button === 3) W.back(); });

  // ------------------------------------------------------------------ screens
  function remember() {
    if (!current) return;
    const a = document.activeElement;
    current.focusKey = a && a.dataset && a.dataset.k || null;
    current.scrolls = W.$$('[data-col]', W.$('#screen')).map((c) => c.scrollTop);
  }

  function show(entry, restore) {
    current = entry;
    const scr = W.screens[entry.name];
    const root = W.$('#screen');
    if (current.cleanup) { try { current.cleanup(); } catch { } }
    root.replaceChildren();
    root.dataset.screen = entry.name;
    const el = scr.render(entry.args || {}, entry);
    root.append(el);
    requestAnimationFrame(() => {
      if (restore && entry.scrolls) W.$$('[data-col]', root).forEach((c, i) => { if (entry.scrolls[i] != null) c.scrollTop = entry.scrolls[i]; });
      const target = restore && entry.focusKey && root.querySelector(`[data-k="${CSS.escape(entry.focusKey)}"]`);
      if (target) W.focus(target, { block: 'nearest' }); else W.focusFirst(root);
    });
  }

  W.go = function go(name, args) {
    if (!W.screens[name]) return;
    remember();
    if (current && current.cleanup) { try { current.cleanup(); } catch { } current.cleanup = null; }
    const entry = { name, args };
    if (current) stack.push(current);
    show(entry, false);
  };

  /** Replace the current screen (no new history entry). */
  W.replace = function replace(name, args) {
    if (current && current.cleanup) { try { current.cleanup(); } catch { } current.cleanup = null; }
    show({ name, args }, false);
  };

  W.refresh = function refresh() {
    if (!current) return;
    remember();
    show(current, true);
  };

  W.current = () => current;

  W.back = function back() {
    const layers = W.$$('#layers > .layer');
    if (layers.length) { layers[layers.length - 1]._close(); return; }
    if (W.player && W.player.isFull()) { W.player.exitFull(); return; }
    if (!stack.length) {
      if (current && current.name !== 'home') { W.replace('home'); return; }
      return;
    }
    if (current && current.cleanup) { try { current.cleanup(); } catch { } current.cleanup = null; }
    show(stack.pop(), true);
  };

  W.home = function home() { stack.length = 0; if (current && current.cleanup) { try { current.cleanup(); } catch { } current.cleanup = null; } show({ name: 'home' }, false); };

  // ------------------------------------------------------------------ pop-ups
  function layer(content, cls) {
    const prev = document.activeElement;
    const el = h('div', { class: 'layer ' + (cls || '') }, h('div', { class: 'scrim' }), content);
    el._close = () => { el.remove(); if (el._onclose) el._onclose(); if (prev && prev.isConnected) prev.focus({ preventScroll: true }); };
    el.firstChild.addEventListener('click', () => el._close());
    W.$('#layers').append(el);
    requestAnimationFrame(() => W.focusFirst(el));
    return el;
  }
  W.layer = layer;

  /** A list of choices. items: [{ label, sub?, action, checked? }]. */
  W.menu = function menu(title, items, opts = {}) {
    return new Promise((resolve) => {
      let done = false;
      const box = h('div', { class: 'dialog menu' + (opts.wide ? ' wide' : '') },
        title ? h('div', { class: 'dialog-title' }, title) : null,
        opts.text ? h('div', { class: 'dialog-text' }, opts.text) : null,
        h('div', { class: 'menu-list', 'data-col': '', 'data-wrap': '' },
          items.filter(Boolean).map((it, i) => W.btn({
            class: 'menu-item' + (it.checked ? ' checked' : '') + (it.danger ? ' danger' : ''), 'data-autofocus': it.checked || (opts.focus === i) ? '' : null,
            onclick: () => { done = true; L._close(); resolve(i); if (it.action) it.action(); },
          }, h('span', { class: 'mi-label' }, it.label), it.sub ? h('span', { class: 'mi-sub' }, it.sub) : null))));
      const L = layer(box);
      L._onclose = () => { if (!done) resolve(-1); };
    });
  };

  W.confirm = (title, text, yes = 'OK', no = 'Cancel') =>
    W.menu(title, [{ label: yes }, { label: no }], { text }).then((i) => i === 0);

  W.alert = (title, text) => W.menu(title, [{ label: 'OK' }], { text });

  /**
   * A form. fields: [{ key, label, value, type?: 'text'|'password'|'url'|'textarea', placeholder }].
   * Resolves with { key: value } or null when cancelled.
   */
  W.form = function form(title, fields, opts = {}) {
    return new Promise((resolve) => {
      let done = false;
      const inputs = {};
      const ok = () => {
        const out = {};
        for (const [k, el] of Object.entries(inputs)) out[k] = el.value;
        if (opts.validate) { const err = opts.validate(out); if (err) { msg.textContent = err; return; } }
        done = true; L._close(); resolve(out);
      };
      const msg = h('div', { class: 'form-error' });
      const box = h('div', { class: 'dialog form' },
        h('div', { class: 'dialog-title' }, title),
        opts.text ? h('div', { class: 'dialog-text' }, opts.text) : null,
        fields.map((f, i) => {
          const input = h(f.type === 'textarea' ? 'textarea' : 'input', {
            class: 'f input', type: f.type === 'textarea' ? null : (f.type || 'text'), placeholder: f.placeholder || '',
            spellcheck: 'false', autocomplete: 'off', 'data-autofocus': i === 0 ? '' : null, rows: f.type === 'textarea' ? 4 : null,
          });
          input.value = f.value || '';
          input._submit = () => { const all = Object.values(inputs); const j = all.indexOf(input); if (j < all.length - 1) all[j + 1].focus(); else ok(); };
          inputs[f.key] = input;
          return h('label', { class: 'field' }, h('span', { class: 'field-label' }, f.label), input);
        }),
        msg,
        h('div', { class: 'dialog-buttons' },
          (opts.extra || []).map((x) => W.btn({ class: 'button', onclick: () => x.action(inputs) }, x.label)),
          W.btn({ class: 'button primary', onclick: ok }, opts.okLabel || 'Save'),
          W.btn({ class: 'button', onclick: () => L._close() }, 'Cancel')));
      const L = layer(box);
      L._onclose = () => { if (!done) resolve(null); };
      setTimeout(() => { const first = Object.values(inputs)[0]; if (first) first.focus(); }, 30);
    });
  };

  W.busy = function busy(text) {
    const box = h('div', { class: 'dialog busy' }, h('div', { class: 'spinner' }), h('div', { class: 'dialog-text' }, text));
    const L = layer(box, 'busy-layer');
    return { close: () => L.isConnected && L._close(), set: (t) => { box.lastChild.textContent = t; } };
  };
})();
