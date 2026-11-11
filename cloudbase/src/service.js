'use strict';
const { createHash } = require('node:crypto');
const { fail } = require('./errors');
const v = require('./validation');
const e = require('./editorial');
const S = 'story_submissions', P = 'published_stories', R = 'story_rate_limits', A = 'story_audit_log', C = 'story_stats';
const hash = text => createHash('sha256').update(text).digest('hex');
const pick = (obj, keys) => Object.fromEntries(keys.filter(k => obj[k] !== undefined).map(k => [k, obj[k]]));
const ADMIN_DETAIL = ['id','title','category','content','summary','authorName','status','createdAt','updatedAt','consentAt','consentVersion','reviewerNote','reviewedAt','published','publishedStoryId','recordType','revision','withdrawn'];
function identity(who, admin = false) {
  if (!who || typeof who.uid !== 'string' || !who.uid) fail(401, 'AUTH_REQUIRED', '请先登录');
  if (admin && who.isAdmin !== true) fail(403, 'ADMIN_REQUIRED', '需要管理员权限');
}
function createService({ repository: db, now = Date.now }) {
  async function counters(tx) { const c = await tx.get(C, 'counts'); if (!c) fail(503, 'NOT_INITIALIZED', '数据库计数未初始化'); return c; }
  async function required(tx, id) { const s = await tx.get(S, id); if (!s) fail(404, 'SUBMISSION_NOT_FOUND', '投稿不存在'); return s; }
  const audit = (tx, s, action, uid, at) => tx.set(A, `${s.id}-${action}`, { submissionId: s.id, action, actorUid: uid, at });
  const bumpPublic = c => ({ ...c, publicRevision: (c.publicRevision || 0) + 1 });
  async function createEditorialStory(payload, who) {
    identity(who, true); v.shape(payload, ['editorialId', ...e.FIELDS]);
    if (typeof payload.editorialId !== 'string' || !v.UUID.test(payload.editorialId)) fail(400, 'INVALID_PAYLOAD', '精选创建ID应为UUID');
    const { editorialId, ...input } = payload;
    const p = e.fields(input);
    if (!p.title || !p.category) fail(400, 'INVALID_PAYLOAD', '请填写标题与分类');
    const id = 'submission-' + editorialId.toLowerCase(), at = new Date(now()).toISOString();
    const fingerprint = hash(JSON.stringify(Object.fromEntries(Object.entries(p).sort(([a], [b]) => a.localeCompare(b)))));
    const s = { content: '', summary: '', authorName: '', ...e.metadata({ recordType: 'editorial' }), ...p,
      id, submissionId: editorialId.toLowerCase(), recordType: 'editorial', revision: 0, ownerUid: who.uid, fingerprint,
      status: 'pending', published: false, withdrawn: false, createdAt: at, updatedAt: at, reviewerNote: '', sortKey: at + '|' + id };
    e.validate(s);
    return db.transaction(async tx => {
      const old = await tx.get(S, id);
      if (old) {
        if (old.recordType !== 'editorial' || old.ownerUid !== who.uid || old.fingerprint !== fingerprint) fail(409, 'SUBMISSION_CONFLICT', '创建ID已被其他内容使用');
        return { id, status: old.status, duplicate: true };
      }
      const c = await counters(tx);
      await tx.set(S, id, s); await tx.set(C, 'counts', { ...c, pending: c.pending + 1 });
      await audit(tx, s, 'editorial-create', who.uid, at);
      return { id, status: 'pending', duplicate: false };
    });
  }
  async function updateManagedStory(payload, who) {
    identity(who, true); v.shape(payload, ['id','revision','patch','confirmPublic']); const id = v.id({ id: payload.id });
    if (!Number.isSafeInteger(payload.revision) || payload.revision < 0 || (payload.confirmPublic !== undefined && typeof payload.confirmPublic !== 'boolean')) fail(400, 'INVALID_PAYLOAD', '版本或公开确认不正确');
    const patch = e.fields(payload.patch);
    if (!Object.keys(patch).length) fail(400, 'INVALID_PAYLOAD', '修改不能为空');
    const at = new Date(now()).toISOString();
    return db.transaction(async tx => {
      const s = await required(tx, id);
      if (s.withdrawn || s.status === 'rejected') fail(409, 'INVALID_STATE', '已撤下或拒绝的记录不可编辑或重发');
      if ((s.revision || 0) !== payload.revision) fail(409, 'REVISION_CONFLICT', '作品已被修改，请刷新详情');
      const n = { ...s, ...e.metadata(s), ...patch, revision: payload.revision + 1, updatedAt: at };
      // Replacing authorized text or its attribution invalidates that grant unless explicitly reasserted.
      if (!Object.hasOwn(patch, 'rightsStatus') && ['content','sourceType','sourceUrl','publisherName','authorName','sourceWorkTitle','commentLocator'].some(k => Object.hasOwn(patch, k) && patch[k] !== s[k])) n.rightsStatus = 'unverified';
      e.validate(n);
      if (s.published && !n.hidden && payload.confirmPublic !== true) fail(409, 'PUBLIC_CONFIRMATION_REQUIRED', '修改公开作品前须再次确认');
      const c = await counters(tx);
      if (s.published) {
        const oldPublication = await tx.get(P, s.publishedStoryId);
        n.publishedAt = s.publishedAt || oldPublication?.publishedAt || s.reviewedAt || s.createdAt || at;
        if (n.hidden) await tx.remove(P, s.publishedStoryId);
        else await tx.set(P, s.publishedStoryId, e.publication({ ...n, id: s.publishedStoryId }, n.publishedAt));
      }
      await tx.set(S, id, n);
      if (s.published) await tx.set(C, 'counts', bumpPublic(c));
      await audit(tx, n, 'edit-' + n.revision, who.uid, at);
      return { id, revision: n.revision, published: n.published, hidden: n.hidden };
    });
  }
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
    const revision = (await db.get(C, 'counts'))?.publicRevision || 0;
    const page = v.page(payload, 'public', revision); const rows = []; let before = page.before;
    // Hidden records are removed transactionally. Defensive filtering also covers legacy/manual rows.
    while (rows.length <= page.limit) {
      const batch = await db.query(P, { ...(page.filter ? { equal: { category: page.filter } } : {}), before, limit: page.limit + 1 });
      rows.push(...batch.filter(s => !s.hidden && !s.withdrawn));
      if (batch.length < page.limit + 1) break;
      before = batch[batch.length - 1].sortKey;
    }
    if (((await db.get(C, 'counts'))?.publicRevision || 0) !== revision) fail(400, 'INVALID_CURSOR', '公开列表已更新，请从首页重新读取');
    return { items: rows.slice(0, page.limit).map(s => e.publicDTO(s)), nextCursor: rows.length > page.limit ? page.cursor(rows[page.limit - 1].sortKey) : null };
  }
  async function getStoryDetail(payload) {
    v.shape(payload, ['id']); if (typeof payload.id !== 'string' || !v.PUBLIC_ID.test(payload.id)) fail(404, 'STORY_NOT_FOUND', '随记不存在或已撤下');
    const s = await db.get(P, payload.id); if (!s || s.hidden || s.withdrawn) fail(404, 'STORY_NOT_FOUND', '随记不存在或已撤下');
    return e.publicDTO(s, true);
  }
  async function getPendingStories(payload, who) {
    identity(who, true); const page = v.page(payload, 'admin');
    const rows = await db.query(S, { ...(page.filter !== 'all' ? { equal: { status: page.filter } } : {}), before: page.before, limit: page.limit + 1 });
    const c = await db.get(C, 'counts'); if (!c) fail(503, 'NOT_INITIALIZED', '数据库计数未初始化');
    return { items: rows.slice(0, page.limit).map(s => ({ ...pick(s, ['id','title','category','authorName','status','createdAt','published','recordType','withdrawn']), ...pick(e.metadata(s), ['hidden','editorialRank','rightsStatus']) })), nextCursor: rows.length > page.limit ? page.cursor(rows[page.limit - 1].sortKey) : null, counts: pick(c, ['pending','approved','rejected']) };
  }
  async function getSubmissionDetail(payload, who) {
    identity(who, true); const s = await required(db, v.id(payload)); return { ...pick(s, ADMIN_DETAIL), ...e.metadata(s), recordType: s.recordType || 'submission', revision: s.revision || 0 };
  }
  async function moderate(payload, who, action) {
    identity(who, true);
    if (action === 'approve') {
      v.shape(payload, ['id','revision']);
      if (payload.revision !== undefined && (!Number.isSafeInteger(payload.revision) || payload.revision < 0)) fail(400, 'INVALID_PAYLOAD', '审核版本不正确');
    }
    const id = v.id(action === 'approve' ? { id: payload.id } : payload, action !== 'approve'); const at = new Date(now()).toISOString();
    const note = payload.note === undefined ? '' : v.string(payload.note, 0, 500);
    return db.transaction(async tx => {
      const s = await required(tx, id);
      if (action === 'approve' && s.status === 'approved' && !s.withdrawn && s.published) return { id, status: 'approved', publishedStoryId: s.publishedStoryId };
      if (action === 'reject' && s.status === 'rejected') return { id, status: 'rejected' };
      if (action === 'withdraw' && s.withdrawn) return { id, withdrawn: true };
      if ((action === 'withdraw' && (s.status !== 'approved' || !s.published)) || (action !== 'withdraw' && (s.status !== 'pending' || s.withdrawn))) fail(409, 'INVALID_STATE', '当前投稿状态不允许此操作');
      if (action === 'approve' && payload.revision !== undefined && payload.revision !== (s.revision || 0)) fail(409, 'REVISION_CONFLICT', '作品已更新，请重新核对后发布');
      const c = await counters(tx);
      if (action === 'approve') {
        const publicId = 'story-cloud-' + s.submissionId.toLowerCase();
        // Read before any writes, including deterministic publication target.
        if (await tx.get(P, publicId)) fail(409, 'INVALID_STATE', '公开随记ID冲突');
        const pub = e.publication({ ...s, id: publicId }, at);
        if (!s.hidden) await tx.set(P, publicId, pub);
        await tx.set(S, id, { ...s, status: 'approved', published: true, publishedStoryId: publicId, publishedAt: at, revision: (s.revision || 0) + 1, reviewerUid: who.uid, reviewedAt: at, updatedAt: at });
        await tx.set(C, 'counts', bumpPublic({ ...c, pending: c.pending - 1, approved: c.approved + 1 }));
        await audit(tx, s, 'approve', who.uid, at); return { id, status: 'approved', publishedStoryId: publicId };
      }
      if (action === 'reject') {
        await tx.set(S, id, { ...s, status: 'rejected', revision: (s.revision || 0) + 1, reviewerNote: note, reviewerUid: who.uid, reviewedAt: at, updatedAt: at });
        await tx.set(C, 'counts', { ...c, pending: c.pending - 1, rejected: c.rejected + 1 });
        await audit(tx, s, 'reject', who.uid, at); return { id, status: 'rejected' };
      }
      await tx.remove(P, s.publishedStoryId);
      await tx.set(S, id, { ...s, published: false, withdrawn: true, revision: (s.revision || 0) + 1, reviewerNote: note, withdrawnAt: at, withdrawnBy: who.uid, updatedAt: at });
      await tx.set(C, 'counts', bumpPublic(c));
      await audit(tx, s, 'withdraw', who.uid, at); return { id, withdrawn: true };
    });
  }
  return { createEditorialStory, updateManagedStory, submitStory, getPublishedStories, getStoryDetail, getPendingStories, getSubmissionDetail, approveStory: (p, u) => moderate(p, u, 'approve'), rejectStory: (p, u) => moderate(p, u, 'reject'), deletePublishedStory: (p, u) => moderate(p, u, 'withdraw') };
}
module.exports = { createService };
