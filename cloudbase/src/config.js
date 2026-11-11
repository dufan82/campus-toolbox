'use strict';
const { fail } = require('./errors');
function config(env = process.env) {
  const required = ['TCB_ENV_ID','TCB_REGION','TCB_AUTH_ORIGIN'];
  if (required.some(key => !env[key]?.trim())) fail(503, 'NOT_CONFIGURED', 'CloudBase环境、地域或认证地址未配置');
  const expected = `https://${env.TCB_ENV_ID}.api.tcloudbasegateway.com`;
  if (env.TCB_AUTH_ORIGIN.replace(/\/$/, '') !== expected) fail(503, 'NOT_CONFIGURED', '认证地址须与CloudBase环境匹配');
  const origins = (env.ADMIN_ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
  for (const origin of origins) {
    let url; try { url = new URL(origin); } catch { fail(503, 'NOT_CONFIGURED', '后台来源域名配置无效'); }
    if (url.protocol !== 'https:' || url.origin !== origin) fail(503, 'NOT_CONFIGURED', '后台须配置精确HTTPS来源');
  }
  // Optional owner-only local review UI. This does not permit arbitrary HTTP
  // web origins; student/API requests and all authentication remain HTTPS.
  if (env.ADMIN_LOCAL_ORIGIN) {
    let local; try { local = new URL(env.ADMIN_LOCAL_ORIGIN); } catch { fail(503, 'NOT_CONFIGURED', '本机审核来源无效'); }
    if (local.protocol !== 'http:' || local.hostname !== '127.0.0.1' || !local.port || local.origin !== env.ADMIN_LOCAL_ORIGIN)
      fail(503, 'NOT_CONFIGURED', '本机审核仅允许精确127.0.0.1来源');
    origins.push(local.origin);
  }
  return { envId: env.TCB_ENV_ID, region: env.TCB_REGION, authOrigin: env.TCB_AUTH_ORIGIN, adminUids: (env.ADMIN_UIDS || '').split(',').map(s => s.trim()).filter(Boolean), origins };
}
module.exports = { config };
