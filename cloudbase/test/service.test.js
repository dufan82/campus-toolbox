'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { mkdtemp, rm } = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
let impl;
try { impl = require('../src/service'); } catch (e) { if (e.code !== 'MODULE_NOT_FOUND') throw e; }
async function setup(t, options = {}) {
  assert.equal(typeof impl?.createService, 'function', 'shared story business service must exist');
  const { FileRepository } = require('../mock/repository');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'story-backend-'));
  const repo = await FileRepository.open(path.join(dir, 'db.json'));
  t.after(async () => { await repo.close(); await rm(dir, { recursive: true, force: true }); });
  let now = Date.parse('2026-11-10T00:00:00Z');
  return { repo, file: path.join(dir, 'db.json'), service: impl.createService({ repository: repo, now: () => now, ...options }), advance: ms => { now += ms; } };
}
function payload(extra = {}) { return { submissionId: randomUUID(), title: 'A story', category: 'LIFE', content: 'A private body. '.repeat(50), authorName: 'Writer', consent: true, consentVersion: '2026-11-10-v1', ...extra }; }
const user = { uid: 'user-a' }; const admin = { uid: 'admin-a', isAdmin: true };
const error = (promise, code) => assert.rejects(promise, e => e.code === code);
test('valid submission is pending and server stamps consent', async t => {
  const { service } = await setup(t); const p = payload(); const s = await service.submitStory(p, user);
  assert.equal(s.status, 'pending'); assert.equal(s.duplicate, false);
  const d = await service.getSubmissionDetail({ id: s.id }, admin);
  assert.equal(d.consentAt, '2026-11-10T00:00:00.000Z'); assert.equal(d.published, false);
});
test('missing auth and forged fields cannot submit', async t => {
  const { service } = await setup(t); await error(service.submitStory(payload(), null), 'AUTH_REQUIRED');
  for (const field of ['uid', 'status', 'reviewer', 'createdAt', '__proto__']) {
    const p = JSON.parse(JSON.stringify(payload())); Object.defineProperty(p, field, { value: 'forged', enumerable: true });
    await error(service.submitStory(p, user), 'INVALID_PAYLOAD');
  }
});
test('validation enforces category consent uuid length and types', async t => {
  const { service } = await setup(t);
  for (const patch of [{ category: 'NO' }, { consent: false }, { consentVersion: 'old' }, { submissionId: 'bad' }, { title: '' }, { content: 'x'.repeat(20001) }, { authorName: {} }]) await error(service.submitStory(payload(patch), user), 'INVALID_PAYLOAD');
});
test('strict trim and Unicode codepoint text boundaries; optional anonymous nickname', async t => {
  const { service } = await setup(t); let i = 0;
  for (const patch of [{ title: 'x' }, { title: 'x'.repeat(31) }, { title: '😀'.repeat(31) }, { title: '   ' }, { content: 'a'.repeat(99) }, { content: '😀'.repeat(99) }, { content: 'x'.repeat(5001) }, { content: ' '.repeat(100) }, { content: 'a'.repeat(99) + '  ' }, { authorName: 'x'.repeat(31) }, { title: '<b>title</b>' }, { content: '<script>alert(1)</script>' + 'x'.repeat(100) }, { status: 'approved' }]) {
    await error(service.submitStory(payload(patch), { uid: 'bad-' + i++ }), 'INVALID_PAYLOAD');
  }
  for (const patch of [{ title: ' 😀😀 ', content: ' 😀'.repeat(100) + ' ', authorName: undefined }, { title: '😀'.repeat(30), content: '😀'.repeat(5000), authorName: '😀'.repeat(30) }, { content: 'x'.repeat(100), authorName: '   ' }]) {
    const s = await service.submitStory(payload(patch), { uid: 'good-' + i++ });
    const d = await service.getSubmissionDetail({ id: s.id }, admin); assert.equal(d.title, patch.title ? patch.title.trim() : 'A story');
    assert.equal(d.authorName, patch.authorName?.trim() || '匿名');
  }
});
test('exact replay does not consume rate and matches publication id', async t => {
  const { service, repo } = await setup(t); const p = payload(); const s = await service.submitStory(p, user);
  const again = await service.submitStory(p, user); assert.equal(again.id, s.id); assert.equal(again.duplicate, true);
  const audit = await repo.query('story_audit_log', {}); assert.equal(audit.length, 1);
});
test('submission ID is globally bound to exact content and owner', async t => {
  const { service } = await setup(t); const p = payload(); await service.submitStory(p, user);
  await error(service.submitStory({ ...p, content: p.content + 'changed' }, user), 'SUBMISSION_CONFLICT');
  await error(service.submitStory(p, { uid: 'user-b' }), 'SUBMISSION_CONFLICT');
});
test('parallel exact retries create one submission and one audit', async t => {
  const { service, repo } = await setup(t); const p = payload();
  const rows = await Promise.all(Array.from({ length: 12 }, () => service.submitStory(p, user)));
  assert.equal(rows.filter(x => !x.duplicate).length, 1); assert.equal((await repo.query('story_submissions', {})).length, 1);
  assert.equal((await repo.query('story_audit_log', {})).length, 1);
});
test('parallel new submissions obey rolling minute rate atomically', async t => {
  const { service } = await setup(t); const rows = await Promise.allSettled([service.submitStory(payload(), user), service.submitStory(payload(), user)]);
  assert.equal(rows.filter(x => x.status === 'fulfilled').length, 1);
  assert.equal(rows.find(x => x.status === 'rejected').reason.code, 'RATE_LIMITED');
});
test('user daily quota five resets at UTC day boundary', async t => {
  const { service, advance } = await setup(t);
  for (let i = 0; i < 5; i++) { await service.submitStory(payload(), user); advance(60001); }
  await error(service.submitStory(payload(), user), 'RATE_LIMITED'); advance(86400000);
  assert.equal((await service.submitStory(payload(), user)).status, 'pending');
});
test('global daily quota fifty is independent of UID', async t => {
  const { service, advance } = await setup(t);
  await Promise.all(Array.from({ length: 50 }, (_, i) => service.submitStory(payload(), { uid: 'u-' + i })));
  await error(service.submitStory(payload(), { uid: '51' }), 'RATE_LIMITED'); advance(86400000);
  await service.submitStory(payload(), { uid: '51' });
});
test('approval atomically publishes, changes counts and writes one audit', async t => {
  const { service, repo } = await setup(t); const s = await service.submitStory(payload(), user);
  const a = await service.approveStory({ id: s.id }, admin); assert.match(a.publishedStoryId, /^story-cloud-[a-z0-9-]{1,63}$/);
  assert.equal((await repo.get('published_stories', a.publishedStoryId)).submissionId, s.submissionId, 'published database record must retain its source submissionId');
  assert.deepEqual(await service.approveStory({ id: s.id }, admin), a);
  const list = await service.getPendingStories({ status: 'all' }, admin); assert.deepEqual(list.counts, { pending: 0, approved: 1, rejected: 0 });
  assert.equal((await repo.query('story_audit_log', {})).length, 2);
});
test('every admin operation rejects missing or ordinary identity', async t => {
  const { service } = await setup(t);
  for (const action of ['getPendingStories', 'getSubmissionDetail', 'approveStory', 'rejectStory', 'deletePublishedStory']) {
    await error(service[action]({}, user), 'ADMIN_REQUIRED'); await error(service[action]({}, null), 'AUTH_REQUIRED');
  }
});
test('reject is idempotent and rejected cannot approve', async t => {
  const { service, repo } = await setup(t); const s = await service.submitStory(payload(), user);
  await service.rejectStory({ id: s.id, note: 'not suitable' }, admin); await service.rejectStory({ id: s.id }, admin);
  await error(service.approveStory({ id: s.id }, admin), 'INVALID_STATE');
  assert.equal((await service.getPendingStories({ status: 'rejected' }, admin)).counts.rejected, 1);
  assert.equal((await repo.query('story_audit_log', {})).length, 2);
});
test('public DTO omits all private fields and body in metadata, adds readingMinutes', async t => {
  const { service } = await setup(t); const p = payload(); const s = await service.submitStory(p, user); const a = await service.approveStory({ id: s.id }, admin);
  const list = await service.getPublishedStories({}); assert.equal(list.items[0].readingMinutes, Math.ceil(Array.from(p.content).length / 300));
  assert.deepEqual(Object.keys(list.items[0]).sort(), ['id','title','category','summary','authorName','publishedAt','sourceType','readingMinutes'].sort());
  const detail = await service.getStoryDetail({ id: a.publishedStoryId });
  assert.deepEqual(Object.keys(detail).sort(), ['id','title','category','content','authorName','publishedAt','sourceType'].sort());
});
test('pending is never public and withdrawn detail uses fixed 404 code', async t => {
  const { service } = await setup(t); const s = await service.submitStory(payload(), user);
  await error(service.getStoryDetail({ id: 'story-cloud-' + s.submissionId }), 'STORY_NOT_FOUND');
  const a = await service.approveStory({ id: s.id }, admin); await service.deletePublishedStory({ id: s.id, note: 'withdrawn' }, admin);
  await assert.rejects(service.getStoryDetail({ id: a.publishedStoryId }), e => e.code === 'STORY_NOT_FOUND' && e.status === 404);
  assert.equal((await service.getPublishedStories({})).items.length, 0);
  await error(service.approveStory({ id: s.id }, admin), 'INVALID_STATE');
  await service.deletePublishedStory({ id: s.id }, admin);
  assert.equal((await service.getSubmissionDetail({ id: s.id }, admin)).status, 'approved');
});
test('reject approved, withdraw pending, oversized note all fail', async t => {
  const { service } = await setup(t); const s = await service.submitStory(payload(), user);
  await error(service.deletePublishedStory({ id: s.id }, admin), 'INVALID_STATE');
  await error(service.rejectStory({ id: s.id, note: 'x'.repeat(501) }, admin), 'INVALID_PAYLOAD');
  await service.approveStory({ id: s.id }, admin); await error(service.rejectStory({ id: s.id }, admin), 'INVALID_STATE');
});
test('stable cursor paginates tied timestamps and binds filter', async t => {
  const { service } = await setup(t);
  for (let i = 0; i < 5; i++) { const s = await service.submitStory(payload(), { uid: 'u' + i }); await service.approveStory({ id: s.id }, admin); }
  const ids = []; let cursor; do { const r = await service.getPublishedStories({ limit: 2, ...(cursor ? { cursor } : {}) }); ids.push(...r.items.map(i => i.id)); cursor = r.nextCursor; } while (cursor);
  assert.equal(new Set(ids).size, 5);
  const r = await service.getPublishedStories({ limit: 2 }); await error(service.getPublishedStories({ cursor: r.nextCursor, category: 'LIFE' }), 'INVALID_CURSOR');
  await error(service.getPublishedStories({ cursor: 'bad' }), 'INVALID_CURSOR');
});
test('transaction failure rolls back publication counters and audit', async t => {
  const { service, repo } = await setup(t); const s = await service.submitStory(payload(), user);
  const original = repo.transaction.bind(repo);
  repo.transaction = fn => original(async tx => { await fn(tx); throw new Error('injected commit failure'); });
  await assert.rejects(service.approveStory({ id: s.id }, admin), /injected/); repo.transaction = original;
  assert.equal((await service.getPublishedStories({})).items.length, 0);
  assert.equal((await service.getSubmissionDetail({ id: s.id }, admin)).status, 'pending');
  assert.equal((await repo.query('story_audit_log', {})).length, 1);
});
test('parallel approve vs reject yields exactly one final transition', async t => {
  const { service, repo } = await setup(t); const s = await service.submitStory(payload(), user);
  const r = await Promise.allSettled([service.approveStory({ id: s.id }, admin), service.rejectStory({ id: s.id }, admin)]);
  assert.equal(r.filter(x => x.status === 'fulfilled').length, 1); assert.equal((await repo.query('story_audit_log', {})).length, 2);
});
test('file-backed committed data survives reopening; concurrent writer refused', async t => {
  const { service, repo, file } = await setup(t); const { FileRepository } = require('../mock/repository');
  await service.submitStory(payload(), user); await assert.rejects(FileRepository.open(file), /locked/i);
  await repo.close(); const reopened = await FileRepository.open(file);
  try { assert.equal((await reopened.query('story_submissions', {})).length, 1); } finally { await reopened.close(); }
});
module.exports = { payload };
