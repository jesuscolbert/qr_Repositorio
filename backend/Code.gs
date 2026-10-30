const TZ = 'America/La_Paz';
const HEADERS = {
 Personas: ['id','sap','nombre','planta','centroCosto','tipo','activo','qrHash','vence','comidas'],
 Operadores: ['id','nombre','planta','rol','activo','tokenHash'],
 Consumos: ['clave','solicitud','persona','nombre','fecha','comida','planta','operador','hora','precio','centroCosto','qrHash'],
 Tarifas: ['desde','desayuno','almuerzo','cena'],
 Plantas: ['id','nombre','activo']
};
function setup() {
 const props=PropertiesService.getScriptProperties();
 let id=props.getProperty('SHEET_ID');
 const book=id?SpreadsheetApp.openById(id):SpreadsheetApp.create('SOBOCE · Control de comedor');
 props.setProperty('SHEET_ID',book.getId()); book.setSpreadsheetTimeZone(TZ);
 Object.keys(HEADERS).forEach(name=>{let s=book.getSheetByName(name); if(!s){s=book.insertSheet(name);s.appendRow(HEADERS[name]);s.setFrozenRows(1);s.getRange(1,1,1,HEADERS[name].length).setFontWeight('bold');}});
 if(book.getSheetByName('Tarifas').getLastRow()===1) book.getSheetByName('Tarifas').appendRow(['2026-09-01',12,23,19]);
 if(book.getSheetByName('Plantas').getLastRow()===1) ['RDMX P.I. SCZ','PTA. WARNES','OF. COM. SCZ','PREF SANTA CRUZ'].forEach(p=>book.getSheetByName('Plantas').appendRow([p,p,true]));
 console.log(book.getUrl());
}
function db_(){return SpreadsheetApp.openById(PropertiesService.getScriptProperties().getProperty('SHEET_ID'));}
function rows_(name){const values=db_().getSheetByName(name).getDataRange().getValues();return values.slice(1).map((r,index)=>Object.assign({_row:index+2},Object.fromEntries(HEADERS[name].map((h,i)=>[h,r[i]])))).filter(r=>r[HEADERS[name][0]]!=='');}
function add_(name,o){db_().getSheetByName(name).appendRow(HEADERS[name].map(h=>safe_(o[h]===undefined?'':o[h])));}
function safe_(v){return typeof v==='string'&&/^[=+@-]/.test(v)?"'"+v:v;}
function date_(v){return v instanceof Date?Utilities.formatDate(v,TZ,'yyyy-MM-dd'):String(v);}
function active_(v){return v===true || String(v).toLowerCase()==='true';}
function hash_(v){return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256,String(v),Utilities.Charset.UTF_8).map(b=>('0'+((b+256)%256).toString(16)).slice(-2)).join('');}
function secret_(){return Utilities.getUuid().replace(/-/g,'')+Utilities.getUuid().replace(/-/g,'');}
function fail_(message){const e=new Error(message);e.definitive=true;throw e;}
function require_(condition,message){if(!condition)fail_(message);}
function text_(v,max){return String(v||'').trim().slice(0,max||180);}
function withLock_(fn){const lock=LockService.getScriptLock();require_(lock.tryLock(20000),'Sistema ocupado. Reintenta la misma operación.');try{return fn();}finally{SpreadsheetApp.flush();lock.releaseLock();}}
function doPost(e){let out;try{require_(String(e.postData.contents).length<20000,'Solicitud demasiado grande.');const req=JSON.parse(e.postData.contents);out=withLock_(()=>handle_(req));}catch(err){out={ok:false,error:err.definitive?String(err.message):'No se pudo confirmar. Reintenta la misma operación.',definitive:!!err.definitive};}return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);}
function operator_(token){require_(/^[a-f0-9]{64}$/.test(String(token)),'Acceso inválido.');const op=rows_('Operadores').find(o=>active_(o.activo)&&o.tokenHash===hash_(token));require_(op,'Acceso revocado o inválido.');return op;}
function plant_(req,op){const p=text_(req.plant);require_(rows_('Plantas').some(x=>x.id===p&&active_(x.activo)),'Planta inválida.');require_(op.rol==='admin'||op.planta===p,'No tienes permiso para esta planta.');return p;}
function publicPerson_(p){return {id:p.id,sap:String(p.sap),nombre:p.nombre,planta:p.planta,tipo:p.tipo};}
function handle_(req){
 const op=operator_(req.token), now=new Date(), today=Utilities.formatDate(now,TZ,'yyyy-MM-dd');
 if(req.action==='session')return {ok:true,operator:op.nombre,role:op.rol,today,plants:rows_('Plantas').filter(p=>active_(p.activo)&&(op.rol==='admin'||p.id===op.planta)).map(p=>p.id)};
 const plant=plant_(req,op);
 if(req.action==='operator'){
  require_(op.rol==='admin','Solo administración puede crear encargados.');
  const name=text_(req.name,100);require_(name.length>=3,'Nombre de encargado inválido.');
  const token=secret_();add_('Operadores',{id:Utilities.getUuid(),nombre:name,planta:plant,rol:'encargado',activo:true,tokenHash:hash_(token)});
  return {ok:true,access:token,person:name,plant};
 }
 if(req.action==='report'){
  require_(/^\d{4}-(0[1-9]|1[0-2])$/.test(req.month),'Mes inválido.');
  const records=rows_('Consumos').filter(r=>date_(r.fecha).slice(0,7)===req.month&&r.planta===plant);
  const cols=['fecha','hora','persona','nombre','comida','planta','operador','precio','centroCosto'];
  const quote=v=>'"'+String(safe_(v)).replace(/"/g,'""')+'"';
  return {ok:true,csv:'\uFEFF'+[cols.join(','),...records.map(r=>cols.map(k=>quote(k==='fecha'?date_(r[k]):r[k])).join(','))].join('\r\n'),count:records.length};
 }
 if(req.action==='people')return {ok:true,people:rows_('Personas').filter(p=>active_(p.activo)&&p.planta===plant).map(publicPerson_)};
 if(req.action==='today'){const rs=rows_('Consumos').filter(r=>date_(r.fecha)===today&&r.planta===plant);return {ok:true,count:rs.length,total:rs.reduce((a,r)=>a+Number(r.precio),0),items:rs.slice(-30).reverse().map(r=>({nombre:r.nombre,comida:r.comida,hora:r.hora}))};}
 if(req.action==='scan'){
  require_(['desayuno','almuerzo','cena'].includes(req.meal),'Comida inválida.');
  require_(/^[a-f0-9-]{36}$/.test(String(req.requestId)),'Identificador de solicitud inválido.');
  require_(/^SB1:[a-f0-9]{64}$/.test(String(req.qr)),'QR no reconocido.');
  const records=rows_('Consumos');
  const retry=records.find(r=>r.solicitud===req.requestId);
  if(retry){require_(retry.qrHash===hash_(req.qr)&&retry.comida===req.meal&&retry.planta===plant&&retry.operador===op.id,'Solicitud incompatible.');return {ok:true,status:'recovered',message:'Registro anterior confirmado. No entregar otra ración.',person:retry.nombre,plant:retry.planta,time:retry.hora};}
  const p=rows_('Personas').find(p=>p.qrHash===hash_(req.qr));require_(p,'QR revocado o desconocido.');
  require_(active_(p.activo),'Persona inactiva.');
  require_(!p.vence||date_(p.vence)>=today,'Autorización vencida.');
  require_(String(p.comidas).split(',').includes(req.meal),'Esta comida no está autorizada.');
  require_(p.tipo!=='VISITA'||p.planta===plant,'Visita autorizada para otra planta.');
  const key=p.id+'|'+today+'|'+req.meal;
  const previous=records.find(r=>r.clave===key);
  if(previous)return {ok:true,status:'duplicate',message:'Ya consumió esta comida.',person:p.nombre,plant:previous.planta,time:previous.hora};
  const rate=rows_('Tarifas').filter(r=>date_(r.desde)<=today).sort((a,b)=>date_(b.desde).localeCompare(date_(a.desde)))[0];require_(rate&&Number.isFinite(Number(rate[req.meal]))&&Number(rate[req.meal])>=0,'No hay una tarifa válida.');
  const time=Utilities.formatDate(now,TZ,'HH:mm:ss');
  add_('Consumos',{clave:key,solicitud:req.requestId,persona:p.id,nombre:p.nombre,fecha:today,comida:req.meal,planta:plant,operador:op.id,hora:time,precio:Number(rate[req.meal]),centroCosto:p.centroCosto,qrHash:hash_(req.qr)});
  return {ok:true,status:'accepted',message:'Entrega autorizada',person:p.nombre,plant,time};
 }
 if(req.action==='issue'){
  require_(op.rol==='admin','Solo administración puede emitir o reemplazar QR de trabajadores.');
  const all=rows_('Personas'),p=all.find(p=>p.id===req.personId&&p.planta===plant&&active_(p.activo));require_(p,'Persona no encontrada.');
  const qr='SB1:'+secret_();db_().getSheetByName('Personas').getRange(p._row,8).setValue(hash_(qr));
  return {ok:true,qr,person:p.nombre,detail:'SAP '+p.sap+' · '+p.planta};
 }
 if(req.action==='visitor'){
  const name=text_(req.name,100),document=text_(req.document,60).toUpperCase().replace(/[^A-Z0-9]/g,'');
  require_(name.length>=3&&document.length>=5,'Nombre y documento de identidad son obligatorios.');
  require_(/^\d{4}-\d{2}-\d{2}$/.test(req.expires)&&req.expires>=today,'Fecha de vencimiento inválida.');
  const validDate=new Date(req.expires+'T12:00:00Z');require_(!isNaN(validDate.getTime())&&validDate.toISOString().slice(0,10)===req.expires,'Fecha inexistente.');
  require_(req.expires<=Utilities.formatDate(new Date(now.getTime()+30*86400000),TZ,'yyyy-MM-dd'),'La visita puede durar hasta 30 días.');
  const meals=String(req.meals||'').split(',');require_(meals.length&&meals.every(m=>['desayuno','almuerzo','cena'].includes(m)),'Selecciona comidas válidas.');
  const id='VIS-'+hash_(document),all=rows_('Personas'),existing=all.find(p=>p.id===id);
  require_(!existing||existing.planta===plant||op.rol==='admin','Visitante registrado en otra planta. Contacta a administración.');
  const qr='SB1:'+secret_(), p={id,sap:'',nombre:name,planta:plant,centroCosto:text_(req.costCenter,50),tipo:'VISITA',activo:true,qrHash:hash_(qr),vence:req.expires,comidas:meals.join(',')};
  require_(p.centroCosto.length>0,'Indica el centro de costo responsable.');
  if(existing)db_().getSheetByName('Personas').getRange(existing._row,1,1,HEADERS.Personas.length).setValues([HEADERS.Personas.map(h=>safe_(p[h]))]);else add_('Personas',p);
  return {ok:true,qr,person:name,detail:'Visita · '+plant+' · hasta '+req.expires};
 }
 fail_('Operación desconocida.');
}
// Ejecutar desde el editor de Apps Script. La clave solo se muestra al crearla.
function createOperator(name,plant,role){return withLock_(()=>{require_(['admin','encargado'].includes(role),'Rol inválido.');require_(rows_('Plantas').some(p=>p.id===plant),'Planta inválida.');const token=secret_();add_('Operadores',{id:Utilities.getUuid(),nombre:name,planta:plant,rol:role,activo:true,tokenHash:hash_(token)});return token;});}
function createFirstAdmin(){require_(rows_('Operadores').length===0,'Ya existe un administrador.');console.log(createOperator('Jesús Colbert','RDMX P.I. SCZ','admin'));}
