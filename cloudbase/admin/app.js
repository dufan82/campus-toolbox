import { ApiError } from './api.js';
import { fillEditor, readEditor } from './editor.js';

const CATEGORIES = { LIFE: '人生', GROWTH: '成长', WARMTH: '温暖', TWIST: '反转', OTHER: '其他' };
const STATUSES = { pending: '待审核', approved: '已通过', rejected: '已拒绝' };
const OPERATIONS = {
  approve: { action: 'approveStory', title: '通过并发布这篇随记？', description: '确认审核通过。隐藏作品不公开；未授权仅公开独立介绍/必要摘录和来源链接。请核对正文、作者与当前授权。', button: '确认通过并发布' },
  reject: { action: 'rejectStory', title: '拒绝这篇投稿？', description: '确认后投稿不会公开。内部备注不会向投稿者或公开阅读端展示。', button: '确认拒绝' },
  withdraw: { action: 'deletePublishedStory', title: '撤下这篇公开随记？', description: '确认后公开端将不可读取该随记。原投稿及审核记录保留，撤下后不能重新发布。', button: '确认撤下' },
  save: { action: 'updateManagedStory', title: '确认修改公开随记？', description: '请确认授权对应当前正文及来源。仅已授权、公有领域、用户授权允许公开全文；其他状态只公开介绍/必要摘录及原文链接。隐藏后不可见，撤销授权后服务端立即清空公开全文。', button: '确认保存公开修改' }
};

function errorText(error, login = false) {
  if (error.code === 'UNKNOWN_ACTION') return '当前服务仍是旧版，尚不支持精选管理。输入已保留，本轮未部署云端。';
  if (error.message === 'EDITOR_VALIDATION' || error.status === 400) return '请核对标题2–120码点、正文最多20000、介绍最多240、摘录最多80，以及HTTPS原文链接和整数排序。';
  if (error.status === 401) return login ? '账号或密码不正确，请重新输入。' : '登录已失效，请重新登录。';
  if (error.status === 403) return '当前账号没有管理员权限，请联系管理员核对服务端 UID 授权。';
  if (error.status === 409) return '投稿状态已变化，请关闭详情并刷新列表后再查看。';
  if (error.status === 429) return '操作过于频繁，请稍后手动重试。';
  if (error.status === 404) return '该投稿已不存在，请关闭详情并刷新列表。';
  if (error.code === 'TIMEOUT') return '请求超时，结果尚未确认。请关闭详情并刷新列表核对，勿重复提交。';
  if (error.code === 'NETWORK_ERROR') return '连接失败，操作结果尚未确认。请检查连接并手动刷新核对。';
  return '服务响应异常，请手动刷新核对；本页未自动重试。';
}

function dateText(value) {
  if (value === null || value === undefined || value === '') return '未记录';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '时间格式异常';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date);
}

function checkRecord(record) {
  if (!record || typeof record.id !== 'string' || !record.id || typeof record.title !== 'string' ||
      typeof record.authorName !== 'string' || !Object.hasOwn(STATUSES, record.status) ||
      !Object.hasOwn(CATEGORIES, record.category) || typeof record.published !== 'boolean') throw new ApiError('INVALID_RESPONSE');
  return record;
}

function checkPage(data) {
  if (!Array.isArray(data.items) || data.items.length > 20 ||
      !(data.nextCursor === null || (typeof data.nextCursor === 'string' && data.nextCursor.length > 0 && data.nextCursor.length <= 2048)) ||
      !data.counts || !Object.keys(STATUSES).every((key) => Number.isSafeInteger(data.counts[key]) && data.counts[key] >= 0)) {
    throw new ApiError('INVALID_RESPONSE');
  }
  data.items.forEach(checkRecord);
  if (new Set(data.items.map((item) => item.id)).size !== data.items.length) throw new ApiError('INVALID_RESPONSE');
  return data;
}

