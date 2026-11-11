// Contract transport. No persistent storage, client UID, credentials log or retry.
const ADMIN_ACTIONS = new Set(['getPendingStories', 'getSubmissionDetail', 'approveStory', 'rejectStory', 'deletePublishedStory', 'createEditorialStory', 'updateManagedStory']);
const MAX_BYTES = 256 * 1024;
const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]']);

export class ApiError extends Error {
  constructor(code, status = 0) {
    super(code);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

export function resolveEndpoint(config = {}, location) {
  const local = ['http:', 'https:'].includes(location.protocol) && LOOPBACK.has(location.hostname);
  const value = config.apiUrl;
  if ((value === undefined || value === '') && local) return { url: '/api', isMock: true };
  const localCloud = local && config.localCloud === true;
  if ((location.protocol !== 'https:' && !localCloud) || typeof value !== 'string' || !value) throw new ApiError('CONFIG_REQUIRED');
  let url;
  try { url = new URL(value); } catch { throw new ApiError('CONFIG_REQUIRED'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || LOOPBACK.has(url.hostname)) {
    throw new ApiError('CONFIG_REQUIRED');
  }
  return { url: url.href, isMock: false, ...(localCloud ? { isLocalCloud: true } : {}) };
}

async function readEnvelope(response) {
  if (Number(response.headers.get('content-length')) > MAX_BYTES) throw new ApiError('RESPONSE_TOO_LARGE');
  const reader = response.body?.getReader();
  if (!reader) throw new ApiError('INVALID_RESPONSE');
  let size = 0;
  const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BYTES) throw new ApiError('RESPONSE_TOO_LARGE');
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  let envelope;
  try { envelope = JSON.parse(new TextDecoder().decode(bytes)); } catch { throw new ApiError('INVALID_RESPONSE'); }
  if (!envelope || typeof envelope.ok !== 'boolean') throw new ApiError('INVALID_RESPONSE');
  return envelope;
}

export function createApiClient({ endpoint, fetchImpl = globalThis.fetch, now = Date.now, timeoutMs = 15000, onUnauthorized = () => {} }) {
  let token = '', expiresAt = 0, generation = 0;
  const pending = new Set();
  let unauthorized = onUnauthorized;

  function clearSession() {
    token = '';
    expiresAt = 0;
    generation++;
    for (const controller of pending) controller.abort();
    pending.clear();
  }

  async function request(action, payload, authenticated) {
    if (authenticated && (!token || now() >= expiresAt)) {
      clearSession();
      unauthorized(401);
      throw new ApiError('UNAUTHENTICATED', 401);
    }
    const version = generation;
    const controller = new AbortController();
    pending.add(controller);
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    try {
      const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
      if (authenticated) headers.Authorization = `Bearer ${token}`;
      const response = await fetchImpl(endpoint.url, {
        method: 'POST', headers, body: JSON.stringify({ action, payload }),
        credentials: 'omit', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', signal: controller.signal
      });
      if (version !== generation) throw new ApiError('CANCELLED');
      if (response.status === 401 || response.status === 403) {
        clearSession();
        if (authenticated) unauthorized(response.status);
        throw new ApiError(response.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', response.status);
      }
      const envelope = await readEnvelope(response);
      if (version !== generation) throw new ApiError('CANCELLED');
      if (!response.ok || !envelope.ok) {
        const code = envelope.error?.code;
        throw new ApiError(typeof code === 'string' && /^[A-Z0-9_]{1,64}$/.test(code) ? code : 'REQUEST_FAILED', response.status);
      }
      if (!envelope.data || typeof envelope.data !== 'object' || Array.isArray(envelope.data)) throw new ApiError('INVALID_RESPONSE');
      return envelope.data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (version !== generation) throw new ApiError('CANCELLED');
      throw new ApiError(timedOut ? 'TIMEOUT' : 'NETWORK_ERROR');
    } finally {
      clearTimeout(timer);
      pending.delete(controller);
    }
  }

  return Object.freeze({
    async login(username, password) {
      clearSession();
      const data = await request('adminLogin', { username, password }, false);
      if (typeof data.accessToken !== 'string' || !/^[\x21-\x7E]{1,8192}$/.test(data.accessToken) ||
          !Number.isFinite(data.expiresIn) || data.expiresIn <= 0 || data.expiresIn > 2147483) {
        throw new ApiError('INVALID_RESPONSE');
      }
      token = data.accessToken;
      expiresAt = now() + data.expiresIn * 1000;
    },
    call(action, payload = {}) {
      if (!ADMIN_ACTIONS.has(action)) return Promise.reject(new ApiError('INVALID_ACTION'));
      return request(action, payload, true);
    },
    hasSession: () => Boolean(token) && now() < expiresAt,
    clearSession,
    setUnauthorizedHandler(handler) { unauthorized = handler; }
  });
}
