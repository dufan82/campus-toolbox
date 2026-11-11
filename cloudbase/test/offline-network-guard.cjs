'use strict';
// Test-only preloader. Block external connections before DNS/TCP; never loaded by production.
const net = require('node:net');
const loopback = new Set(['localhost','127.0.0.1','::1','[::1]']);
const connect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  const a = Array.isArray(args[0]) ? args[0] : args;
  const options = a[0];
  const host = typeof options === 'object' ? (options.host || options.hostname || 'localhost') : typeof a[1] === 'string' ? a[1] : 'localhost';
  if (!loopback.has(host)) throw new Error('OFFLINE_TEST_EXTERNAL_NETWORK_BLOCKED');
  return connect.apply(this, args);
};
const fetch = globalThis.fetch;
globalThis.fetch = function (input, ...args) {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  if (!loopback.has(url.hostname)) throw new Error('OFFLINE_TEST_EXTERNAL_NETWORK_BLOCKED');
  return fetch(input, ...args);
};
