import {test} from 'node:test';
import assert from 'node:assert/strict';
import {timingSafeEqual} from 'node:crypto';
import {deflateSync} from 'node:zlib';
import worker,{LeaderLease} from '../backend/coordination/worker.mjs';
import {staffOperation,authenticate,authorizeWallet,rosterBody,digest,b64,un64,rawToDer,verify} from '../backend/coordination/staff.mjs';
const now=1794930000000,cluster='a'.repeat(64),bootstrap='b'.repeat(64),recovery='c'.repeat(64),enc=new TextEncoder();
class Store{
 data=new Map();async get(k){return structuredClone(this.data.get(k));}async put(k,v){this.data.set(k,structuredClone(v));}async delete(k){this.data.delete(k);}
 async list(o){return new Map([...this.data].filter(([k])=>k.startsWith(o.prefix)).sort(([a],[b])=>a.localeCompare(b)).slice(0,o.limit));}
 async transaction(fn){const before=structuredClone(this.data);try{if(this.retry){this.retry=false;await fn(this);this.data=structuredClone(before);}return await fn(this);}catch(e){this.data=before;throw e;}}
}
async function keys(){const pair=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);return {pair,pub:b64(await crypto.subtle.exportKey('spki',pair.publicKey)),private:b64(await crypto.subtle.exportKey('pkcs8',pair.privateKey)),device:await digest(crypto.randomUUID())};}
async function proof(k,body,route='staff',time=now){const payload=JSON.stringify(body),nonce=await digest(crypto.randomUUID());return {key:k.pub,payload,nonce,time,signature:b64(rawToDer(new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},k.pair.privateKey,enc.encode('staff-request|'+cluster+'|'+route+'|'+time+'|'+nonce+'|'+payload)))))};}
function body(cert){return JSON.parse(new TextDecoder().decode(un64(cert.slice(5).split('.')[0])));}
async function setup(){const root=await keys(),master=await keys(),tx=new Store();const env={NETWORK_KEY:'d'.repeat(64),STAFF_SIGNING_KEY:root.private,STAFF_BOOTSTRAP_HASH:await digest(bootstrap),STAFF_RECOVERY_HASH:await digest(recovery),STAFF_POLICY:JSON.stringify({cluster,authority:root.pub,plants:['Norte','Sur'],personPrefix:'CI-'})};const call=async(k,r,t=now)=>staffOperation(tx,await proof(k,r,'staff',t),env,t);await call(master,{action:'claim',code:bootstrap,name:'Dueña principal',device:master.device});return {root,master,tx,env,call};}
async function enroll(s,role='encargado',plants=['Norte']){const k=await keys();await s.call(k,{action:'request',name:'Encargado Prueba',device:k.device});const granted=await s.call(s.master,{action:'grant',request:await digest(k.pub),name:'Encargado Prueba',role,plants,permissions:[]});return {k,user:body(granted.certificate),cert:granted.certificate};}
test('only the private one-use bootstrap creates the one master, with signed company-bound grants',async()=>{
 const s=await setup(),other=await keys();await assert.rejects(s.call(other,{action:'claim',code:bootstrap,name:'Otro maestro',device:other.device}),/ya existe/);
 const status=await s.call(s.master,{action:'status'}),cert=status.certificate,[data,sig]=cert.slice(5).split('.');assert.equal(await verify(s.root.pub,'CSA1:'+data,sig),true);assert.equal(body(cert).role,'master');assert.equal(body(cert).cluster,cluster);assert.equal([...s.tx.data.keys()].filter(k=>k.startsWith('staffUser:')).length,1);
});
test('administrators and operators cannot create accounts, change roles or retrieve the admin screen',async()=>{
 const s=await setup(),admin=await enroll(s,'admin'),operator=await enroll(s);for(const actor of [admin,operator])for(const action of ['grant','update','list'])await assert.rejects(s.call(actor.k,{action}),/Solo el usuario maestro/);
 const stranger=await keys();await assert.rejects(s.call(stranger,{action:'list'}),/Cuenta pendiente/);
});
test('tampered payloads, cross-route requests and another installation key are rejected',async()=>{
 const s=await setup(),u=await enroll(s),request=await proof(u.k,{action:'list'});request.payload='{"action":"claim"}';await assert.rejects(staffOperation(s.tx,request,s.env,now),/no pertenece/);
 await assert.rejects(authenticate(s.tx,await proof(u.k,{action:'status'}),'wallet',s.env,now),/no pertenece/);
 await assert.rejects(staffOperation(s.tx,await proof(u.k,{action:'status'},'staff',now-200000),s.env,now),/vencida/);
});
test('replay is rejected on mutating and authenticated routes',async()=>{
 const s=await setup(),u=await enroll(s),p=await proof(u.k,{action:'get',tag:'1'.repeat(64)},'wallet');await authenticate(s.tx,p,'wallet',s.env,now);await assert.rejects(authenticate(s.tx,p,'wallet',s.env,now),/repetida/);
});
test('grant only accepts known plants and non-master roles; requests are device-bound and single use',async()=>{
 const s=await setup(),k=await keys();await s.call(k,{action:'request',name:'Nueva persona',device:k.device});const r={action:'grant',request:await digest(k.pub),name:'Nueva persona',role:'encargado',plants:['Norte'],permissions:[]};
 await assert.rejects(s.call(s.master,{...r,role:'master'}));await assert.rejects(s.call(s.master,{...r,plants:['Otra empresa']}));await assert.rejects(s.call(s.master,{...r,permissions:['createAdmin']}));
 const grant=body((await s.call(s.master,r)).certificate);assert.equal(grant.key,k.pub);assert.equal(grant.device,k.device);await assert.rejects(s.call(s.master,r));
});
test('revocation retains the account, increments its revision, and blocks further operations',async()=>{
 const s=await setup(),u=await enroll(s);const update={action:'update',id:u.user.id,expected:u.user.revision,name:u.user.name,role:u.user.role,plants:u.user.plants,permissions:[],active:false};
 const result=await s.call(s.master,update,now+1000);assert.equal(body(result.certificate).active,false);assert.ok(body(result.certificate).revision>u.user.revision);
 await assert.rejects(authenticate(s.tx,await proof(u.k,{action:'get'},'wallet',now+1000),'wallet',s.env,now+1000),/desactivada/);
 assert.equal(body((await s.call(u.k,{action:'status'},now+1000)).certificate).active,false);assert.ok(await s.tx.get('staffUser:'+u.user.id));await assert.rejects(s.call(s.master,update,now+2000),/cambió/);
});
test('replacement preserves account ID, revokes the old key, and cannot accidentally create another master',async()=>{
 const s=await setup(),u=await enroll(s),next=await keys();await s.call(next,{action:'request',name:u.user.name,device:next.device});
 const result=await s.call(s.master,{action:'update',id:u.user.id,expected:u.user.revision,name:u.user.name,role:'encargado',plants:['Norte'],permissions:[],active:true,request:await digest(next.pub)});
 assert.equal(body(result.certificate).id,u.user.id);assert.equal(body(result.certificate).key,next.pub);assert.equal(body((await s.call(u.k,{action:'status'})).replacement).key,next.pub);
 await assert.rejects(authenticate(s.tx,await proof(u.k,{action:'status'},'lease'),'lease',s.env,now));assert.equal((await authenticate(s.tx,await proof(next,{action:'status'},'lease'),'lease',s.env,now)).user.id,u.user.id);
});
test('private recovery is single use and replaces the master without deleting other accounts',async()=>{
 const s=await setup(),u=await enroll(s),next=await keys();const before=await s.tx.get('staffMaster');await s.call(next,{action:'recover',code:recovery,name:'Dueña recuperada',device:next.device});assert.equal(await s.tx.get('staffMaster'),before);assert.ok(await s.tx.get('staffUser:'+u.user.id));
 await assert.rejects(s.call(s.master,{action:'list'}));await assert.rejects(s.call(next,{action:'recover',code:recovery,name:'Otro intento',device:next.device}),/ya fue utilizada/);
});
test('existing-account login recovery requires the master and cannot create a duplicate account',async()=>{
 const s=await setup(),u=await enroll(s,'encargado',['Norte','Sur']),next=await keys(),admin=await enroll(s,'admin');
 const requested=await s.call(next,{action:'request',name:u.user.name,device:next.device,intent:'recover'});
 assert.equal(requested.intent,'recover');assert.equal((await s.call(next,{action:'status'})).pending,true);
 const list=await s.call(s.master,{action:'list'});assert.equal(list.pending.find(x=>x.id===requested.id).intent,'recover');
 const update={action:'update',id:u.user.id,expected:u.user.revision,name:u.user.name,role:u.user.role,plants:u.user.plants,permissions:u.user.permissions,active:u.user.active,request:requested.id};
 for(const actor of [next,u.k,admin.k])await assert.rejects(s.call(actor,update));
 const size=[...s.tx.data.keys()].filter(x=>x.startsWith('staffUser:')).length;
 await assert.rejects(s.call(s.master,{...update,action:'grant'}),/cuenta existente/);
 const recovered=body((await s.call(s.master,update)).certificate);
 for(const field of ['id','name','role','plants','permissions','active'])assert.deepEqual(recovered[field],u.user[field]);
 assert.equal(recovered.key,next.pub);assert.equal(recovered.device,next.device);assert.equal([...s.tx.data.keys()].filter(x=>x.startsWith('staffUser:')).length,size);
 assert.equal(body((await s.call(next,{action:'status'})).certificate).id,u.user.id);
 await assert.rejects(authenticate(s.tx,await proof(u.k,{action:'status'},'lease'),'lease',s.env,now));
 await assert.rejects(s.call(s.master,{...update,expected:recovered.revision}));
});
test('recovery request expiry and company scope cannot be bypassed',async()=>{
 const s=await setup(),u=await enroll(s),next=await keys();await s.call(next,{action:'request',name:u.user.name,device:next.device,intent:'recover'});
 const update={action:'update',id:u.user.id,expected:u.user.revision,name:u.user.name,role:u.user.role,plants:u.user.plants,permissions:u.user.permissions,active:true,request:await digest(next.pub)};
 await assert.rejects(s.call(s.master,update,now+600001),/reemplazo inválida/);
 await assert.rejects(s.call(next,{action:'request',name:u.user.name,device:next.device,intent:'automatic'}),/Tipo de solicitud/);
 const otherEnv={...s.env,STAFF_POLICY:JSON.stringify({...JSON.parse(s.env.STAFF_POLICY),cluster:'9'.repeat(64)})};
 await assert.rejects(staffOperation(s.tx,await proof(s.master,update),otherEnv,now),/no pertenece/);
 assert.equal((await s.tx.get('staffUser:'+u.user.id)).key,u.k.pub);
});
test('a link permission cannot be used to bind workers of another plant, even by lying about the plant',async()=>{
 const s=await setup(),u=await enroll(s),north='1'.repeat(64),south='2'.repeat(64);
 await s.call(s.root,{action:'seed',items:[{tag:north,plant:'Norte'},{tag:south,plant:'Sur'}]});
 assert.equal(await authorizeWallet(s.tx,{action:'bind',tag:north,plant:'Norte'},u.user,s.env,now),null);
 await assert.rejects(authorizeWallet(s.tx,{action:'bind',tag:south,plant:'Sur'},u.user,s.env,now));await assert.rejects(authorizeWallet(s.tx,{action:'bind',tag:south,plant:'Norte'},u.user,s.env,now));
 await assert.rejects(s.call(s.master,{action:'seed',items:[{tag:south,plant:'Norte'}]}),/no autorizada/);
});

