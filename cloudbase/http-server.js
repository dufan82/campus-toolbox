'use strict';
const http = require('node:http');
const { createProductionApi } = require('./index');
const { readBody, send, errorResult } = require('./src/http');
function start() {
  const api = createProductionApi(); // Refuses empty production configuration. No fallback.
  const server = http.createServer(async (req, res) => {
    try { send(res, await api({ method: req.method, headers: req.headers, body: await readBody(req) })); }
    catch (e) { send(res, errorResult(e)); }
  });
  server.requestTimeout = 15000; server.headersTimeout = 10000;
  server.listen(9000, '0.0.0.0'); // CloudBase HTTP function platform port, NOT the local mock.
  return server;
}
if (require.main === module) start();
module.exports = { start };
