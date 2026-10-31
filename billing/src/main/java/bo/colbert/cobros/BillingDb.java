package bo.colbert.cobros;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.BitmapFactory;
import bo.colbert.licensing.LicenseProtocol;
import org.json.*;
import java.time.*;
import java.util.*;

/** Local source of truth. Public API has no path to these administrative methods. */
public final class BillingDb extends SQLiteOpenHelper {
 public static final ZoneId ZONE=ZoneId.of("America/La_Paz");
 public BillingDb(Context c,String name){super(c,name,null,1);}
 @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE items (kind TEXT NOT NULL,id TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(kind,id))");db.execSQL("CREATE TABLE audit (seq INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,action TEXT NOT NULL,subject TEXT NOT NULL)");}
 @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Versión de base no compatible.");}
 interface Op<T>{T run()throws Exception;}
 synchronized <T>T tx(Op<T> op)throws Exception{SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{T r=op.run();db.setTransactionSuccessful();return r;}finally{db.endTransaction();}}
 synchronized JSONObject get(String kind,String id)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE kind=? AND id=?",new String[]{kind,id})){if(!c.moveToFirst())return null;JSONObject row=new JSONObject(c.getString(0));if(kind.equals("receipt")){JSONObject image=get("image",id);if(image!=null)row.put("image",image.getString("value"));}return row;}}
 synchronized JSONArray list(String kind)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE kind=? ORDER BY rowid DESC",new String[]{kind})){while(c.moveToNext())a.put(new JSONObject(c.getString(0)));}return a;}
 private void put(String kind,String id,JSONObject data)throws Exception{if(kind.equals("receipt")&&data.has("image")){put("image",id,new JSONObject().put("id",id).put("value",data.getString("image")));data=new JSONObject(data.toString());data.remove("image");}ContentValues v=new ContentValues();v.put("kind",kind);v.put("id",id);v.put("data",data.toString());getWritableDatabase().insertWithOnConflict("items",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
 private void audit(String action,String subject,long now){ContentValues v=new ContentValues();v.put("time",now);v.put("action",action);v.put("subject",subject);getWritableDatabase().insertOrThrow("audit",null,v);}
 public synchronized JSONObject contract(String id)throws Exception{JSONObject c=get("contract",id);LicenseProtocol.require(c!=null,"Cliente no encontrado.");return c;}
 public synchronized JSONArray contracts()throws Exception{return list("contract");}
 public synchronized boolean empty(){return android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(),"items","kind=?",new String[]{"contract"})==0;}
 public static void id(String id){LicenseProtocol.require(id.matches("[a-zA-Z0-9_-]{1,80}"),"Identificador inválido.");}
 public void create(String id,String customer,String product,String productName,String token,long now)throws Exception{tx(()->{
  id(id);id(product);LicenseProtocol.require(customer.trim().length()>=2&&customer.length()<=120&&productName.length()<=120&&token.matches("[a-f0-9]{64}"),"Revisa cliente, producto y clave.");
  LicenseProtocol.require(get("contract",id)==null,"Ese contrato ya existe.");
  put("contract",id,new JSONObject().put("id",id).put("customer",customer.trim()).put("productId",product).put("productName",productName).put("tokenHash",LicenseProtocol.hash(token)).put("mode","free").put("paidUntil",0).put("amountMinor",0).put("currency","BOB").put("interval","month").put("note","").put("paymentQr","").put("revision",1));audit("create",id,now);return null;
 });}
 public synchronized JSONObject authenticate(String token)throws Exception{
  LicenseProtocol.require(token.matches("[a-f0-9]{64}"),"No autorizado.");String hash=LicenseProtocol.hash(token);JSONArray rows=contracts();
  for(int i=0;i<rows.length();i++){JSONObject c=rows.getJSONObject(i);if(java.security.MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),c.getString("tokenHash").getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return c;}
  throw new SecurityException("No autorizado.");
 }
 public void plan(String id,long amount,String interval,String note,String qr,long now)throws Exception{tx(()->{
  LicenseProtocol.require(amount>=0&&amount<=100000000L&&(interval.equals("day")||interval.equals("month"))&&note.length()<=1000,"Precio o frecuencia inválidos.");if(!qr.isEmpty())image(qr);
  JSONObject c=contract(id);LicenseProtocol.require(amount>0||c.getString("mode").equals("free"),"Para dejar de cobrar usa Habilitar sin cobro.");c.put("amountMinor",amount).put("interval",interval).put("note",note).put("paymentQr",qr);saveContract(c);audit("plan",id,now);return null;
 });}
 private void saveContract(JSONObject c)throws Exception{c.put("revision",c.getLong("revision")+1);put("contract",c.getString("id"),c);}
 private void cancelOpen(String id,long now)throws Exception{JSONArray rows=list("invoice");for(int i=0;i<rows.length();i++){JSONObject inv=rows.getJSONObject(i);if(inv.getString("contractId").equals(id)&&inv.getString("state").equals("open")){inv.put("state","cancelled");put("invoice",inv.getString("id"),inv);audit("invoice_cancelled",inv.getString("id"),now);}}}
 public void charge(String id,long now)throws Exception{tx(()->{JSONObject c=contract(id);LicenseProtocol.require(c.getLong("amountMinor")>0,"Define primero un precio mayor a cero.");LicenseProtocol.require(!c.getString("note").trim().isEmpty()||!c.getString("paymentQr").isEmpty(),"Agrega instrucciones o un QR para recibir el pago.");
  // Repeating Cobrar on an unpaid contract keeps the same invoice and pending receipts.
  if(!c.getString("mode").equals("billing")||c.getLong("paidUntil")>now){cancelOpen(id,now);c.put("mode","billing").put("paidUntil",now);saveContract(c);}
  invoice(c,now);audit("charge",id,now);return null;
 });}
 public void free(String id,long now)throws Exception{tx(()->{JSONObject c=contract(id);cancelOpen(id,now);c.put("mode","free").put("paidUntil",0);saveContract(c);audit("free",id,now);return null;});}
 public void suspend(String id,long now)throws Exception{tx(()->{JSONObject c=contract(id);c.put("mode","suspended");saveContract(c);audit("suspend",id,now);return null;});}
 public void revise(String id,long now)throws Exception{tx(()->{JSONObject c=contract(id);LicenseProtocol.require(c.getString("mode").equals("billing")&&c.getLong("paidUntil")<=now,"Solo se reemplaza un cobro vencido.");JSONArray receipts=pending();for(int i=0;i<receipts.length();i++)LicenseProtocol.require(!receipts.getJSONObject(i).getString("contractId").equals(id),"Revisa primero los comprobantes pendientes de este cliente.");cancelOpen(id,now);invoice(c,now);saveContract(c);audit("revise",id,now);return null;});}
 private JSONObject invoice(JSONObject c,long now)throws Exception{
  JSONArray rows=list("invoice");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.getString("contractId").equals(c.getString("id"))&&r.getString("state").equals("open"))return r;}
  LicenseProtocol.require(c.getLong("amountMinor")>0,"Falta un precio válido para renovar.");String id=UUID.randomUUID().toString();
  JSONObject inv=new JSONObject().put("id",id).put("contractId",c.getString("id")).put("created",now).put("amountMinor",c.getLong("amountMinor")).put("currency",c.getString("currency")).put("interval",c.getString("interval")).put("note",c.getString("note")).put("paymentQr",c.getString("paymentQr")).put("state","open");put("invoice",id,inv);audit("invoice",id,now);return inv;
 }
 public JSONObject status(String contractId,String nonce,long now)throws Exception{return tx(()->{
  LicenseProtocol.require(nonce.matches("[a-f0-9]{64}"),"Consulta inválida.");JSONObject c=contract(contractId);String mode=c.getString("mode");long paid=c.getLong("paidUntil");boolean allowed=mode.equals("free")||(mode.equals("billing")&&now<paid);
  JSONObject claim=new JSONObject().put("version",1).put("contractId",contractId).put("productId",c.getString("productId")).put("customer",c.getString("customer")).put("productName",c.getString("productName")).put("nonce",nonce).put("issuedAt",now).put("paidUntil",mode.equals("free")?0:paid).put("expiresAt",allowed?(mode.equals("free")?now+LicenseProtocol.OFFLINE_MS:Math.min(now+LicenseProtocol.OFFLINE_MS,paid)):now).put("allowed",allowed).put("revision",c.getLong("revision")).put("reason",allowed?"active":mode.equals("suspended")?"suspended":"payment_due");
  if(mode.equals("billing")&&!allowed){JSONObject inv=invoice(c,now);claim.put("invoice",inv);JSONArray receipts=list("receipt");JSONArray own=new JSONArray();for(int i=0;i<receipts.length();i++){JSONObject r=receipts.getJSONObject(i);if(r.getString("invoiceId").equals(inv.getString("id")))own.put(new JSONObject().put("id",r.getString("id")).put("state",r.getString("state")).put("note",r.optString("reviewNote")).put("created",r.getLong("created")));}claim.put("receipts",own);}
  return claim;
 });}
 public static byte[] image(String text)throws Exception{
  LicenseProtocol.require(text.length()<=700000,"Imagen demasiado grande (máximo 512 KB).");byte[] b=java.util.Base64.getDecoder().decode(text);LicenseProtocol.require(b.length>8&&b.length<=512*1024,"Imagen inválida.");
  boolean jpeg=(b[0]&255)==255&&(b[1]&255)==216;boolean png=(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71;
  BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(b,0,b.length,opts);
  LicenseProtocol.require((jpeg||png)&&opts.outWidth>0&&opts.outHeight>0&&opts.outWidth<=5000&&opts.outHeight<=5000,"Adjunta una imagen JPEG o PNG válida.");return b;
 }
 public JSONObject receipt(String contractId,JSONObject request,long now)throws Exception{return tx(()->{
  String req=request.getString("requestId"),invId=request.getString("invoiceId"),picture=request.getString("image"),note=request.optString("note");id(req);LicenseProtocol.require(note.length()<=500,"Nota demasiado larga.");String identity=contractId+":"+req;
  JSONObject previous=get("receipt",identity);String hash=LicenseProtocol.hash(picture);
  if(previous!=null){LicenseProtocol.require(previous.getString("invoiceId").equals(invId)&&previous.getString("imageHash").equals(hash)&&previous.getString("note").equals(note),"Reintento incompatible.");return new JSONObject().put("ok",true).put("receiptId",identity).put("state",previous.getString("state"));}
  JSONObject inv=get("invoice",invId);LicenseProtocol.require(inv!=null&&inv.getString("contractId").equals(contractId)&&inv.getString("state").equals("open"),"Cobro no vigente. Actualiza el estado antes de enviar.");image(picture);
  JSONArray all=list("receipt");int pending=0;long storage;try(Cursor cur=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(length(data)),0) FROM items WHERE kind='image'",null)){cur.moveToFirst();storage=cur.getLong(0);}for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);if(r.getString("contractId").equals(contractId)&&r.getString("state").equals("pending"))pending++;LicenseProtocol.require(!r.getString("imageHash").equals(hash)||!r.getString("state").equals("approved"),"Este comprobante ya fue aprobado.");}
  LicenseProtocol.require(pending<3&&storage+picture.length()<25*1024*1024L,"Hay demasiados comprobantes pendientes o falta espacio. Contacta al administrador.");
  put("receipt",identity,new JSONObject().put("id",identity).put("contractId",contractId).put("invoiceId",invId).put("imageHash",hash).put("image",picture).put("note",note).put("created",now).put("state","pending"));audit("receipt",identity,now);
  return new JSONObject().put("ok",true).put("receiptId",identity).put("state","pending").put("newSubmission",true);
 });}
 public static long extend(long from,String interval){ZonedDateTime z=Instant.ofEpochMilli(from).atZone(ZONE);return (interval.equals("day")?z.plusDays(1):z.plusMonths(1)).toInstant().toEpochMilli();}
 public JSONObject review(String receiptId,boolean approve,String note,long now)throws Exception{return tx(()->{
  LicenseProtocol.require(note.length()<=500,"Nota demasiado larga.");JSONObject r=get("receipt",receiptId);LicenseProtocol.require(r!=null,"Comprobante no encontrado.");if(!r.getString("state").equals("pending"))return r;
  JSONObject inv=get("invoice",r.getString("invoiceId"));LicenseProtocol.require(inv!=null&&inv.getString("state").equals("open"),"Este cobro ya se resolvió o canceló. No se renovó otra vez.");JSONObject c=contract(r.getString("contractId"));
  if(approve){LicenseProtocol.require(c.getString("mode").equals("billing"),"El cliente está suspendido o sin cobro. Reactiva su cobro antes de aprobar.");
   long until=extend(Math.max(now,c.getLong("paidUntil")),inv.getString("interval"));c.put("paidUntil",until);saveContract(c);inv.put("state","paid").put("paidAt",now).put("paidUntil",until).put("approvedReceipt",receiptId);put("invoice",inv.getString("id"),inv);
  }
  r.put("state",approve?"approved":"rejected").put("reviewedAt",now).put("reviewNote",note);put("receipt",receiptId,r);audit(approve?"approve":"reject",receiptId,now);return r;
 });}
 public synchronized JSONArray pending()throws Exception{JSONArray a=list("receipt"),out=new JSONArray();for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);JSONObject inv=get("invoice",r.getString("invoiceId"));if(r.getString("state").equals("pending")&&inv!=null&&inv.getString("state").equals("open"))out.put(r);}return out;}
 public synchronized JSONObject exportData()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT time,action,subject FROM audit ORDER BY seq",null)){while(c.moveToNext())a.put(new JSONObject().put("time",c.getLong(0)).put("action",c.getString(1)).put("subject",c.getString(2)));}return new JSONObject().put("version",1).put("contracts",list("contract")).put("invoices",list("invoice")).put("receipts",list("receipt")).put("images",list("image")).put("audit",a);}
 public void restore(JSONObject backup)throws Exception{tx(()->{LicenseProtocol.require(contracts().length()==0,"Restaura únicamente en una instalación vacía.");LicenseProtocol.require(backup.getInt("version")==1,"Respaldo incompatible.");for(String[] pair:new String[][]{{"contracts","contract"},{"invoices","invoice"},{"receipts","receipt"},{"images","image"}}){JSONArray a=backup.getJSONArray(pair[0]);for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(pair[1].equals("contract"))r.put("revision",Math.max(r.optLong("revision")+1,System.currentTimeMillis()));put(pair[1],r.getString("id"),r);}}JSONArray logs=backup.getJSONArray("audit");for(int i=0;i<logs.length();i++){JSONObject a=logs.getJSONObject(i);audit(a.getString("action"),a.getString("subject"),a.getLong("time"));}return null;});}
}
