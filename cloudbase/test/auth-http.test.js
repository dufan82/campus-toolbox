'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
let authImpl, httpImpl;
try { authImpl = require('../src/auth'); httpImpl = require('../src/http'); } catch (e) { if (e.code !== 'MODULE_NOT_FOUND') throw e; }
function auth(fetchImpl, extra = {}) {
  assert.equal(typeof authImpl?.createOfficialAuth, 'function', 'official HTTP authentication must exist');
  return authImpl.createOfficialAuth({ origin: 'https://example-env.api.tcloudbasegateway.com', adminUids: ['allowed'], fetchImpl, ...extra });
}
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
test('official verification uses fixed user/me, bearer and validated sub only', async () => {
  let request; const a = auth(async (url, options) => { request = { url, options }; return json({ sub: 'allowed', status: 'ACTIVE', uid: 'forged' }); });
  assert.deepEqual(await a.verify('opaque'), { uid: 'allowed', isAdmin: true });
  assert.equal(request.url, 'https://example-env.api.tcloudbasegateway.com/auth/v1/user/me');
  assert.equal(request.options.headers.Authorization, 'Bearer opaque'); assert.equal(request.options.redirect, 'error'); assert.ok(request.options.signal);
});
test('adminLogin official signin then user/me allowlist; no token sub trust', async () => {
  const calls = []; const a = auth(async (url, options) => { calls.push([url, options]); return json(url.endsWith('/signin') ? { access_token: 'opaque-official', expires_in: 7200, sub: 'allowed' } : { sub: 'not-admin', status: 'ACTIVE' }); });
  await assert.rejects(a.login({ username: 'name', password: 'secret' }), e => e.code === 'ADMIN_REQUIRED'); assert.equal(calls.length, 2);
  assert.deepEqual(JSON.parse(calls[0][1].body), { username: 'name', password: 'secret' });
});
test('allowlisted signin returns only access token and expiry', async () => {
  const a = auth(async url => json(url.endsWith('/signin') ? { access_token: 'official', refresh_token: 'do-not-return', expires_in: 3600 } : { sub: 'allowed', status: 'DEFAULT' }));
  assert.deepEqual(await a.login({ username: 'admin', password: 'secret' }), { accessToken: 'official', expiresIn: 3600 });
});
test('invalid expired or missing-sub auth fails closed', async () => {
  for (const response of [json({}, 401), json({ uid: 'allowed' }), json({ sub: '' }), new Response('redirect', { status: 302 })]) {
    await assert.rejects(auth(async () => response).verify('x'), e => ['AUTH_INVALID','AUTH_UNAVAILABLE'].includes(e.code));
  }
});
test('official account status rejects blocked pending unknown missing; active/default accepted', async () => {
  for (const status of ['BLOCKED','PENDING','unknown',undefined,null]) await assert.rejects(auth(async () => json({ sub: 'allowed', status })).verify('opaque'), e => e.code === 'AUTH_INVALID');
  for (const status of ['ACTIVE','DEFAULT']) assert.equal((await auth(async () => json({ sub: 'allowed', status })).verify('opaque')).uid, 'allowed');
});
test('fetch network timeout, oversized or malformed upstream never leaks raw error', async () => {
  for (const fetchImpl of [async () => { throw new Error('secret'); }, async () => new Response('x'.repeat(65537)), async () => new Response('bad json')]) {
    await assert.rejects(auth(fetchImpl).verify('x'), e => e.code === 'AUTH_UNAVAILABLE' && !e.message.includes('secret'));
  }
});
test('auth origin rejects insecure arbitrary endpoint and credentials', () => {
  assert.equal(typeof authImpl?.createOfficialAuth, 'function');
  for (const origin of ['http://localhost:8787','https://evil.example','https://u:p@env.api.tcloudbasegateway.com','https://env.api.tcloudbasegateway.com/path']) assert.throws(() => authImpl.createOfficialAuth({ origin, adminUids: [] }));
});
test('HTTP enforces envelope method size JSON and no unknown action', async () => {
  assert.equal(typeof httpImpl?.createApi, 'function', 'bounded HTTP adapter must exist');
  const api = httpImpl.createApi({ service: {}, auth: {} });
  for (const [request, status] of [[{ method: 'GET' },405], [{ method: 'POST', body: 'bad' },400], [{ method: 'POST', body: 'x'.repeat(262145) },413], [{ method: 'POST', body: JSON.stringify({ action: '__proto__', payload: {} }) },400]]) {
    const r = await api(request); assert.equal(r.statusCode, status); assert.equal(JSON.parse(r.body).ok, false);
  }
});
test('each management request re-verifies bearer, revoked UID loses access', async () => {
  assert.equal(typeof httpImpl?.createApi, 'function'); let n = 0;
  const api = httpImpl.createApi({ service: { getPendingStories: async (_, who) => { assert.equal(who.isAdmin, true); return { items: [] }; } }, auth: { verify: async () => ({ uid: 'allowed', isAdmin: ++n === 1 }) } });
  const req = { method: 'POST', headers: { authorization: 'Bearer opaque' }, body: JSON.stringify({ action: 'getPendingStories', payload: {} }) };
  assert.equal((await api(req)).statusCode, 200); assert.equal((await api(req)).statusCode, 403); assert.equal(n, 2);
});
test('HTTP never reflects service errors, secrets, or body', async () => {
  assert.equal(typeof httpImpl?.createApi, 'function');
  const api = httpImpl.createApi({ service: { getPublishedStories: async () => { throw new Error('secret private body'); } }, auth: {} });
  const r = await api({ method: 'POST', body: JSON.stringify({ action: 'getPublishedStories', payload: {} }) });
  assert.equal(r.statusCode, 500); assert.doesNotMatch(r.body, /secret|private/); assert.equal(r.headers['Cache-Control'], 'no-store');
});
test('production entry never imports mock, and empty config returns NOT_CONFIGURED', async () => {
  const fs = require('node:fs'); const entry = require.resolve('../index');
  assert.doesNotMatch(fs.readFileSync(entry, 'utf8'), /require\([^)]*mock/);
  const { createProductionHandler } = require('../index'); const handler = createProductionHandler({});
  const r = await handler({ httpMethod: 'POST', body: '{}' }); assert.equal(r.statusCode, 503); assert.equal(JSON.parse(r.body).error.code, 'NOT_CONFIGURED');
});

test('verified official anonymous profile without status can submit but cannot become admin', async () => {
 const guest=auth(async()=>json({sub:'guest',groups:[{id:'anonymous'}]}));
 assert.deepEqual(await guest.verify('opaque-official'),{uid:'guest',isAdmin:false});
 for(const profile of [{sub:'allowed',groups:[{id:'anonymous'}]},{sub:'guest',status:'BLOCKED',groups:[{id:'anonymous'}]},{sub:'guest',groups:[{id:'user'}]}])
  await assert.rejects(auth(async()=>json(profile)).verify('opaque-official'),e=>e.code==='AUTH_INVALID');
});
