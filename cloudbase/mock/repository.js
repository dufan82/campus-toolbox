'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const COLLECTIONS = ['story_submissions','published_stories','story_rate_limits','story_audit_log','story_stats'];
class FileRepository {
  static async open(file) {
    file = path.resolve(file); await fs.mkdir(path.dirname(file), { recursive: true });
    let lock;
    try { lock = await fs.open(file + '.lock', 'wx', 0o600); } catch (e) { if (e.code === 'EEXIST') throw new Error('Mock database locked by another writer; inspect stale lock after crash'); throw e; }
    try {
      let state;
      try { state = JSON.parse(await fs.readFile(file, 'utf8')); }
      catch (e) { if (e.code !== 'ENOENT') throw new Error('Mock database corrupt; original preserved'); }
      if (state && (state.schema !== 1 || COLLECTIONS.some(c => !state[c] || typeof state[c] !== 'object' || Array.isArray(state[c])))) throw new Error('Mock database schema invalid; original preserved');
      const fresh = !state;
      state ||= { schema: 1, ...Object.fromEntries(COLLECTIONS.map(c => [c, {}])) };
      if (fresh) state.story_stats.counts = { pending: 0, approved: 0, rejected: 0 };
      const repo = new FileRepository(file, state, lock); if (fresh) await repo.persist(state); return repo;
    } catch (e) { await lock.close(); await fs.unlink(file + '.lock'); throw e; }
  }
  constructor(file, state, lock) { this.file = file; this.state = state; this.lock = lock; this.tail = Promise.resolve(); this.closed = false; }
  async persist(state) {
    const temp = this.file + '.tmp'; const handle = await fs.open(temp, 'w', 0o600);
    try { await handle.writeFile(JSON.stringify(state)); await handle.sync(); } finally { await handle.close(); }
    await fs.rename(temp, this.file);
  }
  transaction(fn) {
    const job = this.tail.then(async () => {
      if (this.closed) throw new Error('Repository closed');
      const next = structuredClone(this.state);
      const tx = { get: async (c, id) => structuredClone(next[c][id] || null), set: async (c, id, data) => { next[c][id] = structuredClone(data); }, remove: async (c, id) => { delete next[c][id]; } };
      const result = await fn(tx); await this.persist(next); this.state = next; return result;
    });
    this.tail = job.catch(() => {}); return job;
  }
  async get(c, id) { await this.tail; return structuredClone(this.state[c][id] || null); }
  async query(c, { equal = {}, before, limit = 1000 } = {}) {
    await this.tail;
    return structuredClone(Object.values(this.state[c]).filter(row => Object.entries(equal).every(([k, v]) => row[k] === v) && (!before || row.sortKey < before)).sort((a, b) => a.sortKey < b.sortKey ? 1 : a.sortKey > b.sortKey ? -1 : 0).slice(0, limit));
  }
  async close() { await this.tail; if (this.closed) return; this.closed = true; await this.lock.close(); await fs.unlink(this.file + '.lock'); }
}
module.exports = { FileRepository, COLLECTIONS };
