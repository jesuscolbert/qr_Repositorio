package bo.soboce.comedor;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Signed worker registrations are portable QR cards and an append-only replicated log. */
final class RosterLedger {
    final PeerDatabase db; final PeerConfig config;
    RosterLedger(PeerDatabase db,PeerConfig config){this.db=db;this.config=config;}
    static void create(SQLiteDatabase sql){
        sql.execSQL("CREATE TABLE IF NOT EXISTS roster_events(origin TEXT NOT NULL,seq INTEGER NOT NULL,person_id TEXT NOT NULL,hash TEXT NOT NULL UNIQUE,qr TEXT NOT NULL,PRIMARY KEY(origin,seq))");
        sql.execSQL("CREATE INDEX IF NOT EXISTS roster_person ON roster_events(person_id,hash)");
    }
    static String document(String raw){
        String ci=raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+","");
        PeerConfig.require(ci.matches("[0-9]{5,20}(?:-[A-Z0-9]{1,6})?"),"Escribe el carnet sin puntos. Si tiene complemento, usa un guion: 1234567-1A.");return ci;
    }
    String personId(String ci){return (config.manifest.optString("installation").startsWith("frigor-")?"FRIGOR-CI-":"CI-")+ci;}
    JSONObject byDocument(String ci)throws Exception{
        for(JSONObject p:db.rows("people"))if(!p.optString("tipo").equals("VISITA")&&p.optString("documento").equals(ci))return p;
        return null;
    }
    JSONObject add(JSONObject request,long now)throws Exception{synchronized(db){
        PeerConfig.require(config.shared,"Actualiza la red de teléfonos para registrar trabajadores.");
        JSONObject op=config.operator(-1);String plant=request.optString("plant");
        new SharedLedger(db,config).validateOperator(op);PeerConfig.require(op.getString("role").equals("admin")||op.getString("plant").equals(plant),"Solo puedes registrar en tu planta.");
        String ci=document(request.optString("document")),name=request.optString("name").trim().replaceAll("\\s+"," ");
        PeerConfig.require(name.isEmpty()||name.length()>=3&&name.length()<=100,"Escribe un nombre de 3 a 100 caracteres o déjalo pendiente.");
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{
            JSONObject existing=byDocument(ci);
            if(existing!=null){sql.setTransactionSuccessful();return existingReply(existing,plant,now);}
            PeerConfig.require(now>0,"Activa fecha y hora automáticas.");long seq;
            try(Cursor c=sql.rawQuery("SELECT COALESCE(MAX(seq),0)+1 FROM roster_events WHERE origin=?",new String[]{config.device})){c.moveToFirst();seq=c.getLong(0);}
            JSONObject b=new JSONObject().put("v",4).put("cluster",config.cluster).put("origin",config.device).put("seq",seq).put("time",now).put("operator",op).put("ci",ci).put("name",name).put("plant",plant);
            String body=b.toString(),qr="SB4:"+Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))+"."+config.mac("worker|"+body);
            accept(qr,now);JSONObject person=db.get("people",personId(ci));JSONObject reply=card(person).put("created",true);
            sql.setTransactionSuccessful();return reply;
        }finally{sql.endTransaction();}
    }}
    private JSONObject existingReply(JSONObject p,String plant,long now)throws Exception{
        PeerConfig.require(p.optBoolean("activo"),"Este carnet ya existe y está inactivo. No se creará otra identidad.");
        PeerConfig.require(p.getString("planta").equals(plant),"Este carnet ya existe en "+p.getString("planta")+". Usa su credencial existente.");
        JSONObject result=PeerCards.request(db,config,new JSONObject().put("action","issue").put("plant",plant).put("personId",p.getString("id")),now);
        return result.put("alreadyExists",true).put("notice","Este carnet ya estaba registrado. Se conserva la misma persona y su historial.");
    }
    static boolean format(String qr){return qr!=null&&qr.length()<=2048&&qr.matches("SB4:[A-Za-z0-9_-]+\\.[a-f0-9]{64}");}
    private JSONObject decode(String qr,long now)throws Exception{
        PeerConfig.require(config.shared&&format(qr),"Credencial de trabajador inválida.");
        String[] parts=qr.substring(4).split("\\.",-1);String body=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
        PeerConfig.require(PeerConfig.equal(config.mac("worker|"+body),parts[1]),"Alta alterada o de otra empresa.");JSONObject b=new JSONObject(body);
        PeerConfig.require(b.getInt("v")==4&&b.getString("cluster").equals(config.cluster)&&b.getString("origin").matches("[a-f0-9]{64}")&&b.getLong("seq")>0&&b.getLong("seq")<2147483647L&&b.getLong("time")>0&&b.getLong("time")<=now+120000,"Identidad o fecha del alta inválida.");
        PeerConfig.require(document(b.getString("ci")).equals(b.getString("ci")),"Carnet no normalizado.");
        String name=b.getString("name");PeerConfig.require(name.isEmpty()||name.length()>=3&&name.length()<=100,"Nombre inválido.");
        JSONObject op=b.getJSONObject("operator");new SharedLedger(db,config).validateOperator(op);boolean known=false;JSONArray plants=config.seed.getJSONArray("plants");for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(b.getString("plant"));
        PeerConfig.require(known&&(op.getString("role").equals("admin")||op.getString("plant").equals(b.getString("plant"))),"Planta no autorizada.");return b;
    }
    JSONObject accept(String qr,long now)throws Exception{synchronized(db){
        JSONObject b=decode(qr,now);String ci=b.getString("ci"),id=personId(ci),origin=b.getString("origin");long seq=b.getLong("seq");
        // Seed identities are immutable. A forged duplicate registration cannot replace them.
        JSONArray seed=config.seed.getJSONArray("people");for(int i=0;i<seed.length();i++){
            JSONObject p=seed.getJSONObject(i);PeerConfig.require(!p.getString("id").equals(id)&&!p.optString("documento").equals(ci),"El carnet ya pertenece al padrón inicial. Usa su QR existente.");
        }
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{
            boolean found=false;try(Cursor c=sql.rawQuery("SELECT qr FROM roster_events WHERE origin=? AND seq=?",new String[]{origin,Long.toString(seq)})){if(c.moveToFirst()){PeerConfig.require(c.getString(0).equals(qr),"Alta incompatible; conserva los datos para revisión.");found=true;}}
            if(!found){ContentValues row=new ContentValues();row.put("origin",origin);row.put("seq",seq);row.put("person_id",id);row.put("hash",PeerConfig.hash(qr));row.put("qr",qr);sql.insertOrThrow("roster_events",null,row);}
            project(id);sql.setTransactionSuccessful();return db.get("people",id);
        }finally{sql.endTransaction();}
    }}
    String qrFor(String id){synchronized(db){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT qr FROM roster_events WHERE person_id=? ORDER BY hash LIMIT 1",new String[]{id})){return c.moveToFirst()?c.getString(0):"";}}}
    void project(String id)throws Exception{
        String qr=qrFor(id);if(qr.isEmpty())return;JSONObject b=decode(qr,Long.MAX_VALUE-120000);
        String ci=b.getString("ci"),name=b.getString("name");int count=0;
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM roster_events WHERE person_id=?",new String[]{id})){c.moveToFirst();count=c.getInt(0);}
        JSONObject existing=db.get("people",id);
        JSONObject p=new JSONObject().put("id",id).put("sap","").put("documento",ci).put("documentoTipo","CI").put("nombre",name.isEmpty()?"CI "+ci+" · Nombre pendiente":name).put("planta",b.getString("plant")).put("centroCosto","").put("tipo","TRABAJADOR").put("activo",existing==null||existing.optBoolean("activo")).put("qrHash","").put("vence","").put("comidas","desayuno,almuerzo,cena").put("rosterConflict",count>1);
        db.save("people",id,p);
    }
    void replay()throws Exception{List<String> ids=new ArrayList<>();try(Cursor c=db.getReadableDatabase().rawQuery("SELECT DISTINCT person_id FROM roster_events",null)){while(c.moveToNext())ids.add(c.getString(0));}for(String id:ids)project(id);}
    JSONObject card(JSONObject p)throws Exception{return MealLedger.ok().put("qr",qrFor(p.getString("id"))).put("person",p.getString("nombre")).put("detail",PersonIdentity.label(p)+" · "+p.getString("planta")).put("notice",p.optBoolean("rosterConflict")?"Este carnet fue dado de alta en más de un teléfono. Se conserva una sola persona; revisa el nombre con el encargado.":"Trabajador registrado. Su QR también permite reconocerlo en otro teléfono actualizado antes de sincronizar.");}
    JSONObject vector()throws Exception{synchronized(db){JSONObject result=new JSONObject();
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT origin,seq FROM roster_events ORDER BY origin,seq",null)){
            String origin="";JSONArray ranges=new JSONArray();long first=0,last=0;
            while(c.moveToNext()){String current=c.getString(0);long seq=c.getLong(1);
                if(!current.equals(origin)){if(!origin.isEmpty()){ranges.put(first).put(last);result.put(origin,ranges);}origin=current;ranges=new JSONArray();first=last=seq;}
                else if(seq==last+1)last=seq;else{ranges.put(first).put(last);first=last=seq;}}
            if(!origin.isEmpty()){ranges.put(first).put(last);result.put(origin,ranges);}
        }return result;
    }}
    JSONArray events(JSONObject after,int limit)throws Exception{synchronized(db){
        PeerConfig.require(after.length()<=512&&limit>0&&limit<=20,"Estado de padrón inválido.");for(Iterator<String> it=after.keys();it.hasNext();){String key=it.next();JSONArray ranges=after.getJSONArray(key);PeerConfig.require(key.matches("[a-f0-9]{64}")&&ranges.length()<=2048&&ranges.length()%2==0,"Estado de padrón inválido.");long previous=0;for(int i=0;i<ranges.length();i+=2){long start=ranges.getLong(i),end=ranges.getLong(i+1);PeerConfig.require(start>previous&&end>=start&&end<2147483647L,"Intervalo de padrón inválido.");previous=end;}}
        JSONArray result=new JSONArray();List<String> origins=new ArrayList<>();vector().keys().forEachRemaining(origins::add);Collections.sort(origins);
        for(String origin:origins){if(result.length()==limit)break;JSONArray ranges=after.optJSONArray(origin);long floor=ranges!=null&&ranges.length()>0&&ranges.getLong(0)==1?ranges.getLong(1):0;
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT seq,qr FROM roster_events WHERE origin=? AND seq>? ORDER BY seq",new String[]{origin,Long.toString(floor)})){while(result.length()<limit&&c.moveToNext()){long seq=c.getLong(0);boolean known=false;if(ranges!=null)for(int i=0;i<ranges.length();i+=2)known|=seq>=ranges.getLong(i)&&seq<=ranges.getLong(i+1);if(!known)result.put(c.getString(1));}}}
        return result;
    }}
    void merge(JSONArray entries)throws Exception{synchronized(db){PeerConfig.require(entries.length()<=20,"Página de altas demasiado grande.");SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{for(int i=0;i<entries.length();i++)accept(entries.getString(i),System.currentTimeMillis());sql.setTransactionSuccessful();}finally{sql.endTransaction();}}}
}
