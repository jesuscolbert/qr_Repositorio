package bo.soboce.comedor;

import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** Authenticated client transport for iOS. It never grants a tunnel lease or billing administration. */
final class MobileControl {
 final PeerEngine engine;final Map<String,Long> nonces=new LinkedHashMap<>();
 MobileControl(PeerEngine engine){this.engine=engine;}
 JSONObject handle(JSONObject envelope)throws Exception {
  long now=System.currentTimeMillis(),time=envelope.getLong("time");String nonce=envelope.getString("nonce"),payload=envelope.getString("payload");
  PeerConfig.require(engine.shared!=null&&nonce.matches("[a-f0-9]{64}")&&Math.abs(now-time)<=120000,"Revisa fecha y configuración del iPhone.");
  PeerConfig.require(PeerConfig.equal(engine.config.mac("control-request|"+time+"|"+nonce+"|"+payload),envelope.getString("signature")),"Consulta no autorizada.");
  synchronized(nonces){nonces.entrySet().removeIf(x->x.getValue()<now-120000);PeerConfig.require(!nonces.containsKey(nonce)&&nonces.size()<2048,"Consulta repetida; espera y reintenta.");nonces.put(nonce,now);}
  long epoch=engine.shared.lease.epoch;JSONObject reply;
  if(!engine.shared.leading())reply=new JSONObject().put("ok",false).put("error","leader").put("definitive",true);
  else try{reply=dispatch(new JSONObject(payload),now,epoch);}catch(IllegalArgumentException e){reply=new JSONObject().put("ok",false).put("definitive",true).put("error",e.getMessage());}
  // A lost lease after a write is deliberately uncertain. A retry must only recover its original ID.
  if(!engine.shared.leading()||epoch!=engine.shared.lease.epoch)reply=SharedNetwork.uncertain();
  reply.put("serverTime",now).put("epoch",epoch).put("controlProtocol",1);String body=reply.toString();
  return new JSONObject().put("payload",body).put("signature",engine.config.mac("control-reply|"+nonce+"|"+body));
 }
 JSONObject dispatch(JSONObject r,long now,long epoch)throws Exception {
  String action=r.getString("action");PeerConfig c=engine.config;PeerDatabase db=engine.db;
  if(action.equals("bootstrap")){
   JSONArray ids=new JSONArray(),documents=new JSONArray(),keys=new JSONArray();
   for(int i=0;i<c.seed.getJSONArray("people").length();i++){JSONObject p=c.seed.getJSONArray("people").getJSONObject(i);ids.put(p.getString("id"));if(!p.optString("documento").isEmpty())documents.put(p.getString("documento"));}
   for(int i=0;i<c.nodes.length();i++)keys.put(c.nodes.getJSONObject(i).getString("publicKey"));
   return MealLedger.ok().put("cluster",c.cluster).put("plants",c.seed.getJSONArray("plants")).put("rates",c.seed.getJSONArray("rates")).put("ratesConfigured",c.seed.optBoolean("ratesConfigured",true)).put("seedIds",ids).put("seedDocuments",documents).put("legacyKeys",keys).put("legacyOperators",c.nodes).put("personPrefix",c.manifest.optString("installation").startsWith("frigor-")?"FRIGOR-CI-":"CI-");
  }
  if(action.equals("page"))return page(r,now);
  if(action.equals("sync")){
   // Replication remains available after expiry, as in Android's existing exchange protocol.
   synchronized(db){android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{
    new RosterLedger(db,c).merge(r.getJSONArray("roster"));new SharedLedger(db,c).merge(r.getJSONArray("events"));
    JSONObject reply=MealLedger.ok().put("vector",new SharedLedger(db,c).vector());sql.setTransactionSuccessful();return reply;
   }finally{sql.endTransaction();}}
  }
  if(action.equals("recover")){
   JSONObject req=r.getJSONObject("request");String id=req.getString("requestId");PeerConfig.require(id.matches("[a-f0-9-]{36}"),"Solicitud inválida.");
   synchronized(db){JSONObject saved=db.get("shared_results",id);if(saved==null)return SharedNetwork.uncertain();PeerConfig.require(OfflineLedger.sameRequest(req,saved.getJSONObject("request")),"Solicitud incompatible.");return SharedLedger.recovered(saved.getJSONObject("reply")).put("receipt",new SharedLedger(db,c).eventFor(id));}
  }
  if(action.equals("scan")){
   PeerConfig.require(r.getLong("epoch")==epoch,"El servidor cambió antes de registrar. Vuelve a sincronizar.");
   SharedLedger ledger=new SharedLedger(db,c);JSONObject req=r.getJSONObject("request");return ledger.scan(req,r.getJSONObject("operator"),now,engine.license.allowed()).put("receipt",ledger.eventFor(req.getString("requestId")));
  }
  if(action.equals("walletStatus")||action.equals("walletBind")||action.equals("walletRevoke")){
   QrPrintData.requirePermission(engine.license);WalletRegistry wallet=engine.walletRegistry();String person=r.getString("personId");wallet.authority(wallet.person(person));
   if(action.equals("walletStatus")){JSONObject binding=wallet.lookup(person);return MealLedger.ok().put("binding",binding==null?JSONObject.NULL:binding);}
   PeerConfig.require(r.optBoolean("identityChecked"),"Verifica presencialmente el carnet y su titular.");
   if(action.equals("walletBind"))return MealLedger.ok().put("certificate",wallet.bind(person,r.getString("pairing"),r.getLong("expected"),engine.license,now));
   wallet.revoke(person,r.getLong("expected"),r.getString("requestId"),engine.license);return MealLedger.ok();
  }
  throw new IllegalArgumentException("Acción de control no reconocida.");
 }
 JSONObject page(JSONObject r,long now)throws Exception {
  String kind=r.getString("kind"),cursor=r.optString("after"),field;PeerConfig.require(cursor.length()<=160,"Cursor inválido.");List<JSONObject> all=new ArrayList<>();
  synchronized(engine.db){
   if(kind.equals("people")){field="id";RosterLedger roster=new RosterLedger(engine.db,engine.config);for(JSONObject p:engine.db.rows("people"))all.add(new JSONObject(p.toString()).put("card",roster.qrFor(p.getString("id"))));}
   else if(kind.equals("bindings")){field="tag";all.addAll(engine.db.rows("wallet_bindings"));}
   else if(kind.equals("records")){field="registro";String start=LocalDate.parse(MealLedger.day(now)).minusDays(35).toString();for(JSONObject record:new OfflineLedger(engine.db,engine.config).records())if(record.getString("fecha").compareTo(start)>=0)all.add(record);}
   else throw new IllegalArgumentException("Página no reconocida.");
  }
  all.sort(Comparator.comparing(x->x.optString(field)));JSONArray rows=new JSONArray();String next=cursor;boolean more=false;
  for(JSONObject item:all)if(item.getString(field).compareTo(cursor)>0){if(rows.length()==200){more=true;break;}rows.put(item);next=item.getString(field);}
  return MealLedger.ok().put("items",rows).put("after",next).put("more",more).put("from",LocalDate.parse(MealLedger.day(now)).minusDays(35).toString());
 }
}
