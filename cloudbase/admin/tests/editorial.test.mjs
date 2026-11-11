import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { EDIT_FIELDS, fillEditor, readEditor } from '../editor.js';
function form() {
  const inputs = Object.fromEntries(EDIT_FIELDS.map(key => ['edit-' + key, { value: '', checked: false }]));
  return { getElementById: id => inputs[id], inputs };
}
test('admin editor accepts title120/body20000 codepoints and rejects boundary+1', () => {
  for (const unit of ['文','😀']) {
    const doc = form(), record = { title: unit.repeat(120), content: unit.repeat(20000), sourceUrl: 'https://www.douyin.com/video/123' };
    fillEditor(doc, record);
    assert.equal(readEditor(doc).title, record.title); assert.equal(readEditor(doc).content, record.content);
    doc.inputs['edit-title'].value += unit; assert.throws(() => readEditor(doc), /EDITOR_VALIDATION/);
    fillEditor(doc, record); doc.inputs['edit-content'].value += unit;
    assert.throws(() => readEditor(doc), /EDITOR_VALIDATION/);
  }
});
test('admin form maxlength and visible validation match expanded management limits', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  const js = await readFile(new URL('../app.js', import.meta.url), 'utf8');
  assert.match(html, /id="edit-title"[^>]*maxlength="120"/);
  assert.match(html, /id="edit-content"[^>]*maxlength="20000"/);
  assert.match(html, /最多20000码点/);
  assert.match(js, /标题2–120码点、正文最多20000/);
});
test('form keeps publisher and comment author separate; Unicode excerpt and signed rank', () => {
  const doc = form();
  fillEditor(doc, { title: '随记', authorName: '评论者', publisherName: '发布者', sourceUrl: 'https://www.douyin.com/video/123', excerpt: '😀'.repeat(80), editorialRank: -7 });
  const p = readEditor(doc); assert.equal(p.publisherName, '发布者'); assert.equal(p.authorName, '评论者');
  assert.equal(p.rightsStatus, 'unverified'); assert.equal(p.editorialRank, -7);
  doc.inputs['edit-excerpt'].value += '😀'; assert.throws(() => readEditor(doc), /EDITOR_VALIDATION/);
});
test('all source/rights fields and pre-publication confirmation are wired without inline code', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  const js = await readFile(new URL('../app.js', import.meta.url), 'utf8');
  for (const key of EDIT_FIELDS) assert.ok(html.includes(`id="edit-${key}"`), key);
  for (const action of ['createEditorialStory','updateManagedStory']) assert.ok(js.includes(`'${action}'`));
  assert.match(js, /confirmPublic/); assert.match(js, /editorDirty/);
  assert.doesNotMatch(html + js, /小故事|公开故事|故事正文/);
});
