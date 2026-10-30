package bo.soboce.comedor;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Durable signed receipts. Replication never authorizes a second physical delivery. */
final class OfflineLedger {
    final PeerDatabase db;
    final PeerConfig config;
    OfflineLedger(PeerDatabase db,PeerConfig config){this.db=db;this.config=config;}

    static void create(SQLiteDatabase sql){
        sql.execSQL("CREATE TABLE IF NOT EXISTS offline_events(actor INTEGER NOT NULL,seq INTEGER NOT NULL,request_id TEXT NOT NULL,meal_key TEXT NOT NULL,value TEXT NOT NULL,record TEXT NOT NULL,PRIMARY KEY(actor,seq),UNIQUE(actor,request_id))");
        sql.execSQL("CREATE INDEX IF NOT EXISTS offline_meal_key ON offline_events(meal_key)");
        sql.execSQL("CREATE TABLE IF NOT EXISTS offline_results(id TEXT PRIMARY KEY,json TEXT NOT NULL)");
    }
    static boolean sameRequest(JSONObject a,JSONObject b){
        for(String key:new String[]{"action","plant","meal","qr","requestId"})if(!a.optString(key).equals(b.optString(key)))return false;
        return true;
    }
    JSONObject scan(JSONObject request,long now) throws Exception {synchronized(db){
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
        try{
            PeerConfig.require(request.optString("action").equals("scan")&&request.optString("requestId").matches("[a-f0-9-]{36}"),"Solicitud inválida.");
            String id=config.node+":"+request.getString("requestId");
            JSONObject saved=db.get("offline_results",id);
            if(saved==null){
                JSONObject legacy=db.get("results",request.getString("requestId"));
                if(legacy!=null){PeerConfig.require(legacy.getInt("actor")==config.node&&sameRequest(legacy.getJSONObject("request"),request),"Solicitud incompatible.");return PeerEngine.replyForAttempt(legacy,null);}
            }else{
                PeerConfig.require(sameRequest(saved.getJSONObject("request"),request),"Solicitud incompatible.");
                return recovered(saved.getJSONObject("reply"));
            }
            long last=Long.parseLong(db.meta("lastOfflineTime","0"));
            if(now<=0||now<last-60000)return new JSONObject().put("ok",false).put("definitive",false).put("error","La fecha del teléfono retrocedió. Activa fecha y hora automáticas antes de registrar.");
            JSONObject reply;
            try{
                JSONObject record=new MealLedger(db,config).scanRecord(request,config.node,now);
                JSONObject previous=findMeal(record.getString("clave"));
                if(previous!=null)reply=MealLedger.ok().put("status","duplicate").put("message","Esta comida ya figura registrada en este teléfono.").put("person",record.getString("nombre")).put("plant",previous.getString("planta")).put("time",previous.getString("hora"));
                else{
                    long seq=vector().getLong(config.node)+1;
                    JSONObject body=new JSONObject().put("kind","offline-meal-v3").put("actor",config.node).put("seq",seq).put("time",now).put("request",request).put("record",record);
                    String value=config.seal(body);insert(body,value);
                    reply=accepted(record).put("event",config.node+":"+seq);
                    db.putMeta("lastOfflineTime",Long.toString(Math.max(last,now)));
                }
            }catch(IllegalArgumentException e){reply=new JSONObject().put("ok",false).put("definitive",true).put("error",e.getMessage());}
            db.save("offline_results",id,new JSONObject().put("request",request).put("reply",reply));
            sql.setTransactionSuccessful();return reply;
        }finally{sql.endTransaction();}
    }}
    private static JSONObject accepted(JSONObject record) throws Exception {
        return MealLedger.ok().put("status","accepted").put("message","Entrega guardada en este teléfono. Se compartirá al conectar con los demás.").put("person",record.getString("nombre")).put("plant",record.getString("planta")).put("time",record.getString("hora"));
    }
    private static JSONObject recovered(JSONObject saved) throws Exception {
        JSONObject reply=new JSONObject(saved.toString());
        if(reply.optString("status").equals("accepted"))reply.put("status","recovered").put("message","Registro anterior recuperado. No entregar otra ración.");
        return reply;
    }
    private JSONObject findMeal(String key) throws Exception {
        JSONObject legacy=db.get("meals",key);if(legacy!=null)return legacy;
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT record FROM offline_events WHERE meal_key=? ORDER BY actor,seq LIMIT 1",new String[]{key})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}
    }
    JSONArray vector() {synchronized(db){
        long[] heads=new long[4];
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT actor,MAX(seq) FROM offline_events GROUP BY actor",null)){while(c.moveToNext())heads[c.getInt(0)]=c.getLong(1);}
        JSONArray result=new JSONArray();for(long n:heads)result.put(n);return result;
    }}
    static void checkVector(JSONArray vector) throws Exception {
        PeerConfig.require(vector.length()==4,"Estado de sincronización inválido.");
        for(int i=0;i<4;i++)PeerConfig.require(vector.getLong(i)>=0,"Estado de sincronización inválido.");
    }
    JSONArray events(JSONArray after,int limit) throws Exception {synchronized(db){
        checkVector(after);PeerConfig.require(limit>0&&limit<=20,"Página inválida.");JSONArray items=new JSONArray();
        for(int actor=0;actor<4&&items.length()<limit;actor++){
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT value FROM offline_events WHERE actor=? AND seq>? ORDER BY seq LIMIT ?",new String[]{Integer.toString(actor),Long.toString(after.getLong(actor)),Integer.toString(limit-items.length())})){while(c.moveToNext())items.put(c.getString(0));}
        }
        return items;
    }}
    void merge(JSONArray values) throws Exception {synchronized(db){
        PeerConfig.require(values.length()<=20,"Página demasiado grande.");
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
        try{
            for(int i=0;i<values.length();i++){
                String value=values.getString(i);PeerConfig.require(value.length()<=24000,"Registro demasiado grande.");
                JSONObject body=config.command(value);validate(body);
                int actor=body.getInt("actor");long seq=body.getLong("seq");
                try(Cursor c=sql.rawQuery("SELECT value FROM offline_events WHERE actor=? AND seq=?",new String[]{Integer.toString(actor),Long.toString(seq)})){
                    if(c.moveToFirst()){PeerConfig.require(c.getString(0).equals(value),"La identidad de un teléfono tiene registros incompatibles. No copies su configuración a otro equipo.");continue;}
                }
                PeerConfig.require(seq==vector().getLong(actor)+1,"Faltan registros anteriores; reanuda la sincronización.");
                insert(body,value);
                if(actor==config.node){
                    JSONObject request=body.getJSONObject("request");String id=actor+":"+request.getString("requestId");
                    if(db.get("offline_results",id)==null)db.save("offline_results",id,new JSONObject().put("request",request).put("reply",accepted(body.getJSONObject("record"))));
                }
            }
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }}
    private void insert(JSONObject body,String value) throws Exception {
        JSONObject record=body.getJSONObject("record");ContentValues row=new ContentValues();
        row.put("actor",body.getInt("actor"));row.put("seq",body.getLong("seq"));row.put("request_id",body.getJSONObject("request").getString("requestId"));row.put("meal_key",record.getString("clave"));row.put("value",value);row.put("record",record.toString());
        db.getWritableDatabase().insertOrThrow("offline_events",null,row);
    }
    private void validate(JSONObject body) throws Exception {
        int actor=body.getInt("actor");long time=body.getLong("time");JSONObject request=body.getJSONObject("request"),r=body.getJSONObject("record"),op=config.nodes.getJSONObject(actor);
        PeerConfig.require(body.getString("kind").equals("offline-meal-v3")&&body.getLong("seq")>0&&time>0&&request.getString("action").equals("scan"),"Registro incompatible.");
        String plant=request.getString("plant"),meal=request.getString("meal"),id=r.getString("persona"),qr=request.getString("qr");
        boolean known=false;JSONArray plants=config.seed.getJSONArray("plants");for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(plant);
        PeerConfig.require(known&&(op.getString("role").equals("admin")||op.getString("plant").equals(plant)),"Registro fuera de la planta autorizada.");
        PeerConfig.require(Arrays.asList("desayuno","almuerzo","cena").contains(meal)&&!id.isEmpty()&&id.length()<=100,"Persona o comida inválida.");
        PeerConfig.require(request.getString("requestId").matches("[a-f0-9-]{36}")&&request.getString("requestId").equals(r.getString("solicitud")),"Solicitud incompatible.");
        PeerConfig.require((qr.matches("SB1:[a-f0-9]{64}")||PeerCards.format(qr))&&PeerConfig.hash(qr).equals(r.getString("qrHash")),"Credencial incompatible.");
        PeerConfig.require(r.getString("fecha").equals(MealLedger.day(time))&&r.getString("hora").equals(MealLedger.time(time))&&r.getString("clave").equals(id+"|"+MealLedger.day(time)+"|"+meal),"Fecha o identidad incompatible.");
        PeerConfig.require(r.getString("planta").equals(plant)&&r.getString("comida").equals(meal)&&r.getString("operador").equals(op.getString("name"))&&Double.isFinite(r.getDouble("precio"))&&r.getDouble("precio")>=0,"Datos de consumo inválidos.");
        // Authenticate the issuer's historical receipt; do not apply today's QR revocations retroactively.
        PeerConfig.require(!r.getString("nombre").isEmpty()&&r.getString("nombre").length()<=200&&r.getString("centroCosto").length()<=100,"Datos personales inválidos.");
    }
    void acknowledge(int peer,JSONArray vector) throws Exception {synchronized(db){
        PeerConfig.require(peer>=0&&peer<4&&peer!=config.node,"Teléfono inválido.");checkVector(vector);
        db.putMeta("offlineAck"+peer,vector.toString());db.putMeta("offlineLastSync",Long.toString(System.currentTimeMillis()));
    }}
    long pending() throws Exception {synchronized(db){
        long own=vector().getLong(config.node),all=own;
        for(int peer=0;peer<4;peer++)if(peer!=config.node)all=Math.min(all,new JSONArray(db.meta("offlineAck"+peer,"[0,0,0,0]")).getLong(config.node));
        return Math.max(0,own-all);
    }}
    List<JSONObject> records() throws Exception {synchronized(db){
        List<JSONObject> records=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        for(JSONObject legacy:db.rows("meals"))records.add(new JSONObject(legacy.toString()).put("registro","anterior:"+legacy.getString("solicitud")).put("origen","Versión anterior"));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT actor,seq,record FROM offline_events ORDER BY actor,seq",null)){
            while(c.moveToNext())records.add(new JSONObject(c.getString(2)).put("registro",c.getInt(0)+":"+c.getLong(1)).put("origen",config.nodes.getJSONObject(c.getInt(0)).getString("name")));
        }
        for(JSONObject r:records)counts.merge(r.getString("clave"),1,Integer::sum);
        for(JSONObject r:records)r.put("revision",counts.get(r.getString("clave"))>1?"POSIBLE DUPLICADO":"");
        records.sort(Comparator.comparing((JSONObject r)->r.optString("fecha")+" "+r.optString("hora")).thenComparing(r->r.optString("registro")));
        return records;
    }}
}
