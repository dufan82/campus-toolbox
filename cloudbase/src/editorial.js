'use strict';
const net = require('node:net');
const v = require('./validation');
const { fail } = require('./errors');
const SOURCE_TYPES = ['douyin_post','douyin_image','douyin_comment','other_platform','user_submission'];
const RIGHTS = ['unverified','excerpt_only','authorized','public_domain','user_authorized','revoked'];
const FULL_TEXT = new Set(['authorized','public_domain','user_authorized']);
const FIELDS = ['title','category','content','summary','authorName','sourceType','sourceUrl','publisherName','sourceWorkTitle','commentLocator','rightsStatus','rightsNote','editorialRank','excerpt','hidden'];
const META = ['sourceType','sourceUrl','publisherName','sourceWorkTitle','commentLocator','rightsStatus','editorialRank','excerpt','hidden'];
function sourceUrl(value) {
  const text = v.string(value, 1, 2048); let u;
  try { u = new URL(text); } catch { fail(400, 'INVALID_PAYLOAD', '来源须为完整HTTPS链接'); }
  // Syntax/public-host validation only: never fetch a pasted URL (including redirects).
  if (u.protocol !== 'https:' || u.username || u.password || (u.port && u.port !== '443') ||
      !u.hostname.includes('.') || net.isIP(u.hostname.replace(/^\[|\]$/g, '')) ||
      /(^|\.)(localhost|local|internal|test|invalid|example)$/.test(u.hostname) || /\s/.test(text)) {
    fail(400, 'INVALID_PAYLOAD', '来源须为无凭据的公网HTTPS链接');
  }
  return text;
}
function metadata(s) {
  const legacy = s.recordType !== 'editorial' && (s.sourceType === 'submission' || s.sourceType === 'user_submission' || s.consentVersion === v.CONSENT);
  return { sourceType: s.sourceType === 'submission' || !s.sourceType ? (legacy ? 'user_submission' : 'other_platform') : s.sourceType,
    sourceUrl: s.sourceUrl || '', publisherName: s.publisherName || '', sourceWorkTitle: s.sourceWorkTitle || '', commentLocator: s.commentLocator || '',
    rightsStatus: s.rightsStatus || (legacy ? 'user_authorized' : 'unverified'), rightsNote: s.rightsNote || '', editorialRank: s.editorialRank ?? 0,
    excerpt: s.excerpt || '', hidden: s.hidden === true };
}
function fields(p) {
  v.shape(p, FIELDS); const out = {};
  for (const [key, value] of Object.entries(p)) {
    if (key === 'sourceType' || key === 'rightsStatus' || key === 'category') {
      if (!(key === 'sourceType' ? SOURCE_TYPES : key === 'rightsStatus' ? RIGHTS : v.CATEGORIES).includes(value)) fail(400, 'INVALID_PAYLOAD', '来源、授权或分类不正确');
      out[key] = value;
    } else if (key === 'editorialRank') {
      if (!Number.isInteger(value) || Math.abs(value) > 1000000000) fail(400, 'INVALID_PAYLOAD', '排序应为-1000000000至1000000000的整数');
      out[key] = value;
    } else if (key === 'hidden') {
      if (typeof value !== 'boolean') fail(400, 'INVALID_PAYLOAD', '隐藏状态应为布尔值'); out[key] = value;
    } else if (key === 'sourceUrl') { out[key] = value === '' ? '' : sourceUrl(value); }
    else {
      const max = { title: 120, content: 20000, summary: 240, authorName: 80, publisherName: 80, sourceWorkTitle: 200, commentLocator: 500, rightsNote: 2000, excerpt: 80 }[key];
      out[key] = v.plain(value, key === 'title' ? 2 : 0, max);
    }
  }
  return out;
}
function validate(s) {
  if (s.sourceType !== 'user_submission' && !s.sourceUrl) fail(400, 'INVALID_PAYLOAD', '外部来源必须填写HTTPS原文链接');
  // Rights apply to the exact text; never infer a summary or excerpt from a private draft.
  if (s.sourceType !== 'user_submission' && !FULL_TEXT.has(s.rightsStatus) && !s.summary && !s.excerpt) fail(400, 'INVALID_PAYLOAD', '未获全文授权时请填写独立介绍或必要短摘录');
}
function sortKey(rank, at, id) {
  // Preserve all legacy rank=0 ISO keys and existing descending indexes without migration.
  const prefix = rank === 0 ? '' : (rank < 0 ? '~' : '!') + String(1000000000 - rank).padStart(10, '0') + '|';
  return prefix + at + '|' + id;
}
function publicDTO(s, detail = false) {
  const m = metadata(s), full = FULL_TEXT.has(m.rightsStatus);
  const dto = Object.fromEntries(['id','title','category','authorName','publishedAt'].filter(k => s[k] !== undefined).map(k => [k, s[k]]));
  for (const key of META) dto[key] = key === 'excerpt' ? Array.from(m.excerpt).slice(0, 80).join('') : m[key];
  dto.content = detail && full ? (s.content || '') : '';
  dto.summary = s.summary || '';
  dto.readingMinutes = Math.max(1, Math.ceil(Array.from(full ? s.content || '' : dto.summary + m.excerpt).length / 300));
  return dto;
}
function publication(s, at) {
  const m = metadata(s);
  validate({ ...s, ...m });
  const summary = s.summary !== undefined ? s.summary : (FULL_TEXT.has(m.rightsStatus) ? Array.from((s.content || '').replace(/\s+/g, ' ')).slice(0, 120).join('') : '');
  return { ...publicDTO({ ...s, ...m, summary, publishedAt: at }, true), submissionId: s.submissionId,
    sortKey: sortKey(m.editorialRank, at, s.id) };
}
module.exports = { FIELDS, META, SOURCE_TYPES, RIGHTS, FULL_TEXT, fields, metadata, validate, publication, publicDTO, sortKey };