function endpoint(s){
 // Cloudflare provides this method; Node uses the same constant-time primitive.
 crypto.subtle.timingSafeEqual=timingSafeEqual;
 const instance=new LeaderLease({storage:s.tx},s.env);
 s.env.LEADER={idFromName:()=> 'comedor',get:()=>instance};
 return (path,input,token=s.env.NETWORK_KEY)=>worker.fetch(new Request('https://coordinator.test'+path,{method:'POST',headers:{Authorization:'Bearer '+token},body:JSON.stringify(input)}),s.env);
}
const leaseInput=k=>({action:'acquire',device:k.device,boot:'f'.repeat(64),epoch:0,reportsProtocol:1});
test('coordinator enforcement preserves the old live lease and rejects old clients after activation',async()=>{
 const s=await setup(),post=endpoint(s),old=await keys();await s.tx.delete('staffEnabled');
 assert.equal((await post('/lease',leaseInput(old))).status,200);const previous=await s.tx.get('lease');
 await s.tx.put('staffEnabled',true);
 assert.equal((await post('/lease',{...leaseInput(old),action:'renew',epoch:previous.epoch})).status,400);
 assert.deepEqual(await s.tx.get('lease'),previous);
 const signed=await proof(s.master,leaseInput(s.master),'lease',Date.now());s.tx.retry=true;
 const reply=await post('/lease',{staff:signed});assert.equal(reply.status,200);assert.equal((await reply.json()).owner,old.device);
 assert.equal((await post('/lease',{staff:signed})).status,400);
 const bad=await proof(s.master,leaseInput(old),'lease',Date.now());assert.equal((await post('/lease',{staff:bad})).status,400);
 assert.equal((await post('/lease',leaseInput(old),'wrong-key')).status,401);
});
test('coordinator wallet uses signed plant and account identity and survives a transaction retry',async()=>{
 const s=await setup(),u=await enroll(s),post=endpoint(s),tag='1'.repeat(64);
 await s.call(s.root,{action:'seed',items:[{tag,plant:'Norte'}]});
 const operation={action:'bind',tag,plant:'Norte',expected:0,requestId:'2'.repeat(64),keyHash:'3'.repeat(64),actor:'4'.repeat(64),by:'Falsa identidad'};
 assert.equal((await post('/wallet',operation)).status,400);
 s.tx.retry=true;const response=await post('/wallet',{staff:await proof(u.k,operation,'wallet',Date.now()),plant:'Sur',by:'Falso'});
 assert.equal(response.status,200);const binding=(await response.json()).binding;assert.equal(binding.by,u.user.name);assert.equal(binding.actor,u.user.device);assert.equal(binding.version,1);
 await s.call(s.master,{action:'update',id:u.user.id,expected:u.user.revision,name:u.user.name,role:u.user.role,plants:u.user.plants,permissions:[],active:false});
 assert.equal((await post('/wallet',{staff:await proof(u.k,{action:'get',tag},'wallet',Date.now())})).status,400);assert.equal((await s.tx.get('wallet:'+tag)).version,1);
});
test('new worker proof binds its registered plant and rejects a modified registration',async()=>{
 const s=await setup(),creator=await enroll(s,'admin',['Norte','Sur']),operator=await enroll(s),ci='12345678';
 const roster={v:4,cluster,origin:creator.k.device,seq:1,time:now,operator:{name:creator.user.name},ci,name:'Persona / de prueba',plant:'Norte'};
 const payload=JSON.stringify(roster).replaceAll('/','\\/');
 const signature=b64(rawToDer(new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},creator.k.pair.privateKey,enc.encode('staff-event|'+cluster+'|roster|'+payload)))));
 const signed={...roster,staff:{certificate:creator.cert,payload,signature}};
 const key=await crypto.subtle.importKey('raw',enc.encode(s.env.NETWORK_KEY),{name:'HMAC',hash:'SHA-256'},false,['sign']);
 const bytes=await crypto.subtle.sign('HMAC',key,enc.encode(cluster+'|wallet-person|CI-'+ci));const tag=Buffer.from(bytes).toString('hex');
 const r={action:'bind',tag,plant:'Norte',roster:'SB4:'+b64(enc.encode(JSON.stringify({...signed,plant:'Sur'})))+'.unused'};
 await assert.rejects(authorizeWallet(s.tx,r,operator.user,s.env,now));assert.equal(await s.tx.get('staffSubject:'+tag),undefined);
 r.roster='SB4:'+b64(enc.encode(JSON.stringify(signed)))+'.unused';assert.equal(await authorizeWallet(s.tx,r,operator.user,s.env,now),null);assert.equal(await s.tx.get('staffSubject:'+tag),'Norte');
 await s.tx.delete('staffSubject:'+tag);r.roster='SB5:'+b64(deflateSync(JSON.stringify(signed)))+'.unused';assert.equal(await authorizeWallet(s.tx,r,operator.user,s.env,now),null);assert.equal(await s.tx.get('staffSubject:'+tag),'Norte');
});
test('compressed credentials reject corruption and oversized decompression',async()=>{
 await assert.rejects(rosterBody('SB5:invalid.unused'));await assert.rejects(rosterBody('SB5:'+b64(deflateSync('x'.repeat(9000)))+'.unused'),/demasiado grande/);
});
