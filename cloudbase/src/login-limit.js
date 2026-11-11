'use strict';
const { fail } = require('./errors');
// No user/password/IP stored. Shared fixed document works across warm/cold instances.
function createLoginLimiter(repository, now = Date.now) {
  return () => repository.transaction(async tx => {
    const time = now(); const day = new Date(time).toISOString().slice(0, 10);
    const row = await tx.get('story_rate_limits', 'admin-login-global');
    const start = row && time - row.windowStart < 60000 ? row.windowStart : time;
    const count = row && start === row.windowStart ? row.count : 0;
    const dayCount = row?.day === day ? row.dayCount : 0;
    if (count >= 10 || dayCount >= 100) fail(429, 'RATE_LIMITED', '管理员登录请求已达限制，请稍后再试');
    await tx.set('story_rate_limits', 'admin-login-global', { windowStart: start, count: count + 1, day, dayCount: dayCount + 1 });
  });
}
module.exports = { createLoginLimiter };
