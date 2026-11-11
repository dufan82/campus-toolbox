'use strict';const{test}=require('node:test');const assert=require('node:assert/strict');const{config}=require('../src/config');
const base={TCB_ENV_ID:'test-env',TCB_REGION:'ap-shanghai',TCB_AUTH_ORIGIN:'https://test-env.api.tcloudbasegateway.com'};
test('exact optional loopback review origin is separate from public HTTPS origins',()=>{
 assert(config({...base,ADMIN_LOCAL_ORIGIN:'http://127.0.0.1:8791'}).origins.includes('http://127.0.0.1:8791'));
 for(const origin of ['http://evil.test','http://192.168.1.1:8791','http://127.0.0.1.evil.test:8791','http://127.0.0.1:8791/path','https://127.0.0.1:8791'])assert.throws(()=>config({...base,ADMIN_LOCAL_ORIGIN:origin}));
 assert.throws(()=>config({...base,ADMIN_ALLOWED_ORIGINS:'http://127.0.0.1:8791'}));
});
