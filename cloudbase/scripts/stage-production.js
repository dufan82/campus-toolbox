'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
async function stage(root = path.resolve(__dirname, '..'), output = path.join(root, 'build', 'production')) {
  const absolute = path.resolve(output);
  if (!absolute.startsWith(path.join(root, 'build') + path.sep)) throw new Error('Production staging must stay under cloudbase/build');
  await fs.mkdir(absolute, { recursive: true });
  const allowed = ['index.js','http-server.js','scf_bootstrap','package.json','package-lock.json','src'];
  const existing = await fs.readdir(absolute);
  if (existing.some(name => !allowed.includes(name) && name !== 'node_modules')) throw new Error('Staging has unexpected files; preserve and inspect before packaging');
  for (const name of allowed) await fs.cp(path.join(root, name), path.join(absolute, name), { recursive: true });
  const packageFile = path.join(absolute, 'package.json'); const pkg = JSON.parse(await fs.readFile(packageFile, 'utf8'));
  pkg.scripts = { start: 'node http-server.js' }; pkg.description = 'Campus story review API'; await fs.writeFile(packageFile, JSON.stringify(pkg, null, 2) + '\n');
  await fs.chmod(path.join(absolute, 'scf_bootstrap'), 0o755);
  const manifest = [];
  async function inspect(dir) {
    for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
      if (entry.name === 'node_modules') continue;
      const filename = path.join(dir, entry.name);
      if (entry.isDirectory()) await inspect(filename);
      else manifest.push({ file: path.relative(absolute, filename).replaceAll('\\', '/'), sha256: createHash('sha256').update(await fs.readFile(filename)).digest('hex') });
    }
  }
  await inspect(absolute); return { directory: absolute, files: manifest };
}
if (require.main === module) stage().then(r => console.log(JSON.stringify(r, null, 2))).catch(() => { console.error('Production staging failed; no deploy attempted.'); process.exitCode = 1; });
module.exports = { stage };
