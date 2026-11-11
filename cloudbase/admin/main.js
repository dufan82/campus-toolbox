import { createApiClient, resolveEndpoint } from './api.js';
import { mountAdminApp } from './app.js';

let endpoint;
try { endpoint = resolveEndpoint(window.CAMPUS_ADMIN_CONFIG, window.location); } catch {
  document.getElementById('environment').textContent = '未配置 API';
  const message = document.getElementById('config-message');
  message.hidden = false;
  message.textContent = '尚未配置生产 API。请在外部 config.js 填写真实 HTTPS 地址后重新打开。';
}
if (endpoint) mountAdminApp({ document, window, api: createApiClient({ endpoint }), environment: endpoint });
