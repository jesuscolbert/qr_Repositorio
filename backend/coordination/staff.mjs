// Account authority, independent of billing and tunnel leadership. No meal data lives here.
const hex=/^[a-f0-9]{64}$/;
const enc=new TextEncoder(), dec=new TextDecoder();
export const b64=b=>{const a=new Uint8Array(b);let s='';for(let i=0;i<a.length;i+=8192)s+=String.fromCharCode(...a.subarray(i,i+8192));return btoa(s).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');};
export const un64=s=>Uint8Array.from(atob(s.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0));
export const digest=async s=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',enc.encode(s))),b=>b.toString(16).padStart(2,'0')).join('');
const require=(ok,msg='Acceso del encargado no autorizado.')=>{if(!ok)throw Error(msg);};
export function derToRaw(bytes){
  require(bytes.length>=8&&bytes.length<=72&&bytes[0]===48&&bytes[1]===bytes.length-2);let at=2;const out=new Uint8Array(64);
  for(let i=0;i<2;i++){require(bytes[at++]===2);let n=bytes[at++];require(n>=1&&n<=33&&at+n<=bytes.length);let part=bytes.slice(at,at+n);at+=n;if(part.length===33){require(part[0]===0);part=part.slice(1);}out.set(part,32*i+32-part.length);}require(at===bytes.length);return out;
}
export function rawToDer(raw){const parts=[];for(let i=0;i<2;i++){let p=Array.from(raw.slice(i*32,i*32+32));while(p.length>1&&p[0]===0)p.shift();if(p[0]&128)p.unshift(0);parts.push(2,p.length,...p);}return new Uint8Array([48,parts.length,...parts]);}
async function publicKey(key){require(typeof key==='string'&&key.length<=160);return crypto.subtle.importKey('spki',un64(key),{name:'ECDSA',namedCurve:'P-256'},false,['verify']);}
export async function verify(key,message,signature){try{return await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},await publicKey(key),derToRaw(un64(signature)),enc.encode(message));}catch{return false;}}
export async function seal(env,body){const data=b64(enc.encode(JSON.stringify(body)));const key=await crypto.subtle.importKey('pkcs8',un64(env.STAFF_SIGNING_KEY),{name:'ECDSA',namedCurve:'P-256'},false,['sign']);return 'CSA1:'+data+'.'+b64(rawToDer(new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},key,enc.encode('CSA1:'+data)))));}
export function policy(env){const p=JSON.parse(env.STAFF_POLICY||'null');require(p&&hex.test(p.cluster)&&Array.isArray(p.plants)&&p.plants.length>0&&p.plants.length<=100&&p.plants.every(x=>typeof x==='string'&&x.length>0&&x.length<=100),'Falta configurar la autoridad de cuentas.');return p;}
export async function signedRequest(envelope,route,env,now){
 require(envelope&&typeof envelope.payload==='string'&&envelope.payload.length<=16000&&typeof envelope.key==='string'&&hex.test(envelope.nonce||'')&&Number.isSafeInteger(envelope.time)&&Math.abs(now-envelope.time)<=120000,'Solicitud vencida; activa la hora automática.');
 const p=policy(env);require(await verify(envelope.key,'staff-request|'+p.cluster+'|'+route+'|'+envelope.time+'|'+envelope.nonce+'|'+envelope.payload,envelope.signature),'La solicitud no pertenece a este teléfono.');
 return JSON.parse(envelope.payload);
}
export async function authenticate(tx,envelope,route,env,now,{replay=true}={}){
 const input=await signedRequest(envelope,route,env,now), keyHash=await digest(envelope.key),id=await tx.get('staffKey:'+keyHash),user=id?await tx.get('staffUser:'+id):null;
 require(user&&user.active&&user.key===envelope.key,'Cuenta pendiente, desactivada o teléfono reemplazado.');
 if(replay){const k='staffNonce:'+keyHash;let recent=await tx.get(k)||[];recent=recent.filter(x=>x.time>now-120000);require(!recent.some(x=>x.nonce===envelope.nonce)&&recent.length<100,'Solicitud repetida; espera un momento.');recent.push({nonce:envelope.nonce,time:now});await tx.put(k,recent);}
 return {input,user};
}
export function permission(user,action,plant){require(user.active&&(!plant||user.plants.includes(plant)),'No tienes permiso para esta planta.');require(user.role==='master'||user.permissions.includes(action),'Tu cuenta no tiene permiso para esta operación.');}
async function directory(tx,env,now){const users=await tx.list({prefix:'staffUser:',limit:501});require(users.size<=500,'Se alcanzó el límite de cuentas.');return seal(env,{kind:'directory',cluster:policy(env).cluster,revision:await tx.get('staffRevision')||0,time:now,accounts:[...users.values()].map(x=>x.certificate)});}
async function saveUser(tx,env,u,now,by){
 const revision=(await tx.get('staffRevision')||0)+1, user={...u,revision,issued:now};delete user.certificate;
 const certificate=await seal(env,{kind:'grant',cluster:policy(env).cluster,...user});user.certificate=certificate;
 await tx.put('staffUser:'+user.id,user);await tx.put('staffKey:'+await digest(user.key),user.id);await tx.put('staffRevision',revision);
 await tx.put('staffAudit:'+String(revision).padStart(12,'0'),{time:now,by,user:user.id,active:user.active,role:user.role,revision});return user;
}
export const defaultPermissions=role=>role==='encargado'?['scan','link','people','report']:['scan','link','people','report','issue','worker','visitor'];
export async function staffOperation(tx,envelope,env,now){
 const r=await signedRequest(envelope,'staff',env,now),p=policy(env),hash=await digest(envelope.key);
 require(typeof r.action==='string');
 if(r.action==='seed'){
  require(envelope.key===p.authority&&Array.isArray(r.items)&&r.items.length<=100,'Carga inicial no autorizada.');
  for(const item of r.items){require(hex.test(item.tag||'')&&p.plants.includes(item.plant));const old=await tx.get('staffSubject:'+item.tag);require(!old||old===item.plant,'La planta de un trabajador ya está definida.');await tx.put('staffSubject:'+item.tag,item.plant);}return {ok:true,count:r.items.length};
 }
 if(r.action==='request'){
  require(typeof r.name==='string'&&r.name.trim().length>=3&&r.name.trim().length<=100&&hex.test(r.device||''),'Completa tu nombre.');
  const existing=await tx.get('staffKey:'+hash);if(existing)return {ok:true,alreadyKnown:true};
  const pending=await tx.list({prefix:'staffPending:',limit:501});for(const [k,v] of pending)if(v.expires<now)await tx.delete(k);
  require([...pending.values()].filter(x=>x.expires>=now).length<200,'Hay demasiadas solicitudes pendientes.');
  await tx.put('staffPending:'+hash,{key:envelope.key,device:r.device,name:r.name.trim(),expires:now+600000});return {ok:true,id:hash,expires:now+600000};
 }
 if(r.action==='claim'||r.action==='recover'){
  require(hex.test(r.code||'')&&hex.test(r.device||'')&&typeof r.name==='string'&&r.name.trim().length>=3&&r.name.trim().length<=100,'Código o nombre inválidos.');
  const masterId=await tx.get('staffMaster');const id=masterId||crypto.randomUUID();
  const old=masterId?await tx.get('staffUser:'+masterId):null;
  if(r.action==='claim')require(!masterId,'El usuario maestro ya existe. Solicita autorización o utiliza la recuperación privada.');
  else require(masterId&&!await tx.get('staffRecoveryUsed:'+await digest(r.code)),'La recuperación ya fue utilizada o aún no existe maestro.');
  const expected=r.action==='claim'?env.STAFF_BOOTSTRAP_HASH:env.STAFF_RECOVERY_HASH;
  require(expected&&await digest(r.code)===expected,'Código maestro incorrecto.');
  const known=await tx.get('staffKey:'+hash);require(!known||known===id,'Este perfil ya pertenece a otra cuenta. Crea un perfil nuevo.');
  const user=await saveUser(tx,env,{id,key:envelope.key,device:r.device,name:r.name.trim(),role:'master',plants:p.plants,permissions:defaultPermissions('master'),active:true},now,old?.id||'bootstrap');
  await tx.put('staffMaster',id);await tx.put('staffEnabled',true);if(r.action==='recover')await tx.put('staffRecoveryUsed:'+await digest(r.code),true);
  await tx.delete('staffPending:'+hash);return {ok:true,certificate:user.certificate,directory:await directory(tx,env,now)};
 }
 if(r.action==='status'){
  const id=await tx.get('staffKey:'+hash),u=id?await tx.get('staffUser:'+id):null;
  if(!u)return {ok:true,pending:true,enabled:!!await tx.get('staffEnabled')};
  if(u.key!==envelope.key)return {ok:true,replacement:u.certificate};
  // A revoked phone must be able to learn its own signed revocation.
  return {ok:true,certificate:u.certificate,...(u.active?{directory:await directory(tx,env,now)}:{})};
 }
 const {user}=await authenticate(tx,envelope,'staff',env,now);
 require(user.role==='master','Solo el usuario maestro puede administrar cuentas.');
 if(r.action==='list'){
  const pending=await tx.list({prefix:'staffPending:',limit:200});return {ok:true,directory:await directory(tx,env,now),pending:[...pending.entries()].filter(([,v])=>v.expires>=now).map(([k,v])=>({id:k.slice('staffPending:'.length),name:v.name,expires:v.expires}))};
 }
 if(r.action==='grant'){
  require(hex.test(r.request||''),'Escanea la solicitud del teléfono.');const pending=await tx.get('staffPending:'+r.request);require(pending&&pending.expires>=now,'La solicitud venció. Genera otra en el teléfono del encargado.');
  require(!await tx.get('staffKey:'+r.request),'Esta solicitud ya fue autorizada.');
  require(['admin','encargado'].includes(r.role)&&typeof r.name==='string'&&r.name.trim().length>=3&&r.name.trim().length<=100,'Nombre o rol inválidos.');
  require(Array.isArray(r.plants)&&r.plants.length>0&&new Set(r.plants).size===r.plants.length&&r.plants.every(x=>p.plants.includes(x)),'Selecciona plantas válidas.');
  const allowed=defaultPermissions('admin');require(Array.isArray(r.permissions)&&r.permissions.every(x=>allowed.includes(x)),'Permisos inválidos.');
  const granted=await saveUser(tx,env,{id:crypto.randomUUID(),key:pending.key,device:pending.device,name:r.name.trim(),role:r.role,plants:r.plants,permissions:r.role==='admin'?allowed:[...new Set([...defaultPermissions('encargado'),...r.permissions])],active:true},now,user.id);
  await tx.delete('staffPending:'+r.request);return {ok:true,certificate:granted.certificate,directory:await directory(tx,env,now)};
 }
 if(r.action==='update'){
  const target=await tx.get('staffUser:'+r.id);require(target&&target.role!=='master','No se puede desactivar ni reemplazar al maestro desde esta opción.');
  require(r.expected===target.revision,'La cuenta cambió. Actualiza la lista y reintenta.');
  require(typeof r.active==='boolean'&&['admin','encargado'].includes(r.role)&&typeof r.name==='string'&&r.name.trim().length>=3&&r.name.trim().length<=100,'Cuenta inválida.');
  require(Array.isArray(r.plants)&&r.plants.length>0&&new Set(r.plants).size===r.plants.length&&r.plants.every(x=>p.plants.includes(x)),'Selecciona plantas válidas.');
  require(Array.isArray(r.permissions)&&r.permissions.every(x=>defaultPermissions('admin').includes(x)),'Permisos inválidos.');
  let replacement={};if(r.request){require(hex.test(r.request));const pending=await tx.get('staffPending:'+r.request);require(pending&&pending.expires>=now&&!await tx.get('staffKey:'+r.request),'Solicitud de reemplazo inválida.');replacement={key:pending.key,device:pending.device};await tx.delete('staffPending:'+r.request);}
  const updated=await saveUser(tx,env,{...target,...replacement,name:r.name.trim(),role:r.role,plants:r.plants,permissions:r.role==='admin'?defaultPermissions('admin'):[...new Set([...defaultPermissions('encargado'),...r.permissions])],active:r.active},now,user.id);
  return {ok:true,certificate:updated.certificate,directory:await directory(tx,env,now)};
 }
 throw Error('Acción de cuentas no reconocida.');
}

