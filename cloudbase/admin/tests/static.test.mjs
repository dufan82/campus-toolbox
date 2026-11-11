import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';

const root = new URL('../', import.meta.url);
const read = (path) => readFile(new URL(path, root), 'utf8');

test('CSP permits only external scripts/styles; no inline handlers, CDN, HTML rendering or token storage', async () => {
  const html = await read('index.html');
  assert.match(html, /Content-Security-Policy/);
  for (const directive of ["default-src 'none'", "script-src 'self'", "style-src 'self'", "base-uri 'none'", "form-action 'none'"]) {
    assert.ok(html.includes(directive));
  }
  assert.doesNotMatch(html, /unsafe-inline|unsafe-eval|\son\w+=|\sstyle=|<style|https?:\/\//i);
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
  assert.ok(scripts.length >= 2);
  for (const [, attrs, content] of scripts) { assert.match(attrs, /src=/); assert.equal(content.trim(), ''); }
  for (const name of (await readdir(root)).filter((name) => name.endsWith('.js'))) {
    const source = await read(name);
    assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\s*\(|new Function/);
    assert.doesNotMatch(source, /localStorage|sessionStorage|indexedDB|document\.cookie|console\.|setInterval/);
  }
});

test('production configuration stays empty and assets/fonts are local', async () => {
  assert.match(await read('config.js'), /apiUrl:\s*''/);
  assert.doesNotMatch(await read('config.js'), /https?:\/\//);
  const css = await read('styles.css');
  assert.doesNotMatch(css, /@import|https?:\/\//);
  for (const color of ['#172B49', '#35B6A0', '#F2A65A', '#F5F7FA']) assert.ok(css.includes(color));
  assert.match(css, /font-family:\s*system-ui/);
});

test('contract action names, page size and confirmation UI remain present', async () => {
  const js = await read('app.js');
  for (const action of ['getPendingStories', 'getSubmissionDetail', 'approveStory', 'rejectStory', 'deletePublishedStory']) {
    assert.ok(js.includes(`'${action}'`));
  }
  assert.match(js, /limit: 20/);
  const html = await read('index.html');
  assert.match(html, /id="confirm-dialog"/);
  assert.match(html, /id="review-note"[^>]*maxlength="500"/);
  assert.doesNotMatch(html, /mock-admin-password|value="admin"/);
});

test('read-only Mock smoke never overwrites mutation-workflow detail/confirmation evidence', async () => {
  const smoke = await read('tests/smoke_mock.py');
  assert.ok(smoke.includes("capture('mock-smoke-detail-desktop.png')"));
  assert.ok(smoke.includes("capture('mock-smoke-confirm-desktop.png')"));
  assert.doesNotMatch(smoke, /capture\('mock-(?:detail|confirm)-desktop\.png'\)/);
});
