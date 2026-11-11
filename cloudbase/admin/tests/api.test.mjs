import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient, resolveEndpoint, ApiError } from '../api.js';

const location = (url) => new URL(url);
const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { 'Content-Type': 'application/json' }
});
const ok = (data) => json({ ok: true, data });
const auth = { accessToken: 'synthetic-memory-token', expiresIn: 3600 };
const production = { url: 'https://admin.test/api', isMock: false };

test('empty production config fails closed; only exact loopback auto-selects /api', () => {
  assert.throws(() => resolveEndpoint({}, location('https://admin.test/admin/')), /CONFIG/);
  for (const host of ['localhost', '127.0.0.1', '[::1]']) {
    assert.deepEqual(resolveEndpoint({ apiUrl: '' }, location(`http://${host}:8787/admin/`)), {
      url: '/api', isMock: true
    });
  }
  for (const host of ['localhost.evil.test', '0.0.0.0', '192.168.1.2']) {
    assert.throws(() => resolveEndpoint({}, location(`http://${host}/admin/`)), /CONFIG/);
  }
});

test('production endpoint must be fixed HTTPS, without credentials/query/hash or loopback', () => {
  for (const apiUrl of ['/api', 'http://admin.test/api', 'https://user:secret@admin.test/api',
    'https://admin.test/api?token=x', 'https://admin.test/api#x', 'https://localhost/api']) {
    assert.throws(() => resolveEndpoint({ apiUrl }, location('https://admin.test/admin/')), /CONFIG/);
  }
  assert.deepEqual(resolveEndpoint({ apiUrl: production.url }, location('https://admin.test/')), production);
  assert.throws(() => resolveEndpoint({}, location('file:///admin/index.html')), /CONFIG/);
});

test('login and every admin call use contract envelope, no UID and no cookie credentials', async () => {
  const requests = [];
  const client = createApiClient({ endpoint: production, fetchImpl: async (url, options) => {
    requests.push({ url, ...options });
    return ok(requests.length === 1 ? auth : { items: [], counts: {}, nextCursor: null });
  }});
  await client.login('test-admin', 'test-only-password');
  await client.call('getPendingStories', { status: 'pending', limit: 20 });
  await client.call('getSubmissionDetail', { id: 's-1' });
  assert.deepEqual(JSON.parse(requests[0].body), {
    action: 'adminLogin', payload: { username: 'test-admin', password: 'test-only-password' }
  });
  assert.equal(requests[0].headers.Authorization, undefined);
  for (const request of requests.slice(1)) {
    assert.equal(request.headers.Authorization, `Bearer ${auth.accessToken}`);
    assert.equal(request.credentials, 'omit');
    assert.equal(request.cache, 'no-store');
    assert.equal(request.redirect, 'error');
    assert.equal(JSON.parse(request.body).payload.uid, undefined);
  }
  assert.equal(client.accessToken, undefined);
});

test('no session and unknown actions never send a request', async () => {
  let calls = 0;
  const client = createApiClient({ endpoint: production, fetchImpl: async () => { calls++; return ok(auth); }});
  await assert.rejects(client.call('approveStory', { id: 's-1' }), { code: 'UNAUTHENTICATED' });
  await assert.rejects(client.call('submitStory', {}), { code: 'INVALID_ACTION' });
  assert.equal(calls, 0);
});

for (const status of [401, 403]) {
  test(`${status} clears memory session and notifies login view`, async () => {
    let calls = 0, reset = 0;
    const client = createApiClient({ endpoint: production, onUnauthorized: () => reset++,
      fetchImpl: async () => ++calls === 1 ? ok(auth) : json({ ok: false, error: { code: 'DENIED', message: 'not rendered' } }, status)
    });
    await client.login('test', 'test');
    await assert.rejects(client.call('getSubmissionDetail', { id: 's-1' }), (e) => e.status === status);
    assert.equal(client.hasSession(), false);
    assert.equal(reset, 1);
  });
}

test('clearSession prevents an in-flight login from restoring a token', async () => {
  let complete;
  const client = createApiClient({ endpoint: production, fetchImpl: () => new Promise((r) => { complete = r; }) });
  const pending = client.login('test', 'test');
  client.clearSession();
  complete(ok(auth));
  await assert.rejects(pending, { code: 'CANCELLED' });
  assert.equal(client.hasSession(), false);
});

test('expiry is checked before requests; login cannot accept malformed credentials', async () => {
  let now = 0, calls = 0;
  const client = createApiClient({ endpoint: production, now: () => now,
    fetchImpl: async () => { calls++; return ok({ ...auth, expiresIn: 1 }); }
  });
  await client.login('test', 'test');
  now = 1001;
  await assert.rejects(client.call('approveStory', { id: 's-1' }), { code: 'UNAUTHENTICATED' });
  assert.equal(calls, 1);
  const invalid = createApiClient({ endpoint: production, fetchImpl: async () => ok({ accessToken: 'bad\nheader', expiresIn: -1 }) });
  await assert.rejects(invalid.login('test', 'test'), { code: 'INVALID_RESPONSE' });
  assert.equal(invalid.hasSession(), false);
});

test('response envelope and 256KiB cap are enforced; server text is never exposed', async () => {
  for (const response of [json({ items: [] }), json({ ok: true }), json({ ok: true, data: 'wrong' }),
    new Response('not json'), ok({ text: 'x'.repeat(262145) })]) {
    const client = createApiClient({ endpoint: production, fetchImpl: async () => response });
    await assert.rejects(client.login('test', 'test'), (e) => e instanceof ApiError && !e.message.includes('not json'));
  }
  const client = createApiClient({ endpoint: production, fetchImpl: async () => json({ ok: false, error: {
    code: 'BAD_LOGIN', message: 'credential or HTML must not appear'
  } }, 400) });
  await assert.rejects(client.login('test', 'test'), (e) => !e.message.includes('credential'));
});

test('timeout is bounded and mutation is never automatically retried', async () => {
  let calls = 0;
  const client = createApiClient({ endpoint: production, timeoutMs: 8, fetchImpl: async (_url, options) => {
    if (++calls === 1) return ok(auth);
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))));
  }});
  await client.login('test', 'test');
  await assert.rejects(client.call('approveStory', { id: 's-1' }), { code: 'TIMEOUT' });
  assert.equal(calls, 2);
});

test('local real-cloud review requires explicit opt-in and exact loopback, never weakens cloud HTTPS',()=>{
 const config={apiUrl:'https://api.test/api',localCloud:true};
 assert.equal(resolveEndpoint(config,location('http://127.0.0.1:8791/')).isMock,false);
 assert.equal(resolveEndpoint(config,location('http://127.0.0.1:8791/')).isLocalCloud,true);
 for(const page of ['http://192.168.1.2:8791/','http://localhost.evil.test/'])assert.throws(()=>resolveEndpoint(config,location(page)),/CONFIG/);
 assert.throws(()=>resolveEndpoint({apiUrl:config.apiUrl},location('http://127.0.0.1:8791/')),/CONFIG/);
 assert.throws(()=>resolveEndpoint({...config,apiUrl:'http://api.test/api'},location('http://127.0.0.1:8791/')),/CONFIG/);
});
