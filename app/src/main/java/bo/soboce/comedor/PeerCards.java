package bo.soboce.comedor;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Signed credentials can be issued locally; only the replicated ledger authorizes a meal. */
final class PeerCards {
    private static final List<String> MEALS=Meals.ALL;
    static boolean local(String action){return Arrays.asList("session","people","today","report","conflicts","issue","visitor").contains(action);}
    static boolean format(String qr){return qr!=null&&qr.length()<=2048&&qr.matches("SB[23]:[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");}

    static JSONObject request(PeerDatabase db,PeerConfig config,JSONObject request,long now) throws Exception {
        try {
            String action=request.getString("action");
            if(!action.equals("issue")&&!action.equals("visitor"))return new MealLedger(db,config).read(request);
            synchronized(db){return issue(db,config,request,now);}
        }catch(IllegalArgumentException e){return new JSONObject().put("ok",false).put("definitive",true).put("error",e.getMessage());}
    }
    private static boolean knownPlant(PeerConfig config,String plant) throws Exception {
        JSONArray plants=config.seed.getJSONArray("plants");for(int i=0;i<plants.length();i++)if(plants.getString(i).equals(plant))return true;return false;
    }
    private static void issuerPlant(PeerConfig config,int issuer,String plant) throws Exception {
        PeerConfig.require((issuer>=0&&issuer<4||issuer==-1&&config.shared)&&knownPlant(config,plant),"Planta o encargado inválidos.");
        JSONObject op=config.operator(issuer);
        PeerConfig.require(op.getString("role").equals("admin")||op.getString("plant").equals(plant),"Solo puedes emitir tarjetas de tu planta.");
    }
    private static JSONObject issue(PeerDatabase db,PeerConfig config,JSONObject r,long now) throws Exception {
        String plant=r.optString("plant"),name,detail;issuerPlant(config,config.shared?-1:config.node,plant);
        JSONObject body=new JSONObject().put("v",config.shared?3:2).put("cluster",config.cluster).put("issuer",config.node).put("plant",plant).put("issued",now);
        if(r.getString("action").equals("issue")) {
            JSONObject p=db.get("people",r.optString("personId"));
            PeerConfig.require(p!=null&&p.optBoolean("activo")&&p.optString("planta").equals(plant)&&!p.optString("tipo").equals("VISITA"),"Trabajador activo no encontrado en esta planta.");
            body.put("kind","employee").put("id",p.getString("id"));name=p.getString("nombre");detail=PersonIdentity.label(p)+" · "+plant;
        } else {
            name=r.optString("name").trim();String document=r.optString("document").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""),cost=r.optString("costCenter").trim(),expires=r.optString("expires"),meals=r.optString("meals");
            PeerConfig.require(name.length()>=3&&name.length()<=100&&document.length()>=5&&document.length()<=60&&!cost.isEmpty()&&cost.length()<=50,"Completa nombre, documento y centro de costo.");
            PersonIdentity.requireNewVisitor(db.rows("people"),document);
            visitorDates(expires,now);validMeals(meals);
            body.put("kind","visitor").put("id","VIS-"+PeerConfig.hash(document)).put("name",name).put("cost",cost).put("expires",expires).put("meals",meals);
            detail="Visita · "+plant+" · hasta "+expires;
        }
        if(config.shared)body.put("device",config.device).put("operator",config.operator(-1));
        String data=body.toString();String qr=(config.shared?"SB3:":"SB2:")+Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(config.shared?config.mac("card|"+data).getBytes(StandardCharsets.US_ASCII):Base64.getDecoder().decode(config.sign("card|"+config.cluster+"|"+data)));
        PeerConfig.require(format(qr),"Los datos de la tarjeta son demasiado largos.");
        return MealLedger.ok().put("qr",qr).put("person",name).put("detail",detail).put("notice","Tarjeta creada en este teléfono. Las otras tarjetas de esta persona siguen vigentes y identifican a la misma persona. El control usa los consumos conocidos por cada teléfono.");
    }
    private static void visitorDates(String expires,long issued) {
        try{
            LocalDate end=LocalDate.parse(expires),start=LocalDate.parse(MealLedger.day(issued));
            PeerConfig.require(!end.isBefore(start)&&!end.isAfter(start.plusDays(30)),"La visita puede durar de hoy hasta 30 días.");
        }catch(java.time.DateTimeException e){throw new IllegalArgumentException("Fecha de vencimiento inválida.");}
    }
    private static void validMeals(String meals){List<String> list=Arrays.asList(meals.split(",",-1));PeerConfig.require(!list.isEmpty()&&MEALS.containsAll(list),"Selecciona comidas válidas.");}

    static JSONObject resolve(PeerDatabase db,PeerConfig config,String qr,long when) throws Exception {
        try{return resolveChecked(db,config,qr,when);}
        catch(JSONException e){throw new IllegalArgumentException("La tarjeta QR está incompleta.");}
    }
    private static JSONObject resolveChecked(PeerDatabase db,PeerConfig config,String qr,long when) throws Exception {
        PeerConfig.require(format(qr),"QR no reconocido.");JSONObject card;
        try {
            String[] parts=qr.substring(4).split("\\.",-1);String body=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            card=new JSONObject(body);
            String signature=Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(parts[1]));
            if(qr.startsWith("SB3:")){
                PeerConfig.require(config.shared && card.getInt("v")==3 && config.cluster.equals(card.getString("cluster")) && PeerConfig.equal(config.mac("card|"+body),new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.US_ASCII)),"QR alterado o de otra red.");
                JSONObject op=card.getJSONObject("operator");PeerConfig.require(knownPlant(config,card.getString("plant")) && (op.optString("role").equals("admin") || op.optString("plant").equals(card.getString("plant"))),"Planta no autorizada.");
            }else {PeerConfig.require(card.getInt("v")==2&&config.cluster.equals(card.getString("cluster"))&&config.verify(card.getInt("issuer"),"card|"+config.cluster+"|"+body,signature),"QR alterado o de otra red.");
                issuerPlant(config,card.getInt("issuer"),card.getString("plant"));
            }
            PeerConfig.require(card.getLong("issued")>=0&&card.getLong("issued")<=when+120000,"Revisa la fecha del teléfono que emitió el QR.");
        }catch(Exception e){throw new IllegalArgumentException("QR inválido, alterado o emitido con una fecha incorrecta.");}
        String id=card.getString("id"),kind=card.optString("kind");
        JSONObject existing=db.get("people",id);
        if(kind.equals("employee")) {
            PeerConfig.require(existing!=null&&!existing.optString("tipo").equals("VISITA")&&existing.optString("planta").equals(card.getString("plant")),"Trabajador desconocido o tarjeta de otra planta de origen.");
            return existing;
        }
        PeerConfig.require(kind.equals("visitor")&&id.matches("VIS-[a-f0-9]{64}"),"Identidad de visitante inválida.");
        PeerConfig.require(existing==null||existing.optBoolean("activo"),"Visitante inactivo.");
        String expires=card.getString("expires"),meals=card.getString("meals"),name=card.getString("name"),cost=card.getString("cost");
        visitorDates(expires,card.getLong("issued"));validMeals(meals);
        PeerConfig.require(name.length()>=3&&name.length()<=100&&!cost.isEmpty()&&cost.length()<=50,"Datos de visitante inválidos.");
        // The document hash is stable across issuers/cards; the meal key remains global.
        return new JSONObject().put("id",id).put("sap","").put("nombre",name).put("planta",card.getString("plant")).put("centroCosto",cost).put("tipo","VISITA").put("activo",true).put("vence",expires).put("comidas",meals);
    }
}
