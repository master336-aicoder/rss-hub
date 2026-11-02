// Route-citation audit (F15). For each doc citation into a Spring controller, reconstruct the effective URL of the
// cited line (class-level @RequestMapping + the mapping annotation governing that line) and compare it with the route
// the doc row claims. Catches same-file line drift, which a range check cannot see.
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DOCS = ['doc/test-plan.md', 'doc/design/01-requirements.md', 'doc/design/02-architecture.md',
  'doc/design/03-technical-design.md', 'doc/design/04-roadmap.md'];
const CIT = /\[([^\]]*)\]\(([^)]+\.java)(#L(\d+))?\)/g;
// Only tokens that can be endpoint paths, never file paths.
const ROUTE = /(\/(?:api|feed|tag|category|repack|event|blog|opml|health)[a-z0-9\-{}/.<>]*)(?=[\s`,)|"'\]])/gi;

const strip = r => r.replace(/\.\{format\}/g, '').replace(/\{[^}]*\}/g, '').replace(/<[^>]*>/g, '')
  .replace(/\/\d+(?=\/|$)/g, '').replace(/\/{2,}/g, '/')
  .replace(/\.(rss|atom|json|xml)$/i, '')
  .replace(/\.$/, '').replace(/\/$/, '').toLowerCase();
const fileCache = new Map();
const readSrc = f => {
  if (!fileCache.has(f)) fileCache.set(f, fs.readFileSync(f, 'utf8').split(/\r?\n/));
  return fileCache.get(f);
};

function effective(src, line) {
  let prefix = '';
  for (let i = 0; i < Math.min(src.length, line); i++) {
    const m = /@RequestMapping\("([^"]+)"\)/.exec(src[i]);
    if (m && i < line) prefix = m[1];
  }
  // A citation often lands on the javadoc above a handler, so the governing annotation may be a few lines below.
  const found = [];
  for (let i = Math.max(0, line - 13); i < Math.min(src.length, line + 12); i++) {
    const m = /@(?:Get|Post|Patch|Put|Delete|Request)Mapping(?:\("([^"]*)"\))?/.exec(src[i]);
    if (m) found.push((prefix + (m[1] ? '/' + m[1] : '')).toLowerCase());
  }
  return found.length ? found : null;
}

let checked = 0;
const bad = [];
for (const doc of DOCS) {
  const lines = fs.readFileSync(path.join(ROOT, doc), 'utf8').split(/\r?\n/);
  lines.forEach((text, idx) => {
    const routes = [...new Set([...text.matchAll(ROUTE)].map(m => strip(m[1])))].filter(r => r.length > 4);
    if (!routes.length) return;
    let m;
    CIT.lastIndex = 0;
    while ((m = CIT.exec(text)) !== null) {
      const abs = path.normalize(path.join(ROOT, path.dirname(doc), m[2]));
      if (!fs.existsSync(abs)) continue;
      const start = m[4] ? Number(m[4]) : 0;
      if (!start || start > fs.readFileSync(abs, 'utf8').split(/\r?\n/).length) continue;
      const eff = effective(readSrc(abs), start);
      const labelNum = /:(\d+)(?:-\d+)?(?=[\s·]|$)/.exec(m[1].replace(/`/g, ''));
      if (labelNum && Number(labelNum[1]) !== start) {
        bad.push(`${doc}:${idx + 1} LABEL/ANCHOR-MISMATCH [${m[1]}] -> #L${start} (${path.basename(abs)})`);
      }
      if (eff === null) continue;                       // no mapping annotation near the cited line
      checked++;
      const es = eff.map(strip).filter(e => e.length >= 2);
      if (!es.length) continue;
      const ok = routes.some(r => es.some(e => r.startsWith(e) || e.startsWith(r) || r === e));
      if (!ok) {
        bad.push(`${doc}:${idx + 1} ROUTE-DRIFT cites ${path.basename(abs)}#L${start} whose route is `
          + `[${eff.join(' | ')}] but the row claims ${routes.join(' ')} | line: ${(readSrc(abs)[start - 1] || '').trim().slice(0, 60)}`);
      }
    }
  });
}
console.log(`route-bearing citations checked: ${checked}`);
console.log(bad.length ? bad.join('\n') : 'no drift');
console.log(`flagged: ${bad.length}`);
