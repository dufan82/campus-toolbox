'use strict';
const { ApiError, fail } = require('./errors');
const v = require('./validation');
const MAX_BYTES = 256 * 1024;
const ACTIONS = new Set(['submitStory','getPublishedStories','getStoryDetail','adminLogin','getPendingStories','getSubmissionDetail','approveStory','rejectStory','deletePublishedStory','createEditorialStory','updateManagedStory']);
const PUBLIC = new Set(['getPublishedStories','getStoryDetail']);
const ADMIN = new Set(['getPendingStories','getSubmissionDetail','approveStory','rejectStory','deletePublishedStory','createEditorialStory','updateManagedStory']);
function result(status, data, headers = {}) {
  let body = JSON.stringify(data);
  if (Buffer.byteLength(body) > MAX_BYTES) { status = 500; body = JSON.stringify({ ok: false, error: { code: 'RESPONSE_TOO_LARGE', message: '响应超出大小限制' } }); }
  return { statusCode: status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers }, isBase64Encoded: false, body };
}
function errorResult(e, headers) {
  const known = e instanceof ApiError;
  return result(known ? e.status : 500, { ok: false, error: { code: known ? e.code : 'INTERNAL_ERROR', message: known ? e.message : '服务暂不可用' } }, headers);
}
function createApi({ service, auth, allowedOrigins = [], now = Date.now, loginLimiter }) {
  let loginStart = 0, loginCount = 0;
  return async request => {
    const headers = Object.fromEntries(Object.entries(request.headers || {}).map(([k, value]) => [k.toLowerCase(), value]));
    let cors = {};
    try {
      if (headers.origin) {
        if (!allowedOrigins.includes(headers.origin)) fail(403, 'ORIGIN_NOT_ALLOWED', '请求来源未获允许');
        cors = { 'Access-Control-Allow-Origin': headers.origin, Vary: 'Origin', 'Access-Control-Allow-Methods': 'POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type, Authorization' };
      }
      if (request.method === 'OPTIONS') return result(204, null, cors);
      if (request.method !== 'POST') fail(405, 'METHOD_NOT_ALLOWED', '仅支持POST请求');
      if (headers['content-type'] && !/^application\/json(?:\s*;|$)/i.test(headers['content-type'])) fail(415, 'UNSUPPORTED_MEDIA_TYPE', '请使用JSON请求');
      const raw = request.body ?? '';
      if ((!Buffer.isBuffer(raw) && typeof raw !== 'string') || Buffer.byteLength(raw) > MAX_BYTES) fail(413, 'PAYLOAD_TOO_LARGE', '请求超出大小限制');
      let input; try { input = JSON.parse(raw.toString()); } catch { fail(400, 'INVALID_JSON', 'JSON格式不正确'); }
      v.shape(input, ['action','payload']);
      if (!ACTIONS.has(input.action)) fail(400, 'UNKNOWN_ACTION', '未知操作');
      v.shape(input.payload, Object.keys(input.payload || {}));
      let data;
      if (input.action === 'adminLogin') {
        const time = now(); if (time - loginStart >= 60000) { loginStart = time; loginCount = 0; }
        if (++loginCount > 10) fail(429, 'RATE_LIMITED', '登录请求过于频繁');
        if (loginLimiter) await loginLimiter();
        data = await auth.login(input.payload);
      } else {
        let who;
        if (!PUBLIC.has(input.action)) {
          if (typeof headers.authorization !== 'string' || !/^Bearer [^\s]+$/.test(headers.authorization)) fail(401, 'AUTH_REQUIRED', '请先登录');
          who = await auth.verify(headers.authorization.slice(7));
          if (ADMIN.has(input.action) && !who.isAdmin) fail(403, 'ADMIN_REQUIRED', '需要管理员权限');
        }
        data = await service[input.action](input.payload, who);
      }
      return result(200, { ok: true, data }, cors);
    } catch (e) { return errorResult(e, cors); }
  };
}
async function readBody(req) {
  const parts = []; let length = 0;
  for await (const chunk of req) { length += chunk.length; if (length > MAX_BYTES) fail(413, 'PAYLOAD_TOO_LARGE', '请求超出大小限制'); parts.push(chunk); }
  return Buffer.concat(parts);
}
function send(res, response) { res.writeHead(response.statusCode, response.headers); res.end(response.body); }
module.exports = { createApi, errorResult, result, readBody, send, MAX_BYTES };
