'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
test('HTTP runtime passes dedicated server key explicitly and does not rely on startup temporary identity',()=>{
 const filename=path.resolve(__dirname,'../index.js');let received;
 const sdk={init:options=>{received=options;return{database:()=>({})}}};
 const stubRequire=name=>name==='@cloudbase/node-sdk'?sdk:require(path.resolve(path.dirname(filename),name));
 const box={require:stubRequire,exports:{},process:{env:{}},Buffer};vm.runInNewContext(fs.readFileSync(filename,'utf8'),box,{filename});
 const env={TCB_ENV_ID:'test-env',TCB_REGION:'ap-shanghai',TCB_AUTH_ORIGIN:'https://test-env.api.tcloudbasegateway.com',CLOUDBASE_APIKEY:'synthetic-server-key-only'};
 box.exports.createProductionApi(env);assert.equal(received.accessKey,env.CLOUDBASE_APIKEY);assert.equal(received.env,'test-env');
});
