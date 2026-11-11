'use strict';
// Enumerate explicitly: cmd.exe/PowerShell and older Node versions do not expand test globs alike.
const fs = require('node:fs'); const path = require('node:path'); const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const files = fs.readdirSync(path.join(root, 'test')).filter(name => name.endsWith('.test.js')).sort().map(name => 'test/' + name);
const r = spawnSync(process.execPath, ['--test', ...files], { cwd: root, stdio: 'inherit' });
process.exitCode = r.status ?? 1;
