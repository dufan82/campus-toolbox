'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'verification', 'story-cloud-20261110', 'backend');
fs.mkdirSync(out, { recursive: true });
const records = [];
function run(name, args, expectedExit = 0) {
  const start = new Date().toISOString(); const r = spawnSync(process.execPath, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
  fs.writeFileSync(path.join(out, name), (r.stdout || '') + (r.stderr || ''));
  records.push({ command: 'node ' + args.join(' '), startedAt: start, endedAt: new Date().toISOString(), exitCode: r.status, expectedExit, log: name, passed: r.status === expectedExit });
}
run('20-final-tests.tap', ['--test','--test-reporter=tap', ...fs.readdirSync(path.join(root, 'test')).filter(s => s.endsWith('.test.js')).map(s => 'test/' + s)]);
run('21-init-plan.json', ['scripts/init-db.js','--plan']);
// Use a deliberately empty child environment to prove production config cannot fall back.
const refuse = spawnSync(process.execPath, ['-e', "require('./index').createProductionHandler({})({httpMethod:'POST',body:'{}'}).then(r=>{console.log(r.statusCode,JSON.parse(r.body).error.code);process.exit(r.statusCode===503&&JSON.parse(r.body).error.code==='NOT_CONFIGURED'?0:1)})"], { cwd: root, encoding: 'utf8' });
fs.writeFileSync(path.join(out, '22-empty-production.txt'), (refuse.stdout || '') + (refuse.stderr || ''));
records.push({ command: 'node -e createProductionHandler({}) unconfigured assertion', exitCode: refuse.status, expectedExit: 0, log: '22-empty-production.txt', passed: refuse.status === 0 });
run('23-stage-manifest.json', ['scripts/stage-production.js']);
const hashes = {};
function snapshot(dir) { for (const ent of fs.readdirSync(dir, { withFileTypes: true })) { if (['node_modules','build','admin','verification','data'].includes(ent.name)) continue; const f = path.join(dir, ent.name); if (ent.isDirectory()) snapshot(f); else if (!ent.name.startsWith('.env') || ent.name === '.env.example') hashes[path.relative(root, f).replaceAll('\\','/')] = createHash('sha256').update(fs.readFileSync(f)).digest('hex'); } }
snapshot(root);
fs.writeFileSync(path.join(out, 'source-sha256.json'), JSON.stringify(hashes, null, 2) + '\n');
fs.writeFileSync(path.join(out, 'commands.json'), JSON.stringify({ at: new Date().toISOString(), node: process.version, sdk: require('@cloudbase/node-sdk/package.json').version, commands: records, deploymentPerformed: false }, null, 2) + '\n');
console.log(JSON.stringify(records, null, 2));
if (records.some(r => !r.passed)) process.exitCode = 1;
