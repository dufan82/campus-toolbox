'use strict';
// Owner's browser UI only. Authentication goes directly to the HTTPS cloud API;
// this loopback file server neither receives nor proxies passwords/tokens.
const http=require('node:http'),fs=require('node:fs/promises'),path=require('node:path');
const files={'/':'index.html','/index.html':'index.html','/styles.css':'styles.css','/api.js':'api.js','/app.js':'app.js','/editor.js':'editor.js','/main.js':'main.js'};
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8'};
function validateApi(value){const url=new URL(value);if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||['localhost','127.0.0.1','[::1]'].includes(url.hostname))throw new Error('A fixed HTTPS cloud API is required');return url.href;}
async function start({apiUrl,port=8791}={}){
 const api=validateApi(apiUrl);const root=path.resolve(__dirname,'../admin');
 const server=http.createServer(async(req,res)=>{
  const origin='http://127.0.0.1:'+server.address().port;
  const head={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self' "+new URL(api).origin+"; img-src 'self'; base-uri 'none'; object-src 'none'; form-action 'none'; frame-ancestors 'none'"};
  if(req.headers.host!=='127.0.0.1:'+server.address().port||(req.headers.origin&&req.headers.origin!==origin)){res.writeHead(403,head);res.end();return;}
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405,head);res.end();return;}
  const route=req.url?.split('?')[0];let body,filename;
  try{
   if(route==='/config.js'){filename='config.js';body=Buffer.from('window.CAMPUS_ADMIN_CONFIG = Object.freeze('+JSON.stringify({apiUrl:api,localCloud:true})+');\n');}
   else{filename=files[route];if(!filename){res.writeHead(404,head);res.end();return;}body=await fs.readFile(path.join(root,filename));}
   res.writeHead(200,{...head,'Content-Type':types[path.extname(filename)],'Content-Length':body.length});res.end(req.method==='HEAD'?undefined:body);
  }catch{res.writeHead(500,head);res.end();}
 });
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve)});return server;
}
if(require.main===module){const args=process.argv.slice(2);const value=args.length===2&&args[0]==='--api'?args[1]:'';start({apiUrl:value}).then(()=>console.log('Campus story review: http://127.0.0.1:8791/\nCloud API over HTTPS. Keep this window open. Ctrl+C to stop.')).catch(()=>{console.error('Cannot start local review. Check the HTTPS API and whether port 8791 is in use.');process.exitCode=1})}
module.exports={start,validateApi};
