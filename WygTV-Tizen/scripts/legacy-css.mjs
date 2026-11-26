// CSS fallbacks for Tizen 5.5 (Chromium 69), used by scripts/build.mjs and the tests.
// Flex `gap` (Chrome 84), `aspect-ratio` (88), `min()`/`max()`/`clamp()` (79) and `:has()` (105) are newer
// than 2020 Samsung TVs. legacyCss() keeps every rule as it is and adds:
// - after each flex rule with a gap, margins between its children under `html.no-flex-gap`
//   (web/tizen-legacy.js sets that class only when the engine ignores flex gap)
// - a px value in front of each min()/max()/clamp() value
// - selectors with :has() split into a rule of their own, so the others still apply
// and returns the aspect-ratio rules for tizen-legacy.js to apply with script.
/** Splits a stylesheet into top-level blocks: { prelude, body } for rules, { raw } for everything else. */
export function parseCss(css) {
  const out = [];
  let i = 0;
  while (i < css.length) {
    if (css.startsWith('/*', i)) { const e = css.indexOf('*/', i + 2); const end = e < 0 ? css.length : e + 2; out.push({ raw: css.slice(i, end) }); i = end; continue; }
    if (/\s/.test(css[i])) { let j = i; while (j < css.length && /\s/.test(css[j])) j++; out.push({ raw: css.slice(i, j) }); i = j; continue; }
    const open = css.indexOf('{', i);
    if (open < 0) { out.push({ raw: css.slice(i) }); break; }
    let depth = 1, j = open + 1;
    while (j < css.length && depth) { if (css[j] === '{') depth++; else if (css[j] === '}') depth--; j++; }
    const prelude = css.slice(i, open).trim(), body = css.slice(open + 1, j - 1);
    out.push(prelude.startsWith('@') ? { raw: css.slice(i, j) } : { prelude, body });
    i = j;
  }
  return out;
}

/** Selector list → selectors, splitting on commas outside brackets. */
export function splitSelectors(s) {
  const out = []; let depth = 0, cur = '';
  for (const c of s) {
    if (c === '(' || c === '[') depth++;
    if (c === ')' || c === ']') depth--;
    if (c === ',' && !depth) { out.push(cur.trim()); cur = ''; } else cur += c;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function decls(body) {
  const m = {};
  for (const d of body.split(';')) {
    const k = d.indexOf(':');
    if (k < 0) continue;
    m[d.slice(0, k).trim().toLowerCase()] = d.slice(k + 1).replace(/!important/, '').trim();
  }
  return m;
}

/** `html.no-flex-gap` in front of a selector (or added to its leading html / :root). */
function under(cls, sel) {
  if (/^:root\b/.test(sel)) return sel.replace(/^:root/, 'html.' + cls);
  if (/^html\b/.test(sel)) return sel.replace(/^html/, 'html.' + cls);
  return 'html.' + cls + ' ' + sel;
}

/** A px fallback for a min()/max()/clamp() value: its first px argument, or nothing. */
export function pxFallback(value) {
  if (!/(^|[^a-z-])(min|max|clamp)\(/i.test(value)) return null;
  const px = value.match(/-?\d*\.?\d+px/);
  return px ? value.replace(/(^|[^a-z-])(min|max|clamp)\([^()]*\)/gi, (_m, pre) => pre + px[0]) : null;
}

export function legacyCss(css) {
  const blocks = parseCss(css);
  const rules = blocks.filter((b) => b.prelude);
  // Simple selectors pushed apart with an auto margin keep it: the gap fallback skips them.
  const autoMargin = new Set();
  for (const r of rules) {
    const d = decls(r.body);
    if (/^auto$/.test(d['margin-left'] || '') || /^auto$/.test(d['margin-top'] || '')) {
      for (const s of splitSelectors(r.prelude)) if (/^[.#][\w-]+$/.test(s)) autoMargin.add(s);
    }
  }
  const skip = [...autoMargin].map((s) => `:not(${s})`).join('');
  const gapRules = []; // { sels, row, col, dir, wrap }
  const aspect = []; // [selector, ratio | 0 for auto]
  const out = [];
  const marginRules = (sels, row, col, dir, wrap, reset) => {
    const kids = (s) => (wrap ? `${under('no-flex-gap', s)} > *${skip}` : `${under('no-flex-gap', s)} > :not([hidden]) ~ :not([hidden])${skip}`);
    let m;
    if (wrap) m = `margin-right: ${col}; margin-bottom: ${row};`;
    else if (/column/.test(dir)) m = `margin-top: ${row};` + (reset ? ' margin-left: 0;' : '');
    else m = `margin-left: ${col};` + (reset ? ' margin-top: 0;' : '');
    return `\n${sels.map((s) => under('no-flex-gap', s)).join(', ')} { gap: 0; }\n${sels.map(kids).join(', ')} { ${m} }`;
  };
  for (const b of blocks) {
    if (!b.prelude) { out.push(b.raw); continue; }
    let sels = splitSelectors(b.prelude);
    const d = decls(b.body);
    // :has() makes Chromium 69 drop the whole rule, so selectors with it get a rule of their own.
    const has = sels.filter((s) => s.includes(':has('));
    let body = b.body;
    // min() / max() / clamp() get a px value in front for engines that don't know them.
    body = body.replace(/(^|;)(\s*)([a-z-]+)\s*:([^;]*)/gi, (all, semi, ws, prop, val) => {
      const fb = pxFallback(val);
      return fb ? `${semi}${ws}${prop}:${fb};${ws}${prop}:${val}` : all;
    });
    if (has.length && has.length < sels.length) {
      sels = sels.filter((s) => !s.includes(':has('));
      out.push(`${sels.join(', ')} {${body}}\n${has.join(', ')} {${body}}`);
    } else out.push(`${b.prelude} {${body}}`);
    if (d['aspect-ratio']) {
      const v = d['aspect-ratio'];
      const ratio = v === 'auto' ? 0 : v.includes('/') ? v.split('/').map(Number).reduce((a, c) => a / c) : Number(v);
      for (const s of sels) aspect.push([s, Math.round(ratio * 1e4) / 1e4]);
    }
    const flex = /^(inline-)?flex$/.test(d.display || '');
    if (flex && d.gap) {
      const [row, col = row] = d.gap.split(/\s+/);
      const dir = d['flex-direction'] || 'row', wrap = /^wrap/.test(d['flex-wrap'] || '') || /wrap/.test(d['flex-flow'] || '');
      gapRules.push({ sels, row, col, dir, wrap });
      if (row !== '0' || col !== '0') out.push(marginRules(sels, row, col, dir, wrap, false));
    } else if (d['flex-direction'] && !d.gap) {
      // e.g. .tile.small turning a column of .tile into a row: its gap goes the other way.
      for (const g of gapRules) {
        const ext = sels.filter((s) => g.sels.some((gs) => s.startsWith(gs) && /^[.:#[]/.test(s.slice(gs.length))));
        if (ext.length) out.push(marginRules(ext, g.row, g.col, d['flex-direction'], g.wrap, true));
      }
    }
  }
  return { css: out.join(''), aspect };
}
