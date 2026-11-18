// SCRAM-SHA-256 (RFC 5802/7677) over HTTPS inside device-signed requests.
// Password stretching happens on the client. Only salt, StoredKey and ServerKey
// are stored here; none is included in the replicated account directory.
const enc=new TextEncoder(),hex=/^[a-f0-9]{64}$/;
const require=(ok,message)=>{if(!ok)throw Error(message||'Solicitud de acceso inválida.');};
export const base64=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes)));
export function bytes(text,size=32){require(typeof text==='string'&&text.length<=128);const value=Uint8Array.from(atob(text),c=>c.charCodeAt(0));require(value.length===size&&base64(value)===text);return value;}
export const random=()=>Array.from(crypto.getRandomValues(new Uint8Array(32)),b=>b.toString(16).padStart(2,'0')).join('');
export async function hash(value){return new Uint8Array(await crypto.subtle.digest('SHA-256',value));}
export async function hmac(key,message){return new Uint8Array(await crypto.subtle.sign('HMAC',await crypto.subtle.importKey('raw',key,{name:'HMAC',hash:'SHA-256'},false,['sign']),enc.encode(message)));}
export function equal(a,b){let difference=a.length^b.length;for(let i=0;i<Math.max(a.length,b.length);i++)difference|=(a[i]||0)^(b[i]||0);return difference===0;}
export function username(value){require(typeof value==='string','Escribe tu usuario.');const name=value.trim().toLowerCase();require(/^[a-z0-9][a-z0-9._-]{2,39}$/.test(name),'El usuario debe tener entre 3 y 40 letras, números, puntos, guiones o guiones bajos.');return name;}
export function credentials(value){require(value&&value.scheme==='SCRAM-SHA-256'&&value.iterations===600000,'Actualiza la app para guardar una contraseña segura.');for(const field of ['salt','storedKey','serverKey'])bytes(value[field]);return {scheme:value.scheme,iterations:600000,salt:value.salt,storedKey:value.storedKey,serverKey:value.serverKey};}
export async function unbound(){const pair=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);return {key:base64(await crypto.subtle.exportKey('spki',pair.publicKey)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,''),device:random()};}
const denied=()=>({ok:false,error:'Usuario o contraseña incorrectos, o cuenta desactivada.',code:'LOGIN_DENIED'});
async function limit(tx,key,max,now){const id='staffLoginLimit:'+key,old=await tx.get(id);const next=old&&old.until>now?old:{count:0,until:now+300000};if(next.count>=max)return false;next.count++;await tx.put(id,next);return true;}
async function cleanup(tx,now){for(const prefix of ['staffLoginLimit:','staffLoginChallenge:'])for(const [k,v] of await tx.list({prefix,limit:1000}))if((v.until||v.expires)<now)await tx.delete(k);}
export async function loginOperation(tx,r,envelope,env,now,api){
 const keyHash=await api.digest(envelope.key);
 if(r.action==='loginStart'){
  const name=username(r.username);require(hex.test(r.device||'')&&hex.test(r.clientNonce||''));
  await cleanup(tx,now);
  // Return failures instead of throwing: transaction rollback must not erase rate limits.
  if(!await limit(tx,'global',200,now)||!await limit(tx,'key:'+keyHash,12,now)||!await limit(tx,'name:'+await api.digest(name),8,now))return {ok:false,code:'LOGIN_WAIT',error:'Demasiados intentos. Espera cinco minutos y vuelve a entrar.'};
  const id=await tx.get('staffUsername:'+name),account=id?await tx.get('staffUser:'+id):null,record=id?await tx.get('staffPassword:'+id):null;
  const known=account&&account.active&&!account.deleted&&account.username===name&&record;
  // Unknown users get the same challenge shape without exposing account existence.
  const salt=known?record.salt:base64(await hmac(enc.encode(env.STAFF_SIGNING_KEY),'unknown-salt|'+name));
  const nonce=r.clientNonce+random(),serverFirst='r='+nonce+',s='+salt+',i=600000',challenge=random();
  await tx.put('staffLoginChallenge:'+keyHash,{id:challenge,user:known?id:null,version:known?record.version:0,name,device:r.device,clientFirst:'n='+name+',r='+r.clientNonce,serverFirst,nonce,expires:now+120000});
  return {ok:true,challenge,serverFirst};
 }
 if(r.action==='loginFinish'){
  const name='staffLoginChallenge:'+keyHash,c=await tx.get(name);
  if(!c||c.id!==r.challenge||c.expires<now)return denied();
  await tx.delete(name); // Exactly one attempt per challenge, including wrong proofs.
  let proof;try{proof=bytes(r.proof);}catch{return denied();}
  const user=c.user?await tx.get('staffUser:'+c.user):null,record=c.user?await tx.get('staffPassword:'+c.user):null;
  if(!user||!user.active||user.deleted||!record||record.version!==c.version||user.username!==c.name)return denied();
  const final='c=biws,r='+c.nonce,message=c.clientFirst+','+c.serverFirst+','+final;
  const signature=await hmac(bytes(record.storedKey),message),clientKey=proof.map((b,i)=>b^signature[i]);
  if(!equal(await hash(clientKey),bytes(record.storedKey)))return denied();
  const known=await tx.get('staffKey:'+keyHash);if(known&&known!==user.id)return denied();
  const updated=user.key===envelope.key&&user.device===c.device?user:await api.saveUser(tx,env,{...user,key:envelope.key,device:c.device},now,user.id);
  return {ok:true,certificate:updated.certificate,directory:await api.directory(tx,env,now),serverSignature:base64(await hmac(bytes(record.serverKey),message))};
 }
 throw Error('Acción de acceso inválida.');
}
