const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm'),fs=require('node:fs'),crypto=require('node:crypto');
function fixture(){
 let sheets={},locked=false,denyLock=false,flushes=0;
 function sheet(name){return {getDataRange:()=>({getValues:()=>sheets[name].map(r=>r.slice())}),appendRow:r=>sheets[name].push(r),getRange:(r,c,h=1,w=1)=>({setValue:v=>{sheets[name][r-1][c-1]=v;},setValues:rs=>rs.forEach((row,i)=>row.forEach((v,j)=>sheets[name][r-1+i][c-1+j]=v))})};}
 const ctx={console,Date,Utilities:{Charset:{UTF_8:'utf8'},DigestAlgorithm:{SHA_256:'sha256'},computeDigest:(_,s)=>[...crypto.createHash('sha256').update(s).digest()],getUuid:()=>crypto.randomUUID(),formatDate:(d,tz,format)=>{const fmt=new Intl.DateTimeFormat('sv-SE',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(d);return format==='HH:mm:ss'?fmt.slice(11):fmt.slice(0,10);}},SpreadsheetApp:{openById:()=>({getSheetByName:sheet}),flush:()=>{flushes++;}},PropertiesService:{getScriptProperties:()=>({getProperty:()=> 'test'})},ContentService:{MimeType:{JSON:'json'},createTextOutput:s=>({setMimeType:()=>JSON.parse(s)})},LockService:{getScriptLock:()=>({tryLock:()=>{if(denyLock||locked)return false;locked=true;return true;},releaseLock:()=>{locked=false;}})}};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync('backend/Code.gs','utf8'),ctx);
 const headers=vm.runInContext('HEADERS',ctx);for(const [name,h] of Object.entries(headers))sheets[name]=[h];
 const token='a'.repeat(64),other='b'.repeat(64),qr='SB1:'+'c'.repeat(64),today=ctx.Utilities.formatDate(new Date(),'America/La_Paz','yyyy-MM-dd');
 ctx.add_('Plantas',{id:'A',nombre:'A',activo:true});ctx.add_('Plantas',{id:'B',nombre:'B',activo:true});
 ctx.add_('Operadores',{id:'opA',nombre:'Admin',planta:'A',rol:'admin',activo:true,tokenHash:ctx.hash_(token)});
 ctx.add_('Operadores',{id:'opB',nombre:'Encargado',planta:'B',rol:'encargado',activo:true,tokenHash:ctx.hash_(other)});
 ctx.add_('Personas',{id:'SAP-123456',sap:'123456',nombre:'Persona prueba',planta:'A',centroCosto:'CC1',tipo:'EMPLEADO',activo:true,qrHash:ctx.hash_(qr),comidas:'desayuno,almuerzo,cena'});
 ctx.add_('Tarifas',{desde:'2026-01-01',desayuno:12,almuerzo:23,cena:19});
 const scan=(changes={})=>({token,action:'scan',plant:'A',meal:'almuerzo',qr,requestId:crypto.randomUUID(),...changes});
 const call=r=>ctx.doPost({postData:{contents:JSON.stringify(r)}});
 return {ctx,sheets,scan,call,token,other,qr,today,deny:()=>denyLock=true,flushes:()=>flushes};
}
test('one meal globally, including another plant and operator',()=>{const f=fixture();assert.equal(f.call(f.scan()).status,'accepted');assert.equal(f.call(f.scan({plant:'B',token:f.other})).status,'duplicate');assert.equal(f.sheets.Consumos.length,2);assert.equal(f.flushes(),2);});
test('breakfast lunch dinner each permitted independently',()=>{const f=fixture();for(const meal of ['desayuno','almuerzo','cena'])assert.equal(f.call(f.scan({meal})).status,'accepted');assert.equal(f.sheets.Consumos.length,4);});
test('retry recovers original without a second authorization',()=>{const f=fixture(),r=f.scan();assert.equal(f.call(r).status,'accepted');assert.equal(f.call(r).status,'recovered');assert.equal(f.sheets.Consumos.length,2);});
test('retry still works after credential revocation',()=>{const f=fixture(),r=f.scan();f.call(r);f.sheets.Personas[1][7]='revoked';assert.equal(f.call(r).status,'recovered');});
test('retry id cannot be reused for another meal',()=>{const f=fixture(),r=f.scan();f.call(r);assert.equal(f.call({...r,meal:'cena'}).ok,false);});
test('unauthorized operator cannot write at another plant',()=>{const f=fixture();assert.equal(f.call(f.scan({token:f.other})).ok,false);assert.equal(f.sheets.Consumos.length,1);});
test('inactive person is denied',()=>{const f=fixture();f.sheets.Personas[1][6]=false;assert.equal(f.call(f.scan()).ok,false);});
test('invalid and revoked tokens denied',()=>{const f=fixture();assert.equal(f.call(f.scan({token:'x'})).ok,false);f.sheets.Operadores[1][4]=false;assert.equal(f.call(f.scan()).ok,false);});
test('visitor expiry and host plant checked',()=>{const f=fixture();f.sheets.Personas[1][5]='VISITA';f.sheets.Personas[1][8]='2000-01-01';assert.equal(f.call(f.scan()).ok,false);f.sheets.Personas[1][8]='2099-01-01';assert.equal(f.call(f.scan({plant:'B'})).ok,false);});
test('new visitor QR retains consumption identity',()=>{const f=fixture();const req={token:f.token,plant:'A',action:'visitor',name:'Visitante Prueba',document:'BO CI 1234567',expires:f.today,meals:'almuerzo',costCenter:'CC1'};const a=f.call(req);assert.equal(a.ok,true);assert.equal(f.call(f.scan({qr:a.qr})).status,'accepted');const b=f.call(req);assert.equal(f.call(f.scan({qr:b.qr})).status,'duplicate');});
test('supervisor cannot issue employee QR',()=>{const f=fixture();assert.equal(f.call({token:f.other,plant:'B',action:'issue',personId:'SAP-123456'}).ok,false);});
test('missing tariff refuses instead of recording zero',()=>{const f=fixture();f.sheets.Tarifas.splice(1);assert.equal(f.call(f.scan()).ok,false);assert.equal(f.sheets.Consumos.length,1);});
test('lock unavailable causes no write',()=>{const f=fixture();f.deny();assert.equal(f.call(f.scan()).ok,false);assert.equal(f.sheets.Consumos.length,1);});
test('invalid meal and malformed QR rejected',()=>{const f=fixture();assert.equal(f.call(f.scan({meal:'extra'})).ok,false);assert.equal(f.call(f.scan({qr:'123456'})).ok,false);});
test('historical cost preserved after tariff change',()=>{const f=fixture();f.call(f.scan());f.sheets.Tarifas[1][2]=99;assert.equal(f.sheets.Consumos[1][9],23);});
test('monthly report cannot expose another plant to an operator',()=>{const f=fixture();f.call(f.scan());const r=f.call({action:'report',token:f.other,plant:'B',month:f.today.slice(0,7)});assert.equal(r.count,0);assert.equal(f.call({action:'report',token:f.other,plant:'A',month:f.today.slice(0,7)}).ok,false);});
test('administrator creates scoped operator without storing raw secret',()=>{const f=fixture();const r=f.call({action:'operator',token:f.token,plant:'A',name:'Encargado nuevo'});assert.equal(r.ok,true);assert.equal(r.access.length,64);assert.ok(!JSON.stringify(f.sheets.Operadores).includes(r.access));assert.equal(f.call(f.scan({token:r.access})).status,'accepted');assert.equal(f.call(f.scan({token:r.access,plant:'B'})).ok,false);});
