'use strict';
const { config } = require('../src/config');
const { CloudBaseRepository } = require('../src/cloudbase-repository');
const indexes = require('../database/indexes.json');
const rules = require('../database/security-rules.json');
const COLLECTIONS = Object.keys(indexes.collections);
function plan(env = process.env) {
  const missing = ['TCB_ENV_ID','TCB_REGION','TCB_AUTH_ORIGIN','ADMIN_UIDS','ADMIN_ALLOWED_ORIGINS'].filter(key => !env[key]?.trim());
  return { mode: 'plan-only-no-network', configured: missing.length === 0, missing, collections: COLLECTIONS, ruleForEveryCollection: rules, indexes, counter: { collection: 'story_stats', id: 'counts', initial: { pending: 0, approved: 0, rejected: 0 } }, security: 'Create/apply deny-all rules and indexes in console BEFORE exposing API. No resource/fee/deploy enabled by this plan.' };
}
async function initializeCounters(db) {
  const repo = new CloudBaseRepository(db);
  const existing = await repo.get('story_stats', 'counts');
  if (existing) return { created: false };
  // Bounded safety check, never rebuild counters by scanning existing private records.
  for (const collection of ['story_submissions','published_stories','story_audit_log']) {
    const r = await db.collection(collection).limit(1).get();
    if (!r || r.code || !Array.isArray(r.data)) throw new Error('Initialization database read failed');
    if (r.data.length) throw new Error('Existing business data without counters: stop and reconcile from an authorized backup; never zero counters');
  }
  return repo.transaction(async tx => {
    if (await tx.get('story_stats', 'counts')) return { created: false };
    await tx.set('story_stats', 'counts', { pending: 0, approved: 0, rejected: 0 }); return { created: true };
  });
}
async function createCollections(db) {
  for (const collection of COLLECTIONS) {
    try {
      const r = await db.createCollection(collection);
      if (!r || (r.code && r.code !== 'DATABASE_COLLECTION_ALREADY_EXIST')) throw new Error('Collection creation failed');
    } catch (e) { if (e.code !== 'DATABASE_COLLECTION_ALREADY_EXIST') throw e; }
  }
}
async function main(args = process.argv.slice(2), env = process.env) {
  if (!args.length || (args.length === 1 && args[0] === '--plan')) { console.log(JSON.stringify(plan(env), null, 2)); return; }
  const allowed = args.every(a => ['--create-collections','--initialize-counters','--security-reviewed'].includes(a) || a.startsWith('--confirm-env='));
  if (!allowed) throw new Error('Unknown initialization option');
  const c = config(env);
  if (!args.includes('--confirm-env=' + c.envId)) throw new Error('Explicit --confirm-env matching configured existing environment is required');
  const create = args.includes('--create-collections'), init = args.includes('--initialize-counters');
  if (create === init) throw new Error('Choose one initialization phase at a time');
  if (init && !args.includes('--security-reviewed')) throw new Error('Apply console deny-all rules/indexes and review them first; then supply --security-reviewed');
  const cloudbase = require('@cloudbase/node-sdk');
  const db = cloudbase.init({ env: c.envId, region: c.region }).database();
  if (create) { await createCollections(db); console.log('Collections ready. Rules/indexes NOT applied by node-sdk: apply database/security-rules.json to all collections and create indexes in console before continuing.'); }
  else console.log(JSON.stringify(await initializeCounters(db)));
}
if (require.main === module) main().catch(() => { console.error('Initialization refused/failed. Check configuration, explicit environment confirmation, console rules/indexes, and existing counters. No secrets or upstream response logged.'); process.exitCode = 1; });
module.exports = { plan, initializeCounters, createCollections, main };
