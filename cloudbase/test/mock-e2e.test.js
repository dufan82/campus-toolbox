'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { mkdtemp, rm } = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { startMock } = require('../mock/server');
test('loopback HTTP full submit approve public withdraw reject persistent workflow', async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'story-http-'));
  const file = path.join(dir, 'db.json'); let app = await startMock({ file, port: 0 });
  t.after(async () => { await app.close(); await rm(dir, { recursive: true, force: true }); });
  assert.equal(app.server.address().address, '127.0.0.1');
  const call = async (action, payload = {}, token) => {
    const response = await fetch(app.url + '/api', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify({ action, payload }) });
    return { status: response.status, ...await response.json() };
  };
  const device = randomUUID();
  const anon = await (await fetch(app.url + '/auth/v1/signin/anonymously', { method: 'POST', headers: { 'x-device-id': device }, body: '{}' })).json();
  assert.match(anon.access_token, /^mock_/); assert.equal(typeof anon.sub, 'string');
  const login = await call('adminLogin', { username: 'admin', password: 'mock-admin-password' }); assert.equal(login.ok, true);
  const token = login.data.accessToken;
  const p = { submissionId: randomUUID(), title: 'HTTP story', content: 'Content '.repeat(20).trim(), authorName: 'Anonymous', category: 'OTHER', consent: true, consentVersion: '2026-11-10-v1' };
  const s = await call('submitStory', p, anon.access_token); assert.equal(s.data.status, 'pending');
  assert.equal((await call('getPendingStories', { status: 'all' }, anon.access_token)).status, 403);
  const a = await call('approveStory', { id: s.data.id }, token); assert.equal(a.data.status, 'approved');
  assert.equal((await call('getPublishedStories')).data.items[0].id, a.data.publishedStoryId);
  assert.equal((await call('getStoryDetail', { id: a.data.publishedStoryId })).data.content, p.content);
  await call('deletePublishedStory', { id: s.data.id }, token);
  const gone = await call('getStoryDetail', { id: a.data.publishedStoryId }); assert.equal(gone.status, 404); assert.equal(gone.error.code, 'STORY_NOT_FOUND');
  const secondAnon = await (await fetch(app.url + '/auth/v1/signin/anonymously', { method: 'POST', headers: { 'x-device-id': randomUUID() }, body: '{}' })).json();
  const rejected = await call('submitStory', { ...p, submissionId: randomUUID() }, secondAnon.access_token);
  assert.equal((await call('rejectStory', { id: rejected.data.id, note: 'not selected' }, token)).data.status, 'rejected');
  assert.equal((await call('approveStory', { id: rejected.data.id }, token)).status, 409);
  await app.close(); app = await startMock({ file, port: 0 });
  const again = await call('adminLogin', { username: 'admin', password: 'mock-admin-password' });
  const list = await call('getPendingStories', { status: 'all' }, again.data.accessToken);
  assert.deepEqual(list.data.counts, { pending: 0, approved: 1, rejected: 1 }); assert.equal(list.data.items.length, 2);
  assert.equal((await call('getPendingStories', {}, token)).status, 401);
  assert.equal((await fetch(app.url + '/api/reset')).status, 404);
  assert.equal((await fetch(app.url + '/CONTRACT.md')).status, 404);
  assert.equal((await fetch(app.url + '/api', { method: 'POST', headers: { origin: 'https://evil.example' }, body: '{}' })).status, 403);
});
test('HTTP adminLogin global limit survives server restart and resets minute without reset endpoint', async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'story-login-http-')); const file = path.join(dir, 'db.json');
  let time = Date.parse('2026-11-10T00:00:00Z'); let app = await startMock({ file, port: 0, now: () => time });
  t.after(async () => { await app.close(); await rm(dir, { recursive: true, force: true }); });
  async function login() { return fetch(app.url + '/api', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'adminLogin', payload: { username: 'admin', password: 'mock-admin-password' } }) }); }
  for (let i = 0; i < 10; i++) { const r = await login(); assert.equal(r.status, 200); await r.arrayBuffer(); }
  const limited = await login(); assert.equal(limited.status, 429); assert.equal((await limited.json()).error.code, 'RATE_LIMITED');
  await app.close(); app = await startMock({ file, port: 0, now: () => time });
  const afterRestart = await login(); assert.equal(afterRestart.status, 429); await afterRestart.arrayBuffer();
  time += 60001; const freshWindow = await login(); assert.equal(freshWindow.status, 200); await freshWindow.arrayBuffer();
  const counter = await app.repository.get('story_rate_limits', 'admin-login-global');
  assert.equal(counter.count, 1); assert.equal(counter.dayCount, 11);
  assert.deepEqual(Object.keys(counter).sort(), ['windowStart','count','day','dayCount'].sort());
});
