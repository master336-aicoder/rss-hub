const fs = require('fs');
const css = fs.readFileSync(require('path').resolve(__dirname, '../web/node_modules/bootstrap/dist/css/bootstrap.min.css'), 'utf8');

const GRAY = /#6c757d|#5c636a|#565e64|#62686d|#212529|#1a1d20|#adb5bd|#dee2e6|#e9ecef|#f8f9fa|#ced4da|#495057|#d3d9df|#c5c8cb/i;

// every class token actually used in the app
const path = require('path');
const used = new Set();
const src = path.resolve(__dirname, '../web/src');
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (p.endsWith('.vue')) {
      const txt = fs.readFileSync(p, 'utf8');
      for (const m of txt.matchAll(/["'` ]([a-z][a-z0-9]*(?:-[a-z0-9]+)*)["'` ]/g)) used.add(m[1]);
      for (const m of txt.matchAll(/class="([^"]*)"/g)) for (const t of m[1].split(/\s+/)) used.add(t);
    }
  }
})(src);

const out = [];
for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const sel = m[1].trim();
  const decl = m[2];
  if (!GRAY.test(decl)) continue;
  const toks = sel.split(',').map(s => s.trim().replace(/\s+/g, ' '));
  const hit = toks.some(t => t.split(/[\s.:[>]+/).filter(Boolean).some(c => used.has(c)));
  if (!hit) continue;
  const grays = [...new Set(decl.match(/#[0-9a-f]{3,6}/gi).filter(h => GRAY.test(h)))];
  out.push({ sel: toks.filter(t => t.split(/[\s.:[>]+/).filter(Boolean).some(c => used.has(c))).join(','), grays });
}
const seen = new Map();
for (const r of out) {
  const k = r.sel;
  if (!seen.has(k)) seen.set(k, new Set());
  r.grays.forEach(g => seen.get(k).add(g));
}
for (const [k, v] of [...seen].sort()) console.log(k.padEnd(42) + [...v].join(' '));
console.log('\nrules touching an in-use selector with a hardcoded gray: ' + seen.size);
