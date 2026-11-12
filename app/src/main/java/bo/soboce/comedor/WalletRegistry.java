package bo.soboce.comedor;

import bo.colbert.credential.WalletProtocol;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Online enrollment authority with an offline mirror. No worker can call the admin coordinator. */
final class WalletRegistry {
 interface Remote {JSONObject call(JSONObject request)throws Exception;}
 final PeerDatabase db;final PeerConfig config;final Remote remote;
 WalletRegistry(PeerDatabase db,PeerConfig config){this(db,config,r->SharedNetwork.http(config.raw.getString("coordinator")+"/wallet",r,"Bearer "+config.raw.getString("networkKey")));}
 WalletRegistry(PeerDatabase db,PeerConfig config,Remote remote){this.db=db;this.config=config;this.remote=remote;}
 static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS wallet_bindings(id TEXT PRIMARY KEY,json TEXT NOT NULL)");db.execSQL("CREATE TABLE IF NOT EXISTS wallet_audit(id TEXT PRIMARY KEY,json TEXT NOT NULL)");}
 String tag(String person)throws Exception{return config.mac("wallet-person|"+person);}
 JSONObject lookup(String person)throws Exception {JSONObject r=remote.call(new JSONObject().put("action","get").put("tag",tag(person)));PeerConfig.require(r.optBoolean("ok"),"No se pudo consultar la vinculación.");JSONObject b=r.optJSONObject("binding");if(b!=null)merge(b);return b;}
 void merge(JSONObject b)throws Exception {synchronized(db){String tag=b.getString("tag");PeerConfig.require(tag.matches("[a-f0-9]{64}")&&b.getLong("version")>0,"Vinculación incompatible.");JSONObject old=db.get("wallet_bindings",tag);if(old==null||old.getLong("version")<b.getLong("version"))db.save("wallet_bindings",tag,b);db.save("wallet_audit",tag+":"+b.getLong("version"),b);}}
 void refresh()throws Exception {
  long after=Long.parseLong(db.meta("walletCursor","0"));JSONObject r=remote.call(new JSONObject().put("action","changes").put("after",after));PeerConfig.require(r.optBoolean("ok"),"No se pudo actualizar la lista de celulares.");JSONArray items=r.getJSONArray("items");PeerConfig.require(items.length()<=100&&r.getLong("after")>=after,"Respuesta de vinculación inválida.");
  synchronized(db){SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{for(int i=0;i<items.length();i++)merge(items.getJSONObject(i));db.putMeta("walletCursor",Long.toString(r.getLong("after")));sql.setTransactionSuccessful();}finally{sql.endTransaction();}}
 }
 JSONObject person(String id)throws Exception {JSONObject p=db.get("people",id);PeerConfig.require(p!=null&&p.optBoolean("activo")&&!p.optString("tipo").equals("VISITA"),"Trabajador activo no encontrado. Sincroniza el padrón.");return p;}
 void authority(JSONObject p)throws Exception {JSONObject op=config.operator(-1);PeerConfig.require(config.shared&&(op.optString("role").equals("admin")||op.optString("plant").equals(p.getString("planta"))),"Solo puedes vincular trabajadores de tus plantas.");}
 String bind(String personId,String pairing,long expected,bo.colbert.licensing.LicenseClient license,long now)throws Exception {
  QrPrintData.requirePermission(license);JSONObject p=person(personId);authority(p);JSONObject pair=WalletProtocol.pairing(pairing,now);
  JSONObject r=new JSONObject().put("action","bind").put("tag",tag(personId)).put("keyHash",WalletProtocol.hash(pair.getString("k"))).put("expected",expected).put("actor",config.device).put("by",config.operator(-1).getString("name")).put("requestId",WalletProtocol.hash("bind|"+tag(personId)+"|"+pairing));
  JSONObject reply=remote.call(r);PeerConfig.require(reply.optBoolean("ok"),reply.optString("error","No se confirmó la vinculación. Consulta y reintenta."));JSONObject b=reply.getJSONObject("binding");merge(b);QrPrintData.requirePermission(license);
  return issue(p,pair.getString("k"),pair.getString("n"),b,b.getLong("time"));
 }
 void revoke(String personId,long expected,String requestId,bo.colbert.licensing.LicenseClient license)throws Exception {
  QrPrintData.requirePermission(license);JSONObject p=person(personId);authority(p);JSONObject r=remote.call(new JSONObject().put("action","revoke").put("tag",tag(personId)).put("expected",expected).put("actor",config.device).put("by",config.operator(-1).getString("name")).put("requestId",requestId));
  PeerConfig.require(r.optBoolean("ok"),r.optString("error","No se confirmó la revocación."));merge(r.getJSONObject("binding"));
 }
 String issue(JSONObject person,String key,String activation,JSONObject binding,long now)throws Exception {
  JSONObject b=new JSONObject().put("v",1).put("c",config.cluster).put("p",person.getString("id")).put("k",key).put("r",binding.getLong("version")).put("tag",binding.getString("tag")).put("a",activation).put("i",now).put("e",now+WalletProtocol.LIFETIME).put("name",person.getString("nombre")).put("document",PersonIdentity.label(person)).put("plant",person.getString("planta")).put("url",config.raw.getString("url")).put("by",binding.getString("by"));
  String body=WalletProtocol.b64(b.toString().getBytes(StandardCharsets.UTF_8));return WalletProtocol.CERT+body+"."+config.mac("wallet-cert|"+body);
 }
 JSONObject authenticated(String cert)throws Exception {JSONObject b=WalletProtocol.certificate(cert);PeerConfig.require(config.shared&&b.getString("c").equals(config.cluster)&&b.getString("tag").equals(tag(b.getString("p")))&&PeerConfig.equal(config.mac("wallet-cert|"+WalletProtocol.certificateBody(cert)),WalletProtocol.certificateMac(cert)),"Credencial alterada o de otra empresa.");return b;}
 void checkBinding(JSONObject b,JSONObject current,long when)throws Exception {
  if(current==null)return;long version=current.getLong("version"),issued=b.getLong("r");
  if(version>=issued&&current.getLong("time")<=when)PeerConfig.require(version==issued&&current.optBoolean("active")&&current.getString("keyHash").equals(WalletProtocol.hash(b.getString("k"))),"Celular revocado o reemplazado. Acude al encargado.");
 }
 JSONObject resolve(String qr,long when)throws Exception {JSONObject b=WalletProtocol.verifyQr(qr,when);authenticated(WalletProtocol.qrCertificate(qr));checkBinding(b,db.get("wallet_bindings",b.getString("tag")),when);return person(b.getString("p"));}
 JSONObject history(JSONObject request,long now)throws Exception {
  JSONObject b=WalletProtocol.verifyHistory(request,now);authenticated(request.getString("cert"));JSONObject p=person(b.getString("p"));JSONObject current=lookup(p.getString("id"));
  PeerConfig.require(current!=null&&current.getLong("version")==b.getLong("r")&&current.optBoolean("active")&&current.getString("keyHash").equals(WalletProtocol.hash(b.getString("k"))),"Celular revocado o reemplazado.");
  String first=LocalDate.parse(MealLedger.day(now)).minusDays(29).toString(),last=MealLedger.day(now);JSONArray rows=new JSONArray();List<JSONObject> all=new OfflineLedger(db,config).records();
  for(int i=all.size()-1;i>=0&&rows.length()<240;i--){JSONObject r=all.get(i);if(r.optString("persona").equals(p.getString("id"))&&r.optString("fecha").compareTo(first)>=0&&r.optString("fecha").compareTo(last)<=0)rows.put(new JSONObject().put("date",r.getString("fecha")).put("time",r.getString("hora")).put("meal",r.getString("comida")).put("plant",r.getString("planta")).put("review",r.optString("revision")));}
  return new JSONObject().put("ok",true).put("serverTime",now).put("items",rows).put("certificate",issue(p,b.getString("k"),b.getString("a"),current,now));
 }
}
