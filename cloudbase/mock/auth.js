'use strict';
// Dedicated development fixture ONLY. This module is never imported by production.
const { randomBytes, createHash, timingSafeEqual } = require('node:crypto');
const { fail } = require('../src/errors');
const v = require('../src/validation');
const digest = s => createHash('sha256').update(s).digest();
function createMockAuth({ now = Date.now } = {}) {
  const sessions = new Map();
  function issue(uid, isAdmin = false) {
    for (const [token, s] of sessions) if (s.expiry <= now()) sessions.delete(token);
    if (sessions.size >= 1000) fail(429, 'RATE_LIMITED', '本机登录会话已达上限');
    const token = 'mock_' + randomBytes(32).toString('base64url');
    sessions.set(token, { uid, isAdmin, expiry: now() + 3600000 }); return token;
  }
  return {
    anonymous(device) {
      if (typeof device !== 'string' || !v.UUID.test(device)) fail(400, 'INVALID_DEVICE_ID', '请提供随机设备UUID');
      const uid = 'mock-anon-' + digest(device).toString('hex').slice(0, 32);
      return { access_token: issue(uid), expires_in: 3600, sub: uid, token_type: 'Bearer' };
    },
    async verify(token) {
      const s = sessions.get(token); if (!s || s.expiry <= now()) fail(401, 'AUTH_INVALID', '本机登录已失效');
      return { uid: s.uid, isAdmin: s.isAdmin };
    },
    async login(payload) {
      v.shape(payload, ['username','password']); v.string(payload.username, 1, 48); v.string(payload.password, 1, 1024);
      if (!timingSafeEqual(digest(payload.username), digest('admin')) || !timingSafeEqual(digest(payload.password), digest('mock-admin-password'))) fail(401, 'AUTH_INVALID', 'Mock演示账号或密码不正确');
      return { accessToken: issue('mock-admin-fixture', true), expiresIn: 3600 };
    }
  };
}
module.exports = { createMockAuth };
