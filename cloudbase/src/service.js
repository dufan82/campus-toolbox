'use strict';
const { createHash } = require('node:crypto');
const { fail } = require('./errors');
const v = require('./validation');
const S = 'story_submissions', P = 'published_stories', R = 'story_rate_limits', A = 'story_audit_log', C = 'story_stats';
const hash = text => createHash('sha256').update(text).digest('hex');
const pick = (obj, keys) => Object.fromEntries(keys.filter(k => obj[k] !== undefined).map(k => [k, obj[k]]));
const DETAIL = ['id','title','category','content','authorName','publishedAt','sourceType'];
const ADMIN_DETAIL = ['id','title','category','content','authorName','status','createdAt','updatedAt','consentAt','consentVersion','reviewerNote','reviewedAt','published','publishedStoryId'];
function identity(who, admin = false) {
  if (!who || typeof who.uid !== 'string' || !who.uid) fail(401, 'AUTH_REQUIRED', '请先登录');
  if (admin && who.isAdmin !== true) fail(403, 'ADMIN_REQUIRED', '需要管理员权限');
}
function createService({ repository: db, now = Date.now }) {
  async function counters(tx) { const c = await tx.get(C, 'counts'); if (!c) fail(503, 'NOT_INITIALIZED', '数据库计数未初始化'); return c; }
  async function required(tx, id) { const s = await tx.get(S, id); if (!s) fail(404, 'SUBMISSION_NOT_FOUND', '投稿不存在'); return s; }
  const audit = (tx, s, action, uid, at) => tx.set(A, `${s.id}-${action}`, { submissionId: s.id, action, actorUid: uid, at });
  async function submitStory(payload, who) {
    identity(who); const p = v.submission(payload); const id = 'submission-' + p.submissionId.toLowerCase();
    const fingerprint = hash(JSON.stringify({ ...p, title: payload.title, content: payload.content, authorName: payload.authorName ?? '' })); const time = now(); const at = new Date(time).toISOString(); const day = at.slice(0, 10);
    return db.transaction(async tx => {
      const old = await tx.get(S, id);
      if (old) {
        if (old.ownerUid !== who.uid || old.fingerprint !== fingerprint) fail(409, 'SUBMISSION_CONFLICT', '投稿ID已被其他内容或用户使用');
        return { submissionId: p.submissionId, id, status: old.status, duplicate: true };
      }
      const rateId = 'user-' + hash(who.uid); const globalId = 'global-' + day;
      const rate = await tx.get(R, rateId); const global = await tx.get(R, globalId); const c = await counters(tx);
      const count = rate?.day === day ? rate.count : 0;
      if ((rate && time - rate.lastAt < 60000) || count >= 5 || (global?.count || 0) >= 50) fail(429, 'RATE_LIMITED', '投稿频率已达限制，请稍后再试');
      const s = { ...p, id, ownerUid: who.uid, fingerprint, status: 'pending', published: false, withdrawn: false, createdAt: at, updatedAt: at, consentAt: at, reviewerNote: '', reviewedAt: null, sortKey: at + '|' + id };
      await tx.set(S, id, s); await tx.set(R, rateId, { day, count: count + 1, lastAt: time });
      await tx.set(R, globalId, { day, count: (global?.count || 0) + 1 });
      await tx.set(C, 'counts', { ...c, pending: c.pending + 1 }); await audit(tx, s, 'submit', who.uid, at);
      return { submissionId: p.submissionId, id, status: 'pending', duplicate: false };
    });
  }
  async function getPublishedStories(payload) {
    const page = v.page(payload, 'public'); const rows = await db.query(P, { ...(page.filter ? { equal: { category: page.filter } } : {}), before: page.before, limit: page.limit + 1 });
    return { items: rows.slice(0, page.limit).map(s => ({ ...pick(s, ['id','title','category','summary','authorName','publishedAt','sourceType','readingMinutes']) })), nextCursor: rows.length > page.limit ? page.cursor(rows[page.limit - 1].sortKey) : null };
  }
  async function getStoryDetail(payload) {
    v.shape(payload, ['id']); if (typeof payload.id !== 'string' || !v.PUBLIC_ID.test(payload.id)) fail(404, 'STORY_NOT_FOUND', '故事不存在或已撤下');
    const s = await db.get(P, payload.id); if (!s) fail(404, 'STORY_NOT_FOUND', '故事不存在或已撤下');
    return pick(s, DETAIL);
  }
  async function getPendingStories(payload, who) {
    identity(who, true); const page = v.page(payload, 'admin');
    const rows = await db.query(S, { ...(page.filter !== 'all' ? { equal: { status: page.filter } } : {}), before: page.before, limit: page.limit + 1 });
    const c = await db.get(C, 'counts'); if (!c) fail(503, 'NOT_INITIALIZED', '数据库计数未初始化');
    return { items: rows.slice(0, page.limit).map(s => pick(s, ['id','title','category','authorName','status','createdAt','published'])), nextCursor: rows.length > page.limit ? page.cursor(rows[page.limit - 1].sortKey) : null, counts: pick(c, ['pending','approved','rejected']) };
  }
  async function getSubmissionDetail(payload, who) {
    identity(who, true); const s = await required(db, v.id(payload)); return pick(s, ADMIN_DETAIL);
  }
  async function moderate(payload, who, action) {
    identity(who, true); const id = v.id(payload, action !== 'approve'); const at = new Date(now()).toISOString();
    const note = payload.note === undefined ? '' : v.string(payload.note, 0, 500);
    return db.transaction(async tx => {
      const s = await required(tx, id);
      if (action === 'approve' && s.status === 'approved' && !s.withdrawn && s.published) return { id, status: 'approved', publishedStoryId: s.publishedStoryId };
      if (action === 'reject' && s.status === 'rejected') return { id, status: 'rejected' };
      if (action === 'withdraw' && s.withdrawn) return { id, withdrawn: true };
      if ((action === 'withdraw' && (s.status !== 'approved' || !s.published)) || (action !== 'withdraw' && (s.status !== 'pending' || s.withdrawn))) fail(409, 'INVALID_STATE', '当前投稿状态不允许此操作');
      const c = await counters(tx);
      if (action === 'approve') {
        const publicId = 'story-cloud-' + s.submissionId.toLowerCase();
        // Read before any writes, including deterministic publication target.
        if (await tx.get(P, publicId)) fail(409, 'INVALID_STATE', '公开故事ID冲突');
        const pub = { id: publicId, submissionId: s.submissionId, title: s.title, category: s.category, content: s.content, authorName: s.authorName, publishedAt: at, sourceType: 'submission', summary: Array.from(s.content.replace(/\s+/g, ' ')).slice(0, 120).join(''), readingMinutes: Math.ceil(Array.from(s.content).length / 300), sortKey: at + '|' + publicId };
        await tx.set(P, publicId, pub);
        await tx.set(S, id, { ...s, status: 'approved', published: true, publishedStoryId: publicId, reviewerUid: who.uid, reviewedAt: at, updatedAt: at });
        await tx.set(C, 'counts', { ...c, pending: c.pending - 1, approved: c.approved + 1 });
        await audit(tx, s, 'approve', who.uid, at); return { id, status: 'approved', publishedStoryId: publicId };
      }
      if (action === 'reject') {
        await tx.set(S, id, { ...s, status: 'rejected', reviewerNote: note, reviewerUid: who.uid, reviewedAt: at, updatedAt: at });
        await tx.set(C, 'counts', { ...c, pending: c.pending - 1, rejected: c.rejected + 1 });
        await audit(tx, s, 'reject', who.uid, at); return { id, status: 'rejected' };
      }
      await tx.remove(P, s.publishedStoryId);
      await tx.set(S, id, { ...s, published: false, withdrawn: true, reviewerNote: note, withdrawnAt: at, withdrawnBy: who.uid, updatedAt: at });
      await audit(tx, s, 'withdraw', who.uid, at); return { id, withdrawn: true };
    });
  }
  return { submitStory, getPublishedStories, getStoryDetail, getPendingStories, getSubmissionDetail, approveStory: (p, u) => moderate(p, u, 'approve'), rejectStory: (p, u) => moderate(p, u, 'reject'), deletePublishedStory: (p, u) => moderate(p, u, 'withdraw') };
}
module.exports = { createService };
