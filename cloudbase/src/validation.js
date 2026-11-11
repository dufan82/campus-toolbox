'use strict';
const { fail } = require('./errors');
const CATEGORIES = ['LIFE', 'GROWTH', 'WARMTH', 'TWIST', 'OTHER'];
const CONSENT = '2026-11-10-v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PUBLIC_ID = /^story-cloud-[a-z0-9-]{1,63}$/;
function shape(p, allowed) {
  if (!p || typeof p !== 'object' || Array.isArray(p) || Object.keys(p).some(k => !allowed.includes(k))) fail(400, 'INVALID_PAYLOAD', '请求字段不正确');
}
function string(value, min, max) {
  if (typeof value !== 'string') fail(400, 'INVALID_PAYLOAD', '文本类型不正确');
  const trimmed = value.trim(); const length = Array.from(trimmed).length;
  if (length < min || length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)) fail(400, 'INVALID_PAYLOAD', '文本类型或长度不正确');
  return trimmed;
}
function plain(value, min, max) { const text = string(value, min, max); if (/<\/?[a-z][^>]*>/iu.test(text)) fail(400, 'INVALID_PAYLOAD', '投稿只接受纯文本，不支持HTML标签'); return text; }
function submission(p) {
  shape(p, ['submissionId','title','category','content','authorName','consent','consentVersion']);
  if (!UUID.test(p.submissionId) || !CATEGORIES.includes(p.category) || p.consent !== true || p.consentVersion !== CONSENT) fail(400, 'INVALID_PAYLOAD', '投稿ID、分类或授权版本不正确');
  return { submissionId: p.submissionId, title: plain(p.title, 2, 30), category: p.category, content: plain(p.content, 100, 5000), authorName: p.authorName === undefined ? '匿名' : (plain(p.authorName, 0, 30) || '匿名'), consent: true, consentVersion: CONSENT };
}
function id(p, note = false) {
  shape(p, note ? ['id','note'] : ['id']); string(p.id, 1, 80);
  if (!/^submission-[a-f0-9-]{36}$/.test(p.id)) fail(400, 'INVALID_PAYLOAD', '投稿ID不正确');
  if (note && p.note !== undefined) string(p.note, 0, 500);
  return p.id;
}
function page(p, kind) {
  shape(p, kind === 'public' ? ['limit','cursor','category'] : ['limit','cursor','status']);
  const limit = p.limit === undefined ? 20 : p.limit;
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) fail(400, 'INVALID_PAYLOAD', '分页大小应为1至50');
  const filter = kind === 'public' ? (p.category || '') : (p.status || 'pending');
  if ((kind === 'public' && filter && !CATEGORIES.includes(filter)) || (kind !== 'public' && !['all','pending','approved','rejected'].includes(filter))) fail(400, 'INVALID_PAYLOAD', '筛选条件不正确');
  let before;
  if (p.cursor !== undefined) {
    try {
      if (typeof p.cursor !== 'string' || p.cursor.length > 1024 || !/^[A-Za-z0-9_-]+$/.test(p.cursor)) throw Error();
      const c = JSON.parse(Buffer.from(p.cursor, 'base64url').toString('utf8'));
      if (c.v !== 1 || c.kind !== kind || c.filter !== filter || typeof c.last !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z\|[a-z0-9-]{1,80}$/.test(c.last)) throw Error();
      before = c.last;
    } catch { fail(400, 'INVALID_CURSOR', '分页游标无效或筛选条件已改变'); }
  }
  return { limit, filter, before, cursor: last => Buffer.from(JSON.stringify({ v: 1, kind, filter, last })).toString('base64url') };
}
module.exports = { CATEGORIES, CONSENT, PUBLIC_ID, UUID, shape, string, submission, id, page };
