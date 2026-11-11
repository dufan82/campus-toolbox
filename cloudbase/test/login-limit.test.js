'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mkdtemp, rm } = require('node:fs/promises');
const os = require('node:os'); const path = require('node:path');
const { FileRepository } = require('../mock/repository');
let createLoginLimiter; try { ({ createLoginLimiter } = require('../src/login-limit')); } catch (e) { if (e.code !== 'MODULE_NOT_FOUND') throw e; }
test('two independent API instance limiters share atomic persistent global login budget', async t => {
  assert.equal(typeof createLoginLimiter, 'function', 'persistent login limiter must exist');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'login-limit-')); const repo = await FileRepository.open(path.join(dir, 'db.json'));
  t.after(async () => { await repo.close(); await rm(dir, { recursive: true, force: true }); });
  let time = 100000;
  const first = createLoginLimiter(repo, () => time), second = createLoginLimiter(repo, () => time);
  const result = await Promise.allSettled(Array.from({ length: 12 }, (_, i) => (i % 2 ? first : second)()));
  assert.equal(result.filter(r => r.status === 'fulfilled').length, 10);
  assert.equal(result.filter(r => r.status === 'rejected' && r.reason.code === 'RATE_LIMITED').length, 2);
  time += 60000; await first();
  const row = await repo.get('story_rate_limits', 'admin-login-global'); assert.equal(row.count, 1); assert.equal(row.dayCount, 11);
});
