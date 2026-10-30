package bo.soboce.comedor;

import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Deterministic state machine: no wall clock, network or randomness during replay. */
final class MealLedger {
    private final PeerDatabase db;
    private final PeerConfig config;
    private static final ZoneId ZONE=ZoneId.of("America/La_Paz");
    private static final List<String> MEALS=Arrays.asList("desayuno","almuerzo","cena");
    MealLedger(PeerDatabase db,PeerConfig config){this.db=db;this.config=config;}
    static String day(long time){return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().toString();}
    static JSONObject ok() throws JSONException{return new JSONObject().put("ok",true);}
    JSONObject execute(JSONObject command) throws Exception {
        JSONObject req=command.getJSONObject("request"), reply;
        int actor=command.getInt("actor");
        try{reply=handle(req,actor,command.getLong("time"));}
        catch(IllegalArgumentException e){reply=new JSONObject().put("ok",false).put("error",e.getMessage()).put("definitive",true);}
        return new JSONObject().put("request",req).put("actor",actor).put("reply",reply);
    }
    JSONObject read(JSONObject request) throws Exception {synchronized(db){return handle(request,config.node,System.currentTimeMillis());}}
    private JSONObject handle(JSONObject r,int actor,long when) throws Exception {
        JSONObject op=config.nodes.getJSONObject(actor);String today=day(when),action=r.getString("action");boolean admin=op.getString("role").equals("admin");
        JSONArray plants=config.seed.getJSONArray("plants");
        if(action.equals("session")){
            JSONArray allowed=new JSONArray();for(int i=0;i<plants.length();i++)if(admin||plants.getString(i).equals(op.getString("plant")))allowed.put(plants.getString(i));
            return ok().put("operator",op.getString("name")).put("role",op.getString("role")).put("today",today).put("plants",allowed).put("p2p",true);
        }
        String plant=r.optString("plant");boolean known=false;for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(plant);
        PeerConfig.require(known&&(admin||op.getString("plant").equals(plant)),"No tienes permiso para esta planta.");
        if(action.equals("people")){
            JSONArray people=new JSONArray();for(JSONObject p:db.rows("people"))if(p.optBoolean("activo")&&p.getString("planta").equals(plant))people.put(new JSONObject().put("id",p.getString("id")).put("sap",p.optString("sap")).put("nombre",p.getString("nombre")).put("planta",plant).put("tipo",p.getString("tipo")));
            return ok().put("people",people);
        }
        if(action.equals("today")||action.equals("report")){
            List<JSONObject> items=new ArrayList<>();double total=0;String month=r.optString("month");
            if(action.equals("report"))PeerConfig.require(month.matches("\\d{4}-(0[1-9]|1[0-2])"),"Mes inválido.");
            for(JSONObject m:db.rows("meals"))if(m.getString("planta").equals(plant)&&(action.equals("today")?m.getString("fecha").equals(today):m.getString("fecha").startsWith(month+"-"))){items.add(m);total+=m.getDouble("precio");}
            if(action.equals("today")){JSONArray recent=new JSONArray();for(int i=items.size()-1;i>=Math.max(0,items.size()-30);i--)recent.put(items.get(i));return ok().put("count",items.size()).put("total",total).put("items",recent).put("synced",db.applied());}
            String[] cols={"fecha","hora","persona","nombre","comida","planta","operador","precio","centroCosto"};StringBuilder csv=new StringBuilder("\uFEFF"+String.join(",",cols));
            for(JSONObject row:items){csv.append("\r\n");for(int i=0;i<cols.length;i++){if(i>0)csv.append(',');String v=row.optString(cols[i]);if(v.matches("^[=+@-].*"))v="'"+v;csv.append('"').append(v.replace("\"","\"\"")).append('"');}}
            return ok().put("count",items.size()).put("csv",csv.toString());
        }
        if(action.equals("scan")){
            String qr=r.optString("qr"),meal=r.optString("meal");
            PeerConfig.require((qr.matches("SB1:[a-f0-9]{64}")||PeerCards.format(qr))&&MEALS.contains(meal),"QR o comida inválidos.");
            String hash=PeerConfig.hash(qr);JSONObject person=null;
            if(PeerCards.format(qr))person=PeerCards.resolve(db,config,qr,when);
            else for(JSONObject p:db.rows("people"))if(p.optString("qrHash").equals(hash)){person=p;break;}
            PeerConfig.require(person!=null,"QR revocado o desconocido.");JSONObject p=person;
            PeerConfig.require(p.optBoolean("activo"),"Persona inactiva.");
            PeerConfig.require(p.optString("vence").isEmpty()||p.getString("vence").compareTo(today)>=0,"Autorización vencida.");
            PeerConfig.require(Arrays.asList(p.optString("comidas").split(",")).contains(meal),"Comida no autorizada.");
            PeerConfig.require(!p.optString("tipo").equals("VISITA")||p.getString("planta").equals(plant),"Visita autorizada en otra planta.");
            String key=p.getString("id")+"|"+today+"|"+meal;JSONObject previous=db.get("meals",key);
            if(previous!=null)return ok().put("status","duplicate").put("message","Ya consumió esta comida.").put("person",p.getString("nombre")).put("plant",previous.getString("planta")).put("time",previous.getString("hora"));
            JSONObject rate=null;JSONArray rates=config.seed.getJSONArray("rates");
            for(int i=0;i<rates.length();i++){JSONObject x=rates.getJSONObject(i);if(x.getString("desde").compareTo(today)<=0&&(rate==null||x.getString("desde").compareTo(rate.getString("desde"))>0))rate=x;}
            PeerConfig.require(rate!=null&&Double.isFinite(rate.optDouble(meal))&&rate.optDouble(meal)>=0,"Falta una tarifa válida.");
            String time=Instant.ofEpochMilli(when).atZone(ZONE).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            JSONObject record=new JSONObject().put("clave",key).put("solicitud",r.getString("requestId")).put("persona",p.getString("id")).put("nombre",p.getString("nombre")).put("fecha",today).put("comida",meal).put("planta",plant).put("operador",op.getString("name")).put("hora",time).put("precio",rate.getDouble(meal)).put("centroCosto",p.optString("centroCosto")).put("qrHash",hash);
            db.save("meals",key,record);
            return ok().put("status","accepted").put("message","Entrega confirmada por la red de teléfonos.").put("person",p.getString("nombre")).put("plant",plant).put("time",time);
        }
        if(action.equals("issue")){
            PeerConfig.require(admin,"Solo administración puede emitir QR de trabajadores.");JSONObject p=db.get("people",r.optString("personId"));
            PeerConfig.require(p!=null&&p.optBoolean("activo")&&p.getString("planta").equals(plant),"Persona no encontrada.");
            String qr=r.getString("newQr");p.put("qrHash",PeerConfig.hash(qr));db.save("people",p.getString("id"),p);
            return ok().put("qr",qr).put("person",p.getString("nombre")).put("detail","SAP "+p.optString("sap")+" · "+plant);
        }
        if(action.equals("visitor")){
            String name=r.optString("name").trim(),document=r.optString("document").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""),cost=r.optString("costCenter").trim(),expires=r.optString("expires");
            PeerConfig.require(name.length()>=3&&name.length()<=100&&document.length()>=5&&document.length()<=60&&!cost.isEmpty()&&cost.length()<=50,"Completa nombre, documento y centro de costo.");
            LocalDate end;try{end=LocalDate.parse(expires);}catch(Exception e){throw new IllegalArgumentException("Fecha inválida.");}
            LocalDate start=LocalDate.parse(today);PeerConfig.require(!end.isBefore(start)&&!end.isAfter(start.plusDays(30)),"La visita puede durar de hoy hasta 30 días.");
            List<String> meals=Arrays.asList(r.optString("meals").split(","));PeerConfig.require(!meals.isEmpty()&&MEALS.containsAll(meals),"Selecciona comidas válidas.");
            String id="VIS-"+PeerConfig.hash(document);JSONObject existing=db.get("people",id);
            PeerConfig.require(existing==null||admin||existing.getString("planta").equals(plant),"Visitante registrado en otra planta.");
            String qr=r.getString("newQr");JSONObject p=new JSONObject().put("id",id).put("sap","").put("nombre",name).put("planta",plant).put("centroCosto",cost).put("tipo","VISITA").put("activo",true).put("qrHash",PeerConfig.hash(qr)).put("vence",expires).put("comidas",String.join(",",meals));db.save("people",id,p);
            return ok().put("qr",qr).put("person",name).put("detail","Visita · "+plant+" · hasta "+expires);
        }
        throw new IllegalArgumentException("Configura los cuatro encargados mediante sus archivos de red.");
    }
}
