// Runs requests of the eSignet Postman collection outside the Postman app.
//
// newman's sandbox has no Web Crypto, which the collection's scripts need to generate client keys,
// DPoP proofs and client assertions. This runner executes the collection's own pre-request and test
// scripts in Node (which has crypto.subtle) behind a minimal `pm` shim, so the Native SSO folder can
// be checked end to end against a running eSignet.
//
// Usage: node run.cjs <collection.json> <environment.json> "<Folder>/<Request>" ...
const fs = require('fs');
const chai = require('chai');

const [collectionPath, envPath, ...selectors] = process.argv.slice(2);
const collection = JSON.parse(fs.readFileSync(collectionPath, 'utf8'));
const environment = {};
for (const v of JSON.parse(fs.readFileSync(envPath, 'utf8')).values) environment[v.key] = v.value;
const collectionVars = {};
for (const v of collection.variable || []) collectionVars[v.key] = v.value;

function findItem(items, path) {
  const [head, ...rest] = path;
  const item = items.find((i) => i.name === head);
  if (!item) return null;
  return rest.length === 0 ? item : findItem(item.item || [], rest);
}

function resolve(s) {
  if (typeof s !== 'string') return s;
  return s.replace(/\{\{([^}]+)\}\}/g, (_, k) => {
    if (k === '$isoTimestamp') return new Date().toISOString();
    if (k === '$randomEmail') return `user${Date.now()}@example.com`;
    if (k in collectionVars && collectionVars[k] !== '') return collectionVars[k];
    return environment[k] ?? '';
  });
}

async function runScript(code, pm) {
  const fn = new Function('pm', 'crypto', 'console', `return (async () => {\n${code}\n})();`);
  await fn(pm, globalThis.crypto, console);
}

async function runItem(label, item) {
  const req = item.request;
  const headers = {};
  for (const h of req.header || []) headers[h.key] = h.value;
  const tests = [];
  const pmBase = {
    environment: { get: (k) => environment[k], set: (k, v) => { environment[k] = String(v); } },
    collectionVariables: { get: (k) => collectionVars[k], set: (k, v) => { collectionVars[k] = String(v); } },
    expect: chai.expect,
    test: (name, fn) => {
      try { fn(); tests.push([true, name]); } catch (e) { tests.push([false, `${name}: ${e.message}`]); }
    },
  };
  const pm = {
    ...pmBase,
    request: {
      method: req.method,
      headers: { upsert: ({ key, value }) => { headers[key] = value; } },
    },
  };

  for (const ev of item.event || []) {
    if (ev.listen === 'prerequest') await runScript(ev.script.exec.join('\n'), pm);
  }

  const url = resolve(req.url.raw);
  let body;
  if (req.body?.mode === 'raw') body = resolve(req.body.raw);
  if (req.body?.mode === 'urlencoded') {
    body = new URLSearchParams(req.body.urlencoded.map((p) => [p.key, resolve(p.value)])).toString();
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
  }
  const resolvedHeaders = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k, resolve(v)]));
  const res = await fetch(url, { method: req.method, headers: resolvedHeaders, body, redirect: 'manual' });
  const text = await res.text();

  const response = {
    code: res.status,
    text: () => text,
    json: () => JSON.parse(text),
    headers: { get: (k) => res.headers.get(k) },
    to: { have: { status: (s) => chai.expect(res.status, `HTTP status (body: ${text.slice(0, 200)})`).to.eql(s) } },
  };
  for (const ev of item.event || []) {
    if (ev.listen === 'test') await runScript(ev.script.exec.join('\n'), { ...pmBase, response });
  }

  const failed = tests.filter(([ok]) => !ok);
  console.log(`${failed.length ? 'FAIL' : 'PASS'}  ${req.method.padEnd(5)} ${res.status}  ${label}`);
  for (const [ok, name] of tests) console.log(`        ${ok ? '✓' : '✗'} ${name}`);
  return failed.length === 0;
}

(async () => {
  let ok = true;
  for (const selector of selectors) {
    const item = findItem(collection.item, selector.split('/'));
    if (!item) { console.log(`MISSING ${selector}`); ok = false; break; }
    if (!(await runItem(selector, item))) ok = false;
  }
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
