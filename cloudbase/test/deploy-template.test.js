'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const template = require('../cloudbaserc.example.json');
test('offline production template defaults to free-tier fixed 256MB and 3-second platform timeout', () => {
  const api = template.functions.find(fn => fn.name === 'story-api');
  assert.ok(api, 'story API deployment configuration must exist');
  assert.equal(api.type, 'Event');
  assert.equal(api.ignoreSysLog, true, 'platform must not log response tokens or private story bodies');
  assert.equal(api.memorySize, 256, 'free tier memory must remain 256MB');
  assert.equal(api.timeout, 3, 'free tier platform timeout is fixed at 3 seconds, not transport timeout');
});
