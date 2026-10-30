package bo.soboce.comedor;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Append-only receipts from unique installations; legacy receipts remain untouched. */
final class SharedLedger {
    final PeerDatabase db; final PeerConfig config;
    SharedLedger(PeerDatabase db,PeerConfig config){this.db=db;this.config=config;}
    static void create(SQLiteDatabase sql){
        sql.execSQL("CREATE TABLE IF NOT EXISTS shared_events(origin TEXT NOT NULL,seq INTEGER NOT NULL,request_id TEXT NOT NULL UNIQUE,meal_key TEXT NOT NULL,value TEXT NOT NULL,record TEXT NOT NULL,PRIMARY KEY(origin,seq))");
        sql.execSQL("CREATE INDEX IF NOT EXISTS shared_meal_key ON shared_events(meal_key)");
        sql.execSQL("CREATE TABLE IF NOT EXISTS shared_results(id TEXT PRIMARY KEY,json TEXT NOT NULL)");
    }
    JSONObject scan(JSONObject request,JSONObject operator,long now) throws Exception {synchronized(db){
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
        try{
            PeerConfig.require(request.optString("action").equals("scan")&&request.optString("requestId").matches("[a-f0-9-]{36}"),"Solicitud inválida.");
            String id=request.getString("requestId");JSONObject saved=db.get("shared_results",id);
            if(saved!=null){PeerConfig.require(OfflineLedger.sameRequest(saved.getJSONObject("request"),request),"Solicitud incompatible.");return recovered(saved.getJSONObject("reply"));}
            JSONObject historical=db.get("results",id);
            if(historical!=null){PeerConfig.require(OfflineLedger.sameRequest(historical.getJSONObject("request"),request),"Solicitud incompatible.");return PeerEngine.replyForAttempt(historical,null);}
            try(Cursor c=sql.rawQuery("SELECT value FROM offline_events WHERE request_id=?",new String[]{id})){
                if(c.moveToFirst()){JSONObject b=config.command(c.getString(0));PeerConfig.require(OfflineLedger.sameRequest(b.getJSONObject("request"),request),"Solicitud incompatible.");return recovered(accepted(b.getJSONObject("record")));}
            }
            long last=Long.parseLong(db.meta("lastSharedTime","0"));
            if(now<=0||now<last-60000)return new JSONObject().put("ok",false).put("definitive",false).put("error","Activa fecha y hora automáticas: el reloj retrocedió.");
            JSONObject reply;
            try {
                validateOperator(operator);
                JSONObject record=new MealLedger(db,config).scanRecord(request,operator,now),previous=find(record.getString("clave"));
                if(previous!=null)reply=MealLedger.ok().put("status","duplicate").put("message","Esta comida ya está registrada.").put("person",record.getString("nombre")).put("plant",previous.getString("planta")).put("time",previous.getString("hora"));
                else {
                    JSONObject body=new JSONObject().put("kind","shared-meal-v1").put("origin",config.device).put("seq",vector().optLong(config.device)+1).put("time",now).put("operator",operator).put("request",request).put("record",record);
                    String data=body.toString(),value=new JSONObject().put("body",data).put("signature",config.mac("receipt|"+data)).toString();
                    insert(body,value);reply=accepted(record);db.putMeta("lastSharedTime",Long.toString(Math.max(last,now)));
                }
            }catch(IllegalArgumentException e){reply=new JSONObject().put("ok",false).put("definitive",true).put("error",e.getMessage());}
            db.save("shared_results",id,new JSONObject().put("request",request).put("reply",reply));sql.setTransactionSuccessful();return reply;
        }finally{sql.endTransaction();}
    }}
    static JSONObject accepted(JSONObject r) throws Exception {return MealLedger.ok().put("status","accepted").put("message","Entrega guardada. Se compartirá automáticamente con los demás teléfonos.").put("person",r.getString("nombre")).put("plant",r.getString("planta")).put("time",r.getString("hora"));}
    static JSONObject recovered(JSONObject saved) throws Exception {JSONObject r=new JSONObject(saved.toString());if(r.optString("status").equals("accepted"))r.put("status","recovered").put("message","Entrega anterior recuperada. No entregar otra ración.");return r;}
    void validateOperator(JSONObject op) throws Exception {
        String role=op.getString("role"),name=op.getString("name"),plant=op.getString("plant");
        PeerConfig.require(role.matches("admin|encargado")&&name.length()>=3&&name.length()<=100,"Encargado inválido.");
        boolean known=false;JSONArray plants=config.seed.getJSONArray("plants");for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(plant);PeerConfig.require(known,"Planta inválida.");
    }
    private JSONObject find(String key) throws Exception {
        JSONObject old=db.get("meals",key);if(old!=null)return old;
        for(String table:new String[]{"offline_events","shared_events"})try(Cursor c=db.getReadableDatabase().rawQuery("SELECT record FROM "+table+" WHERE meal_key=? LIMIT 1",new String[]{key})){if(c.moveToFirst())return new JSONObject(c.getString(0));}
        return null;
    }
    JSONObject vector(){synchronized(db){JSONObject v=new JSONObject();try(Cursor c=db.getReadableDatabase().rawQuery("SELECT origin,MAX(seq) FROM shared_events GROUP BY origin",null)){while(c.moveToNext())try{v.put(c.getString(0),c.getLong(1));}catch(JSONException impossible){throw new IllegalStateException(impossible);}}return v;}}
    JSONArray events(JSONObject after,int limit) throws Exception {synchronized(db){
        PeerConfig.require(after.length()<=512&&limit>0&&limit<=20,"Estado de sincronización inválido.");
        Iterator<String> keys=after.keys();while(keys.hasNext()){String k=keys.next();PeerConfig.require(k.matches("[a-f0-9]{64}")&&after.getLong(k)>=0,"Estado de sincronización inválido.");}
        JSONArray result=new JSONArray();JSONObject own=vector();List<String> origins=new ArrayList<>();own.keys().forEachRemaining(origins::add);Collections.sort(origins);
        for(String origin:origins){if(result.length()==limit)break;try(Cursor c=db.getReadableDatabase().rawQuery("SELECT value FROM shared_events WHERE origin=? AND seq>? ORDER BY seq LIMIT ?",new String[]{origin,Long.toString(after.optLong(origin)),Integer.toString(limit-result.length())})){while(c.moveToNext())result.put(c.getString(0));}}
        return result;
    }}
    void merge(JSONArray items) throws Exception {synchronized(db){
        PeerConfig.require(items.length()<=20,"Página demasiado grande.");SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
        try{
            for(int i=0;i<items.length();i++){
                String value=items.getString(i);PeerConfig.require(value.length()<=24000,"Registro demasiado grande.");JSONObject sealed=new JSONObject(value);String data=sealed.getString("body");
                PeerConfig.require(PeerConfig.equal(config.mac("receipt|"+data),sealed.getString("signature")),"Registro no autenticado.");JSONObject b=new JSONObject(data);validate(b);
                String origin=b.getString("origin");long seq=b.getLong("seq");
                try(Cursor c=sql.rawQuery("SELECT value FROM shared_events WHERE origin=? AND seq=?",new String[]{origin,Long.toString(seq)})){if(c.moveToFirst()){PeerConfig.require(c.getString(0).equals(value),"Historial incompatible; conserva los datos para revisión.");continue;}}
                PeerConfig.require(seq==vector().optLong(origin)+1,"Faltan registros anteriores.");insert(b,value);
                JSONObject request=b.getJSONObject("request");db.save("shared_results",request.getString("requestId"),new JSONObject().put("request",request).put("reply",accepted(b.getJSONObject("record"))));
            }sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }}
    void validate(JSONObject b) throws Exception {
        PeerConfig.require(b.getString("kind").equals("shared-meal-v1")&&b.getString("origin").matches("[a-f0-9]{64}")&&b.getLong("seq")>0&&b.getLong("time")>0,"Registro inválido.");
        JSONObject req=b.getJSONObject("request"),r=b.getJSONObject("record"),op=b.getJSONObject("operator");validateOperator(op);
        String meal=req.getString("meal"),plant=req.getString("plant"),qr=req.getString("qr");long when=b.getLong("time");
        PeerConfig.require(req.getString("action").equals("scan")&&req.getString("requestId").matches("[a-f0-9-]{36}")&&req.getString("requestId").equals(r.getString("solicitud")),"Solicitud inválida.");
        PeerConfig.require(Meals.ALL.contains(meal)&&(qr.matches("SB1:[a-f0-9]{64}")||PeerCards.format(qr))&&PeerConfig.hash(qr).equals(r.getString("qrHash")),"Comida o QR inválidos.");
        boolean known=false;JSONArray plants=config.seed.getJSONArray("plants");for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(plant);
        PeerConfig.require(known&&(op.getString("role").equals("admin")||op.getString("plant").equals(plant)),"Planta no autorizada.");
        PeerConfig.require(r.getString("fecha").equals(MealLedger.day(when))&&r.getString("hora").equals(MealLedger.time(when))&&r.getString("clave").equals(r.getString("persona")+"|"+MealLedger.day(when)+"|"+Meals.allowance(meal)),"Fecha o persona incompatible.");
        PeerConfig.require(r.getString("planta").equals(plant)&&r.getString("comida").equals(meal)&&r.getString("operador").equals(op.getString("name"))&&Double.isFinite(r.getDouble("precio"))&&r.getDouble("precio")>=0,"Datos inválidos.");
        PeerConfig.require(!r.getString("persona").isEmpty()&&r.getString("persona").length()<=100&&!r.getString("nombre").isEmpty()&&r.getString("nombre").length()<=200&&r.getString("centroCosto").length()<=100,"Persona inválida.");
    }
    private void insert(JSONObject b,String value) throws Exception {ContentValues row=new ContentValues();row.put("origin",b.getString("origin"));row.put("seq",b.getLong("seq"));row.put("request_id",b.getJSONObject("request").getString("requestId"));row.put("meal_key",b.getJSONObject("record").getString("clave"));row.put("record",b.getJSONObject("record").toString());row.put("value",value);db.getWritableDatabase().insertOrThrow("shared_events",null,row);}
    String eventFor(String request) {synchronized(db){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT value FROM shared_events WHERE request_id=?",new String[]{request})){return c.moveToFirst()?c.getString(0):"";}}}
    void appendRecords(List<JSONObject> records) throws Exception {synchronized(db){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT origin,seq,record FROM shared_events ORDER BY origin,seq",null)){while(c.moveToNext()){JSONObject r=new JSONObject(c.getString(2));records.add(r.put("registro",c.getString(0)+":"+c.getLong(1)).put("origen",r.getString("operador")));}}}}
    long pending(){synchronized(db){return Math.max(0,vector().optLong(config.device)-Long.parseLong(db.meta("sharedAck","0")));}}
    void acknowledge(JSONObject vector){synchronized(db){db.putMeta("sharedAck",Long.toString(vector.optLong(config.device)));db.putMeta("sharedLastSync",Long.toString(System.currentTimeMillis()));}}
}
