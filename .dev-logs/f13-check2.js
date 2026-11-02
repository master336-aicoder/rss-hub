// Full-doc citation check: every `](../path.ext#LNN[-MM])` in the docs must point at a real file and an in-range line.
// For .vue/.ts it additionally requires that some label token appears within ±4 lines of the target.
const fs = require("fs");
const path = require("path");

const DOCS = ["doc/test-plan.md", "doc/design/01-requirements.md", "doc/design/02-architecture.md", "doc/design/03-technical-design.md", "doc/design/04-roadmap.md"];
const cache = {};
function lines(rel, from) {
  const p = path.normalize(path.join(path.dirname(from), rel));
  if (cache[p] === undefined) cache[p] = fs.existsSync(p) ? fs.readFileSync(p, "utf8").split(/\r?\n/) : null;
  return cache[p];
}
const STOP = new Set([
  "class", "const", "false", "true", "function", "return", "style", "color", "border", "font", "px", "rem", "var", "none",
  "solid", "button", "input", "span", "div", "template", "type", "value", "width", "height", "display", "flex", "grid",
  "margin", "padding", "background", "aria", "hidden", "required", "placeholder", "maxlength", "rows", "step", "min",
  "max", "size", "multiple", "disabled", "vmodel", "vif", "vfor", "key", "href", "target", "rel", "vue", "app", "main",
  "smoke", "curl", "grep", "node", "http", "https", "api", "json", "yaml",
]);
function sig(s, drop) {
  const out = new Set();
  for (const c of s.match(/[一-鿿][^<>"'`\s]*/g) || []) out.add(c.slice(0, 4));
  for (const id of s.match(/[A-Za-z_$][A-Za-z0-9_$]{3,}/g) || []) {
    if (STOP.has(id.toLowerCase()) || drop.has(id)) continue;
    out.add(id);
  }
  return [...out];
}
let bad = 0;
let total = 0;
for (const doc of DOCS) {
  const text = fs.readFileSync(doc, "utf8");
  const docLines = text.split(/\r?\n/);
  const re = /\[([^\]]*)\]\((\.\.\/[^)#\s]+?)#L(\d+)(?:-(\d+))?\)/g;
  let mt;
  while ((mt = re.exec(text)) !== null) {
    total++;
    const docLineNo = text.slice(0, mt.index).split(/\r?\n/).length;
    const at = (msg) => {
      console.log(`${doc}:${docLineNo} ${msg}`);
      bad++;
    };
    const arr = lines(mt[2], doc);
    if (!arr) {
      at(`MISSING FILE ${mt[2]}`);
      continue;
    }
    const ln = Number(mt[3]);
    const end = Number(mt[4] || mt[3]);
    if (ln < 1 || end > arr.length || ln > arr.length) {
      at(`OUT OF RANGE ${mt[2]}#L${ln}${mt[4] ? "-" + mt[4] : ""} (file has ${arr.length})`);
      continue;
    }
    if (/\.(vue|ts)$/.test(mt[2]) && ln <= 4 && end > arr.length - 4) continue;
    if (!/\.(vue|ts|css)$/.test(mt[2])) continue;
    const base = path.basename(mt[2]);
    const drop = new Set([base.replace(/\.(vue|ts|css)$/, ""), "vue", "ts", "css", "src", "main", "theme", "fluent", "index", "App", "router", "styles", "stores", "composables"]);
    const tokens = sig(mt[1], drop);
    if (!tokens.length) continue;
    const near = arr.slice(Math.max(0, ln - 5), ln + 4).join("\n");
    if (!tokens.some((t) => near.includes(t))) {
      at(`NO-LABEL-HIT ${mt[0].slice(0, 70)} tokens=${JSON.stringify(tokens).slice(0, 60)} line=${JSON.stringify(String(arr[ln - 1]).trim()).slice(0, 70)}`);
    }
  }
}
console.log(`checked ${total} citations, flagged ${bad}`);