export async function rosterBody(qr){
 require(typeof qr==='string'&&/^SB[45]:/.test(qr)&&qr.length<=8192,'Actualiza el padrón con el maestro.');
 const [encoded]=qr.slice(4).split('.');let data=un64(encoded);
 if(qr.startsWith('SB5:')){
  const reader=new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate')).getReader();const chunks=[];let size=0;
  try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;require(size<=8192,'Credencial demasiado grande.');chunks.push(value);}}finally{await reader.cancel();}
  data=new Uint8Array(size);let offset=0;for(const chunk of chunks){data.set(chunk,offset);offset+=chunk.length;}
 }
 require(data.length<=8192,'Credencial demasiado grande.');return JSON.parse(dec.decode(data));
}
export async function authorizeWallet(tx,r,user,env,now){
 if(r.action==='subjects'){
  require(user.role==='master'&&Array.isArray(r.items)&&r.items.length<=100,'Solo el maestro migra trabajadores anteriores.');
  for(const item of r.items){require(hex.test(item.tag||'')&&user.plants.includes(item.plant));const old=await tx.get('staffSubject:'+item.tag);require(!old||old===item.plant,'Trabajador registrado en otra planta.');await tx.put('staffSubject:'+item.tag,item.plant);}return {ok:true};
 }
 if(!['bind','revoke'].includes(r.action))return null;
 permission(user,'link',r.plant);let plant=await tx.get('staffSubject:'+r.tag);
 if(!plant&&typeof r.roster==='string'){
  const body=await rosterBody(r.roster),proof=body.staff;require(proof,'El maestro debe sincronizar este trabajador anterior.');delete body.staff;
  const [grantData,grantSig]=proof.certificate.slice(5).split('.');require(proof.certificate.startsWith('CSA1:')&&await verify(policy(env).authority,'CSA1:'+grantData,grantSig),'Alta sin permiso individual.');const grant=JSON.parse(dec.decode(un64(grantData)));
  require(grant.cluster===policy(env).cluster&&grant.kind==='grant'&&grant.active&&grant.issued<=body.time+120000&&body.time<=now+120000);permission(grant,'worker',body.plant);
  const payload=proof.payload??JSON.stringify(body);require(typeof payload==='string'&&payload.length<=8192&&JSON.stringify(JSON.parse(payload))===JSON.stringify(body),'Contenido del alta alterado.');
  require(await verify(grant.key,'staff-event|'+grant.cluster+'|roster|'+payload,proof.signature),'Alta alterada.');
  require(typeof body.ci==='string'&&/^[0-9]{5,20}(?:-[A-Z0-9]{1,6})?$/.test(body.ci));
  const id=policy(env).personPrefix+body.ci,key=await crypto.subtle.importKey('raw',enc.encode(env.NETWORK_KEY),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const tag=Array.from(new Uint8Array(await crypto.subtle.sign('HMAC',key,enc.encode(grant.cluster+'|wallet-person|'+id))),b=>b.toString(16).padStart(2,'0')).join('');require(tag===r.tag,'Trabajador incompatible.');plant=body.plant;await tx.put('staffSubject:'+r.tag,plant);
 }
 require(plant&&plant===r.plant,'Sincroniza el trabajador con el maestro antes de vincularlo.');permission(user,'link',plant);return null;
}
