import {test} from 'node:test';
import assert from 'node:assert/strict';
import {timingSafeEqual,pbkdf2Sync,createHmac,createHash,randomBytes} from 'node:crypto';
import {deflateSync} from 'node:zlib';
import worker,{LeaderLease} from '../backend/coordination/worker.mjs';
import {staffOperation,authenticate,authorizeWallet,rosterBody,digest,b64,un64,rawToDer,verify} from '../backend/coordination/staff.mjs';
import {base64,bytes,hash,hmac,equal,credentials} from '../backend/coordination/password.mjs';
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

const password='Comedor-ñ-2026';
function passwordRecord(secret=password){const salt=randomBytes(32),salted=pbkdf2Sync(secret.normalize('NFKC'),salt,600000,32,'sha256'),client=createHmac('sha256',salted).update('Client Key').digest();return {scheme:'SCRAM-SHA-256',iterations:600000,salt:salt.toString('base64'),storedKey:createHash('sha256').update(client).digest('base64'),serverKey:createHmac('sha256',salted).update('Server Key').digest('base64')};}
function answerPassword(name,secret,nonce,serverFirst){const parts=serverFirst.split(','),salt=Buffer.from(parts[1].slice(2),'base64'),salted=pbkdf2Sync(secret.normalize('NFKC'),salt,600000,32,'sha256'),client=createHmac('sha256',salted).update('Client Key').digest(),stored=createHash('sha256').update(client).digest();const message='n='+name+',r='+nonce+','+serverFirst+',c=biws,r='+parts[0].slice(2),sig=createHmac('sha256',stored).update(message).digest();return {proof:Buffer.from(client.map((x,i)=>x^sig[i])).toString('base64'),serverSignature:createHmac('sha256',createHmac('sha256',salted).update('Server Key').digest()).update(message).digest('base64')};}
async function createPasswordUser(s,name='operador.test',secret=password){const record=passwordRecord(secret);const reply=await s.call(s.master,{action:'createAccount',username:name,name:'Cuenta con contraseña',role:'encargado',plants:['Norte'],permissions:[],password:record});return {record,user:body(reply.certificate)};}
async function startLogin(s,k,name='operador.test',secret=password,time=now){const nonce=await digest(crypto.randomUUID()),start=await s.call(k,{action:'loginStart',username:name,device:k.device,clientNonce:nonce},time);assert.equal(start.ok,true);return {start,answer:answerPassword(name.trim().toLowerCase(),secret,nonce,start.serverFirst)};}
async function finishLogin(s,k,login,time=now){return s.call(k,{action:'loginFinish',challenge:login.start.challenge,proof:login.answer.proof},time);}
test('SCRAM-SHA-256 calculations match the RFC 7677 published vector',async()=>{
 const salted=pbkdf2Sync('pencil',Buffer.from('W22ZaJ0SNY7soEsUEjb6gQ==','base64'),4096,32,'sha256'),client=await hmac(salted,'Client Key'),stored=await hash(client);
 const nonce='rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0',message='n=user,r=rOprNGfwEbeRWgbNEkqO,r='+nonce+',s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r='+nonce;
 const sig=await hmac(stored,message),proof=client.map((x,i)=>x^sig[i]);assert.equal(base64(proof),'dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=');assert.equal(base64(await hmac(await hmac(salted,'Server Key'),message)),'6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=');assert.equal(equal(await hash(proof.map((x,i)=>x^sig[i])),stored),true);
});
test('only master creates password accounts; verifiers never enter grants, directories or audit records',async()=>{
 const s=await setup(),u=await createPasswordUser(s,' Operador.Test '),admin=await enroll(s,'admin'),operator=await enroll(s);
 assert.equal(u.user.username,'operador.test');assert.equal(u.user.passwordVersion,1);assert.equal(u.user.active,true);
 for(const actor of [admin.k,operator.k])for(const action of ['createAccount','password','deleteAccount'])await assert.rejects(s.call(actor,{action}),/Solo el usuario maestro/);
 await assert.rejects(createPasswordUser(s,'OPERADOR.TEST'),/ya está registrado/);
 const publicValues=JSON.stringify([...s.tx.data].filter(([k])=>!k.startsWith('staffPassword:')));for(const secret of [u.record.storedKey,u.record.serverKey,password])assert.equal(publicValues.includes(secret),false);
 const directory=body((await s.call(s.master,{action:'list'})).directory);for(const cert of directory.accounts){const grant=body(cert);for(const field of ['salt','storedKey','serverKey','password'])assert.equal(field in grant,false);}
 assert.throws(()=>credentials({...u.record,iterations:1}));assert.throws(()=>credentials({...u.record,storedKey:'broken'}));
});
test('password login reuses the account and replaces a phone automatically without master approval',async()=>{
 const s=await setup(),u=await createPasswordUser(s),first=await keys(),second=await keys();
 const login=await startLogin(s,first),reply=await finishLogin(s,first,login);assert.equal(reply.ok,true);assert.equal(reply.serverSignature,login.answer.serverSignature);let signed=body(reply.certificate);
 assert.equal(signed.id,u.user.id);assert.equal(signed.key,first.pub);assert.equal(signed.device,first.device);assert.equal(signed.role,'encargado');assert.deepEqual(signed.plants,['Norte']);
 const same=await finishLogin(s,first,await startLogin(s,first));assert.equal(body(same.certificate).revision,signed.revision);
 const moved=await finishLogin(s,second,await startLogin(s,second));assert.equal(body(moved.certificate).id,u.user.id);assert.equal(body(moved.certificate).key,second.pub);
 await assert.rejects(authenticate(s.tx,await proof(first,{action:'status'},'lease'),'lease',s.env,now));assert.equal(body((await s.call(first,{action:'status'})).replacement).key,second.pub);
 assert.equal((await authenticate(s.tx,await proof(second,{action:'status'},'lease'),'lease',s.env,now)).user.id,u.user.id);
 assert.equal([...s.tx.data.keys()].filter(x=>x.startsWith('staffUser:')).length,2);
});
test('password reset preserves account history but rejects old passwords, proofs and phones',async()=>{
 const s=await setup(),u=await createPasswordUser(s),phone=await keys();await finishLogin(s,phone,await startLogin(s,phone));const current=body((await s.call(phone,{action:'status'})).certificate),old=await startLogin(s,phone);
 const replacement=passwordRecord('Nueva-clave-2027');const updated=body((await s.call(s.master,{action:'password',id:u.user.id,expected:current.revision,username:u.user.username,password:replacement})).certificate);
 assert.equal(updated.id,u.user.id);assert.equal(updated.passwordVersion,2);assert.equal((await finishLogin(s,phone,old)).ok,false);await assert.rejects(authenticate(s.tx,await proof(phone,{action:'status'},'lease'),'lease',s.env,now));
 assert.equal((await finishLogin(s,phone,await startLogin(s,phone))).ok,false);
 const fresh=await finishLogin(s,phone,await startLogin(s,phone,'operador.test','Nueva-clave-2027'));assert.equal(fresh.ok,true);assert.equal(body(fresh.certificate).id,u.user.id);
});
test('master converts an existing PIN account without duplicating it and keeps its own recovery available',async()=>{
 const s=await setup(),legacy=await enroll(s),record=passwordRecord();const result=await s.call(s.master,{action:'password',id:legacy.user.id,expected:legacy.user.revision,username:'existente',password:record});assert.equal(body(result.certificate).id,legacy.user.id);assert.equal(body(result.certificate).key,legacy.k.pub);
 const master=body((await s.call(s.master,{action:'status'})).certificate);const own=await s.call(s.master,{action:'password',id:master.id,expected:master.revision,username:'principal',password:passwordRecord()});assert.equal(body(own.certificate).key,s.master.pub);assert.equal((await s.call(s.master,{action:'list'})).ok,true);
 const newer=await keys();const recovered=body((await s.call(newer,{action:'recover',name:'Dueña principal',code:recovery,device:newer.device})).certificate);assert.equal(recovered.username,'principal');assert.equal(recovered.id,master.id);
});
test('deleting an account retains audit identity and prevents password sign-in and username reuse',async()=>{
 const s=await setup(),u=await createPasswordUser(s),phone=await keys(),login=await startLogin(s,phone);
 const result=await s.call(s.master,{action:'deleteAccount',id:u.user.id,expected:u.user.revision});const deleted=body(result.certificate);assert.equal(deleted.active,false);assert.equal(deleted.deleted,true);assert.equal(deleted.id,u.user.id);assert.equal(await s.tx.get('staffPassword:'+u.user.id),undefined);
 assert.equal((await finishLogin(s,phone,login)).ok,false);assert.equal((await finishLogin(s,phone,await startLogin(s,phone))).ok,false);await assert.rejects(createPasswordUser(s),/ya está registrado/);
 const master=body((await s.call(s.master,{action:'status'})).certificate);await assert.rejects(s.call(s.master,{action:'deleteAccount',id:master.id,expected:master.revision}),/maestra/);
});
test('login proofs are bound to the device key, expire and cannot be replayed',async()=>{
 const s=await setup();await createPasswordUser(s);const phone=await keys(),other=await keys(),login=await startLogin(s,phone);
 assert.equal((await finishLogin(s,other,login)).ok,false);assert.equal((await finishLogin(s,phone,login,now+120001)).ok,false);
 const good=await startLogin(s,phone);assert.equal((await finishLogin(s,phone,good)).ok,true);assert.equal((await finishLogin(s,phone,good)).ok,false);
 const unknown=await startLogin(s,other,'desconocido');assert.equal(Object.keys(unknown.start).join(','),Object.keys(good.start).join(','));assert.equal((await finishLogin(s,other,unknown)).ok,false);
});
test('failed password attempts and throttling survive a real coordinator transaction',async()=>{
 const s=await setup();await createPasswordUser(s);const phone=await keys(),post=endpoint(s),now=Date.now();
 for(let i=0;i<8;i++){const start=await(await post('/staff',await proof(phone,{action:'loginStart',username:'operador.test',device:phone.device,clientNonce:await digest(String(i))},'staff',now))).json();assert.equal(start.ok,true);const bad=await(await post('/staff',await proof(phone,{action:'loginFinish',challenge:start.challenge,proof:randomBytes(32).toString('base64')},'staff',now))).json();assert.equal(bad.ok,false);assert.equal(await s.tx.get('staffLoginChallenge:'+await digest(phone.pub)),undefined);}
 const next=await(await post('/staff',await proof(phone,{action:'loginStart',username:'operador.test',device:phone.device,clientNonce:await digest('next')},'staff',now))).json();assert.equal(next.code,'LOGIN_WAIT');assert.equal((await s.tx.get('staffLoginLimit:name:'+await digest('operador.test'))).count,8);
});
