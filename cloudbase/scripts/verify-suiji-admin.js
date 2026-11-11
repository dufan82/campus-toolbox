'use strict';
// Local regression only: no staging, initialization, deploy tools, real env/config or cloud calls.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'verification', 'suiji-admin-20261111');
fs.mkdirSync(out, { recursive: true });
const commands = [];
function run(name, program, args, environment = {}) {
  const startedAt = new Date().toISOString();
  const result = spawnSync(program, args, { cwd: root, encoding: 'utf8', timeout: 120000, env: { ...process.env, PYTHONIOENCODING: 'utf-8', ...environment } });
  const raw = (result.stdout || '') + (result.stderr || '') + (result.error ? String(result.error) : '');
  fs.writeFileSync(path.join(out, name), raw);
  commands.push({ command: [program, ...args], cwd: root, startedAt, endedAt: new Date().toISOString(), exitCode: result.status, log: name });
  console.log(`${name}: exit=${result.status}`);
}
const guard = path.join(root, 'test', 'offline-network-guard.cjs').replaceAll('\\', '/');
const env = { NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require "${guard}"`.trim() };
run('final-backend.tap', process.execPath, ['scripts/run-tests.js'], env);
run('final-admin.tap', process.execPath, ['--test','admin/tests/api.test.mjs','admin/tests/static.test.mjs','admin/tests/editorial.test.mjs'], env);
run('final-browser-regression.txt', 'python', ['admin/tests/test_browser.py'], env);
run('final-editorial-browser.txt', 'python', ['admin/tests/test_editorial_browser.py'], env);
fs.writeFileSync(path.join(out, 'commands.json'), JSON.stringify({ node: process.version, commands, externalNodeNetwork: 'blocked by test-only preloader', editorialBrowserNetwork: 'all non-loopback routes aborted', realCloudCalled: false, deployed: false }, null, 2) + '\n');
if (commands.some(c => c.exitCode !== 0)) process.exitCode = 1;
