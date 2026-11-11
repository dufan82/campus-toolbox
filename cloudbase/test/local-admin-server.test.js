'use strict';const{test}=require('node:test');const assert=require('node:assert/strict');const http=require('node:http');const{start,validateApi}=require('../scripts/local-admin');
test('local review serves only assets and never handles passwords or APIs',async t=>{const server=await start({apiUrl:'https://api.test/api',port:0});t.after(()=>server.close());const origin='http://127.0.0.1:'+server.address().port;
 assert.equal((await fetch(origin)).status,200);const config=await(await fetch(origin+'/config.js')).text();assert(config.includes('localCloud'));assert(!config.includes('password'));
 for(const name of ['/api','/../package.json','/secret.env'])assert.equal((await fetch(origin+name)).status,404);
 assert.equal((await fetch(origin,{method:'POST',body:'synthetic-invalid'})).status,405);
 assert.equal((await fetch(origin,{headers:{Origin:'https://evil.test'}})).status,403);
 const spoof=await new Promise(resolve=>{const r=http.get(origin,{headers:{Host:'evil.test'}},res=>{res.resume();res.on('end',()=>resolve(res.statusCode))});r.on('error',()=>resolve(0))});assert.equal(spoof,403);
});
test('local review refuses non-HTTPS cloud targets and credential URLs',()=>{for(const x of ['http://api.test/api','https://u:p@api.test/api','https://api.test/api?token=x','https://127.0.0.1/api'])assert.throws(()=>validateApi(x));});
