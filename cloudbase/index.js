'use strict';
const { config } = require('./src/config');
const { createOfficialAuth } = require('./src/auth');
const { CloudBaseRepository } = require('./src/cloudbase-repository');
const { createService } = require('./src/service');
const { createLoginLimiter } = require('./src/login-limit');
const { createApi, errorResult, MAX_BYTES } = require('./src/http');
function createProductionApi(env = process.env) {
  const c = config(env);
  const cloudbase = require('@cloudbase/node-sdk');
  // HTTP functions need a dedicated server API key. Explicit priority avoids
  // retaining a startup temporary credential after the platform rotates it.
  // Event functions may still use their supported ambient runtime identity.
  const app = cloudbase.init({ env: c.envId, region: c.region, ...(env.CLOUDBASE_APIKEY ? { accessKey: env.CLOUDBASE_APIKEY } : {}) });
  const repository = new CloudBaseRepository(app.database());
  return createApi({ service: createService({ repository }), auth: createOfficialAuth({ origin: c.authOrigin, adminUids: c.adminUids }), allowedOrigins: c.origins, loginLimiter: createLoginLimiter(repository) });
}
function createProductionHandler(env = process.env) {
  let api;
  return async (event, context = {}) => {
    try {
      api ||= createProductionApi(env);
      let body = event.body || '';
      if (event.isBase64Encoded) {
        if (typeof body !== 'string' || body.length > Math.ceil(MAX_BYTES / 3) * 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body)) return errorResult(new (require('./src/errors').ApiError)(400, 'INVALID_JSON', '请求编码无效'));
        body = Buffer.from(body, 'base64');
      }
      return api({ method: event.httpMethod || context.httpContext?.httpMethod, headers: event.headers || context.httpContext?.headers, body });
    } catch (e) { return errorResult(e); }
  };
}
exports.main = createProductionHandler();
exports.createProductionHandler = createProductionHandler;
exports.createProductionApi = createProductionApi;
