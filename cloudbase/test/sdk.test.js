'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { CloudBaseRepository } = require('../src/cloudbase-repository');
test('pinned SDK transaction document shape is object/null, ordinary get is array', async () => {
  const ordinary = { collection: () => ({ doc: () => ({ get: async () => ({ data: [{ _id: 'x', id: 'x', value: 1 }] }) }) }) };
  const tx = { collection: () => ({ doc: id => ({ get: async () => ({ data: id === 'missing' ? null : { _id: 'x', id: 'x', value: 1 } }), set: async data => { assert.equal(data._id, undefined); return { updated: 1 }; }, remove: async () => ({ deleted: 1 }) }) }) };
  ordinary.runTransaction = fn => fn(tx);
  const repo = new CloudBaseRepository(ordinary);
  assert.equal((await repo.get('collection', 'x')).value, 1);
  await repo.transaction(async t => { assert.equal((await t.get('collection', 'x')).value, 1); assert.equal(await t.get('collection', 'missing'), null); await t.set('collection', 'x', { value: 2 }); await t.remove('collection', 'x'); });
});
test('SDK failures must never be interpreted as empty documents or successful writes', async () => {
  const tx = { collection: () => ({ doc: () => ({ get: async () => ({ code: 'DATABASE_REQUEST_FAILED' }), set: async () => ({ code: 'DATABASE_REQUEST_FAILED' }) }) }) };
  const repo = new CloudBaseRepository({ ...tx, runTransaction: fn => fn(tx) });
  await assert.rejects(repo.get('x', 'y'));
  await assert.rejects(repo.transaction(t => t.set('x', 'y', {})));
});