// Injection boundary for local tests: api is the contract client, never a UID/role flag.
export function mountAdminApp({ document: doc, window: win, api, environment }) {
  const el = (id) => doc.getElementById(id);
  const filters = [...doc.querySelectorAll('[data-status]')];
  const detailDialog = el('detail-dialog'), confirmDialog = el('confirm-dialog');
  let epoch = 0, listTicket = 0, detailTicket = 0;
  let status = 'pending', cursors = [null], pageIndex = 0, nextCursor = null;
  let selected = null, confirmation = null, busyList = false, busyMutation = false, busyLogin = false;
  let editorDirty = false;
  const listeners = [];
  function on(target, type, handler) {
    target.addEventListener(type, handler);
    listeners.push(() => target.removeEventListener(type, handler));
  }
  function text(id, value) { el(id).textContent = value; }
  function node(tag, value, className) {
    const item = doc.createElement(tag);
    if (value !== undefined) item.textContent = value;
    if (className) item.className = className;
    return item;
  }

  text('environment', environment.isMock ? '本地Mock' : environment.isLocalCloud ? '本机审核 · 真实云端' : '已配置 API');
  el('environment').classList.toggle('mock', environment.isMock);
  for (const badge of doc.querySelectorAll('[data-mock-badge]')) badge.hidden = !environment.isMock;
  if (environment.isMock) {
    el('config-message').hidden = false;
    text('config-message', '本地Mock：仅连接本机测试服务，数据不会发布到云端。');
  }
  el('login-submit').disabled = false;

  function closeConfirmation() {
    if (confirmDialog.open) confirmDialog.close();
    confirmation = null;
    el('review-note').value = '';
    text('confirm-message', '');
    text('confirm-story', '');
    text('note-count', '0 / 500');
  }

  function clearDetail() {
    detailTicket++;
    selected = null;
    editorDirty = false;
    text('detail-title', '正在读取…');
    text('detail-message', '');
    el('detail-message').classList.remove('error');
    text('story-content', '');
    text('reviewer-note', '');
    text('editor-message', '');
    el('managed-editor').hidden = true;
    fillEditor(doc, {});
    el('detail-meta').replaceChildren();
    el('detail-body').hidden = true;
    for (const id of ['approve', 'reject', 'withdraw']) el(id).hidden = true;
  }

  function closeDetail() {
    closeConfirmation();
    if (detailDialog.open) detailDialog.close();
    clearDetail();
  }

  function reset(message = '') {
    epoch++;
    listTicket++;
    api.clearSession();
    busyLogin = false;
    busyList = false;
    busyMutation = false;
    closeDetail();
    el('workspace').hidden = true;
    el('login-view').hidden = false;
    el('logout').hidden = true;
    el('username').value = '';
    el('password').value = '';
    el('login-submit').disabled = false;
    text('login-submit', '登录');
    text('login-message', message);
    el('story-rows').replaceChildren();
    for (const key of Object.keys(STATUSES)) text(`count-${key}`, '—');
    text('workspace-message', '');
    status = 'pending'; cursors = [null]; pageIndex = 0; nextCursor = null;
    setListControls();
    el('confirm-submit').disabled = false;
    el('confirm-cancel').disabled = false;
    el('detail-close').disabled = false;
    el('save').disabled = false;
  }

  api.setUnauthorizedHandler((httpStatus) => {
    reset(errorText({ status: httpStatus }));
    el('username').focus();
  });

  function setListControls() {
    el('create-editorial').disabled = busyList || busyMutation;
    for (const filter of filters) {
      const active = filter.dataset.status === status;
      filter.classList.toggle('active', active);
      filter.setAttribute('aria-pressed', String(active));
      filter.disabled = busyList;
    }
    el('refresh').disabled = busyList;
    el('previous-page').disabled = busyList || pageIndex === 0;
    el('next-page').disabled = busyList || !nextCursor;
    el('story-rows').setAttribute('aria-busy', String(busyList));
    text('page-info', `第 ${pageIndex + 1} 页`);
  }

  function renderRows(items) {
    const fragment = doc.createDocumentFragment();
    for (const item of items) {
      const tr = node('tr');
      tr.append(node('td', item.title, 'title-cell'), node('td', CATEGORIES[item.category]), node('td', item.authorName || '未署名', 'author-cell'));
      const statusCell = node('td');
      statusCell.append(node('span', item.hidden && item.published ? '已隐藏' : item.status === 'pending' && item.recordType === 'editorial' ? '精选草稿' : item.status === 'approved' && !item.published ? '已撤下' : STATUSES[item.status],
        `status-badge ${item.status === 'approved' && !item.published ? 'withdrawn' : item.status}`));
      tr.append(statusCell, node('td', dateText(item.createdAt), 'date-cell'));
      const actionCell = node('td');
      const button = node('button', '查看', 'row-action');
      button.type = 'button';
      button.setAttribute('aria-label', `查看投稿：${item.title}`);
      // This listener belongs to the row; removing rows removes its only reference.
      button.addEventListener('click', () => { if (!busyList) openDetail(item.id); });
      actionCell.append(button); tr.append(actionCell); fragment.append(tr);
    }
    el('story-rows').replaceChildren(fragment);
  }

  async function loadList(targetIndex = 0, successMessage = '') {
    const version = epoch, ticket = ++listTicket;
    busyList = true;
    nextCursor = null;
    el('empty-state').hidden = true;
    el('story-rows').replaceChildren();
    text('workspace-message', '正在读取投稿…');
    setListControls();
    try {
      const payload = { status, limit: 20 };
      if (cursors[targetIndex]) payload.cursor = cursors[targetIndex];
      const data = checkPage(await api.call('getPendingStories', payload));
      if (version !== epoch || ticket !== listTicket) return;
      if (data.nextCursor && cursors.slice(0, targetIndex + 1).includes(data.nextCursor)) throw new ApiError('INVALID_RESPONSE');
      pageIndex = targetIndex;
      nextCursor = data.nextCursor;
      renderRows(data.items);
      for (const key of Object.keys(STATUSES)) text(`count-${key}`, String(data.counts[key]));
      text('list-summary', `本页 ${data.items.length} 条 · 每页 20 条`);
      text('workspace-message', successMessage);
      el('empty-state').hidden = data.items.length !== 0;
    } catch (error) {
      if (version === epoch && ticket === listTicket) {
        text('workspace-message', `${successMessage ? `${successMessage} ` : ''}${errorText(error)}`);
        text('list-summary', '列表未更新');
      }
    } finally {
      if (version === epoch && ticket === listTicket) { busyList = false; setListControls(); }
    }
  }

  async function openDetail(id) {
    clearDetail();
    const ticket = detailTicket, version = epoch;
    if (!detailDialog.open) detailDialog.showModal();
    text('detail-message', '正在读取详情…');
    try {
      const data = checkRecord(await api.call('getSubmissionDetail', { id }));
      if (version !== epoch || ticket !== detailTicket) return;
      if (data.id !== id || typeof data.content !== 'string' ||
          !(data.reviewerNote === null || data.reviewerNote === undefined || typeof data.reviewerNote === 'string')) throw new ApiError('INVALID_RESPONSE');
      selected = data;
      text('detail-title', data.title);
      const fields = [
        ['投稿编号', data.id], ['状态', data.status === 'approved' && !data.published ? '已通过 · 已撤下' : STATUSES[data.status]],
        ['分类', CATEGORIES[data.category]], ['署名', data.authorName || '未署名'],
        ['投稿时间', dateText(data.createdAt)], ['更新时间', dateText(data.updatedAt)],
        ['同意时间', dateText(data.consentAt)], ['同意版本', typeof data.consentVersion === 'string' ? data.consentVersion : '未记录'],
        ['审核时间', dateText(data.reviewedAt)], ['公开状态', data.hidden && data.published ? '已隐藏' : data.published ? '公开中' : '未公开'],
        ['来源类型', data.sourceType || '用户投稿'], ['原文链接', data.sourceUrl || '无外部链接'],
        ['作品发布者', data.publisherName || '未记录'], ['来源作品标题', data.sourceWorkTitle || '未记录'],
        ['评论定位', data.commentLocator || '未记录'], ['授权状态', data.rightsStatus || '旧版投稿同意'],
        ['云端排序', String(data.editorialRank ?? 0)], ['授权备注（仅管理员）', data.rightsNote || '未记录']
      ];
      el('detail-meta').replaceChildren(...fields.flatMap(([key, value]) => [node('dt', key), node('dd', value)]));
      text('story-content', data.content);
      text('reviewer-note', data.reviewerNote || '暂无内部备注');
      if (Number.isSafeInteger(data.revision) && !data.withdrawn && data.status !== 'rejected' && !(data.status === 'approved' && !data.published)) {
        fillEditor(doc, data); el('managed-editor').hidden = false;
      }
      el('detail-body').hidden = false;
      text('detail-message', '时间均为北京时间（UTC+8）。正文按纯文本展示。');
      el('approve').hidden = data.status !== 'pending';
      el('reject').hidden = data.status !== 'pending';
      el('withdraw').hidden = data.status !== 'approved' || !data.published;
      el('detail-title').focus();
    } catch (error) {
      if (version === epoch && ticket === detailTicket) {
        el('detail-message').classList.add('error');
        text('detail-message', errorText(error));
      }
    }
  }

  function askConfirmation(kind) {
    if (!selected || busyMutation) return;
    if (kind === 'approve' && editorDirty) { text('editor-message', '请先保存修改，再确认发布。'); return; }
    if (kind === 'withdraw' || kind === 'save' ? selected.status !== 'approved' || !selected.published : selected.status !== 'pending') return;
    confirmation = { kind, id: selected.id };
    if (kind === 'save') {
      try { confirmation.patch = readEditor(doc); } catch (error) { text('editor-message', errorText(error)); return; }
    }
    const operation = OPERATIONS[kind];
    text('confirm-title', operation.title);
    text('confirm-description', operation.description);
    text('confirm-story', selected.title);
    text('confirm-submit', operation.button);
    text('confirm-message', '');
    el('note-field').hidden = kind === 'approve' || kind === 'save';
    el('review-note').value = '';
    text('note-count', '0 / 500');
    el('confirm-submit').classList.toggle('danger', kind !== 'approve');
    confirmDialog.showModal();
    el('confirm-cancel').focus();
  }

  async function confirmOperation() {
    if (busyMutation || !confirmation || !selected || confirmation.id !== selected.id) return;
    const { kind, id } = confirmation, version = epoch;
    if (kind === 'save') { await saveEditor(confirmation.patch, true); return; }
    const note = el('review-note').value.trim();
    if (note.length > 500) { text('confirm-message', '内部备注最多 500 字。'); return; }
    busyMutation = true;
    el('confirm-submit').disabled = true;
    el('confirm-cancel').disabled = true;
    el('detail-close').disabled = true;
    text('confirm-message', '正在提交，请勿重复操作…');
    try {
      const payload = { id };
      if (kind === 'approve' && Number.isSafeInteger(selected.revision)) payload.revision = selected.revision;
      if (kind !== 'approve' && note) payload.note = note;
      const data = await api.call(OPERATIONS[kind].action, payload);
      if (version !== epoch) return;
      if (data.id !== id || (kind === 'withdraw' ? data.withdrawn !== true : data.status !== (kind === 'approve' ? 'approved' : 'rejected')) ||
          (kind === 'approve' && typeof data.publishedStoryId !== 'string')) throw new ApiError('INVALID_RESPONSE');
      closeDetail();
      cursors = [null]; pageIndex = 0;
      const message = { approve: '已通过并发布。', reject: '已拒绝，内部备注已保存。', withdraw: '已撤下，公开端不再展示该随记。' }[kind];
      await loadList(0, message);
      el('workspace-title').focus();
    } catch (error) {
      if (version === epoch) text('confirm-message', errorText(error));
    } finally {
      if (version === epoch) {
        busyMutation = false;
        el('confirm-submit').disabled = false;
        el('confirm-cancel').disabled = false;
        el('detail-close').disabled = false;
      }
    }
  }

  async function saveEditor(patch, confirmPublic = false) {
    if (!selected || busyMutation) return;
    const version = epoch, record = selected;
    busyMutation = true;
    for (const id of ['save','confirm-submit','confirm-cancel','detail-close']) el(id).disabled = true;
    text('editor-message', '正在保存，请勿重复操作…');
    try {
      const action = record.isNew ? 'createEditorialStory' : 'updateManagedStory';
      const payload = record.isNew ? { editorialId: record.editorialId, ...patch } : { id: record.id, revision: record.revision, patch, confirmPublic };
      const result = await api.call(action, payload);
      if (version !== epoch) return;
      if (typeof result.id !== 'string' || (!record.isNew && result.id !== record.id)) throw new ApiError('INVALID_RESPONSE');
      closeConfirmation();
      await openDetail(result.id);
      cursors = [null]; pageIndex = 0;
      await loadList(0, record.isNew ? '精选草稿已保存；核对详情后点击通过并发布。' : '修改已保存，公开内容由服务端按当前授权重算。');
    } catch (error) {
      if (version === epoch) { text('editor-message', errorText(error)); text('confirm-message', errorText(error)); }
    } finally {
      if (version === epoch) {
        busyMutation = false;
        for (const id of ['save','confirm-submit','confirm-cancel','detail-close']) el(id).disabled = false;
        setListControls();
      }
    }
  }

  on(el('login-form'), 'submit', async (event) => {
    event.preventDefault();
    if (busyLogin) return;
    busyLogin = true;
    const version = epoch;
    const username = el('username').value.trim();
    let password = el('password').value;
    el('password').value = '';
    el('login-submit').disabled = true;
    text('login-message', ''); text('login-submit', '正在登录…');
    try {
      const result = api.login(username, password);
      password = '';
      await result;
      if (version !== epoch) return;
      el('login-view').hidden = true;
      el('workspace').hidden = false;
      el('logout').hidden = false;
      el('username').value = '';
      el('workspace-title').focus();
      await loadList();
    } catch (error) {
      if (version === epoch) text('login-message', errorText(error, true));
    } finally {
      password = '';
      if (version === epoch) { busyLogin = false; el('login-submit').disabled = false; text('login-submit', '登录'); }
    }
  });
  on(el('logout'), 'click', () => { reset('已退出登录。'); el('username').focus(); });
  on(el('refresh'), 'click', () => { cursors = [null]; pageIndex = 0; loadList(); });
  for (const filter of filters) on(filter, 'click', () => {
    status = filter.dataset.status; cursors = [null]; pageIndex = 0; loadList();
  });
  on(el('previous-page'), 'click', () => { if (pageIndex > 0 && !busyList) loadList(pageIndex - 1); });
  on(el('next-page'), 'click', () => {
    if (!nextCursor || busyList) return;
    cursors = cursors.slice(0, pageIndex + 1); cursors.push(nextCursor); loadList(pageIndex + 1);
  });
  on(el('detail-close'), 'click', () => { if (!busyMutation) closeDetail(); });
  on(detailDialog, 'cancel', (event) => { event.preventDefault(); if (!busyMutation) closeDetail(); });
  for (const kind of ['approve','reject','withdraw']) on(el(kind), 'click', () => askConfirmation(kind));
  on(el('editor-form'), 'submit', event => {
    event.preventDefault(); if (!selected || busyMutation) return;
    if (selected.published) { askConfirmation('save'); return; }
    try { saveEditor(readEditor(doc)); } catch (error) { text('editor-message', errorText(error)); }
  });
  on(el('editor-form'), 'input', () => { editorDirty = true; });
  on(el('create-editorial'), 'click', () => {
    if (busyMutation) return;
    clearDetail();
    selected = { isNew: true, editorialId: win.crypto.randomUUID(), status: 'pending', published: false, recordType: 'editorial' };
    text('detail-title', '新增随记精选');
    text('detail-message', '仅保存管理员草稿，不自动发布。请使用原文链接，不粘贴整段分享口令。');
    fillEditor(doc, selected); el('managed-editor').hidden = false;
    detailDialog.showModal(); el('edit-title').focus();
  });
  on(el('confirm-cancel'), 'click', () => { if (!busyMutation) closeConfirmation(); });
  on(confirmDialog, 'cancel', (event) => { event.preventDefault(); if (!busyMutation) closeConfirmation(); });
  on(el('confirm-submit'), 'click', confirmOperation);
  on(el('review-note'), 'input', () => text('note-count', `${el('review-note').value.length} / 500`));
  // pagehide covers BFCache too; restored pages stay logged out, without refetching.
  on(win, 'pagehide', () => reset());
  on(win, 'beforeunload', () => reset());
  setListControls();
  return { destroy() { reset(); api.setUnauthorizedHandler(() => {}); listeners.splice(0).forEach((remove) => remove()); } };
}
