'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { plan, initializeCounters, createCollections, main } = require('../scripts/init-db');
test('initialization defaults to offline plan with missing real environment explicitly listed', () => {
  const p = plan({}); assert.equal(p.configured, false); assert.equal(p.mode, 'plan-only-no-network'); assert.equal(p.missing.length, 5);
  assert.deepEqual(p.ruleForEveryCollection, { read: false, write: false }); assert.equal(p.collections.length, 5);
});
test('unconfigured or unconfirmed initialization refuses before any database calls', async () => {
  await assert.rejects(main(['--initialize-counters'], {}), e => e.code === 'NOT_CONFIGURED');
  await assert.rejects(main(['--initialize-counters'], { TCB_ENV_ID: 'test', TCB_REGION: 'ap-shanghai', TCB_AUTH_ORIGIN: 'https://test.api.tcloudbasegateway.com' }), /confirm-env/);
});
test('existing counter is retained, never reset', async () => {
  const db = { collection: () => ({ doc: () => ({ get: async () => ({ data: [{ pending: 2, approved: 3, rejected: 4 }] }) }) }) };
  assert.deepEqual(await initializeCounters(db), { created: false });
});
test('missing counters with existing data fail closed rather than zeroing counts', async () => {
  const db = { collection: () => ({ doc: () => ({ get: async () => ({ data: [] }) }), limit: () => ({ get: async () => ({ data: [{ id: 'existing' }] }) }) }) };
  await assert.rejects(initializeCounters(db), /Existing business data/);
});
test('collection initialization accepts only exact official already-exists error', async () => {
  let n = 0;
  await createCollections({ createCollection: async () => { n++; return { code: 'DATABASE_COLLECTION_ALREADY_EXIST' }; } }); assert.equal(n, 5);
  await assert.rejects(createCollections({ createCollection: async () => ({ code: 'DENIED' }) }));
  await assert.rejects(createCollections({ createCollection: async () => { throw Object.assign(new Error('no'), { code: 'OTHER' }); } }));
});
test('production staging contains no fixture/test/admin/data or real env values', async () => {
  const { stage } = require('../scripts/stage-production');
  const r = await stage(); assert.ok(r.files.length >= 10);
  assert.equal(r.files.some(row => /mock|test|admin|data\/|\.env/.test(row.file)), false);
  for (const row of r.files) assert.match(row.sha256, /^[a-f0-9]{64}$/);
});
