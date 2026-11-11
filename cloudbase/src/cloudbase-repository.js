'use strict';
// API surface verified against pinned @cloudbase/node-sdk / @cloudbase/database sources.
// Never accept credentials or database identity from HTTP headers/payloads.
function checked(result) { if (!result || result.code) throw new Error('CloudBase database operation failed'); return result; }
function document(result, inTransaction = false) {
  checked(result); const data = result.data;
  const value = inTransaction ? data : (Array.isArray(data) ? data[0] : undefined);
  if (value === null || (!inTransaction && Array.isArray(data) && !data.length)) return null;
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('CloudBase database response invalid');
  const { _id, ...row } = value; return row;
}
class CloudBaseRepository {
  constructor(database) { this.db = database; }
  async get(collection, id) { return document(await this.db.collection(collection).doc(id).get()); }
  transaction(fn) {
    return this.db.runTransaction(async transaction => fn({
      get: async (c, id) => document(await transaction.collection(c).doc(id).get(), true),
      set: async (c, id, data) => { checked(await transaction.collection(c).doc(id).set(data)); },
      remove: async (c, id) => { checked(await transaction.collection(c).doc(id).remove()); }
    }));
  }
  async query(collection, { equal = {}, before, limit = 20 } = {}) {
    const where = { ...equal }; if (before) where.sortKey = this.db.command.lt(before);
    const result = checked(await this.db.collection(collection).where(where).orderBy('sortKey', 'desc').limit(limit).get());
    if (!Array.isArray(result.data)) throw new Error('CloudBase database response invalid');
    return result.data.map(({ _id, ...row }) => row);
  }
}
module.exports = { CloudBaseRepository };
