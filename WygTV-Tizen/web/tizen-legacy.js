'use strict';
/*
 * WygTV for Samsung TVs: fills in CSS that 2020 TVs (Tizen 5.5, Chromium 69) don't have. It runs before the
 * screens, and does nothing on TVs whose engine already has these features.
 * - Flex `gap`: when it is ignored, html gets the class `no-flex-gap`, which turns on the margins
 *   scripts/legacy-css.mjs added after each flex rule.
 * - `aspect-ratio`: when unknown, elements matching the rules below get a height from their width.
 * Add ?legacy=1 to the page address (or set localStorage wyg.legacy = 1) to try both on a newer browser.
 */
(function () {
  // How big everything is drawn on the TV (Settings → Appearance → Size of text and menus). The page is laid out
  // narrower than the 1920-pixel screen and the TV scales it up, so text, buttons and spacing all grow together:
  // xlarge lays out 1097 wide (1.75×), large 1280 (1.5×, the usual size for TV apps and the default), medium 1536
  // (1.25×), small 1920 (as on a computer).
  const SIZES = { xlarge: 1097, large: 1280, medium: 1536, small: 1920 };
  let tvSize = 'large';
  try { tvSize = JSON.parse(localStorage.getItem('wyg.tvSize') || '"large"'); } catch (e) { }
  if (!SIZES[tvSize]) tvSize = 'large';
  const vp = document.querySelector('meta[name=viewport]');
  if (vp) vp.setAttribute('content', 'width=' + SIZES[tvSize] + ', user-scalable=no');
  window.__wygTvSize = tvSize;

  // [selector, width ÷ height] from styles.css in stylesheet order; 0 means `aspect-ratio: auto`. Filled in by the build.
  const ASPECT = /*@ASPECT@*/[];
  const root = document.documentElement;
  let forced = /[?&]legacy=1\b/.test(location.search);
  try { forced = forced || localStorage.getItem('wyg.legacy') === '1'; } catch (e) { }

  function flexGapWorks() {
    const d = document.createElement('div');
    d.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden';
    d.appendChild(document.createElement('div'));
    d.appendChild(document.createElement('div'));
    root.appendChild(d);
    const ok = d.scrollHeight === 1;
    root.removeChild(d);
    return ok;
  }
  const aspectWorks = !!(window.CSS && CSS.supports && CSS.supports('aspect-ratio', '1 / 1'));

  if (forced || !flexGapWorks()) root.classList.add('no-flex-gap');
  if (!forced && aspectWorks) return;
  root.classList.add('no-aspect-ratio');

  /** The ratio the stylesheet gives an element (the last matching rule wins), or 0. */
  function ratioOf(el) {
    let r = 0, hit = false;
    for (let i = 0; i < ASPECT.length; i++) {
      let m = false;
      try { m = el.matches(ASPECT[i][0]); } catch (e) { }
      if (m) { r = ASPECT[i][1]; hit = true; }
    }
    return hit ? r : 0;
  }
  const sized = new Set();
  const ro = window.ResizeObserver ? new ResizeObserver((entries) => entries.forEach((e) => size(e.target))) : null;
  function size(el) {
    const r = ratioOf(el);
    if (!r || !el.isConnected) { el.style.removeProperty('height'); sized.delete(el); if (ro) ro.unobserve(el); return; }
    // offsetWidth is the layout width: a focused tile's scale() must not feed back into its size.
    const w = el.offsetWidth;
    if (!w) return;
    const hgt = Math.round(w / r * 100) / 100 + 'px';
    if (el.style.height !== hgt) el.style.height = hgt;
  }
  const all = ASPECT.filter((a) => a[1]).map((a) => a[0]).join(', ');
  let queued = false;
  function scan() {
    queued = false;
    if (all) {
      let list = [];
      try { list = document.querySelectorAll(all); } catch (e) { }
      for (let i = 0; i < list.length; i++) {
        const el = list[i];
        if (!sized.has(el)) { sized.add(el); if (ro) ro.observe(el); }
        size(el);
      }
    }
    // Elements that stopped matching (e.g. the player leaving its mini window) lose the height.
    sized.forEach((el) => { if (!el.isConnected || !ratioOf(el)) size(el); });
  }
  const later = () => { if (!queued) { queued = true; requestAnimationFrame(scan); } };
  new MutationObserver(later).observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'hidden'] });
  window.addEventListener('resize', later);
  later();
})();
