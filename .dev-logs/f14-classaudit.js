const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../web/src');
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (p.endsWith('.vue')) files.push(p);
  }
})(root);

const COLORY = /(^|-)(primary|secondary|success|danger|warning|info|light|dark|body|white|black|muted)(-|$)/;
const hits = new Map();

for (const f of files) {
  const lines = fs.readFileSync(f, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    for (const m of line.matchAll(/(?:class|:class|:class="\{[^}]*)="([^"]*)"/g)) {
      for (const tok of m[1].split(/[\s'",:{}]+/)) {
        if (!COLORY.test(tok)) continue;
        if (!/^(btn|text|bg|border|alert|link|table|nav|list-group|page|form|badge)/.test(tok)) continue;
        const k = tok.replace(/^btn-/, 'btn:');
        if (!hits.has(k)) hits.set(k, []);
        hits.get(k).push(path.relative(root, f) + ':' + (i + 1));
      }
    }
    for (const m of line.matchAll(/['"]((?:text|bg|border|btn)-(?:primary|secondary|success|danger|warning|info|light|dark|body[a-z-]*))[a-z-]*['"]/g)) {
      const tok = m[1];
      const k = tok.replace(/^btn-/, 'btn:');
      if (!hits.has(k)) hits.set(k, []);
      hits.get(k).push(path.relative(root, f) + ':' + (i + 1) + ' (binding)');
    }
  });
}

const keys = [...hits.keys()].sort();
for (const k of keys) {
  const where = hits.get(k);
  console.log(k.padEnd(26) + String(where.length).padStart(3) + '  ' + where.slice(0, 3).join(', ') + (where.length > 3 ? ' …' : ''));
}
console.log('\nunique colour-bearing classes: ' + keys.length + ' across ' + files.length + ' .vue files');
