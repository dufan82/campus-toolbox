'use strict';
const { fail } = require('./errors');
const v = require('./validation');
function createOfficialAuth({ origin, adminUids = [], fetchImpl = globalThis.fetch, timeoutMs = 5000 }) {
  const url = new URL(origin);
  if (url.protocol !== 'https:' || !/^[a-z0-9-]+\.api\.tcloudbasegateway\.com$/.test(url.hostname) || url.port || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('TCB_AUTH_ORIGIN must be the official HTTPS environment auth origin');
  const fixedOrigin = url.origin; const admins = new Set(adminUids);
  async function request(route, options) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(fixedOrigin + route, { ...options, redirect: 'error', signal: controller.signal });
      if ([400,401,403].includes(response.status)) fail(401, 'AUTH_INVALID', '登录已失效或凭据不正确');
      if (!response.ok) fail(503, 'AUTH_UNAVAILABLE', '身份认证服务暂不可用');
      // Bound upstream body without trusting Content-Length; timer also covers stream reads.
      const reader = response.body.getReader(); const chunks = []; let size = 0;
      try {
        while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length; if (size > 65536) { await reader.cancel(); fail(503, 'AUTH_UNAVAILABLE', '身份认证响应无效'); } chunks.push(Buffer.from(value)); }
      } finally { reader.releaseLock(); }
      const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (!data || typeof data !== 'object' || Array.isArray(data)) fail(503, 'AUTH_UNAVAILABLE', '身份认证响应无效');
      return data;
    } catch (e) { if (e.code === 'AUTH_INVALID' || e.code === 'AUTH_UNAVAILABLE') throw e; fail(503, 'AUTH_UNAVAILABLE', '身份认证服务暂不可用'); }
    finally { clearTimeout(timer); }
  }
  async function verify(token) {
    if (typeof token !== 'string' || !token || token.length > 8192 || /\s/.test(token)) fail(401, 'AUTH_INVALID', '登录令牌无效');
    const profile = await request('/auth/v1/user/me', { method: 'GET', headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' } });
    if (typeof profile.sub !== 'string' || !profile.sub || profile.sub.length > 256) fail(401, 'AUTH_INVALID', '身份认证响应缺少用户标识');
    // The official anonymous user/me response omits status and carries the
    // anonymous group. Only that verified non-admin profile may omit it.
    const anonymous = profile.status === undefined && !admins.has(profile.sub)
      && Array.isArray(profile.groups) && profile.groups.some(group => group?.id === 'anonymous');
    if (!anonymous && !['ACTIVE', 'DEFAULT'].includes(profile.status)) fail(401, 'AUTH_INVALID', '该账号状态不可用');
    return { uid: profile.sub, isAdmin: admins.has(profile.sub) };
  }
  async function login(payload) {
    v.shape(payload, ['username','password']); v.string(payload.username, 1, 48); v.string(payload.password, 1, 1024);
    const tokens = await request('/auth/v1/signin', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ username: payload.username, password: payload.password }) });
    if (typeof tokens.access_token !== 'string' || !Number.isFinite(tokens.expires_in) || tokens.expires_in <= 0) fail(503, 'AUTH_UNAVAILABLE', '身份认证响应无效');
    const who = await verify(tokens.access_token);
    if (!who.isAdmin) fail(403, 'ADMIN_REQUIRED', '该账号没有审核权限');
    return { accessToken: tokens.access_token, expiresIn: tokens.expires_in };
  }
  return { verify, login };
}
module.exports = { createOfficialAuth };
