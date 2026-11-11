'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { mkdtemp, rm } = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { FileRepository } = require('../mock/repository');
const { createService } = require('../src/service');
const admin = { uid: 'fixture-admin', isAdmin: true }, user = { uid: 'fixture-user' };
const fixture = (patch = {}) => ({ editorialId: randomUUID(), title: '随记精选', category: 'OTHER', content: '仅管理员完整草稿'.repeat(50), summary: '管理员撰写的独立介绍', authorName: '评论作者', publisherName: '作品发布者', sourceType: 'douyin_comment', sourceUrl: 'https://www.douyin.com/video/123456789', sourceWorkTitle: '原作品标题', commentLocator: '置顶评论，第一段', rightsNote: '内部凭据说明', excerpt: '短摘录', ...patch });
async function setup(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'suiji-test-'));
  const repo = await FileRepository.open(path.join(dir, 'db.json'));
  const service = createService({ repository: repo, now: () => Date.parse('2026-11-11T00:00:00.000Z') });
  t.after(async () => { await repo.close(); await rm(dir, { recursive: true, force: true }); });
  const create = async patch => service.createEditorialStory(fixture(patch), admin);
  const edit = async (id, patch, extra = {}) => {
    const s = await service.getSubmissionDetail({ id }, admin);
    return service.updateManagedStory({ id, revision: s.revision, patch, ...extra }, admin);
  };
  return { repo, service, create, edit };
}
const rejects = (p, code) => assert.rejects(p, e => e.code === code);
test('admin editorial create and edit allow title120/body20000 codepoints, reject boundary+1', async t => {
  const { service, create, edit } = await setup(t);
  for (const unit of ['文','😀']) {
    const title = unit.repeat(120), content = unit.repeat(20000);
    const s = await create({ title, content, rightsStatus: 'authorized' });
    let d = await service.getSubmissionDetail({ id: s.id }, admin);
    assert.equal(d.title, title); assert.equal(d.content, content);
    for (const patch of [{ title: title + unit }, { content: content + unit }]) {
      await rejects(create(patch), 'INVALID_PAYLOAD');
      await rejects(edit(s.id, patch), 'INVALID_PAYLOAD');
    }
    await edit(s.id, { title: '改' + unit.repeat(119), content: '改' + unit.repeat(19999), rightsStatus: 'authorized' });
    const a = await service.approveStory({ id: s.id }, admin);
    d = await service.getStoryDetail({ id: a.publishedStoryId });
    assert.equal(d.title, '改' + unit.repeat(119)); assert.equal(d.content, '改' + unit.repeat(19999));
  }
});
test('ordinary students remain title30/body5000 even after admin limits expand', async t => {
  const { service } = await setup(t);
  for (const [i, unit] of ['文','😀'].entries()) {
    const p = { submissionId: randomUUID(), title: unit.repeat(30), category: 'LIFE', content: unit.repeat(5000), consent: true, consentVersion: '2026-11-10-v1' };
    const who = { uid: 'student-boundary-' + i };
    const s = await service.submitStory(p, who); assert.equal(s.status, 'pending');
    const d = await service.getSubmissionDetail({ id: s.id }, admin);
    assert.equal(d.title, p.title); assert.equal(d.content, p.content);
    for (const patch of [{ title: p.title + unit }, { content: p.content + unit }]) {
      await rejects(service.submitStory({ ...p, ...patch, submissionId: randomUUID() }, who), 'INVALID_PAYLOAD');
    }
  }
});
test('editorial draft is admin-only, exact create replay is idempotent, metadata stays separate', async t => {
  const { service, repo } = await setup(t); const p = fixture();
  await rejects(service.createEditorialStory(p, user), 'ADMIN_REQUIRED');
  const s = await service.createEditorialStory(p, admin);
  assert.equal((await service.createEditorialStory(p, admin)).id, s.id);
  await rejects(service.createEditorialStory({ ...p, content: 'different' }, admin), 'SUBMISSION_CONFLICT');
  await rejects(service.getSubmissionDetail({ id: s.id }, user), 'ADMIN_REQUIRED');
  const d = await service.getSubmissionDetail({ id: s.id }, admin);
  assert.equal(d.recordType, 'editorial'); assert.equal(d.rightsStatus, 'unverified');
  assert.equal(d.content, p.content); assert.equal(d.publisherName, p.publisherName); assert.notEqual(d.publisherName, d.authorName);
  assert.equal((await service.getPublishedStories({})).items.length, 0);
  assert.equal((await repo.query('story_audit_log')).length, 1);
});
test('all rights states gate full text, edits revoke previously public body, privacy allowlist', async t => {
  const { service, create, edit } = await setup(t);
  const s = await create(); const a = await service.approveStory({ id: s.id }, admin);
  assert.match(a.publishedStoryId, /^story-cloud-[a-z0-9-]{1,63}$/);
  for (const rightsStatus of ['authorized','public_domain','user_authorized','unverified','excerpt_only','revoked']) {
    await edit(s.id, { rightsStatus }, { confirmPublic: true });
    const d = await service.getStoryDetail({ id: a.publishedStoryId });
    assert.equal(d.content, ['authorized','public_domain','user_authorized'].includes(rightsStatus) ? fixture().content : '');
    assert.equal(d.rightsStatus, rightsStatus); assert.equal(d.summary, fixture().summary);
    for (const key of ['rightsNote','reviewerNote','reviewerUid','ownerUid','fingerprint','consentAt','submissionId','recordType']) assert.equal(d[key], undefined);
    const row = (await service.getPublishedStories({})).items[0]; assert.equal(row.content, ''); assert.equal(row.rightsNote, undefined);
  }
  await rejects(edit(s.id, { rightsStatus: 'authorized' }), 'PUBLIC_CONFIRMATION_REQUIRED');
  const d = await service.getSubmissionDetail({ id: s.id }, admin);
  await rejects(service.updateManagedStory({ id: s.id, revision: d.revision - 1, patch: { hidden: true } }, admin), 'REVISION_CONFLICT');
  assert.equal((await service.getSubmissionDetail({ id: s.id }, admin)).rightsStatus, 'revoked');
});
test('rank keyset pagination mixes old submissions and editorial, invalidates stale cursors', async t => {
  const { service, repo, create, edit } = await setup(t);
  const legacyId = 'story-cloud-' + randomUUID();
  await repo.transaction(tx => tx.set('published_stories', legacyId, { id: legacyId, title: 'legacy', category: 'OTHER', authorName: '旧作者', content: '旧投稿全文', summary: '旧摘要', sourceType: 'submission', publishedAt: '2026-11-10T00:00:00.000Z', sortKey: '2026-11-10T00:00:00.000Z|' + legacyId }));
  const ids = [];
  for (const editorialRank of [3,-2,0,-2]) { const s = await create({ editorialRank }); const a = await service.approveStory({ id: s.id }, admin); ids.push({ ...s, publicId: a.publishedStoryId, editorialRank }); }
  let cursor, all = [];
  do { const p = await service.getPublishedStories({ limit: 1, ...(cursor ? { cursor } : {}) }); all.push(...p.items); cursor = p.nextCursor; } while (cursor);
  assert.deepEqual(all.map(s => s.editorialRank), [-2,-2,0,0,3]); assert.equal(new Set(all.map(s => s.id)).size, 5);
  assert.equal((await service.getStoryDetail({ id: legacyId })).rightsStatus, 'user_authorized');
  assert.equal((await service.getStoryDetail({ id: legacyId })).content, '旧投稿全文');
  const first = await service.getPublishedStories({ limit: 1 });
  await edit(ids[0].id, { editorialRank: -9 }, { confirmPublic: true });
  await rejects(service.getPublishedStories({ cursor: first.nextCursor }), 'INVALID_CURSOR');
  await edit(ids[0].id, { hidden: true });
  await rejects(service.getStoryDetail({ id: ids[0].publicId }), 'STORY_NOT_FOUND');
  assert.equal((await service.getPublishedStories({})).items.length, 4);
  await edit(ids[0].id, { hidden: false }, { confirmPublic: true });
  await service.deletePublishedStory({ id: ids[0].id }, admin);
  await rejects(edit(ids[0].id, { hidden: false }, { confirmPublic: true }), 'INVALID_STATE');
  await rejects(service.getStoryDetail({ id: ids[0].publicId }), 'STORY_NOT_FOUND');
});
test('strict source, rights, Unicode excerpt and privilege validation', async t => {
  const { service, create } = await setup(t);
  for (const patch of [{ sourceUrl: 'http://www.douyin.com/x' }, { sourceUrl: 'https://localhost/x' }, { sourceUrl: 'https://127.0.0.1/x' }, { sourceUrl: 'https://u:p@www.douyin.com/x' }, { sourceUrl: '分享文字 https://www.douyin.com/x' }, { excerpt: '😀'.repeat(81) }, { editorialRank: 1.5 }, { hidden: 'false' }, { rightsStatus: 'yes' }, { sourceType: 'submission' }, { status: 'approved' }]) await rejects(create(patch), 'INVALID_PAYLOAD');
  await create({ excerpt: '😀'.repeat(80) });
  for (const key of ['sourceType','rightsStatus','editorialRank','hidden','recordType','rightsNote']) {
    await rejects(service.submitStory({ submissionId: randomUUID(), title: '投稿', category: 'LIFE', content: '正文'.repeat(100), consent: true, consentVersion: '2026-11-10-v1', [key]: 'forged' }, user), 'INVALID_PAYLOAD');
  }
  for (const action of ['createEditorialStory','updateManagedStory']) await rejects(service[action]({}, null), 'AUTH_REQUIRED');
  await rejects(service.updateManagedStory({}, user), 'ADMIN_REQUIRED');
});
test('editorial edits and publication roll back with audit; revision prevents lost updates', async t => {
  const { repo, service, create, edit } = await setup(t); const s = await create(); await service.approveStory({ id: s.id }, admin);
  const before = structuredClone(repo.state), original = repo.transaction.bind(repo);
  repo.transaction = fn => original(async tx => { await fn(tx); throw new Error('injected commit failure'); });
  await assert.rejects(edit(s.id, { rightsStatus: 'authorized' }, { confirmPublic: true }), /injected/);
  repo.transaction = original; assert.deepEqual(repo.state, before);
  const d = await service.getSubmissionDetail({ id: s.id }, admin);
  const outcomes = await Promise.allSettled([1,2].map(editorialRank => service.updateManagedStory({ id: s.id, revision: d.revision, patch: { editorialRank }, confirmPublic: true }, admin)));
  assert.equal(outcomes.filter(s => s.status === 'fulfilled').length, 1);
  assert.equal(outcomes.find(s => s.status === 'rejected').reason.code, 'REVISION_CONFLICT');
});
test('changing authorized body without reasserting rights never inherits full-text grant', async t => {
  const { create, service, edit, repo } = await setup(t);
  const s = await create({ rightsStatus: 'authorized' }); const a = await service.approveStory({ id: s.id }, admin);
  await edit(s.id, { content: '新的未授权完整正文'.repeat(30) }, { confirmPublic: true });
  const d = await service.getStoryDetail({ id: a.publishedStoryId }); assert.equal(d.rightsStatus, 'unverified'); assert.equal(d.content, '');
  assert.equal((await repo.get('published_stories', a.publishedStoryId)).content, '', 'public collection must not retain private drafts after revocation');
  assert.ok((await service.getSubmissionDetail({ id: s.id }, admin)).content.includes('新的未授权'));
});
test('approval can bind the exact reviewed revision without breaking legacy id-only approval', async t => {
  const { service, create, edit } = await setup(t); const s = await create();
  await edit(s.id, { summary: '更新后的独立介绍' });
  await rejects(service.approveStory({ id: s.id, revision: 0 }, admin), 'REVISION_CONFLICT');
  assert.equal((await service.getPublishedStories({})).items.length, 0);
  await service.approveStory({ id: s.id, revision: 1 }, admin);
});
test('defensive public DTO gates stale rows, hidden rows do not truncate pagination', async t => {
  const { repo, service } = await setup(t); const rows = [];
  for (let i = 0; i < 7; i++) {
    const id = 'story-cloud-fixture-' + i;
    rows.push({ id, title: '离线记录', category: 'OTHER', sourceType: 'other_platform', sourceUrl: fixture().sourceUrl,
      content: '不该外泄的旧公开草稿', rightsNote: '私密', ownerUid: 'private', hidden: i > 2,
      publishedAt: '2026-11-11T00:00:00.000Z', sortKey: '2026-11-11T00:00:00.000Z|' + id });
  }
  await repo.transaction(async tx => { for (const row of rows) await tx.set('published_stories', row.id, row); });
  let p = await service.getPublishedStories({ limit: 2 }); assert.equal(p.items.length, 2);
  assert.ok(p.items.every(s => s.content === '' && s.rightsStatus === 'unverified' && !s.rightsNote));
  p = await service.getPublishedStories({ limit: 2, cursor: p.nextCursor }); assert.equal(p.items.length, 1); assert.equal(p.nextCursor, null);
  await rejects(service.getStoryDetail({ id: rows[6].id }), 'STORY_NOT_FOUND');
  assert.equal((await service.getStoryDetail({ id: rows[0].id })).content, '');
});
test('editorial HTTP server enforces admin identity, persists edits and never exposes private metadata', async t => {
  const { startMock } = require('../mock/server');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'suiji-http-')); const file = path.join(dir, 'db.json');
  let app = await startMock({ file, port: 0 });
  t.after(async () => { await app.close(); await rm(dir, { recursive: true, force: true }); });
  const call = async (action, payload = {}, token) => {
    const response = await fetch(app.url + '/api', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify({ action, payload }) });
    return { status: response.status, ...await response.json() };
  };
  const anon = await (await fetch(app.url + '/auth/v1/signin/anonymously', { method: 'POST', headers: { 'x-device-id': randomUUID() }, body: '{}' })).json();
  let token = (await call('adminLogin', { username: 'admin', password: 'mock-admin-password' })).data.accessToken;
  for (const action of ['createEditorialStory','updateManagedStory']) {
    assert.equal((await call(action)).status, 401); assert.equal((await call(action, {}, anon.access_token)).status, 403);
    assert.equal((await call(action, {}, 'forged')).status, 401);
  }
  const p = fixture({ rightsStatus: 'authorized' }); const s = await call('createEditorialStory', p, token);
  const a = await call('approveStory', { id: s.data.id }, token); const publicId = a.data.publishedStoryId;
  let d = (await call('getSubmissionDetail', { id: s.data.id }, token)).data;
  assert.equal((await call('updateManagedStory', { id: d.id, revision: d.revision, patch: { rightsStatus: 'revoked' }, confirmPublic: true }, anon.access_token)).status, 403);
  await call('updateManagedStory', { id: d.id, revision: d.revision, patch: { rightsStatus: 'revoked' }, confirmPublic: true }, token);
  await app.close(); app = await startMock({ file, port: 0 });
  token = (await call('adminLogin', { username: 'admin', password: 'mock-admin-password' })).data.accessToken;
  assert.equal((await call('getStoryDetail', { id: publicId })).data.content, '');
  d = (await call('getSubmissionDetail', { id: s.data.id }, token)).data;
  assert.equal(d.content, p.content); assert.equal(d.rightsNote, p.rightsNote);
  assert.equal((await app.repository.query('story_audit_log')).length, 3);
});
