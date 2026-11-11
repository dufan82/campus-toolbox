'use strict';
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { FileRepository } = require('./repository');
const { createMockAuth } = require('./auth');
const { createService } = require('../src/service');
const { createLoginLimiter } = require('../src/login-limit');
const { createApi, errorResult, result, readBody, send } = require('../src/http');
const { fail } = require('../src/errors');
async function startMock({ port = 8787, file = process.env.MOCK_DB_FILE || path.join(__dirname, 'data', 'stories.json'), now = Date.now } = {}) {
  const repository = await FileRepository.open(file);
  const auth = createMockAuth({ now }); const service = createService({ repository, now });
  let api; let anonymousWindow = 0, anonymousCount = 0;
  const adminRoot = path.resolve(__dirname, '..', 'admin');
  const server = http.createServer(async (req, res) => {
    try {
      const boundPort = server.address().port;
      if (![ `127.0.0.1:${boundPort}`, `localhost:${boundPort}` ].includes(req.headers.host)) fail(403, 'HOST_NOT_ALLOWED', '仅允许本机访问');
      const url = new URL(req.url, `http://127.0.0.1:${boundPort}`);
      if (url.pathname === '/api') return send(res, await api({ method: req.method, headers: req.headers, body: await readBody(req) }));
      if (url.pathname === '/auth/v1/signin/anonymously') {
        if (req.method !== 'POST') fail(405, 'METHOD_NOT_ALLOWED', '仅支持POST请求');
        if (req.headers.origin && ![`http://127.0.0.1:${boundPort}`,`http://localhost:${boundPort}`].includes(req.headers.origin)) fail(403, 'ORIGIN_NOT_ALLOWED', '仅允许本机来源');
        const raw = await readBody(req); let p; try { p = JSON.parse(raw.toString()); } catch { fail(400, 'INVALID_JSON', 'JSON格式不正确'); }
        if (!p || typeof p !== 'object' || Array.isArray(p) || Object.keys(p).length) fail(400, 'INVALID_PAYLOAD', '匿名登录请求应为空对象');
        const time = now(); if (time - anonymousWindow >= 60000) { anonymousWindow = time; anonymousCount = 0; }
        if (++anonymousCount > 120) fail(429, 'RATE_LIMITED', '本机匿名登录请求过于频繁');
        return send(res, result(200, auth.anonymous(req.headers['x-device-id'])));
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') fail(404, 'NOT_FOUND', '接口不存在');
      let relative; try { relative = decodeURIComponent(url.pathname); } catch { fail(400, 'INVALID_PATH', '路径不正确'); }
      relative = relative.replace(/^\/admin(?:\/|$)/, '/'); if (relative === '/') relative = '/index.html';
      if (relative.includes('\\') || relative.includes('\0') || relative.split('/').includes('..')) fail(404, 'NOT_FOUND', '文件不存在');
      const target = path.resolve(adminRoot, '.' + relative);
      const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
      const type = types[path.extname(target)]; if (!type || !target.startsWith(adminRoot + path.sep)) fail(404, 'NOT_FOUND', '文件不存在');
      let real; try { real = await fs.realpath(target); } catch { fail(404, 'NOT_FOUND', '管理页文件尚未提供'); }
      if (!real.startsWith(await fs.realpath(adminRoot) + path.sep)) fail(404, 'NOT_FOUND', '文件不存在');
      const bytes = await fs.readFile(real);
      res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY' }); res.end(req.method === 'HEAD' ? undefined : bytes);
    } catch (e) { send(res, errorResult(e)); }
  });
  server.requestTimeout = 15000; server.headersTimeout = 10000;
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  } catch (e) { await repository.close(); throw e; }
  const actualPort = server.address().port;
  api = createApi({ service, auth, now, allowedOrigins: [`http://127.0.0.1:${actualPort}`,`http://localhost:${actualPort}`], loginLimiter: createLoginLimiter(repository, now) });
  return { server, repository, url: `http://127.0.0.1:${actualPort}`, async close() { await new Promise((resolve, reject) => { server.close(e => e ? reject(e) : resolve()); server.closeIdleConnections?.(); }); await repository.close(); } };
}
if (require.main === module) {
  startMock().then(app => {
    console.log(`Story MOCK listening at ${app.url}; local fixture only; persistent database enabled.`);
    const stop = () => app.close().then(() => process.exit(0)).catch(() => process.exit(1));
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
  }).catch(e => { console.error(e.code === 'EADDRINUSE' ? 'Loopback port 8787 already in use; existing process preserved.' : 'Mock start failed; inspect configuration / database lock.'); process.exitCode = 1; });
}
module.exports = { startMock };
